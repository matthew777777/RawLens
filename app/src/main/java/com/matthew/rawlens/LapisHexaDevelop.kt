// SPDX-License-Identifier: GPL-3.0-or-later
package com.matthew.rawlens

import android.content.Context
import org.json.JSONObject
import java.io.File
import java.io.RandomAccessFile
import java.nio.ByteOrder
import java.nio.channels.FileChannel

/** Develop the worker's already demosaiced camera RGB through the RGB-merge JPEG tail. */
internal object LapisHexaDevelop {
    fun save(context: Context, job: File, width: Int, height: Int, captureTime: Long,
             jpeg: JpegOutputSettings, denoise: DenoiseSettings): String {
        val sidecar = JSONObject(File(job, "develop-metadata.json").readText())
        val metadata = OfflineBurstMetadata.synthesize(
            sidecar.getJSONObject("container").toString(), sidecar.getJSONObject("frame").toString(), 0, "0")
        require(metadata.imageWidth == width && metadata.imageHeight == height)
        val source = File(job, "develop-rgb16.bin")
        val samples = Math.multiplyExact(Math.multiplyExact(width, height), 3)
        require(source.length() == samples.toLong() * 2)
        // mmap avoids charging the ~600 MB float input against ART's heap limit.
        val floatsFile = File(job, "develop-rgb32.tmp")
        try {
            RandomAccessFile(source, "r").use { input ->
                RandomAccessFile(floatsFile, "rw").use { output ->
                    output.setLength(samples.toLong() * 4)
                    val shorts = input.channel.map(FileChannel.MapMode.READ_ONLY, 0, source.length())
                        .order(ByteOrder.LITTLE_ENDIAN).asShortBuffer()
                    val rgb = output.channel.map(FileChannel.MapMode.READ_WRITE, 0, output.length())
                        .order(ByteOrder.nativeOrder())
                    val values = rgb.asFloatBuffer()
                    for (i in 0 until samples) values.put(i, (shorts.get(i).toInt() and 0xffff) / 65535f)
                    val developer = RawDevelopmentCoordinator(context)
                    try {
                        val developed = developer.developMfsrJpeg(rgb, width, height, metadata,
                            RawDevelopmentSettings(denoise = denoise), jpeg)
                        try {
                            return JpegSaver(context).save(developed, metadata, null,
                                captureTimeMillis = captureTime, typeSuffix = "200TO50MP",
                                description = LapisHexaDng.ALGORITHM + "; single-frame linear camera RGB")
                        } finally {
                            developed.bitmap.recycle()
                            developed.gainmap?.recycle()
                        }
                    } finally { developer.close() }
                }
            }
        } finally { floatsFile.delete() }
    }
}
