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
    val noKernelnet: Boolean,
    val captureId: Long
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
                noKernelnet = args.contains("--no-kernelnet"),
                captureId = value("--capture-id")?.toLong() ?: System.currentTimeMillis()
            )
        }

        private fun fail(tool: String, message: String): Nothing {
            System.err.println("$tool: $message")
            System.err.println(
                "usage: $tool --in <dng-dir> --out <dir> [--ref N] [--cache DIR]" +
                    " [--crop x,y,w,h] [--limit N] [--files a.dng,b.dng]" +
                    " [--no-kernelnet] [--capture-id ID]"
            )
            System.err.println("       $tool vkcheck  (prove the Vulkan driver + SR shader modules)")
            throw IllegalArgumentException(message)
        }
    }
}
