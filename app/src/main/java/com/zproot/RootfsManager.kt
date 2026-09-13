package com.zproot

import android.content.Context
import java.io.File
import java.net.URL

object RootfsManager {

    private const val ALPINE_URL =
        "https://dl-cdn.alpinelinux.org/alpine/v3.20/releases/aarch64/alpine-minirootfs-3.20.3-aarch64.tar.gz"

    fun rootfsDir(context: Context): File = File(context.filesDir, "rootfs")

    fun isInstalled(context: Context): Boolean =
        File(rootfsDir(context), "bin/sh").exists()

    fun install(context: Context, onLog: (String) -> Unit) {
        try {
            val dest = rootfsDir(context)
            if (isInstalled(context)) {
                onLog("Alpine already installed at ${dest.absolutePath}")
                return
            }

            val cache = File(context.cacheDir, "alpine.tar.gz")

            onLog("Downloading Alpine minirootfs...")
            URL(ALPINE_URL).openStream().use { input ->
                cache.outputStream().use { output -> input.copyTo(output) }
            }
            onLog("Downloaded ${cache.length()} bytes")

            dest.mkdirs()
            onLog("Extracting to ${dest.absolutePath}")

            val pb = ProcessBuilder(
                "/system/bin/toybox", "tar",
                "-xzf", cache.absolutePath,
                "-C", dest.absolutePath,
            )
            pb.redirectErrorStream(true)
            val proc = pb.start()
            val text = proc.inputStream.bufferedReader().readText()
            val code = proc.waitFor()

            cache.delete()

            if (code != 0) {
                onLog("Extraction failed (exit=$code)")
                onLog(text)
                return
            }

            onLog("Extraction ok")
            onLog("sh exists: ${File(dest, "bin/sh").exists()}")
        } catch (e: Exception) {
            onLog("Error: ${e.javaClass.simpleName}: ${e.message}")
            e.stackTrace.take(5).forEach { onLog("  at $it") }
        }
    }
}
