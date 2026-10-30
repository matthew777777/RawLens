// SPDX-License-Identifier: GPL-3.0-or-later
package com.matthew.rawlens

import java.io.IOException

/** Completion ordering for one file: durability, close, publication. Never copies data. */
internal object RecordingCompletion {
    fun finish(valid: Boolean, bytes: Long, sync: () -> Unit, close: () -> Unit,
               publish: () -> Unit, deleteEmpty: () -> Unit): Boolean {
        var failure: Exception? = null
        fun attempt(action: () -> Unit) {
            try { action() } catch (e: Exception) {
                if (failure == null) failure = e else failure?.addSuppressed(e)
            }
        }
        if (valid && bytes != 0L) attempt(sync)
        attempt(close)
        if (bytes == 0L) {
            // Only known-empty files may be removed. Unknown size can mean a detached drive.
            attempt(deleteEmpty)
        } else {
            // Retain and expose nonempty partial takes even after a failed flush.
            attempt(publish)
        }
        failure?.let { throw IOException("Recording finalization failed; file retained", it) }
        return valid && bytes > 0L
    }
}
