// SPDX-License-Identifier: GPL-3.0-or-later
package com.matthew.rawlens

import android.content.Context
import android.hardware.camera2.CameraCaptureSession
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraDevice
import android.hardware.camera2.CameraManager
import android.hardware.camera2.CameraMetadata
import android.hardware.camera2.CaptureResult
import android.hardware.camera2.TotalCaptureResult
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
 * - N encode workers ([encodeWorkers], default 3, up to 4) encode RAW16
 *   planes concurrently into pooled payload buffers (~10ms effective at 3x).
 * - One committer drains a sequence-numbered reorder buffer strictly in
 *   capture order (the container rejects timestamp regressions), plus audio
 *   and motion. A wedged head is skipped after [SKIP_TIMEOUT_MS] so one
 *   slow frame costs a gap, never a stall; a dropped head is skipped
 *   immediately once no stage can deliver it. Footer == encoded - latePurged.
 * - HAL RAW16 plane -> encoder -> container, no per-frame files (this is
 *   the PhotonCamera gap: no thread-per-DNG, no MediaStore churn).
 * - [crop] switches Open Gate / 16:9 / 2.39:1 / 2.00:1 via the encoder's
 *   crop args with no session reconfiguration. Safe to change
 *   mid-recording (fps locks at record start).
 * - P4 streams: PCM16 stereo-first audio ([AudioPcmRecorder]) and continuous
 *   gyro/accel ([MotionRecorder]) share the video frame timeline. Capture
 *   runs on boot-time ns, but every on-disk timestamp is
 *   recording-relative (`ts - timeOriginNs`, the first queued frame's
 *   sensor ts): readers do 32-bit ms math that absolute timestamps
 *   overflow, breaking audio sync (PhotonCamera's fix). Audio/motion
 *   pre-roll without video is dropped so all timelines start together.
 *   All container writes happen on the committer — the native writer has
 *   no locking. Missing streams are legal: denial/missing hardware
 *   records silent/still video instead of failing.
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
        /** Capture gaps (dropped/wedged heads skipped over): committed in-order around them. */
        val commitSkipped: Int,
        /** Encoded but never committed (arrived after its skip): footer == encoded - latePurged. */
        val latePurged: Int,
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
        val resultWaitMsAvg: Float,
        val resultWaitMsMax: Float,
        val closeMsAvg: Float,
        val closeMsMax: Float,
        val writeMsAvg: Float,
        val writeMsMax: Float,
        val framesAcquired: Int,
        val framesEncoded: Int,
        val framesDropped: Int,
        val commitSkipped: Int,
        val latePurged: Int,
        val queueDepth: Int,
        val reorderDepth: Int,
        val fileBytes: Long,
        val audioChunks: Int,
        val audioFrames: Long,
        val gyroSamples: Long,
        val accelSamples: Long,
        val cropLabel: String,
    )

    /** Encode worker count, set before [start]. 3 sustains 30fps Open Gate
     * with one worker to spare: a wedged/exiled worker is parked, and the
     * two survivors still cover 30fps (~66ms each). 2 has no redundancy —
     * one parked worker leaves a solo ~22fps feed. 4 targets 60fps gates on
     * stronger SoCs (more heat). Idle workers block in take(), so
     * steady-state CPU/heat is unchanged; only burst parallelism grows. */
    var encodeWorkers: Int = 3

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
    private val resultLock = java.lang.Object()
    private val captureResults = java.util.TreeMap<Long, TotalCaptureResult>()
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
    /**
     * Container timestamp origin: the first queued frame's sensor ts.
     * Written once on the camera thread, read on workers (frame metadata)
     * and the committer (frame/audio/motion rebasing). Every on-disk
     * timestamp is recording-relative (`ts - origin`) because readers do
     * 32-bit ms math that absolute boot-time timestamps overflow, breaking
     * audio sync entirely (PhotonCamera's fix, our MotionCam Tools
     * symptom). In-memory tracking (reorder, fps) stays absolute.
     */
    @Volatile private var timeOriginNs = Long.MIN_VALUE
    private val acquired = AtomicInteger(0)
    private val encoded = AtomicInteger(0)
    private val dropped = AtomicInteger(0)
    private val commitSkipped = AtomicInteger(0)
    private val latePurged = AtomicInteger(0)
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
    @Volatile private var resultWaitMsAvg = 0f
    @Volatile private var resultWaitMsMax = 0f
    @Volatile private var closeMsAvg = 0f
    @Volatile private var closeMsMax = 0f
    @Volatile private var writeMsAvg = 0f
    @Volatile private var writeMsMax = 0f
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
        latePurged.set(0)
        audioChunks.set(0)
        audioFrames.set(0)
        audioDropped.set(0)
        gyroSamples.set(0)
        accelSamples.set(0)
        fpsEstimate = 0f
        encodeMsAvg = 0f
        encodeMsMax = 0f
        resultWaitMsAvg = 0f
        resultWaitMsMax = 0f
        closeMsAvg = 0f
        closeMsMax = 0f
        writeMsAvg = 0f
        writeMsMax = 0f
        lastFrameTs = -1L
        hasAudioStream = false
        hasMotionStream = false
        nextSeq = 0L
        nextCommitSeq = 0L
        timeOriginNs = Long.MIN_VALUE
        synchronized(reorderLock) { reorder.clear() }
        synchronized(resultLock) { captureResults.clear() }
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
        var audioChannels = 0
        if (audioEnabled) {
            val rec = AudioPcmRecorder()
            if (rec.start { chunk -> onAudioChunk(chunk) }) {
                audio = rec
                hasAudioStream = true
                audioRate = AudioPcmRecorder.SAMPLE_RATE
                audioChannels = rec.channels
            }
        }
        writerHandle = try {
            val meta = CinemaRawMetadata.container(
                containerMetadataJson, chars, realtime, audioRate,
                audioChannels, hasMotionStream, orientation
            )
            CinemaRawWriter.open(output.absolutePath, meta)
        } catch (e: Exception) {
            shutdownStreams()
            throw e
        }

        // Urgent-display like the encode workers: this thread services 30fps
        // acquisition, the VF fan-out and all result callbacks — preempting
        // it (default priority) drops frames the encoders could have kept.
        val thread = HandlerThread(
            "RawVideoCam", android.os.Process.THREAD_PRIORITY_URGENT_DISPLAY
        ).apply { start() }
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
                // Container timestamp origin: first queued frame (volatile
                // write; the queue hands happens-before to the workers).
                // A later-skipped head only shifts the first committed
                // frame to a small positive offset — still relative.
                if (timeOriginNs == Long.MIN_VALUE) timeOriginNs = ts
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
            session!!.setRepeatingRequest(req, object : CameraCaptureSession.CaptureCallback() {
                override fun onCaptureCompleted(session: CameraCaptureSession,
                    request: CaptureRequest, result: TotalCaptureResult) {
                    val ts = result.get(CaptureResult.SENSOR_TIMESTAMP) ?: return
                    synchronized(resultLock) {
                        captureResults[ts] = result
                        while (captureResults.size > 64) captureResults.pollFirstEntry()
                        resultLock.notifyAll()
                    }
                }
            }, handler)
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
        try {
            if (handle != 0L) CinemaRawWriter.close(handle)
        } finally {
            teardown()
        }
        // Propagate finalization failures so callers retain, rather than publish,
        // a potentially incomplete local recording.
        // containerFrames = committed in-order frames (footer == encoded - latePurged).
        val encodedCount = encoded.get()
        val lateCount = latePurged.get()
        return Stats(
            framesAcquired = acquired.get(),
            framesEncoded = encodedCount,
            framesDropped = dropped.get(),
            commitSkipped = commitSkipped.get(),
            latePurged = lateCount,
            audioChunks = audioChunks.get(),
            audioFrames = audioFrames.get(),
            audioDropped = audioDropped.get(),
            gyroSamples = gyroSamples.get(),
            accelSamples = accelSamples.get(),
            hasAudio = hasAudioStream,
            hasMotion = motionStats != null &&
                (motionStats.gyroSamples > 0 || motionStats.accelSamples > 0),
            containerFrames = (encodedCount - lateCount).toLong(),
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
        resultWaitMsAvg = resultWaitMsAvg,
        resultWaitMsMax = resultWaitMsMax,
        closeMsAvg = closeMsAvg,
        closeMsMax = closeMsMax,
        writeMsAvg = writeMsAvg,
        writeMsMax = writeMsMax,
        framesAcquired = acquired.get(),
        framesEncoded = encoded.get(),
        framesDropped = dropped.get(),
        commitSkipped = commitSkipped.get(),
        latePurged = latePurged.get(),
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
                // Image and result callbacks can arrive in either order. Only write
                // calibration from this exact exposure, never a stale AWB result.
                val wait0 = SystemClock.elapsedRealtimeNanos()
                val result = synchronized(resultLock) {
                    val deadline = SystemClock.elapsedRealtime() + 100
                    while (!captureResults.containsKey(task.timestampNs)) {
                        val remaining = deadline - SystemClock.elapsedRealtime()
                        if (remaining <= 0) break
                        resultLock.wait(remaining)
                    }
                    captureResults.remove(task.timestampNs)
                } ?: error("Missing capture result for ${task.timestampNs}")
                val waitMs = (SystemClock.elapsedRealtimeNanos() - wait0) / 1e6f
                resultWaitMsAvg =
                    if (resultWaitMsAvg == 0f) waitMs else resultWaitMsAvg * 0.95f + waitMs * 0.05f
                resultWaitMsMax =
                    if (waitMs > resultWaitMsMax) waitMs else resultWaitMsMax * 0.999f + waitMs * 0.001f
                val frameJson =
                    CinemaRawMetadata.frame(task.frameJson, result, timeOriginNs)
                buf.flip()
                synchronized(reorderLock) {
                    reorder[task.seq] =
                        PendingFrame(task.seq, buf, bytes, task.timestampNs, frameJson)
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
                val close0 = SystemClock.elapsedRealtimeNanos()
                RawImageOwnership.release(task.image)
                val closeMs = (SystemClock.elapsedRealtimeNanos() - close0) / 1e6f
                closeMsAvg =
                    if (closeMsAvg == 0f) closeMs else closeMsAvg * 0.95f + closeMs * 0.05f
                closeMsMax =
                    if (closeMs > closeMsMax) closeMs else closeMsMax * 0.999f + closeMs * 0.001f
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
     * A missing head with an arrived successor is skipped immediately when
     * no stage can still deliver it ([isHeadMissing]: dropped at the queue
     * or pool, or a failed encode), so one dropped frame costs a gap, never
     * a stall. A head still queued or in flight may yet arrive: skip after
     * [SKIP_TIMEOUT_MS] so one slow frame costs a gap, never a spiral.
     * Footer frame count == encoded - latePurged.
     */
    private fun commitLoop() {
        // Urgent-display like the encode workers: the single committer must
        // sustain the full frame rate (~30ms writes vs a 33ms budget), so it
        // cannot wait behind default-priority scheduling under full load.
        android.os.Process.setThreadPriority(android.os.Process.THREAD_PRIORITY_URGENT_DISPLAY)
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
                if (isHeadMissing(nextCommitSeq)) {
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
        // nowhere to go — count them late-purged, return their buffers.
        synchronized(reorderLock) {
            for ((_, pend) in reorder) {
                pend.payload.clear()
                payloadPool.offer(pend.payload)
                latePurged.incrementAndGet()
            }
            reorder.clear()
        }
    }

    /**
     * Write every consecutive head from [nextCommitSeq]; purge late
     * arrivals. Committed frame timestamps are recording-relative
     * (`ts - timeOriginNs`); the reorder buffer and fps tracking stay
     * absolute. Rebasing preserves order, so the writer's strict
     * increase still holds exactly when the capture order did.
     */
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
                        latePurged.incrementAndGet()
                    }
                }
            }
            val pend = synchronized(reorderLock) { reorder[nextCommitSeq] } ?: break
            // Origin is always set here: a pending frame implies an
            // acquired frame, which set it. Relative ts is >= 0 by
            // capture order (a skipped head only shifts the start up).
            val relTs = pend.timestampNs - timeOriginNs
            try {
                val write0 = SystemClock.elapsedRealtimeNanos()
                CinemaRawWriter.writeFrame(
                    writerHandle, pend.payload, pend.bytes, relTs, pend.frameJson
                )
                val writeMs = (SystemClock.elapsedRealtimeNanos() - write0) / 1e6f
                writeMsAvg =
                    if (writeMsAvg == 0f) writeMs else writeMsAvg * 0.95f + writeMs * 0.05f
                writeMsMax =
                    if (writeMs > writeMsMax) writeMs else writeMsMax * 0.999f + writeMs * 0.001f
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

    /**
     * Advance past a missing head; its buffer (if any) returns to the pool.
     * A head that arrived while skipping counts late-purged (it was
     * encoded); a genuinely absent head counts skipped (a capture gap).
     */
    private fun skipHead() {
        var late = false
        synchronized(reorderLock) {
            reorder.remove(nextCommitSeq)?.let {
                it.payload.clear()
                payloadPool.offer(it.payload)
                late = true
            }
        }
        nextCommitSeq++
        if (late) latePurged.incrementAndGet()
        else commitSkipped.incrementAndGet()
    }

    /** Which worker (if any) is still grinding the given sequence. */
    private fun findInflightWorker(seq: Long): Int {
        for (i in 0 until workerCount) {
            if (inflightSeq.get(i) == seq) return i
        }
        return -1
    }

    /**
     * True when no pipeline stage can still deliver [seq]: no worker holds
     * it, it sits in neither the reorder buffer nor the encode queue. Only
     * call with an arrived successor: sequence numbers are assigned in
     * capture order and the queue is FIFO, so a successor in hand proves
     * [seq] left every transit window long ago — it was dropped (queue or
     * pool pressure) or its encode failed, never merely delayed.
     */
    private fun isHeadMissing(seq: Long): Boolean {
        if (findInflightWorker(seq) >= 0) return false
        synchronized(reorderLock) {
            if (reorder.containsKey(seq)) return false
        }
        // Weakly-consistent scan: a missed racing offer only delays the
        // skip to the timeout backstop, never corrupts order.
        for (item in encodeQueue) {
            if (item is EncodeTask && item.seq == seq) return false
        }
        return true
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
        val origin = timeOriginNs
        var chunk = audioQueue.poll()
        while (chunk != null) {
            // Recording-relative like frames; pre-roll without video is
            // dropped so both timelines start together (PhotonCamera rule).
            val rel = if (origin == Long.MIN_VALUE) -1 else chunk.timestampNs - origin
            if (rel < 0) {
                audioDropped.incrementAndGet()
            } else try {
                CinemaRawWriter.writeAudio(
                    writerHandle, chunk.data, chunk.frames, chunk.channels, rel
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
        val origin = timeOriginNs
        var chunk = rec.drainGyro()
        while (chunk != null) {
            try {
                rebaseMotion(chunk, origin)?.let {
                    CinemaRawWriter.writeGyro(
                        writerHandle, it.timestampsNs, it.axes, it.count
                    )
                    gyroSamples.addAndGet(it.count.toLong())
                }
            } catch (e: Exception) {
                Log.w(TAG, "gyro write failed: ${e.message}")
                break
            }
            chunk = rec.drainGyro()
        }
        var accel = rec.drainAccel()
        while (accel != null) {
            try {
                rebaseMotion(accel, origin)?.let {
                    CinemaRawWriter.writeAccel(
                        writerHandle, it.timestampsNs, it.axes, it.count
                    )
                    accelSamples.addAndGet(it.count.toLong())
                }
            } catch (e: Exception) {
                Log.w(TAG, "accel write failed: ${e.message}")
                break
            }
            accel = rec.drainAccel()
        }
    }

    /**
     * Recording-relative copy of a motion chunk, or null when the whole
     * chunk predates video. Samples are ascending and the origin is fixed,
     * so pre-roll is always a leading run — skip it, keep the rest.
     */
    private fun rebaseMotion(chunk: MotionRecorder.Chunk, origin: Long): MotionRecorder.Chunk? {
        if (origin == Long.MIN_VALUE) return null
        var start = 0
        while (start < chunk.count && chunk.timestampsNs[start] < origin) start++
        if (start >= chunk.count) return null
        val n = chunk.count - start
        val ts = LongArray(n) { i -> chunk.timestampsNs[start + i] - origin }
        val ax = chunk.axes.copyOfRange(start * 3, chunk.count * 3)
        return MotionRecorder.Chunk(ts, ax, n)
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
