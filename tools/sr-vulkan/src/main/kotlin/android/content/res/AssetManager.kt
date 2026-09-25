// SPDX-FileCopyrightText: 2026 RawLens contributors
// SPDX-License-Identifier: GPL-3.0-or-later
//
// DESKTOP HOST SHIM (sr-vulkan only). Directory-backed AssetManager: open()
// resolves against [rootDir], falling back to the classpath for packaged
// resources (SPIR-V, manifest ride inside the jar; model files live in the
// directory because native code needs real file paths). The host ncnn JNI
// bridge reads model files through this (same nativeCreate signature as
// Android); the Vulkan program cache reads shaders through it (same
// constructor as Android).
package android.content.res

import java.io.File
import java.io.FileNotFoundException
import java.io.InputStream

class AssetManager(val rootDir: File) {
    fun open(fileName: String): InputStream {
        val file = File(rootDir, fileName)
        if (file.isFile) return file.inputStream()
        return AssetManager::class.java.getResourceAsStream("/$fileName")
            ?: throw FileNotFoundException("asset $fileName missing (root=$rootDir)")
    }
}
