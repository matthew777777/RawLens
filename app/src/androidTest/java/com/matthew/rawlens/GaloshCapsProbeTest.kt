package com.matthew.rawlens

import android.util.Log
import androidx.test.platform.app.InstrumentationRegistry
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Phase 1 gate: the GALOSH Vulkan contract (Vulkan 1.2 + float16 arithmetic +
 * 16-bit storage access) plus every manifest SPIR-V module must create on the
 * real driver. Writes caps.json + report.txt to <external-files>/galosh-probe/
 * (adb pull, no permissions needed).
 */
class GaloshCapsProbeTest {
    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private val outputDir = File(context.getExternalFilesDir(null), "galosh-probe").apply { mkdirs() }
    private val report = StringBuilder()
    private fun log(s: String) {
        synchronized(report) { report.appendLine(s) }
        Log.i("GALOSHPROBE", s)
    }

    @Test
    fun probeCapsAndLoadShaders() {
        try {
            val capsJson = GaloshVulkan.probeCapsJson()
            File(outputDir, "caps.json").writeText(capsJson)
            log("caps.json bytes=${capsJson.length}")
            log("caps=$capsJson")
            val caps = JSONObject(capsJson)
            assertTrue("probe error: $capsJson", !caps.has("error"))
            log("loader=${caps.getString("loaderVersion")} " +
                "instance=${caps.getString("instanceVersion")} " +
                "devices=${caps.getInt("deviceCount")}")
            assertTrue("no Vulkan devices", caps.getInt("deviceCount") >= 1)
            val dev = caps.getJSONArray("devices").getJSONObject(0)
            log("device0 name=${dev.getString("name")} api=${dev.getString("apiVersion")} " +
                "vendor=${dev.getInt("vendorId")} type=${dev.getInt("deviceType")}")
            val feats = dev.getJSONObject("features")
            val sg = dev.getJSONObject("subgroup")
            log("shaderFloat16=${feats.getBoolean("shaderFloat16")} " +
                "storage16=${feats.getBoolean("storageBuffer16BitAccess")} " +
                "subgroupSize=${sg.getInt("size")} " +
                "sgControlExt=${feats.getBoolean("subgroupSizeControlExt")} " +
                "sgControl=${feats.getBoolean("subgroupSizeControl")}")
            // GALOSH V2 contract gates (docs/galosh-phase0-spec.md).
            val parts = dev.getString("apiVersion").split(".").map { it.toInt() }
            assertTrue("need Vulkan 1.2+, got ${dev.getString("apiVersion")}",
                parts[0] > 1 || (parts[0] == 1 && parts[1] >= 2))
            assertTrue("need storageBuffer16BitAccess", feats.getBoolean("storageBuffer16BitAccess"))
            assertTrue("need shaderFloat16", feats.getBoolean("shaderFloat16"))
            // Shader scaffold: every manifest module must create on this driver.
            val handle = GaloshVulkan.init(context.assets)
            try {
                val expected = GaloshVulkan.expectedShaderCount()
                val loaded = GaloshVulkan.loadedShaderCount(handle)
                log("shaders loaded=$loaded expected=$expected")
                assertEquals(43, expected)
                // All kernels except possibly the SG-32 one (skipped where the
                // device cannot pin subgroup 32, e.g. this Mali's fixed 16).
                assertTrue("loaded=$loaded expected=$expected",
                    loaded == expected || loaded == expected - 1)
            } finally {
                GaloshVulkan.release(handle)
            }
            log("PASS probeCapsAndLoadShaders")
        } finally {
            File(outputDir, "report.txt").writeText(report.toString())
        }
    }
}
