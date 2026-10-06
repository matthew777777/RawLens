// SPDX-FileCopyrightText: 2026 RawLens contributors
// SPDX-License-Identifier: GPL-3.0-or-later
package com.matthew.srdesktop

import java.io.File

/** Shared CLI options for the SR desktop tools (stdlib-only arg parsing). */
data class Options(
    val inDir: File,
    val outDir: File,
    val ref: Int,
    val cacheDir: File,
    val crop: IntArray?,
    val limit: Int,
    val files: List<File>?,
    val backend: String,
    val kernelnet: Boolean,
    val kernelnetSigmaMpy: Float,
    val kernelnetKernelMpy: Float,
    val kernelnetMajorMpy: Float,
    val kernelnetMinSigma: Float,
    val referenceOnly: Boolean,
    val noKernelnet: Boolean,
    val noInpaint: Boolean,
    val noCfl: Boolean,
    val zipperGates: Boolean,
    val dumpFields: File?,
    val cpuGatCov: Boolean,
    val kernelPreset: String,
    val kDetail: Double?,
    val flatSigma: Double?,
    val detailFloor: Double?,
    val flowRegularizeSigma: Double?,
    val captureId: Long,
    val mosaicScale: String,
    val linearScale: String,
    /** HDR+ N-cap override for the merge set (default: shared policy, 8). */
    val maxFrames: Int?,
    /** Merge-time support-gate overrides (default: shared policy). */
    val minReliableFrac: Double?,
    val minMeanR: Double?,
    val minSupportFrac: Double?,
    /** Expected bracket stops "−3,−2,−1,0,1" for EV coverage logging (desktop parity probe). */
    val bracketStops: String?,
    /** R/B kernel widening for the chroma latch guard (default 2.0, 1.0 = reference-verbatim). */
    val chromaMpy: Double?
) {
    companion object {
        fun parse(args: Array<String>, tool: String): Options {
            fun value(flag: String): String? {
                val i = args.indexOf(flag)
                return if (i >= 0 && i + 1 < args.size) args[i + 1] else null
            }
            val inDir = value("--in")?.let(::File)
                ?: fail(tool, "missing --in <dng-dir>")
            val outDir = value("--out")?.let(::File)
                ?: fail(tool, "missing --out <dir>")
            val crop = value("--crop")?.split(",")?.map { it.toInt() }?.toIntArray()?.also {
                require(it.size == 4) { "Crop must be x,y,w,h" }
            }
            return Options(
                inDir = inDir,
                outDir = outDir,
                ref = value("--ref")?.toInt() ?: -1,
                cacheDir = value("--cache")?.let(::File) ?: File(outDir, "cache"),
                crop = crop,
                limit = value("--limit")?.toInt() ?: 30,
                files = value("--files")?.split(",")?.map(::File),
                backend = value("--backend") ?: "cpu",
                kernelnet = args.contains("--kernelnet"),
                kernelnetSigmaMpy = value("--kernelnet-sigma-mpy")?.toFloat() ?: 1.0f,
                kernelnetKernelMpy = value("--kernelnet-kernel-mpy")?.toFloat()
                    ?: com.matthew.rawlens.RawSrKernelNetAniso.DEFAULT_KERNEL_SIGMA_MPY,
                kernelnetMajorMpy = value("--kernelnet-major-mpy")?.toFloat()
                    ?: com.matthew.rawlens.RawSrKernelNetAniso.DEFAULT_KERNEL_SIGMA_MAJOR_MPY,
                kernelnetMinSigma = value("--kernelnet-min-sigma")?.toFloat()
                    ?: com.matthew.rawlens.RawSrKernelNetAniso.DEFAULT_MIN_SIGMA,
                referenceOnly = args.contains("--reference-only"),
                noKernelnet = args.contains("--no-kernelnet"),
                noInpaint = args.contains("--no-inpaint"),
                noCfl = args.contains("--no-cfl"),
                zipperGates = args.contains("--zipper-gates"),
                dumpFields = value("--dump-fields")?.let(::File),
                cpuGatCov = args.contains("--cpu-gat-cov"),
                kernelPreset = value("--kernel-preset") ?: "reference",
                kDetail = value("--k-detail")?.let {
                    runCatching { it.toDouble() }.getOrNull() ?: fail(tool, "bad --k-detail $it")
                },
                flatSigma = value("--flat-sigma")?.let {
                    runCatching { it.toDouble() }.getOrNull() ?: fail(tool, "bad --flat-sigma $it")
                },
                detailFloor = value("--detail-floor")?.let {
                    runCatching { it.toDouble() }.getOrNull() ?: fail(tool, "bad --detail-floor $it")
                },
                flowRegularizeSigma = value("--flow-regularize-sigma")?.let {
                    runCatching { it.toDouble() }.getOrNull() ?: fail(tool, "bad --flow-regularize-sigma $it")
                },
                captureId = value("--capture-id")?.toLong() ?: System.currentTimeMillis(),
                mosaicScale = value("--mosaic-scale") ?: "sr",
                linearScale = value("--linear-scale") ?: "1x",
                maxFrames = value("--max-frames")?.let {
                    runCatching { it.toInt() }.getOrNull() ?: fail(tool, "bad --max-frames $it")
                },
                minReliableFrac = value("--min-reliable-frac")?.let {
                    runCatching { it.toDouble() }.getOrNull() ?: fail(tool, "bad --min-reliable-frac $it")
                },
                minMeanR = value("--min-mean-r")?.let {
                    runCatching { it.toDouble() }.getOrNull() ?: fail(tool, "bad --min-mean-r $it")
                },
                minSupportFrac = value("--min-support-frac")?.let {
                    runCatching { it.toDouble() }.getOrNull() ?: fail(tool, "bad --min-support-frac $it")
                },
                bracketStops = value("--bracket-stops"),
                chromaMpy = value("--chroma-mpy")?.let {
                    runCatching { it.toDouble() }.getOrNull() ?: fail(tool, "bad --chroma-mpy $it")
                }
            )
        }

        /** Builds the shared merge-time rejection policy from CLI overrides (nulls keep defaults). */
        fun rejectionPolicy(opts: Options): com.matthew.rawlens.RawSrFrameRejection.Policy {
            val base = com.matthew.rawlens.RawSrFrameRejection.Policy()
            return base.copy(
                maxMergeFrames = opts.maxFrames ?: base.maxMergeFrames,
                minReliableFraction = opts.minReliableFrac ?: base.minReliableFraction,
                minMeanRobustness = opts.minMeanR ?: base.minMeanRobustness,
                minSupportFraction = opts.minSupportFrac ?: base.minSupportFraction
            )
        }

        /**
         * Raymerge-style EV coverage probe for bracketed DNG dirs: logs one
         * F-line per frame (EV relative to [ref], rounded to stops) plus the
         * all-EVs-covered summary against [--bracket-stops]. Logging only —
         * the SR merge itself stays constant-exposure and rejects mixed-EV
         * frames through the shared gates.
         */
        fun reportBracketCoverage(
            tool: String,
            exposures: List<Double?>,
            ref: Int,
            bracketStops: String?
        ) {
            if (bracketStops == null) return
            val stops = runCatching {
                bracketStops.split(",").map { it.trim().toInt() }.toIntArray()
            }.getOrNull()
            if (stops == null) {
                println("$tool: WARNING ignoring unparseable --bracket-stops \"$bracketStops\"")
                return
            }
            val plan = runCatching { com.matthew.rawlens.HdrBracketConfig.plan(stops) }.getOrNull()
            if (plan == null) {
                println("$tool: WARNING ignoring invalid --bracket-stops \"$bracketStops\"")
                return
            }
            val ev = com.matthew.rawlens.HdrBracketConfig.evRelativeToReference(exposures, ref)
            val keptPerStop = mutableMapOf<Int, Int>()
            exposures.indices.forEach { i ->
                val rounded = ev[i]?.let { kotlin.math.round(it).toInt() }
                if (rounded != null) keptPerStop[rounded] = (keptPerStop[rounded] ?: 0) + 1
                val evTag = if (ev[i] == null) "?" else "%+.2f".format(ev[i])
                println("$tool: " + com.matthew.rawlens.HdrBracketConfig.frameLogLine(
                    i, rounded ?: 0, "Loaded", "ev=$evTag", isReference = i == ref))
            }
            println("$tool: " + com.matthew.rawlens.HdrBracketConfig.coverageSummary(plan, keptPerStop))
        }

        private fun fail(tool: String, message: String): Nothing {
            System.err.println("$tool: $message")
            System.err.println(
                "usage: $tool --in <dng-dir> --out <dir> [--ref N] [--cache DIR]" +
                    " [--crop x,y,w,h] [--limit N] [--files a.dng,b.dng]" +
                    " [--backend cpu|vulkan] [--kernelnet] [--kernelnet-sigma-mpy X]" +
                    " [--kernelnet-kernel-mpy X] [--kernelnet-major-mpy X] [--kernelnet-min-sigma X]" +
                    " [--no-kernelnet] [--zipper-gates]" +
                    " [--no-inpaint] [--no-cfl] [--kernel-preset reference|decoupled_sharp]" +
                    " [--k-detail X] [--flat-sigma X] [--detail-floor X] [--flow-regularize-sigma X] [--capture-id ID]" +
                    " [--mosaic-scale native|sr] [--linear-scale 1x|sr]" +
                    " [--max-frames N] [--min-reliable-frac X] [--min-mean-r X]" +
                    " [--min-support-frac X] [--bracket-stops -3,-2,-1,0,1]"
            )
            System.err.println("       $tool vkcheck  (prove the Vulkan driver + SR shader modules)")
            throw IllegalArgumentException(message)
        }
    }
}
