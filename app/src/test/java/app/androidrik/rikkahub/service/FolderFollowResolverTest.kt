package app.androidrik.rikkahub.service

import app.androidrik.rikkahub.data.model.Folder
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.time.Instant
import kotlin.uuid.Uuid

/**
 * 「文件夹跟随」解析器（设计稿 `design-conversation-batch-assistant-migration.md` D1）：
 * 同名归并 / 无同名克隆 / 源已删回落未归档 / 同源只解析一次 / 多源同名归并到同一目标。
 */
class FolderFollowResolverTest {

    private class FakeFolders {
        val targetFolders = mutableMapOf<Uuid, Pair<String, Uuid>>() // id → (name, targetAssistantId)
        var createdCount = 0
        var loadedCount = 0

        fun sourceFolder(name: String) = Folder(
            id = Uuid.random(),
            assistantId = Uuid.random(), // 源助手（任意，解析不看源归属）
            name = name,
            createAt = Instant.now(),
        )

        fun targetFolder(name: String) = targetFolders.entries
            .find { it.value.first == name }?.key
    }

    private fun resolver(
        fake: FakeFolders,
        sources: MutableMap<Uuid, Folder?> = mutableMapOf(),
    ) = FolderFollowResolver(
        loadSourceFolder = { id ->
            fake.loadedCount++
            sources[id]
        },
        findTargetByName = { name -> fake.targetFolder(name) },
        createTargetFolder = { name ->
            fake.createdCount++
            val id = Uuid.random()
            fake.targetFolders[id] = name to Uuid.random()
            id
        },
    )

    @Test
    fun `unfiled conversation stays unfiled`() = runTest {
        val fake = FakeFolders()
        assertNull(resolver(fake).resolve(null))
        assertEquals(0, fake.createdCount)
        assertEquals(0, fake.loadedCount)
    }

    @Test
    fun `same name in target merges into existing folder`() = runTest {
        val fake = FakeFolders()
        val existing = Uuid.random()
        fake.targetFolders[existing] = "工作" to Uuid.random()
        val source = fake.sourceFolder("工作").also { fake.loadedCount = 0 }

        assertEquals(existing, resolver(fake, mutableMapOf(source.id to source)).resolve(source.id))
        assertEquals("命中同名不建新文件夹", 0, fake.createdCount)
    }

    @Test
    fun `no same name clones a folder into target`() = runTest {
        val fake = FakeFolders()
        val source = fake.sourceFolder("项目A")

        val target = resolver(fake, mutableMapOf(source.id to source)).resolve(source.id)

        assertEquals("克隆体登记在目标侧", target, fake.targetFolder("项目A"))
        assertEquals(1, fake.createdCount)
    }

    @Test
    fun `source folder deleted mid-flight falls back to unfiled`() = runTest {
        val fake = FakeFolders()
        val sourceId = Uuid.random()

        assertNull(resolver(fake, mutableMapOf(sourceId to null)).resolve(sourceId))
        assertEquals(0, fake.createdCount)
    }

    @Test
    fun `same source resolves once`() = runTest {
        val fake = FakeFolders()
        val source = fake.sourceFolder("A")
        val r = resolver(fake, mutableMapOf(source.id to source))

        val first = r.resolve(source.id)
        val second = r.resolve(source.id)

        assertEquals(first, second)
        assertEquals("同源只查一次", 1, fake.loadedCount)
        assertEquals(1, fake.createdCount)
    }

    @Test
    fun `distinct source folders with same name merge into one target`() = runTest {
        val fake = FakeFolders()
        val a = fake.sourceFolder("工作")
        val b = fake.sourceFolder("工作")
        val r = resolver(fake, mutableMapOf(a.id to a, b.id to b))

        val targetA = r.resolve(a.id)
        val targetB = r.resolve(b.id)

        assertEquals("同名源归并到同一目标文件夹", targetA, targetB)
        assertEquals(1, fake.createdCount)
    }
}
