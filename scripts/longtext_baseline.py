"""Offline P0 long-text device baseline. Python 3.10+, JDK/Android SDK, no pip dependencies."""
from __future__ import annotations

import argparse
from collections import defaultdict
from datetime import datetime, timezone
import hashlib
import json
import math
from pathlib import Path
import shutil
import statistics
import subprocess
import sys

ROOT = Path(__file__).resolve().parents[1]
PACKAGE = "app.androidrik.rikkahub.longtextbaseline.debug"
TEST_CLASS = "app.androidrik.rikkahub.perf.LongTextBaselineTest"


def command(args, *, check=True):
    return subprocess.run([str(a) for a in args], cwd=ROOT, capture_output=True, check=check)


def percentile(values, percent):
    """Nearest-rank percentile, also used for per-run frame and latency summaries."""
    ordered = sorted(values)
    return ordered[max(0, math.ceil(len(ordered) * percent / 100) - 1)] if ordered else None


def summarize_row(row):
    frames = [f for f in row["frames"] if not f["first_draw"] and f["total_ns"] >= 0]
    deadlines = [f for f in frames if f["deadline_ns"] > 0]
    dropped = sum(f["dropped_callbacks"] for f in row["frames"])
    issues = []
    if len(frames) < 10:
        issues.append("too_few_frames")
    if len(deadlines) != len(frames):
        issues.append("missing_frame_deadlines")
    if dropped:
        issues.append("frame_callback_loss")
    if row["thermal_status"] >= 2:
        issues.append("thermal_throttling_risk")
    before = row["memory_before"]["art"]
    after = row["memory_after"]["art"]
    def delta(name):
        if name not in before or name not in after:
            return None
        return int(after[name]) - int(before[name])
    total_ms = [f["total_ns"] / 1e6 for f in frames]
    updates = row.get("stream_update_at_ns", [])
    return {
        "kind": row["kind"], "length": row["length_codepoints"], "scenario": row["scenario"],
        "iteration": row["iteration"], "animation": row["animation"],
        "frame_count": len(frames), "frame_p95_ms": percentile(total_ms, 95),
        "frame_p99_ms": percentile(total_ms, 99),
        "layout_p95_ms": percentile([f["layout_ns"] / 1e6 for f in frames], 95),
        "deadline_miss_percent": 100 * sum(f["total_ns"] >= f["deadline_ns"] for f in deadlines) / len(deadlines) if deadlines else None,
        "commit_to_draw_p95_ms": percentile(row["input_connection_commit_to_draw_ms"], 95),
        "selection_to_draw_p95_ms": percentile(row["selection_to_draw_ms"], 95),
        "sampled_peak_java_heap_mib": row["sampled_peak_java_heap_bytes"] / 2**20,
        "pss_before_mib": row["memory_before"]["pss_kib"] / 1024,
        "pss_after_mib": row["memory_after"]["pss_kib"] / 1024,
        "gc_count_delta": delta("art.gc.gc-count"),
        "allocated_bytes_delta": delta("art.gc.bytes-allocated"),
        "stream_update_interval_p95_ms": percentile([(b-a)/1e6 for a, b in zip(updates, updates[1:])], 95),
        "callback_drops": dropped, "quality_issues": issues,
    }


def aggregate(rows):
    groups = defaultdict(list)
    for row in rows:
        summary = summarize_row(row)
        groups[(summary["kind"], summary["length"], summary["scenario"], summary["animation"])].append(summary)
    result = []
    for key, values in sorted(groups.items()):
        item = dict(zip(("kind", "length", "scenario", "animation"), key))
        item["repetitions"] = len(values)
        item["quality_issues"] = sorted({issue for value in values for issue in value["quality_issues"]})
        for name in values[0]:
            if name in item or name == "iteration":
                continue
            numbers = [value[name] for value in values if value[name] is not None]
            item[name] = statistics.median(numbers) if numbers else None
        result.append(item)
    return result


def load_rows(path):
    return [json.loads(line) for line in Path(path).read_text(encoding="utf-8").splitlines() if line.strip()]


def validate_complete_run(rows, metadata):
    params = metadata["parameters"]
    expected = {(kind, int(size), scenario, iteration, params["animation"] == "true")
                for kind in params["kinds"].split(',') for size in params["sizes"].split(',')
                for scenario in params["scenarios"].split(',') for iteration in range(params["iterations"])}
    actual = {(r["kind"], r["length_codepoints"], r["scenario"], r["iteration"], r["animation"]) for r in rows}
    if len(actual) != len(rows) or actual != expected or len(rows) != metadata["expected_rows"]:
        raise ValueError("Incomplete or duplicate baseline matrix")


def write_report(directory, rows):
    summary = aggregate(rows)
    (directory / "summary.json").write_text(json.dumps(summary, indent=2, ensure_ascii=False), encoding="utf-8")
    lines = ["# P0 long-text baseline", "", "Debug production components, offline replay; not a release Macrobenchmark or real API/IME composition measurement.", "",
             "Values are medians of per-repetition metrics. Frame percentiles exclude first-draw frames; misses use each frame's Android deadline. PSS is endpoint-only; heap peaks are sparsely sampled.", "",
             "| Kind | Chars | Scenario | Repeats | Frame P95 ms | P99 ms | Miss % | Commit→draw P95 ms | Quality |",
             "|---|---:|---|---:|---:|---:|---:|---:|---|"]
    def display(value):
        return "N/A" if value is None else f"{value:.2f}"
    for item in summary:
        lines.append(f"| {item['kind']} | {item['length']} | {item['scenario']} | {item['repetitions']} | "
                     f"{display(item['frame_p95_ms'])} | {display(item['frame_p99_ms'])} | "
                     f"{display(item['deadline_miss_percent'])} | {display(item['commit_to_draw_p95_ms'])} | "
                     f"{', '.join(item['quality_issues']) or 'ok'} |")
    (directory / "report.md").write_text("\n".join(lines) + "\n", encoding="utf-8")
    return summary


def run(args):
    if args.current_handoff_driver and not args.reference_run:
        raise ValueError("--current-handoff-driver requires --reference-run")
    package = "app.androidrik.rikkahub.debug" if args.shared_package else PACKAGE
    adb = args.adb or shutil.which("adb")
    if not adb:
        raise ValueError("adb not found; pass --adb")
    devices = command([adb, "devices"]).stdout.decode().splitlines()[1:]
    connected = [line.split()[0] for line in devices if len(line.split()) > 1 and line.split()[1] == "device"]
    serial = args.serial or (connected[0] if len(connected) == 1 else None)
    if not serial or serial not in connected:
        raise ValueError("Select one connected authorized device with --serial")
    adb_cmd = [adb, "-s", serial]
    run_id = args.run_id or datetime.now(timezone.utc).strftime("p0_%Y%m%dT%H%M%SZ")
    if not run_id.replace("_", "").replace("-", "").isalnum():
        raise ValueError("run-id may only contain letters, digits, underscores and hyphens")
    directory = ROOT / "_measure" / "longtext" / run_id
    directory.mkdir(parents=True, exist_ok=False)
    metadata = {"run_id": run_id, "git_revision": command(["git", "rev-parse", "HEAD"]).stdout.decode().strip(),
                "utc": datetime.now(timezone.utc).isoformat(), "serial": serial,
                "parameters": {k: v for k, v in vars(args).items() if k != "func"}}
    metadata["process_policy"] = "single_process" if args.single_process else "fresh_process_per_fixture"
    subruns = []
    patch = command(["git", "diff", "--binary", "HEAD"]).stdout
    (directory / "source.patch").write_bytes(patch)
    metadata["patch_sha256"] = hashlib.sha256(patch).hexdigest()
    untracked = command(["git", "ls-files", "--others", "--exclude-standard"]).stdout.decode().splitlines()
    for name in untracked:
        target = directory / "untracked-source" / name
        target.parent.mkdir(parents=True, exist_ok=True)
        shutil.copy2(ROOT / name, target)
    for label, shell in {
        "device": ["getprop", "ro.build.fingerprint"], "screen": ["wm", "size"],
        "density": ["wm", "density"], "font_scale": ["settings", "get", "system", "font_scale"], "battery_before": ["dumpsys", "battery"],
        "thermal_before": ["dumpsys", "thermalservice"], "ime": ["settings", "get", "secure", "default_input_method"],
    }.items():
        (directory / f"{label}.txt").write_bytes(command(adb_cmd + ["shell"] + shell).stdout)
    try:
        if not args.skip_build and not args.reference_run:
            gradle = ROOT / ("gradlew.bat" if sys.platform == "win32" else "gradlew")
            with (directory / "build.log").open("wb") as log:
                subprocess.run([str(gradle), ":app:assembleDebug", ":app:assembleDebugAndroidTest",
                                "-PlongTextBaseline=" + ("shared" if args.shared_package else "true"), "--console=plain"], cwd=ROOT, stdout=log, stderr=subprocess.STDOUT, check=True)
        if args.reference_run:
            reference = Path(args.reference_run).resolve()
            reference_meta = json.loads((reference / "metadata.json").read_text(encoding="utf-8"))
            if reference_meta["parameters"]["shared_package"] != args.shared_package:
                raise ValueError("Reference package mode differs")
            apk, test_apk = reference / "apks/application.apk", reference / "apks/tests.apk"
            if hashlib.sha256(apk.read_bytes()).hexdigest() != reference_meta["apk_sha256"]:
                raise ValueError("Reference application APK hash differs")
            if hashlib.sha256(test_apk.read_bytes()).hexdigest() != reference_meta.get("test_apk_sha256"):
                raise ValueError("Reference test APK hash differs")
            metadata["target_artifact_origin"] = str(reference)
            metadata["target_git_revision"] = reference_meta.get("target_git_revision", reference_meta["git_revision"])
            if args.current_handoff_driver:
                if args.scenarios != "edit_handoff_full":
                    raise ValueError("Current driver with reference app supports only edit_handoff_full; other entry points may differ in ABI")
                test_listing = ROOT / "app/build/outputs/apk/androidTest/debug/output-metadata.json"
                current_test = json.loads(test_listing.read_text())
                if current_test["applicationId"] != package + ".test":
                    raise ValueError("Current test APK package differs; rebuild the matching baseline mode")
                test_apk = test_listing.parent / current_test["elements"][0]["outputFile"]
                metadata["driver_artifact_origin"] = "current_build_handoff_only"
        else:
            main = ROOT / "app/build/outputs/apk/debug/output-metadata.json"
            test = ROOT / "app/build/outputs/apk/androidTest/debug/output-metadata.json"
            main_meta, test_meta = json.loads(main.read_text()), json.loads(test.read_text())
            if main_meta["applicationId"] != package or test_meta["applicationId"] != package + ".test":
                raise ValueError("APK metadata is not isolated; rebuild with -PlongTextBaseline=true")
            apk = main.parent / next(e["outputFile"] for e in main_meta["elements"] if not e["filters"])
            test_apk = test.parent / test_meta["elements"][0]["outputFile"]
        metadata["apk_sha256"] = hashlib.sha256(apk.read_bytes()).hexdigest()
        metadata["test_apk_sha256"] = hashlib.sha256(test_apk.read_bytes()).hexdigest()
        artifacts = directory / "apks"
        artifacts.mkdir()
        shutil.copy2(apk, artifacts / "application.apk")
        shutil.copy2(test_apk, artifacts / "tests.apk")
        command(adb_cmd + ["install", "-r", apk])
        # Match AGP's test installation: target processes must be able to query their runner APK.
        command(adb_cmd + ["install", "-r", "-t", "--force-queryable", test_apk])
        batches = [(args.sizes, args.kinds)] if args.single_process else [
            (size, kind) for size in args.sizes.split(',') for kind in args.kinds.split(',')]
        with (directory / "instrumentation.log").open("w", encoding="utf-8") as log, (directory / "test-runtime.log").open("wb") as runtime:
            logger = subprocess.Popen(adb_cmd + ["logcat", "-T", "1", "-v", "threadtime", "-s",
                                                "AndroidRuntime:E", "TestRunner:I", "System.err:W", "libc:F", "DEBUG:F"], stdout=runtime, stderr=subprocess.STDOUT)
            try:
                metadata["instrumentation_exit_codes"] = []
                for index, (size, kind) in enumerate(batches):
                    command(adb_cmd + ["shell", "am", "force-stop", package])
                    child_id = f"{run_id}_{index}"
                    subruns.append(child_id)
                    instrument = adb_cmd + ["shell", "am", "instrument", "-w", "-r", "-e", "class", TEST_CLASS]
                    for name, value in {"sizes": size, "kinds": kind, "scenarios": args.scenarios,
                                        "iterations": args.iterations, "animation": args.animation, "reportRun": child_id}.items():
                        instrument += ["-e", name, str(value)]
                    instrument += [package + ".test/app.androidrik.rikkahub.perf.LongTextBaselineRunner"]
                    process = subprocess.Popen(instrument, cwd=ROOT, stdout=subprocess.PIPE, stderr=subprocess.STDOUT,
                                               text=True, encoding="utf-8", errors="replace")
                    for line in process.stdout:
                        log.write(line)
                        log.flush()
                        if "P0 " in line or "FAIL" in line or "Exception" in line:
                            print(line.rstrip(), flush=True)
                    metadata["instrumentation_exit_codes"].append(process.wait())
            finally:
                logger.terminate()
                logger.wait(timeout=10)
        # Retain test-process diagnostics only, excluding unrelated apps' System.err messages.
        runtime_path = directory / "test-runtime.log"
        runtime_lines = runtime_path.read_text(encoding="utf-8", errors="replace").splitlines()
        pids = {line.split()[2] for line in runtime_lines if len(line.split()) > 6 and
                ("recordBaseline(app.androidrik.rikkahub.perf.LongTextBaselineTest)" in line or
                 f"Process: {package}," in line or f"Cmdline: {package}" in line)}
        runtime_path.write_text("\n".join(line for line in runtime_lines if
                                         line.startswith("---") or (len(line.split()) > 3 and line.split()[2] in pids)) + "\n", encoding="utf-8")
    finally:
        chunks = []
        metadata["fixture_runs"] = subruns
        for child_id in subruns:
            raw = command(adb_cmd + ["exec-out", "run-as", package, "cat", f"files/longtext-baseline/{child_id}.jsonl"], check=False)
            if raw.returncode == 0 and raw.stdout.lstrip().startswith(b"{"):
                chunks.append(raw.stdout)
        rows = []
        if chunks:
            (directory / "raw.jsonl").write_bytes(b"".join(chunks))
            rows = load_rows(directory / "raw.jsonl")
            write_report(directory, rows)
        metadata["rows"] = len(rows)
        metadata["expected_rows"] = len(args.sizes.split(',')) * len(args.kinds.split(',')) * len(args.scenarios.split(',')) * args.iterations
        for name in ("battery", "thermalservice"):
            (directory / f"{name}_after.txt").write_bytes(command(adb_cmd + ["shell", "dumpsys", name], check=False).stdout)
        (directory / "metadata.json").write_text(json.dumps(metadata, indent=2, ensure_ascii=False), encoding="utf-8")
        print(f"Report: {directory}", flush=True)
    log = (directory / "instrumentation.log").read_text(encoding="utf-8")
    if metadata["rows"] != metadata["expected_rows"] or "FAILURES!!!" in log or "INSTRUMENTATION_FAILED" in log or log.count("OK (1 test)") != len(subruns):
        raise RuntimeError("Baseline incomplete; inspect preserved partial report and instrumentation.log")


def compare(args):
    before, after = load_rows(Path(args.before) / "raw.jsonl"), load_rows(Path(args.after) / "raw.jsonl")
    if not before or not after:
        raise ValueError("Cannot compare empty runs")
    manifests = [json.loads((Path(path) / "metadata.json").read_text(encoding="utf-8")) for path in (args.before, args.after)]
    for rows, manifest in zip((before, after), manifests):
        validate_complete_run(rows, manifest)
    policies = [manifest.get("process_policy", "legacy_single_process") for manifest in manifests]
    if policies[0] != policies[1]:
        raise ValueError("Process isolation policies differ")
    for name in ("schema_version", "driver_protocol", "build_fingerprint", "scope", "fixture_version", "font_scale", "density_dpi", "application_id"):
        if {r[name] for r in before} != {r[name] for r in after}:
            raise ValueError(f"Incompatible {name}")
    for filename in ("screen.txt", "density.txt", "ime.txt"):
        if (Path(args.before) / filename).read_bytes() != (Path(args.after) / filename).read_bytes():
            raise ValueError(f"Incompatible environment: {filename}")
    hashes = lambda rows: {(r["kind"], r["length_codepoints"], r["fixture_sha256"]) for r in rows}
    if hashes(before) != hashes(after):
        raise ValueError("Fixture hashes differ")
    rates = lambda rows: {(r["kind"], r["length_codepoints"], r["scenario"], r["animation"], round(r["refresh_rate_hz"])) for r in rows}
    if rates(before) != rates(after):
        raise ValueError("Observed display refresh rates differ")
    def keyed(rows):
        return {(r['kind'], r['length'], r['scenario'], r['animation']): r for r in aggregate(rows)}
    b, a = keyed(before), keyed(after)
    if b.keys() != a.keys():
        raise ValueError("Scenario/length/animation matrices differ")
    compared = 0
    for key in b:
        if b[key]["repetitions"] != a[key]["repetitions"]:
            raise ValueError(f"Repetition counts differ for {key}")
        if b[key]["quality_issues"] or a[key]["quality_issues"]:
            print(f"SKIP {key}: quality issues before={b[key]['quality_issues']}, after={a[key]['quality_issues']}")
            continue
        old, new = b[key]["frame_p95_ms"], a[key]["frame_p95_ms"]
        compared += 1
        print(f"{key}: P95 {old:.2f} -> {new:.2f} ms ({(new/old-1)*100:+.1f}%), "
              f"deadline miss {b[key]['deadline_miss_percent']:.2f}% -> {a[key]['deadline_miss_percent']:.2f}%")
    if not compared:
        raise ValueError("No comparable groups remain after quality checks")


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    commands = parser.add_subparsers(dest="command", required=True)
    execute = commands.add_parser("run")
    execute.add_argument("--adb")
    execute.add_argument("--serial")
    execute.add_argument("--run-id")
    execute.add_argument("--skip-build", action="store_true")
    execute.add_argument("--reference-run", help="Replay an archived run's application/test APK pair with new workload parameters")
    execute.add_argument("--current-handoff-driver", action="store_true", help="Use current driver against reference app, only for ABI-compatible edit_handoff_full")
    execute.add_argument("--shared-package", action="store_true", help="OEM fallback: regular debug package, isolated Application/DB/settings")
    execute.add_argument("--single-process", action="store_true", help="Diagnostic only: reuse a process across fixtures; not comparable with the default")
    execute.add_argument("--sizes", default="10000,50000,100000")
    execute.add_argument("--kinds", default="prose,dense,mixed")
    execute.add_argument("--scenarios", default="scroll,stream,edit_inline,edit_full")
    execute.add_argument("--iterations", type=int, default=3)
    execute.add_argument("--animation", choices=("true", "false"), default="true")
    execute.set_defaults(func=run)
    comparison = commands.add_parser("compare")
    comparison.add_argument("before")
    comparison.add_argument("after")
    comparison.set_defaults(func=compare)
    args = parser.parse_args()
    args.func(args)


if __name__ == "__main__":
    main()
