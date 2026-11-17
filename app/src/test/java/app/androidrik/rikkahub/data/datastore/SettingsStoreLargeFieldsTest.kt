package app.androidrik.rikkahub.data.datastore

import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.core.stringPreferencesKey
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/**
 * 安全模式「清除超大设置」的机制测试（上游 `27976e4da`，#2333）。
 *
 * 超大世界书/助手导入会让设置 JSON 编码在落盘时 OOM、每次启动的第一次写入重现 ⇒
 * 循环闪退。安全模式的手段是：**不解码**盘上原始字符串长度找出超大字段，然后把
 * 对应 key 直接删掉（decode 侧 `?: 默认值` 即恢复默认）。
 *
 * `SettingsStore` 构造即启动设置流收集（依赖 Koin 的 PebbleEngine/AppDatabase），
 * JVM 单测无法实例化；这里直驱 `createSettingsDataStore` 验证同一套机制——
 * 纯函数 [collectLargeStoredFields] 与「删 key = 恢复默认」。
 */
class SettingsStoreLargeFieldsTest {

    @get:Rule
    val tempFolder = TemporaryFolder()

    @Test
    fun `detects oversized fields with raw length without decoding, sorted descending`() {
        val big = stringPreferencesKey("lorebooks")
        val bigger = stringPreferencesKey("assistants")
        val small = stringPreferencesKey("title_prompt")
        val unrelated = stringPreferencesKey("not_resettable") // 不在白名单 ⇒ 再大也不列
        val prefs = emptyPreferences().toMutablePreferences().apply {
            set(big, "x".repeat(1_500_000))
            set(bigger, "y".repeat(2_000_000))
            set(small, "keep")
            set(unrelated, "z".repeat(9_999_999))
        }
        val detected = collectLargeStoredFields(prefs, minChars = 1_000_000)
        // 从大到小；白名单外与低于阈值的都不出现
        assertEquals(listOf(bigger.name to 2_000_000, big.name to 1_500_000), detected)
    }

    @Test
    fun `threshold is inclusive and empty prefs yield nothing`() {
        val prefs = emptyPreferences().toMutablePreferences().apply {
            set(SettingsStore.LOREBOOKS, "x".repeat(1_000))
        }
        assertEquals(listOf(SettingsStore.LOREBOOKS.name to 1_000),
            collectLargeStoredFields(prefs, minChars = 1_000))
        assertTrue(collectLargeStoredFields(emptyPreferences(), minChars = 1).isEmpty())
    }

    @Test
    fun `removing the key on disk restores the default and keeps other fields`() = runBlocking {
        val file = File(tempFolder.root, "settings.preferences_pb")
        val job = SupervisorJob()
        try {
            val store = createSettingsDataStore(
                file, scope = CoroutineScope(Dispatchers.IO + job), storage = preferencesTestStorage(file),
            ) {}

            // 模拟「导入超大世界书后的落盘」：lorebooks 巨大，其余字段必须幸存
            val big = "y".repeat(500_000)
            store.edit { prefs ->
                prefs[SettingsStore.LOREBOOKS] = big
                prefs[SettingsStore.TITLE_PROMPT] = "kept"
            }
            val detected = collectLargeStoredFields(store.data.first(), minChars = 100_000)
            assertEquals(listOf(SettingsStore.LOREBOOKS.name to 500_000), detected)

            // 与 SettingsStore.resetStoredField 同一动作：删 key（而非写默认值）
            store.edit { prefs -> prefs.remove(SettingsStore.LOREBOOKS) }

            val after = store.data.first()
            assertNull("清除后 key 必须不存在（decode 侧 `?: 默认值` 即恢复默认）", after[SettingsStore.LOREBOOKS])
            assertEquals("其余字段不得被波及", "kept", after[SettingsStore.TITLE_PROMPT])
            assertTrue(collectLargeStoredFields(after, minChars = 100_000).isEmpty())
        } finally {
            job.cancelAndJoin()
        }

        // 重开库再读一次：删除已持久化（安全模式清完即脱险，重启不再回灌）
        val readerJob = SupervisorJob()
        try {
            val reopened = createSettingsDataStore(
                file, scope = CoroutineScope(Dispatchers.IO + readerJob), storage = preferencesTestStorage(file),
            ) {}
            val prefs = reopened.data.first()
            assertFalse(prefs.asMap().containsKey(SettingsStore.LOREBOOKS))
            assertEquals("kept", prefs[SettingsStore.TITLE_PROMPT])
        } finally {
            readerJob.cancelAndJoin()
        }
    }
}
