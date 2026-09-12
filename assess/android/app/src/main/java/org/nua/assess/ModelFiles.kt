package org.nua.assess

import java.io.File

/** Keep the previous model until initialization of its replacement succeeds. */
object ModelFiles {
    fun recover(installed: File) {
        val backup = File(installed.path + ".previous")
        if (backup.exists()) {
            check(!installed.exists() || installed.delete()) { "Could not remove interrupted model import." }
            check(backup.renameTo(installed)) { "Could not restore previous model." }
        }
    }

    fun replace(candidate: File, installed: File, removePrevious: (File) -> Boolean = { it.delete() }, initialize: () -> Boolean) {
        val backup = File(installed.path + ".previous")
        check(!backup.exists()) { "Previous import needs recovery." }
        val hadPrevious = installed.exists()
        if (hadPrevious) check(installed.renameTo(backup)) { "Could not preserve previous model." }
        try {
            check(candidate.renameTo(installed)) { "Could not store new model." }
            check(initialize()) { "New model is incompatible or exceeds available memory." }
            // Cleanup is part of the transaction. If it fails, restore the old
            // model now rather than reporting failure with a new model active.
            if (hadPrevious) check(removePrevious(backup)) { "Previous-model cleanup failed; replacement rolled back." }
        } catch (failure: Exception) {
            if (installed.exists()) check(installed.delete()) { "Could not roll back model import. Restart the app." }
            if (hadPrevious) check(backup.renameTo(installed)) { "Could not restore previous model. Restart the app." }
            throw failure
        }
    }
}
