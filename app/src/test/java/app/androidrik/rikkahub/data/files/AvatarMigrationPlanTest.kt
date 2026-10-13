package app.androidrik.rikkahub.data.files

import app.androidrik.rikkahub.data.datastore.DisplaySetting
import app.androidrik.rikkahub.data.datastore.Settings
import app.androidrik.rikkahub.data.model.Assistant
import app.androidrik.rikkahub.data.model.Avatar
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.uuid.Uuid

/**
 * 头像迁移**判据**的 JVM 单测（上游 issue #2127 的存量部分）。
 *
 * 只管「哪些引用要搬、搬到哪」这个纯函数；真正的 rename / DataStore 写入走
 * `AvatarMigration.migrate`（依赖 Context 与 SettingsStore，JVM 够不着），
 * 所以把易错的部分——**判据与路径拼接**——抽出来在这里逐例钉死。
 *
 * 判据必须足够窄：只认 `/upload/` 这个路径段。宽一点（例如「只要在 filesDir 下」）
 * 就会把 `images/`（imggen 产物）或已经是 `avatars/` 的引用也列进搬迁计划，
 * 那会去动本不该动的文件。
 */
class AvatarMigrationPlanTest {

    private fun settings(
        avatars: List<Avatar> = emptyList(),
        userAvatar: Avatar = Avatar.Dummy,
    ): Settings = Settings(
        assistants = avatars.mapIndexed { index, avatar ->
            Assistant(id = Uuid.random(), name = "a$index", avatar = avatar)
        },
        displaySetting = DisplaySetting(userAvatar = userAvatar),
    )

    private fun fileUrl(path: String) = "file://$path"

    @Test
    fun `assistant avatar in upload is planned into avatars`() {
        val old = fileUrl("/data/user/0/app.androidrik.rikkahub/files/upload/ab12.jpg")
        val plan = AvatarMigration.planAvatarMigration(settings(avatars = listOf(Avatar.Image(old))))

        assertEquals(
            "upload/ 下的助手头像必须搬进 avatars/，且保持文件名不变",
            mapOf(
                old to fileUrl("/data/user/0/app.androidrik.rikkahub/files/avatars/ab12.jpg"),
            ),
            plan,
        )
    }

    @Test
    fun `user avatar in upload is planned too`() {
        val old = fileUrl("/data/user/0/app.androidrik.rikkahub/files/upload/user.png")
        val plan = AvatarMigration.planAvatarMigration(
            settings(userAvatar = Avatar.Image(old)),
        )

        assertEquals(
            "用户头像是既有缺口之一（#2127 提到连用户头像一起被清），不能漏",
            fileUrl("/data/user/0/app.androidrik.rikkahub/files/avatars/user.png"),
            plan[old],
        )
    }

    @Test
    fun `already migrated avatar is left alone`() {
        val url = fileUrl("/data/user/0/app.androidrik.rikkahub/files/avatars/ab12.jpg")

        assertTrue(
            "幂等：已经是 avatars/ 的引用不能再进搬迁计划（否则每次启动都重算）",
            AvatarMigration.planAvatarMigration(settings(avatars = listOf(Avatar.Image(url)))).isEmpty(),
        )
    }

    @Test
    fun `non-upload folders are not touched`() {
        // 判据窄化的回归钉子：images/（imggen 产物）与 tool_outputs/ 都不该被搬
        val images = fileUrl("/data/user/0/app.androidrik.rikkahub/files/images/ab12.png")
        val toolOutput = fileUrl("/data/user/0/app.androidrik.rikkahub/files/tool_outputs/ab12.txt")

        val plan = AvatarMigration.planAvatarMigration(
            settings(avatars = listOf(Avatar.Image(images), Avatar.Image(toolOutput))),
        )

        assertTrue("只有 /upload/ 下的头像才搬，别动 images/ 与 tool_outputs/", plan.isEmpty())
    }

    @Test
    fun `remote and non-image avatars are ignored`() {
        val plan = AvatarMigration.planAvatarMigration(
            settings(
                avatars = listOf(
                    Avatar.Image("https://example.com/a.png"),
                    Avatar.Emoji("🙂"),
                    Avatar.Dummy,
                ),
            ),
        )

        assertTrue("外链头像是用户自己填的 URL，不能改；Emoji/Dummy 无文件", plan.isEmpty())
    }

    @Test
    fun `same file referenced twice yields one mapping`() {
        val old = fileUrl("/data/user/0/app.androidrik.rikkahub/files/upload/shared.jpg")
        val plan = AvatarMigration.planAvatarMigration(
            settings(
                avatars = listOf(Avatar.Image(old), Avatar.Image(old)),
                userAvatar = Avatar.Image(old),
            ),
        )

        assertEquals("同一文件被多个头像引用时只产生一条映射（避免二次搬迁）", 1, plan.size)
    }
}
