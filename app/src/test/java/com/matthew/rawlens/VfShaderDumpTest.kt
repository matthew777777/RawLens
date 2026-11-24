// SPDX-License-Identifier: GPL-3.0-or-later
package com.matthew.rawlens

import java.io.File
import org.junit.Test

/** Writes the runtime VF GLES shaders for offline compiler analysis (no-op unless -Dvf.dump=dir). */
class VfShaderDumpTest {
    @Test fun dump() {
        val dir = System.getProperty("vf.dump") ?: System.getenv("VF_DUMP") ?: return
        File(dir).mkdirs()
        File(dir, "vf_gpu.frag").writeText(VfGpuImport.gpuFragmentShader())
        File(dir, "vf_cpu_es3.frag").writeText(VfGpuImport.cpuFragmentShader(es3 = true))
        File(dir, "bgu_slice.frag").writeText(BguSlice.fragmentShader())
        File(dir, "vf_cpu.frag").writeText(VfGpuImport.cpuFragmentShader())
    }
}
