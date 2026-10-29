// SPDX-License-Identifier: GPL-3.0-or-later
package com.matthew.rawlens

/**
 * Drop-in for `android.util.Log` across this package: every call still goes
 * to logcat, and is also teed to [LogcatFileWriter] so the session file
 * keeps RawLens's own lines on ROMs whose logd hands the app nothing back
 * (Xiaomi 14T Pro field report: a session holding only the silence marker).
 * Files in this package resolve `Log` here by not importing android.util.Log.
 */
internal object Log {
    const val DEBUG = android.util.Log.DEBUG

    fun d(tag: String, msg: String): Int = emit('D', tag, msg, null) { android.util.Log.d(tag, msg) }
    fun d(tag: String, msg: String, tr: Throwable?): Int = emit('D', tag, msg, tr) { android.util.Log.d(tag, msg, tr) }
    fun i(tag: String, msg: String): Int = emit('I', tag, msg, null) { android.util.Log.i(tag, msg) }
    fun i(tag: String, msg: String, tr: Throwable?): Int = emit('I', tag, msg, tr) { android.util.Log.i(tag, msg, tr) }
    fun w(tag: String, msg: String): Int = emit('W', tag, msg, null) { android.util.Log.w(tag, msg) }
    fun w(tag: String, msg: String, tr: Throwable?): Int = emit('W', tag, msg, tr) { android.util.Log.w(tag, msg, tr) }
    fun w(tag: String, tr: Throwable?): Int = emit('W', tag, "", tr) { android.util.Log.w(tag, tr) }
    fun e(tag: String, msg: String): Int = emit('E', tag, msg, null) { android.util.Log.e(tag, msg) }
    fun e(tag: String, msg: String, tr: Throwable?): Int = emit('E', tag, msg, tr) { android.util.Log.e(tag, msg, tr) }

    fun isLoggable(tag: String, level: Int): Boolean = android.util.Log.isLoggable(tag, level)
    fun getStackTraceString(tr: Throwable?): String = android.util.Log.getStackTraceString(tr)

    private inline fun emit(level: Char, tag: String, msg: String, tr: Throwable?, platform: () -> Int): Int {
        LogcatFileWriter.tee(level, tag, msg, tr)
        return platform()
    }
}
