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

    @Test
    fun `V4 旧备份里的 rikkahub 搜索提供方被剔除且选中索引左移`() {
        val input = "{\"searchServices\":[" +
            "{\"type\":\"rikkahub\",\"id\":\"2f0d3a58-4b7c-4c1e-9a5f-3d0f4e6a7b81\"}," +
            "{\"type\":\"tavily\",\"id\":\"9c1b2a3d-4e5f-4a6b-8c7d-0e1f2a3b4c5d\"}]," +
            "\"searchServiceSelected\":1}"
        val result = SettingsJsonMigrator.migrate(input)
        // 子类已从 sealed 层删除 ⇒ 旧备份若留着它，decodeFromString<Settings> 会整份失败
        assertFalse(result.contains("rikkahub"))
        assertTrue(result.contains("\"tavily\""))
        // 选中项原为 tavily（旧索引 1），剔除前一项后必须左移到 0，否则会切到别的提供方
        assertTrue(result.contains("\"searchServiceSelected\":0"))
    }

    @Test
    fun `V4 只含 rikkahub 的旧备份剔除后为空数组并 clamp 索引`() {
        val input = "{\"searchServices\":[{\"type\":\"rikkahub\"}],\"searchServiceSelected\":3}"
        val result = SettingsJsonMigrator.migrate(input)
        assertFalse(result.contains("rikkahub"))
        assertTrue(result.contains("\"searchServices\":[]"))
        assertTrue(result.contains("\"searchServiceSelected\":0"))
    }

    @Test
    fun `V4 不含 rikkahub 的备份保持原样`() {
        val input = "{\"searchServices\":[{\"type\":\"tavily\"," +
            "\"id\":\"9c1b2a3d-4e5f-4a6b-8c7d-0e1f2a3b4c5d\"}],\"searchServiceSelected\":0}"
        val result = SettingsJsonMigrator.migrate(input)
        assertTrue(result.contains("\"tavily\""))
        assertTrue(result.contains("\"searchServiceSelected\":0"))
    }
}
