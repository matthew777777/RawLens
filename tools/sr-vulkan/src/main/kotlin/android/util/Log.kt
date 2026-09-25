// SPDX-FileCopyrightText: 2026 RawLens contributors
// SPDX-License-Identifier: GPL-3.0-or-later
//
// DESKTOP HOST SHIM (sr-vulkan only, never shipped on Android). Mirrors the
// android.util.Log surface used by the ported SR core so those files compile
// byte-identical on the JVM. Diagnostics go to stderr; unlike release Android,
// debug logs are ON by default (this is a developer iteration tool) unless
// -Dsrvulkan.quiet=1. Compile errors here mean ported code grew new Log uses:
// extend this shim, never the ported files.
package android.util

object Log {
    const val VERBOSE = 2
    const val DEBUG = 3
    const val INFO = 4
    const val WARN = 5
    const val ERROR = 6
    const val ASSERT = 7

    private val quiet = System.getProperty("srvulkan.quiet") == "1"

    @JvmStatic fun v(tag: String, msg: String): Int = emit(VERBOSE, tag, msg, null)
    @JvmStatic fun v(tag: String, msg: String, tr: Throwable): Int = emit(VERBOSE, tag, msg, tr)
    @JvmStatic fun d(tag: String, msg: String): Int = emit(DEBUG, tag, msg, null)
    @JvmStatic fun d(tag: String, msg: String, tr: Throwable): Int = emit(DEBUG, tag, msg, tr)
    @JvmStatic fun i(tag: String, msg: String): Int = emit(INFO, tag, msg, null)
    @JvmStatic fun i(tag: String, msg: String, tr: Throwable): Int = emit(INFO, tag, msg, tr)
    @JvmStatic fun w(tag: String, msg: String): Int = emit(WARN, tag, msg, null)
    @JvmStatic fun w(tag: String, msg: String, tr: Throwable): Int = emit(WARN, tag, msg, tr)
    @JvmStatic fun e(tag: String, msg: String): Int = emit(ERROR, tag, msg, null)
    @JvmStatic fun e(tag: String, msg: String, tr: Throwable): Int = emit(ERROR, tag, msg, tr)

    @JvmStatic
    fun isLoggable(tag: String, level: Int): Boolean = !quiet && level >= DEBUG

    @JvmStatic
    fun println(priority: Int, tag: String, msg: String): Int = emit(priority, tag, msg, null)

    private fun emit(priority: Int, tag: String, msg: String, tr: Throwable?): Int {
        if (quiet && priority < WARN) return 0
        val level = when (priority) {
            VERBOSE -> "V"
            DEBUG -> "D"
            INFO -> "I"
            WARN -> "W"
            ERROR, ASSERT -> "E"
            else -> "?"
        }
        System.err.println("$level/$tag: $msg")
        tr?.printStackTrace(System.err)
        return 0
    }
}
