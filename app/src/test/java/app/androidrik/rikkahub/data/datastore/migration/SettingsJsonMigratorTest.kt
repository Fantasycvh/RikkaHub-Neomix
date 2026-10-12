package app.androidrik.rikkahub.data.datastore.migration

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SettingsJsonMigratorTest {

    @Test
    fun `旧包名 Avatar 完整类名迁移到新包名`() {
        val input = """{"displaySetting":{"userAvatar":{"type":"me.rerere.rikkahub.data.model.Avatar.Dummy"}}}"""
        val result = SettingsJsonMigrator.migrate(input)
        assertTrue(result.contains("app.androidrik.rikkahub.data.model.Avatar.Dummy"))
        assertFalse(result.contains("me.rerere."))
    }

    @Test
    fun `旧包名 mcpServers 完整类名经包名迁移后转短名`() {
        val input = """{"mcpServers":[{"type":"me.rerere.rikkahub.data.mcp.McpServerConfig.SseTransportServer"}]}"""
        val result = SettingsJsonMigrator.migrate(input)
        assertTrue(result.contains("\"sse\""))
        assertFalse(result.contains("me.rerere."))
    }

    @Test
    fun `旧包名 assistants UIMessagePart 完整类名转短名`() {
        val input = """{"assistants":[{"presetMessages":[{"parts":[{"type":"me.rerere.ai.ui.UIMessagePart.Text"}]}]}]}"""
        val result = SettingsJsonMigrator.migrate(input)
        assertTrue(result.contains("\"text\""))
        assertFalse(result.contains("me.rerere."))
    }

    @Test
    fun `私有开发版包名的多态类名迁移到当前命名空间`() {
        val input = """{"displaySetting":{"userAvatar":{"type":"app.fantasycvh.rikkahub.data.model.Avatar.Dummy"}}}"""
        val result = SettingsJsonMigrator.migrate(input)
        assertTrue(result.contains("app.androidrik.rikkahub.data.model.Avatar.Dummy"))
        assertFalse(result.contains("app.fantasycvh."))
    }

    @Test
    fun `私有开发版包名的文件路径迁移到当前 applicationId`() {
        val input = """{
          "a":"file:///data/user/0/app.fantasycvh.rikkahub/files/upload/a.jpg",
          "b":"/data/data/app.fantasycvh.rikkahub/files/workspaces/w/x.txt",
          "c":"file:///data/user/0/app.fantasycvh.rikkahub.debug/files/upload/b.jpg"
        }"""
        val result = SettingsJsonMigrator.migrate(input, "app.androidrik.rikkahub")
        assertFalse(result.contains("fantasycvh"))
        assertTrue(result.contains("file:///data/user/0/app.androidrik.rikkahub/files/upload/a.jpg"))
        assertTrue(result.contains("file:///data/user/0/app.androidrik.rikkahub/files/workspaces/w/x.txt"))
        assertTrue(result.contains("file:///data/user/0/app.androidrik.rikkahub/files/upload/b.jpg"))
    }

    @Test
    fun `新包名备份不受影响`() {
        val input = """{"displaySetting":{"userAvatar":{"type":"app.androidrik.rikkahub.data.model.Avatar.Dummy"}}}"""
        val result = SettingsJsonMigrator.migrate(input)
        assertTrue(result.contains("app.androidrik.rikkahub.data.model.Avatar.Dummy"))
        assertFalse(result.contains("me.rerere."))
    }
}
