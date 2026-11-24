// SPDX-License-Identifier: GPL-3.0-or-later
package com.matthew.rawlens

import android.content.Context
import org.json.JSONObject
import java.io.File
import java.io.RandomAccessFile
import java.nio.ByteOrder
import java.nio.channels.FileChannel

/** Develop the remosaiced Bayer through the existing single-frame RAW JPEG path. */
internal object LapisHexaDevelop {
    fun save(context: Context, job: File, width: Int, height: Int, captureTime: Long,
             jpeg: JpegOutputSettings, denoise: DenoiseSettings): String {
        val sidecar = JSONObject(File(job, "develop-metadata.json").readText())
        val metadata = OfflineBurstMetadata.synthesize(
            sidecar.getJSONObject("container").toString(), sidecar.getJSONObject("frame").toString(), 0, "0")
        require(metadata.imageWidth == width && metadata.imageHeight == height)
        val source = File(job, "develop-bayer16.bin")
        require(source.length() == width.toLong() * height * 2)
        RandomAccessFile(source, "r").use { input ->
            val bayer = input.channel.map(FileChannel.MapMode.READ_ONLY, 0, source.length())
                .order(ByteOrder.LITTLE_ENDIAN)
            val developer = RawDevelopmentCoordinator(context)
            try {
                val developed = developer.developJpeg(bayer, metadata,
                    RawDevelopmentSettings(denoise = denoise), jpeg)
                try {
                    return JpegSaver(context).save(developed, metadata, null,
                        captureTimeMillis = captureTime, typeSuffix = "200TO50MP",
                        description = LapisHexaDng.ALGORITHM + "; 200 MP grouped RAW -> 50 MP Bayer CFA; " +
                            "phase fit=" + File(job, "phase-fit-request").isFile())
                } finally { developed.bitmap.recycle(); developed.gainmap?.recycle() }
            } finally { developer.close() }
        }
    }
}
