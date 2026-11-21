package com.zproot

import java.io.File
import java.io.FileInputStream
import java.io.InputStream
import java.util.zip.GZIPInputStream

internal data class SymlinkRewrite(val path: String, val relativeTarget: String)

/** Validates the tar member names before Android's tar implementation extracts an archive. */
internal object AlpineTarValidator {
    private const val BLOCK_SIZE = 512
    private const val MAX_ENTRIES = 100_000
    private const val MAX_UNCOMPRESSED_BYTES = 512L * 1024L * 1024L

    private data class Entry(
        val path: String,
        val type: Char,
        val absoluteTarget: String?,
        val hardLinkTarget: String? = null,
    )

    fun validateGzipTar(archive: File): List<SymlinkRewrite> {
        return GZIPInputStream(FileInputStream(archive)).use(::validateTar)
    }

    internal fun validateTar(input: InputStream): List<SymlinkRewrite> {
        val entries = ArrayList<Entry>()
        val seenPaths = HashSet<String>()
        var totalPayload = 0L
        var sawEnd = false

        while (true) {
            val header = readBlock(input) ?: throw IllegalArgumentException("Truncated tar header")
            if (header.all { it == 0.toByte() }) {
                val second = readBlock(input) ?: throw IllegalArgumentException("Truncated tar end marker")
                require(second.all { it == 0.toByte() }) { "Invalid tar end marker" }
                sawEnd = true
                break
            }
            require(entries.size < MAX_ENTRIES) { "Too many tar members" }
            verifyHeaderChecksum(header)

            val name = readString(header, 0, 100)
            val prefix = readString(header, 345, 155)
            val rawPath = if (prefix.isEmpty()) name else "$prefix/$name"
            val path = safeMemberPath(rawPath)
            require(seenPaths.add(path)) { "Duplicate tar member path: $rawPath" }
            val type = header[156].toInt().toChar().let { if (it == '\u0000') '0' else it }
            val size = readOctal(header, 124, 12)
            val linkName = readString(header, 157, 100)

            require(size >= 0) { "Negative tar member size" }
            totalPayload = Math.addExact(totalPayload, size)
            require(totalPayload <= MAX_UNCOMPRESSED_BYTES) { "Tar payload exceeds size limit" }

            val absoluteTarget = if (type == '2') validateSymlinkTarget(path, linkName) else null
            val hardLinkTarget = if (type == '1') safeMemberPath(linkName) else null
            when (type) {
                '0' -> require(path.isNotEmpty()) { "Empty regular-file path" }
                '5' -> require(size == 0L) { "Directory has a payload" }
                '2' -> {
                    require(size == 0L) { "Symlink has a payload" }
                }
                '1' -> {
                    require(size == 0L) { "Hard link has a payload" }
                }
                else -> throw IllegalArgumentException("Unsupported tar member type: $type")
            }
            entries.add(Entry(path, type, absoluteTarget, hardLinkTarget))

            val paddedSize = Math.multiplyExact((size + BLOCK_SIZE - 1) / BLOCK_SIZE, BLOCK_SIZE.toLong())
            skipFully(input, paddedSize)
        }

        require(sawEnd) { "Tar end marker missing" }
        val symlinkPaths = entries.filter { it.type == '2' }.map { it.path }.toHashSet()
        val regularFilePaths = entries.filter { it.type == '0' }.map { it.path }.toHashSet()
        for (entry in entries) {
            var parent = entry.path.substringBeforeLast('/', "")
            while (parent.isNotEmpty()) {
                require(parent !in symlinkPaths) {
                    "Tar member is nested beneath symlink: ${entry.path}"
                }
                parent = parent.substringBeforeLast('/', "")
            }
        }
        for (entry in entries) {
            val target = entry.hardLinkTarget ?: continue
            require(!isBeneathSymlink(target, symlinkPaths)) {
                "Hard-link target is beneath symlink: ${entry.path} -> $target"
            }
            require(target in regularFilePaths) {
                "Hard-link target is not an in-archive regular file: ${entry.path} -> $target"
            }
        }

        return entries.mapNotNull { entry ->
            val target = entry.absoluteTarget ?: return@mapNotNull null
            SymlinkRewrite(entry.path, relativeSymlinkTarget(entry.path, target))
        }
    }

    private fun verifyHeaderChecksum(header: ByteArray) {
        val expected = readOctal(header, 148, 8)
        var actual = 0L
        for (i in header.indices) {
            actual += if (i in 148 until 156) 32 else (header[i].toInt() and 0xff)
        }
        require(expected == actual) { "Tar header checksum mismatch" }
    }

    private fun safeMemberPath(raw: String): String {
        require(raw.isNotEmpty()) { "Empty tar path" }
        require(!raw.startsWith('/')) { "Absolute tar path rejected: $raw" }
        require(!raw.contains('\\')) { "Backslash in tar path rejected" }
        val parts = raw.split('/')
        require(parts.none { it == ".." }) { "Parent traversal in tar path rejected: $raw" }
        return parts.filter { it.isNotEmpty() && it != "." }.joinToString("/")
    }

    /** Returns a normalized root-relative target only when the original target was absolute. */
    private fun validateSymlinkTarget(path: String, target: String): String? {
        require(target.isNotEmpty()) { "Empty symlink target" }
        require(!target.contains('\\')) { "Backslash in symlink target rejected" }
        val resolved = ArrayList<String>()
        val absolute = target.startsWith('/')
        if (!absolute) {
            val parent = path.substringBeforeLast('/', "")
            if (parent.isNotEmpty()) resolved.addAll(parent.split('/').filter { it.isNotEmpty() })
        }
        for (part in target.split('/')) {
            when (part) {
                "", "." -> Unit
                ".." -> {
                    require(resolved.isNotEmpty()) { "Symlink escapes rootfs: $path -> $target" }
                    resolved.removeAt(resolved.lastIndex)
                }
                else -> resolved.add(part)
            }
        }
        return if (absolute) resolved.joinToString("/") else null
    }

    private fun relativeSymlinkTarget(linkPath: String, rootTarget: String): String {
        val parent = linkPath.substringBeforeLast('/', "")
        val from = if (parent.isEmpty()) emptyList() else parent.split('/')
        val to = if (rootTarget.isEmpty()) emptyList() else rootTarget.split('/')
        var common = 0
        while (common < from.size && common < to.size && from[common] == to[common]) common++
        val parts = List(from.size - common) { ".." } + to.drop(common)
        return if (parts.isEmpty()) "." else parts.joinToString("/")
    }

    private fun isBeneathSymlink(path: String, symlinkPaths: Set<String>): Boolean {
        var parent = path.substringBeforeLast('/', "")
        while (parent.isNotEmpty()) {
            if (parent in symlinkPaths) return true
            parent = parent.substringBeforeLast('/', "")
        }
        return false
    }

    private fun readString(header: ByteArray, offset: Int, length: Int): String {
        var end = offset
        val limit = offset + length
        while (end < limit && header[end] != 0.toByte()) end++
        return header.copyOfRange(offset, end).toString(Charsets.UTF_8).trimEnd(' ')
    }

    private fun readOctal(header: ByteArray, offset: Int, length: Int): Long {
        var value = 0L
        var sawDigit = false
        for (i in offset until offset + length) {
            val b = header[i].toInt() and 0xff
            if (b == 0 || b == 32) {
                if (sawDigit) break
                continue
            }
            require(b in '0'.code..'7'.code) { "Invalid octal tar field" }
            sawDigit = true
            value = Math.addExact(Math.multiplyExact(value, 8L), (b - '0'.code).toLong())
        }
        return value
    }

    private fun readBlock(input: InputStream): ByteArray? {
        val block = ByteArray(BLOCK_SIZE)
        var offset = 0
        while (offset < block.size) {
            val count = input.read(block, offset, block.size - offset)
            if (count < 0) {
                if (offset == 0) return null
                throw IllegalArgumentException("Truncated tar block")
            }
            if (count == 0) continue
            offset += count
        }
        return block
    }

    private fun skipFully(input: InputStream, count: Long) {
        var remaining = count
        val scratch = ByteArray(8192)
        while (remaining > 0) {
            val read = input.read(scratch, 0, minOf(scratch.size.toLong(), remaining).toInt())
            if (read < 0) throw IllegalArgumentException("Truncated tar member")
            if (read == 0) continue
            remaining -= read
        }
    }
}
