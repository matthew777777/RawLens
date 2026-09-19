package com.particlesdevs.photoncamera.processing.ml;

import android.content.Context;
import android.content.res.AssetManager;

import android.util.Log;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.FloatBuffer;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

/**
 * Runs the RawNIND-tiny single-frame Bayer denoiser natively (PyTorch model
 * trained on Mac via python/rawnind-train, exported to ONNX then converted
 * with pnnx to ncnn; Vulkan backend for GPU inference).
 *
 *   in   (W2,H2,5) packed [R,G1,G2,B] + sigma plane, channel-last floats,
 *        normalized [0,1]-ish domain (post black/white + lens shading)
 *   out  (W2,H2,4) denoised packed Bayer, channel-last floats
 *
 * where W2 = bayerWidth/2, H2 = bayerHeight/2 (packed quad resolution).
 * Geometry is dynamic (fully convolutional net, batch 1); tiling is handled
 * natively with fixed-size tiles so GPU memory stays flat at any MP count.
 *
 * The model files (models/rawnind_tiny.ncnn.param/.bin) are NOT shipped in
 * the repo until trained; when absent the processor reports unavailable and
 * the Kotlin side falls back to wavelet/bypass. Same contract on emulator
 * ABIs (native stub returns 0).
 *
 * Process-wide singleton loading on a background thread, mirroring
 * FlowNetNcnnProcessor. Call {@link #start(Context)} from Application
 * onCreate so Vulkan shader compilation never blocks the shutter path.
 */
public final class RawNindNcnnProcessor {
    private static final String TAG = "RawNindNcnnProcessor";
    private static final String MODEL_PARAM = "models/rawnind_tiny.ncnn.param";
    private static final long INIT_TIMEOUT_MS = 30000;

    private static volatile RawNindNcnnProcessor sInstance;
    private static final Object sLock = new Object();

    private final Context appContext;
    private final CountDownLatch initLatch = new CountDownLatch(1);
    private volatile long nativeHandle;
    private volatile boolean ready = false;

    static { System.loadLibrary("ncnnMl"); }

    /**
     * Ensures the singleton is loading the model in the background (idempotent).
     */
    public static RawNindNcnnProcessor start(Context context) {
        RawNindNcnnProcessor inst = sInstance;
        if (inst == null) {
            synchronized (sLock) {
                inst = sInstance;
                if (inst == null) {
                    inst = new RawNindNcnnProcessor(context);
                    sInstance = inst;
                }
            }
        }
        return inst;
    }

    /** The process-wide processor, or null if {@link #start} was never called. */
    public static RawNindNcnnProcessor getInstance() {
        return sInstance;
    }

    private RawNindNcnnProcessor(Context context) {
        appContext = context.getApplicationContext();
        Thread t = new Thread(this::backgroundInit, "rawnind-ncnn-init");
        t.start();
    }

    /** Blocks until the background load finished (or timed out). */
    public boolean waitReady(long timeoutMs) {
        try {
            return initLatch.await(timeoutMs, TimeUnit.MILLISECONDS) && ready;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return ready;
        }
    }

    /** Non-blocking: true only after a successful background load. */
    public boolean isReady() {
        return ready;
    }

    private void backgroundInit() {
        long t0 = System.currentTimeMillis();
        AssetManager am = appContext.getAssets();
        try {
            nativeHandle = nativeCreate(am, MODEL_PARAM);
        } catch (Throwable t) {
            Log.e(TAG, "RawNindNcnn: failed to initialize", t);
            nativeHandle = 0;
        }
        if (nativeHandle == 0) {
            Log.w(TAG, "RawNindNcnn: model unavailable: " + MODEL_PARAM
                    + " (train via python/rawnind-train; see NcnnML logcat)");
            ready = false;
            initLatch.countDown();
            return;
        }

        // Best-effort tiny warmup: builds Vulkan pipelines now so the first
        // real capture never pays shader-compile latency on the save thread.
        // A warmup failure must not fail init (real inference builds lazily).
        try {
            FloatBuffer warmIn = zeroPacked(64, 64);
            ByteBuffer warmOut = ByteBuffer.allocateDirect(64 * 64 * 4 * 4)
                    .order(ByteOrder.nativeOrder());
            if (!nativeRun(nativeHandle, warmIn, 64, 64, warmOut.asFloatBuffer())) {
                Log.w(TAG, "RawNindNcnn: warmup forward failed (non-fatal)");
            }
        } catch (Throwable t) {
            Log.w(TAG, "RawNindNcnn: warmup threw (non-fatal)", t);
        }
        ready = true;
        Log.d(TAG, "rawnind ncnn init+warmup in "
                + (System.currentTimeMillis() - t0) + "ms");
        initLatch.countDown();
    }

    /**
     * Denoise one packed Bayer frame.
     *
     * @param packed  direct FloatBuffer, channel-last [R,G1,G2,B,S] per quad,
     *                length == w2*h2*5
     * @param w2      packed width (bayerWidth/2)
     * @param h2      packed height (bayerHeight/2)
     * @return direct ByteBuffer of channel-last [R,G1,G2,B] floats,
     *         length == w2*h2*4*4 bytes, or null on error / if not ready
     */
    public ByteBuffer runInference(FloatBuffer packed, int w2, int h2) {
        if (!waitReady(INIT_TIMEOUT_MS)) return null;
        return runInferenceLocked(packed, w2, h2);
    }

    private ByteBuffer runInferenceLocked(FloatBuffer packed, int w2, int h2) {
        if (nativeHandle == 0 || packed == null || w2 <= 0 || h2 <= 0) return null;
        long start = System.nanoTime();
        ByteBuffer outBuf = ByteBuffer.allocateDirect(w2 * h2 * 4 * 4)
                .order(ByteOrder.nativeOrder());
        packed.rewind();
        boolean ok;
        try {
            ok = nativeRun(nativeHandle, packed, w2, h2, outBuf.asFloatBuffer());
        } catch (Throwable t) {
            Log.e(TAG, "RawNindNcnn: inference failed", t);
            return null;
        }
        if (!ok) {
            Log.e(TAG, "RawNindNcnn: inference returned an error");
            return null;
        }
        Log.d(TAG, "inference " + w2 + "x" + h2 + " packed in "
                + (System.nanoTime() - start) / 1_000_000 + "ms");
        return outBuf;
    }

    /** Close the native ncnn net. Safe to call multiple times. */
    public void close() {
        synchronized (sLock) {
            if (sInstance == this) sInstance = null;
        }
        long h = nativeHandle;
        if (h != 0) {
            nativeHandle = 0;
            ready = false;
            nativeDestroy(h);
        }
    }

    private static FloatBuffer zeroPacked(int w2, int h2) {
        FloatBuffer fb = ByteBuffer.allocateDirect(w2 * h2 * 5 * 4)
                .order(ByteOrder.nativeOrder()).asFloatBuffer();
        for (int i = 0; i < w2 * h2 * 5; i++) fb.put(0f);
        fb.rewind();
        return fb;
    }

    private static native long nativeCreate(AssetManager assetManager, String paramPath);
    private static native boolean nativeRun(long handle, FloatBuffer packed,
                                            int w2, int h2, FloatBuffer out);
    private static native void nativeDestroy(long handle);
}
