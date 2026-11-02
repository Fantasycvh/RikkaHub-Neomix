package app.androidrik.rikkahub.service

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 通知命名空间（设计 §4.4 / P0）。
 *
 * 钉住四条：①**同一 run 稳定同 id**（重复投递原地更新通知，不堆叠）；
 * ②**不同 run 不同 id**（并行 8 个 run 的通知不得互相覆盖，取消时也不得误撤别人）；
 * ③**恒为正整数且避开固定占用的 40000**（那是定时 run 前台服务通知的 id，撞上会互相顶掉）；
 * ④同一 run 的不同动作（批准 / 拒绝 / 打开详情）requestCode 互异
 * （否则 `FLAG_UPDATE_CURRENT` 会让后注册的 PendingIntent 覆盖前一个）。
 *
 * 这些断言只依赖纯函数行为，不需要 Android 运行时——所以是本 P0 切片里为数不多能 JVM 钉住的点。
 */
class NotificationIdsTest {

    private val runIds = listOf(
        "11111111-1111-1111-1111-111111111111",
        "22222222-2222-2222-2222-222222222222",
        "33333333-3333-3333-3333-333333333333",
        "0192f3a4-5b6c-7d8e-9f00-112233445566",
        "run-without-uuid-shape",
        "任务中文 run id",
    )

    @Test
    fun `the same run always maps to the same notification id`() {
        runIds.forEach { runId ->
            assertEquals(
                "重复投递必须命中同一条通知",
                NotificationIds.subagentApproval(runId),
                NotificationIds.subagentApproval(runId),
            )
        }
    }

    @Test
    fun `different runs get different notification ids`() {
        val ids = runIds.map(NotificationIds::subagentApproval)

        assertEquals("不同 run 的通知 id 不得重合", runIds.size, ids.toSet().size)
    }

    @Test
    fun `notification ids are positive and avoid reserved fixed ids`() {
        runIds.map(NotificationIds::subagentApproval).forEach { id ->
            assertTrue("通知 id 必须是正整数，实际 $id", id > 0)
            assertNotEquals("40000 是定时 run 前台服务通知的固定 id", 40000, id)
            assertNotEquals("1 是聊天生成完成通知的固定 id", 1, id)
        }
    }

    @Test
    fun `a hash that lands on a reserved id is shifted deterministically`() {
        // 这两个 probe 是用 FNV-1a 反解出来的（不是随机样例）：整串
        // "reserved-probe:<probe>" 的哈希掩掉符号位后恰好等于 1 / 40000。
        // 命中固定占用的 id 时必须顺延，否则会顶掉别人的常驻通知。
        assertEquals(2, NotificationIds.stableId("reserved-probe", RESERVED_ONE_PROBE))
        assertEquals(40001, NotificationIds.stableId("reserved-probe", RESERVED_40000_PROBE))
    }

    @Test
    fun `actions get distinct request codes for the same run`() {
        val approve = NotificationIds.subagentApprovalRequestCode(
            runIds.first(),
            NotificationIds.ApprovalAction.APPROVE,
        )
        val deny = NotificationIds.subagentApprovalRequestCode(
            runIds.first(),
            NotificationIds.ApprovalAction.DENY,
        )
        val open = NotificationIds.subagentApprovalRequestCode(
            runIds.first(),
            NotificationIds.ApprovalAction.OPEN_DETAIL,
        )

        assertEquals("requestCode 必须为正", 3, setOf(approve, deny, open).size)
        listOf(approve, deny, open).forEach { assertTrue("requestCode 必须为正，实际 $it", it > 0) }

        // 同一动作重复取仍是同一个 requestCode（否则每次弹通知都会新建一个 PendingIntent）
        assertEquals(
            approve,
            NotificationIds.subagentApprovalRequestCode(
                runIds.first(),
                NotificationIds.ApprovalAction.APPROVE,
            ),
        )
    }

    @Test
    fun `request codes differ across runs`() {
        val codes = runIds.map {
            NotificationIds.subagentApprovalRequestCode(
                it,
                NotificationIds.ApprovalAction.APPROVE,
            )
        }

        assertEquals("不同 run 的批准 requestCode 不得重合", runIds.size, codes.toSet().size)
    }

    private companion object {
        /** FNV-1a("reserved-probe:<probe>") & 0x7FFFFFFF == 1 的输入（反解所得）。 */
        const val RESERVED_ONE_PROBE = "bmZH8b"

        /** FNV-1a("reserved-probe:<probe>") & 0x7FFFFFFF == 40000 的输入（反解所得）。 */
        const val RESERVED_40000_PROBE = "dtcnil"
    }
}
