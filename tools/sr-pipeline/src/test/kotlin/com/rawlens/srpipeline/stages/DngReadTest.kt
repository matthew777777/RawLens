package com.rawlens.srpipeline.stages

import com.rawlens.srpipeline.SrpBridge
import com.rawlens.srpipeline.parity.NpyReader
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import org.junit.Assert.assertEquals
import org.junit.Assume.assumeTrue
import org.junit.Test

/**
 * Tests for [DngRead] (TinyDNG v3 ingest, RGGB flip, normalisation).
 *
 * The committed fixtures come from `IMG_20260927_134544_522_F00.dng` via
 * rawpy: `dngread_region_raw.npy` (64x64 uint16 at even origin
 * (2008,1498)), `dngread_region_norm.npy` (flipped + normalised float32),
 * `dngread_meta.npy` ([h,w,white,black0..3] int64),
 * `dngread_noise.npy` ([alpha,beta] x4 float64, R,G1,B,G2 order). The
 * live-DNG legs need `-Dsrpipeline.burst=<burst dir>` (default: the known
 * burst path) and `-Dsrpipeline.lib=<libsrpipeline_jni>`; they skip when
 * absent. The flip/normalise leg runs on the committed fixtures alone.
 */
class DngReadTest {

    private val burstDir: File
        get() = File(System.getProperty(
            "srpipeline.burst",
            "/Users/monikamalinowska/Downloads/Photos/Photos/IMG_20260927_134544_522"))

    private val lib: String
        get() = System.getProperty("srpipeline.lib", "")

    private fun fixDir(): File = File(System.getProperty("srpipeline.fixtures", "parity/fixtures"))

    private fun assumeDng(): File {
        assumeTrue("native DNG proof needs -Dsrpipeline.lib", lib.isNotEmpty() && File(lib).exists())
        val frame = File(burstDir, "IMG_20260927_134544_522_F00.dng")
        assumeTrue("burst DNG missing: $frame", frame.exists())
        SrpBridge.loadPath(lib)
        return frame
    }

    private fun u16Payload(raw: NpyReader.RawNpy): ShortArray {
        assertEquals("<u2", raw.descr)
        val out = ShortArray(raw.payload.size / 2)
        ByteBuffer.wrap(raw.payload).order(ByteOrder.LITTLE_ENDIAN).asShortBuffer().get(out)
        return out
    }

    private fun i64Payload(raw: NpyReader.RawNpy): LongArray {
        assertEquals("<i8", raw.descr)
        val out = LongArray(raw.payload.size / 8)
        ByteBuffer.wrap(raw.payload).order(ByteOrder.LITTLE_ENDIAN).asLongBuffer().get(out)
        return out
    }

    private fun f64Payload(raw: NpyReader.RawNpy): DoubleArray {
        assertEquals("<f8", raw.descr)
        val out = DoubleArray(raw.payload.size / 8)
        ByteBuffer.wrap(raw.payload).order(ByteOrder.LITTLE_ENDIAN).asDoubleBuffer().get(out)
        return out
    }

    @Test
    fun `meta matches rawpy tags exactly`() {
        val frame = assumeDng()
        val meta = DngRead.readMeta(frame.absolutePath)
        val want = i64Payload(NpyReader.loadRaw(File(fixDir(), "dngread_meta.npy")))
        assertEquals(want[0].toInt(), meta.height)
        assertEquals(want[1].toInt(), meta.width)
        assertEquals(want[2].toInt(), meta.white)
        for (i in 0 until 4) assertEquals(want[3 + i].toInt(), meta.black[i])
        // GRBG [[3,2],[0,1]] with the LibRaw 3->1 G2 remap.
        assertEquals(listOf(1, 2, 0, 1), meta.cfa.toList())
        val info = SrpBridge.nativeDngMeta(frame.absolutePath)
            ?: throw AssertionError("meta unreadable")
        assertEquals(6, info[11])
    }

    @Test
    fun `noise alpha beta match tag profile exactly`() {
        val frame = assumeDng()
        val info = SrpBridge.nativeDngMeta(frame.absolutePath)
            ?: throw AssertionError("meta unreadable")
        val noise = SrpBridge.nativeDngNoise(frame.absolutePath)
            ?: throw AssertionError("noise unreadable")
        val (alpha, beta) = DngRead.noiseAlphaBeta(noise, info[11])
        val want = f64Payload(NpyReader.loadRaw(File(fixDir(), "dngread_noise.npy")))
        for (i in 0 until 4) {
            assertEquals(want[2 * i], alpha[i], 0.0)
            assertEquals(want[2 * i + 1], beta[i], 0.0)
        }
    }

    @Test
    fun `region decode matches rawpy bytes exactly`() {
        val frame = assumeDng()
        val meta = DngRead.readMeta(frame.absolutePath)
        val buf = DngRead.decodeRegion(frame.absolutePath, meta, 2008, 1498, 64, 64)
        val shorts = ShortArray(64 * 64)
        buf.asShortBuffer().get(shorts)
        val want = u16Payload(NpyReader.loadRaw(File(fixDir(), "dngread_region_raw.npy")))
        assertEquals(want.size, shorts.size)
        var bad = 0
        for (i in shorts.indices) if (shorts[i] != want[i]) bad++
        println("dngread region mismatches=$bad")
        assertEquals(0, bad)
    }

    @Test
    fun `color profile matches burst tags`() {
        val frame = assumeDng()
        val profile = SrpBridge.readDngColorProfile(frame.absolutePath)
        // Ground truth from the source DNG (exiftool): Xiaomi REDMI Note 15
        // Pro, D65 ColorMatrix1 + AsShotNeutral.
        val wantMatrix = doubleArrayOf(
            0.6667938232, -0.1588897705, -0.08573913574,
            -0.5739440918, 1.389785767, 0.1430206299,
            -0.137878418, 0.2651519775, 0.6036224365)
        val wantNeutral = doubleArrayOf(0.5361256545, 1.0, 0.6080760095)
        val m = profile.colorMatrix ?: throw AssertionError("matrix missing")
        val n = profile.neutral ?: throw AssertionError("neutral missing")
        for (i in 0 until 9) assertEquals(wantMatrix[i], m[i], 1e-9)
        for (i in 0 until 3) assertEquals(wantNeutral[i], n[i], 1e-9)
        assertEquals(21, profile.illuminant)
        assertEquals("Xiaomi", profile.make)
        assertEquals("REDMI Note 15 Pro 5G/25080RABDG", profile.model)
    }

    @Test
    fun `write carries profile tags and pixels roundtrip`() {
        val frame = assumeDng()
        val profile = SrpBridge.readDngColorProfile(frame.absolutePath)
        val w = 8
        val h = 6
        val rgb = SrpBridge.directFloats(w * h * 3)
        val fb = rgb.asFloatBuffer()
        for (i in 0 until w * h * 3) fb.put(i, (i % 256) / 255f)
        rgb.rewind()
        val out = File.createTempFile("srp-color", ".dng")
        out.deleteOnExit()
        val rc = SrpBridge.writeDng(out.absolutePath, w, h, rgb,
            profile.make, profile.model,
            profile.colorMatrix, profile.neutral, profile.illuminant)
        assertEquals(0, rc)
        val back = SrpBridge.readDngColorProfile(out.absolutePath)
        // Writer stores SRATIONALs (1e-6 denominator), so the roundtrip
        // re-quantizes the profile: gate at that floor, not bit-exact.
        for (i in 0 until 9) assertEquals(profile.colorMatrix!![i], back.colorMatrix!![i], 1e-6)
        for (i in 0 until 3) assertEquals(profile.neutral!![i], back.neutral!![i], 1e-6)
        assertEquals(profile.illuminant, back.illuminant)
        assertEquals(profile.make, back.make)
        assertEquals(profile.model, back.model)
    }

    @Test
    fun `flip and normalize match rawpy pipeline exactly`() {
        // Fixture-only leg: no DNG needed.
        val raw = u16Payload(NpyReader.loadRaw(File(fixDir(), "dngread_region_raw.npy")))
        val pixels = IntArray(raw.size) { raw[it].toInt() and 0xFFFF }
        val flipped = DngRead.flipToRggb(pixels, 64, 64, intArrayOf(1, 2, 0, 1))
        val got = DngRead.normalize(flipped, 64, 64, intArrayOf(64, 64, 64, 64), 1023)
        val want = NpyReader.load(File(fixDir(), "dngread_region_norm.npy")).data
        var maxAbs = 0f
        for (y in 0 until 64) for (x in 0 until 64) {
            maxAbs = maxOf(maxAbs, kotlin.math.abs(got[y][x] - want[y * 64 + x]))
        }
        println("dngread normalize maxabs=$maxAbs")
        assertEquals(0f, maxAbs, 0f)
    }

    @Test
    fun `readNormalized matches the four-step chain bit-exactly`() {
        // The fused single-pass reader must produce the exact bytes of
        // decodeFull + toUnsigned + flipToRggb + normalize (bit-identity,
        // not an envelope): the CLI uses it for every frame on both
        // backends, so any divergence would break e2e identity.
        val frame = assumeDng()
        val meta = DngRead.readMeta(frame.absolutePath)
        val bits = DngRead.decodeFull(frame.absolutePath, meta)
        val pixels = DngRead.toUnsigned(bits, meta.width * meta.height)
        val flipped = DngRead.flipToRggb(pixels, meta.width, meta.height, meta.cfa)
        val want = DngRead.normalize(flipped, meta.width, meta.height, meta.black, meta.white)
        val got = DngRead.readNormalized(frame.absolutePath, meta)
        assertEquals(want.size, got.size)
        var diff = 0
        for (y in want.indices) {
            assertEquals(want[y].size, got[y].size)
            for (x in want[y].indices) {
                if (want[y][x] != got[y][x]) diff++
            }
        }
        val n = want.size * want[0].size
        println("dngread fused-vs-chain: $diff/$n lanes differ")
        assertEquals("fused reader must be bit-exact", 0, diff)
    }
}
