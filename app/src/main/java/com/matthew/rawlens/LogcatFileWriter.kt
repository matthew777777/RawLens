// SPDX-License-Identifier: GPL-3.0-or-later
package com.matthew.rawlens

import android.content.ContentUris
import android.content.ContentValues
import android.content.Context
import android.net.Uri
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
 * No permission is needed for any half of the design:
 * - Continuous capture reads `logcat --pid=<own pid>`, which the platform
 *   already scopes to this UID, and appends to the app-private external
 *   folder (`getExternalFilesDir`, always writable, no runtime grant).
 * - A mirror of the live session is upserted into `Download/RawLens/logs/`
 *   via the Downloads collection (same no-permission pattern as
 *   [BurstSidecar]), throttled to 30 s and forced on rotation/stop/crash,
 *   so the file is always there with no tap. Sharing reuses the mirror.
 *
 * Crash coverage comes from chaining the default uncaught-exception handler:
 * the crash block is written and `sync()`ed before the previous handler takes
 * over and kills the process, so the file on disk always ends with the fatal
 * stack trace; the crash flag plus a forced mirror mean the log reaches
 * Downloads even when the app can never launch again. Files rotate at
 * [MAX_BYTES] and old sessions are pruned to [KEEP_SESSIONS], so a stuck loop
 * can never fill storage. If the logcat child dies on its own it is
 * respawned (bounded), so capture resumes by itself.
 */
internal object LogcatFileWriter {
    private const val TAG = "LogcatFileWriter"
    const val DIR_NAME = "logcat"
    const val FILE_PREFIX = "logcat-"
    const val FILE_EXTENSION = "txt"
    const val MAX_BYTES = 8L * 1024L * 1024L
    const val KEEP_SESSIONS = 5
    /** Flag left behind by a dying process so the next launch can offer the crash log. */
    const val CRASH_MARKER = "last-crash.txt"
    private const val FLUSH_EVERY_LINES = 50
    private const val EXPORT_DIR = "Download/RawLens/logs/"
    /** Continuous Downloads mirror cadence; rotation/stop/crash always force one. */
    const val MIRROR_INTERVAL_MS = 30_000L
    private const val MAX_RESPAWNS = 3

    private val lock = Any()
    private var process: Process? = null
    private var readerThread: Thread? = null
    private var writer: BufferedWriter? = null
    private var stream: FileOutputStream? = null
    private var currentFile: File? = null
    private var logDir: File? = null
    private var packageName: String? = null
    private var appContext: Context? = null
    private var pid = 0
    private var lastMirrorMs = 0L
    @Volatile private var mirrorError: String? = null
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

    /** Session-shaped names only; also rejects path traversal for export-by-name. */
    fun isSessionName(name: String): Boolean =
        name.startsWith(FILE_PREFIX) && name.endsWith(".$FILE_EXTENSION") &&
            '/' !in name && '\\' !in name

    /** Names beyond the newest [keep] session files, oldest first. */
    fun sessionsToDelete(names: List<String>, keep: Int): List<String> {
        val sessions = names.filter(::isSessionName).sorted()
        if (sessions.size <= keep) return emptyList()
        return sessions.subList(0, sessions.size - keep)
    }

    /** Interval gate for the Downloads mirror; rotation/stop/crash bypass it. */
    fun mirrorDue(lastMirrorMs: Long, nowMs: Long): Boolean =
        nowMs - lastMirrorMs >= MIRROR_INTERVAL_MS

    /**
     * Reads and clears the crash flag left by [recordCrash]. Resolves the log
     * dir itself so the flag is honored even when capture is toggled off.
     * Returns the crashed session's file name, or null on a clean shutdown.
     */
    fun consumeCrashMarker(context: Context): String? = synchronized(lock) {
        val dir = logDir ?: resolveDir(context)?.also { logDir = it } ?: return null
        val marker = File(dir, CRASH_MARKER)
        val name = runCatching {
            marker.takeIf { it.isFile }?.readText(Charsets.UTF_8)?.trim()
        }.getOrNull()?.takeIf { !it.isNullOrEmpty() && isSessionName(it) }
        runCatching { marker.delete() }
        name
    }

    /** One-line state for the Settings diagnostics row (fresh on every open). */
    fun statusLine(): String = synchronized(lock) {
        val file = currentFile
        val sizeKb = file?.takeIf { it.isFile }?.length()?.div(1024) ?: 0L
        val mirror = mirrorError?.let { "Downloads mirror failed ($it)" }
            ?: if (lastMirrorMs > 0L) "Downloads mirror: ok" else "Downloads mirror: pending"
        "running=$running file=${file?.name ?: "none"} ${sizeKb}KB, $mirror"
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
                val dir = resolveDir(context) ?: run {
                    Log.w(TAG, "No writable storage for log dir")
                    return
                }
                logDir = dir
                appContext = context.applicationContext
                packageName = context.packageName
                openSessionLocked(dir)
                if (!handlerInstalled) {
                    // Once per process: re-capturing on a later start() would
                    // chain our own handler and record every crash twice.
                    previousHandler = Thread.getDefaultUncaughtExceptionHandler()
                    Thread.setDefaultUncaughtExceptionHandler { thread, error ->
                        recordCrash(thread, error)
                        previousHandler?.uncaughtException(thread, error)
                    }
                    handlerInstalled = true
                }
                pid = android.os.Process.myPid()
                spawnLocked()
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

    /**
     * External app-private folder preferred (large logs don't touch internal
     * storage); internal fallback when external is unavailable. Both need no
     * permission. Null only when neither root exists at all.
     */
    private fun resolveDir(context: Context): File? {
        context.getExternalFilesDir(null)?.let { return File(it, DIR_NAME) }
        return File(context.filesDir, DIR_NAME)
    }

    /** (Re)starts the logcat child; caller must hold [lock]. */
    private fun spawnLocked() {
        runCatching { process?.destroy() }
        process = ProcessBuilder("logcat", "-v", "threadtime", "--pid=$pid")
            .redirectErrorStream(true)
            .start()
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
        // Leave the complete session in Downloads even when logging is off.
        maybeMirror(force = true)
    }

    /**
     * Copies the current session into Downloads for the Files app / sharing.
     * Returns the MediaStore URI, or null when there is nothing to export.
     */
    @Throws(IOException::class)
    fun exportLatestToDownloads(context: Context): Uri? {
        val source = synchronized(lock) {
            flushLocked()
            currentFile?.takeIf { it.isFile && it.length() > 0L }
        } ?: return null
        return exportFile(context, source)
    }

    /**
     * Exports a previous session (e.g. the crashed one from [consumeCrashMarker])
     * so a crash-on-launch log still reaches Downloads without opening settings.
     */
    @Throws(IOException::class)
    fun exportSessionToDownloads(context: Context, sessionName: String): Uri? {
        require(isSessionName(sessionName)) { "Not a log session: $sessionName" }
        val source = synchronized(lock) {
            flushLocked()
            val dir = logDir ?: throw IOException("Log writer never started")
            File(dir, sessionName).takeIf { it.isFile && it.length() > 0L }
        } ?: return null
        return exportFile(context, source)
    }

    @Throws(IOException::class)
    private fun exportFile(context: Context, source: File): Uri {
        val resolver = context.contentResolver
        val collection = MediaStore.Downloads.getContentUri("external")
        val values = ContentValues().apply {
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

    /**
     * Mirrors the current session into `Download/RawLens/logs/` so the file is
     * always there without any tap. Throttled to [MIRROR_INTERVAL_MS] unless
     * [force] (rotation is covered by cadence; stop/crash force). Lock is held
     * only for state; the MediaStore I/O runs outside it.
     */
    private fun maybeMirror(force: Boolean) {
        val ctx = appContext ?: return
        val source: File = synchronized(lock) {
            if (!force && !mirrorDue(lastMirrorMs, System.currentTimeMillis())) return
            val file = currentFile?.takeIf { it.isFile && it.length() > 0L } ?: return
            lastMirrorMs = System.currentTimeMillis()
            file
        }
        val error = mirrorFile(ctx, source)
        synchronized(lock) { mirrorError = error }
    }

    /**
     * Upserts one Downloads entry per session name: rewrites the existing row
     * when the session was mirrored before, inserts (pending-guarded) on the
     * first mirror. Returns null on success, a short reason otherwise.
     */
    private fun mirrorFile(ctx: Context, source: File): String? {
        return try {
            val resolver = ctx.contentResolver
            val collection = MediaStore.Downloads.getContentUri("external")
            var uri: Uri? = null
            resolver.query(
                collection,
                arrayOf(MediaStore.Downloads._ID),
                "${MediaStore.Downloads.DISPLAY_NAME}=?",
                arrayOf(source.name),
                null
            )?.use { cursor ->
                if (cursor.moveToFirst()) {
                    uri = ContentUris.withAppendedId(collection, cursor.getLong(0))
                }
            }
            if (uri == null) {
                val values = ContentValues().apply {
                    put(MediaStore.Downloads.DISPLAY_NAME, source.name)
                    put(MediaStore.Downloads.MIME_TYPE, "text/plain")
                    put(MediaStore.Downloads.RELATIVE_PATH, EXPORT_DIR)
                    put(MediaStore.Downloads.IS_PENDING, 1)
                }
                val inserted = resolver.insert(collection, values)
                    ?: return "insert returned null"
                try {
                    resolver.openOutputStream(inserted, "w")?.use { out ->
                        source.inputStream().use { input -> input.copyTo(out) }
                    } ?: return "open stream failed".also { resolver.delete(inserted, null, null) }
                    values.clear()
                    values.put(MediaStore.Downloads.IS_PENDING, 0)
                    if (resolver.update(inserted, values, null, null) != 1) {
                        return "publish failed"
                    }
                } catch (failure: Exception) {
                    resolver.delete(inserted, null, null)
                    throw failure
                }
            } else {
                val target = uri ?: return "query returned null"
                resolver.openOutputStream(target, "w")?.use { out ->
                    source.inputStream().use { input -> input.copyTo(out) }
                } ?: return "open stream failed"
            }
            null
        } catch (failure: Exception) {
            "${failure.javaClass.simpleName}: ${failure.message}"
        }
    }

    /**
     * Copies logcat stdout into the session file. If the child dies on its own
     * (never on [stop], which clears [running] first), it is respawned with a
     * 1 s backoff up to [MAX_RESPAWNS] times so capture resumes by itself.
     */
    private fun pumpLoop() {
        var restarts = 0
        while (running) {
            val input = synchronized(lock) { process?.inputStream } ?: break
            var wantRespawn = false
            try {
                BufferedReader(InputStreamReader(input, Charsets.UTF_8)).use { reader ->
                    while (running) {
                        val line = try {
                            reader.readLine()
                        } catch (failure: IOException) {
                            if (running) Log.w(TAG, "Logcat read failed", failure)
                            break
                        } ?: break
                        appendLine(line)
                    }
                }
                wantRespawn = running
            } catch (failure: Throwable) {
                Log.w(TAG, "Logcat reader failed", failure)
                wantRespawn = running
            }
            if (!wantRespawn) break
            if (++restarts > MAX_RESPAWNS) {
                Log.w(TAG, "Logcat respawn budget spent; capture stopped")
                break
            }
            try {
                Thread.sleep(1000L)
            } catch (failure: InterruptedException) {
                Thread.currentThread().interrupt()
                break
            }
            val respawned = synchronized(lock) {
                if (!running) false else try {
                    spawnLocked()
                    true
                } catch (failure: Throwable) {
                    Log.w(TAG, "Logcat respawn failed", failure)
                    false
                }
            }
            if (!respawned) break
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
                maybeMirror(force = false)
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
                // Flag the crashed session for the next launch: if the app dies
                // on startup the user can never reach Settings, so the next
                // run auto-exports this file and offers to share it.
                runCatching {
                    val name = currentFile?.name
                    if (logDir != null && name != null) {
                        File(requireNotNull(logDir), CRASH_MARKER).writeText(name, Charsets.UTF_8)
                    }
                }
                // Mirror the crashed session even if the app can never launch
                // again. Best-effort MediaStore I/O; failures only surface in
                // the next run's status line, never here.
                maybeMirror(force = true)
            } catch (failure: Throwable) {
                Log.w(TAG, "Recording crash to log file failed", failure)
            }
        }
    }

    private fun openSessionLocked(dir: File) {
        closeLocked()
        if (!dir.isDirectory && !dir.mkdirs()) throw IOException("Cannot create log dir $dir")
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
