package com.rawlens.srpipeline.diag

import com.rawlens.srpipeline.stages.EndToEnd
import com.rawlens.srpipeline.stages.Grey
import com.rawlens.srpipeline.stages.PipelineConfig
import com.rawlens.srpipeline.stages.Resample
import com.rawlens.srpipeline.stages.alignLevels
import com.rawlens.srpipeline.stages.initAlignment
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.sqrt

/**
 * Checklist M / C10 border band: A/B of AlignLoopConfig.edgeExtendFlow
 * (fix) vs false (Python-exact zero shortfall). Burst IMG_20260929_170823_957.
 * Run: ./gradlew :tools:sr-pipeline:test --tests '*C10BorderAbTest*' -PsrpDiag=1 -i
 */
class C10BorderAbTest {
    private val dir = "/Users/monikamalinowska/Downloads/My/Photos/Photos/IMG_20260929_170823_957"
    private fun readCrop(frame: Int, x0: Int, y0: Int, w: Int, h: Int): Array<FloatArray> {
        RandomAccessFile("$dir/IMG_20260929_170823_957_F%02d.dng".format(frame), "r").use { f ->
            val row = ByteArray(w * 2)
            return Array(h) { y ->
                f.seek(6644L + ((y0 + y).toLong() * 4080 + x0) * 2)
                f.readFully(row)
                val bb = ByteBuffer.wrap(row).order(ByteOrder.LITTLE_ENDIAN)
                FloatArray(w) { ((bb.getShort(it * 2).toInt() and 0xFFFF) - 64f) / 959f }
            }
        }
    }
    private val base = EndToEnd.loopConfig(PipelineConfig(factors = listOf(1, 2, 4, 4), tileSizes = listOf(16, 16, 16, 8)))

    @Test
    fun fullFrameShortfallGeometry() {
        assumeTrue(System.getProperty("srpipeline.diag", "").isNotEmpty())
        // App frame: 4080x3060 raw (RGGB flip keeps size), FFT grey full res, padded to 16.
        for ((w, h) in listOf(4080 to 3060, 4078 to 3060, 512 to 512, 2048 to 2048)) {
            val padH = (h + 15) / 16 * 16
            val padW = (w + 15) / 16 * 16
            val pyr = Resample.buildGaussianPyramid(Array(padH) { FloatArray(padW) }, base.factors)
            val n = base.factors.size
            val tiles = pyr.mapIndexed { p, l -> val ts = base.tileSizes[n - 1 - p]; (l.size / ts) to (l[0].size / ts) }
            val parts = (1 until n).map { p ->
                val c = n - p - 1
                val rep = base.factors[c + 1] / (base.tileSizes[c] / base.tileSizes[c + 1])
                val (cy, cx) = tiles[p - 1]; val (ty, tx) = tiles[p]
                "lvl$p ${ty}x$tx from ${cy * rep}x${cx * rep}: shortfall rows ${ty - cy * rep}, cols ${tx - cx * rep}"
            }
            println("C10G ${w}x$h: " + parts.joinToString(" | "))
        }
    }

    @Test
    fun knownShiftAb() {
        assumeTrue(System.getProperty("srpipeline.diag", "").isNotEmpty())
        val big = 2048
        val (bx, by) = 1785 to 506
        for (edge in listOf(false, true)) {
            val loop = base.copy(edgeExtendFlow = edge)
            val init = initAlignment(Grey.computeGreyImages(readCrop(0, bx, by, big, big), "FFT"), loop)
            for ((sx, sy) in listOf(0 to 0, 4 to -2, 8 to 6, 16 to -10, 24 to 20, 40 to -30)) {
                val flow = alignLevels(init, Grey.computeGreyImages(readCrop(0, bx - sx, by - sy, big, big), "FFT"), loop)
                val n = flow.size; val m = flow[0].size
                var bad = 0; var all = 0; var brBad = 0; var brAll = 0; var inBad = 0; var inAll = 0
                val mx = (kotlin.math.abs(sx) + 15) / 16 + 1; val my = (kotlin.math.abs(sy) + 15) / 16 + 1
                for (ty in my until n - my) for (tx in mx until m - mx) {
                    val dx = flow[ty][tx][0] - sx; val dy = flow[ty][tx][1] - sy
                    val b = sqrt((dx * dx + dy * dy).toDouble()) > 0.5
                    all++; if (b) bad++
                    if (n - 1 - ty < 8 || m - 1 - tx < 8) { brAll++; if (b) brBad++ } else if (ty >= 8 && tx >= 8) { inAll++; if (b) inBad++ }
                }
                println("C10A edge=%-5s s=(%3d,%3d) bad %.1f%% | bottom/right 128px band %.1f%% | interior %.2f%%".format(
                    edge, sx, sy, 100.0 * bad / all, 100.0 * brBad / brAll, 100.0 * inBad / inAll))
            }
        }
    }

    /** Real 8-frame merge on a 512 crop: where does the output change, and is the band better aligned? */
    @Test
    fun realMergeAb() {
        assumeTrue(System.getProperty("srpipeline.diag", "").isNotEmpty())
        for ((ox, oy) in listOf(1785 to 1274, 801 to 600)) {
            val frames = (0..7).map { readCrop(it, ox, oy, 512, 512) }
            val outs = listOf(false, true).map { edge ->
                val cfg = PipelineConfig(scale = 1f, factors = listOf(1, 2, 4, 4), tileSizes = listOf(16, 16, 16, 8))
                val loop = EndToEnd.loopConfig(cfg).copy(edgeExtendFlow = edge)
                val init = initAlignment(Grey.computeGreyImages(frames[0], "FFT"), loop)
                // band alignment residual: mean |ref - moving(x+flow)| at integer-rounded flow in the bottom/right 48 px
                var res = 0.0; var cnt = 0
                val g0 = Grey.computeGreyImages(frames[0], "FFT")
                for (k in 1..7) {
                    val gk = Grey.computeGreyImages(frames[k], "FFT")
                    val f = alignLevels(init, gk, loop)
                    for (y in 16 until 512 - 16) for (x in 16 until 512 - 16) {
                        if (y < 512 - 48 && x < 512 - 48) continue
                        val t = f[minOf(y / 16, f.size - 1)][minOf(x / 16, f[0].size - 1)]
                        val my = (y + Math.round(t[1])).coerceIn(0, 511); val mx = (x + Math.round(t[0])).coerceIn(0, 511)
                        res += kotlin.math.abs(g0[y][x] - gk[my][mx]); cnt++
                    }
                }
                println("C10M c${ox}_$oy edge=$edge bottom/right 48px band mean |ref - warped| %.5f".format(res / cnt))
                EndToEnd.run(frames[0], frames.drop(1).map { fr -> { fr } }, cfg) { fg -> alignLevels(init, fg, loop) }
            }
            var inMax = 0f; var bandMax = 0f; var changed = 0
            for (y in 0 until 512) for (x in 0 until 512) for (c in 0..2) {
                val d = kotlin.math.abs(outs[0][y][x][c] - outs[1][y][x][c])
                if (d > 1e-6f) changed++
                if (y >= 512 - 64 || x >= 512 - 64) bandMax = maxOf(bandMax, d) else inMax = maxOf(inMax, d)
            }
            println("C10M c${ox}_$oy merge diff: changed samples %d, max in bottom/right 64px band %.5f, max elsewhere %.6f".format(changed, bandMax, inMax))
        }
    }
}
