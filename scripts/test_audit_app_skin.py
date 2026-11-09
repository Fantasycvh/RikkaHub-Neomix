"""Regression checks for skin bypass detection, without modifying application sources."""
from pathlib import Path
import tempfile
import unittest
from unittest.mock import patch

import audit_app_skin as audit


class SkinCoverageAuditTest(unittest.TestCase):
    def setUp(self):
        self.directory = tempfile.TemporaryDirectory()
        self.addCleanup(self.directory.cleanup)
        self.root = Path(self.directory.name)
        self.source = patch.object(audit, "SOURCE", self.root)
        self.source.start()
        self.addCleanup(self.source.stop)

    def source_file(self, code, path="ui/pages/Fixture.kt"):
        target = self.root / path
        target.parent.mkdir(parents=True, exist_ok=True)
        target.write_text(code, encoding="utf-8")

    def test_material_aliases_and_fully_qualified_calls_cannot_bypass_audit(self):
        self.source_file("""
import androidx.compose.material3.Button as NativeAction
fun Fixture() {
    NativeAction(onClick = {}) {}
    androidx.compose.material3.TextButton(onClick = {}) {}
}
""")
        counts, _, _, findings = audit.scan()
        self.assertEqual(1, counts["Button", "raw"])
        self.assertEqual(1, counts["TextButton", "raw"])
        self.assertEqual(2, len(findings))

    def test_skin_alias_and_direct_and_qualified_calls_count_once(self):
        self.source_file("""
import app.androidrik.rikkahub.ui.components.ui.AppButton as Button
fun Fixture() {
    Button(onClick = {}) {}
    AppButton(onClick = {}) {}
    app.androidrik.rikkahub.ui.components.ui.AppButton(onClick = {}) {}
}
""")
        counts, _, _, findings = audit.scan()
        self.assertEqual(3, counts["Button", "skin"])
        self.assertEqual(0, counts["Button", "raw"])
        self.assertEqual([], findings)

    def test_quoted_examples_comments_and_function_declarations_are_not_calls(self):
        self.source_file('''
// Button() RoundedCornerShape(8.dp)
/* TextField() */
val example = "OutlinedTextField()"
val multiline = """Button()"""
fun AppButton() {}
fun Fixture() { AppButton() }
''')
        counts, _, _, findings = audit.scan()
        self.assertEqual(1, sum(counts.values()))
        self.assertEqual([], findings)

    def test_nested_multiline_shapes_keep_exact_expression_and_source_line(self):
        self.source_file("""fun Fixture() {
    val shape = RoundedCornerShape(
        topStart = if (selected) radius else 0.dp,
        bottomStart = radius,
    )
}
""")
        _, _, _, findings = audit.scan()
        self.assertEqual(1, len(findings))
        self.assertEqual(2, findings[0]["line"])
        self.assertEqual("RoundedCornerShape( topStart = if (selected) radius else 0.dp, bottomStart = radius, )",
                         findings[0]["expression"])

    def test_wrapper_delegates_are_not_counted_as_page_bypasses(self):
        self.source_file("fun AppButton() { Button() }", audit.WRAPPERS)
        counts, _, _, findings = audit.scan()
        self.assertEqual(0, sum(counts.values()))
        self.assertEqual([], findings)


if __name__ == "__main__":
    unittest.main()
