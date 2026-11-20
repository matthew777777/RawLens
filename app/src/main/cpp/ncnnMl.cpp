//
// Created by eszdman on 16.08.2026.
//
// Single JNI wrapper around ncnn (Vulkan backend) for the RawNIND denoisers
// used by RawLens:
//
//   * RawNIND single-frame denoisers (two coexisting variants):
//     - RawNIND-tiny (rawnind_tiny.ncnn.param/.bin, python/rawnind-train:
//       5ch packed [R,G1,G2,B]+sigma in, 4ch packed Bayer out, same packed res)
//     - RawNIND-bayer/UtNet2 (rawnind_bayer.ncnn.param/.bin, RawNIND upstream:
//       4ch packed [R,G1,G2,B] in, no sigma, 3ch camRGB out at 2x packed res,
//       i.e. full Bayer resolution, arbitrary gain)
//     Paste converted files into app/src/main/assets/models/ — absent files
//     simply report unavailable. Each handle auto-detects its variant from
//     its own .param, so old and new models work through the same JNI entry
//     points with per-variant buffer contracts (see RawNindCtx).
//
// Both variants share one ncnn runtime linked statically into this library
// (see ncnn/<ABI>/lib/libncnn.a).
//
// Java side:
//   com.particlesdevs.photoncamera.processing.ml.RawNindNcnnProcessor
//
// The library is built as libncnnMl.so and loaded via System.loadLibrary("ncnnMl").
//

#include <jni.h>
#ifdef __ANDROID__
#include <android/log.h>
#include <android/asset_manager_jni.h>
#include <android/asset_manager.h>
#else
// Desktop host build (tools/sr-vulkan native ncnnMl): file-backed models,
// stderr logging. Every inference line below is shared verbatim.
#include <cstdio>
#endif

#include <algorithm>
#include <cstdlib>
#include <cstring>
#include <memory>
#include <string>
#include <sys/time.h>
#include <vector>

#include "net.h"
#include "mat.h"
#if NCNN_VULKAN
#include "gpu.h"
#endif


#ifdef _OPENMP
#include <omp.h>
#endif

#define LOG_TAG "NcnnML"
#ifdef __ANDROID__
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, LOG_TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, LOG_TAG, __VA_ARGS__)
#else
// Same messages, stderr instead of logcat (identical format/args at every
// call site; only the sink differs).
#define LOGI(...) do { fprintf(stderr, "[NcnnML] "); fprintf(stderr, __VA_ARGS__); fprintf(stderr, "\n"); } while (0)
#define LOGE(...) do { fprintf(stderr, "[NcnnML][E] "); fprintf(stderr, __VA_ARGS__); fprintf(stderr, "\n"); } while (0)
#endif

// Pin the OpenMP runtime to a fixed number of threads. No CPU-count / core
// topology probing here — the caller decides the thread count.
static void pinOpenMPThreads(int num_threads) {
#ifdef _OPENMP
    omp_set_dynamic(0);
    omp_set_num_threads(num_threads);
    LOGI("openmp: linked statically, pinned to %d threads", omp_get_max_threads());
#else
    (void)num_threads;
#endif
}

static int64_t nowMs() {
    struct timeval tv;
    gettimeofday(&tv, nullptr);
    return (int64_t)tv.tv_sec * 1000 + tv.tv_usec / 1000;
}

static int64_t nowUs() {
    struct timeval tv;
    gettimeofday(&tv, nullptr);
    return (int64_t)tv.tv_sec * 1000000 + tv.tv_usec;
}

// Derive .bin asset path from .param path: "models/foo.ncnn.param" → "models/foo.ncnn.bin"
static std::string paramToBinPath(const std::string& paramPath) {
    std::string binPath = paramPath;
    size_t pos = binPath.rfind(".param");
    if (pos != std::string::npos)
        binPath.replace(pos, 6, ".bin");
    return binPath;
}

#ifndef __ANDROID__
// Desktop host: the shim android.content.res.AssetManager carries its APK-
// asset root as a java.io.File (getRootDir). Model files resolve under it as
// "<root>/<paramPath>" with the .bin derived by paramToBinPath, exactly the
// layout the Android branch reads from the APK.
static std::string desktopAssetRoot(JNIEnv* env, jobject assetManager) {
    if (assetManager == nullptr) return std::string();
    jclass amClass = env->GetObjectClass(assetManager);
    if (amClass == nullptr) return std::string();
    jmethodID getRoot = env->GetMethodID(amClass, "getRootDir", "()Ljava/io/File;");
    if (getRoot == nullptr) return std::string();
    jobject rootFile = env->CallObjectMethod(assetManager, getRoot);
    if (rootFile == nullptr || env->ExceptionCheck()) {
        env->ExceptionClear();
        return std::string();
    }
    jclass fileClass = env->GetObjectClass(rootFile);
    if (fileClass == nullptr) return std::string();
    jmethodID getAbs = env->GetMethodID(fileClass, "getAbsolutePath", "()Ljava/lang/String;");
    if (getAbs == nullptr) return std::string();
    jstring absStr = (jstring)env->CallObjectMethod(rootFile, getAbs);
    if (absStr == nullptr || env->ExceptionCheck()) {
        env->ExceptionClear();
        return std::string();
    }
    const char* utf = env->GetStringUTFChars(absStr, nullptr);
    std::string root = (utf != nullptr) ? utf : "";
    if (utf != nullptr) env->ReleaseStringUTFChars(absStr, utf);
    return root;
}

// Desktop host: slurp a whole file (used to sniff the .param for the
// RawNIND variant, mirroring the AAsset branch below).
static bool desktopReadFile(const std::string& path, std::string* text) {
    FILE* f = fopen(path.c_str(), "rb");
    if (f == nullptr) return false;
    fseek(f, 0, SEEK_END);
    long len = ftell(f);
    fseek(f, 0, SEEK_SET);
    if (len < 0) {
        fclose(f);
        return false;
    }
    text->resize((size_t)len);
    if (len > 0 && fread(&(*text)[0], 1, (size_t)len, f) != (size_t)len) {
        fclose(f);
        text->clear();
        return false;
    }
    fclose(f);
    return true;
}
#endif // __ANDROID__

// ---------------------------------------------------------------------------
// Shared init (no-op for ncnn; kept for Java compatibility).
// ---------------------------------------------------------------------------
extern "C" JNIEXPORT jboolean JNICALL
Java_com_particlesdevs_photoncamera_processing_ml_NcnnMl_nativeEnsureInit(
    JNIEnv*, jclass) {
    return JNI_TRUE;
}

// ===========================================================================
// RawNIND single-frame denoisers (python/rawnind-train tiny + upstream bayer).
//
// Variants (auto-detected per handle from its .param — see
// detectRawNindVariant; both share in0/out0 blob names):
//   TINY  input  (1,5,H/2,W/2) packed [R,G1,G2,B] + sigma plane, normalized
//         [0,1]-ish floats AFTER black/white normalization and lens-shading
//         correction, BEFORE demosaic. Java passes channel-last H2xW2x5.
//         output (1,4,H/2,W/2) denoised packed Bayer, same layout minus sigma.
//   BAYER input  (1,4,H/2,W/2) packed [R,G1,G2,B], same domain but NO sigma
//         plane (arbitrary gain handled inside the net). Java passes
//         channel-last H2xW2x4.
//         output (1,3,H,W) denoised camRGB at 2x packed resolution, i.e.
//         full Bayer resolution, channel-last HxWx3 (demosaiced — bypasses
//         AMaZE instead of feeding it).
//
// This runs on FIXED-SIZE tiles so Vulkan blob/workspace
// allocators reuse the same device-memory blocks for every capture: TILE is
// constant (core + 2*border, packed px), tiles overlap by tileBorder (>= the
// model's RF radius of 23 packed px), and only each tile's interior core is
// written out. Packed origins always map to even Bayer origins, so no
// stride-phase correction is needed (the net has no stride-2 layers with
// absolute phase; all downsamples are symmetric).
//
// GPU is the default backend (flagship target; fp16 Vulkan).
// Env knobs (debug/experimentation):
//   RN_CPU=1    force CPU backend
//   RN_FP32=1   disable fp16 storage/arithmetic
//   RN_TILE=N   packed-px tile core, rounded up to /16 (default 512 = 1024 Bayer)
//   RN_BORDER=N tile overlap in packed px (default 32, minimum 24)
//   RN_NOTILE=1 fall back to a single full-res pass (A/B comparison only)
// ===========================================================================

struct RawNindCtx {
    ncnn::Net net;
#if NCNN_VULKAN
    const ncnn::VulkanDevice* allocatorDevice = nullptr;
    ncnn::VkAllocator* blobPool = nullptr;
    ncnn::VkAllocator* stagingPool = nullptr;
#endif
    ~RawNindCtx() {
        // Release tensors/pipelines before returning their allocators. Failed
        // warmups and repeated enable/disable must not strand device memory.
        net.clear();
#if NCNN_VULKAN
        if (allocatorDevice) {
            if (blobPool) allocatorDevice->reclaim_blob_allocator(blobPool);
            if (stagingPool) allocatorDevice->reclaim_staging_allocator(stagingPool);
        }
#endif
    }
    int tileCore = 512;    // packed-px interior advance (~512 gives a 4x3-ish
                           // tile grid at 12MP packed 2000x1500)
    int tileBorder = 32;   // overlap margin per side (packed px, >= RF 23)
    bool stageTiming = false;
    // Per-handle variant (see detectRawNindVariant):
    //   tiny:  inChannels=5, outChannels=4, outScale=1
    //   bayer: inChannels=4, outChannels=3, outScale=2
    int inChannels = 5;
    int outChannels = 4;
    int outScale = 1;
    // Fixed-size tile Mats, allocated on first run and reused for every tile
    // of every capture (identical shapes -> stable GPU memory).
    ncnn::Mat inTile;      // TILE x TILE x inChannels (interleaved scratch)
};

// The JNI signature is identical for both variants, so each handle sniffs
// its own .param asset: the bayer graph ends with a PixelShuffle
// (DepthToSpace) upsampling tail, while legacy tiny ends with a residual
// BinaryOp add and starts with a sigma-stripping Split/Crop. Unknown graphs
// keep the legacy tiny contract (fail loudly on dims, never silently).
struct RawNindVariant {
    int inChannels;
    int outChannels;
    int outScale;
};

#ifdef __ANDROID__
static RawNindVariant detectRawNindVariant(AAssetManager* mgr,
                                           const std::string& paramPath) {
    RawNindVariant v{5, 4, 1};
    AAsset* asset = AAssetManager_open(mgr, paramPath.c_str(), AASSET_MODE_BUFFER);
    if (asset == nullptr) return v;
    size_t len = AAsset_getLength(asset);
    const char* buf = static_cast<const char*>(AAsset_getBuffer(asset));
    std::string text(buf != nullptr ? buf : "", len);
    AAsset_close(asset);
    if (text.find("PixelShuffle") != std::string::npos) {
        v = {4, 3, 2};
    }
    return v;
}
#endif

#ifndef __ANDROID__
static RawNindVariant detectRawNindVariantFromFile(const std::string& paramFullPath) {
    RawNindVariant v{5, 4, 1};
    std::string text;
    if (!desktopReadFile(paramFullPath, &text)) return v;
    if (text.find("PixelShuffle") != std::string::npos) {
        v = {4, 3, 2};
    }
    return v;
}
#endif

static jboolean rawnindRunFull(RawNindCtx* ctx, const float* inPtr,
                               int w2, int h2, float* outPtr);
static jboolean rawnindRunTiled(RawNindCtx* ctx, const float* inPtr,
                                int w2, int h2, float* outPtr);

extern "C" JNIEXPORT jlong JNICALL
Java_com_particlesdevs_photoncamera_processing_ml_RawNindNcnnProcessor_nativeCreate(
    JNIEnv* env, jclass, jobject assetManager, jstring paramPath) {
#ifdef __ANDROID__
    AAssetManager* mgr = AAssetManager_fromJava(env, assetManager);
    if (mgr == nullptr) {
        LOGE("AAssetManager_fromJava failed");
        return 0;
    }
#else
    const std::string assetRoot = desktopAssetRoot(env, assetManager);
    if (assetRoot.empty()) {
        LOGE("rawnind: cannot resolve shim AssetManager rootDir");
        return 0;
    }
#endif
    const char* path = env->GetStringUTFChars(paramPath, nullptr);
    if (path == nullptr) return 0;
    std::string paramStr = path;
    env->ReleaseStringUTFChars(paramPath, path);

    auto* ctx = new (std::nothrow) RawNindCtx();
    if (ctx == nullptr) return 0;

    // GPU-first: the net is a ~1M-param U-Net that is compute-bound at full
    // res, so Vulkan fp16 wins on flagship Adreno/Mali (unlike a tiny net).
    ctx->net.opt.use_vulkan_compute = true;
    ctx->net.opt.use_fp16_packed = true;
    ctx->net.opt.use_fp16_storage = true;
    ctx->net.opt.use_fp16_arithmetic = true;
    ctx->net.opt.use_bf16_storage = false;
    ctx->net.opt.use_subgroup_ops = false;
    ctx->net.opt.num_threads = 4;
    ctx->net.opt.lightmode = false;
    pinOpenMPThreads(ctx->net.opt.num_threads);

    if (getenv("RN_CPU") && getenv("RN_CPU")[0] == '1') {
        ctx->net.opt.use_vulkan_compute = false;
        LOGI("rawnind: Vulkan disabled by RN_CPU=1, using CPU");
    }
    if (getenv("RN_FP32") && getenv("RN_FP32")[0] == '1') {
        ctx->net.opt.use_fp16_packed = false;
        ctx->net.opt.use_fp16_storage = false;
        ctx->net.opt.use_fp16_arithmetic = false;
        LOGI("rawnind: fp32 storage path selected by RN_FP32=1");
    }

    std::string binPath = paramToBinPath(paramStr);

#ifdef __ANDROID__
    RawNindVariant variant = detectRawNindVariant(mgr, paramStr);
#else
    RawNindVariant variant = detectRawNindVariantFromFile(assetRoot + "/" + paramStr);
#endif
    ctx->inChannels = variant.inChannels;
    ctx->outChannels = variant.outChannels;
    ctx->outScale = variant.outScale;
    if (ctx->outScale == 2) {
        // RawNIND Bayer predicts arbitrary learned gain. Real outputs exceed
        // FP16's 65504 limit before host gain matching: fp16 yielded Inf/NaN.
        ctx->net.opt.use_fp16_packed = false;
        ctx->net.opt.use_fp16_storage = false;
        ctx->net.opt.use_fp16_arithmetic = false;
        ctx->net.opt.lightmode = true;
        // FP32 activations dominate memory, including during the 64x64
        // startup warmup (which also runs a complete fixed-size tile).
        // A 256px tile uses one quarter of the old 512px activation storage.
        // Preserve the overlap/receptive-field margin for capture seams.
        ctx->tileCore = 128;
        ctx->tileBorder = 64;
    }

#ifdef __ANDROID__
    if (ctx->net.load_param(mgr, paramStr.c_str()) != 0) {
        LOGE("rawnind load_param(%s) failed", paramStr.c_str());
        delete ctx;
        return 0;
    }
    if (ctx->net.load_model(mgr, binPath.c_str()) != 0) {
        LOGE("rawnind load_model(%s) failed", binPath.c_str());
        delete ctx;
        return 0;
    }
#else
    // Desktop host: same files, resolved under the shim AssetManager root.
    const std::string paramFull = assetRoot + "/" + paramStr;
    const std::string binFull = assetRoot + "/" + binPath;
    if (ctx->net.load_param(paramFull.c_str()) != 0) {
        LOGE("rawnind load_param(%s) failed", paramFull.c_str());
        delete ctx;
        return 0;
    }
    if (ctx->net.load_model(binFull.c_str()) != 0) {
        LOGE("rawnind load_model(%s) failed", binFull.c_str());
        delete ctx;
        return 0;
    }
#endif

    if (const char* t = getenv("RN_TILE")) {
        int v = atoi(t);
        if (v >= 128) ctx->tileCore = (v + 15) / 16 * 16;
    }
    if (const char* b = getenv("RN_BORDER")) {
        int v = atoi(b);
        if (v >= 24) ctx->tileBorder = (v + 15) / 16 * 16;
    }
    if (const char* st = getenv("RN_STAGETIMING")) {
        ctx->stageTiming = strcmp(st, "0") != 0;
    }

#if NCNN_VULKAN
    // Persistent, device-pooled Vulkan allocators (same rationale as
    // KernelNet): every tile reuses blocks instead of paying
    // vkAllocateMemory per extraction.
    if (ctx->net.opt.use_vulkan_compute &&
        !(getenv("RN_NOALLOC") && getenv("RN_NOALLOC")[0] == '1')) {
        const ncnn::VulkanDevice* vkdev = ctx->net.vulkan_device();
        if (vkdev != nullptr) {
            ctx->allocatorDevice = vkdev;
            ctx->blobPool = vkdev->acquire_blob_allocator();
            ctx->stagingPool = vkdev->acquire_staging_allocator();
            ctx->net.opt.blob_vkallocator = ctx->blobPool;
            ctx->net.opt.workspace_vkallocator = ctx->blobPool;
            ctx->net.opt.staging_vkallocator = ctx->stagingPool;
            LOGI("rawnind: persistent vk allocators attached (pooled)");
        } else {
            LOGE("rawnind: vulkan_device null, cannot attach pooled allocators");
        }
    }
#endif

    LOGI("rawnind model loaded (%s: %dch in, %dch out, x%d; tile core=%d border=%d)",
         ctx->outScale == 2 ? "bayer" : "tiny",
         ctx->inChannels, ctx->outChannels, ctx->outScale,
         ctx->tileCore, ctx->tileBorder);

#if NCNN_VULKAN
    if (ctx->net.opt.use_vulkan_compute) {
        LOGI("rawnind backend: vulkan (gpu_count=%d)", ncnn::get_gpu_count());
        LOGI("rawnind vulkan_device=%s",
             ctx->net.vulkan_device() ? "created (GPU ops in use)" : "NULL (running on CPU!)");
    } else {
        LOGI("rawnind backend: cpu (%d threads)", ctx->net.opt.num_threads);
    }
#else
    LOGI("rawnind backend: cpu, ncnn built without vulkan (%d threads)",
         ctx->net.opt.num_threads);
#endif
    return (jlong)ctx;
}

// packed: [w2*h2*inChannels] channel-last floats, normalized domain.
//   tiny:  5ch (R,G1,G2,B,S); out: [w2*h2*4] channel-last (R,G1,G2,B).
//   bayer: 4ch (R,G1,G2,B);    out: [(2*w2)*(2*h2)*3] channel-last camRGB.
// The variant travels with the handle (see RawNindCtx), so one entry point
// serves both models; buffer sizes are validated Java-side and dims
// double-checked here after extract.
extern "C" JNIEXPORT jboolean JNICALL
Java_com_particlesdevs_photoncamera_processing_ml_RawNindNcnnProcessor_nativeRun(
    JNIEnv* env, jclass, jlong handle, jobject packedBuffer, jint w2, jint h2,
    jobject outBuffer) {
    auto* ctx = reinterpret_cast<RawNindCtx*>(handle);
    if (ctx == nullptr) return JNI_FALSE;
    if (w2 <= 0 || h2 <= 0) return JNI_FALSE;
    pinOpenMPThreads(ctx->net.opt.num_threads);

    const float* inPtr = static_cast<const float*>(env->GetDirectBufferAddress(packedBuffer));
    float* outPtr = static_cast<float*>(env->GetDirectBufferAddress(outBuffer));
    if (inPtr == nullptr || outPtr == nullptr) {
        LOGE("GetDirectBufferAddress failed");
        return JNI_FALSE;
    }

    if (getenv("RN_NOTILE") && getenv("RN_NOTILE")[0] == '1')
        return rawnindRunFull(ctx, inPtr, w2, h2, outPtr);
    return rawnindRunTiled(ctx, inPtr, w2, h2, outPtr);
}

// Single full-res pass. Kept for A/B comparison via RN_NOTILE=1; production
// uses the tiled path (stable Vulkan footprint across captures).
static jboolean rawnindRunFull(RawNindCtx* ctx, const float* inPtr,
                               int w2, int h2, float* outPtr) {
    const int C = ctx->inChannels;
    const int OC = ctx->outChannels;
    const int S = ctx->outScale;
    ncnn::Mat in(w2, h2, C);
    if (in.empty()) return JNI_FALSE;
    for (int c = 0; c < C; c++) {
        float* ch = (float*)in.channel(c);
        for (int i = 0, n = w2 * h2; i < n; i++) ch[i] = inPtr[C * i + c];
    }

    int64_t tStart = nowMs();

    ncnn::Extractor ex = ctx->net.create_extractor();
    // Blob names follow the pnnx export (Input in0 ... out0),
    // NOT the ONNX input/output names from export.py.
    int ret_in = ex.input("in0", in);
    if (ret_in != 0) {
        LOGE("rawnind input failed ret=%d", ret_in);
        return JNI_FALSE;
    }

    ncnn::Mat out;
    int ret_out = ex.extract("out0", out);
    if (ret_out != 0) {
        LOGE("rawnind extract failed ret=%d", ret_out);
        return JNI_FALSE;
    }

    LOGI("rawnind forward %dx%d took %lld ms", w2, h2,
         (long long)(nowMs() - tStart));

    // Tiny: 4ch packed at the same resolution. Bayer: 3ch camRGB at 2x
    // packed resolution (full Bayer size).
    const int ow = w2 * S, oh = h2 * S;
    if (out.c != OC || out.w != ow || out.h != oh) {
        LOGE("unexpected rawnind output dims=%d w=%d h=%d c=%d (want %dch %dx%d)",
             out.dims, out.w, out.h, out.c, OC, ow, oh);
        return JNI_FALSE;
    }
    const float* chp[4];
    for (int c = 0; c < OC; c++) chp[c] = (const float*)out.channel(c);
    for (int i = 0, n = ow * oh; i < n; i++) {
        for (int c = 0; c < OC; c++) {
            if (!std::isfinite(chp[c][i])) {
                LOGE("rawnind non-finite output");
                return JNI_FALSE;
            }
            outPtr[OC * i + c] = chp[c][i];
        }
    }

    return JNI_TRUE;
}

// Fill one TILE x TILE C-channel tile from the channel-last full-res plane
// with clamp-to-edge padding. Tile origins are always >= 0, so only
// right/bottom can overflow.
static void fillTileClamped(float* dst, const float* src, int W, int H,
                            int x0, int y0, int T, int C) {
    for (int y = 0; y < T; y++) {
        int sy = y0 + y;
        if (sy > H - 1) sy = H - 1;
        const int xSpan = (x0 + T <= W) ? T : (W - x0);
        const float* srow = src + (size_t)sy * W * C + (size_t)x0 * C;
        float* drow = dst + (size_t)y * T * C;
        memcpy(drow, srow, (size_t)xSpan * C * sizeof(float));
        if (xSpan < T) {
            const float* edge = src + (size_t)sy * W * C + (size_t)(W - 1) * C;
            for (int x = xSpan; x < T; x++)
                memcpy(drow + (size_t)x * C, edge, C * sizeof(float));
        }
    }
}

static jboolean rawnindRunTiled(RawNindCtx* ctx, const float* inPtr,
                                int w2, int h2, float* outPtr) {
    const int B = ctx->tileBorder;      // overlap per side (packed px)
    const int STEP = ctx->tileCore;     // interior advance (packed px)
    const int TILE = STEP + 2 * B;      // fixed net input size
    const int C = ctx->inChannels;
    const int OC = ctx->outChannels;
    const int S = ctx->outScale;
    const int OTILE = TILE * S;         // net output tile (bayer: 2x input)

    if (ctx->inTile.empty()) {
        ctx->inTile.create(TILE, TILE, C);
    }

    // Number of tiles such that the last tile's core reaches the image edge.
    auto tileCount = [TILE, STEP](int dim) -> int {
        return dim <= TILE ? 1 : (dim - TILE + STEP - 1) / STEP + 1;
    };
    const int nx = tileCount(w2);
    const int ny = tileCount(h2);

    int64_t tStart = nowMs();
    int64_t tFill = 0, tInput = 0, tExtract = 0, tCopy = 0;
    int64_t worstExtract = 0;

    // Reused deinterleaved tile input: split the interleaved inTile into
    // per-channel planes without reallocating per tile.
    ncnn::Mat tileIn(TILE, TILE, C);
    if (ctx->inTile.empty() || tileIn.empty()) {
        LOGE("rawnind tile allocation failed (%dpx)", TILE);
        return JNI_FALSE;
    }

    for (int iy = 0; iy < ny; iy++) {
        const int ty0 = iy * STEP;
        const int vy0 = (iy == 0) ? 0 : ty0 + B;
        const int vy1 = (iy == ny - 1) ? h2 : ty0 + TILE - B;
        for (int ix = 0; ix < nx; ix++) {
            const int tx0 = ix * STEP;
            const int vx0 = (ix == 0) ? 0 : tx0 + B;
            const int vx1 = (ix == nx - 1) ? w2 : tx0 + TILE - B;

            int64_t s0 = nowUs();
            fillTileClamped((float*)ctx->inTile.data, inPtr, w2, h2, tx0, ty0, TILE, C);
            // Deinterleave tile (channel-last -> C planes).
            {
                const float* inter = (const float*)ctx->inTile.data;
                for (int c = 0; c < C; c++) {
                    float* ch = (float*)tileIn.channel(c);
                    for (int i = 0, n = TILE * TILE; i < n; i++) ch[i] = inter[C * i + c];
                }
            }
            int64_t s1 = nowUs();

            ncnn::Extractor ex = ctx->net.create_extractor();
            int ret_in = ex.input("in0", tileIn);
            if (ret_in != 0) {
                LOGE("rawnind tile input failed ret=%d", ret_in);
                return JNI_FALSE;
            }
            int64_t s2 = nowUs();
            ncnn::Mat out;
            int ret_out = ex.extract("out0", out);
            if (ret_out != 0) {
                LOGE("rawnind tile extract failed ret=%d", ret_out);
                return JNI_FALSE;
            }
            int64_t s3 = nowUs();
            if (out.c != OC || out.w != OTILE || out.h != OTILE) {
                LOGE("unexpected rawnind tile output dims=%d w=%d h=%d c=%d (want %dch %dx%d)",
                     out.dims, out.w, out.h, out.c, OC, OTILE, OTILE);
                return JNI_FALSE;
            }
            int64_t s4 = nowUs();

            // Core of this tile -> global channel-last output. Input valid
            // range [vx0,vx1) maps to [vx0*S,vx1*S) in the output (S=2 for
            // the bayer upsampling model, 1 for tiny).
            const int ox0 = vx0 * S, ox1 = vx1 * S;
            const int oy0 = vy0 * S, oy1 = vy1 * S;
            const int lx0 = (vx0 - tx0) * S, ly0 = (vy0 - ty0) * S;
            const float* och[4];
            for (int c = 0; c < OC; c++) och[c] = (const float*)out.channel(c);
            for (int y = oy0; y < oy1; y++) {
                float* dst = outPtr + ((size_t)y * (w2 * S) + ox0) * OC;
                const int ly = ly0 + (y - oy0);
                for (int x = ox0; x < ox1; x++) {
                    const int li = ly * OTILE + (lx0 + (x - ox0));
                    for (int c = 0; c < OC; c++) {
                        if (!std::isfinite(och[c][li])) {
                            LOGE("rawnind non-finite tile output");
                            return JNI_FALSE;
                        }
                        dst[c] = och[c][li];
                    }
                    dst += OC;
                }
            }
            int64_t s5 = nowUs();

            tFill += s1 - s0;
            tInput += s2 - s1;
            tExtract += s3 - s2;
            tCopy += s5 - s4;
            if (s3 - s2 > worstExtract) worstExtract = s3 - s2;
        }
    }

    LOGI("rawnind tiled %dx%d (%dx%d tiles of %dpx, core=%d border=%d)"
         " took %lld ms", w2, h2, nx, ny, TILE, STEP, B,
         (long long)(nowMs() - tStart));
    if (ctx->stageTiming) {
        const int n = nx * ny;
        LOGI("rawnind stages (%d tiles): fill=%lldus input=%lldus extract=%lldus"
             " (worst %lldus) copy=%lldus", n,
             (long long)tFill / n, (long long)tInput / n,
             (long long)tExtract / n, (long long)worstExtract,
             (long long)tCopy / n);
    }
    return JNI_TRUE;
}

extern "C" JNIEXPORT void JNICALL
Java_com_particlesdevs_photoncamera_processing_ml_RawNindNcnnProcessor_nativeDestroy(
    JNIEnv*, jclass, jlong handle) {
    auto* ctx = reinterpret_cast<RawNindCtx*>(handle);
    if (ctx != nullptr) delete ctx;
}
