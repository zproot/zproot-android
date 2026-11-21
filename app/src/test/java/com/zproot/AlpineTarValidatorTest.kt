package com.zproot

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.util.zip.GZIPInputStream
import java.util.zip.GZIPOutputStream
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class AlpineTarValidatorTest {
    @Test
    fun acceptsRegularFilesAndSafeSymlinks() {
        val archive = tarGzip(
            entry("etc/alpine-release", '0', "3.24.4\n".toByteArray()),
            entry("bin", '5'),
            entry("bin/sh", '0', byteArrayOf(1, 2, 3)),
            entry("bin/shell", '2', link = "/bin/busybox"),
            entry("bin/relative-shell", '2', link = "sh"),
        )
        val rewrites = GZIPInputStream(ByteArrayInputStream(archive)).use(AlpineTarValidator::validateTar)
        assertEquals(listOf(SymlinkRewrite("bin/shell", "busybox")), rewrites)
    }

    @Test
    fun rejectsParentTraversalMemberNames() {
        val archive = tarGzip(entry("../outside", '0', byteArrayOf(1)))
        assertThrows(IllegalArgumentException::class.java) {
            GZIPInputStream(ByteArrayInputStream(archive)).use(AlpineTarValidator::validateTar)
        }
    }

    @Test
    fun rejectsSymlinkTargetsThatEscapeRootfs() {
        val absoluteEscape = tarGzip(entry("lib", '2', link = "/../../system/lib"))
        assertThrows(IllegalArgumentException::class.java) {
            GZIPInputStream(ByteArrayInputStream(absoluteEscape)).use(AlpineTarValidator::validateTar)
        }

        val relativeEscape = tarGzip(entry("usr/lib/link", '2', link = "../../../../outside"))
        assertThrows(IllegalArgumentException::class.java) {
            GZIPInputStream(ByteArrayInputStream(relativeEscape)).use(AlpineTarValidator::validateTar)
        }
    }

    @Test
    fun rejectsEntriesNestedBeneathSymlinks() {
        val archive = tarGzip(
            entry("lib", '2', link = "usr/lib"),
            entry("lib/escape", '0', byteArrayOf(1)),
        )
        assertThrows(IllegalArgumentException::class.java) {
            GZIPInputStream(ByteArrayInputStream(archive)).use(AlpineTarValidator::validateTar)
        }
    }

    @Test
    fun rejectsHardLinkSourceBeneathAbsoluteSymlink() {
        val archive = tarGzip(
            entry("lib", '2', link = "/usr/lib"),
            entry("bin/library-copy", '1', link = "lib/library.so"),
        )
        val error = assertThrows(IllegalArgumentException::class.java) {
            GZIPInputStream(ByteArrayInputStream(archive)).use(AlpineTarValidator::validateTar)
        }
        org.junit.Assert.assertTrue(error.message!!.contains("Hard-link target is beneath symlink"))
    }

    @Test
    fun acceptsHardLinkToArchiveRegularFileAndRejectsMissingTarget() {
        val valid = tarGzip(
            entry("usr/lib/library.so", '0', byteArrayOf(1, 2, 3)),
            entry("usr/lib/library-copy.so", '1', link = "usr/lib/library.so"),
        )
        GZIPInputStream(ByteArrayInputStream(valid)).use(AlpineTarValidator::validateTar)

        val invalid = tarGzip(entry("usr/lib/library-copy.so", '1', link = "usr/lib/missing.so"))
        val error = assertThrows(IllegalArgumentException::class.java) {
            GZIPInputStream(ByteArrayInputStream(invalid)).use(AlpineTarValidator::validateTar)
        }
        org.junit.Assert.assertTrue(error.message!!.contains("not an in-archive regular file"))
    }

    @Test
    fun rejectsCorruptedHeaderChecksum() {
        val rawTar = entry("etc/alpine-release", '0', "3.24.4\n".toByteArray()) + ByteArray(1024)
        rawTar[0] = (rawTar[0].toInt() xor 0x20).toByte()
        val corrupted = gzip(rawTar)
        assertThrows(Exception::class.java) {
            GZIPInputStream(ByteArrayInputStream(corrupted)).use(AlpineTarValidator::validateTar)
        }
    }

    private fun tarGzip(vararg entries: ByteArray): ByteArray {
        val tar = ByteArrayOutputStream()
        entries.forEach(tar::write)
        tar.write(ByteArray(1024))
        return gzip(tar.toByteArray())
    }

    private fun gzip(tar: ByteArray): ByteArray {
        val output = ByteArrayOutputStream()
        GZIPOutputStream(output).use { it.write(tar) }
        return output.toByteArray()
    }

    private fun entry(path: String, type: Char, payload: ByteArray = byteArrayOf(), link: String = ""): ByteArray {
        val header = ByteArray(512)
        putString(header, 0, 100, path)
        putOctal(header, 100, 8, 0x1a4L)
        putOctal(header, 108, 8, 0)
        putOctal(header, 116, 8, 0)
        putOctal(header, 124, 12, payload.size.toLong())
        putOctal(header, 136, 12, 0)
        for (i in 148 until 156) header[i] = 32
        header[156] = type.code.toByte()
        putString(header, 157, 100, link)
        putString(header, 257, 6, "ustar")
        putString(header, 263, 2, "00")
        val checksum = header.sumOf { it.toInt() and 0xff }
        val checksumText = "%06o".format(checksum)
        putString(header, 148, 6, checksumText)
        header[154] = 0
        header[155] = 32

        val result = ByteArrayOutputStream()
        result.write(header)
        result.write(payload)
        val padding = (512 - payload.size % 512) % 512
        result.write(ByteArray(padding))
        return result.toByteArray()
    }

    private fun putString(target: ByteArray, offset: Int, length: Int, value: String) {
        val bytes = value.toByteArray(Charsets.UTF_8)
        require(bytes.size <= length)
        bytes.copyInto(target, offset)
    }

    private fun putOctal(target: ByteArray, offset: Int, length: Int, value: Long) {
        val digits = "%0${length - 1}o".format(value)
        putString(target, offset, length - 1, digits)
        target[offset + length - 1] = 0
    }
}
