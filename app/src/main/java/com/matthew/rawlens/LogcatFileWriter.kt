// SPDX-License-Identifier: GPL-3.0-or-later
package com.matthew.rawlens

import android.content.Context
import android.os.Build
import android.provider.MediaStore
import android.util.Log
import java.io.BufferedReader
import java.io.BufferedWriter
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.io.InputStreamReader
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone

/**
 * Streams the app's own logcat into a session text file that survives crashes.
 *
 * No permission is needed for either half of the design:
 * - Continuous capture reads `logcat --pid=<own pid>`, which the platform
 *   already scopes to this UID, and appends to the app-private external
 *   folder (`getExternalFilesDir`, always writable, no runtime grant).
 * - Sharing copies the latest session into `Download/RawLens/logs/` via the
 *   Downloads collection, the same no-permission pattern as [BurstSidecar].
 *
 * Crash coverage comes from chaining the default uncaught-exception handler:
 * the crash block is written and `sync()`ed before the previous handler takes
 * over and kills the process, so the file on disk always ends with the fatal
 * stack trace. Files rotate at [MAX_BYTES] and old sessions are pruned to
 * [KEEP_SESSIONS], so a stuck loop can never fill storage.
 */
internal object LogcatFileWriter {
    private const val TAG = "LogcatFileWriter"
    const val DIR_NAME = "logcat"
    const val FILE_PREFIX = "logcat-"
    const val FILE_EXTENSION = "txt"
    const val MAX_BYTES = 8L * 1024L * 1024L
    const val KEEP_SESSIONS = 5
    private const val FLUSH_EVERY_LINES = 50
    private const val EXPORT_DIR = "Download/RawLens/logs/"

    private val lock = Any()
    private var process: Process? = null
    private var readerThread: Thread? = null
    private var writer: BufferedWriter? = null
    private var stream: FileOutputStream? = null
    private var currentFile: File? = null
    private var logDir: File? = null
    private var packageName: String? = null
    private var bytesWritten = 0L
    private var linesSinceFlush = 0
    @Volatile private var running = false
    @Volatile private var previousHandler: Thread.UncaughtExceptionHandler? = null
    private var handlerInstalled = false

    /** `logcat-20260921_143005_042.txt`, sortable so pruning keeps the newest. */
    fun sessionFileName(captureTimeMillis: Long): String {
        val stem = SimpleDateFormat("yyyyMMdd_HHmmss_SSS", Locale.US).apply {
            timeZone = TimeZone.getDefault()
        }.format(Date(captureTimeMillis))
        return "$FILE_PREFIX$stem.$FILE_EXTENSION"
    }

    /** Names beyond the newest [keep] session files, oldest first. */
    fun sessionsToDelete(names: List<String>, keep: Int): List<String> {
        val sessions = names.filter { it.startsWith(FILE_PREFIX) && it.endsWith(".$FILE_EXTENSION") }.sorted()
        if (sessions.size <= keep) return emptyList()
        return sessions.subList(0, sessions.size - keep)
    }

    fun isRunning(): Boolean = running

    fun currentSessionPath(): String? = synchronized(lock) { currentFile?.absolutePath }

    /**
     * Starts streaming. Idempotent; safe to call on every launch. Failures are
     * logged to logcat and leave the writer stopped rather than crashing setup.
     */
    fun start(context: Context) {
        synchronized(lock) {
            if (running) return
            try {
                val dir = File(context.getExternalFilesDir(null), DIR_NAME)
                    .takeIf { it != null } ?: File(context.filesDir, DIR_NAME)
                if (!dir.isDirectory && !dir.mkdirs()) {
                    Log.w(TAG, "Cannot create log dir $dir")
                    return
                }
                logDir = dir
                packageName = context.packageName
                openSessionLocked(dir)
                previousHandler = Thread.getDefaultUncaughtExceptionHandler()
                if (!handlerInstalled) {
                    Thread.setDefaultUncaughtExceptionHandler { thread, error ->
                        recordCrash(thread, error)
                        previousHandler?.uncaughtException(thread, error)
                    }
                    handlerInstalled = true
                }
                val pid = android.os.Process.myPid()
                process = ProcessBuilder("logcat", "-v", "threadtime", "--pid=$pid")
                    .redirectErrorStream(true)
                    .start()
                running = true
                readerThread = Thread(::pumpLoop, "logcat-file").apply {
                    isDaemon = true
                    start()
                }
            } catch (failure: Throwable) {
                Log.w(TAG, "Logcat file capture unavailable", failure)
                closeLocked()
            }
        }
    }

    fun stop() {
        synchronized(lock) {
            if (!running) return
            running = false
            try {
                process?.destroy()
            } catch (failure: Throwable) {
                Log.w(TAG, "Destroying logcat process failed", failure)
            } finally {
                process = null
            }
        }
        try {
            readerThread?.join(2000L)
        } catch (failure: InterruptedException) {
            Thread.currentThread().interrupt()
        } finally {
            readerThread = null
        }
        synchronized(lock) { closeLocked() }
    }

    /**
     * Copies the current session into Downloads for the Files app / sharing.
     * Returns the MediaStore URI, or null when there is nothing to export.
     */
    @Throws(IOException::class)
    fun exportLatestToDownloads(context: Context): android.net.Uri? {
        val source = synchronized(lock) {
            flushLocked()
            currentFile?.takeIf { it.isFile && it.length() > 0L }
        } ?: return null
        val resolver = context.contentResolver
        val collection = MediaStore.Downloads.getContentUri("external")
        val values = android.content.ContentValues().apply {
            put(MediaStore.Downloads.DISPLAY_NAME, source.name)
            put(MediaStore.Downloads.MIME_TYPE, "text/plain")
            put(MediaStore.Downloads.RELATIVE_PATH, EXPORT_DIR)
            put(MediaStore.Downloads.IS_PENDING, 1)
        }
        val uri = resolver.insert(collection, values)
            ?: throw IOException("Could not create log export entry")
        try {
            resolver.openOutputStream(uri, "w")?.use { out ->
                source.inputStream().use { input -> input.copyTo(out) }
            } ?: throw IOException("Could not open log export stream")
            values.clear()
            values.put(MediaStore.Downloads.IS_PENDING, 0)
            if (resolver.update(uri, values, null, null) != 1) {
                throw IOException("Could not publish log export")
            }
        } catch (failure: Exception) {
            resolver.delete(uri, null, null)
            throw failure
        }
        return uri
    }

    private fun pumpLoop() {
        val reader = try {
            BufferedReader(InputStreamReader(process?.inputStream ?: return, Charsets.UTF_8))
        } catch (failure: Throwable) {
            Log.w(TAG, "Logcat reader setup failed", failure)
            return
        }
        try {
            var line: String?
            while (running) {
                line = try {
                    reader.readLine()
                } catch (failure: IOException) {
                    if (running) Log.w(TAG, "Logcat read failed", failure)
                    break
                } ?: break
                appendLine(line)
            }
        } finally {
            runCatching { reader.close() }
        }
    }

    private fun appendLine(line: String) {
        synchronized(lock) {
            if (!running) return
            try {
                if (bytesWritten >= MAX_BYTES) openSessionLocked(requireNotNull(logDir))
                writer?.let {
                    it.write(line)
                    it.newLine()
                    bytesWritten += line.toByteArray(Charsets.UTF_8).size + 1
                    if (++linesSinceFlush >= FLUSH_EVERY_LINES) flushLocked()
                }
            } catch (failure: IOException) {
                Log.w(TAG, "Appending log line failed", failure)
            }
        }
    }

    private fun recordCrash(thread: Thread, error: Throwable) {
        synchronized(lock) {
            try {
                if (writer == null && logDir != null && !running) {
                    // Writer was toggled off or never started: still leave a
                    // crash trace next to the sessions rather than losing it.
                    running = true
                    openSessionLocked(requireNotNull(logDir))
                }
                writer?.let {
                    it.write("----- FATAL EXCEPTION on ${thread.name} -----")
                    it.newLine()
                    it.write(Log.getStackTraceString(error))
                    it.newLine()
                    it.write("----- process dying, log ends here -----")
                    it.newLine()
                    it.flush()
                }
                stream?.fd?.sync()
            } catch (failure: Throwable) {
                Log.w(TAG, "Recording crash to log file failed", failure)
            }
        }
    }

    private fun openSessionLocked(dir: File) {
        closeLocked()
        pruneLocked(dir)
        val file = File(dir, sessionFileName(System.currentTimeMillis()))
        stream = FileOutputStream(file, true)
        writer = stream!!.bufferedWriter(Charsets.UTF_8)
        currentFile = file
        bytesWritten = 0L
        linesSinceFlush = 0
        writer?.let {
            it.write("RawLens log session ${file.name}")
            it.newLine()
            it.write(header(packageName))
            it.newLine()
            it.flush()
        }
    }

    private fun header(packageName: String?): String {
        return try {
            "package=$packageName model=${Build.MANUFACTURER} ${Build.MODEL} " +
                "sdk=${Build.VERSION.SDK_INT} fingerprint=${Build.FINGERPRINT}"
        } catch (failure: Throwable) {
            "package=$packageName (header unavailable: $failure)"
        }
    }

    private fun pruneLocked(dir: File) {
        val names = dir.list()?.toList() ?: return
        for (stale in sessionsToDelete(names, KEEP_SESSIONS - 1)) {
            runCatching { File(dir, stale).delete() }
        }
    }

    private fun flushLocked() {
        try {
            writer?.flush()
            linesSinceFlush = 0
        } catch (failure: IOException) {
            Log.w(TAG, "Flushing log file failed", failure)
        }
    }

    private fun closeLocked() {
        flushLocked()
        runCatching { writer?.close() }
        runCatching { stream?.close() }
        writer = null
        stream = null
    }
}
