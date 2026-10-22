package com.rawlens.srpipeline

import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Kotlin façade over the sr-pipeline native library (`srpipeline`).
 *
 * One API for desktop (JVM + host `libsrpipeline`) and Android (NDK
 * `libsrpipeline.so` packaged with the app): the C++ implementation behind
 * these entry points is identical on both platforms. There is deliberately
 * no PNG path here — image output goes through [writeDng] (TinyDNG v3).
 *
 * Loading is explicit (no static init) so JVM unit tests stay hermetic:
 * call [loadLibrary] on Android or [loadPath] on desktop before any other
 * method. Every method validates its arguments before crossing JNI.
 */
object SrpBridge {
    /** Load by library name (Android: packaged `libsrpipeline.so`). */
    @JvmStatic fun loadLibrary(name: String = "srpipeline") = System.loadLibrary(name)

    /** Load from an absolute path (desktop host build). */
    @JvmStatic fun loadPath(path: String) = System.load(path)

    /** Creates a Vulkan compute context; returns 0 when unavailable. */
    @JvmStatic external fun nativeCreate(): Long

    /** Releases a context from [nativeCreate]; no-op on 0. */
    @JvmStatic external fun nativeDestroy(handle: Long)

    /** Human-readable device summary, or "(no device)". */
    @JvmStatic external fun nativeDeviceInfo(handle: Long): String

    /**
     * Merge-accumulate over direct buffers: `acc[i] += gain * in[i]`.
     * Returns the `srp::Status` ordinal (0 = ok). Both buffers must be
     * direct, native-order, position 0, with at least `count` floats
     * remaining; `spirv` holds the compiled merge_accumulate.comp module.
     */
    @JvmStatic
    external fun nativeMergeAccumulate(
        handle: Long,
        inBuffer: ByteBuffer,
        accBuffer: ByteBuffer,
        spirv: ByteArray,
        count: Int,
        width: Int,
        height: Int,
        gain: Float,
    ): Int

    /**
     * GAT (op 0) / decimate (op 1) over direct buffers. Returns the
     * `srp::Status` ordinal (0 = ok). `inBuffer` holds `width*height`
     * full-res floats; `outBuffer` receives `count` floats (`width*height`
     * for op 0, quarter for op 1); `abBuffer` holds 8 floats
     * (alpha[0:4], beta[0:4], EXIF plane order; zeros for op 1). `spirv`
     * holds the compiled gat.comp module. All buffers direct, native-order,
     * position 0.
     */
    @JvmStatic
    external fun nativeGat(
        handle: Long,
        inBuffer: ByteBuffer,
        outBuffer: ByteBuffer,
        abBuffer: ByteBuffer,
        spirv: ByteArray,
        width: Int,
        height: Int,
        count: Int,
        op: Int,
    ): Int

    /**
     * Menon2007 demosaic-grey (demosaic.comp ops 0-11, RGGB only) over
     * direct buffers. Returns the `srp::Status` ordinal (0 = ok).
     * `cfaBuffer` holds `width*height` CFA floats, `greyBuffer` receives
     * `width*height` grey floats. `spirv` holds the compiled demosaic.comp
     * module. Finite CFA input only (no NaN fallback on-device).
     */
    @JvmStatic
    external fun nativeDemosaicMenon(
        handle: Long,
        cfaBuffer: ByteBuffer,
        greyBuffer: ByteBuffer,
        spirv: ByteArray,
        width: Int,
        height: Int,
    ): Int

    /**
     * Small-kernel gradients + preprocessing (grad.comp ops 0-3) over
     * direct buffers. Returns the `srp::Status` ordinal (0 = ok).
     * `inBuffer` holds `width*height` floats; `outBuffer` receives
     * `count` outputs (`count*2` interleaved-pair floats for ops 0-1,
     * `count` floats for ops 2-3). `expBits` carries the op-3 gamma
     * exponent by bit pattern. `spirv` holds the compiled grad.comp
     * module. All buffers direct, native-order, position 0.
     */
    @JvmStatic
    external fun nativeGrad(
        handle: Long,
        inBuffer: ByteBuffer,
        outBuffer: ByteBuffer,
        spirv: ByteArray,
        width: Int,
        height: Int,
        count: Int,
        op: Int,
        expBits: Int,
    ): Int

    /**
     * Kernel-covariance build (kernels.comp, Alg.5) over direct buffers.
     * Returns the `srp::Status` ordinal (0 = ok). `gradsBuffer` holds
     * `gradW*gradH*2` interleaved-pair floats, `kparamsBuffer` holds 8
     * floats (six shaping scalars + selection_law/is_iso by bit pattern),
     * `covBuffer` receives `covW*covH*4` floats ((xx,xy,yy,0) per cov
     * pixel). `spirv` holds the compiled kernels.comp module. All buffers
     * direct, native-order, position 0.
     */
    @JvmStatic
    external fun nativeKernelsCov(
        handle: Long,
        gradsBuffer: ByteBuffer,
        kparamsBuffer: ByteBuffer,
        covBuffer: ByteBuffer,
        spirv: ByteArray,
        gradW: Int,
        gradH: Int,
        covW: Int,
        covH: Int,
    ): Int

    /**
     * Elementwise divide (divide.comp, utils.divide) over direct buffers.
     * Returns the `srp::Status` ordinal (0 = ok). `numBuffer`/`denBuffer`
     * hold `count` floats each; `outBuffer` receives `count` quotients.
     * `numBuffer` and `outBuffer` may be the same object for in-place
     * `num /= den`. `spirv` holds the compiled divide.comp module. All
     * buffers direct, native-order, position 0.
     */
    @JvmStatic
    external fun nativeDivide(
        handle: Long,
        numBuffer: ByteBuffer,
        denBuffer: ByteBuffer,
        outBuffer: ByteBuffer,
        spirv: ByteArray,
        count: Int,
    ): Int

    /**
     * Resident device buffer: allocates a zero-filled buffer of `count`
     * floats on-device, returning a context-scoped handle (0 on failure).
     * The buffer stays on-device across calls (never evicted while held);
     * free it with [freeResident] (also freed with the context).
     */
    @JvmStatic
    external fun nativeCreateResident(handle: Long, count: Long): Long

    /** Returns the `srp::Status` ordinal (0 = ok; unknown handle fails). */
    @JvmStatic
    external fun nativeFreeResident(handle: Long, resident: Long): Int

    /**
     * Downloads `count` floats from a resident buffer into `outBuffer`.
     * Returns the `srp::Status` ordinal (0 = ok).
     */
    @JvmStatic
    external fun nativeDownloadResident(
        handle: Long,
        resident: Long,
        outBuffer: ByteBuffer,
        count: Long,
    ): Int

    /**
     * Uploads `count` floats from `inBuffer` into a resident buffer.
     * Returns the `srp::Status` ordinal (0 = ok).
     */
    @JvmStatic
    external fun nativeUploadResident(
        handle: Long,
        resident: Long,
        inBuffer: ByteBuffer,
        count: Long,
    ): Int

    /**
     * Merge-splat with device-resident accumulators (`numRes`/`denRes`
     * from [createResident]): stages only the per-frame inputs; num/den
     * stay on-device across frames (same dispatches, same order, same
     * zeros — bit-identical to [mergeSplat]). No readback.
     */
    @JvmStatic
    external fun nativeMergeSplatRes(
        handle: Long,
        compBuffer: ByteBuffer,
        flowBuffer: ByteBuffer,
        covBuffer: ByteBuffer,
        rBuffer: ByteBuffer,
        numRes: Long,
        denRes: Long,
        spirv: ByteArray,
        lrW: Int,
        lrH: Int,
        hrW: Int,
        hrH: Int,
        covCount: Int,
        rCount: Int,
        tileSize: Int,
        scale: Float,
        mode: Int,
        ftx: Int,
        fty: Int,
    ): Int

    /**
     * Divide with device-resident accumulators (in-place num=num/den,
     * like [divide]). Zero host traffic. Returns the `srp::Status`
     * ordinal (0 = ok).
     */
    @JvmStatic
    external fun nativeDivideRes(
        handle: Long,
        numRes: Long,
        denRes: Long,
        spirv: ByteArray,
        count: Int,
    ): Int

    /**
     * init_robustness (robustness.comp ops 0+1, bayer) over direct
     * buffers. Returns the `srp::Status` ordinal (0 = ok). `refBuffer`
     * holds `width*height` raw floats; `meansBuffer`/`varsBuffer` each
     * receive `3*gh*gw` floats (`gh = height/2`, `gw = width/2`). `spirv`
     * holds the compiled robustness.comp module. All buffers direct,
     * native-order, position 0.
     */
    @JvmStatic
    external fun nativeRobustInit(
        handle: Long,
        refBuffer: ByteBuffer,
        meansBuffer: ByteBuffer,
        varsBuffer: ByteBuffer,
        spirv: ByteArray,
        width: Int,
        height: Int,
    ): Int

    /**
     * compute_robustness minus the smap leg (ops 0+1+2+3+4+5, bayer) over
     * direct buffers. Returns the `srp::Status` ordinal (0 = ok).
     * `compBuffer` holds `width*height` raw floats; `meansBuffer`/
     * `varsBuffer` hold `3*gh*gw` reference stats; `flowBuffer` holds
     * `ftx*fty*2` pairs; `lutBuffer` holds `2*lutN` curve floats;
     * `sBuffer` holds the `ftx*fty` spread map; `outBuffer` receives the
     * `gh*gw` robustness map. `tBits` carries t by bit pattern. `spirv`
     * holds the compiled robustness.comp module.
     */
    @JvmStatic
    external fun nativeRobustCompute(
        handle: Long,
        compBuffer: ByteBuffer,
        meansBuffer: ByteBuffer,
        varsBuffer: ByteBuffer,
        flowBuffer: ByteBuffer,
        lutBuffer: ByteBuffer,
        sBuffer: ByteBuffer,
        outBuffer: ByteBuffer,
        spirv: ByteArray,
        width: Int,
        height: Int,
        tileSize: Int,
        lutN: Int,
        noiseCorrection: Int,
        tBits: Int,
        ftx: Int,
        fty: Int,
    ): Int

    /**
     * init_robustness with device-resident stats. Returns the
     * `srp::Status` ordinal (0 = ok). `refBuffer` holds
     * `width*height` raw floats; `meansRes`/`varsRes` are residents of
     * exactly `3*gh*gw` floats each (from [createResident]) that receive
     * the stats and stay on-device — no host download. Same shader and
     * input bytes as [nativeRobustInit]: bit-identical on-device stats.
     */
    @JvmStatic
    external fun nativeRobustInitRes(
        handle: Long,
        refBuffer: ByteBuffer,
        meansRes: Long,
        varsRes: Long,
        spirv: ByteArray,
        width: Int,
        height: Int,
    ): Int

    /**
     * compute_robustness minus the smap leg with device-resident
     * reference stats. Returns the `srp::Status` ordinal (0 = ok).
     * `compBuffer` holds `width*height` raw floats; `meansRes`/`varsRes`
     * are the residents written by [nativeRobustInitRes]; `flowBuffer`
     * holds `ftx*fty*2` pairs; `lutBuffer` holds `2*lutN` curve floats;
     * `sBuffer` holds the `ftx*fty` spread map; `outBuffer` receives the
     * `gh*gw` robustness map. `tBits` carries t by bit pattern. Same
     * shader and bytes as [nativeRobustCompute]: bit-identical output.
     */
    @JvmStatic
    external fun nativeRobustComputeRes(
        handle: Long,
        compBuffer: ByteBuffer,
        meansRes: Long,
        varsRes: Long,
        flowBuffer: ByteBuffer,
        lutBuffer: ByteBuffer,
        sBuffer: ByteBuffer,
        outBuffer: ByteBuffer,
        spirv: ByteArray,
        width: Int,
        height: Int,
        tileSize: Int,
        lutN: Int,
        noiseCorrection: Int,
        tBits: Int,
        ftx: Int,
        fty: Int,
    ): Int

    /**
     * compute_s (op 6) over direct buffers. Returns the `srp::Status`
     * ordinal (0 = ok). `flowBuffer` holds `ntx*nty*2` interleaved-pair
     * floats (finite only); `sBuffer` receives `ntx*nty` floats. Mt/s1/s2
     * ride by bit pattern. `spirv` holds the compiled robustness.comp
     * module.
     */
    @JvmStatic
    external fun nativeSmap(
        handle: Long,
        flowBuffer: ByteBuffer,
        sBuffer: ByteBuffer,
        spirv: ByteArray,
        ntx: Int,
        nty: Int,
        mtBits: Int,
        s1Bits: Int,
        s2Bits: Int,
    ): Int

    /**
     * Grey-FFT lowpass over a resident frame. `frameRes` must hold
     * `width*height` reals (uploaded once per frame); `outBuffer`
     * receives `width*height` floats. Bit-identical to the staged
     * greyHave path; saves one full-frame upload per call.
     */
    @JvmStatic
    external fun nativeGreyFftRes(
        handle: Long,
        frameRes: Long,
        outBuffer: ByteBuffer,
        fftSpirv: ByteArray,
        corrSpirv: ByteArray,
        width: Int,
        height: Int,
    ): Int

    /**
     * gat/decimate (gat.comp, op 0/1) over resident scratch. `inRes`
     * holds `width*height` floats; `outRes` holds `count`; `ab` is the
     * 8-float profile staged as a direct buffer. Bit-identical to
     * nativeGat.
     */
    @JvmStatic
    external fun nativeGatRes(
        handle: Long,
        inRes: Long,
        outRes: Long,
        ab: ByteBuffer,
        spirv: ByteArray,
        width: Int,
        height: Int,
        count: Int,
        op: Int,
    ): Int

    /**
     * kerngrad/sobel/sqrt/gamma (grad.comp, op 0..3) over resident
     * scratch. `inRes` holds `width*height` floats; `outRes` holds
     * `count` (op 0/1) or `count*2` floats. Bit-identical to nativeGrad.
     */
    @JvmStatic
    external fun nativeGradRes(
        handle: Long,
        inRes: Long,
        outRes: Long,
        spirv: ByteArray,
        width: Int,
        height: Int,
        count: Int,
        op: Int,
        expBits: Int,
    ): Int

    /**
     * Kernel-covariance build (kernels.comp) over resident scratch.
     * `gradsRes` holds `gradW*gradH*2` floats; `covRes` receives
     * `covW*covH*4`; `kp` is the 8-float profile staged as a direct
     * buffer. Bit-identical to nativeKernelsCov.
     */
    @JvmStatic
    external fun nativeKernelsCovRes(
        handle: Long,
        gradsRes: Long,
        kp: ByteBuffer,
        covRes: Long,
        spirv: ByteArray,
        gradW: Int,
        gradH: Int,
        covW: Int,
        covH: Int,
    ): Int

    /**
     * Direct L2 block-matching cross term (corr_direct.comp): the central
     * (2r+1)^2 correlations of `chunkTiles` tiles from `tileOffset`, same
     * layout as the cropped FFT correlation. `refRes` is the pinned
     * reference level (`refW*refH`), `patches` the extracted moving patches.
     */
    @JvmStatic
    external fun nativeCorrDirect(
        handle: Long,
        refRes: Long,
        patches: ByteBuffer,
        out: ByteBuffer,
        dummy: ByteBuffer,
        spirv: ByteArray,
        refW: Int,
        refH: Int,
        tileSize: Int,
        radius: Int,
        nTx: Int,
        tileOffset: Int,
        chunkTiles: Int,
        flip: Int,
    ): Int

    /**
     * FFT grey via mixed-radix Stockham passes (grey_fft.comp) from resident
     * `inRes` into `out` (`width*height` floats). Returns 3 (kInitFailed)
     * when an axis has a prime factor > 32: use [nativeGreyFftRes] instead.
     */
    @JvmStatic
    external fun nativeGreyFftMixedRes(
        handle: Long,
        inRes: Long,
        out: ByteBuffer,
        spirv: ByteArray,
        width: Int,
        height: Int,
    ): Int

    /**
     * PhotonCamera KernelNet (kernelnet.comp) on the FULL reference frame
     * `rawRes` (`rawW*rawH` normalized floats) -> Wronski covariances for
     * the crop at (`cropX`, `cropY`) into `covRes` (`covW*covH*4` floats).
     * `weightsRes` holds [KernelNet.weights] (`weightCount` floats);
     * `mapRes` receives the (s1, s2, rho) map, `3*mapW*mapH` floats with
     * map = ((rawW/2 - 1)/2 + 1) x ((rawH/2 - 1)/2 + 1).
     */
    @JvmStatic
    external fun nativeKernelNetCovRes(
        handle: Long,
        rawRes: Long,
        weightsRes: Long,
        weightCount: Int,
        spirv: ByteArray,
        rawW: Int,
        rawH: Int,
        sigma: Float,
        sigmaScale: Float,
        sigmaCap: Float,
        cropX: Int,
        cropY: Int,
        covW: Int,
        covH: Int,
        covRes: Long,
        mapRes: Long,
        flipX: Int,
        flipY: Int,
    ): Int

    /**
     * compute_s (op 6) writing a resident spread map. `flowBuffer`
     * stages `ntx*nty*2` floats; `sRes` receives `ntx*nty` floats and
     * stays on-device for the robust leg. Bit-identical to nativeSmap.
     */
    @JvmStatic
    external fun nativeSmapRes(
        handle: Long,
        flowBuffer: ByteBuffer,
        sRes: Long,
        spirv: ByteArray,
        ntx: Int,
        nty: Int,
        mtBits: Int,
        s1Bits: Int,
        s2Bits: Int,
    ): Int

    /**
     * compute_robustness with a resident frame and resident s map.
     * `compRes` holds `width*height` raw floats; `meansRes`/`varsRes`
     * hold `3*(width/2)*(height/2)` each; `sRes` holds `ftx*fty`;
     * flow/lut stage as direct buffers; `outBuffer` receives the map.
     * Bit-identical to nativeRobustCompute.
     */
    @JvmStatic
    external fun nativeRobustComputeFrameRes(
        handle: Long,
        compRes: Long,
        meansRes: Long,
        varsRes: Long,
        flowBuffer: ByteBuffer,
        lutBuffer: ByteBuffer,
        sRes: Long,
        outBuffer: ByteBuffer,
        spirv: ByteArray,
        width: Int,
        height: Int,
        tileSize: Int,
        lutN: Int,
        noiseCorrection: Int,
        tBits: Int,
        ftx: Int,
        fty: Int,
        /** S.3 map resident ([nativeRobustHfRes]); 0 = S.3 off. */
        hfRes: Long,
        /** Float bits of s1 (the M > Mt strength the S map holds there). */
        s1Bits: Int,
    ): Int

    /**
     * Supplement S.3 variance-loss map (robustness op 9) from the resident
     * reference stats `meansRes`/`varsRes` (3 planes of (width/2)*(height/2))
     * into `hfRes` (0/1 per guide pixel). `alpha`/`beta` are the R, G, B
     * noise-model coefficients (normalized units).
     */
    @JvmStatic
    external fun nativeRobustHfRes(
        handle: Long,
        meansRes: Long,
        varsRes: Long,
        hfRes: Long,
        spirv: ByteArray,
        width: Int,
        height: Int,
        rho: Float,
        k: Float,
        alpha: FloatArray,
        beta: FloatArray,
    ): Int

    /**
     * Merge splat (merge_splat.comp) with a resident frame and resident
     * covs. `inRes` holds `lrW*lrH` raw floats; `covRes` holds
     * `covCount` merge-ready vec4s; flow/r stage; `numRes`/`denRes`
     * accumulate `hrW*hrH*3` each in place. Bit-identical to
     * nativeMergeSplatRes.
     */
    @JvmStatic
    external fun nativeMergeSplatFrameRes(
        handle: Long,
        inRes: Long,
        flowBuffer: ByteBuffer,
        covRes: Long,
        rBuffer: ByteBuffer,
        numRes: Long,
        denRes: Long,
        spirv: ByteArray,
        lrW: Int,
        lrH: Int,
        hrW: Int,
        hrH: Int,
        covCount: Int,
        rCount: Int,
        tileSize: Int,
        scale: Float,
        mode: Int,
        ftx: Int,
        fty: Int,
    ): Int

    /**
     * Merge splat (merge_splat.comp, Alg.4) over direct buffers. Returns
     * the `srp::Status` ordinal (0 = ok). `compBuffer` holds `lrW*lrH`
     * raw floats; `flowBuffer` holds `ftx*fty*2` pairs; `covBuffer` holds
     * `covCount` merge-ready vec4 floats; `rBuffer` holds `rCount`
     * robustness floats; `numBuffer`/`denBuffer` hold `hrW*hrH*3` floats
     * each and accumulate in place. `spirv` holds the compiled
     * merge_splat.comp module. All buffers direct, native-order.
     */
    /**
     * DNG metadata through TinyDNG v3 image 0. Returns null on failure,
     * else an int[12]: width,height,black[4],white,cfa[4],noiseCount.
     */
    @JvmStatic
    external fun nativeDngMeta(path: String): IntArray?

    /**
     * DNG NoiseProfile (S,O) pairs as double[8] (zeros when absent).
     * Returns null on failure.
     */
    @JvmStatic
    external fun nativeDngNoise(path: String): DoubleArray?

    /**
     * Camera color metadata: `[hasMatrix, m0..m8, hasNeutral, n0..n2,
     * illuminant]` (15 doubles). Returns null on failure.
     */
    @JvmStatic
    external fun nativeDngColorMeta(path: String): DoubleArray?

    /** `[make, model]` (empty strings when absent). Null on failure. */
    @JvmStatic
    external fun nativeDngMakeModel(path: String): Array<String>?

    /**
     * EXIF orientation of image 0 (1..8), or 0 when unreadable/absent.
     * Advisory display metadata; never fails a run.
     */
    @JvmStatic
    external fun nativeDngOrientation(path: String): Int

    /**
     * Decodes image 0 into `pixelsBuffer` (direct, native-order). Full
     * frame when `regW`/`regH` are 0 (needs `2*width*height` bytes),
     * else the even-aligned `(regX,regY,regW,regH)` region (needs
     * `2*regW*regH` bytes). Returns the `srp::DngReadStatus` ordinal
     * (0 = ok).
     */
    @JvmStatic
    external fun nativeDngPixels(
        path: String,
        pixelsBuffer: ByteBuffer,
        width: Int,
        height: Int,
        regX: Int,
        regY: Int,
        regW: Int,
        regH: Int,
    ): Int

    /**
     * Single-pass decode + flip + normalise into a direct float buffer
     * (`4*w*h` bytes, row-major). Same bytes as [decodeFull] +
     * [toUnsigned] + [flipToRggb] + [normalize]. Returns the
     * `srp::DngReadStatus` ordinal (0 = ok).
     */
    @JvmStatic
    external fun nativeDngNormalized(
        path: String,
        outBuffer: ByteBuffer,
        width: Int,
        height: Int,
        black: IntArray,
        white: Int,
        cfa: IntArray,
    ): Int

    @JvmStatic
    external fun nativeMergeSplat(
        handle: Long,
        compBuffer: ByteBuffer,
        flowBuffer: ByteBuffer,
        covBuffer: ByteBuffer,
        rBuffer: ByteBuffer,
        numBuffer: ByteBuffer,
        denBuffer: ByteBuffer,
        spirv: ByteArray,
        lrW: Int,
        lrH: Int,
        hrW: Int,
        hrH: Int,
        covCount: Int,
        rCount: Int,
        tileSize: Int,
        scale: Float,
        mode: Int,
        ftx: Int,
        fty: Int,
    ): Int

    /**
     * Writes row-major chunky float32 RGB in [0,1] as an uncompressed
     * 16-bit linear-RGB DNG via TinyDNG v3. Returns the `srp::DngStatus`
     * ordinal (0 = ok). `rgb` must be direct, native-order, position 0,
     * with at least `width*height*3` floats remaining.
     */
    @JvmStatic
    external fun nativeWriteDng(
        path: String,
        width: Int,
        height: Int,
        rgb: ByteBuffer,
        make: String?,
        model: String?,
    ): Int

    /**
     * Full-profile write: `matrix` (9, nullable), `neutral` (3, nullable)
     * and `illuminant` select the output ColorMatrix1/AsShotNeutral/
     * CalibrationIlluminant1 (nulls select the writer defaults).
     * `orientation` (0 = omit, else 1..8) writes EXIF Orientation so raw
     * editors display the file like the finished PNG. Returns
     * the `srp::DngStatus` ordinal (0 = ok).
     */
    @JvmStatic
    external fun nativeWriteDngColor(
        path: String,
        width: Int,
        height: Int,
        rgb: ByteBuffer,
        make: String?,
        model: String?,
        matrix: DoubleArray?,
        neutral: DoubleArray?,
        illuminant: Int,
        orientation: Int,
    ): Int

    /** Direct float buffer helper: native order, position 0. */
    @JvmStatic
    fun directFloats(count: Int): ByteBuffer =
        ByteBuffer.allocateDirect(count * java.lang.Float.BYTES)
            .order(ByteOrder.nativeOrder())

    /**
     * malloc-backed direct buffer (zeroed), outside the Java heap; null on
     * allocation failure. The caller must [nativeFreeDirect] it exactly
     * once and never touch it afterwards.
     */
    @JvmStatic
    external fun nativeAllocDirect(bytes: Long): ByteBuffer?

    @JvmStatic
    external fun nativeFreeDirect(buffer: ByteBuffer)

    /** Native-order float view over [nativeAllocDirect]; throws OOM on failure. */
    @JvmStatic
    fun nativeFloats(count: Int): ByteBuffer =
        (nativeAllocDirect(count.toLong() * java.lang.Float.BYTES)
            ?: throw OutOfMemoryError("native direct buffer of $count floats"))
            .order(ByteOrder.nativeOrder())

    /**
     * Scoped set of [nativeFloats] buffers freed together by [close]; every
     * buffer must be dead (not referenced by running code) at close.
     */
    class NativeFloats : AutoCloseable {
        private val owned = ArrayList<ByteBuffer>()
        fun floats(count: Int): ByteBuffer = nativeFloats(count).also { owned += it }
        override fun close() {
            for (b in owned) nativeFreeDirect(b)
            owned.clear()
        }
    }

    /**
     * Validated wrapper for [nativeMergeAccumulate]: throws
     * IllegalArgumentException before crossing JNI on bad arguments.
     */
    @JvmStatic
    fun mergeAccumulate(
        handle: Long,
        inBuffer: ByteBuffer,
        accBuffer: ByteBuffer,
        spirv: ByteArray,
        count: Int,
        width: Int,
        height: Int,
        gain: Float,
    ): Int {
        require(handle != 0L) { "SrpBridge: null context handle" }
        require(count > 0) { "SrpBridge: count must be positive" }
        checkDirect(inBuffer, count, "inBuffer")
        checkDirect(accBuffer, count, "accBuffer")
        require(spirv.isNotEmpty() && spirv.size % 4 == 0) {
            "SrpBridge: spirv must be a non-empty multiple of 4 bytes"
        }
        return nativeMergeAccumulate(
            handle, inBuffer, accBuffer, spirv, count, width, height, gain)
    }

    /**
     * Validated wrapper for [nativeGat]: throws IllegalArgumentException
     * before crossing JNI on bad arguments.
     */
    @JvmStatic
    fun gat(
        handle: Long,
        inBuffer: ByteBuffer,
        outBuffer: ByteBuffer,
        abBuffer: ByteBuffer,
        spirv: ByteArray,
        width: Int,
        height: Int,
        count: Int,
        op: Int,
    ): Int {
        require(handle != 0L) { "SrpBridge: null context handle" }
        require(width > 0 && height > 0 && count > 0) {
            "SrpBridge: bad dimensions"
        }
        require(op == 0 || op == 1) { "SrpBridge: op must be 0 (GAT) or 1 (decimate)" }
        if (op == 0) require(count == width * height) {
            "SrpBridge: GAT count must equal width*height"
        } else {
            require(width % 2 == 0 && height % 2 == 0) {
                "SrpBridge: decimate needs even dimensions"
            }
            require(count == (width / 2) * (height / 2)) {
                "SrpBridge: decimate count must equal width/2*height/2"
            }
        }
        checkDirect(inBuffer, width * height, "inBuffer")
        checkDirect(outBuffer, count, "outBuffer")
        checkDirect(abBuffer, 8, "abBuffer")
        require(spirv.isNotEmpty() && spirv.size % 4 == 0) {
            "SrpBridge: spirv must be a non-empty multiple of 4 bytes"
        }
        return nativeGat(handle, inBuffer, outBuffer, abBuffer, spirv,
            width, height, count, op)
    }

    /**
     * Validated wrapper for [nativeDemosaicMenon]: throws
     * IllegalArgumentException before crossing JNI on bad arguments.
     */
    @JvmStatic
    fun demosaicMenon(
        handle: Long,
        cfaBuffer: ByteBuffer,
        greyBuffer: ByteBuffer,
        spirv: ByteArray,
        width: Int,
        height: Int,
    ): Int {
        require(handle != 0L) { "SrpBridge: null context handle" }
        require(width > 0 && height > 0) {
            "SrpBridge: bad dimensions"
        }
        checkDirect(cfaBuffer, width * height, "cfaBuffer")
        checkDirect(greyBuffer, width * height, "greyBuffer")
        require(spirv.isNotEmpty() && spirv.size % 4 == 0) {
            "SrpBridge: spirv must be a non-empty multiple of 4 bytes"
        }
        return nativeDemosaicMenon(handle, cfaBuffer, greyBuffer, spirv,
            width, height)
    }

    /**
     * Validated wrapper for [nativeGrad]: throws IllegalArgumentException
     * before crossing JNI on bad arguments.
     */
    @JvmStatic
    fun grad(
        handle: Long,
        inBuffer: ByteBuffer,
        outBuffer: ByteBuffer,
        spirv: ByteArray,
        width: Int,
        height: Int,
        count: Int,
        op: Int,
        expBits: Int = 0,
    ): Int {
        require(handle != 0L) { "SrpBridge: null context handle" }
        require(width > 0 && height > 0 && count > 0) {
            "SrpBridge: bad dimensions"
        }
        require(op in 0..3) { "SrpBridge: op must be 0-3 (kerngrad/sobel/sqrt/gamma)" }
        if (op == 0) {
            require(width >= 2 && height >= 2) {
                "SrpBridge: kerngrad needs width/height >= 2"
            }
            require(count == (width - 1) * (height - 1)) {
                "SrpBridge: kerngrad count must equal (width-1)*(height-1)"
            }
        } else {
            require(count == width * height) {
                "SrpBridge: count must equal width*height"
            }
        }
        checkDirect(inBuffer, width * height, "inBuffer")
        checkDirect(outBuffer, if (op <= 1) count * 2 else count, "outBuffer")
        require(spirv.isNotEmpty() && spirv.size % 4 == 0) {
            "SrpBridge: spirv must be a non-empty multiple of 4 bytes"
        }
        return nativeGrad(handle, inBuffer, outBuffer, spirv,
            width, height, count, op, expBits)
    }

    /**
     * Validated wrapper for [nativeKernelsCov]: throws
     * IllegalArgumentException before crossing JNI on bad arguments.
     */
    @JvmStatic
    fun kernelsCov(
        handle: Long,
        gradsBuffer: ByteBuffer,
        kparamsBuffer: ByteBuffer,
        covBuffer: ByteBuffer,
        spirv: ByteArray,
        gradW: Int,
        gradH: Int,
        covW: Int,
        covH: Int,
    ): Int {
        require(handle != 0L) { "SrpBridge: null context handle" }
        require(gradW > 0 && gradH > 0 && covW > 0 && covH > 0) {
            "SrpBridge: bad dimensions"
        }
        checkDirect(gradsBuffer, gradW * gradH * 2, "gradsBuffer")
        checkDirect(kparamsBuffer, 8, "kparamsBuffer")
        checkDirect(covBuffer, covW * covH * 4, "covBuffer")
        require(spirv.isNotEmpty() && spirv.size % 4 == 0) {
            "SrpBridge: spirv must be a non-empty multiple of 4 bytes"
        }
        return nativeKernelsCov(handle, gradsBuffer, kparamsBuffer, covBuffer,
            spirv, gradW, gradH, covW, covH)
    }

    /**
     * Validated wrapper for [nativeDivide]: throws IllegalArgumentException
     * before crossing JNI on bad arguments.
     */
    @JvmStatic
    fun divide(
        handle: Long,
        numBuffer: ByteBuffer,
        denBuffer: ByteBuffer,
        outBuffer: ByteBuffer,
        spirv: ByteArray,
        count: Int,
    ): Int {
        require(handle != 0L) { "SrpBridge: null context handle" }
        require(count > 0) { "SrpBridge: count must be positive" }
        checkDirect(numBuffer, count, "numBuffer")
        checkDirect(denBuffer, count, "denBuffer")
        checkDirect(outBuffer, count, "outBuffer")
        require(spirv.isNotEmpty() && spirv.size % 4 == 0) {
            "SrpBridge: spirv must be a non-empty multiple of 4 bytes"
        }
        return nativeDivide(handle, numBuffer, denBuffer, outBuffer,
            spirv, count)
    }

    /**
     * Validated wrapper for [nativeCreateResident]: throws
     * IllegalArgumentException before crossing JNI on bad arguments.
     * Returns 0 on native failure (like a null context handle).
     */
    @JvmStatic
    fun createResident(handle: Long, count: Long): Long {
        require(handle != 0L) { "SrpBridge: null context handle" }
        require(count > 0) { "SrpBridge: count must be positive" }
        return nativeCreateResident(handle, count)
    }

    /**
     * Validated wrapper for [nativeFreeResident]: throws
     * IllegalArgumentException before crossing JNI on bad arguments.
     */
    @JvmStatic
    fun freeResident(handle: Long, resident: Long): Int {
        require(handle != 0L) { "SrpBridge: null context handle" }
        require(resident != 0L) { "SrpBridge: null resident handle" }
        return nativeFreeResident(handle, resident)
    }

    /**
     * Validated wrapper for [nativeDownloadResident]: throws
     * IllegalArgumentException before crossing JNI on bad arguments.
     */
    @JvmStatic
    fun downloadResident(
        handle: Long,
        resident: Long,
        outBuffer: ByteBuffer,
        count: Long,
    ): Int {
        require(handle != 0L) { "SrpBridge: null context handle" }
        require(resident != 0L) { "SrpBridge: null resident handle" }
        require(count > 0) { "SrpBridge: count must be positive" }
        require(count <= Int.MAX_VALUE) { "SrpBridge: count too large" }
        checkDirect(outBuffer, count.toInt(), "outBuffer")
        return nativeDownloadResident(handle, resident, outBuffer, count)
    }

    /**
     * Validated wrapper for [nativeUploadResident]: throws
     * IllegalArgumentException before crossing JNI on bad arguments.
     */
    @JvmStatic
    fun uploadResident(
        handle: Long,
        resident: Long,
        inBuffer: ByteBuffer,
        count: Long,
    ): Int {
        require(handle != 0L) { "SrpBridge: null context handle" }
        require(resident != 0L) { "SrpBridge: null resident handle" }
        require(count > 0) { "SrpBridge: count must be positive" }
        require(count <= Int.MAX_VALUE) { "SrpBridge: count too large" }
        checkDirect(inBuffer, count.toInt(), "inBuffer")
        return nativeUploadResident(handle, resident, inBuffer, count)
    }

    /**
     * Validated wrapper for [nativeMergeSplatRes]: throws
     * IllegalArgumentException before crossing JNI on bad arguments.
     */
    @JvmStatic
    fun mergeSplatRes(
        handle: Long,
        compBuffer: ByteBuffer,
        flowBuffer: ByteBuffer,
        covBuffer: ByteBuffer,
        rBuffer: ByteBuffer,
        numRes: Long,
        denRes: Long,
        spirv: ByteArray,
        lrW: Int,
        lrH: Int,
        hrW: Int,
        hrH: Int,
        tileSize: Int,
        scale: Float,
        bayer: Boolean,
        ftx: Int,
        fty: Int,
    ): Int {
        require(handle != 0L) { "SrpBridge: null context handle" }
        require(numRes != 0L) { "SrpBridge: null num resident handle" }
        require(denRes != 0L) { "SrpBridge: null den resident handle" }
        require(lrW > 0 && lrH > 0 && hrW > 0 && hrH > 0 && tileSize > 0 && ftx > 0 && fty > 0) {
            "SrpBridge: bad dimensions"
        }
        require(scale > 0f) { "SrpBridge: scale must be positive" }
        require(ftx * tileSize >= lrW && fty * tileSize >= lrH) {
            "SrpBridge: flow grid must cover the frame"
        }
        val gw = lrW / 2
        val gh = lrH / 2
        val covCount = (if (bayer) gw * gh else lrW * lrH) * 4
        val rCount = if (bayer) gw * gh else lrW * lrH
        checkDirect(compBuffer, lrW * lrH, "compBuffer")
        checkDirect(flowBuffer, ftx * fty * 2, "flowBuffer")
        checkDirect(covBuffer, covCount, "covBuffer")
        checkDirect(rBuffer, rCount, "rBuffer")
        require(spirv.isNotEmpty() && spirv.size % 4 == 0) {
            "SrpBridge: spirv must be a non-empty multiple of 4 bytes"
        }
        return nativeMergeSplatRes(handle, compBuffer, flowBuffer, covBuffer,
            rBuffer, numRes, denRes, spirv, lrW, lrH, hrW, hrH,
            covCount, rCount, tileSize, scale, if (bayer) 0 else 1, ftx, fty)
    }

    /**
     * Validated wrapper for [nativeDivideRes]: throws
     * IllegalArgumentException before crossing JNI on bad arguments.
     */
    @JvmStatic
    fun divideRes(
        handle: Long,
        numRes: Long,
        denRes: Long,
        spirv: ByteArray,
        count: Int,
    ): Int {
        require(handle != 0L) { "SrpBridge: null context handle" }
        require(numRes != 0L) { "SrpBridge: null num resident handle" }
        require(denRes != 0L) { "SrpBridge: null den resident handle" }
        require(count > 0) { "SrpBridge: count must be positive" }
        require(spirv.isNotEmpty() && spirv.size % 4 == 0) {
            "SrpBridge: spirv must be a non-empty multiple of 4 bytes"
        }
        return nativeDivideRes(handle, numRes, denRes, spirv, count)
    }

    /**
     * Validated wrapper for [nativeRobustInit]: throws
     * IllegalArgumentException before crossing JNI on bad arguments.
     */
    @JvmStatic
    fun robustInit(
        handle: Long,
        refBuffer: ByteBuffer,
        meansBuffer: ByteBuffer,
        varsBuffer: ByteBuffer,
        spirv: ByteArray,
        width: Int,
        height: Int,
    ): Int {
        require(handle != 0L) { "SrpBridge: null context handle" }
        require(width >= 2 && height >= 2) {
            "SrpBridge: bad dimensions"
        }
        val gw = width / 2
        val gh = height / 2
        checkDirect(refBuffer, width * height, "refBuffer")
        checkDirect(meansBuffer, 3 * gw * gh, "meansBuffer")
        checkDirect(varsBuffer, 3 * gw * gh, "varsBuffer")
        require(spirv.isNotEmpty() && spirv.size % 4 == 0) {
            "SrpBridge: spirv must be a non-empty multiple of 4 bytes"
        }
        return nativeRobustInit(handle, refBuffer, meansBuffer, varsBuffer,
            spirv, width, height)
    }

    /**
     * Validated wrapper for [nativeRobustCompute]: throws
     * IllegalArgumentException before crossing JNI on bad arguments.
     */
    @JvmStatic
    fun robustCompute(
        handle: Long,
        compBuffer: ByteBuffer,
        meansBuffer: ByteBuffer,
        varsBuffer: ByteBuffer,
        flowBuffer: ByteBuffer,
        lutBuffer: ByteBuffer,
        sBuffer: ByteBuffer,
        outBuffer: ByteBuffer,
        spirv: ByteArray,
        width: Int,
        height: Int,
        tileSize: Int,
        lutN: Int,
        noiseCorrection: Boolean,
        t: Float,
        ftx: Int,
        fty: Int,
    ): Int {
        require(handle != 0L) { "SrpBridge: null context handle" }
        require(width >= 2 && height >= 2 && tileSize >= 2 && lutN > 0 && ftx > 0 && fty > 0) {
            "SrpBridge: bad dimensions"
        }
        val gw = width / 2
        val gh = height / 2
        checkDirect(compBuffer, width * height, "compBuffer")
        checkDirect(meansBuffer, 3 * gw * gh, "meansBuffer")
        checkDirect(varsBuffer, 3 * gw * gh, "varsBuffer")
        checkDirect(flowBuffer, ftx * fty * 2, "flowBuffer")
        checkDirect(lutBuffer, 2 * lutN, "lutBuffer")
        checkDirect(sBuffer, ftx * fty, "sBuffer")
        checkDirect(outBuffer, gw * gh, "outBuffer")
        require(spirv.isNotEmpty() && spirv.size % 4 == 0) {
            "SrpBridge: spirv must be a non-empty multiple of 4 bytes"
        }
        return nativeRobustCompute(handle, compBuffer, meansBuffer,
            varsBuffer, flowBuffer, lutBuffer, sBuffer, outBuffer, spirv,
            width, height, tileSize, lutN, if (noiseCorrection) 1 else 0,
            java.lang.Float.floatToIntBits(t), ftx, fty)
    }

    /**
     * Validated wrapper for [nativeRobustInitRes]: throws
     * IllegalArgumentException before crossing JNI on bad arguments.
     * Drops the two 3-channel host stats buffers of [robustInit] (75 MB
     * at full-res): the stats live on-device for the whole run.
     */
    @JvmStatic
    fun robustInitRes(
        handle: Long,
        refBuffer: ByteBuffer,
        meansRes: Long,
        varsRes: Long,
        spirv: ByteArray,
        width: Int,
        height: Int,
    ): Int {
        require(handle != 0L) { "SrpBridge: null context handle" }
        require(meansRes != 0L) { "SrpBridge: null means resident handle" }
        require(varsRes != 0L) { "SrpBridge: null vars resident handle" }
        require(width >= 2 && height >= 2) {
            "SrpBridge: bad dimensions"
        }
        checkDirect(refBuffer, width * height, "refBuffer")
        require(spirv.isNotEmpty() && spirv.size % 4 == 0) {
            "SrpBridge: spirv must be a non-empty multiple of 4 bytes"
        }
        return nativeRobustInitRes(handle, refBuffer, meansRes, varsRes,
            spirv, width, height)
    }

    /**
     * Validated wrapper for [nativeRobustComputeRes]: throws
     * IllegalArgumentException before crossing JNI on bad arguments.
     * Reads the reference stats from the [robustInitRes] residents
     * instead of host buffers (no per-frame 75 MB re-upload).
     */
    @JvmStatic
    fun robustComputeRes(
        handle: Long,
        compBuffer: ByteBuffer,
        meansRes: Long,
        varsRes: Long,
        flowBuffer: ByteBuffer,
        lutBuffer: ByteBuffer,
        sBuffer: ByteBuffer,
        outBuffer: ByteBuffer,
        spirv: ByteArray,
        width: Int,
        height: Int,
        tileSize: Int,
        lutN: Int,
        noiseCorrection: Boolean,
        t: Float,
        ftx: Int,
        fty: Int,
    ): Int {
        require(handle != 0L) { "SrpBridge: null context handle" }
        require(meansRes != 0L) { "SrpBridge: null means resident handle" }
        require(varsRes != 0L) { "SrpBridge: null vars resident handle" }
        require(width >= 2 && height >= 2 && tileSize >= 2 && lutN > 0 && ftx > 0 && fty > 0) {
            "SrpBridge: bad dimensions"
        }
        val gw = width / 2
        val gh = height / 2
        checkDirect(compBuffer, width * height, "compBuffer")
        checkDirect(flowBuffer, ftx * fty * 2, "flowBuffer")
        checkDirect(lutBuffer, 2 * lutN, "lutBuffer")
        checkDirect(sBuffer, ftx * fty, "sBuffer")
        checkDirect(outBuffer, gw * gh, "outBuffer")
        require(spirv.isNotEmpty() && spirv.size % 4 == 0) {
            "SrpBridge: spirv must be a non-empty multiple of 4 bytes"
        }
        return nativeRobustComputeRes(handle, compBuffer, meansRes, varsRes,
            flowBuffer, lutBuffer, sBuffer, outBuffer, spirv,
            width, height, tileSize, lutN, if (noiseCorrection) 1 else 0,
            java.lang.Float.floatToIntBits(t), ftx, fty)
    }

    /**
     * Validated wrapper for [nativeSmap]: throws IllegalArgumentException
     * before crossing JNI on bad arguments.
     */
    @JvmStatic
    fun smap(
        handle: Long,
        flowBuffer: ByteBuffer,
        sBuffer: ByteBuffer,
        spirv: ByteArray,
        ntx: Int,
        nty: Int,
        mt: Float = 0.8f,
        s1: Float = 2f,
        s2: Float = 12f,
    ): Int {
        require(handle != 0L) { "SrpBridge: null context handle" }
        require(ntx > 0 && nty > 0) {
            "SrpBridge: bad dimensions"
        }
        checkDirect(flowBuffer, ntx * nty * 2, "flowBuffer")
        checkDirect(sBuffer, ntx * nty, "sBuffer")
        require(spirv.isNotEmpty() && spirv.size % 4 == 0) {
            "SrpBridge: spirv must be a non-empty multiple of 4 bytes"
        }
        return nativeSmap(handle, flowBuffer, sBuffer, spirv, ntx, nty,
            java.lang.Float.floatToIntBits(mt),
            java.lang.Float.floatToIntBits(s1),
            java.lang.Float.floatToIntBits(s2))
    }

    /**
     * Validated wrapper for [nativeMergeSplat]: throws
     * IllegalArgumentException before crossing JNI on bad arguments.
     */
    @JvmStatic
    fun mergeSplat(
        handle: Long,
        compBuffer: ByteBuffer,
        flowBuffer: ByteBuffer,
        covBuffer: ByteBuffer,
        rBuffer: ByteBuffer,
        numBuffer: ByteBuffer,
        denBuffer: ByteBuffer,
        spirv: ByteArray,
        lrW: Int,
        lrH: Int,
        hrW: Int,
        hrH: Int,
        tileSize: Int,
        scale: Float,
        bayer: Boolean,
        ftx: Int,
        fty: Int,
    ): Int {
        require(handle != 0L) { "SrpBridge: null context handle" }
        require(lrW > 0 && lrH > 0 && hrW > 0 && hrH > 0 && tileSize > 0 && ftx > 0 && fty > 0) {
            "SrpBridge: bad dimensions"
        }
        require(scale > 0f) { "SrpBridge: scale must be positive" }
        require(ftx * tileSize >= lrW && fty * tileSize >= lrH) {
            "SrpBridge: flow grid must cover the frame"
        }
        val gw = lrW / 2
        val gh = lrH / 2
        val covCount = (if (bayer) gw * gh else lrW * lrH) * 4
        val rCount = if (bayer) gw * gh else lrW * lrH
        checkDirect(compBuffer, lrW * lrH, "compBuffer")
        checkDirect(flowBuffer, ftx * fty * 2, "flowBuffer")
        checkDirect(covBuffer, covCount, "covBuffer")
        checkDirect(rBuffer, rCount, "rBuffer")
        checkDirect(numBuffer, hrW * hrH * 3, "numBuffer")
        checkDirect(denBuffer, hrW * hrH * 3, "denBuffer")
        require(spirv.isNotEmpty() && spirv.size % 4 == 0) {
            "SrpBridge: spirv must be a non-empty multiple of 4 bytes"
        }
        return nativeMergeSplat(handle, compBuffer, flowBuffer, covBuffer,
            rBuffer, numBuffer, denBuffer, spirv, lrW, lrH, hrW, hrH,
            covCount, rCount, tileSize, scale, if (bayer) 0 else 1, ftx, fty)
    }

    /**
     * Per-level L1 refinement over direct buffers. Returns the `srp::Status`
     * ordinal (0 = ok). `refBuffer`/`movBuffer` hold `width*height` level
     * floats each; `flowBuffer` holds `tileCount*2` floats ((nty,ntx,2)
     * flattened, staged with the current flow and read back refined).
     * `spirv` holds the compiled align.comp module. All buffers direct,
     * native-order, position 0.
     */
    @JvmStatic
    external fun nativeAlignL1(
        handle: Long,
        refBuffer: ByteBuffer,
        movBuffer: ByteBuffer,
        flowBuffer: ByteBuffer,
        spirv: ByteArray,
        width: Int,
        height: Int,
        tileCount: Int,
        tileSize: Int,
        searchRadius: Int,
        ntx: Int,
    ): Int

    /**
     * Validated wrapper for [nativeAlignL1]: throws IllegalArgumentException
     * before crossing JNI on bad arguments.
     */
    @JvmStatic
    fun alignL1(
        handle: Long,
        refBuffer: ByteBuffer,
        movBuffer: ByteBuffer,
        flowBuffer: ByteBuffer,
        spirv: ByteArray,
        width: Int,
        height: Int,
        tileCount: Int,
        tileSize: Int,
        searchRadius: Int,
        ntx: Int,
    ): Int {
        require(handle != 0L) { "SrpBridge: null context handle" }
        require(width > 0 && height > 0 && tileCount > 0 && tileSize > 0 && ntx > 0) {
            "SrpBridge: bad dimensions"
        }
        require(tileCount % ntx == 0) { "SrpBridge: tileCount must be a multiple of ntx" }
        checkDirect(refBuffer, width * height, "refBuffer")
        checkDirect(movBuffer, width * height, "movBuffer")
        checkDirect(flowBuffer, tileCount * 2, "flowBuffer")
        require(spirv.isNotEmpty() && spirv.size % 4 == 0) {
            "SrpBridge: spirv must be a non-empty multiple of 4 bytes"
        }
        return nativeAlignL1(handle, refBuffer, movBuffer, flowBuffer, spirv,
            width, height, tileCount, tileSize, searchRadius, ntx)
    }

    /**
     * Per-level L1 refinement with a device-resident reference image.
     * Returns the `srp::Status` ordinal (0 = ok). `refRes` is a resident
     * of `width*height` floats; `movBuffer`/`flowBuffer` are staged
     * (per-frame data), flow read back refined like the staged path.
     * `spirv` holds the compiled align.comp module. All buffers direct,
     * native-order, position 0.
     */
    @JvmStatic
    external fun nativeAlignL1Res(
        handle: Long,
        refRes: Long,
        movBuffer: ByteBuffer,
        flowBuffer: ByteBuffer,
        spirv: ByteArray,
        width: Int,
        height: Int,
        tileCount: Int,
        tileSize: Int,
        searchRadius: Int,
        ntx: Int,
    ): Int

    /**
     * Validated wrapper for [nativeAlignL1Res]: throws
     * IllegalArgumentException before crossing JNI on bad arguments.
     */
    @JvmStatic
    fun alignL1Res(
        handle: Long,
        refRes: Long,
        movBuffer: ByteBuffer,
        flowBuffer: ByteBuffer,
        spirv: ByteArray,
        width: Int,
        height: Int,
        tileCount: Int,
        tileSize: Int,
        searchRadius: Int,
        ntx: Int,
    ): Int {
        require(handle != 0L) { "SrpBridge: null context handle" }
        require(refRes != 0L) { "SrpBridge: null resident handle" }
        require(width > 0 && height > 0 && tileCount > 0 && tileSize > 0 && ntx > 0) {
            "SrpBridge: bad dimensions"
        }
        require(tileCount % ntx == 0) { "SrpBridge: tileCount must be a multiple of ntx" }
        checkDirect(movBuffer, width * height, "movBuffer")
        checkDirect(flowBuffer, tileCount * 2, "flowBuffer")
        require(spirv.isNotEmpty() && spirv.size % 4 == 0) {
            "SrpBridge: spirv must be a non-empty multiple of 4 bytes"
        }
        return nativeAlignL1Res(handle, refRes, movBuffer, flowBuffer, spirv,
            width, height, tileCount, tileSize, searchRadius, ntx)
    }

    /**
     * Align-L2 patch extraction (corr.comp op 0) over direct buffers.
     * Returns the `srp::Status` ordinal (0 = ok). `movBuffer` holds
     * `height*width` floats, `flowBuffer` holds `tileCount*2` floats,
     * `patchBuffer` receives `chunkTiles*S*S` floats (S = tileSize+2*radius):
     * the `[tileOffset, tileOffset+chunkTiles)` window of the full
     * `tileCount` grid. `spirv` holds the compiled corr.comp module.
     * All buffers direct, native-order, position 0.
     */
    @JvmStatic
    external fun nativeCorrExtract(
        handle: Long,
        movBuffer: ByteBuffer,
        flowBuffer: ByteBuffer,
        patchBuffer: ByteBuffer,
        spirv: ByteArray,
        height: Int,
        width: Int,
        tileCount: Int,
        tileSize: Int,
        radius: Int,
        ntx: Int,
        tileOffset: Int,
        chunkTiles: Int,
    ): Int

    /**
     * Align-L2 box-energy argmin (corr.comp op 1) over direct buffers.
     * Returns the `srp::Status` ordinal (0 = ok). `patchBuffer` holds
     * `patchCount` floats, `corrBuffer` holds `corrCount` floats,
     * `dflowBuffer` receives `chunkTiles*2` winning integer shifts, same
     * window as [nativeCorrExtract] (`tileCount` stays the full grid).
     */
    @JvmStatic
    external fun nativeCorrArgmin(
        handle: Long,
        patchBuffer: ByteBuffer,
        corrBuffer: ByteBuffer,
        dflowBuffer: ByteBuffer,
        spirv: ByteArray,
        tileCount: Int,
        tileSize: Int,
        radius: Int,
        ntx: Int,
        patchCount: Int,
        corrCount: Int,
        tileOffset: Int,
        chunkTiles: Int,
    ): Int

    /**
     * Align-L2 centre crop of full corr maps (corr.comp op 2) over direct
     * buffers. Returns the `srp::Status` ordinal (0 = ok). `fullBuffer`
     * holds `tileCount*S*S` floats, `cropBuffer` receives `tileCount*C*C`
     * floats (C = 2*radius+1). `dummyBuffer` is any direct buffer holding
     * at least 1 float (contents ignored; op 2 never reads binding 1).
     */
    @JvmStatic
    external fun nativeCorrCrop(
        handle: Long,
        fullBuffer: ByteBuffer,
        cropBuffer: ByteBuffer,
        dummyBuffer: ByteBuffer,
        spirv: ByteArray,
        tileCount: Int,
        tileSize: Int,
        radius: Int,
        ntx: Int,
    ): Int

    /**
     * Validated wrapper for [nativeCorrCrop]: throws
     * IllegalArgumentException before crossing JNI on bad arguments.
     */
    @JvmStatic
    fun corrCrop(
        handle: Long,
        fullBuffer: ByteBuffer,
        cropBuffer: ByteBuffer,
        dummyBuffer: ByteBuffer,
        spirv: ByteArray,
        tileCount: Int,
        tileSize: Int,
        radius: Int,
        ntx: Int,
    ): Int {
        require(handle != 0L) { "SrpBridge: null context handle" }
        require(tileCount > 0 && tileSize > 0 && ntx > 0) {
            "SrpBridge: bad dimensions"
        }
        require(tileCount % ntx == 0) { "SrpBridge: tileCount must be a multiple of ntx" }
        val s = tileSize + 2 * radius
        val c = 2 * radius + 1
        checkDirect(fullBuffer, tileCount * s * s, "fullBuffer")
        checkDirect(cropBuffer, tileCount * c * c, "cropBuffer")
        checkDirect(dummyBuffer, 1, "dummyBuffer")
        require(spirv.isNotEmpty() && spirv.size % 4 == 0) {
            "SrpBridge: spirv must be a non-empty multiple of 4 bytes"
        }
        return nativeCorrCrop(handle, fullBuffer, cropBuffer, dummyBuffer,
            spirv, tileCount, tileSize, radius, ntx)
    }

    /**
     * Validated wrapper for [nativeCorrExtract]: throws
     * IllegalArgumentException before crossing JNI on bad arguments.
     * `tileCount` is the FULL grid (whole-row guard); the dispatch
     * covers the `[tileOffset, tileOffset+chunkTiles)` window into a
     * chunk-sized `patchBuffer`.
     */
    @JvmStatic
    fun corrExtract(
        handle: Long,
        movBuffer: ByteBuffer,
        flowBuffer: ByteBuffer,
        patchBuffer: ByteBuffer,
        spirv: ByteArray,
        height: Int,
        width: Int,
        tileCount: Int,
        tileSize: Int,
        radius: Int,
        ntx: Int,
        tileOffset: Int,
        chunkTiles: Int,
    ): Int {
        require(handle != 0L) { "SrpBridge: null context handle" }
        require(height > 0 && width > 0 && tileCount > 0 && tileSize > 0 && ntx > 0) {
            "SrpBridge: bad dimensions"
        }
        require(tileCount % ntx == 0) { "SrpBridge: tileCount must be a multiple of ntx" }
        require(chunkTiles > 0 && tileOffset >= 0 && tileOffset + chunkTiles <= tileCount) {
            "SrpBridge: chunk window out of range"
        }
        val s = tileSize + 2 * radius
        checkDirect(movBuffer, height * width, "movBuffer")
        checkDirect(flowBuffer, tileCount * 2, "flowBuffer")
        checkDirect(patchBuffer, chunkTiles * s * s, "patchBuffer")
        require(spirv.isNotEmpty() && spirv.size % 4 == 0) {
            "SrpBridge: spirv must be a non-empty multiple of 4 bytes"
        }
        return nativeCorrExtract(handle, movBuffer, flowBuffer, patchBuffer,
            spirv, height, width, tileCount, tileSize, radius, ntx, tileOffset, chunkTiles)
    }

    /**
     * Validated wrapper for [nativeCorrArgmin]: throws
     * IllegalArgumentException before crossing JNI on bad arguments.
     * Same window convention as [corrExtract]: `tileCount` is the FULL
     * grid, the dispatch covers `[tileOffset, tileOffset+chunkTiles)`
     * into chunk-sized buffers.
     */
    @JvmStatic
    fun corrArgmin(
        handle: Long,
        patchBuffer: ByteBuffer,
        corrBuffer: ByteBuffer,
        dflowBuffer: ByteBuffer,
        spirv: ByteArray,
        tileCount: Int,
        tileSize: Int,
        radius: Int,
        ntx: Int,
        patchCount: Int,
        corrCount: Int,
        tileOffset: Int,
        chunkTiles: Int,
    ): Int {
        require(handle != 0L) { "SrpBridge: null context handle" }
        require(tileCount > 0 && tileSize > 0 && ntx > 0 && patchCount > 0 && corrCount > 0) {
            "SrpBridge: bad dimensions"
        }
        require(tileCount % ntx == 0) { "SrpBridge: tileCount must be a multiple of ntx" }
        require(chunkTiles > 0 && tileOffset >= 0 && tileOffset + chunkTiles <= tileCount) {
            "SrpBridge: chunk window out of range"
        }
        val s = tileSize + 2 * radius
        val c = 2 * radius + 1
        require(patchCount == chunkTiles * s * s) {
            "SrpBridge: patchCount must equal chunkTiles*S*S"
        }
        require(corrCount == chunkTiles * c * c) {
            "SrpBridge: corrCount must equal chunkTiles*C*C"
        }
        checkDirect(patchBuffer, patchCount, "patchBuffer")
        checkDirect(corrBuffer, corrCount, "corrBuffer")
        checkDirect(dflowBuffer, chunkTiles * 2, "dflowBuffer")
        require(spirv.isNotEmpty() && spirv.size % 4 == 0) {
            "SrpBridge: spirv must be a non-empty multiple of 4 bytes"
        }
        return nativeCorrArgmin(handle, patchBuffer, corrBuffer, dflowBuffer,
            spirv, tileCount, tileSize, radius, ntx, patchCount, corrCount, tileOffset, chunkTiles)
    }

    /**
     * Full circular FFT correlation (fft.comp resident chain) over direct
     * buffers. Returns the `srp::Status` ordinal (0 = ok). `refBuffer` /
     * `movBuffer` hold `tileCount*S*S` floats each ((n,S,S) real);
     * `corrBuffer` receives `tileCount*S*S` fftshifted real floats.
     * `spirv` holds the compiled fft.comp module. All buffers direct,
     * native-order, position 0.
     */
    @JvmStatic
    external fun nativeCorrelate(
        handle: Long,
        refBuffer: ByteBuffer,
        movBuffer: ByteBuffer,
        corrBuffer: ByteBuffer,
        spirv: ByteArray,
        tileCount: Int,
        s: Int,
    ): Int

    /**
     * Validated wrapper for [nativeCorrelate]: throws
     * IllegalArgumentException before crossing JNI on bad arguments.
     */
    @JvmStatic
    fun correlate(
        handle: Long,
        refBuffer: ByteBuffer,
        movBuffer: ByteBuffer,
        corrBuffer: ByteBuffer,
        spirv: ByteArray,
        tileCount: Int,
        s: Int,
    ): Int {
        require(handle != 0L) { "SrpBridge: null context handle" }
        require(tileCount > 0 && s > 0) { "SrpBridge: bad dimensions" }
        val n = tileCount * s * s
        checkDirect(refBuffer, n, "refBuffer")
        checkDirect(movBuffer, n, "movBuffer")
        checkDirect(corrBuffer, n, "corrBuffer")
        require(spirv.isNotEmpty() && spirv.size % 4 == 0) {
            "SrpBridge: spirv must be a non-empty multiple of 4 bytes"
        }
        return nativeCorrelate(handle, refBuffer, movBuffer, corrBuffer,
            spirv, tileCount, s)
    }

    /**
     * Pin align-L2 reference spectra (one forward FFT per level, reused
     * across frames). Returns the `srp::Status` ordinal (0 = ok).
     * `refBuffer` holds `tileCount*S*S` reals; `refspecRes` is a resident
     * of exactly `tileCount*S*S*2` floats receiving the spectra.
     * `spirvFft`/`spirvCorr` hold the fft/corr modules. All buffers
     * direct, native-order, position 0.
     */
    @JvmStatic
    external fun nativeCorrelatePinRef(
        handle: Long,
        refBuffer: ByteBuffer,
        refspecRes: Long,
        spirvFft: ByteArray,
        spirvCorr: ByteArray,
        tileCount: Int,
        s: Int,
    ): Int

    /**
     * Validated wrapper for [nativeCorrelatePinRef]: throws
     * IllegalArgumentException before crossing JNI on bad arguments.
     */
    @JvmStatic
    fun correlatePinRef(
        handle: Long,
        refBuffer: ByteBuffer,
        refspecRes: Long,
        spirvFft: ByteArray,
        spirvCorr: ByteArray,
        tileCount: Int,
        s: Int,
    ): Int {
        require(handle != 0L) { "SrpBridge: null context handle" }
        require(refspecRes != 0L) { "SrpBridge: null resident handle" }
        require(tileCount > 0 && s > 0) { "SrpBridge: bad dimensions" }
        checkDirect(refBuffer, tileCount * s * s, "refBuffer")
        require(spirvFft.isNotEmpty() && spirvFft.size % 4 == 0) {
            "SrpBridge: spirvFft must be a non-empty multiple of 4 bytes"
        }
        require(spirvCorr.isNotEmpty() && spirvCorr.size % 4 == 0) {
            "SrpBridge: spirvCorr must be a non-empty multiple of 4 bytes"
        }
        return nativeCorrelatePinRef(handle, refBuffer, refspecRes,
            spirvFft, spirvCorr, tileCount, s)
    }

    /**
     * Pinned-ref FFT correlation over direct buffers. Returns the
     * `srp::Status` ordinal (0 = ok). `movBuffer` holds `tileCount*S*S`
     * reals, `corrBuffer` receives `tileCount*S*S` fftshifted reals;
     * `refspecRes` is the pinned spectra resident (borrowed). Bit-exact
     * vs [correlate]. All buffers direct, native-order, position 0.
     */
    @JvmStatic
    external fun nativeCorrelatePinned(
        handle: Long,
        movBuffer: ByteBuffer,
        refspecRes: Long,
        corrBuffer: ByteBuffer,
        spirvFft: ByteArray,
        spirvCorr: ByteArray,
        tileCount: Int,
        s: Int,
    ): Int

    /**
     * Validated wrapper for [nativeCorrelatePinned]: throws
     * IllegalArgumentException before crossing JNI on bad arguments.
     */
    @JvmStatic
    fun correlatePinned(
        handle: Long,
        movBuffer: ByteBuffer,
        refspecRes: Long,
        corrBuffer: ByteBuffer,
        spirvFft: ByteArray,
        spirvCorr: ByteArray,
        tileCount: Int,
        s: Int,
    ): Int {
        require(handle != 0L) { "SrpBridge: null context handle" }
        require(refspecRes != 0L) { "SrpBridge: null resident handle" }
        require(tileCount > 0 && s > 0) { "SrpBridge: bad dimensions" }
        val n = tileCount * s * s
        checkDirect(movBuffer, n, "movBuffer")
        checkDirect(corrBuffer, n, "corrBuffer")
        require(spirvFft.isNotEmpty() && spirvFft.size % 4 == 0) {
            "SrpBridge: spirvFft must be a non-empty multiple of 4 bytes"
        }
        require(spirvCorr.isNotEmpty() && spirvCorr.size % 4 == 0) {
            "SrpBridge: spirvCorr must be a non-empty multiple of 4 bytes"
        }
        return nativeCorrelatePinned(handle, movBuffer, refspecRes,
            corrBuffer, spirvFft, spirvCorr, tileCount, s)
    }

    /**
     * FFT lowpass grey (fft.comp resident chain) over direct buffers.
     * Returns the `srp::Status` ordinal (0 = ok). `inBuffer` holds
     * `width*height` raw floats; `outBuffer` receives `width*height` grey
     * floats. `spirv` holds the compiled fft.comp module. Requires
     * width,height >= 4. All buffers direct, native-order, position 0.
     */
    @JvmStatic
    external fun nativeGreyFft(
        handle: Long,
        inBuffer: ByteBuffer,
        outBuffer: ByteBuffer,
        spirv: ByteArray,
        width: Int,
        height: Int,
    ): Int

    /**
     * Validated wrapper for [nativeGreyFft]: throws
     * IllegalArgumentException before crossing JNI on bad arguments.
     */
    @JvmStatic
    fun greyFft(
        handle: Long,
        inBuffer: ByteBuffer,
        outBuffer: ByteBuffer,
        spirv: ByteArray,
        width: Int,
        height: Int,
    ): Int {
        require(handle != 0L) { "SrpBridge: null context handle" }
        require(width >= 4 && height >= 4) { "SrpBridge: grey needs width/height >= 4" }
        checkDirect(inBuffer, width * height, "inBuffer")
        checkDirect(outBuffer, width * height, "outBuffer")
        require(spirv.isNotEmpty() && spirv.size % 4 == 0) {
            "SrpBridge: spirv must be a non-empty multiple of 4 bytes"
        }
        return nativeGreyFft(handle, inBuffer, outBuffer, spirv, width, height)
    }

    /**
     * ICA Hessian build (ica.comp op 0) over direct buffers. Returns the
     * `srp::Status` ordinal (0 = ok). `gradxBuffer`/`gradyBuffer` hold
     * `width*height` floats each; `hessBuffer` receives `tileCount*4`
     * floats ((h00,h01,h11,0) per tile). `spirv` holds the compiled
     * ica.comp module. All buffers direct, native-order, position 0.
     */
    @JvmStatic
    external fun nativeIcaHessian(
        handle: Long,
        gradxBuffer: ByteBuffer,
        gradyBuffer: ByteBuffer,
        hessBuffer: ByteBuffer,
        spirv: ByteArray,
        width: Int,
        height: Int,
        tileCount: Int,
        tileSize: Int,
        ntx: Int,
    ): Int

    /**
     * ICA Gauss-Newton refine (ica.comp op 1) over direct buffers. Returns
     * the `srp::Status` ordinal (0 = ok). `ref/mov/gradx/grady` hold
     * `width*height` floats each; `hessBuffer` holds `tileCount*4` packed
     * floats; `flowBuffer` holds `tileCount*2` floats (staged with the
     * current flow, read back refined). `clipRadius` uploads by bit
     * pattern.
     */
    @JvmStatic
    external fun nativeIcaRefine(
        handle: Long,
        refBuffer: ByteBuffer,
        movBuffer: ByteBuffer,
        gradxBuffer: ByteBuffer,
        gradyBuffer: ByteBuffer,
        hessBuffer: ByteBuffer,
        flowBuffer: ByteBuffer,
        spirv: ByteArray,
        width: Int,
        height: Int,
        tileCount: Int,
        tileSize: Int,
        niter: Int,
        clipRadius: Float,
        ntx: Int,
    ): Int

    /**
     * Validated wrapper for [nativeIcaHessian]: throws
     * IllegalArgumentException before crossing JNI on bad arguments.
     */
    @JvmStatic
    fun icaHessian(
        handle: Long,
        gradxBuffer: ByteBuffer,
        gradyBuffer: ByteBuffer,
        hessBuffer: ByteBuffer,
        spirv: ByteArray,
        width: Int,
        height: Int,
        tileCount: Int,
        tileSize: Int,
        ntx: Int,
    ): Int {
        require(handle != 0L) { "SrpBridge: null context handle" }
        require(width > 0 && height > 0 && tileCount > 0 && tileSize > 0 && ntx > 0) {
            "SrpBridge: bad dimensions"
        }
        require(tileCount % ntx == 0) { "SrpBridge: tileCount must be a multiple of ntx" }
        checkDirect(gradxBuffer, width * height, "gradxBuffer")
        checkDirect(gradyBuffer, width * height, "gradyBuffer")
        checkDirect(hessBuffer, tileCount * 4, "hessBuffer")
        require(spirv.isNotEmpty() && spirv.size % 4 == 0) {
            "SrpBridge: spirv must be a non-empty multiple of 4 bytes"
        }
        return nativeIcaHessian(handle, gradxBuffer, gradyBuffer, hessBuffer,
            spirv, width, height, tileCount, tileSize, ntx)
    }

    /**
     * Validated wrapper for [nativeIcaRefine]: throws
     * IllegalArgumentException before crossing JNI on bad arguments.
     */
    @JvmStatic
    fun icaRefine(
        handle: Long,
        refBuffer: ByteBuffer,
        movBuffer: ByteBuffer,
        gradxBuffer: ByteBuffer,
        gradyBuffer: ByteBuffer,
        hessBuffer: ByteBuffer,
        flowBuffer: ByteBuffer,
        spirv: ByteArray,
        width: Int,
        height: Int,
        tileCount: Int,
        tileSize: Int,
        niter: Int,
        clipRadius: Float,
        ntx: Int,
    ): Int {
        require(handle != 0L) { "SrpBridge: null context handle" }
        require(width > 0 && height > 0 && tileCount > 0 && tileSize > 0 && niter > 0 && ntx > 0) {
            "SrpBridge: bad dimensions"
        }
        require(tileCount % ntx == 0) { "SrpBridge: tileCount must be a multiple of ntx" }
        checkDirect(refBuffer, width * height, "refBuffer")
        checkDirect(movBuffer, width * height, "movBuffer")
        checkDirect(gradxBuffer, width * height, "gradxBuffer")
        checkDirect(gradyBuffer, width * height, "gradyBuffer")
        checkDirect(hessBuffer, tileCount * 4, "hessBuffer")
        checkDirect(flowBuffer, tileCount * 2, "flowBuffer")
        require(spirv.isNotEmpty() && spirv.size % 4 == 0) {
            "SrpBridge: spirv must be a non-empty multiple of 4 bytes"
        }
        return nativeIcaRefine(handle, refBuffer, movBuffer, gradxBuffer,
            gradyBuffer, hessBuffer, flowBuffer, spirv, width, height,
            tileCount, tileSize, niter, clipRadius, ntx)
    }

    /**
     * ICA Gauss-Newton refine with device-resident level statics.
     * Returns the `srp::Status` ordinal (0 = ok). `refRes`/`gradxRes`/
     * `gradyRes` hold `width*height` floats each, `hessRes` holds
     * `tileCount*4` packed floats; `movBuffer`/`flowBuffer` are staged
     * (per-frame data), flow read back refined like the staged path.
     * `spirv` holds the compiled ica.comp module. All buffers direct,
     * native-order, position 0.
     */
    @JvmStatic
    external fun nativeIcaRefineRes(
        handle: Long,
        refRes: Long,
        movBuffer: ByteBuffer,
        gradxRes: Long,
        gradyRes: Long,
        hessRes: Long,
        flowBuffer: ByteBuffer,
        spirv: ByteArray,
        width: Int,
        height: Int,
        tileCount: Int,
        tileSize: Int,
        niter: Int,
        clipRadius: Float,
        ntx: Int,
    ): Int

    /** [nativeIcaRefineRes] with ica_wg.comp (one workgroup per tile). */
    @JvmStatic
    external fun nativeIcaRefineWgRes(
        handle: Long,
        refRes: Long,
        movBuffer: ByteBuffer,
        gradxRes: Long,
        gradyRes: Long,
        hessRes: Long,
        flowBuffer: ByteBuffer,
        spirv: ByteArray,
        width: Int,
        height: Int,
        tileCount: Int,
        tileSize: Int,
        niter: Int,
        clipRadius: Float,
        ntx: Int,
    ): Int

    /**
     * Validated wrapper for [nativeIcaRefineRes]: throws
     * IllegalArgumentException before crossing JNI on bad arguments.
     */
    @JvmStatic
    fun icaRefineRes(
        handle: Long,
        refRes: Long,
        movBuffer: ByteBuffer,
        gradxRes: Long,
        gradyRes: Long,
        hessRes: Long,
        flowBuffer: ByteBuffer,
        spirv: ByteArray,
        width: Int,
        height: Int,
        tileCount: Int,
        tileSize: Int,
        niter: Int,
        clipRadius: Float,
        ntx: Int,
    ): Int {
        require(handle != 0L) { "SrpBridge: null context handle" }
        require(refRes != 0L && gradxRes != 0L && gradyRes != 0L && hessRes != 0L) {
            "SrpBridge: null resident handle"
        }
        require(width > 0 && height > 0 && tileCount > 0 && tileSize > 0 && niter > 0 && ntx > 0) {
            "SrpBridge: bad dimensions"
        }
        require(tileCount % ntx == 0) { "SrpBridge: tileCount must be a multiple of ntx" }
        checkDirect(movBuffer, width * height, "movBuffer")
        checkDirect(flowBuffer, tileCount * 2, "flowBuffer")
        require(spirv.isNotEmpty() && spirv.size % 4 == 0) {
            "SrpBridge: spirv must be a non-empty multiple of 4 bytes"
        }
        return nativeIcaRefineRes(handle, refRes, movBuffer, gradxRes,
            gradyRes, hessRes, flowBuffer, spirv, width, height,
            tileCount, tileSize, niter, clipRadius, ntx)
    }

    /** Validated wrapper for [nativeWriteDng]. */
    @JvmStatic
    fun writeDng(
        path: String,
        width: Int,
        height: Int,
        rgb: ByteBuffer,
        make: String?,
        model: String?,
        colorMatrix: DoubleArray? = null,
        neutral: DoubleArray? = null,
        illuminant: Int = 21,
        orientation: Int = 0,
    ): Int {
        require(path.isNotEmpty()) { "SrpBridge: empty path" }
        require(width > 0 && height > 0) { "SrpBridge: bad dimensions" }
        checkDirect(rgb, width * height * 3, "rgb")
        if (colorMatrix != null) require(colorMatrix.size == 9) {
            "SrpBridge: colorMatrix must have 9 entries"
        }
        if (neutral != null) require(neutral.size == 3) {
            "SrpBridge: neutral must have 3 entries"
        }
        require(orientation in 0..8) { "SrpBridge: orientation must be 0..8" }
        return if (colorMatrix == null && neutral == null && orientation == 0) {
            nativeWriteDng(path, width, height, rgb, make, model)
        } else {
            nativeWriteDngColor(path, width, height, rgb, make, model,
                colorMatrix, neutral, illuminant, orientation)
        }
    }

    /**
     * EXIF orientation of image 0 (1..8), or 0 when the file is
     * unreadable or carries none. Advisory only; never throws.
     */
    @JvmStatic
    fun readDngOrientation(path: String): Int {
        if (path.isEmpty()) return 0
        return try {
            val o = nativeDngOrientation(path)
            if (o in 1..8) o else 0
        } catch (e: Exception) {
            0
        }
    }

    /**
     * Camera color profile for output passthrough: ColorMatrix1,
     * AsShotNeutral, CalibrationIlluminant1, Make/Model. Null entries mean
     * the source tag is absent (writer defaults apply).
     */
    data class DngColorProfile(
        val colorMatrix: DoubleArray?,
        val neutral: DoubleArray?,
        val illuminant: Int,
        val make: String?,
        val model: String?,
    )

    /** Reads the color profile; throws IllegalStateException when unreadable. */
    @JvmStatic
    fun readDngColorProfile(path: String): DngColorProfile {
        val nums = nativeDngColorMeta(path)
            ?: throw IllegalStateException("SrpBridge: cannot read DNG color meta: $path")
        require(nums.size == 15) { "SrpBridge: bad color meta length ${nums.size}" }
        val names = nativeDngMakeModel(path)
            ?: throw IllegalStateException("SrpBridge: cannot read DNG make/model: $path")
        require(names.size == 2) { "SrpBridge: bad make/model length ${names.size}" }
        return DngColorProfile(
            colorMatrix = if (nums[0] != 0.0) nums.copyOfRange(1, 10) else null,
            neutral = if (nums[10] != 0.0) nums.copyOfRange(11, 14) else null,
            illuminant = nums[14].toInt(),
            make = names[0].ifEmpty { null },
            model = names[1].ifEmpty { null },
        )
    }

    private fun checkDirect(buf: ByteBuffer, floats: Int, name: String) {
        require(buf.isDirect) { "SrpBridge: $name must be a direct buffer" }
        require(buf.order() == ByteOrder.nativeOrder()) {
            "SrpBridge: $name must be native-order"
        }
        require(buf.position() == 0) { "SrpBridge: $name position must be 0" }
        require(buf.remaining() >= floats * java.lang.Float.BYTES) {
            "SrpBridge: $name too small for $floats floats"
        }
    }
}
