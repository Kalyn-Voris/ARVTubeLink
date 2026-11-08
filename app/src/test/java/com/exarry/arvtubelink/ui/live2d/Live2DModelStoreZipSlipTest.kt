package com.exarry.arvtubelink.ui.live2d

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/**
 * Zip Slip 防线的回归测试。
 *
 * 为什么必须存在：这套防线只有在「真的收到恶意 zip」时才生效，而正常用户
 * 永远不会提供这样的包——靠手测等于永不验证。这里直接构造恶意条目名，
 * 走 [Live2DModelStore.extractZip] 的真实路径，确认：
 *  1. 任何穿越形态都被拦截（抛 ZipSlipException）；
 *  2. 目标目录**外**没有留下任何文件（这是漏洞的真正危害，不是异常本身）。
 *
 * 攻击目标固定为 `root/../../escape_*.txt`，只向上两级——足够跳出解压根，
 * 又不触碰真实系统路径（TemporaryFolder 是 JVM 临时目录，测试完全隔离）。
 */
class Live2DModelStoreZipSlipTest {

    @get:Rule
    val tmp = TemporaryFolder()

    /** 构造一个含指定条目的 zip 字节流。条目名原样写入，不做任何规范化。 */
    private fun zipWith(vararg entries: Pair<String, String>): ByteArray {
        val out = ByteArrayOutputStream()
        ZipOutputStream(out).use { zos ->
            for ((name, content) in entries) {
                // 注意：不能用 ZipEntry(name).isDirectory 之类，名字必须原样保留
                zos.putNextEntry(ZipEntry(name))
                zos.write(content.toByteArray())
                zos.closeEntry()
            }
        }
        return out.toByteArray()
    }

    /** 建一个「解压根目录」，返回它。 */
    private fun newRoot(): File =
        tmp.newFolder("live2d_models").resolve("1730000000000").apply { mkdirs() }

    // ========================================================================
    //  被拦截的形态
    // ========================================================================

    @Test
    fun `relative traversal is rejected and writes nothing outside`() {
        val root = newRoot()
        val outside = root.parentFile!!.parentFile!!.resolve("escape_relative.txt")

        try {
            Live2DModelStore.extractZip(
                ByteArrayInputStream(zipWith("../escape_relative.txt" to "pwned")),
                root,
            )
            fail("相对路径穿越未被拦截")
        } catch (e: Live2DModelStore.ZipSlipException) {
            // 期望路径
        }

        assertFalse("穿越文件被写到解压根之外: ${outside.path}", outside.exists())
    }

    @Test
    fun `multi level traversal is rejected`() {
        val root = newRoot()
        val outside = tmp.root.resolve("escape_multi.txt")

        try {
            Live2DModelStore.extractZip(
                ByteArrayInputStream(zipWith("../../../../escape_multi.txt" to "pwned")),
                root,
            )
            fail("多级穿越未被拦截")
        } catch (e: Live2DModelStore.ZipSlipException) {
        }

        assertFalse(outside.exists())
    }

    @Test
    fun `absolute path entry is rejected`() {
        val root = newRoot()
        val outside = File(tmp.root, "escape_absolute.txt")

        try {
            Live2DModelStore.extractZip(
                ByteArrayInputStream(zipWith(outside.absolutePath to "pwned")),
                root,
            )
            fail("绝对路径条目未被拦截")
        } catch (e: Live2DModelStore.ZipSlipException) {
        }

        assertFalse(outside.exists())
    }

    @Test
    fun `drive letter entry is rejected`() {
        val root = newRoot()
        try {
            Live2DModelStore.extractZip(
                ByteArrayInputStream(zipWith("C:/Windows/escape.txt" to "pwned")),
                root,
            )
            fail("盘符条目未被拦截")
        } catch (e: Live2DModelStore.ZipSlipException) {
        }
    }

    @Test
    fun `backslash traversal is rejected`() {
        val root = newRoot()
        val outside = root.parentFile!!.resolve("escape_backslash.txt")

        try {
            Live2DModelStore.extractZip(
                ByteArrayInputStream(zipWith("..\\escape_backslash.txt" to "pwned")),
                root,
            )
            fail("反斜杠穿越未被拦截")
        } catch (e: Live2DModelStore.ZipSlipException) {
        }

        assertFalse(outside.exists())
    }

    @Test
    fun `traversal hidden behind a legal prefix is rejected`() {
        val root = newRoot()
        val outside = root.parentFile!!.parentFile!!.resolve("escape_nested.txt")

        try {
            Live2DModelStore.extractZip(
                ByteArrayInputStream(zipWith("model/sample/../../../escape_nested.txt" to "pwned")),
                root,
            )
            fail("带合法前缀的穿越未被拦截")
        } catch (e: Live2DModelStore.ZipSlipException) {
        }

        assertFalse(outside.exists())
    }

    @Test
    fun `entry pointing at the root itself is rejected`() {
        val root = newRoot()
        try {
            Live2DModelStore.extractZip(ByteArrayInputStream(zipWith("./" to "")), root)
            fail("指向根目录本身的条目未被拦截")
        } catch (e: Live2DModelStore.ZipSlipException) {
        }
    }

    /**
     * 关键语义：一个恶意 zip 里即使**同时**含有合法条目，也必须整体失败，
     * 而不是「跳过坏的、保留好的」——含穿越条目的包整体视为恶意。
     */
    @Test
    fun `one malicious entry aborts the whole archive`() {
        val root = newRoot()
        val good = "model/sample/ok.txt"
        val outside = root.parentFile!!.parentFile!!.resolve("escape_mixed.txt")

        try {
            Live2DModelStore.extractZip(
                ByteArrayInputStream(
                    zipWith(
                        good to "fine",
                        "../escape_mixed.txt" to "pwned",
                    )
                ),
                root,
            )
            fail("混合包未被整体拒绝")
        } catch (e: Live2DModelStore.ZipSlipException) {
        }

        assertFalse("穿越文件被写出", outside.exists())
        // 合法条目在恶意条目之前可能已被写入——这不构成漏洞（仍在根内），
        // 但上层 fail() 会 deleteRecursively 整个目录，见 importZip 的实现。
        assertFalse("解压根之外出现文件", outside.exists())
    }

    // ========================================================================
    //  合法包必须正常解压（防止防线过严把正常模型也拒了）
    // ========================================================================

    @Test
    fun `normal archive extracts all entries`() {
        val root = newRoot()

        Live2DModelStore.extractZip(
            ByteArrayInputStream(
                zipWith(
                    "sample.model3.json" to "{\"Version\":3}",
                    "sample.moc3" to "MOC",
                    "textures/texture_00.png" to "PNG",
                    "motion/idle.motion3.json" to "{}",
                )
            ),
            root,
        )

        assertTrue(File(root, "sample.model3.json").isFile)
        assertEquals("MOC", File(root, "sample.moc3").readText())
        assertTrue(File(root, "textures/texture_00.png").isFile)
        assertTrue(File(root, "motion/idle.motion3.json").isFile)
    }

    @Test
    fun `nested directories are created`() {
        val root = newRoot()
        Live2DModelStore.extractZip(
            ByteArrayInputStream(zipWith("a/b/c/deep.txt" to "x")),
            root,
        )
        assertEquals("x", File(root, "a/b/c/deep.txt").readText())
    }

    // ========================================================================
    //  zip 炸弹上限
    // ========================================================================

    @Test
    fun `entry count limit is enforced`() {
        val root = newRoot()
        // MAX_ENTRIES = 4096，构造 4097 个空条目
        val entries = Array(4097) { i -> "f$i.txt" to "" }
        try {
            Live2DModelStore.extractZip(ByteArrayInputStream(zipWith(*entries)), root)
            fail("条目数上限未生效")
        } catch (e: Live2DModelStore.ZipLimitException) {
        }
    }
}
