package com.exarry.arvtubelink.ui.live2d

import java.io.File
import java.io.IOException
import java.io.RandomAccessFile

/**
 * ZIP 中央目录审计器：解压**之前**先把整个包的结构读一遍，只信「中央目录 + 本地头」里可验证的字段。
 *
 * ## 为什么需要这一层（借鉴 7-Zip 的防御思路）
 * `java.util.zip.ZipFile` 只负责「把字节解开」，它**不告诉我们条目是什么文件类型**：
 * `ZipEntry` 没有暴露 external attributes / unix mode。而 zip 条目的真实类型（普通文件、
 * 目录、符号链接、设备文件、FIFO、socket）恰恰写在这两个字段里：
 *
 *  - `version made by` 高 8 位 = 打包平台（3 = Unix，19 = macOS）；
 *  - `external file attributes` 高 16 位 = `st_mode`（`S_IFMT` 掩码取类型位）。
 *
 * 7-Zip 的 ZIP handler 在解压前就会读这两处，非 `S_IFREG` / `S_IFDIR` 的条目直接拒绝。
 * 移动端虽然普通 `File` API 建不出符号链接，但「骗过解压器把它当普通文件写出去」本身
 * 就是路径穿越 / 后续攻击的一环（写出一个内容为 `/data/data/...` 的文件，再被别的组件当
 * 链接解释），所以这里按 7-Zip 的口径一律拒绝。
 *
 * ## 为什么不用 commons-compress
 * 它确实直接暴露 `getUnixMode()` / `isUnixSymlink()`，但为了一个属性检查引入一个
 * ~1 MB 的运行时依赖（连带 commons-io）不划算；中央目录格式是稳定且有限的，
 * 这里自解析 46 字节的固定头即可，还能顺便做「本地头 vs 中央目录」的一致性校验，
 * 这正好也是 7-Zip 防「解析差异攻击」的做法。
 *
 * ## 解析差异防御（本类的第二个职责）
 * 同一个 zip 里，中央目录记录和本地头各有一份文件名/大小/算法。攻击者可以让两份记录
 * 不一致：解压器按中央目录建目录、按本地头写文件，或反过来。这里对每个条目**按字节**
 * 比对两处文件名，并比对算法与（无数据描述符时）长度；不一致即整包拒绝。
 *
 * @throws ZipFormatException 结构非法 / 解析差异 / 不支持的变体（ZIP64、分卷、加密）
 */
internal object Live2DZipAuditor {

    private const val SIG_EOCD = 0x06054b50L
    private const val SIG_EOCD64_LOCATOR = 0x07064b50L
    private const val SIG_CENTRAL = 0x02014b50L
    private const val SIG_LOCAL = 0x04034b50L

    private const val EOCD_MIN_SIZE = 22
    private const val EOCD_MAX_COMMENT = 0xFFFF
    private const val CENTRAL_HEADER_SIZE = 46
    private const val LOCAL_HEADER_SIZE = 30

    /** 中央目录本身的上限：4096 个条目 × 最长名字也远小于此，超出即视为畸形包。 */
    private const val MAX_CENTRAL_DIR_SIZE = 16L * 1024 * 1024

    private const val FLAG_ENCRYPTED = 0x0001
    private const val FLAG_DATA_DESCRIPTOR = 0x0008

    private const val PLATFORM_UNIX = 3
    private const val PLATFORM_OSX = 19

    private const val S_IFMT = 0xF000
    private const val S_IFREG = 0x8000
    private const val S_IFDIR = 0x4000
    private const val S_IFLNK = 0xA000
    private const val S_IFCHR = 0x2000
    private const val S_IFBLK = 0x6000
    private const val S_IFIFO = 0x1000
    private const val S_IFSOCK = 0xC000

    private const val ZIP64_SENTINEL = 0xFFFF_FFFFL

    /** DOS external attributes 里的目录位。 */
    private const val DOS_ATTRIBUTE_DIRECTORY = 0x10L

    /** 条目类型。只有 [DIRECTORY] 与 [REGULAR] 允许落盘。 */
    enum class Kind { DIRECTORY, REGULAR, SYMLINK, DEVICE, OTHER }

    /**
     * 一个条目的审计结果。
     *
     * @param rawName 中央目录里的原始文件名字节（用于和本地头按字节比对，避免编码差异）
     * @param name 仅供日志展示的 UTF-8 解码结果；真正的路径以解压器给出的名字为准
     */
    data class Entry(
        val name: String,
        val rawName: ByteArray,
        val kind: Kind,
        val platform: Int,
        val unixMode: Int,
        val method: Int,
        val flags: Int,
        val compressedSize: Long,
        val uncompressedSize: Long,
        val localHeaderOffset: Long,
        val externalAttributes: Long,
    ) {
        val isEncrypted: Boolean get() = flags and FLAG_ENCRYPTED != 0

        val usesDataDescriptor: Boolean get() = flags and FLAG_DATA_DESCRIPTOR != 0
    }

    /**
     * 读取 [file] 的中央目录并逐个校验本地头，返回按中央目录顺序排列的条目。
     *
     * 顺序即 `ZipFile.entries()` 的顺序，上层按**下标**与解压器结果配对，
     * 这样即使文件名编码解码方式不同也不会错位（两处读的是同一份中央目录）。
     */
    fun audit(file: File): List<Entry> {
        RandomAccessFile(file, "r").use { raf ->
            val fileLength = raf.length()
            if (fileLength < EOCD_MIN_SIZE) {
                throw ZipFormatException("文件过小，不是有效的 zip 压缩包")
            }

            // EOCD 固定 22 字节 + 最长 64 KiB 注释；从尾部倒着找签名
            val tailLength = minOf(fileLength, (EOCD_MIN_SIZE + EOCD_MAX_COMMENT).toLong()).toInt()
            val tail = readAt(raf, fileLength - tailLength, tailLength)
            val eocd = findEocd(tail) ?: throw ZipFormatException("找不到中央目录结尾记录（EOCD）")

            // ZIP64 用 EOCD64 locator 紧贴在 EOCD 之前标记；Live2D 模型不可能到 4 GB，直接拒绝
            if (eocd >= 20 && u32(tail, eocd - 20) == SIG_EOCD64_LOCATOR) {
                throw ZipFormatException("不支持 ZIP64 压缩包")
            }

            val diskNumber = u16(tail, eocd + 4)
            val centralDirDisk = u16(tail, eocd + 6)
            val entriesOnDisk = u16(tail, eocd + 8)
            val entriesTotal = u16(tail, eocd + 10)
            val centralDirSize = u32(tail, eocd + 12)
            val centralDirOffset = u32(tail, eocd + 16)

            if (diskNumber != 0 || centralDirDisk != 0 || entriesOnDisk != entriesTotal) {
                throw ZipFormatException("不支持分卷 zip")
            }
            if (entriesTotal == 0xFFFF || centralDirSize == ZIP64_SENTINEL || centralDirOffset == ZIP64_SENTINEL) {
                throw ZipFormatException("不支持 ZIP64 压缩包")
            }
            if (centralDirSize > MAX_CENTRAL_DIR_SIZE) {
                throw ZipFormatException("中央目录异常大（$centralDirSize 字节）")
            }
            if (centralDirOffset + centralDirSize > fileLength) {
                throw ZipFormatException("中央目录越界")
            }

            val centralDir = readAt(raf, centralDirOffset, centralDirSize.toInt())
            val entries = ArrayList<Entry>(entriesTotal)
            var pos = 0
            repeat(entriesTotal) {
                if (pos + CENTRAL_HEADER_SIZE > centralDir.size) {
                    throw ZipFormatException("中央目录被截断")
                }
                if (u32(centralDir, pos) != SIG_CENTRAL) {
                    throw ZipFormatException("中央目录条目签名错误")
                }
                val madeBy = u16(centralDir, pos + 4)
                val flags = u16(centralDir, pos + 8)
                val method = u16(centralDir, pos + 10)
                val compressedSize = u32(centralDir, pos + 20)
                val uncompressedSize = u32(centralDir, pos + 24)
                val nameLength = u16(centralDir, pos + 28)
                val extraLength = u16(centralDir, pos + 30)
                val commentLength = u16(centralDir, pos + 32)
                val externalAttributes = u32(centralDir, pos + 38)
                val localHeaderOffset = u32(centralDir, pos + 42)

                val next = pos + CENTRAL_HEADER_SIZE + nameLength + extraLength + commentLength
                if (next > centralDir.size) {
                    throw ZipFormatException("中央目录条目越界")
                }
                if (compressedSize == ZIP64_SENTINEL ||
                    uncompressedSize == ZIP64_SENTINEL ||
                    localHeaderOffset == ZIP64_SENTINEL
                ) {
                    throw ZipFormatException("不支持 ZIP64 条目")
                }
                if (nameLength == 0) {
                    throw ZipFormatException("空条目名")
                }

                val rawName = centralDir.copyOfRange(
                    pos + CENTRAL_HEADER_SIZE,
                    pos + CENTRAL_HEADER_SIZE + nameLength,
                )
                val platform = madeBy ushr 8
                val unixMode = if (platform == PLATFORM_UNIX || platform == PLATFORM_OSX) {
                    ((externalAttributes ushr 16) and 0xFFFF).toInt()
                } else {
                    0
                }

                entries += Entry(
                    name = String(rawName, Charsets.UTF_8),
                    rawName = rawName,
                    kind = classify(platform, unixMode, externalAttributes, rawName),
                    platform = platform,
                    unixMode = unixMode,
                    method = method,
                    flags = flags,
                    compressedSize = compressedSize,
                    uncompressedSize = uncompressedSize,
                    localHeaderOffset = localHeaderOffset,
                    externalAttributes = externalAttributes,
                )
                pos = next
            }

            for (entry in entries) validateLocalHeader(raf, fileLength, entry)
            return entries
        }
    }

    /**
     * 判断条目类型。
     *
     * Unix（含 macOS）包按 `st_mode` 的类型位判断；FAT/NTFS 包没有 unix mode，
     * 退回「名字以 '/' 结尾」或 DOS 目录属性位——两者都不满足即普通文件。
     * 注意 `S_IFMT` 为空（0）也是合法状态（部分打包工具不写类型位），按名字兜底。
     */
    private fun classify(platform: Int, unixMode: Int, externalAttributes: Long, rawName: ByteArray): Kind {
        val endsWithSeparator = rawName.isNotEmpty() &&
            (rawName[rawName.size - 1] == '/'.code.toByte() || rawName[rawName.size - 1] == '\\'.code.toByte())

        if (platform == PLATFORM_UNIX || platform == PLATFORM_OSX) {
            return when (unixMode and S_IFMT) {
                S_IFDIR -> Kind.DIRECTORY
                S_IFLNK -> Kind.SYMLINK
                S_IFCHR, S_IFBLK, S_IFIFO, S_IFSOCK -> Kind.DEVICE
                S_IFREG, 0 -> if (endsWithSeparator) Kind.DIRECTORY else Kind.REGULAR
                else -> Kind.OTHER
            }
        }

        val dosDirectory = (externalAttributes and DOS_ATTRIBUTE_DIRECTORY) != 0L
        return if (endsWithSeparator || dosDirectory) Kind.DIRECTORY else Kind.REGULAR
    }

    /**
     * 校验本地头与中央目录一致。
     *
     * 文件名按字节比较（不解码，绕开编码差异）；算法与加密位必须一致；
     * 未使用数据描述符（bit 3）时，两处长度也必须一致。
     */
    private fun validateLocalHeader(raf: RandomAccessFile, fileLength: Long, entry: Entry) {
        val offset = entry.localHeaderOffset
        if (offset < 0 || offset + LOCAL_HEADER_SIZE > fileLength) {
            throw ZipFormatException("本地文件头越界: ${entry.name}")
        }
        val header = readAt(raf, offset, LOCAL_HEADER_SIZE)
        if (u32(header, 0) != SIG_LOCAL) {
            throw ZipFormatException("本地文件头签名错误: ${entry.name}")
        }
        val localFlags = u16(header, 6)
        val localMethod = u16(header, 8)
        val localCompressedSize = u32(header, 18)
        val localUncompressedSize = u32(header, 22)
        val nameLength = u16(header, 26)
        val extraLength = u16(header, 28)

        if (localMethod != entry.method) {
            throw ZipFormatException("本地头压缩算法与中央目录不一致: ${entry.name}")
        }
        if ((localFlags xor entry.flags) and FLAG_ENCRYPTED != 0) {
            throw ZipFormatException("本地头加密标志与中央目录不一致: ${entry.name}")
        }
        if (nameLength != entry.rawName.size) {
            throw ZipFormatException("本地头文件名长度与中央目录不一致: ${entry.name}")
        }
        val localName = readAt(raf, offset + LOCAL_HEADER_SIZE, nameLength)
        if (!localName.contentEquals(entry.rawName)) {
            throw ZipFormatException("本地头文件名与中央目录不一致: ${entry.name}")
        }
        if (!entry.usesDataDescriptor) {
            if (localCompressedSize != entry.compressedSize || localUncompressedSize != entry.uncompressedSize) {
                throw ZipFormatException("本地头长度与中央目录不一致: ${entry.name}")
            }
        }

        val dataStart = offset + LOCAL_HEADER_SIZE + nameLength + extraLength
        if (dataStart + entry.compressedSize > fileLength) {
            throw ZipFormatException("压缩数据越界: ${entry.name}")
        }
    }

    /**
     * 从尾部倒着找 EOCD，并校验注释长度确实一直延伸到文件末尾
     * （否则文件数据里偶然出现的同签名会被误判成目录结尾）。
     */
    private fun findEocd(tail: ByteArray): Int {
        var index = tail.size - EOCD_MIN_SIZE
        while (index >= 0) {
            if (u32(tail, index) == SIG_EOCD && index + EOCD_MIN_SIZE <= tail.size) {
                val commentLength = u16(tail, index + 20)
                if (index + EOCD_MIN_SIZE + commentLength == tail.size) return index
            }
            index--
        }
        return -1
    }

    private fun readAt(raf: RandomAccessFile, offset: Long, length: Int): ByteArray {
        val out = ByteArray(length)
        raf.seek(offset)
        raf.readFully(out)
        return out
    }

    private fun u16(bytes: ByteArray, offset: Int): Int =
        (bytes[offset].toInt() and 0xFF) or ((bytes[offset + 1].toInt() and 0xFF) shl 8)

    private fun u32(bytes: ByteArray, offset: Int): Long =
        (bytes[offset].toLong() and 0xFF) or
            ((bytes[offset + 1].toLong() and 0xFF) shl 8) or
            ((bytes[offset + 2].toLong() and 0xFF) shl 16) or
            ((bytes[offset + 3].toLong() and 0xFF) shl 24)
}

/** zip 结构非法 / 解析差异 / 不支持的变体。 */
internal class ZipFormatException(message: String) : IOException(message)
