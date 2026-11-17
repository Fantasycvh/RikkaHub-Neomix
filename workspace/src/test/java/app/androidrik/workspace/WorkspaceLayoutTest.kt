package app.androidrik.workspace

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class WorkspaceLayoutTest {
    private val layout = WorkspaceLayout(
        workspaceDir = "/workspace",
        writableRoots = listOf("/workspace", "/tmp", "/mnt/ext/repos"),
        approvalRoots = listOf("/mnt/ext", "/skills", "/workspace/private"),
    )

    @Test
    fun writableRootsStayWritable() {
        assertTrue(layout.isWritable("/workspace"))
        assertTrue(layout.isWritable("/workspace/notes/a.md"))
        assertTrue(layout.isWritable("/tmp/a.txt"))
        assertTrue(layout.isWritable("/mnt/ext/repos"))
        assertTrue(layout.isWritable("/mnt/ext/repos/src/a.kt"))
    }

    @Test
    fun approvalRootsRequireApproval() {
        assertFalse(layout.isWritable("/mnt/ext/Camera/a.jpg"))
        assertFalse(layout.isWritable("/skills/demo/SKILL.md"))
    }

    @Test
    fun nestedApprovalRootOverridesWritableRoot() {
        // /workspace 免审批，但更深的 /workspace/private 需要审批（最长匹配）
        assertFalse(layout.isWritable("/workspace/private/key.pem"))
        assertTrue(layout.isWritable("/workspace/public/a.txt"))
    }

    @Test
    fun dotSegmentsDoNotBypassApproval() {
        // /workspace/../skills 归一化后是 /skills ⇒ 需要审批
        assertFalse(layout.isWritable("/workspace/../skills/demo/SKILL.md"))
        // /workspace/private/.. 归一化后是 /workspace ⇒ 免审批（. 不能锁死审批区，但也不能逃出工作区）
        assertTrue(layout.isWritable("/workspace/private/../public/a.txt"))
    }

    @Test
    fun traversalBeyondRootIsNotWritable() {
        assertFalse(layout.isWritable("/workspace/../.."))
        assertFalse(layout.isWritable("/../workspace/a.txt"))
    }

    @Test
    fun trailingSlashAndBlankNormalizeLikeBefore() {
        assertTrue(layout.isWritable("/workspace/"))
        assertFalse(layout.isWritable("/"))
        assertFalse(layout.isWritable(""))
        assertFalse(layout.isWritable("workspace/a.txt"))
    }

    @Test
    fun approvalRootsDefaultToEmpty() {
        val plain = WorkspaceLayout(workspaceDir = "/workspace", writableRoots = listOf("/workspace"))
        assertTrue(plain.isWritable("/workspace/a.txt"))
        assertFalse(plain.isWritable("/skills/a.md"))
        assertEquals(emptyList<String>(), plain.approvalRoots)
    }
}
