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
 * Runs the PhotonCamera KernelNet anisotropic kernel PARAMETER model natively
 * (ncnn, CPU backend by default; see KN_GPU=1 in ncnnMl.cpp).
 *
 * Model: {@code models/kernelnet_aniso_v2_2_params.ncnn.{param,bin}}, vendored
 * from PhotonCamera (see {@code app/src/main/cpp/flownet/UPSTREAM.md} and
 * NOTICE.md). Contract mirrors the upstream
 * {@code KernelNetNcnnProcessor} (in0 = quad-res quad-mean sqrt luma [0,1],
 * in1 = scalar sigma tiled to the luma grid, quarter-res s1/s2/rho output)
 * with one deliberate divergence:
 * this build's native side emits channel-major float32 {@code [s1][s2][rho]}
 * planes ({@code outW*outH*3} floats) instead of upstream's RGBA-interleaved
 * fp16 halves, so the Kotlin side can convert straight to the SR precision
 * field without a half-decode pass.
 *
 * Process-wide singleton loading on a background thread, mirroring
 * FlowNetNcnnProcessor. Emulator ABIs have no prebuilt ncnn archive (see
 * flownet_stub.cpp): the processor then reports unavailable and callers fall
 * back to the analytic kernel path.
 */
public final class KernelNetNcnnProcessor {
    private static final String TAG = "KernelNetNcnnProcessor";
    private static final String MODEL_PARAM = "models/kernelnet_aniso_v2_2_params.ncnn.param";
    private static final long INIT_TIMEOUT_MS = 30000;

    private static volatile KernelNetNcnnProcessor sInstance;
    private static final Object sLock = new Object();

    private final Context appContext;
    private final CountDownLatch initLatch = new CountDownLatch(1);
    private final Object inferenceLock = new Object();
    private volatile long nativeHandle;
    private volatile boolean ready = false;

    static { System.loadLibrary("ncnnMl"); }

    /**
     * Ensures the singleton is loading the model in the background (idempotent).
     */
    public static KernelNetNcnnProcessor start(Context context) {
        KernelNetNcnnProcessor inst = sInstance;
        if (inst == null) {
            synchronized (sLock) {
                inst = sInstance;
                if (inst == null) {
                    inst = new KernelNetNcnnProcessor(context);
                    sInstance = inst;
                }
            }
        }
        return inst;
    }

    /** The process-wide processor, or null if {@link #start} was never called. */
    public static KernelNetNcnnProcessor getInstance() {
        return sInstance;
    }

    private KernelNetNcnnProcessor(Context context) {
        appContext = context.getApplicationContext();
        Thread t = new Thread(this::backgroundInit, "kernelnet-ncnn-init");
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
            Log.e(TAG, "KernelNetNcnn: failed to initialize", t);
            nativeHandle = 0;
        }
        if (nativeHandle == 0) {
            Log.w(TAG, "KernelNetNcnn: model unavailable: " + MODEL_PARAM
                    + " (see NcnnML logcat for the native load error)");
            ready = false;
            initLatch.countDown();
            return;
        }
        ready = true;
        Log.d(TAG, "kernelnet ncnn init in "
                + (System.currentTimeMillis() - t0) + "ms");
        initLatch.countDown();
    }

    /**
     * Run the parameter model on one luma plane.
     *
     * @param gray   direct FloatBuffer of quad-mean sqrt luma in [0,1],
     *               length == width*height (see RawSrKernelNetAniso.lumaPlane)
     * @param width  input width (quad-res)
     * @param height input height (quad-res)
     * @param sigma  estimated noise sigma (scalar, normalized domain, at
     *               mid-brightness like upstream ESD4D.kernelSigma)
     * @return half-res s1/s2/rho planes, or null on error / if not ready
     */
    public Result runInference(FloatBuffer gray, int width, int height, float sigma) {
        if (!waitReady(INIT_TIMEOUT_MS)) return null;
        synchronized (inferenceLock) {
            return runInferenceGuarded(gray, width, height, sigma, null);
        }
    }

    /**
     * Allocation-free overload for the burst save path: when {@code out} is a
     * direct buffer of at least {@code outW*outH*3} floats it is filled in
     * place (native writes from its base address); otherwise a fresh buffer
     * is allocated exactly like {@link #runInference(FloatBuffer, int, int, float)}.
     * Reusing one caller-owned pair across burst frames keeps the 512MB-heap
     * save path from churning ~22MB of direct buffers per frame. The returned
     * {@link Result} borrows {@code out}: consume it before the next call.
     */
    public Result runInference(FloatBuffer gray, int width, int height, float sigma, FloatBuffer out) {
        if (!waitReady(INIT_TIMEOUT_MS)) return null;
        synchronized (inferenceLock) {
            return runInferenceGuarded(gray, width, height, sigma, out);
        }
    }

    private Result runInferenceGuarded(FloatBuffer gray, int width, int height, float sigma, FloatBuffer out) {
        if (nativeHandle == 0 || gray == null || width <= 0 || height <= 0) return null;
        if (!gray.isDirect() || gray.capacity() < (long) width * height) {
            Log.e(TAG, "KernelNetNcnn: gray buffer too small: cap=" + gray.capacity()
                    + " need=" + ((long) width * height));
            return null;
        }
        long start = System.nanoTime();
        int outW = (width - 1) / 2 + 1;
        int outH = (height - 1) / 2 + 1;
        final long need = (long) outW * outH * 3;
        FloatBuffer outBuf = out;
        if (outBuf == null || !outBuf.isDirect() || outBuf.capacity() < need) {
            if (outBuf != null) {
                Log.w(TAG, "KernelNetNcnn: scratch too small (" + outBuf.capacity()
                        + " < " + need + "), allocating");
            }
            try {
                final long bytes = need * 4;
                if (bytes > Integer.MAX_VALUE) {
                    Log.e(TAG, "KernelNetNcnn: output too large (" + need + " floats)");
                    return null;
                }
                outBuf = ByteBuffer.allocateDirect((int) bytes)
                        .order(ByteOrder.nativeOrder()).asFloatBuffer();
            } catch (OutOfMemoryError oom) {
                // Caller (bridge) falls back to the analytic field; log once
                // here so the reason survives even if the caller stays quiet.
                Log.w(TAG, "KernelNetNcnn: output allocation failed (" + need + " floats)", oom);
                return null;
            }
        } else {
            outBuf.clear();
        }
        gray.rewind();
        boolean ok;
        try {
            ok = nativeRun(nativeHandle, gray, width, height, sigma, outBuf);
        } catch (Throwable t) {
            Log.e(TAG, "KernelNetNcnn: inference failed", t);
            return null;
        }
        if (!ok) {
            Log.e(TAG, "KernelNetNcnn: inference returned an error");
            return null;
        }
        Log.d(TAG, "inference " + width + "x" + height + " -> "
                + outW + "x" + outH + " in " + (System.nanoTime() - start) / 1_000_000 + "ms");
        return new Result(outBuf, outW, outH);
    }

    /** Close the native ncnn net. Safe to call multiple times. */
    public void close() {
        synchronized (sLock) {
            if (sInstance == this) sInstance = null;
        }
        synchronized (inferenceLock) {
            long h = nativeHandle;
            if (h != 0) {
                nativeHandle = 0;
                ready = false;
                nativeDestroy(h);
            }
        }
    }

    /**
     * Parameter map at half resolution: channel-major float32 planes
     * {@code [s1][s2][rho]}, each {@code width*height} floats, row-major.
     * s1/s2 in [0, 2]-ish kernel sigmas (input-texel units; s1 is the
     * y-sigma, s2 the x-sigma per the upstream merge convention),
     * rho in [-1, 1].
     */
    public static class Result {
        private final FloatBuffer params;
        public final int width;
        public final int height;

        Result(FloatBuffer params, int width, int height) {
            this.params = params;
            this.width = width;
            this.height = height;
        }

        /** Direct buffer of {@code width*height*3} floats, rewound. */
        public FloatBuffer params() {
            FloatBuffer dup = params.duplicate();
            dup.rewind();
            return dup;
        }
    }

    private static native long nativeCreate(AssetManager assetManager, String paramPath);
    private static native boolean nativeRun(long handle, FloatBuffer gray, int width, int height,
                                            float sigma, FloatBuffer out);
    private static native void nativeDestroy(long handle);
}
