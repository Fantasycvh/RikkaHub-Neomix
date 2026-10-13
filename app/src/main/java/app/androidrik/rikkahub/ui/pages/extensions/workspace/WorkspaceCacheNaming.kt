package app.androidrik.rikkahub.ui.pages.extensions.workspace

import app.androidrik.workspace.WorkspaceFileEntry

/**
 * 工作区文件导出到 cache 时的**防碰撞文件名**（上游 issue #2126）。
 *
 * ## 它修的是什么
 *
 * 预览/分享工作区图片时，文件要先进 cache 再由 Coil（或 FileProvider）消费。
 * 原来用的是 `File(dir, entry.name)`——**只用文件名**，而工作区是一个目录树：
 * 两个 `images/` 与 `backup/` 下的 `a.png` 会落到**同一个 cache 路径**、互相覆盖。
 * 表现就是报告里那条「预览第二张同名图片，看到的是第一张的内容」：
 * Coil 的缓存键是绝对路径，路径没变 ⇒ 直接命中上一次的字节。
 *
 * 同一个 VM 里另一个导出函数（`exportToCacheDir`）早就踩过并修掉了这个坑，
 * 注释写着「同名文件在不同子目录会碰撞, 用路径替换分隔符保证唯一」——
 * 本函数就是把那条判据抽出来共用，避免两个出口再次分叉。
 *
 * ## 为什么用「路径换分隔符」而不是「保留目录树」
 *
 * 目录树能保留干净的文件名，但代价是消费侧全都要改成递归查找（FileProvider 授权、
 * Coil 加载、`saveMessageImage` 的 `startsWith("/")` 判定都建立在「拿到一个文件路径」上）。
 * 替换分隔符只是一个字符串操作，**扩展名天然保留**（路径末尾就是原文件名，`.png` 不动），
 * 故 `detectFileType` / MIME 推断 / MediaStore 的显示名都不受影响。
 *
 * 例：`images/a.png` → `images_a.png`；`a.png` → `a.png`（顶层文件名保持原样）。
 */
internal fun workspaceCacheFileName(path: String): String =
    path.trim('/').replace('/', '_')
