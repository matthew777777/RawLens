package com.rawlens.srpipeline.parity

import java.io.File
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Dual parity: per-stage, line-by-line Kotlin-vs-CPU and Kotlin-vs-GPU.
 *
 * - CPU reference: `parity/fixtures/` (gen_fixtures.py, SEED=0).
 * - GPU reference: `parity/gpu_reference/gpu_<name>.npy`
 *   (gen_gpu_reference.py, SAME fixture inputs; missing files report
 *   `GPU-SKIP`, never fail).
 * - Envelopes: `parity/envelopes.json`, read-only. A stage passes on the
 *   envelope; bit-exactness (`exact=`) is reported separately and never
 *   gates the pass. Envelopes are never weakened here.
 *
 * Gating rule: only Kotlin-vs-CPU feeds the pass/fail verdict (that is the
 * port's contract). Kotlin-vs-GPU is diagnostic triangulation: envelopes
 * bound CPU-vs-GPU on the Vulkan report's inputs, so by the triangle
 * inequality KG can exceed them by documented GPU-side precision classes
 * (1-ulp decimate/divide, FMA-contracted Hessian) even when Kotlin==CPU
 * bit-exactly. KG lines still print exact/maxAbs/maxRel/pass against the
 * same envelope for the record, but never fail the test.
 *
 * One stdout line per (stage, output); the test fails iff any wired
 * Kotlin-vs-CPU comparison exceeds its envelope.
 */
class ParityDualTest {
    private val envelopes: Map<String, Envelope> by lazy {
        EnvelopeTable.load(parityFile("envelopes.json"))
    }
    private val fixDir: File by lazy { parityFile("fixtures") }
    private val gpuDir: File? by lazy {
        try {
            parityRefDir("gpu_reference")
        } catch (e: IllegalStateException) {
            null
        }
    }

    @Test fun kotlinVsCpuAndGpu() {
        val failures = mutableListOf<String>()
        var gpuSkips = 0
        var gpuCompares = 0
        // STAGES (synthetic fixtures) plus CROP_STAGES (real-burst
        // 512-crops, the proof that counts). Crop specs share their base
        // stage envelope; absOnly mirrors ParityHarness semantics.
        for (spec in STAGES + CROP_STAGES) {
            val env = envelopes[spec.stage]
                ?: envelopes[spec.stage.removeSuffix("_crop")]
                ?: error("no envelope for stage ${spec.stage}")
            if (spec.implClass == null) {
                println("dual SKIP: ${spec.stage}: ${spec.skipReason}")
                continue
            }
            val inputs = spec.inputs.associateWith {
                NpyReader.load(File(fixDir, "$it.npy"))
            }
            val cpuExp = spec.expected.map {
                NpyReader.load(File(fixDir, "$it.npy"))
            }
            val got = runKotlinPort(spec, inputs)
            for (i in spec.expected.indices) {
                val name = spec.expected[i]
                val exp = cpuExp[i]
                val out = got[i]
                if (!out.shape.contentEquals(exp.shape)) {
                    println("dual: ${spec.stage}/$name SHAPE-MISMATCH kotlin=${out.shape.toList()} fixture=${exp.shape.toList()}")
                    failures += "${spec.stage}/$name Kotlin shape ${out.shape.toList()} != fixture ${exp.shape.toList()}"
                    continue
                }
                val exactKC = isExact(out.data, exp.data)
                val absKC = maxAbsDiff(out.data, exp.data)
                val relKC = maxRelDiff(out.data, exp.data)
                val passKC = absKC <= env.maxAbs &&
                    (spec.absOnly || env.maxRel == null || relKC <= env.maxRel)
                val gpuFile = gpuDir?.let { File(it, "gpu_$name.npy") }
                val gpuNote: String
                if (gpuFile != null && gpuFile.exists()) {
                    gpuCompares++
                    val g = NpyReader.load(gpuFile)
                    if (!g.shape.contentEquals(out.shape)) {
                        // Diagnostic only (see gating rule above): a GPU
                        // shape mismatch cannot fail the Kotlin port.
                        gpuNote = "GPU-SHAPE-MISMATCH gpu=${g.shape.toList()} " +
                            "kotlin=${out.shape.toList()}"
                    } else {
                        val exactKG = isExact(out.data, g.data)
                        val absKG = maxAbsDiff(out.data, g.data)
                        val relKG = maxRelDiff(out.data, g.data)
                        val passKG = absKG <= env.maxAbs &&
                            (env.maxRel == null || relKG <= env.maxRel)
                        // Diagnostic only (see gating rule above).
                        gpuNote = "exact=$exactKG maxAbs=$absKG " +
                            "maxRel=$relKG pass=$passKG"
                    }
                } else {
                    gpuSkips++
                    gpuNote = "GPU-SKIP (no gpu_$name.npy)"
                }
                println(
                    "dual: ${spec.stage}/$name " +
                        "KC exact=$exactKC maxAbs=$absKC maxRel=$relKC " +
                        "pass=$passKC (env abs=${env.maxAbs} " +
                        "rel=${env.maxRel}) | KG $gpuNote",
                )
                if (!passKC) {
                    val why = mutableListOf<String>()
                    if (absKC > env.maxAbs) why += "abs $absKC > ${env.maxAbs}"
                    if (env.maxRel != null && relKC > env.maxRel) {
                        why += "rel $relKC > ${env.maxRel}"
                    }
                    failures += "${spec.stage}/$name Kotlin-vs-CPU " +
                        "exceeds envelope (${why.joinToString("; ")})"
                }
            }
        }
        println("dual: GPU comparisons=$gpuCompares skips=$gpuSkips")
        assertTrue(
            "dual parity failures:\n" + failures.joinToString("\n"),
            failures.isEmpty(),
        )
    }
}
