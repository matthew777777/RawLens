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
 * Runs the RawNIND single-frame denoisers natively (NCNN, Vulkan backend).
 *
 * Two coexisting model variants share the same native entry points; each
 * native handle auto-detects its variant from its own .param:
 *
 * <ul>
 *   <li>TINY ({@code models/rawnind_tiny.ncnn.param}): PyTorch model trained
 *   on Mac via python/rawnind-train.
 *   in   (W2,H2,5) packed [R,G1,G2,B] + sigma plane, channel-last floats,
 *        normalized [0,1]-ish domain (post black/white + lens shading)
 *   out  (W2,H2,4) denoised packed Bayer, channel-last floats
 *   </li>
 *   <li>BAYER ({@code models/rawnind_bayer.ncnn.param}): upstream RawNIND
 *   UtNet2 weights, 4ch packed Bayer in, arbitrary gain (no sigma plane).
 *   in   (W2,H2,4) packed [R,G1,G2,B], channel-last floats, same domain
 *   out  (W,H,3) denoised camRGB at 2x packed resolution (full Bayer size),
 *        channel-last floats — demosaiced output that bypasses AMaZE.
 *   </li>
 * </ul>
 *
 * where W2 = bayerWidth/2, H2 = bayerHeight/2 (packed quad resolution).
 * Geometry is dynamic (fully convolutional nets, batch 1); tiling is handled
 * natively with fixed-size tiles so GPU memory stays flat at any MP count.
 *
 * The model files live in app/src/main/assets/models/; when absent the
 * corresponding path reports unavailable and the Kotlin side uses the plain
 * path. Same contract on emulator ABIs (native stub returns 0).
 *
 * Process-wide singleton loading on background threads, mirroring
 * FlowNetNcnnProcessor. Call {@link #start(Context)} from Application
 * onCreate so Vulkan shader compilation never blocks the shutter path.
 */
public final class RawNindNcnnProcessor {
    private static final String TAG = "RawNindNcnnProcessor";
    private static final String MODEL_PARAM = "models/rawnind_tiny.ncnn.param";
    private static final String MODEL_PARAM_BAYER = "models/rawnind_bayer.ncnn.param";
    private static final long INIT_TIMEOUT_MS = 30000;

    private static volatile RawNindNcnnProcessor sInstance;
    private static final Object sLock = new Object();

    private final Context appContext;
    private final CountDownLatch initLatch = new CountDownLatch(1);
    private volatile long nativeHandle;
    private volatile boolean ready = false;
    // Bayer (4ch -> 3ch x2) path: independent handle/state so the two models
    // load, warm up, infer and close without serializing on each other.
    private final CountDownLatch bayerLatch = new CountDownLatch(1);
    private volatile long nativeHandleBayer;
    private volatile boolean readyBayer = false;
    private final Object tinyLock = new Object();
    private final Object bayerLock = new Object();

    interface NativeApi {
        long create(AssetManager assets, String path);
        boolean run(long handle, FloatBuffer input, int width, int height, FloatBuffer output);
        void destroy(long handle);
    }

    private static final class JniApi implements NativeApi {
        static { System.loadLibrary("ncnnMl"); }
        public long create(AssetManager assets, String path) { return nativeCreate(assets, path); }
        public boolean run(long h, FloatBuffer in, int w, int hgt, FloatBuffer out) {
            return nativeRun(h, in, w, hgt, out);
        }
        public void destroy(long handle) { nativeDestroy(handle); }
    }

    private final NativeApi nativeApi;
    private volatile boolean closed;


    /**
     * Ensures the singleton is loading the model in the background (idempotent).
     */
    public static RawNindNcnnProcessor start(Context context) {
        RawNindNcnnProcessor inst = sInstance;
        if (inst == null) {
            synchronized (sLock) {
                inst = sInstance;
                if (inst == null) {
                    inst = new RawNindNcnnProcessor(context, new JniApi());
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

    // Native boundary injection lets lifecycle and missing-model regressions
    // run on the host without loading the Android-only shared library.
    RawNindNcnnProcessor(Context context, NativeApi api) {
        appContext = context.getApplicationContext();
        nativeApi = api;
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

    /** Non-blocking: true only after the tiny background load succeeded. */
    public boolean isReady() {
        return ready;
    }

    /** Non-blocking: true only after the bayer background load succeeded. */
    public boolean isBayerReady() {
        return readyBayer;
    }

    /** Blocks until the bayer background load finished (or timed out). */
    public boolean waitBayerReady(long timeoutMs) {
        try {
            return bayerLatch.await(timeoutMs, TimeUnit.MILLISECONDS) && readyBayer;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return readyBayer;
        }
    }

    public boolean isLoading() { return !closed && initLatch.getCount() != 0; }
    public boolean isBayerLoading() { return !closed && bayerLatch.getCount() != 0; }

    private void backgroundInit() {
        // A missing optional tiny model must not skip the shipped Bayer model
        // or leave waitBayerReady waiting on a latch that can never complete.
        try {
            initializeTiny();
        } finally {
            initializeBayer();
        }
    }

    private void initializeTiny() {
        try {
            synchronized (tinyLock) {
                if (closed) return;
                nativeHandle = loadAndWarm(MODEL_PARAM, 5, 4, 1);
                ready = nativeHandle != 0;
            }
        } finally {
            initLatch.countDown();
        }
    }

    private void initializeBayer() {
        try {
            synchronized (bayerLock) {
                if (closed) return;
                nativeHandleBayer = loadAndWarm(MODEL_PARAM_BAYER, 4, 3, 2);
                readyBayer = nativeHandleBayer != 0;
            }
        } finally {
            bayerLatch.countDown();
        }
    }

    /** A model is ready only after its real input/output contract ran once. */
    private long loadAndWarm(String path, int inputs, int outputs, int scale) {
        long handle = 0;
        long started = System.currentTimeMillis();
        try {
            handle = nativeApi.create(appContext.getAssets(), path);
            if (handle == 0) {
                Log.w(TAG, "RawNIND model unavailable: " + path);
                return 0;
            }
            FloatBuffer warmIn = zeroPacked(64, 64, inputs);
            FloatBuffer warmOut = ByteBuffer.allocateDirect(64 * scale * 64 * scale * outputs * 4)
                    .order(ByteOrder.nativeOrder()).asFloatBuffer();
            if (!nativeApi.run(handle, warmIn, 64, 64, warmOut)) {
                Log.e(TAG, "RawNIND warmup failed: " + path);
                nativeApi.destroy(handle);
                return 0;
            }
            Log.i(TAG, "RawNIND ready: " + path + " in "
                    + (System.currentTimeMillis() - started) + "ms");
            return handle;
        } catch (Throwable failure) {
            if (handle != 0) {
                try { nativeApi.destroy(handle); } catch (Throwable ignored) { }
            }
            Log.e(TAG, "RawNIND initialization failed: " + path, failure);
            return 0;
        }
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
        if (closed || !waitReady(INIT_TIMEOUT_MS)) return null;
        return runInferenceLocked(packed, w2, h2);
    }

    private ByteBuffer runInferenceLocked(FloatBuffer packed, int w2, int h2) {
        if (nativeHandle == 0 || packed == null || w2 <= 0 || h2 <= 0) return null;
        // Native reads w2*h2*5 floats via GetDirectBufferAddress (position and
        // limit are ignored across JNI), so reject short/heap buffers here
        // instead of over-reading native memory.
        long need = (long) w2 * h2 * 5;
        if (!packed.isDirect() || packed.capacity() < need) {
            Log.e(TAG, "RawNindNcnn: packed buffer too small: cap=" + packed.capacity()
                    + " need=" + need);
            return null;
        }
        long start = System.nanoTime();
        ByteBuffer outBuf = ByteBuffer.allocateDirect(w2 * h2 * 4 * 4)
                .order(ByteOrder.nativeOrder());
        packed.rewind();
        // Native reuses one shared tile scratch per handle across
        // extractors, so concurrent runs on the SAME model would corrupt each
        // other; serialize per model (tiny vs bayer run concurrently).
        boolean ok;
        synchronized (tinyLock) {
            if (nativeHandle == 0) return null;
            try {
                ok = nativeApi.run(nativeHandle, packed, w2, h2, outBuf.asFloatBuffer());
            } catch (Throwable t) {
                Log.e(TAG, "RawNindNcnn: inference failed", t);
                return null;
            }
        }
        if (!ok) {
            Log.e(TAG, "RawNindNcnn: inference returned an error");
            return null;
        }
        Log.d(TAG, "inference " + w2 + "x" + h2 + " packed in "
                + (System.nanoTime() - start) / 1_000_000 + "ms");
        return outBuf;
    }

    /**
     * Denoise+demosaic one packed Bayer frame with the bayer (UtNet2) model.
     *
     * @param packed  direct FloatBuffer, channel-last [R,G1,G2,B] per quad,
     *                length == w2*h2*4 (no sigma plane — arbitrary gain)
     * @param w2      packed width (bayerWidth/2)
     * @param h2      packed height (bayerHeight/2)
     * @return direct ByteBuffer of channel-last camRGB floats,
     *         length == (2*w2)*(2*h2)*3*4 bytes (full Bayer resolution),
     *         or null on error / if the bayer model is not ready
     */
    public ByteBuffer runInferenceBayer(FloatBuffer packed, int w2, int h2) {
        if (closed || !waitBayerReady(INIT_TIMEOUT_MS)) return null;
        if (nativeHandleBayer == 0 || packed == null || w2 <= 0 || h2 <= 0) return null;
        long need = (long) w2 * h2 * 4;
        if (!packed.isDirect() || packed.capacity() < need) {
            Log.e(TAG, "RawNindNcnn(bayer): packed buffer too small: cap=" + packed.capacity()
                    + " need=" + need);
            return null;
        }
        long start = System.nanoTime();
        int ow = w2 * 2, oh = h2 * 2;
        ByteBuffer outBuf = ByteBuffer.allocateDirect(ow * oh * 3 * 4)
                .order(ByteOrder.nativeOrder());
        packed.rewind();
        boolean ok;
        synchronized (bayerLock) {
            if (nativeHandleBayer == 0) return null;
            try {
                ok = nativeApi.run(nativeHandleBayer, packed, w2, h2, outBuf.asFloatBuffer());
            } catch (Throwable t) {
                Log.e(TAG, "RawNindNcnn(bayer): inference failed", t);
                return null;
            }
        }
        if (!ok) {
            Log.e(TAG, "RawNindNcnn(bayer): inference returned an error");
            return null;
        }
        Log.d(TAG, "bayer inference " + w2 + "x" + h2 + " packed -> "
                + ow + "x" + oh + " rgb in "
                + (System.nanoTime() - start) / 1_000_000 + "ms");
        return outBuf;
    }

    /** Close the native ncnn nets. Safe to call multiple times. */
    public void close() {
        closed = true;
        synchronized (sLock) {
            if (sInstance == this) sInstance = null;
        }
        synchronized (tinyLock) {
            long h = nativeHandle;
            if (h != 0) {
                nativeHandle = 0;
                ready = false;
                nativeApi.destroy(h);
            }
        }
        synchronized (bayerLock) {
            long h = nativeHandleBayer;
            if (h != 0) {
                nativeHandleBayer = 0;
                readyBayer = false;
                nativeApi.destroy(h);
            }
        }
    }

    private static FloatBuffer zeroPacked(int w2, int h2, int channels) {
        FloatBuffer fb = ByteBuffer.allocateDirect(w2 * h2 * channels * 4)
                .order(ByteOrder.nativeOrder()).asFloatBuffer();
        for (int i = 0; i < w2 * h2 * channels; i++) fb.put(0f);
        fb.rewind();
        return fb;
    }

    private static native long nativeCreate(AssetManager assetManager, String paramPath);
    private static native boolean nativeRun(long handle, FloatBuffer packed,
                                            int w2, int h2, FloatBuffer out);
    private static native void nativeDestroy(long handle);
}
