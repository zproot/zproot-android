package com.zproot

import java.io.File
import java.nio.file.Files
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RootfsTransactionTest {
    @Test
    fun restoresBackupWhenRenameWasInterruptedBeforeInstallingStaging() = withTempDir { root ->
        val destination = File(root, "rootfs")
        val backup = File(root, "rootfs.previous")
        assertTrue(backup.mkdir())
        File(backup, "sentinel").writeText("previous rootfs")

        assertTrue(RootfsTransaction.restorePrevious(destination, backup))

        assertTrue(destination.isDirectory)
        assertEquals("previous rootfs", File(destination, "sentinel").readText())
        assertFalse(backup.exists())
    }

    @Test
    fun restoresLiveRootfsWhenStagingPromotionFails() = withTempDir { root ->
        val destination = File(root, "rootfs")
        val backup = File(root, "rootfs.previous")
        val staging = File(root, "missing-staging")
        assertTrue(destination.mkdir())
        File(destination, "sentinel").writeText("previous rootfs")

        assertFalse(RootfsTransaction.promote(staging, destination, backup))

        assertTrue(destination.isDirectory)
        assertEquals("previous rootfs", File(destination, "sentinel").readText())
        assertFalse(backup.exists())
    }

    @Test
    fun commitsStagingBeforeRemovingPreviousBackup() = withTempDir { root ->
        val destination = File(root, "rootfs")
        val backup = File(root, "rootfs.previous")
        val staging = File(root, "rootfs.installing")
        assertTrue(destination.mkdir())
        File(destination, "sentinel").writeText("old rootfs")
        assertTrue(staging.mkdir())
        File(staging, "sentinel").writeText("new rootfs")

        assertTrue(RootfsTransaction.promote(staging, destination, backup))

        assertEquals("new rootfs", File(destination, "sentinel").readText())
        assertFalse(backup.exists())
    }

    @Test
    fun refusesToDeleteBackupWithoutConfirmedCommit() = withTempDir { root ->
        val destination = File(root, "rootfs")
        val backup = File(root, "rootfs.previous")
        assertTrue(destination.mkdir())
        assertTrue(backup.mkdir())
        File(backup, "sentinel").writeText("recoverable")

        assertFalse(RootfsTransaction.removeBackupAfterConfirmedCommit(destination, backup, false))
        assertTrue(backup.exists())
        assertTrue(RootfsTransaction.removeBackupAfterConfirmedCommit(destination, backup, true))
        assertFalse(backup.exists())
    }

    @Test
    fun refusesPromotionInsteadOfDiscardingExistingRecoveryBackup() = withTempDir { root ->
        val destination = File(root, "rootfs")
        val backup = File(root, "rootfs.previous")
        val staging = File(root, "rootfs.installing")
        assertTrue(destination.mkdir())
        assertTrue(backup.mkdir())
        assertTrue(staging.mkdir())
        File(backup, "sentinel").writeText("recoverable")

        assertFalse(RootfsTransaction.promote(staging, destination, backup))

        assertEquals("recoverable", File(backup, "sentinel").readText())
        assertTrue(destination.exists())
        assertTrue(staging.exists())
    }

    private fun withTempDir(block: (File) -> Unit) {
        val directory = Files.createTempDirectory("zproot-transaction-test").toFile()
        try {
            block(directory)
        } finally {
            directory.deleteRecursively()
        }
    }
}
