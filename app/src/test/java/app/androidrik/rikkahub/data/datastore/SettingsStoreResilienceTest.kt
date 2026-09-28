package app.androidrik.rikkahub.data.datastore

import androidx.datastore.core.CorruptionException
import androidx.datastore.core.IOException
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/**
 * 设置读取失败**不得**退化为「用户数据是空的」（上游 `70b382f5`）。
 *
 * 原实现是 `catch { if (IOException) emit(emptyPreferences()) }`——读失败时给出的空
 * Preferences 会被 `update()` 当成用户数据整份写回，把全部设置抹成默认值，
 * 用户表现是「某次启动后设置全丢」。修法分两层：
 * - 瞬时 IO 错误：退避重试，仍失败**向上抛**（不产出空值）
 * - 文件真损坏：corruptionHandler 先**备份原文件**再重建为空（唯一允许回退空的路径）
 */
class SettingsStoreResilienceTest {

    @get:Rule
    val tempFolder = TemporaryFolder()

    // ---- 第一层：读失败的重试策略（纯函数） ----

    @Test
    fun `transient io error retries until the cap`() {
        val io = IOException("disk busy")
        // 前 READ_MAX_RETRIES 次重试（attempt 从 0 起）
        assertTrue(shouldRetrySettingsRead(io, attempt = 0))
        assertTrue(shouldRetrySettingsRead(io, attempt = 2))
        // 到达上限后不再重试 ⇒ 向上抛，绝不产出空配置
        assertFalse(shouldRetrySettingsRead(io, attempt = 3))
        assertFalse(shouldRetrySettingsRead(io, attempt = 10))
    }

    @Test
    fun `corruption is never retried`() {
        // 损坏交给 corruptionHandler（备份 + 重建），重试毫无意义
        assertFalse(shouldRetrySettingsRead(CorruptionException("bad file", null), attempt = 0))
    }

    @Test
    fun `unexpected errors are not retried`() {
        assertFalse(shouldRetrySettingsRead(IllegalStateException("boom"), attempt = 0))
    }

    // ---- 第二层：真损坏 ⇒ 备份原文件 + 重建为空 ----

    @Test
    fun `corrupt settings file is backed up and store recovers`() = runBlocking {
        val file = File(tempFolder.root, "settings.preferences_pb")
        val garbage = byteArrayOf(0x1, 0x2, 0x3, 0x4, 0x5) // 不是合法的 preferences 序列化
        file.writeBytes(garbage)
        // 放一个「用户数据」键，验证损坏后它确实丢失（损坏必然丢数据，重点是能恢复可用）
        val launchCountKey = androidx.datastore.preferences.core.intPreferencesKey("launch_count")

        val store = createSettingsDataStore(file)
        val prefs = store.data.first() // 不应抛异常（由 corruptionHandler 兜住）

        // 原文件必须留一份备份供人工抢救，且内容与原损坏内容一致
        val backups = tempFolder.root.listFiles()?.filter { it.name.contains(".corrupt-") }.orEmpty()
        assertEquals("原文件必须留一份备份", 1, backups.size)
        assertTrue("备份内容应与原损坏文件一致", garbage.contentEquals(backups.first().readBytes()))

        // 恢复后是「迁移过的全新状态」：用户键已不存在，但库可用（迁移会写入 data_version 等）
        assertNull("损坏后用户数据不可恢复（可预期）", prefs[launchCountKey])
        assertNotNull(
            "重建后的空库应立即跑完迁移（data_version 已写入 ⇒ store 恢复可用）",
            prefs[SettingsStore.VERSION],
        )
    }

    @Test
    fun `valid settings file round-trips untouched`() = runBlocking {
        val file = File(tempFolder.root, "settings.preferences_pb")
        val store = createSettingsDataStore(file)

        // 正常写入再读出：既验证未误触损坏处理，也守住「文件位置/格式未变」的兼容契约
        store.updateData { prefs ->
            prefs.toMutablePreferences().apply {
                set(androidx.datastore.preferences.core.intPreferencesKey("launch_count"), 7)
            }
        }
        val read = store.data.first()
        assertEquals(
            7,
            read[androidx.datastore.preferences.core.intPreferencesKey("launch_count")],
        )
        val backups = tempFolder.root.listFiles()?.filter { it.name.contains(".corrupt-") }.orEmpty()
        assertTrue("正常写入不应产生损坏备份", backups.isEmpty())
    }
}
