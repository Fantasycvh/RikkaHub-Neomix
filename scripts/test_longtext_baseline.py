"""Host-side measurement math and quality checks; no device required."""
import sys
sys.dont_write_bytecode = True
import unittest
from longtext_baseline import aggregate, percentile, summarize_row, validate_complete_run


def row():
    return {
        "kind": "prose", "length_codepoints": 10000, "scenario": "scroll", "iteration": 0,
        "animation": True, "thermal_status": 0,
        "frames": [{"first_draw": False, "total_ns": i * 1_000_000, "layout_ns": 1_000_000,
                    "deadline_ns": 10_000_000, "dropped_callbacks": 0} for i in range(1, 21)],
        "input_connection_commit_to_draw_ms": [], "selection_to_draw_ms": [],
        "memory_before": {"art": {"art.gc.gc-count": "2"}, "pss_kib": 1024},
        "memory_after": {"art": {"art.gc.gc-count": "5"}, "pss_kib": 2048},
        "sampled_peak_java_heap_bytes": 2**20,
    }


class MeasurementTest(unittest.TestCase):
    def test_nearest_rank_and_empty_values(self):
        self.assertEqual(19, percentile(list(range(1, 21)), 95))
        self.assertIsNone(percentile([], 95))

    def test_per_frame_deadline_includes_equality_and_excludes_first_draw(self):
        value = row()
        value["frames"].append({"first_draw": True, "total_ns": 999_000_000, "deadline_ns": 1,
                                "layout_ns": 0, "dropped_callbacks": 0})
        summary = summarize_row(value)
        self.assertEqual(19, summary["frame_p95_ms"])
        self.assertEqual(55, summary["deadline_miss_percent"])
        self.assertEqual(3, summary["gc_count_delta"])
        self.assertIsNone(summary["allocated_bytes_delta"])

    def test_missing_frames_are_not_reported_as_zero_jank(self):
        value = row()
        value["frames"] = []
        summary = summarize_row(value)
        self.assertIsNone(summary["deadline_miss_percent"])
        self.assertIn("too_few_frames", summary["quality_issues"])

    def test_callback_loss_and_thermal_risk_are_visible(self):
        value = row()
        value["frames"][0]["dropped_callbacks"] = 2
        value["thermal_status"] = 3
        self.assertEqual(["frame_callback_loss", "thermal_throttling_risk"], summarize_row(value)["quality_issues"])

    def test_repetitions_use_medians_of_run_metrics(self):
        first, second = row(), row()
        for frame in second["frames"]:
            frame["total_ns"] *= 3
        summary, = aggregate([first, second])
        self.assertEqual(2, summary["repetitions"])
        self.assertEqual(38, summary["frame_p95_ms"])

    def test_comparison_rejects_missing_and_duplicate_iterations(self):
        manifest = {"parameters": {"kinds": "prose", "sizes": "10000", "scenarios": "scroll",
                                  "iterations": 2, "animation": "true"}, "expected_rows": 2}
        first, second = row(), row()
        with self.assertRaisesRegex(ValueError, "Incomplete or duplicate"):
            validate_complete_run([first], manifest)
        with self.assertRaisesRegex(ValueError, "Incomplete or duplicate"):
            validate_complete_run([first, second], manifest)
        second["iteration"] = 1
        validate_complete_run([first, second], manifest)


if __name__ == "__main__":
    unittest.main()
