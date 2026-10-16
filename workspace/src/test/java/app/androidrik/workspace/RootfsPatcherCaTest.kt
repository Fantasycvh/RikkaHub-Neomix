package app.androidrik.workspace

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.nio.file.Files

/**
 * rootfs 的 **CA 证书离线兜底**（借自 Yuihub `8a3a37ac`，见 `docs/peer-apps-benchmark.md` §四.5）。
 *
 * 背景：ubuntu-base 裸镜像不含 `ca-certificates`（真机核实：`etc/ssl` 目录**根本不存在**），
 * 于是容器内一切 https 都是**静默失败** —— git/pip/npm 报"证书验证失败"、curl 报 000。
 * 而"用 apt 装证书"本身要联网，形成死循环 ⇒ 直接复用 **Android 系统证书库**离线生成。
 *
 * 注入 `caSourceDirs` 而不是读默认的 `/system/...`：那条路径在 JVM 测试机上不存在，
 * 且默认值恰好就是"什么都不做"的分支，用它测等于没测。
 */
class RootfsPatcherCaTest {

    private fun pem(tag: String) =
        "-----BEGIN CERTIFICATE-----\n$tag\n-----END CERTIFICATE-----\n"

    private class Fixture {
        val linuxDir: File = Files.createTempDirectory("rootfs-ca-linux").toFile().apply {
            File(this, "etc").mkdirs()
        }
        val store: File = Files.createTempDirectory("rootfs-ca-store").toFile()
        val certsDir: File = File(linuxDir, "etc/ssl/certs")
        val bundle: File = File(certsDir, "ca-certificates.crt")

        fun patch() = RootfsPatcher().patch(
            linuxDir,
            RootfsPatchOptions(caSourceDirs = listOf(store.absolutePath)),
        )
    }

    private fun fixture() = Fixture()

    @Test
    fun `missing bundle is generated from the android store`() {
        val f = fixture()
        File(f.store, "aaaa1111.0").writeText(pem("A"))
        File(f.store, "bbbb2222.0").writeText(pem("B"))

        assertFalse("前提：这个 rootfs 里原本没有证书目录", f.certsDir.exists())
        f.patch()

        assertEquals(pem("A") + pem("B"), f.bundle.readText())
    }

    @Test
    fun `android hash named copies are written next to the bundle`() {
        val f = fixture()
        File(f.store, "aaaa1111.0").writeText(pem("A"))
        File(f.store, "bbbb2222.0").writeText(pem("B"))
        f.patch()

        // 两条路径都要有：curl/openssl 读 bundle，按目录查找的调用方读 <hash>.0
        assertEquals("Android 哈希名要原样保留", pem("A"), File(f.certsDir, "aaaa1111.0").readText())
        assertEquals(pem("B"), File(f.certsDir, "bbbb2222.0").readText())
    }

    @Test
    fun `non certificate and non hash files are skipped`() {
        val f = fixture()
        File(f.store, "good.0").writeText(pem("A"))
        File(f.store, "empty.0").writeText("not a certificate at all\n")
        File(f.store, "readme.txt").writeText(pem("B"))

        f.patch()

        assertEquals("只有真的是证书的 .0 才进 bundle", pem("A"), f.bundle.readText())
        assertFalse(File(f.certsDir, "empty.0").exists())
        assertFalse("后缀不匹配的不该被当作系统证书库", File(f.certsDir, "readme.txt").exists())
    }

    @Test
    fun `bundle is not rewritten when the store is unchanged`() {
        val f = fixture()
        File(f.store, "aaaa1111.0").writeText(pem("A"))
        f.patch()

        val content = f.bundle.readText()
        val stamp = f.bundle.lastModified()
        Thread.sleep(30) // 让 mtime 可区分

        f.patch()

        assertEquals(content, f.bundle.readText())
        assertEquals("内容没变就不该重写（patch 每次会话启动都会跑）", stamp, f.bundle.lastModified())
    }

    /**
     * **与参考实现的唯一偏差**：Yuihub 是「bundle 非空即短路」，本仓是「内容变了才写」。
     * 理由是 patch 在本仓每次会话启动都跑 —— 短路会让证书库永远停在第一次的快照，
     * Android 侧更新 CA 后 rootfs 仍信旧库，而这类失败的形态恰恰是静默的。
     */
    @Test
    fun `bundle is refreshed when the store gains a certificate`() {
        val f = fixture()
        File(f.store, "aaaa1111.0").writeText(pem("A"))
        f.patch()
        assertEquals(pem("A"), f.bundle.readText())

        File(f.store, "cccc3333.0").writeText(pem("C"))
        f.patch()

        assertEquals("系统库新增证书后必须跟上", pem("A") + pem("C"), f.bundle.readText())
        assertTrue(File(f.certsDir, "cccc3333.0").exists())
    }

    /**
     * **真机验出来的缺陷**：Android 14+ 上 `/apex/com.android.conscrypt/cacerts` 与
     * `/system/etc/security/cacerts` 是**同一批证书**（145 张被读两遍 ⇒ bundle 里 290 个
     * BEGIN CERTIFICATE、体积翻倍）。文件名去重，且**先到先得**（apex 在前 = 活库优先）。
     */
    @Test
    fun `same certificate name from two stores is merged once`() {
        val f = fixture()
        val secondStore = Files.createTempDirectory("rootfs-ca-store2").toFile()
        File(f.store, "aaaa1111.0").writeText(pem("APEX"))
        File(secondStore, "aaaa1111.0").writeText(pem("SYSTEM-LEGACY"))

        RootfsPatcher().patch(
            f.linuxDir,
            RootfsPatchOptions(caSourceDirs = listOf(f.store.absolutePath, secondStore.absolutePath)),
        )

        assertEquals("同名只进一次，且用先到的那个来源", pem("APEX"), f.bundle.readText())
        assertEquals(1, Regex("BEGIN CERTIFICATE").findAll(f.bundle.readText()).count())
    }

    @Test
    fun `no readable certificate leaves no empty bundle`() {
        val f = fixture()
        File(f.store, "empty.0").writeText("nope\n")

        f.patch()

        assertFalse(
            "一张都没读到就不要造空文件——空 bundle 会让「非空即就绪」式判断误以为已补好，更难查",
            f.bundle.exists(),
        )
    }

    @Test
    fun `absent source directories are ignored`() {
        val f = fixture()

        RootfsPatcher().patch(
            f.linuxDir,
            RootfsPatchOptions(caSourceDirs = listOf(File(f.store, "does-not-exist").absolutePath)),
        )

        assertFalse(f.bundle.exists())
    }

    @Test
    fun `certificate step does not disturb the other rootfs patches`() {
        val f = fixture()
        File(f.store, "aaaa1111.0").writeText(pem("A"))

        f.patch()

        // 顺带钉住：证书步骤插在 patch() 最前，不能因此让 DNS/hosts/locale 这些步骤失效
        assertTrue(File(f.linuxDir, "etc/resolv.conf").readText().contains("nameserver "))
        assertTrue(File(f.linuxDir, "etc/hostname").isFile)
        assertTrue(File(f.linuxDir, "etc/default/locale").readText().startsWith("LANG="))
        assertEquals(pem("A"), f.bundle.readText())
    }
}
