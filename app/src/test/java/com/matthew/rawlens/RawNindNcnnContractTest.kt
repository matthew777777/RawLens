// SPDX-FileCopyrightText: 2026 RawLens contributors
// SPDX-License-Identifier: GPL-3.0-or-later
package com.matthew.rawlens

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Guards the blob-name contract between the pnnx-exported RawNIND-tiny
 * graph and the native inference code (app/src/main/cpp/ncnnMl.cpp).
 *
 * pnnx renames the ONNX `input`/`output` tensors to `in0`/`out0`; native
 * code that still uses the ONNX names fails on device with
 * `find_blob_index_by_name input failed` / `rawnind tile input failed`.
 * This test cross-checks the blob literals in the rawnind functions
 * against the shipped param file, so a re-export or a bad merge breaks
 * the unit tests instead of the shutter path.
 */
class RawNindNcnnContractTest {
    private fun paramBlobs(): Pair<String, Set<String>> {
        val param = File("src/main/assets/models/rawnind_tiny.ncnn.param")
        assertTrue("missing " + param.path, param.isFile)
        assertTrue("missing model bin", File("src/main/assets/models/rawnind_tiny.ncnn.bin").isFile)
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
        return Pair(lines[0], all)
    }

    private fun rawnindBlobLiterals(): List<String> {
        val cpp = File("src/main/cpp/ncnnMl.cpp").readText()
        // FlowNet/KernelNet live above; everything from RawNindCtx on is the
        // denoiser (full-res + tiled paths share the same blob names).
        val region = cpp.substring(cpp.indexOf("struct RawNindCtx"))
        return Regex("""ex\.(input|extract)\("([^"]+)"""")
            .findAll(region).map { it.groupValues[2] }.toList()
    }

    @Test fun rawnindBlobsMatchExportedParam() {
        val (_, blobs) = paramBlobs()
        val literals = rawnindBlobLiterals()
        assertTrue("no rawnind blob literals found", literals.isNotEmpty())
        for (lit in literals) {
            assertTrue("native blob \"$lit\" missing from rawnind_tiny.ncnn.param", lit in blobs)
        }
        assertTrue("native code never feeds in0", "in0" in literals)
        assertTrue("native code never reads out0", "out0" in literals)
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
