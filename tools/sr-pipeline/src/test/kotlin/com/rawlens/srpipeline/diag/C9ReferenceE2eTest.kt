package com.rawlens.srpipeline.diag

import com.rawlens.srpipeline.stages.EndToEnd
import com.rawlens.srpipeline.stages.Grey
import com.rawlens.srpipeline.stages.alignLevels
import com.rawlens.srpipeline.stages.alignLevelsPerLevel
import com.rawlens.srpipeline.stages.alignLvl
import com.rawlens.srpipeline.stages.upscaleLvl
import com.rawlens.srpipeline.stages.Ica
import com.rawlens.srpipeline.stages.Resample
import com.rawlens.srpipeline.stages.computeGuideImage
import com.rawlens.srpipeline.stages.computeLocalStats
import com.rawlens.srpipeline.stages.computeRobustness
import com.rawlens.srpipeline.stages.initAlignment
import com.rawlens.srpipeline.stages.KernelParams
import com.rawlens.srpipeline.stages.PipelineConfig
import com.rawlens.srpipeline.stages.RobustnessParams
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Checklist M / C9: RawLens CPU engine vs the Python reference on identical
 * float inputs. The Python side (mfsr-refs harness c9_export.py) writes
 * `burst.f32` ([n][h][w], normalised RGGB), `cfg.json` (the reference's
 * effective SNR-tuned config) and `out_ref.f32`; this test writes
 * `out_rawlens.f32` next to them with every post-paper change off
 * (edgeExtendFlow, kMin, colour-diff, S.3, hot pixels are not in EndToEnd).
 * Run: ./gradlew :tools:sr-pipeline:test --tests '*C9ReferenceE2eTest*' -PsrpDiag=1 -PsrpBurstDir=<dir> -i
 */
class C9ReferenceE2eTest {
    private fun cfg(text: String, key: String): String =
        Regex("\"$key\":\\s*(\\[[^\\]]*\\]|\"[^\"]*\"|[^,}\\s]+)").find(text)?.groupValues?.get(1)
            ?: error("cfg.json has no $key")
    private fun num(t: String, k: String) = cfg(t, k).toFloat()
    private fun list(t: String, k: String) = cfg(t, k).trim('[', ']').split(',').map { it.trim().toFloat() }

    @Test
    fun runReferenceConfig() {
        assumeTrue(System.getProperty("srpipeline.diag", "").isNotEmpty())
        val dir = File(System.getProperty("srpipeline.burst"))
        val t = File(dir, "cfg.json").readText()
        val n = num(t, "n").toInt(); val h = num(t, "h").toInt(); val w = num(t, "w").toInt()
        require(cfg(t, "grey_method") == "\"FFT\"" && cfg(t, "merge_pre") == "\"gat\"" &&
            cfg(t, "law") == "\"linear\"" && cfg(t, "iso") == "false" && cfg(t, "flow_up") == "\"bilinear\"" &&
            cfg(t, "preproc_align") == "null") { "unsupported reference config: $t" }
        val bb = ByteBuffer.wrap(File(dir, "burst.f32").readBytes()).order(ByteOrder.LITTLE_ENDIAN).asFloatBuffer()
        val frames = Array(n) { Array(h) { FloatArray(w) } }
        for (k in 0 until n) for (y in 0 until h) bb.get(frames[k][y])
        val config = PipelineConfig(
            scale = num(t, "scale"),
            bayer = true,
            greyMethod = "FFT",
            factors = list(t, "factors").map { it.toInt() },
            tileSizes = list(t, "tile_sizes").map { it.toInt() },
            searchRadii = list(t, "search_radii").map { it.toInt() },
            kernelParams = KernelParams(
                kDetail = num(t, "k_detail"), kDenoise = num(t, "k_denoise"),
                dTh = num(t, "D_th"), dTr = num(t, "D_tr"),
                kStretch = num(t, "k_stretch"), kShrink = num(t, "k_shrink"),
            ),
            kernelPreprocessing = "gat",
            kernelAlpha = list(t, "alpha").toFloatArray(),
            kernelBeta = list(t, "beta").toFloatArray(),
            robustnessParams = RobustnessParams(
                t = num(t, "t"), s1 = num(t, "s1"), s2 = num(t, "s2"), mt = num(t, "Mt"),
                noiseCorrection = false, s3 = false,
            ),
            robustnessEnabled = cfg(t, "rob") == "true",
            icaIterations = num(t, "ica_iter").toInt(),
            icaClip = cfg(t, "ica_clip") == "true",
            edgeExtendFlow = false,
        )
        fun write(name: String, values: Sequence<Float>, count: Int) {
            val ob = ByteBuffer.allocate(count * 4).order(ByteOrder.LITTLE_ENDIAN)
            values.forEach { ob.putFloat(it) }
            File(dir, name).writeBytes(ob.array())
        }
        fun writeImage(name: String, out: Array<Array<FloatArray>>) =
            write(name, out.asSequence().flatMap { r -> r.asSequence().flatMap { it.asSequence() } }, out.size * out[0].size * 3)

        // (a) RawLens flows, same loop the engine uses.
        val loop = EndToEnd.loopConfig(config)
        val refInit = initAlignment(Grey.computeGreyImages(frames[0], "FFT"), loop)
        val ourFlows = (1 until n).map { k -> alignLevels(refInit, Grey.computeGreyImages(frames[k], "FFT"), loop) }
        val ty = ourFlows[0].size; val tx = ourFlows[0][0].size
        write("flows_rawlens.f32", ourFlows.asSequence().flatMap { f -> f.asSequence().flatMap { r -> r.asSequence().flatMap { it.asSequence() } } }, (n - 1) * ty * tx * 2)

        // (b) Robustness with the reference's own flows (isolates robustness from alignment).
        val fb = ByteBuffer.wrap(File(dir, "flows_ref.f32").readBytes()).order(ByteOrder.LITTLE_ENDIAN).asFloatBuffer()
        val refFlows = List(n - 1) { Array(ty) { Array(tx) { FloatArray(2).also { fb.get(it) } } } }
        val (means, vars) = computeLocalStats(computeGuideImage(frames[0]))
        val robs = (1 until n).map { k ->
            computeRobustness(frames[k], means, vars, refFlows[k - 1], config.sigmaSqCurve, config.dSqCurve,
                config.tileSizes.first(), true, config.robustnessParams)
        }
        write("rob_rawlens_refflow.f32", robs.asSequence().flatMap { r -> r.asSequence().flatMap { it.asSequence() } }, (n - 1) * robs[0].size * robs[0][0].size)

        // (e) Per-level flows (after ICA), coarse to fine, frames 1 and 6.
        for (fi in listOf(1, 6)) {
            alignLevelsPerLevel(refInit, Grey.computeGreyImages(frames[fi], "FFT"), loop).forEachIndexed { i, f ->
                write("klvl_f${fi}_$i.f32", f.asSequence().flatMap { r -> r.asSequence().flatMap { it.asSequence() } }, f.size * f[0].size * 2)
                println("C9 level f$fi $i ${f.size}x${f[0].size}")
            }
        }

        // (f) Replay pyramid level p=2 (31x31 tiles) from the reference's own p=1 flow:
        //     dumps after block matching and after ICA, isolating the first diverging level.
        for (fi in listOf(1, 6)) {
            val src = File(dir, "lvl_f${fi}_3_ica_l2.f32")
            if (!src.exists()) continue
            val sb = ByteBuffer.wrap(src.readBytes()).order(ByteOrder.LITTLE_ENDIAN).asFloatBuffer()
            val p = 2; val c = loop.factors.size - p - 1
            val prev = Array(refInit.tiledSpectra[1].size) { Array(refInit.tiledSpectra[1][0].size) { FloatArray(2).also { sb.get(it) } } }
            val nTy = refInit.tiledSpectra[p].size; val nTx = refInit.tiledSpectra[p][0].size
            val cur = upscaleLvl(prev, intArrayOf(nTy, nTx), c, loop)
            val mov = Resample.buildGaussianPyramid(Grey.computeGreyImages(frames[fi], "FFT"), loop.factors)
            alignLvl(refInit.refPyramid[p], mov[p], cur, c, loop.tileSizes, loop.searchRadii, loop.metrics)
            write("kreplay_f${fi}_bm.f32", cur.asSequence().flatMap { r -> r.asSequence().flatMap { it.asSequence() } }, nTy * nTx * 2)
            Ica.alignLvl(refInit.refPyramid[p], refInit.gradXPyramid[p], refInit.gradYPyramid[p], refInit.hessians[p], mov[p], cur,
                tileSize = loop.tileSizes[c], nIter = loop.nIter, clip = loop.icaClip, searchRadius = loop.searchRadii[c])
            write("kreplay_f${fi}_ica.f32", cur.asSequence().flatMap { r -> r.asSequence().flatMap { it.asSequence() } }, nTy * nTx * 2)
        }

        // (d) Kernel covariances per frame ([n][gH][gW][2][2]), reference first as in the engine.
        val covs = (0 until n).map { EndToEnd.frameKernels(frames[it], config) }
        write("cov_rawlens.f32", covs.asSequence().flatMap { c -> c.asSequence().flatMap { r -> r.asSequence().flatMap { m -> m.asSequence().flatMap { it.asSequence() } } } },
            n * covs[0].size * covs[0][0].size * 4)

        // (c) Full engine, own flows and injected reference flows.
        writeImage("out_rawlens.f32", EndToEnd.run(frames[0], (1 until n).map { k -> { frames[k] } }, config))
        var next = 0
        writeImage("out_rawlens_refflow.f32", EndToEnd.run(frames[0], (1 until n).map { k -> { frames[k] } }, config) { refFlows[next++] })
        println("C9: wrote outputs to $dir")
    }
}
