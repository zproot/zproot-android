package com.zproot

import java.io.File

/** Filesystem-only rootfs promotion and recovery operations, kept separate for JVM testing. */
internal object RootfsTransaction {
    /** Restore a stranded previous rootfs when the live destination is absent. */
    fun restorePrevious(destination: File, backup: File): Boolean {
        if (!backup.exists() || destination.exists()) return true
        if (!backup.renameTo(destination)) return false
        return destination.exists() && !backup.exists()
    }

    /** Promote a validated staging tree; retain the backup unless promotion/restoration succeeds. */
    fun promote(staging: File, destination: File, backup: File): Boolean {
        if (backup.exists()) return false

        var movedLiveRootfs = false
        if (destination.exists()) {
            if (!destination.renameTo(backup)) return false
            if (!backup.exists() || destination.exists()) return false
            movedLiveRootfs = true
        }

        if (!staging.renameTo(destination)) {
            if (movedLiveRootfs && !destination.exists()) {
                restorePrevious(destination, backup)
            }
            return false
        }
        if (!destination.exists()) {
            if (movedLiveRootfs) restorePrevious(destination, backup)
            return false
        }

        // The staging tree is live now. A failed cleanup leaves a recoverable backup in place.
        if (movedLiveRootfs) backup.deleteRecursively()
        return true
    }

    /** Delete a backup only when the caller has independently confirmed the live install. */
    fun removeBackupAfterConfirmedCommit(
        destination: File,
        backup: File,
        commitConfirmed: Boolean,
    ): Boolean {
        if (!commitConfirmed || !destination.exists()) return false
        return !backup.exists() || backup.deleteRecursively()
    }
}
