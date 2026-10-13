package app.androidrik.search

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test
import kotlin.uuid.Uuid

/**
 * 搜索选项的**值相等**语义（上游 `640496127` 的同类修正：普通 `class` → `data class`）。
 *
 * ## 为什么要专门钉住
 *
 * [SearchServiceOptions.BingLocalOptions] 原本是普通 `class`，用的是**引用相等**。
 * 设置是整份 JSON 存取的（`Settings` → DataStore），**每次解码都会 new 出新实例** ⇒
 * 「内容完全没变但实例不相等」⇒ `Settings` 上的 `distinctUntilChanged()` 挡不住它
 * ⇒ **任何一次设置写入都会让「读设置」的界面整树重组**（拖动一次 slider 会触发多次）。
 *
 * 这类缺陷**没有任何报错**、行为断言也看不出来（设置读出来的值始终是对的），
 * 只表现为「界面莫名重组」，所以只有把相等性钉死才能防回退。
 *
 * 同时断言**不同 id / enabled 必须不相等**——`data class` 的 `equals` 若被人改成
 * 只看部分字段（或有人图省事写成 `equals(other) = true` 的「去重」），上面的
 * `distinctUntilChanged` 就会变成「吞掉真实的设置变化」，那是更难查的反向 bug。
 */
class SearchOptionsEqualityTest {

    @Test
    fun `bing local options with same content are equal`() {
        val id = Uuid.random()

        assertEquals(
            "同 id/同 enabled 的两个实例必须相等：解码出来的新实例要与内存里的等价，" +
                "否则 distinctUntilChanged 挡不住、每次写入都整树重组",
            SearchServiceOptions.BingLocalOptions(id = id, enabled = true),
            SearchServiceOptions.BingLocalOptions(id = id, enabled = true),
        )
        assertEquals(
            "hashCode 必须与 equals 一致（否则放进 Set/Map 会出诡异行为）",
            SearchServiceOptions.BingLocalOptions(id = id, enabled = true).hashCode(),
            SearchServiceOptions.BingLocalOptions(id = id, enabled = true).hashCode(),
        )
    }

    @Test
    fun `bing local options differing in any field are not equal`() {
        val id = Uuid.random()

        assertNotEquals(
            "enabled 不同必须不相等（否则开关划动会被去重吞掉）",
            SearchServiceOptions.BingLocalOptions(id = id, enabled = true),
            SearchServiceOptions.BingLocalOptions(id = id, enabled = false),
        )
        assertNotEquals(
            "id 不同必须不相等（否则两个搜索服务条目会被当成同一个）",
            SearchServiceOptions.BingLocalOptions(id = id, enabled = true),
            SearchServiceOptions.BingLocalOptions(id = Uuid.random(), enabled = true),
        )
    }

    @Test
    fun `default instance equals a fresh instance built with the same id`() {
        // 默认值必须也是等值语义的一部分：否则「解码出默认值」与「DEFAULT 常量」不相等，
        // 界面上「恢复默认」这类操作会被当成一次真实变化（多一次重组）
        val default = SearchServiceOptions.DEFAULT

        assertEquals(
            "同 id 的默认实例必须与 DEFAULT 相等",
            SearchServiceOptions.BingLocalOptions(id = default.id),
            default,
        )
    }
}
