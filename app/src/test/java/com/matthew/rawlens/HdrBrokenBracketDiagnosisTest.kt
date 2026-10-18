package com.matthew.rawlens

import org.junit.Test
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

/**
 * Broken-bracket diagnosis: 2026-10-16 12MP 2-stop bracket
 * (IMG_20261016_114334_308) whose production merge smeared like a painting.
 *
 * Runs the production legacy merge over translation-aligned frames and
 * reports sharpness / noise / fidelity metrics plus a full-res dump, so a
 * stage regression shows up as a number, not an adjective. Skips silently
 * when the DNGs are absent.
 *
 * Run: `HDR_BRACKETS=/path/to/dir HDR_PREFIX=IMG_... ./gradlew
 * :app:testDebugUnitTest --tests
 * "com.matthew.rawlens.HdrBrokenBracketDiagnosisTest"`.
 */
class HdrBrokenBracketDiagnosisTest {
    @Test fun diagnoseBothMerges() {
        // System properties first: Gradle test workers do not reliably
        // inherit the client's environment (long-lived daemon), so
        // `-Dhdr.brackets=... -Dhdr.prefix=...` is the robust channel.
        val dirPath = System.getProperty("hdr.brackets", System.getenv("HDR_BRACKETS") ?: "")
        val prefix = System.getProperty("hdr.prefix", System.getenv("HDR_PREFIX") ?: "")
        val dir = dirPath.takeIf { it.isNotEmpty() }?.let { File(it) } ?: return
        if (prefix.isEmpty()) return
        val files = listOf(
            File(dir, "${prefix}_F00.dng"),
            File(dir, "${prefix}_F01.dng"),
            File(dir, "${prefix}_F02.dng")
        )
        if (files.any { !it.isFile }) {
            println("broken-hdr: brackets absent, skipping")
            return
        }
        val frames = files.map { readFrame(it) }
        val refIdx = 1
        val aligned = frames.mapIndexed { k, f ->
            if (k == refIdx) f
            else {
                val shift = HdrBracketAligner.estimateShift(frames[refIdx], f)
                    ?: error("no shift measured on diagnostic bracket")
                println("broken-hdr: shift F01->F%02d = (%.2f, %.2f)".format(k, shift.dx, shift.dy))
                f.copy(flow = shift.asFlow())
            }
        }
        report(
            "legacy",
            HdrRawMerge.merge(aligned, refIdx, HdrRawMerge.Options(true, true, 8f)),
            aligned[refIdx]
        )
        report(
            "legacy-nodeghost",
            HdrRawMerge.merge(
                aligned, refIdx, HdrRawMerge.Options(smoothMask = true, deghost = false)
            ),
            aligned[refIdx]
        )
    }

    private fun readFrame(file: File): HdrMergeFrame {
        val bytes = file.readBytes()
        val buf = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
        fun u16(p: Int) = buf.getShort(p).toInt() and 0xFFFF
        fun u32(p: Int) = (buf.getInt(p).toLong() and 0xFFFFFFFFL)
        val ifd = u32(4).toInt()
        val n = u16(ifd)
        data class Entry(val type: Int, val count: Long, val dataOff: Int)
        val tags = HashMap<Int, Entry>()
        for (k in 0 until n) {
            val p = ifd + 2 + k * 12
            val unit = when (u16(p + 2)) { 3 -> 2; 4 -> 4; 5 -> 8; 12 -> 8; else -> 1 }
            val count = u32(p + 4)
            tags[u16(p)] = Entry(
                u16(p + 2), count,
                if (count * unit <= 4) -(p + 8) else u32(p + 8).toInt()
            )
        }
        fun bytesOf(e: Entry): ByteArray {
            val unit = when (e.type) { 3 -> 2; 4 -> 4; 5 -> 8; 12 -> 8; else -> 1 }
            val total = (e.count * unit).toInt()
            return if (e.dataOff < 0) ByteArray(total) { bytes[-e.dataOff + it] }
            else bytes.copyOfRange(e.dataOff, e.dataOff + total)
        }
        fun longs(tag: Int): List<Long> {
            val e = requireNotNull(tags[tag])
            val b = ByteBuffer.wrap(bytesOf(e)).order(ByteOrder.LITTLE_ENDIAN)
            return when (e.type) {
                3 -> List(e.count.toInt()) { (b.getShort().toInt() and 0xFFFF).toLong() }
                4 -> List(e.count.toInt()) { b.getInt().toLong() and 0xFFFFFFFFL }
                else -> throw IllegalArgumentException("tag $tag")
            }
        }
        fun rationals(tag: Int): List<Double> {
            val e = requireNotNull(tags[tag])
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
        val stripOff = longs(273)[0].toInt()
        val stripCount = longs(279)[0].toInt()
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
        val black = rationals(50714)
        val white = longs(50717)[0].toFloat()
        val values = FloatArray(width * height) { i ->
            val x = i % width
            val y = i / width
            (raw[i].toFloat() - black[((y and 1) shl 1) or (x and 1)].toFloat()) /
                (white - black[((y and 1) shl 1) or (x and 1)].toFloat())
        }
        val exp = rationals(33434)[0]
        val fnum = rationals(33437)[0]
        val iso = longs(34855)[0].toInt()
        val focal = rationals(37386)[0]
        val np = run {
            val e = requireNotNull(tags[51041])
            val b = ByteBuffer.wrap(bytesOf(e)).order(ByteOrder.LITTLE_ENDIAN)
            DoubleArray(e.count.toInt()) { b.getDouble() }
        }
        val noise = CfaNoiseModel(
            FloatArray(4) { listOf(np[0], np[2], np[2], np[4])[it].toFloat() },
            FloatArray(4) { listOf(np[1], np[3], np[3], np[5])[it].toFloat() }
        )
        val cfa = UnpackedRawCfa(width, height, pattern, values, RawCrop(0, 0, width, height))
        return HdrMergeFrame(
            cfa, (exp * 1e9).toLong(), iso, fnum.toFloat(), null, focal.toFloat(), noise
        )
    }

    private fun report(label: String, out: UnpackedRawCfa, ref: HdrMergeFrame) {
        val v = out.values
        val w = out.width
        val h = out.height
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
        val refVals = ref.cfa.values
        var fad = 0.0
        var fadN = 0
        var i = 0
        while (i < v.size) {
            fad += abs(v[i] - refVals[i])
            fadN++
            i += 4
        }
        // Raw (unnormalized) fidelity: scale output back by white level over
        // reference calibration, mirroring the merge's own normalization.
        println(
            "broken-hdr [$label]: sharpness=%.6f refFidelityMAD=%.6f min=%.5f max=%.5f".format(
                grad / gradN, fad / fadN,
                v.minOrNull() ?: Float.NaN, v.maxOrNull() ?: Float.NaN
            )
        )
        val outDir = File("build/hdr-validation")
        outDir.mkdirs()
        val dump = File(outDir, "broken-$label.f32")
        val bb = ByteBuffer.allocate(v.size * 4).order(ByteOrder.LITTLE_ENDIAN)
        bb.asFloatBuffer().put(v)
        dump.writeBytes(bb.array())
        File(outDir, "broken-$label.shape").writeText("$w $h")
        println("broken-hdr [$label]: f32=${dump.absolutePath}")
    }
}
