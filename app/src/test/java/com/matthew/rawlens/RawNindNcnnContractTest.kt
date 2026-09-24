// SPDX-FileCopyrightText: 2026 RawLens contributors
// SPDX-License-Identifier: GPL-3.0-or-later
package com.matthew.rawlens

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File

/**
 * Guards the blob-name contract between the pnnx-exported RawNIND graphs
 * and the native inference code (app/src/main/cpp/ncnnMl.cpp).
 *
 * Two model variants share the native entry points (each handle
 * auto-detects its variant from its own .param):
 * - tiny (`models/rawnind_tiny.ncnn.param`): 5ch+sigma in, 4ch packed out.
 * - bayer (`models/rawnind_bayer.ncnn.param`): 4ch packed in, 3ch camRGB
 *   out at 2x (PixelShuffle tail).
 *
 * pnnx renames the ONNX `input`/`output` tensors to `in0`/`out0`; native
 * code that still uses the ONNX names fails on device with
 * `find_blob_index_by_name input failed` / `rawnind tile input failed`.
 * This test cross-checks the blob literals in the rawnind functions
 * against the shipped param files, so a re-export or a bad merge breaks
 * the unit tests instead of the shutter path.
 */
class RawNindNcnnContractTest {
    private fun paramBlobs(paramFile: String): Pair<String, Set<String>> {
        val param = File("src/main/assets/models/$paramFile")
        // Model blobs are not shipped (see docs: missing-model is a skip,
        // not a failure — runtime falls back to the plain path).
        assumeTrue("model not shipped: " + param.path, param.isFile)
        val bin = param.path.replace(".param", ".bin")
        assumeTrue("model bin not shipped: $bin", File(bin).isFile)
        val lines = param.readLines().map { it.trim() }.filter { it.isNotEmpty() }
        assertTrue("truncated param file", lines.size > 2)
        // Line format: "<Op> <name> <#in> <#out> <inputs...> <outputs...> [...]"
        // (trailing layer attributes must not be mistaken for blobs).
        fun outputsOf(line: String): List<String> {
            val t = line.split(Regex("\\s+"))
            val numIn = t[2].toInt()
            val numOut = t[3].toInt()
            return t.subList(4 + numIn, 4 + numIn + numOut)
        }
        val inputLine = lines.first { it.startsWith("Input ") }
        assertEquals(listOf("in0"), outputsOf(inputLine))
        val outputBlob = outputsOf(lines.last())
        assertEquals(listOf("out0"), outputBlob)
        val all = lines.drop(2).flatMap(::outputsOf).toSet()
        return Pair(lines.joinToString("\n"), all)
    }

    private fun rawnindBlobLiterals(): List<String> {
        val cpp = File("src/main/cpp/ncnnMl.cpp").readText()
        // FlowNet/KernelNet live above; everything from RawNindCtx on is the
        // denoiser (full-res + tiled paths share the same blob names, and
        // both variants use the same entry points).
        val region = cpp.substring(cpp.indexOf("struct RawNindCtx"))
        return Regex("""ex\.(input|extract)\("([^"]+)"""")
            .findAll(region).map { it.groupValues[2] }.toList()
    }

    @Test fun rawnindBlobsMatchExportedParam() {
        val (_, blobs) = paramBlobs("rawnind_tiny.ncnn.param")
        val literals = rawnindBlobLiterals()
        assertTrue("no rawnind blob literals found", literals.isNotEmpty())
        for (lit in literals) {
            assertTrue("native blob \"$lit\" missing from rawnind_tiny.ncnn.param", lit in blobs)
        }
        assertTrue("native code never feeds in0", "in0" in literals)
        assertTrue("native code never reads out0", "out0" in literals)
    }

    @Test fun bayerBlobsMatchExportedParam() {
        val (text, blobs) = paramBlobs("rawnind_bayer.ncnn.param")
        val literals = rawnindBlobLiterals()
        assertTrue("no rawnind blob literals found", literals.isNotEmpty())
        for (lit in literals) {
            assertTrue("native blob \"$lit\" missing from rawnind_bayer.ncnn.param", lit in blobs)
        }
        // Bayer variant markers: 4ch first convolution (6=1152 = 32*4*9)
        // and a PixelShuffle (DepthToSpace) 2x upsampling tail.
        assertTrue("bayer param missing 4ch first conv",
            text.contains("6=1152"))
        assertTrue("bayer param missing PixelShuffle tail",
            text.contains("PixelShuffle"))
    }

    @Test fun tinyParamIsLegacyContract() {
        val (text, _) = paramBlobs("rawnind_tiny.ncnn.param")
        // Legacy tiny markers: 5ch first convolution (6=1440 = 32*5*9) and
        // the sigma-stripping Split/Crop head; no upsampling tail.
        assertTrue("tiny param missing 5ch first conv",
            text.contains("6=1440"))
        assertTrue("tiny param should not contain PixelShuffle",
            !text.contains("PixelShuffle"))
    }

    @Test fun staleOnnxBlobNamesAreGone() {
        // The pre-pnnx names must not appear as blob literals anywhere: every
        // shipped param graph (flownet, kernelnet, rawnind) uses inN/outN.
        val cpp = File("src/main/cpp/ncnnMl.cpp").readText()
        val literals = Regex("""ex\.(input|extract)\("([^"]+)"""")
            .findAll(cpp).map { it.groupValues[2] }.toSet()
        assertTrue("stale ONNX blob name in use", "input" !in literals && "output" !in literals)
    }
}
