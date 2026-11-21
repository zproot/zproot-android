package com.zproot

import android.content.Context
import android.system.Os
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest

object RootfsManager {
    private const val ALPINE_VERSION = "3.24.4"
    private const val ALPINE_ARCHIVE = "alpine-minirootfs-$ALPINE_VERSION-aarch64.tar.gz"
    private const val ALPINE_URL =
        "https://dl-cdn.alpinelinux.org/alpine/v3.24/releases/aarch64/$ALPINE_ARCHIVE"
    private const val ALPINE_SHA512 =
        "aa4a4161d4a97db889c47f85a965602f25725974a981af8a80d4511d1655a05cf6650a8276cf5c9ec39c8fd2070dd762ceeed228cef9e9d86e892070d06348f8"
    private const val MAX_ARCHIVE_BYTES = 64L * 1024L * 1024L
    private const val INSTALL_MARKER = ".zproot-install"
    private const val INSTALL_ID = "alpine-$ALPINE_VERSION-aarch64-sha512:$ALPINE_SHA512"

    fun rootfsDir(context: Context): File = File(context.filesDir, "rootfs")

    fun isInstalled(context: Context): Boolean {
        val root = rootfsDir(context)
        val marker = File(root, INSTALL_MARKER)
        val release = File(root, "etc/alpine-release")
        return marker.isFile && marker.readText().trim() == INSTALL_ID &&
            release.isFile && release.readText().trim().startsWith("$ALPINE_VERSION") &&
            File(root, "bin/sh").exists()
    }

    /** Installs into a staging directory and promotes it only after integrity and layout checks pass. */
    @Synchronized
    fun install(context: Context, onLog: (String) -> Unit): Boolean {
        val appContext = context.applicationContext
        val files = appContext.filesDir
        val destination = rootfsDir(appContext)
        val staging = File(files, "rootfs.installing")
        val backup = File(files, "rootfs.previous")
        val archive = File(appContext.cacheDir, "$ALPINE_ARCHIVE.part")
        try {
            if (!RootfsTransaction.restorePrevious(destination, backup)) {
                throw IllegalStateException("Could not restore previous rootfs")
            }
            if (isInstalled(appContext)) {
                if (backup.exists() && !RootfsTransaction.removeBackupAfterConfirmedCommit(destination, backup, true)) {
                    onLog("Installed rootfs is valid; retaining backup because cleanup failed")
                }
                onLog("Alpine $ALPINE_VERSION is already installed at ${destination.absolutePath}")
                return true
            }

            onLog("Downloading Alpine $ALPINE_VERSION aarch64 minirootfs...")
            download(archive, onLog)
            verifySha512(archive)
            onLog("SHA-512 verified")
            val symlinkRewrites = AlpineTarValidator.validateGzipTar(archive)
            onLog("Archive paths and links validated (${symlinkRewrites.size} absolute links will be rootfs-normalized)")

            if (staging.exists() && !staging.deleteRecursively()) {
                throw IllegalStateException("Could not clear stale staging directory")
            }
            if (!staging.mkdirs()) throw IllegalStateException("Could not create staging directory")
            extract(archive, staging, onLog)
            normalizeAbsoluteSymlinks(staging, symlinkRewrites)
            validateRootfs(staging)
            File(staging, INSTALL_MARKER).writeText(INSTALL_ID + "\n")

            if (!RootfsTransaction.promote(staging, destination, backup)) {
                throw IllegalStateException("Could not atomically install staged rootfs")
            }
            if (backup.exists()) {
                onLog("New rootfs committed; retaining previous backup because cleanup failed")
            }

            onLog("Alpine $ALPINE_VERSION installed successfully")
            return true
        } catch (e: Exception) {
            onLog("Install failed: ${e.javaClass.simpleName}: ${e.message ?: "unknown error"}")
            return false
        } finally {
            if (staging.exists()) staging.deleteRecursively()
            if (archive.exists()) archive.delete()
        }
    }

    private fun download(destination: File, onLog: (String) -> Unit) {
        if (destination.exists() && !destination.delete()) {
            throw IllegalStateException("Could not clear partial archive")
        }
        val connection = (URL(ALPINE_URL).openConnection() as HttpURLConnection).apply {
            connectTimeout = 20_000
            readTimeout = 60_000
            instanceFollowRedirects = true
            useCaches = false
            requestMethod = "GET"
        }
        try {
            connection.connect()
            require(connection.url.protocol.equals("https", ignoreCase = true)) {
                "Archive redirect did not use HTTPS"
            }
            require(connection.responseCode == HttpURLConnection.HTTP_OK) {
                "Archive server returned HTTP ${connection.responseCode}"
            }
            require(connection.contentLengthLong <= MAX_ARCHIVE_BYTES) { "Archive exceeds size limit" }
            var received = 0L
            connection.inputStream.use { input ->
                FileOutputStream(destination).use { output ->
                    val buffer = ByteArray(32 * 1024)
                    while (true) {
                        val count = input.read(buffer)
                        if (count < 0) break
                        received += count
                        require(received <= MAX_ARCHIVE_BYTES) { "Archive exceeds size limit" }
                        output.write(buffer, 0, count)
                    }
                    output.fd.sync()
                }
            }
            require(received > 0) { "Downloaded archive is empty" }
            onLog("Downloaded ${received / 1024} KiB")
        } catch (e: Exception) {
            destination.delete()
            throw e
        } finally {
            connection.disconnect()
        }
    }

    private fun verifySha512(archive: File) {
        val digest = MessageDigest.getInstance("SHA-512")
        FileInputStream(archive).use { input ->
            val buffer = ByteArray(32 * 1024)
            while (true) {
                val count = input.read(buffer)
                if (count < 0) break
                digest.update(buffer, 0, count)
            }
        }
        val actual = digest.digest().joinToString("") { byte -> "%02x".format(byte.toInt() and 0xff) }
        require(actual == ALPINE_SHA512) { "Alpine archive checksum mismatch" }
    }

    private fun extract(archive: File, destination: File, onLog: (String) -> Unit) {
        onLog("Extracting Alpine rootfs...")
        val process = ProcessBuilder(
            "/system/bin/toybox", "tar", "-xzf", archive.absolutePath, "-C", destination.absolutePath,
        ).redirectErrorStream(true).start()
        val message = process.inputStream.bufferedReader().use { it.readText() }
        val exitCode = process.waitFor()
        if (exitCode != 0) {
            throw IllegalStateException("toybox tar exited $exitCode: ${message.take(2000)}")
        }
    }

    private fun validateRootfs(root: File) {
        require(File(root, "etc/alpine-release").isFile) { "Archive lacks etc/alpine-release" }
        val release = File(root, "etc/alpine-release").readText().trim()
        require(release.startsWith("$ALPINE_VERSION")) { "Unexpected Alpine release: $release" }
        require(File(root, "bin/sh").exists()) { "Archive lacks bin/sh" }
        require(File(root, "bin/busybox").exists() || File(root, "usr/bin/busybox").exists()) {
            "Archive lacks BusyBox shell implementation"
        }
    }

    private fun normalizeAbsoluteSymlinks(root: File, rewrites: List<SymlinkRewrite>) {
        for (rewrite in rewrites) {
            val link = File(root, rewrite.path)
            Os.unlink(link.absolutePath)
            Os.symlink(rewrite.relativeTarget, link.absolutePath)
        }
    }
}
