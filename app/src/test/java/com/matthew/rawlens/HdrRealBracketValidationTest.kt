package com.matthew.rawlens

import org.junit.Test
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

/**
 * Real-bracket before/after validation for the HDR merge (Phases 1-3).
 *
 * Loads three real exposure-bracket DNGs, aligns with the production
 * translation pre-aligner, merges with production [HdrRawMerge.Options],
 * and reports sharpness/noise/fidelity metrics plus a downsampled PGM for
 * visual comparison. Uses only APIs that exist before AND after the
 * Phase 1-3 work, so the same file runs unmodified on both trees.
 *
 * Run: `./gradlew :app:testDebugUnitTest --tests
 * "com.matthew.rawlens.HdrRealBracketValidationTest" -Dhdr.label=after`
 * with `-Dhdr.brackets=/path/to/dir` (default below). Skips silently when
 * the DNGs are absent so CI stays green.
 */
class HdrRealBracketValidationTest {
    private data class FrameMeta(
        val raw: IntArray,
        val width: Int,
        val height: Int,
        val pattern: BayerPattern,
        val black: FloatArray,
        val white: Float,
        val exposureNanos: Long,
        val iso: Int,
        val aperture: Float,
        val focal: Float,
        val noiseScale: FloatArray,
        val noiseOffset: FloatArray
    )

    @Test fun mergeRealBracketsAndReport() {
        // Label via environment (Gradle test workers do not inherit -D).
        val label = System.getenv("HDR_LABEL") ?: System.getProperty("hdr.label", "run")
        val dir = System.getenv("HDR_BRACKETS")?.let { File(it) } ?: File(System.getProperty(
            "hdr.brackets", "/Users/monikamalinowska/Downloads/23"))
        val prefix = System.getenv("HDR_PREFIX") ?: "IMG_20260923_150504_903"
        val files = listOf(
            File(dir, "${prefix}_F00.dng"),
            File(dir, "${prefix}_F01.dng"),
            File(dir, "${prefix}_F02.dng")
        )
        if (files.any { !it.isFile }) {
            println("hdr-validation [$label]: brackets absent, skipping")
            return
        }
        val metas = files.map { readDng(it) }
        println("hdr-validation [$label]: loaded " +
            metas.joinToString { "${it.width}x${it.height} exp=${it.exposureNanos}ns" })

        val frames = metas.map { m ->
            val values = FloatArray(m.width * m.height) { i ->
                val x = i % m.width
                val y = i / m.width
                val black = m.black[((y and 1) shl 1) or (x and 1)]
                (m.raw[i].toFloat() - black) / (m.white - black)
            }
            val cfa = UnpackedRawCfa(
                m.width, m.height, m.pattern, values,
                RawCrop(0, 0, m.width, m.height)
            )
            HdrMergeFrame(
                cfa = cfa,
                exposureTimeNanos = m.exposureNanos,
                sensitivityIso = m.iso,
                aperture = m.aperture,
                flow = null,
                focalLength = m.focal,
                noiseModel = CfaNoiseModel(m.noiseScale, m.noiseOffset)
            )
        }
        // Production geometry: middle exposure is the reference; the
        // translation pre-aligner always runs (FlowNet unavailable on JVM).
        val refIdx = 1
        val aligned = frames.mapIndexed { k, f ->
            if (k == refIdx) f
            else {
                val shift = HdrBracketAligner.estimateShift(frames[refIdx], f)
                println("hdr-validation [$label]: shift F01->F%02d = (%.2f, %.2f)".format(k, shift.dx, shift.dy))
                f.copy(flow = shift.asFlow())
            }
        }
        val t0 = System.nanoTime()
        val out = HdrRawMerge.merge(aligned, refIdx, HdrRawMerge.Options(true, true, 8f))
        val mergeMs = (System.nanoTime() - t0) / 1_000_000
        println("hdr-validation [$label]: merge drainMs=$mergeMs")

        val v = out.values
        val w = out.width
        val h = out.height
        require(v.size == w * h)
        var finite = 0
        var min = Float.MAX_VALUE
        var max = -Float.MAX_VALUE
        for (x in v) {
            if (x.isFinite()) {
                finite++
                if (x < min) min = x
                if (x > max) max = x
            }
        }
        // Sharpness: mean abs gradient, stride 2 for speed.
        var grad = 0.0
        var gradN = 0
        var y = 2
        while (y < h - 2) {
            var x = 2
            while (x < w - 2) {
                val i = y * w + x
                grad += abs(v[i] - v[i - 1]) + abs(v[i] - v[i - w])
                gradN += 2
                x += 2
            }
            y += 2
        }
        // Noise: MAD of the Laplacian on a stride-8 grid (robust, fast).
        val laps = DoubleArray(((h / 8) * (w / 8)))
        var ln = 0
        var yy = 4
        while (yy < h - 4) {
            var xx = 4
            while (xx < w - 4) {
                val i = yy * w + xx
                laps[ln++] = (4.0 * v[i] - v[i - 1] - v[i + 1] - v[i - w] - v[i + w]).toDouble()
                xx += 8
            }
            yy += 8
        }
        laps.sort(0, ln)
        val median = laps[ln / 2]
        val devs = DoubleArray(ln) { abs(laps[it] - median) }
        devs.sort()
        val mad = devs[ln / 2]
        // Fidelity vs the gain-matched reference (same output domain:
        // reference samples scaled by cal(ref)/whiteLevel like the merge).
        val refCal = calibrationOf(aligned[refIdx])
        val whiteLevel = aligned.maxOf { calibrationOf(it) }
        val refGain = refCal / whiteLevel
        val refVals = aligned[refIdx].cfa.values
        var fad = 0.0
        var fadN = 0
        var shadVar = 0.0
        var shadMean = 0.0
        var shadN = 0
        var clipErr = 0.0
        var clipN = 0
        // Short-exposure expectation in the clipped mask.
        val shortCal = calibrationOf(aligned[0])
        val shortGain = shortCal / whiteLevel
        val shortVals = aligned[0].cfa.values
        var i = 0
        while (i < v.size) {
            val r = refVals[i] * refGain
            fad += abs(v[i] - r)
            fadN++
            if (refVals[i] < 0.05f) {
                shadMean += v[i]
                shadN++
            }
            if (refVals[i] >= 0.98f) {
                clipErr += abs(v[i] - shortVals[i] * shortGain)
                clipN++
            }
            i += 4
        }
        shadMean /= shadN.coerceAtLeast(1)
        var shadAcc = 0.0
        i = 0
        while (i < v.size) {
            if (refVals[i] < 0.05f) {
                val d = v[i] - shadMean
                shadAcc += d * d
            }
            i += 4
        }
        shadVar = shadAcc / shadN.coerceAtLeast(1)
        println("hdr-validation [$label]: finite=$finite/${v.size} min=%.5f max=%.5f".format(min, max))
        println("hdr-validation [$label]: sharpness=%.6f noiseMAD=%.7f".format(grad / gradN, mad))
        println("hdr-validation [$label]: refFidelityMAD=%.6f (n=%d)".format(fad / fadN, fadN))
        println("hdr-validation [$label]: shadowVar=%.8f (n=%d)".format(shadVar, shadN))
        println("hdr-validation [$label]: clipRescueErr=%.6f (n=%d)".format(
            if (clipN > 0) clipErr / clipN else Double.NaN, clipN))
        writePgm(label, out)
        writeFullRes(label, out)
    }

    private fun calibrationOf(f: HdrMergeFrame): Float {
        val area = Math.PI.toFloat() * (0.5f * f.focalLength / f.aperture).let { it * it }
        return 100f / (area * f.exposureTimeNanos * 1e-9f * f.sensitivityIso)
    }

    private fun writePgm(label: String, cfa: UnpackedRawCfa) {
        val scale = 4
        val ow = cfa.width / scale
        val oh = cfa.height / scale
        // Fixed display gain for both runs so before/after are comparable.
        val gain = 8f
        val sb = StringBuilder("P5\n$ow $oh\n255\n")
        val bytes = ByteArray(ow * oh)
        for (oy in 0 until oh) for (ox in 0 until ow) {
            var sum = 0.0
            for (dy in 0 until scale) for (dx in 0 until scale) {
                sum += cfa.values[(oy * scale + dy) * cfa.width + ox * scale + dx]
            }
            val g = Math.pow(max(0.0, sum / (scale * scale) * gain).toDouble(), 1.0 / 2.2)
            bytes[oy * ow + ox] = (min(1.0, max(0.0, g)) * 255).toInt().toByte()
        }
        val outDir = File("build/hdr-validation")
        outDir.mkdirs()
        val pgm = File(outDir, "merge-$label.pgm")
        pgm.outputStream().use { it.write(sb.toString().toByteArray()) }
        pgm.appendBytes(bytes)
        println("hdr-validation [$label]: pgm=${pgm.absolutePath}")
    }

    private fun writeFullRes(label: String, cfa: UnpackedRawCfa) {
        // Full-res little-endian float32 dump for offline spectral analysis.
        val outDir = File("build/hdr-validation")
        outDir.mkdirs()
        val dump = File(outDir, "merge-$label.f32")
        val bb = ByteBuffer.allocate(cfa.values.size * 4).order(ByteOrder.LITTLE_ENDIAN)
        bb.asFloatBuffer().put(cfa.values)
        dump.writeBytes(bb.array())
        File(outDir, "merge-$label.shape").writeText("${cfa.width} ${cfa.height}")
        println("hdr-validation [$label]: f32=${dump.absolutePath} (${dump.length() / 1048576} MiB)")
    }

    // ---- minimal single-strip uncompressed DNG reader (little-endian) ----

    private fun readDng(file: File): FrameMeta {
        val bytes = file.readBytes()
        require(bytes.size > 8 && bytes[0] == 0x49.toByte() && bytes[1] == 0x49.toByte()) {
            "${file.name}: expected little-endian TIFF"
        }
        val buf = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
        fun u16(p: Int) = buf.getShort(p).toInt() and 0xFFFF
        fun u32(p: Int) = (buf.getInt(p).toLong() and 0xFFFFFFFFL)
        require(u16(2) == 42)
        val ifd = u32(4).toInt()
        val n = u16(ifd)
        data class Entry(val type: Int, val count: Long, val dataOff: Int)
        val tags = HashMap<Int, Entry>()
        for (k in 0 until n) {
            val p = ifd + 2 + k * 12
            val tag = u16(p)
            val type = u16(p + 2)
            val count = u32(p + 4)
            val inline = ByteArray(4) { bytes[p + 8 + it] }
            val unit = when (type) { 3 -> 2; 4 -> 4; 5 -> 8; 12 -> 8; else -> 1 }
            val total = count * unit
            val dataOff = if (total <= 4) -1 else u32(p + 8).toInt()
            tags[tag] = Entry(type, count, if (total <= 4) -(p + 8) else dataOff)
        }
        fun bytesOf(e: Entry): ByteArray {
            val unit = when (e.type) { 3 -> 2; 4 -> 4; 5 -> 8; 12 -> 8; else -> 1 }
            val total = (e.count * unit).toInt()
            return if (e.dataOff < 0) {
                val base = -e.dataOff
                ByteArray(total) { bytes[base + it] }
            } else bytes.copyOfRange(e.dataOff, e.dataOff + total)
        }
        fun longs(tag: Int): List<Long> {
            val e = requireNotNull(tags[tag]) { "missing tag $tag" }
            val b = ByteBuffer.wrap(bytesOf(e)).order(ByteOrder.LITTLE_ENDIAN)
            return when (e.type) {
                3 -> List(e.count.toInt()) { (b.getShort().toInt() and 0xFFFF).toLong() }
                4 -> List(e.count.toInt()) { b.getInt().toLong() and 0xFFFFFFFFL }
                else -> throw IllegalArgumentException("tag $tag unexpected type ${e.type}")
            }
        }
        fun rationals(tag: Int): List<Double> {
            val e = requireNotNull(tags[tag]) { "missing tag $tag" }
            require(e.type == 5)
            val b = ByteBuffer.wrap(bytesOf(e)).order(ByteOrder.LITTLE_ENDIAN)
            return List(e.count.toInt()) {
                val num = b.getInt().toLong() and 0xFFFFFFFFL
                val den = b.getInt().toLong() and 0xFFFFFFFFL
                num.toDouble() / den.toDouble()
            }
        }
        val width = longs(256)[0].toInt()
        val height = longs(257)[0].toInt()
        require(longs(258).all { it == 16L }) { "need 16-bit" }
        require((tags[259]?.let { longs(259)[0] } ?: 1L) == 1L) { "need uncompressed" }
        val stripOff = longs(273)[0].toInt()
        val stripCount = longs(279)[0].toInt()
        require(stripCount == width * height * 2)
        val raw = IntArray(width * height)
        val db = ByteBuffer.wrap(bytes, stripOff, stripCount).order(ByteOrder.LITTLE_ENDIAN)
        for (k in raw.indices) raw[k] = db.getShort().toInt() and 0xFFFF
        val cfaBytes = bytesOf(requireNotNull(tags[33422]))
        val cells = IntArray(4) { cfaBytes[it].toInt() and 0xFF }
        val pattern = when (cells.toList()) {
            listOf(0, 1, 1, 2) -> BayerPattern.RGGB
            listOf(1, 0, 2, 1) -> BayerPattern.GRBG
            listOf(1, 2, 0, 1) -> BayerPattern.GBRG
            listOf(2, 1, 1, 0) -> BayerPattern.BGGR
            else -> throw IllegalArgumentException("CFA $cells")
        }
        val exp = rationals(33434)[0]
        val fnum = rationals(33437)[0]
        val iso = longs(34855)[0].toInt()
        val focal = rationals(37386)[0]
        val blackR = rationals(50714)
        require(blackR.size == 4)
        val white = longs(50717)[0].toFloat()
        // NoiseProfile: 6 doubles (R,G,B planes); expand like CfaNoiseModel.from.
        val np = run {
            val e = requireNotNull(tags[51041]) { "missing NoiseProfile" }
            val b = ByteBuffer.wrap(bytesOf(e)).order(ByteOrder.LITTLE_ENDIAN)
            DoubleArray(e.count.toInt()) { b.getDouble() }
        }
        require(np.size == 6)
        return FrameMeta(
            raw, width, height, pattern,
            FloatArray(4) { blackR[it].toFloat() }, white,
            (exp * 1e9).toLong(), iso, fnum.toFloat(), focal.toFloat(),
            FloatArray(4) { listOf(np[0], np[2], np[2], np[4])[it].toFloat() },
            FloatArray(4) { listOf(np[1], np[3], np[3], np[5])[it].toFloat() }
        )
    }
}
