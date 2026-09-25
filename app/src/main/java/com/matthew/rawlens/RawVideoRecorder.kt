// SPDX-License-Identifier: GPL-3.0-or-later
package com.matthew.rawlens

import android.content.Context
import android.hardware.camera2.CameraCaptureSession
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraDevice
import android.hardware.camera2.CameraManager
import android.hardware.camera2.CameraMetadata
import android.hardware.camera2.CaptureRequest
import android.hardware.camera2.params.OutputConfiguration
import android.hardware.camera2.params.SessionConfiguration
import android.media.Image
import android.media.ImageReader
import android.os.Build
import android.os.Handler
import android.os.HandlerThread
import android.os.SystemClock
import android.util.Log
import android.util.Range
import android.util.Size
import java.io.File
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicLongArray

/**
 * RAW Video recorder: a standalone TEMPLATE_RECORD RAW-only session that
 * writes one `.mcraw` file (type-7 frames, PCM16 audio, gyro/accel motion).
 *
 * Pipeline (60fps-ready by design, 30fps sustained on MT6878 Open Gate):
 * - Camera thread acquires, assigns sequence numbers, fans out to the live
 *   Vulkan VF, and queues bounded (cap 8, drop-oldest): cadence over latency.
 * - N encode workers ([encodeWorkers], default 2, up to 4) encode RAW16
 *   planes concurrently into pooled payload buffers (~15ms effective at 2x).
 * - One committer drains a sequence-numbered reorder buffer strictly in
 *   capture order (the container rejects timestamp regressions), plus audio
 *   and motion. A wedged head is skipped after [SKIP_TIMEOUT_MS] so one
 *   slow frame costs a gap, never a stall; footer == encoded - skipped.
 * - HAL RAW16 plane -> encoder -> container, no per-frame files (this is
 *   the PhotonCamera gap: no thread-per-DNG, no MediaStore churn).
 * - [crop] switches Open Gate / 16:9 / 2.39:1 / 2.00:1 via the encoder's
 *   crop args with no session reconfiguration. Safe to change
 *   mid-recording (fps locks at record start).
 * - P4 streams: PCM16 mono audio ([AudioPcmRecorder]) and continuous
 *   gyro/accel ([MotionRecorder]) share the video frame timeline
 *   (boot-time ns). All container writes happen on the committer — the
 *   native writer has no locking. Missing streams are legal: denial/missing
 *   hardware records silent/still video instead of failing.
 * - Live preview fan-out: frames are adopted into [RawImageOwnership] and
 *   offered to the RAW viewfinder ([attachViewfinder]) on the camera thread
 *   while workers encode the same frames. The Vulkan zero-copy GPU path
 *   samples the same gralloc buffer through a lease; the encoders read the
 *   same plane; neither copies, neither blocks the other.
 *
 * This class owns its camera session; it is independent of
 * [RawCameraController]'s stills/ZSL session. Only one may hold the camera.
 */
internal class RawVideoRecorder(
    private val cameraManager: CameraManager,
    private val cameraId: String,
    val frameSize: Size,
    private val appContext: Context? = null,
) {
    data class Stats(
        val framesAcquired: Int,
        val framesEncoded: Int,
        val framesDropped: Int,
        /** Encoded but never committed (reorder skip/timeout): footer == encoded - skipped. */
        val commitSkipped: Int,
        val audioChunks: Int,
        val audioFrames: Long,
        val audioDropped: Int,
        val gyroSamples: Long,
        val accelSamples: Long,
        val hasAudio: Boolean,
        val hasMotion: Boolean,
        val containerFrames: Long,
        val fileBytes: Long,
    )

    /** Live feed for the video debug overlay (cf. RawVfStats/snapshot). */
    data class VideoStats(
        val recording: Boolean,
        val elapsedMs: Long,
        val fps: Float,
        val frameIntervalMs: Float,
        val encodeMsAvg: Float,
        val encodeMsMax: Float,
        val framesAcquired: Int,
        val framesEncoded: Int,
        val framesDropped: Int,
        val commitSkipped: Int,
        val queueDepth: Int,
        val reorderDepth: Int,
        val fileBytes: Long,
        val audioChunks: Int,
        val audioFrames: Long,
        val gyroSamples: Long,
        val accelSamples: Long,
        val cropLabel: String,
    )

    /** Encode worker count, set before [start]. 2 sustains 30fps Open Gate
     * on MT6878; 3-4 targets 60fps gates on stronger SoCs (more heat). */
    var encodeWorkers: Int = 2

    /** Switchable any time, including mid-recording; no session reconfig. */
    @Volatile var crop: VideoCrop = VideoCrop.OPEN_GATE

    /**
     * Live preview fan-out: when attached, every acquired frame is offered to
     * the RAW viewfinder on the camera thread (its own contract) while the
     * encode thread encodes the same frame. Zero-copy is preserved — the GPU
     * path samples the same gralloc buffer through a registry lease, the
     * encoder reads the same plane, neither copies. The viewfinder already
     * sustains full-res 30 fps in stills mode, so recording adds only the
     * encoder thread, on separate cores.
     */
    @Volatile private var vfView: RawViewfinder? = null
    private var vfChars: CameraCharacteristics? = null

    fun attachViewfinder(view: RawViewfinder?) {
        vfView = view
    }

    /** Set false before [start] to record silent video without asking for the mic. */
    var audioEnabled: Boolean = true

    /** Set false before [start] to skip motion logging. */
    var motionEnabled: Boolean = true

    private var camThread: HandlerThread? = null
    private var camHandler: Handler? = null
    private var device: CameraDevice? = null
    private var session: CameraCaptureSession? = null
    private var reader: ImageReader? = null
    private var encodeThreads: Array<Thread?> = emptyArray()
    private var commitThread: Thread? = null
    private var audio: AudioPcmRecorder? = null
    private var motion: MotionRecorder? = null
    private var writerHandle: Long = 0L
    private var outputFile: File? = null
    private var startRealtimeMs: Long = 0L

    private data class EncodeTask(
        val seq: Long,
        val image: Image,
        val src: java.nio.ByteBuffer,
        val size: Int,
        val width: Int,
        val height: Int,
        val stride: Int,
        val cropTop: Int,
        val cropHeight: Int,
        val timestampNs: Long,
        val frameJson: String,
    )

    private data class PendingFrame(
        val seq: Long,
        val payload: java.nio.ByteBuffer,
        val bytes: Int,
        val timestampNs: Long,
        val frameJson: String,
    )

    private val encodeQueue = ArrayBlockingQueue<Any>(QUEUE_CAP)
    private val audioQueue =
        ArrayBlockingQueue<AudioPcmRecorder.Chunk>(AUDIO_QUEUE_CAP)
    private lateinit var payloadPool: ArrayBlockingQueue<java.nio.ByteBuffer>
    private val reorder = java.util.TreeMap<Long, PendingFrame>()
    private val reorderLock = Object()
    private val workersAlive = AtomicInteger(0)
    private var workerCount = 2
    // In-flight sequence per worker (camera/worker threads write, committer
    // reads) so a timed-out head can be attributed to its slow worker.
    // -1 = idle (sequence numbers start at 0).
    private val inflightSeq = AtomicLongArray(MAX_WORKERS).apply {
        for (i in 0 until MAX_WORKERS) set(i, -1L)
    }
    private val workerParked = java.util.concurrent.atomic.AtomicIntegerArray(MAX_WORKERS)
    private var nextSeq = 0L // camera thread only
    private var nextCommitSeq = 0L // committer thread only
    private val acquired = AtomicInteger(0)
    private val encoded = AtomicInteger(0)
    private val dropped = AtomicInteger(0)
    private val commitSkipped = AtomicInteger(0)
    private val audioChunks = AtomicInteger(0)
    private val audioFrames = AtomicLong(0)
    private val audioDropped = AtomicInteger(0)
    private val gyroSamples = AtomicLong(0)
    private val accelSamples = AtomicLong(0)
    @Volatile private var accepting = false
    @Volatile private var fpsEstimate = 0f
    @Volatile private var intervalMsEstimate = 0f
    @Volatile private var encodeMsAvg = 0f
    @Volatile private var encodeMsMax = 0f
    @Volatile private var hasAudioStream = false
    @Volatile private var hasMotionStream = false
    private var lastFrameTs = -1L // committer thread only

    fun start(output: File, containerMetadataJson: String) {
        check(device == null) { "Already started" }
        require(CinemaRawWriter.available) { "cinemaraw native library unavailable" }
        val workers = encodeWorkers.coerceIn(1, MAX_WORKERS)
        encodeWorkers = workers
        outputFile = output
        acquired.set(0)
        encoded.set(0)
        dropped.set(0)
        commitSkipped.set(0)
        audioChunks.set(0)
        audioFrames.set(0)
        audioDropped.set(0)
        gyroSamples.set(0)
        accelSamples.set(0)
        fpsEstimate = 0f
        encodeMsAvg = 0f
        encodeMsMax = 0f
        lastFrameTs = -1L
        hasAudioStream = false
        hasMotionStream = false
        nextSeq = 0L
        nextCommitSeq = 0L
        synchronized(reorderLock) { reorder.clear() }
        encodeQueue.clear()
        audioQueue.clear()
        workersAlive.set(workers)
        startRealtimeMs = SystemClock.elapsedRealtime()

        val chars = cameraManager.getCameraCharacteristics(cameraId)
        vfChars = chars
        val realtime = chars.get(CameraCharacteristics.SENSOR_INFO_TIMESTAMP_SOURCE) ==
            CameraCharacteristics.SENSOR_INFO_TIMESTAMP_SOURCE_REALTIME
        val orientation = chars.get(CameraCharacteristics.SENSOR_ORIENTATION) ?: 0
        val front = chars.get(CameraCharacteristics.LENS_FACING) ==
            CameraCharacteristics.LENS_FACING_FRONT

        // P4 streams start before the container opens so their presence is
        // recorded in container metadata. Chunks queue behind the first
        // frame; index-based discovery doesn't care about file order.
        if (motionEnabled && appContext != null) {
            val rec = MotionRecorder(appContext, orientation, front, realtime)
            if (rec.start()) {
                motion = rec
                hasMotionStream = true
            }
        } else if (motionEnabled) {
            Log.w(TAG, "motion skipped: no context")
        }
        var audioRate = 0
        if (audioEnabled) {
            val rec = AudioPcmRecorder()
            if (rec.start { chunk -> onAudioChunk(chunk) }) {
                audio = rec
                hasAudioStream = true
                audioRate = AudioPcmRecorder.SAMPLE_RATE
            }
        }
        val meta = buildMetadata(
            containerMetadataJson, realtime, audioRate,
            hasMotionStream, orientation
        )
        writerHandle = try {
            CinemaRawWriter.open(output.absolutePath, meta)
        } catch (e: Exception) {
            shutdownStreams()
            throw e
        }

        val thread = HandlerThread("RawVideoCam").apply { start() }
        camThread = thread
        val handler = Handler(thread.looper)
        camHandler = handler

        val r = ImageReader.newInstance(
            frameSize.width, frameSize.height,
            android.graphics.ImageFormat.RAW_SENSOR, READER_MAX_IMAGES
        )
        reader = r
        // Pooled payload buffers: workers plus two spare so one wedged
        // worker can never starve the others into a pool deadlock (checkout
        // below is non-blocking regardless). Sized for the worst case
        // (near-incompressible frame); overflow drops the frame instead of
        // growing.
        val ew = (frameSize.width + 63) / 64 * 64
        val payloadBound =
            (ew.toLong() * frameSize.height * 2 + ew * frameSize.height / 4 + 65536)
                .coerceAtMost(Int.MAX_VALUE.toLong()).toInt()
        payloadPool = ArrayBlockingQueue(workers + 2)
        repeat(workers + 2) {
            payloadPool.offer(
                java.nio.ByteBuffer.allocateDirect(payloadBound)
                    .order(java.nio.ByteOrder.nativeOrder())
            )
        }
        workerCount = workers
        for (i in 0 until MAX_WORKERS) {
            inflightSeq.set(i, -1L)
            workerParked.set(i, 0)
        }
        accepting = true
        r.setOnImageAvailableListener({ rd ->
            // One at a time: outstanding images stay <= QUEUE_CAP + workers in
            // hand, well under READER_MAX_IMAGES, so backpressure (debug
            // builds, thermal throttle) causes drops — never a reader wedge.
            while (true) {
                val img = try {
                    rd.acquireNextImage()
                } catch (e: IllegalStateException) {
                    Log.w(TAG, "reader saturated under backpressure, pending dropped")
                    break
                } ?: break
                if (!accepting) {
                    RawImageOwnership.release(img)
                    continue
                }
                acquired.incrementAndGet()
                // Viewfinder fan-out (camera thread, the VF's own contract):
                // adopt into the shared lease registry, offer EVERY frame for
                // display (GPU path borrows zero-copy, CPU path samples
                // synchronously), then hand to the encode queue. Workers
                // release the camera ref after encoding; native close fires
                // when the GPU lease (if any) also returns. Never blocks,
                // never copies.
                vfView?.let { view ->
                    try {
                        RawImageOwnership.adopt(img, rd)
                        try {
                            view.offer(img, chars, null)
                        } catch (e: Exception) {
                            Log.w(TAG, "vf offer failed: ${e.message}")
                        }
                    } catch (e: Exception) {
                        Log.w(TAG, "vf adopt failed: ${e.message}")
                    }
                }
                val plane = img.planes[0]
                if (plane.pixelStride != 2) {
                    Log.w(TAG, "dropping packed plane pixelStride=${plane.pixelStride}")
                    RawImageOwnership.release(img)
                    dropped.incrementAndGet()
                    continue
                }
                val resolved = crop.resolve(frameSize.width, frameSize.height)
                val ts = img.timestamp
                val task = EncodeTask(
                    seq = nextSeq++,
                    image = img,
                    src = plane.buffer,
                    size = plane.buffer.remaining(),
                    width = frameSize.width,
                    height = frameSize.height,
                    stride = plane.rowStride,
                    cropTop = resolved.top,
                    cropHeight = resolved.height,
                    timestampNs = ts,
                    frameJson = "{\"width\":${frameSize.width}," +
                        "\"height\":${resolved.height}," +
                        "\"compressionType\":7,\"timestamp\":$ts," +
                        "\"crop\":\"${crop.name}\"}",
                )
                if (!encodeQueue.offer(task)) {
                    // Latest-wins for video: drop the oldest queued frame.
                    // (Poison only enters after accepting=false, when nothing
                    // is offered anymore — but never drop it if seen.)
                    when (val old = encodeQueue.poll()) {
                        is EncodeTask -> RawImageOwnership.release(old.image)
                        null -> Unit
                        else -> encodeQueue.offer(old)
                    }
                    dropped.incrementAndGet()
                    if (!encodeQueue.offer(task)) {
                        RawImageOwnership.release(img)
                        dropped.incrementAndGet()
                    }
                }
            }
        }, handler)

        try {
            device = openCamera(handler)
            session = createSession(handler)
            commitThread = Thread(::commitLoop, "RawVideoCommit").apply { start() }
            encodeThreads = Array(workers) { index ->
                Thread({ workerLoop(index) }, "RawVideoEncode-$index").apply { start() }
            }
            val req = device!!.createCaptureRequest(CameraDevice.TEMPLATE_RECORD).apply {
                addTarget(r.surface)
                set(CaptureRequest.CONTROL_MODE, CameraMetadata.CONTROL_MODE_AUTO)
                set(CaptureRequest.CONTROL_AE_TARGET_FPS_RANGE, Range(FPS, FPS))
                set(CaptureRequest.CONTROL_AE_MODE, CaptureRequest.CONTROL_AE_MODE_ON)
                set(CaptureRequest.CONTROL_AWB_MODE, CaptureRequest.CONTROL_AWB_MODE_AUTO)
            }.build()
            session!!.setRepeatingRequest(req, null, handler)
        } catch (e: Exception) {
            teardown()
            throw e
        }
    }

    fun stop(): Stats {
        accepting = false
        try {
            session?.stopRepeating()
        } catch (_: Exception) {
        }
        // Flush: abort pending captures so the encode thread drains quickly.
        try {
            session?.abortCaptures()
        } catch (_: Exception) {
        }
        // Stop producers first so nothing new enters the queues: audio
        // chunks are all queued before the committer is asked to drain.
        val audioStats = try {
            audio?.stop()
        } catch (_: Exception) {
            null
        }
        audio = null
        val motionStats = try {
            // Sensors off; buffered samples stay queued for the final drain.
            motion?.stop()
        } catch (_: Exception) {
            null
        }
        // One poison per worker; blocking puts complete as workers take.
        try {
            repeat(encodeThreads.size) { encodeQueue.put(Poison) }
        } catch (_: InterruptedException) {
        }
        for (t in encodeThreads) {
            try {
                t?.join(15_000)
            } catch (_: InterruptedException) {
            }
        }
        // The committer exits on its own once workers are done and every
        // queue is drained; interrupt as a backstop so close() can't race it.
        try {
            commitThread?.join(5_000)
        } catch (_: InterruptedException) {
        }
        if (commitThread?.isAlive == true) {
            commitThread?.interrupt()
            try {
                commitThread?.join(5_000)
            } catch (_: InterruptedException) {
            }
        }
        val handle = writerHandle
        writerHandle = 0L
        if (handle != 0L) {
            try {
                CinemaRawWriter.close(handle)
            } catch (e: Exception) {
                Log.w(TAG, "container close failed: ${e.message}")
            }
        }
        teardown()
        return Stats(
            framesAcquired = acquired.get(),
            framesEncoded = encoded.get(),
            framesDropped = dropped.get(),
            commitSkipped = commitSkipped.get(),
            audioChunks = audioChunks.get(),
            audioFrames = audioFrames.get(),
            audioDropped = audioDropped.get(),
            gyroSamples = gyroSamples.get(),
            accelSamples = accelSamples.get(),
            hasAudio = hasAudioStream,
            hasMotion = motionStats != null &&
                (motionStats.gyroSamples > 0 || motionStats.accelSamples > 0),
            containerFrames = -1L, // filled by container footer validation (test/P3 reader)
            fileBytes = outputFile?.length() ?: 0L,
        ).also {
            motion = null
            Log.i(
                TAG, "stop: audio chunks=${audioStats?.chunks} frames=${audioStats?.frames} " +
                    "motion gyro=${motionStats?.gyroSamples} accel=${motionStats?.accelSamples}"
            )
        }
    }

    /** Latest mic peak 0..1 for HUD meters (0 when silent); safe any thread. */
    fun audioLevel(): Float = audio?.lastPeak ?: 0f

    fun snapshot(): VideoStats = VideoStats(
        recording = device != null,
        elapsedMs = if (startRealtimeMs == 0L) 0L
        else SystemClock.elapsedRealtime() - startRealtimeMs,
        fps = fpsEstimate,
        frameIntervalMs = intervalMsEstimate,
        encodeMsAvg = encodeMsAvg,
        encodeMsMax = encodeMsMax,
        framesAcquired = acquired.get(),
        framesEncoded = encoded.get(),
        framesDropped = dropped.get(),
        commitSkipped = commitSkipped.get(),
        queueDepth = encodeQueue.size,
        reorderDepth = synchronized(reorderLock) { reorder.size },
        fileBytes = outputFile?.length() ?: 0L,
        audioChunks = audioChunks.get(),
        audioFrames = audioFrames.get(),
        gyroSamples = gyroSamples.get(),
        accelSamples = accelSamples.get(),
        cropLabel = crop.label,
    )

    fun sensorOrientation(): Int = try {
        cameraManager.getCameraCharacteristics(cameraId)
            .get(CameraCharacteristics.SENSOR_ORIENTATION) ?: 0
    } catch (_: Exception) {
        0
    }

    private fun onAudioChunk(chunk: AudioPcmRecorder.Chunk) {
        // Frames outrank audio under pressure: a full queue drops the 43ms
        // audio chunk (audible gap counter), never a frame. The committer
        // drains this queue every pass, so 32 deep is plenty.
        if (!audioQueue.offer(chunk)) audioDropped.incrementAndGet()
    }

    private fun workerLoop(index: Int) {
        // Urgent-display priority: sustained 30fps pipeline work must stay
        // on big/mid cores. Under thermal pressure the scheduler exiles
        // default-priority threads to little cores where the same encode
        // runs ~10x slower — one exiled worker then poisons every commit
        // head while the other starves on pool checkout.
        android.os.Process.setThreadPriority(android.os.Process.THREAD_PRIORITY_URGENT_DISPLAY)
        while (true) {
            if (workerParked.get(index) != 0) {
                Log.i(TAG, "encode worker $index parked, exitting")
                break
            }
            val item = try {
                encodeQueue.take()
            } catch (_: InterruptedException) {
                break
            }
            if (item === Poison) break
            val task = item as EncodeTask
            inflightSeq.set(index, task.seq)
            var buf: java.nio.ByteBuffer? = null
            try {
                // Non-blocking checkout: if every buffer is pinned behind a
                // missing head, drop this frame instead of wedging forever.
                // The committer keeps skipping and the pipeline self-heals.
                buf = try {
                    payloadPool.poll(POOL_WAIT_MS, TimeUnit.MILLISECONDS)
                } catch (_: InterruptedException) {
                    break
                }
                if (buf == null) {
                    if (dropped.incrementAndGet() % 30 == 1) {
                        Log.w(TAG, "payload pool exhausted, dropping frames")
                    }
                    continue
                }
                val t0 = SystemClock.elapsedRealtimeNanos()
                val bytes = CinemaRawWriter.encodeFrame(
                    task.src, task.size, task.width, task.height, task.stride,
                    task.cropTop, task.cropHeight, buf
                )
                val ms = (SystemClock.elapsedRealtimeNanos() - t0) / 1e6f
                // Benign races on stats: worst case a slightly stale overlay.
                encodeMsAvg =
                    if (encodeMsAvg == 0f) ms else encodeMsAvg * 0.95f + ms * 0.05f
                encodeMsMax =
                    if (ms > encodeMsMax) ms else encodeMsMax * 0.999f + ms * 0.001f
                buf.flip()
                synchronized(reorderLock) {
                    reorder[task.seq] =
                        PendingFrame(task.seq, buf, bytes, task.timestampNs, task.frameJson)
                    buf = null // ownership transferred to the committer
                    reorderLock.notifyAll()
                }
                encoded.incrementAndGet()
            } catch (e: Exception) {
                Log.w(TAG, "frame encode failed: ${e.message}")
                buf?.let { it.clear(); payloadPool.offer(it) }
            } finally {
                inflightSeq.set(index, -1L)
                // Registry-aware: closes directly when never adopted for
                // the VF, waits out the GPU lease when borrowed.
                RawImageOwnership.release(task.image)
            }
        }
        if (workersAlive.decrementAndGet() == 0) {
            Log.i(TAG, "last encode worker exitting")
        }
        synchronized(reorderLock) { reorderLock.notifyAll() }
    }

    /**
     * Single committer: writes frames strictly in capture order (the native
     * writer rejects timestamp regressions), drains audio, drains motion.
     * A missing head with an arrived successor means a worker is wedged or
     * failed on it: skip after [SKIP_TIMEOUT_MS] (or immediately once no
     * worker can still deliver it) so one slow frame costs a gap, never a
     * stall. Footer frame count == encoded - skipped.
     */
    private fun commitLoop() {
        var headWaitStart = -1L
        while (true) {
            commitReadyHeads()
            drainAudioQueue()
            drainMotion()
            if (workersAlive.get() == 0 && encodeQueue.isEmpty() &&
                audioQueue.isEmpty() && synchronized(reorderLock) { reorder.isEmpty() }
            ) {
                break
            }
            val successor = synchronized(reorderLock) {
                reorder.keys.any { it > nextCommitSeq }
            }
            if (successor) {
                if (workersAlive.get() == 0) {
                    skipHead()
                    headWaitStart = -1L
                    continue
                }
                val now = SystemClock.elapsedRealtime()
                if (headWaitStart < 0) headWaitStart = now
                else if (now - headWaitStart > SKIP_TIMEOUT_MS) {
                    Log.w(TAG, "commit head stalled, skipping seq=$nextCommitSeq")
                    val culprit = findInflightWorker(nextCommitSeq)
                    skipHead()
                    if (culprit >= 0) parkWorker(culprit)
                    headWaitStart = -1L
                    continue
                }
            } else {
                headWaitStart = -1L
            }
            var interrupted = false
            synchronized(reorderLock) {
                try {
                    reorderLock.wait(COMMIT_POLL_MS)
                } catch (_: InterruptedException) {
                    interrupted = true
                }
            }
            if (interrupted) break
        }
        // Final purge: late arrivals after skips are valid payloads with
        // nowhere to go — count them skipped, return their buffers.
        synchronized(reorderLock) {
            for ((_, pend) in reorder) {
                pend.payload.clear()
                payloadPool.offer(pend.payload)
                commitSkipped.incrementAndGet()
            }
            reorder.clear()
        }
    }

    /** Write every consecutive head from [nextCommitSeq]; purge late arrivals. */
    private fun commitReadyHeads() {
        while (true) {
            synchronized(reorderLock) {
                val it = reorder.entries.iterator()
                while (it.hasNext()) {
                    val e = it.next()
                    if (e.key < nextCommitSeq) {
                        e.value.payload.clear()
                        payloadPool.offer(e.value.payload)
                        it.remove()
                        commitSkipped.incrementAndGet()
                    }
                }
            }
            val pend = synchronized(reorderLock) { reorder[nextCommitSeq] } ?: break
            try {
                CinemaRawWriter.writeFrame(
                    writerHandle, pend.payload, pend.bytes, pend.timestampNs, pend.frameJson
                )
                updateCommitFps(pend.timestampNs)
            } catch (e: Exception) {
                Log.w(TAG, "frame commit failed: ${e.message}")
            } finally {
                pend.payload.clear()
                payloadPool.offer(pend.payload)
            }
            synchronized(reorderLock) { reorder.remove(nextCommitSeq) }
            nextCommitSeq++
        }
    }

    /** Advance past a missing head; its buffer (if any) returns to the pool. */
    private fun skipHead() {
        synchronized(reorderLock) {
            reorder.remove(nextCommitSeq)?.let {
                it.payload.clear()
                payloadPool.offer(it.payload)
            }
        }
        nextCommitSeq++
        commitSkipped.incrementAndGet()
    }

    /** Which worker (if any) is still grinding the given sequence. */
    private fun findInflightWorker(seq: Long): Int {
        for (i in 0 until workerCount) {
            if (inflightSeq.get(i) == seq) return i
        }
        return -1
    }

    /**
     * Park a pathologically slow worker (thermal little-core exile): it
     * finishes its current frame, then exits. The survivor runs solo and
     * ordered — a brief gap, then full speed, instead of endless skips.
     * If every worker gets parked, the take ends early with a valid file.
     */
    private fun parkWorker(index: Int) {
        if (workerParked.compareAndSet(index, 0, 1)) {
            Log.w(TAG, "parking slow encode worker $index")
        }
    }

    private fun updateCommitFps(ts: Long) {
        if (lastFrameTs > 0) {
            val dtMs = (ts - lastFrameTs) / 1e6f
            if (dtMs > 0) {
                intervalMsEstimate = if (intervalMsEstimate == 0f) dtMs
                else intervalMsEstimate * 0.9f + dtMs * 0.1f
                fpsEstimate = 1000f / intervalMsEstimate
            }
        }
        lastFrameTs = ts
    }

    private fun drainAudioQueue() {
        if (writerHandle == 0L) return
        var chunk = audioQueue.poll()
        while (chunk != null) {
            try {
                CinemaRawWriter.writeAudio(
                    writerHandle, chunk.data, chunk.frames, chunk.timestampNs
                )
                audioChunks.incrementAndGet()
                audioFrames.addAndGet(chunk.frames.toLong())
            } catch (e: Exception) {
                Log.w(TAG, "audio write failed: ${e.message}")
            }
            chunk = audioQueue.poll()
        }
    }

    /** Called on the committer thread: chunk buffered motion into the container. */
    private fun drainMotion() {
        val rec = motion ?: return
        if (writerHandle == 0L) return
        var chunk = rec.drainGyro()
        while (chunk != null) {
            try {
                CinemaRawWriter.writeGyro(
                    writerHandle, chunk.timestampsNs, chunk.axes, chunk.count
                )
                gyroSamples.addAndGet(chunk.count.toLong())
            } catch (e: Exception) {
                Log.w(TAG, "gyro write failed: ${e.message}")
                break
            }
            chunk = rec.drainGyro()
        }
        var accel = rec.drainAccel()
        while (accel != null) {
            try {
                CinemaRawWriter.writeAccel(
                    writerHandle, accel.timestampsNs, accel.axes, accel.count
                )
                accelSamples.addAndGet(accel.count.toLong())
            } catch (e: Exception) {
                Log.w(TAG, "accel write failed: ${e.message}")
                break
            }
            accel = rec.drainAccel()
        }
    }

    private fun buildMetadata(
        baseJson: String, realtime: Boolean, audioRate: Int,
        hasMotion: Boolean, orientation: Int
    ): String {
        val extra = "\"realtimeTimestamps\":$realtime," +
            "\"audioSampleRate\":$audioRate,\"hasMotion\":$hasMotion," +
            "\"sensorOrientation\":$orientation," +
            "\"gyroMapperVersion\":${GyroCameraFrameMapper.MAPPER_VERSION}"
        val trimmed = baseJson.trim()
        return if (trimmed.endsWith("}")) {
            trimmed.dropLast(1) + "," + extra + "}"
        } else {
            "{$extra}"
        }
    }

    private fun shutdownStreams() {
        try {
            audio?.stop()
        } catch (_: Exception) {
        }
        audio = null
        try {
            motion?.stop()
        } catch (_: Exception) {
        }
        motion = null
    }

    private fun openCamera(handler: Handler): CameraDevice {
        val latch = CountDownLatch(1)
        var opened: CameraDevice? = null
        var error: String? = null
        cameraManager.openCamera(cameraId, object : CameraDevice.StateCallback() {
            override fun onOpened(d: CameraDevice) {
                opened = d
                latch.countDown()
            }

            override fun onDisconnected(d: CameraDevice) {
                error = "disconnected"
                latch.countDown()
            }

            override fun onError(d: CameraDevice, code: Int) {
                error = "error=$code"
                latch.countDown()
            }
        }, handler)
        check(latch.await(10, TimeUnit.SECONDS)) { "Camera open timed out" }
        if (opened == null) throw IllegalStateException("Camera open failed: $error")
        return opened!!
    }

    private fun createSession(handler: Handler): CameraCaptureSession {
        val latch = CountDownLatch(1)
        var configured: CameraCaptureSession? = null
        var error: String? = null
        val config = if (Build.VERSION.SDK_INT >= 28) {
            SessionConfiguration(
                SessionConfiguration.SESSION_REGULAR,
                listOf(OutputConfiguration(reader!!.surface)),
                // Use the camera handler executor for session callbacks.
                java.util.concurrent.Executor { handler.post(it) },
                object : CameraCaptureSession.StateCallback() {
                    override fun onConfigured(s: CameraCaptureSession) {
                        configured = s
                        latch.countDown()
                    }

                    override fun onConfigureFailed(s: CameraCaptureSession) {
                        error = "configureFailed"
                        latch.countDown()
                    }
                }
            )
        } else {
            @Suppress("DEPRECATION")
            device!!.createCaptureSession(
                listOf(reader!!.surface),
                object : CameraCaptureSession.StateCallback() {
                    override fun onConfigured(s: CameraCaptureSession) {
                        configured = s
                        latch.countDown()
                    }

                    override fun onConfigureFailed(s: CameraCaptureSession) {
                        error = "configureFailed"
                        latch.countDown()
                    }
                },
                handler
            )
            null
        }
        if (config != null) device!!.createCaptureSession(config)
        check(latch.await(10, TimeUnit.SECONDS)) { "Session configure timed out" }
        if (configured == null) throw IllegalStateException("Session failed: $error")
        return configured!!
    }

    private fun teardown() {
        accepting = false
        try {
            session?.close()
        } catch (_: Exception) {
        }
        session = null
        try {
            device?.close()
        } catch (_: Exception) {
        }
        device = null
        try {
            reader?.close()
        } catch (_: Exception) {
        }
        reader = null
        encodeThreads = emptyArray()
        commitThread = null
        shutdownStreams()
        try {
            camThread?.quitSafely()
        } catch (_: Exception) {
        }
        camThread = null
        camHandler = null
        // If start failed before stop(), never leave a truncated file handle open.
        val handle = writerHandle
        writerHandle = 0L
        if (handle != 0L) {
            try {
                CinemaRawWriter.close(handle)
            } catch (_: Exception) {
            }
        }
    }

    companion object {
        private const val TAG = "RawVideoRecorder"
        const val FPS = 30
        // Outstanding Images must stay <= QUEUE_CAP + workers in hand with
        // margin: the acquire loop drops (never wedges) when encode
        // backpressures. GPU-borrowed frames pin one extra buffer each.
        private const val READER_MAX_IMAGES = 12
        private const val QUEUE_CAP = 8
        private const val AUDIO_QUEUE_CAP = 32
        private const val MAX_WORKERS = 4
        private const val SKIP_TIMEOUT_MS = 250L
        private const val COMMIT_POLL_MS = 10L
        private const val POOL_WAIT_MS = 100L

        /** Poison pill: ArrayBlockingQueue bans nulls, so a token object marks drain. */
        private object Poison
    }
}
