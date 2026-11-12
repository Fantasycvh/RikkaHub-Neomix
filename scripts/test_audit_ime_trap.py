"""audit_ime_trap.py 的回归用例（不依赖真实仓库内容，全部在临时目录里造 fixture）。"""
import json
import tempfile
import unittest
from pathlib import Path

import audit_ime_trap as audit


class ImeTrapAuditTest(unittest.TestCase):
    def setUp(self):
        self.directory = tempfile.TemporaryDirectory()
        self.addCleanup(self.directory.cleanup)
        self.root = Path(self.directory.name)
        (self.root / "app" / "src" / "main").mkdir(parents=True)

    def source_file(self, code, path="ui/pages/Fixture.kt"):
        target = self.root / "app" / "src" / "main" / path
        target.parent.mkdir(parents=True, exist_ok=True)
        target.write_text(code, encoding="utf-8")

    def scan(self):
        return audit.scan(str(self.root))

    def write_exceptions(self, entries):
        (self.root / "scripts").mkdir(parents=True, exist_ok=True)
        (self.root / "scripts" / "ime_trap_exceptions.json").write_text(
            json.dumps({"version": 1, "exceptions": entries}), encoding="utf-8"
        )

    # ---- A：Lazy item key 内容哈希 ----
    def test_hash_code_key_in_lazy_items_is_blocking(self):
        self.source_file("""
fun Fixture(list: List<Row>) {
    LazyColumn {
        items(list, key = { it.text.hashCode() }) { row -> Text(row.text) }
    }
}
""")
        hash_hits, *_ = self.scan()
        self.assertEqual(len(hash_hits), 1)

    def test_stable_id_key_is_clean(self):
        self.source_file("""
fun Fixture(list: List<Row>) {
    LazyColumn {
        items(list, key = { it.id }) { row -> Text(row.text) }
    }
}
""")
        hash_hits, *_ = self.scan()
        self.assertEqual(hash_hits, [])

    # ---- A'：TextField 内联在真 Lazy item / CardGroup 豁免 ----
    def test_text_field_inline_in_item_block_is_blocking(self):
        self.source_file("""
fun Fixture() {
    LazyColumn {
        item {
            OutlinedTextField(value = "x", onValueChange = {})
        }
    }
}
""")
        _, item_hits, *_ = self.scan()
        self.assertEqual(len(item_hits), 1)

    def test_card_group_item_dsl_is_not_a_lazy_item(self):
        self.source_file("""
fun Fixture() {
    LazyColumn {
        item {
            CardGroup {
                item(
                    headlineContent = { Text("标题") },
                    supportingContent = { Column { OutlinedTextField(value = "x", onValueChange = {}) } },
                )
            }
        }
    }
}
""")
        _, item_hits, *_ = self.scan()
        # 外层的 Lazy item 仍然命中（这就是真实存在的规则 2 存量债），
        # 但 CardGroup 的 item(...) 不应被当成 lazy item 额外计数。
        self.assertEqual(len(item_hits), 1)

    def test_a_prime_allowance_only_exempts_known_files(self):
        self.source_file("""
fun Fixture() {
    LazyColumn {
        item { OutlinedTextField(value = "x", onValueChange = {}) }
    }
}
""", path="ui/pages/Known.kt")
        self.write_exceptions([{
            "path": "app/src/main/ui/pages/Known.kt",
            "class": "A'",
            "count": 1,
            "reason": "fixture",
        }])
        _, item_hits, *_ = self.scan()
        blocking, exempted = audit.split_a_prime(str(self.root), item_hits)
        self.assertEqual(blocking, [])
        self.assertEqual(len(exempted), 1)

    def test_a_prime_allowance_does_not_leak_to_other_files(self):
        self.source_file("""
fun Fixture() {
    LazyColumn {
        item { OutlinedTextField(value = "x", onValueChange = {}) }
    }
}
""", path="ui/pages/New.kt")
        self.write_exceptions([{
            "path": "app/src/main/ui/pages/Known.kt",
            "class": "A'",
            "count": 1,
            "reason": "fixture",
        }])
        _, item_hits, *_ = self.scan()
        blocking, exempted = audit.split_a_prime(str(self.root), item_hits)
        self.assertEqual(len(blocking), 1)
        self.assertEqual(exempted, [])

    # ---- D：imePadding 顺序 ----
    def test_ime_padding_inside_vertical_scroll_is_flagged(self):
        self.source_file("""
fun Fixture() {
    var text by remember { mutableStateOf("") }
    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(innerPadding)
            .imePadding(),
    ) { OutlinedTextField(value = text, onValueChange = { text = it }) }
}
""")
        *_, inset_warns, echo_warns = self.scan()
        self.assertEqual(len(inset_warns), 1)
        self.assertEqual(len(echo_warns), 0)

    def test_ime_padding_before_vertical_scroll_is_clean(self):
        self.source_file("""
fun Fixture() {
    var text by remember { mutableStateOf("") }
    Column(
        modifier = Modifier
            .fillMaxSize()
            .imePadding()
            .verticalScroll(rememberScrollState())
            .padding(innerPadding),
    ) { OutlinedTextField(value = text, onValueChange = { text = it }) }
}
""")
        *_, inset_warns, echo_warns = self.scan()
        self.assertEqual(inset_warns, [])
        self.assertEqual(echo_warns, [])

    # ---- E：写穿回灌启发式 ----
    def test_write_through_echo_field_is_flagged(self):
        self.source_file("""
fun Fixture() {
    OutlinedTextField(
        value = assistant.name,
        onValueChange = { onUpdate(assistant.copy(name = it)) },
    )
}
""")
        *_, echo_warns = self.scan()
        self.assertEqual(len(echo_warns), 1)

    def test_state_authority_field_is_clean(self):
        self.source_file("""
fun Fixture(state: TextFieldState) {
    OutlinedTextField(state = state)
}
""")
        *_, echo_warns = self.scan()
        self.assertEqual(echo_warns, [])

    def test_stable_box_editing_state_is_not_flagged(self):
        self.source_file("""
fun Fixture() {
    var text by remember { mutableStateOf("") }
    OutlinedTextField(value = text, onValueChange = { text = it })
}
""")
        *_, echo_warns = self.scan()
        self.assertEqual(echo_warns, [])


if __name__ == "__main__":
    unittest.main()
