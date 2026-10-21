package com.rawlens.srpipeline.stages

import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * PhotonCamera KernelNet as the Wronski merge's reconstruction kernel.
 *
 * Model: eszdman/PhotonCamera @ b2eff82 `kernelnet_aniso_v2_2_params`
 * (verbatim in resources/kernelnet, see its README), evaluated in-engine by
 * kernelnet.comp. It replaces Wronski's per-frame structure-tensor kernels
 * (supplement S.1) with ONE (s1, s2, rho) map predicted from the reference
 * frame, as PhotonCamera does; the merge reads it on the reference grid.
 *
 * [sigmaScale] and [sigmaCap] were chosen on two real bursts (bright SNR 29.5,
 * dim SNR 21.8; docs/refactor/mfsr-kernelnet.md): 0.25 / 2.0 px keeps
 * Wronski's measured sharpness (within 1.5 %) with 6-9 % less noise.
 * PhotonCamera's own upscale trims sigmas by 0.9 and caps them at 1.2 px,
 * but its cap is an upscale-output limit that starved the merge's flat
 * regions of denoising.
 */
data class KernelNetConfig(
    /** Packed fp32 weights+biases, see [KernelNet.weights]. */
    val weights: FloatArray,
    /** Noise sigma fed to the net, see [KernelNet.sigma]. */
    val sigma: Float,
    val sigmaScale: Float = KernelNet.DEFAULT_SIGMA_SCALE,
    val sigmaCap: Float = KernelNet.DEFAULT_SIGMA_CAP,
    /**
     * Full reference frame (normalized CFA rows) when the merge runs on a
     * crop, with the crop origin; null = the merge input IS the full frame.
     */
    val fullReference: Array<FloatArray>? = null,
    val cropX: Int = 0,
    val cropY: Int = 0,
    /**
     * Sensor -> pipeline flips of the RGGB phase normalization (see
     * [KernelNet.rggbFlips]); the net runs in sensor orientation.
     */
    val flipX: Boolean = false,
    val flipY: Boolean = false,
)

object KernelNet {
    const val DEFAULT_SIGMA_SCALE = 0.25f
    const val DEFAULT_SIGMA_CAP = 2.0f
    const val RESOURCE_BIN = "/kernelnet/kernelnet_aniso_v2_2_params.ncnn.bin"

    /** (cin, cout, k) per Convolution in .param order. */
    private val LAYERS = listOf(
        Triple(2, 16, 3), Triple(16, 16, 3), Triple(16, 16, 3),
        Triple(16, 16, 3), Triple(16, 16, 3), Triple(16, 3, 1),
    )

    /** ncnn's fp16 weight-blob tag (ModelBinFromDataReader, type 0). */
    private const val TAG_FP16 = 0x01306B47
    private const val TAG_FP32 = 0

    /** Packed float count: per layer cout*cin*k*k weights then cout biases. */
    val weightCount: Int = LAYERS.sumOf { (cin, cout, k) -> cout * cin * k * k + cout }

    /** The bundled model weights (classpath resource; app and desktop alike). */
    fun bundledWeights(): FloatArray {
        val bytes = KernelNet::class.java.getResourceAsStream(RESOURCE_BIN)?.use { it.readBytes() }
            ?: throw IllegalStateException("KernelNet model resource missing: $RESOURCE_BIN")
        return weights(bytes)
    }

    /**
     * Parses an ncnn .bin for this graph: each Convolution loads its weight
     * blob with a 4-byte tag (fp16 halves after 0x01306B47, raw fp32 after
     * 0) and its bias as raw fp32. Returns [w0 b0 ... w5 b5] as fp32.
     */
    fun weights(bin: ByteArray): FloatArray {
        val buf = ByteBuffer.wrap(bin).order(ByteOrder.LITTLE_ENDIAN)
        val out = FloatArray(weightCount)
        var o = 0
        for ((cin, cout, k) in LAYERS) {
            val n = cout * cin * k * k
            when (val tag = buf.int) {
                TAG_FP16 -> repeat(n) { out[o++] = halfToFloat(buf.short.toInt() and 0xffff) }
                TAG_FP32 -> repeat(n) { out[o++] = buf.float }
                else -> throw IllegalArgumentException("KernelNet .bin: unknown weight tag 0x${tag.toString(16)}")
            }
            repeat(cout) { out[o++] = buf.float }
        }
        require(!buf.hasRemaining()) { "KernelNet .bin: ${buf.remaining()} trailing bytes (graph mismatch)" }
        return out
    }

    /**
     * Sigma fed to the net: sqrt(S * 0.5 + O) with S, O the mean R, G, B
     * noise-model slope/offset in normalized units, PhotonCamera ESD4D
     * (`kernelSigma`, noiseMpy 1). [alpha]/[beta] are in [R, G1, B, G2]
     * order.
     */
    fun sigma(alpha: FloatArray, beta: FloatArray): Float {
        val s = (alpha[0] + alpha[1] + alpha[2]) / 3f
        val o = (beta[0] + beta[1] + beta[2]) / 3f
        return kotlin.math.sqrt(s * 0.5f + o)
    }

    /**
     * Axis flips DngRead.flipToRggb / MfsrMerge apply for a CFA in
     * [00, 01, 10, 11] order (0 R, 1 G, 2 B): RGGB none, [2,1,1,0] both,
     * [1,0,2,1] horizontal, [1,2,0,1] vertical.
     */
    fun rggbFlips(cfa: IntArray): Pair<Boolean, Boolean> = when (cfa.toList()) {
        listOf(0, 1, 1, 2) -> false to false
        listOf(2, 1, 1, 0) -> true to true
        listOf(1, 0, 2, 1) -> true to false
        listOf(1, 2, 0, 1) -> false to true
        else -> throw IllegalArgumentException("KernelNet: unsupported CFA ${cfa.toList()}")
    }

    /** Parameter-map size for a raw frame: luma grid halved by the stride-2 layer. */
    fun mapSize(rawW: Int, rawH: Int): Pair<Int, Int> = ((rawW / 2 - 1) / 2 + 1) to ((rawH / 2 - 1) / 2 + 1)

    private fun halfToFloat(h: Int): Float {
        val sign = (h ushr 15) and 1
        val exp = (h ushr 10) and 0x1f
        val mant = h and 0x3ff
        val bits = when {
            exp == 0 && mant == 0 -> sign shl 31
            exp == 0 -> {
                // subnormal: normalize
                var m = mant
                var e = -14
                while (m and 0x400 == 0) { m = m shl 1; e-- }
                (sign shl 31) or ((e + 127) shl 23) or ((m and 0x3ff) shl 13)
            }
            exp == 31 -> (sign shl 31) or (0xff shl 23) or (mant shl 13)
            else -> (sign shl 31) or ((exp - 15 + 127) shl 23) or (mant shl 13)
        }
        return java.lang.Float.intBitsToFloat(bits)
    }
}
