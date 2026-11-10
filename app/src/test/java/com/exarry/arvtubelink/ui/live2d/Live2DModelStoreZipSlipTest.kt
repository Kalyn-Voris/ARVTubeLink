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

/** Zip Slip 防线回归：构造恶意条目名走真实 extractZip，确认抛异常且解压根外无文件 */
class Live2DModelStoreZipSlipTest {

    @get:Rule
    val tmp = TemporaryFolder()

    /** 条目名原样写入 zip，不做规范化 */
    private fun zipWith(vararg entries: Pair<String, String>): ByteArray {
        val out = ByteArrayOutputStream()
        ZipOutputStream(out).use { zos ->
            for ((name, content) in entries) {
                zos.putNextEntry(ZipEntry(name))
                zos.write(content.toByteArray())
                zos.closeEntry()
            }
        }
        return out.toByteArray()
    }

    private fun newRoot(): File =
        tmp.newFolder("live2d_models").resolve("1730000000000").apply { mkdirs() }

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

    /** 合法条目与穿越条目同包时须整包拒绝，不跳过坏的保留好的 */
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
        assertFalse("解压根之外出现文件", outside.exists())
    }

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

    /** 条目数越过 MAX_ENTRIES = 4096 上限 */
    @Test
    fun `entry count limit is enforced`() {
        val root = newRoot()
        val entries = Array(4097) { i -> "f$i.txt" to "" }
        try {
            Live2DModelStore.extractZip(ByteArrayInputStream(zipWith(*entries)), root)
            fail("条目数上限未生效")
        } catch (e: Live2DModelStore.ZipLimitException) {
        }
    }
}
