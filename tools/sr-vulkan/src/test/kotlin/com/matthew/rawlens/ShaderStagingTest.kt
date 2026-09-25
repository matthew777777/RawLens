// SPDX-FileCopyrightText: 2026 RawLens contributors
// SPDX-License-Identifier: GPL-3.0-or-later
package com.matthew.rawlens

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pins the Python transform's sliced-dispatch preprocessing to the exact
 * phone code: staged ESSL must equal RawSrGpuScheduling.shaderSource
 * applied to the version-prepended original (modulo the sanctioned
 * 310 es -> 450 version line). Any drift in either side fails here.
 */
class ShaderStagingTest {
    @Test
    fun stagedEqualsShaderSource() {
        val shaders = File("src/main/resources/shaders")
        val staged = File("build/spirv")
        assertTrue("run :tools:sr-vulkan:compileShaders first", staged.isDirectory)
        var checked = 0
        for (sub in listOf("rawsr", "raw")) {
            for (file in File(shaders, sub).listFiles { f -> f.extension == "glsl" }.orEmpty()) {
                val expected = RawSrGpuScheduling.shaderSource("#version 450\n" + file.readText())
                val actual = File(staged, "${file.nameWithoutExtension}.staged.glsl")
                assertTrue("missing staged output for ${file.name}", actual.isFile)
                assertEquals("staged drift: ${file.name}", expected, actual.readText())
                checked++
            }
        }
        assertTrue("no shaders checked", checked == 20)
    }

    @Test
    fun quadShadersAreUnsliced() {
        val shaders = File("src/main/resources/shaders")
        val staged = File("build/spirv")
        val common = File(shaders, "quad/common.glsl").readText()
        for (name in listOf("color.glsl", "green.glsl")) {
            val body = File(shaders, "quad/$name").readText().replace("#import quad", common)
            val actual = File(staged, "${name.removeSuffix(".glsl")}.staged.glsl")
            assertTrue("missing staged output for $name", actual.isFile)
            assertEquals("staged drift: $name", "#version 450\n$body", actual.readText())
        }
    }
}
