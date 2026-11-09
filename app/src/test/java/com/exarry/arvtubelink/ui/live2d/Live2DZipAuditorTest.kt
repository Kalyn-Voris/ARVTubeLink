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
import java.util.zip.CRC32
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/**
 * 「条目类型白名单」与「解析一致性」的回归测试。
 *
 * ## 为什么需要手工构造 zip
 * `java.util.zip.ZipOutputStream` **无法**写出 unix mode / external attributes——
 * 恰恰是符号链接、设备文件的类型信息所在。用普通 API 造的包永远是「普通文件」，
 * 测不到这道防线。所以这里手写 zip 的最小结构（本地头 + 中央目录 + EOCD），
 * 按字节控制 version-made-by 与 external attributes。
 *
 * 参考格式：PKWARE APPNOTE 4.3.7（本地头）、4.3.12（中央目录）、4.3.16（EOCD）。
 */
class Live2DZipAuditorTest {

    @get:Rule
    val tmp = TemporaryFolder()

    // ========================================================================
    //  手写 zip 构造器
    // ========================================================================

    /**
     * 一个待写入的条目。
     *
     * @param platform version-made-by 的高字节：3 = Unix，19 = macOS，0 = FAT
     * @param unixMode st_mode（仅 Unix/macOS 平台有意义），如 `S_IFLNK or 0x1FF`
     * @param dosAttributes external attributes 的低字节（FAT 平台看这里）
     */
    private data class RawEntry(
        val name: String,
        val content: ByteArray = ByteArray(0),
        val platform: Int = 0,
        val unixMode: Int = 0,
        val dosAttributes: Int = 0,
        val declaredUncompressed: Long? = null,
        val method: Int = 0,
        val flags: Int = 0,
        /** 覆盖本地头里的条目名，用于构造「本地头与中央目录不一致」的包。 */
        val localName: String? = null,
        /** 覆盖中央目录里的压缩后长度，用于构造「声明值造假」的包。 */
        val declaredCompressed: Long? = null,
    )

    /** 按中央目录顺序写出一个合法 zip。 */
    private fun buildZip(vararg entries: RawEntry): ByteArray {
        val out = ByteArrayOutputStream()
        val central = ByteArrayOutputStream()
        val offsets = ArrayList<Long>()

        for (entry in entries) {
            val nameBytes = entry.name.toByteArray(Charsets.UTF_8)
            val localNameBytes = (entry.localName ?: entry.name).toByteArray(Charsets.UTF_8)
            val crc = CRC32().apply { update(entry.content) }.value
            val size = entry.content.size.toLong()
            val declared = entry.declaredUncompressed ?: size
            val declaredCompressed = entry.declaredCompressed ?: size

            offsets += out.size().toLong()

            // ---- 本地文件头 ----
            out.u32(0x04034b50)
            out.u16(20) // version needed
            out.u16(entry.flags)
            out.u16(entry.method)
            out.u16(0) // mod time
            out.u16(0) // mod date
            out.u32(crc)
            out.u32(declaredCompressed)
            out.u32(declared)
            out.u16(localNameBytes.size)
            out.u16(0) // extra length
            out.write(localNameBytes)
            out.write(entry.content)

            // ---- 中央目录条目 ----
            central.u32(0x02014b50)
            central.u16(((entry.platform and 0xFF) shl 8) or 20) // version made by
            central.u16(20) // version needed
            central.u16(entry.flags)
            central.u16(entry.method)
            central.u16(0) // mod time
            central.u16(0) // mod date
            central.u32(crc)
            central.u32(declaredCompressed)
            central.u32(declared)
            central.u16(nameBytes.size)
            central.u16(0) // extra length
            central.u16(0) // comment length
            central.u16(0) // disk number start
            central.u16(0) // internal attributes
            central.u32(
                (entry.unixMode.toLong() shl 16) or (entry.dosAttributes.toLong() and 0xFFFF)
            )
            central.u32(offsets.last())
            central.write(nameBytes)
        }

        val centralBytes = central.toByteArray()
        val centralOffset = out.size().toLong()
        out.write(centralBytes)

        // ---- EOCD ----
        out.u32(0x06054b50)
        out.u16(0) // this disk
        out.u16(0) // central dir start disk
        out.u16(entries.size)
        out.u16(entries.size)
        out.u32(centralBytes.size.toLong())
        out.u32(centralOffset)
        out.u16(0) // comment length

        return out.toByteArray()
    }

    private fun ByteArrayOutputStream.u16(value: Int) {
        write(value and 0xFF)
        write((value ushr 8) and 0xFF)
    }

    private fun ByteArrayOutputStream.u32(value: Long) {
        write((value and 0xFF).toInt())
        write(((value ushr 8) and 0xFF).toInt())
        write(((value ushr 16) and 0xFF).toInt())
        write(((value ushr 24) and 0xFF).toInt())
    }

    private fun newRoot(): File =
        tmp.newFolder("live2d_models").resolve("1730000000000").apply { mkdirs() }

    private fun extract(bytes: ByteArray, root: File) {
        Live2DModelStore.extractZip(ByteArrayInputStream(bytes), root)
    }

    // ========================================================================
    //  特殊文件类型必须整包拒绝（7-Zip 口径）
    // ========================================================================

    @Test
    fun `symlink entry is rejected`() {
        val root = newRoot()
        val zip = buildZip(
            RawEntry(
                name = "link.model3.json",
                content = "/data/data/com.exarry.arvtubelink/files/evil".toByteArray(),
                platform = 3,
                unixMode = S_IFLNK or 0x1FF,
            )
        )

        try {
            extract(zip, root)
            fail("符号链接条目未被拦截")
        } catch (e: Live2DModelStore.ZipUnsafeEntryException) {
            assertTrue("异常信息应点名符号链接: ${e.message}", e.message!!.contains("符号链接"))
        }
        assertFalse("符号链接不应落盘", File(root, "link.model3.json").exists())
    }

    @Test
    fun `character device entry is rejected`() {
        val root = newRoot()
        val zip = buildZip(
            RawEntry(name = "dev", platform = 3, unixMode = S_IFCHR or 0x1B6)
        )
        try {
            extract(zip, root)
            fail("字符设备条目未被拦截")
        } catch (e: Live2DModelStore.ZipUnsafeEntryException) {
        }
        assertFalse(File(root, "dev").exists())
    }

    @Test
    fun `block device entry is rejected`() {
        val root = newRoot()
        val zip = buildZip(
            RawEntry(name = "blk", platform = 3, unixMode = S_IFBLK or 0x1B6)
        )
        try {
            extract(zip, root)
            fail("块设备条目未被拦截")
        } catch (e: Live2DModelStore.ZipUnsafeEntryException) {
        }
    }

    @Test
    fun `fifo entry is rejected`() {
        val root = newRoot()
        val zip = buildZip(
            RawEntry(name = "pipe", platform = 3, unixMode = S_IFIFO or 0x1B6)
        )
        try {
            extract(zip, root)
            fail("FIFO 条目未被拦截")
        } catch (e: Live2DModelStore.ZipUnsafeEntryException) {
        }
    }

    @Test
    fun `socket entry is rejected`() {
        val root = newRoot()
        val zip = buildZip(
            RawEntry(name = "sock", platform = 3, unixMode = S_IFSOCK or 0x1B6)
        )
        try {
            extract(zip, root)
            fail("socket 条目未被拦截")
        } catch (e: Live2DModelStore.ZipUnsafeEntryException) {
        }
    }

    /** macOS 也用 Unix 类型位（platform = 19），必须一并覆盖。 */
    @Test
    fun `macos symlink entry is rejected`() {
        val root = newRoot()
        val zip = buildZip(
            RawEntry(
                name = "mac.link",
                content = "/etc/passwd".toByteArray(),
                platform = 19,
                unixMode = S_IFLNK or 0x1FF,
            )
        )
        try {
            extract(zip, root)
            fail("macOS 符号链接条目未被拦截")
        } catch (e: Live2DModelStore.ZipUnsafeEntryException) {
        }
    }

    /** 合法 Unix 普通文件（S_IFREG）必须放行——防线不能误伤正常模型。 */
    @Test
    fun `unix regular file is allowed`() {
        val root = newRoot()
        val zip = buildZip(
            RawEntry(name = "sample.moc3", content = "MOC".toByteArray(), platform = 3, unixMode = S_IFREG or 0x1A4)
        )
        extract(zip, root)
        assertEquals("MOC", File(root, "sample.moc3").readText())
    }

    /** 合法 Unix 目录（S_IFDIR）必须放行，且名字可以带尾斜杠。 */
    @Test
    fun `unix directory entry is allowed`() {
        val root = newRoot()
        val zip = buildZip(
            RawEntry(name = "textures/", platform = 3, unixMode = S_IFDIR or 0x1ED),
            RawEntry(name = "textures/t.png", content = "PNG".toByteArray(), platform = 3, unixMode = S_IFREG or 0x1A4),
        )
        extract(zip, root)
        assertTrue(File(root, "textures").isDirectory)
        assertTrue(File(root, "textures/t.png").isFile)
    }

    // ========================================================================
    //  解析一致性（中央目录 vs 本地头）
    // ========================================================================

    @Test
    fun `local header name mismatch is rejected`() {
        val root = newRoot()
        val zip = buildZip(
            RawEntry(
                name = "safe.txt",
                content = "x".toByteArray(),
                localName = "evil.txt",
            )
        )
        try {
            extract(zip, root)
            fail("本地头与中央目录文件名不一致未被拦截")
        } catch (e: ZipFormatException) {
            assertTrue("异常应点名文件名不一致: ${e.message}", e.message!!.contains("文件名"))
        }
    }

    @Test
    fun `declared size mismatch is rejected`() {
        val root = newRoot()
        // 实际写 4 字节，却声明解压后 4096 字节 —— 中央目录在说谎
        val zip = buildZip(
            RawEntry(
                name = "lying.bin",
                content = "ABCD".toByteArray(),
                declaredUncompressed = 4096,
            )
        )
        try {
            extract(zip, root)
            fail("声明长度与实际不符未被拦截")
        } catch (e: ZipFormatException) {
        }
    }

    // ========================================================================
    //  zip 炸弹：压缩比与单文件上限
    // ========================================================================

    @Test
    fun `absurd compression ratio is rejected`() {
        val root = newRoot()
        // 压缩后 16 字节、声明解压后 200 MB —— 压缩比 1.3 万倍
        val zip = buildZip(
            RawEntry(
                name = "bomb.bin",
                content = ByteArray(16),
                declaredUncompressed = 200L * 1024 * 1024,
                declaredCompressed = 16,
            )
        )
        try {
            extract(zip, root)
            fail("压缩比上限未生效")
        } catch (e: Live2DModelStore.ZipLimitException) {
            assertTrue("异常应点名压缩比: ${e.message}", e.message!!.contains("压缩比"))
        }
        assertFalse("炸弹文件不应落盘", File(root, "bomb.bin").exists())
    }

    @Test
    fun `declared entry over 450 MB is rejected`() {
        val root = newRoot()
        val zip = buildZip(
            RawEntry(
                name = "huge.bin",
                content = ByteArray(16),
                declaredUncompressed = 500L * 1024 * 1024,
            )
        )
        try {
            extract(zip, root)
            fail("单文件 450 MB 上限未生效")
        } catch (e: Live2DModelStore.ZipLimitException) {
        }
    }

    // ========================================================================
    //  重名条目
    // ========================================================================

    @Test
    fun `duplicate entry names are rejected`() {
        val root = newRoot()
        val zip = buildZip(
            RawEntry(name = "same.txt", content = "first".toByteArray()),
            RawEntry(name = "same.txt", content = "second".toByteArray()),
        )
        try {
            extract(zip, root)
            fail("重名条目未被拦截")
        } catch (e: Live2DModelStore.ZipUnsafeEntryException) {
            assertTrue("异常应点名重复条目: ${e.message}", e.message!!.contains("重复"))
        }
    }

    // ========================================================================
    //  加密条目
    // ========================================================================

    @Test
    fun `encrypted entry is rejected`() {
        val root = newRoot()
        val zip = buildZip(
            RawEntry(name = "secret.txt", content = "x".toByteArray(), flags = 0x0001)
        )
        try {
            extract(zip, root)
            fail("加密条目未被拦截")
        } catch (e: Live2DModelStore.ZipUnsafeEntryException) {
            assertTrue("异常应点名加密: ${e.message}", e.message!!.contains("加密"))
        }
    }

    // ========================================================================
    //  普通 java.util.zip 产生的包（FAT 平台）仍必须正常解压
    // ========================================================================

    @Test
    fun `fat platform archive from ZipOutputStream still works`() {
        val root = newRoot()
        val out = ByteArrayOutputStream()
        ZipOutputStream(out).use { zos ->
            zos.putNextEntry(ZipEntry("dir/"))
            zos.closeEntry()
            zos.putNextEntry(ZipEntry("dir/a.model3.json"))
            zos.write("{\"Version\":3}".toByteArray())
            zos.closeEntry()
            zos.putNextEntry(ZipEntry("dir/a.moc3"))
            zos.write("MOC".toByteArray())
            zos.closeEntry()
        }
        extract(out.toByteArray(), root)

        assertEquals("{\"Version\":3}", File(root, "dir/a.model3.json").readText())
        assertEquals("MOC", File(root, "dir/a.moc3").readText())
    }

    private companion object {
        const val S_IFREG = 0x8000
        const val S_IFDIR = 0x4000
        const val S_IFLNK = 0xA000
        const val S_IFCHR = 0x2000
        const val S_IFBLK = 0x6000
        const val S_IFIFO = 0x1000
        const val S_IFSOCK = 0xC000
    }
}
