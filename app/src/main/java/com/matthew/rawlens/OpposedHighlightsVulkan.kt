// SPDX-FileCopyrightText: 2026 RawLens contributors
// SPDX-License-Identifier: GPL-3.0-or-later
package com.matthew.rawlens

import android.content.res.AssetManager
import java.nio.ByteBuffer
import java.nio.ByteOrder

/** Four ordered Vulkan dispatches; only row statistics return to the host between passes.
 * The borrowed device is confined to the coordinator's serial develop worker.
 */
internal object OpposedHighlightsVulkan {
    data class Result(val cfa: UnpackedRawCfa, val rows: FloatArray, val chroma: FloatArray, val roi: IntArray = IntArray(4),
                      val peakImageBytes: Long = 0, val phaseMs: DoubleArray = DoubleArray(4))
    fun run(vk: VulkanHub.Handle, assets: AssetManager, cfa: UnpackedRawCfa, shading: LensShadingModel? = null): Result =
        Session(vk, assets).use { it.run(cfa, shading) }

    /** Retains the compiled pipeline across photos; images remain per-photo. Serial worker only. */
    class Session(private val vk: VulkanHub.Handle, assets: AssetManager) : java.io.Closeable {
        private val module = vk.loadModule(assets.open("spirv/opposed.spv").use { it.readBytes() })
        private var pipeline = try {
            vk.createPipeline(module, 5, 128, intArrayOf(0, 1, 3, 4, 6, 7, 8), intArrayOf(1, 1, 1, 1, 0, 0, 0))
        } catch (failure: Throwable) {
            vk.destroyModule(module)
            throw failure
        }
        override fun close() {
            if (pipeline != 0L) {
                vk.destroyPipeline(pipeline)
                vk.destroyModule(module)
                pipeline = 0L
            }
        }
        fun run(cfa: UnpackedRawCfa, shading: LensShadingModel? = null, owned: Boolean = false): Result {
            check(pipeline != 0L) { "Opposed session is closed" }
            val w = cfa.width; val h = cfa.height
            require(w >= 4 && h >= 4 && cfa.values.size == w * h)
            val images = mutableListOf<Long>()
            var liveBytes = 0L; var peakBytes = 0L
            val sizes = mutableMapOf<Long, Long>()
            fun image(x: Int, y: Int, format: Int): Long {
                val img = vk.createImage(x, y, format)
                images += img
                val bytes = x.toLong() * y * if (format == 2) 16 else 4
                sizes[img] = bytes; liveBytes += bytes; peakBytes = maxOf(peakBytes, liveBytes)
                return img
            }
            fun release(img: Long) {
                vk.destroyImage(img); images.remove(img); liveBytes -= sizes.remove(img) ?: 0L
            }
            val started = System.nanoTime()
            val phaseMs = DoubleArray(4)
            val roi = intArrayOf(0, 0, w, h)
            fun buffer(n: Int) = ByteBuffer.allocateDirect(n * 4).order(ByteOrder.nativeOrder())
            try {
                val clipping = cfa.highlightClipping
                require(clipping == null || (clipping.width == w && clipping.height == h))
                val eligibility = image(clipping?.wordsPerRow ?: 1, if (clipping != null) h else 1, 1)
                if (clipping != null) {
                    val packed = buffer(clipping.words.size)
                    packed.asIntBuffer().put(clipping.words)
                    vk.writeImage(eligibility, packed)
                }
                vk.bindImage(pipeline, 8, eligibility)
                val src = image(w, h, 0)
                val mask = image(2 * (w / 3), h / 3, 1)
                val rowImage = image(3, h, 2)
                vk.bindImage(pipeline, 0, src)
                vk.bindImage(pipeline, 1, mask)
                vk.bindImage(pipeline, 3, rowImage)
                vk.bindImage(pipeline, 4, src)
                val model = shading?.takeUnless { it.alreadyApplied }
                val gainImage = image(model?.columns ?: 1, model?.rows ?: 1, 2)
                vk.bindImage(pipeline, 6, gainImage)
                val gains = model?.gains ?: floatArrayOf(1f, 1f, 1f, 1f)
                val gainBuffer = buffer(gains.size)
                gainBuffer.asFloatBuffer().put(gains)
                vk.writeImage(gainImage, gainBuffer)
                if (model != null) {
                    val axisSize = maxOf(w, h)
                    val axes = FloatArray(axisSize * 2)
                    val area = model.activeArray
                    for (x in 0 until w) {
                        val n = if (area.width == 1) 0f else
                            (cfa.sensorCropLeft + x - area.left).toFloat() / (area.width - 1).toFloat()
                        axes[x] = n.coerceIn(0f, 1f) * (model.columns - 1)
                    }
                    for (y in 0 until h) {
                        val n = if (area.height == 1) 0f else
                            (cfa.sensorCropTop + y - area.top).toFloat() / (area.height - 1).toFloat()
                        axes[axisSize + y] = n.coerceIn(0f, 1f) * (model.rows - 1)
                    }
                    val coords = image(axisSize, 2, 0)
                    vk.writeImageFloats(coords, axes)
                    vk.bindImage(pipeline, 7, coords)
                } else vk.bindImage(pipeline, 7, gainImage)
                vk.writeImageFloats(src, cfa.values)
                val uploaded = System.nanoTime()
                phaseMs[0] = (uploaded - started) / 1e6
                val cells = OpposedHighlights.cells(cfa)
                fun dispatch(mode: Int, x: Int, y: Int, chroma: FloatArray = FloatArray(3)) {
                    val u = buffer(32)
                    u.putInt(w).putInt(h).putInt(mode).putInt(if (clipping == null) 0 else 1)
                    cells.forEach { u.putInt(it) }
                    repeat(4) { u.putFloat(OpposedHighlights.CLIP) }
                    repeat(4) { u.putFloat(1f) }
                    repeat(4) { u.putFloat(chroma.getOrElse(it) { 0f }) }
                    u.putInt(cfa.sensorCropLeft).putInt(cfa.sensorCropTop)
                        .putInt(model?.columns ?: 0).putInt(model?.rows ?: 0)
                    roi.forEach { u.putInt(it) }
                    u.putInt(model?.activeArray?.left ?: 0).putInt(model?.activeArray?.top ?: 0)
                        .putInt(model?.activeArray?.width ?: 1).putInt(model?.activeArray?.height ?: 1)
                    u.flip(); vk.writeUniforms(pipeline, u)
                    if (mode == 2) vk.dispatch(pipeline, 1, (h + 63) / 64, 1)
                    else vk.dispatch(pipeline, (x + 7) / 8, (y + 7) / 8, 1)
                }
                dispatch(0, w / 3, h / 3)
                dispatch(1, w / 3, h / 3)
                dispatch(2, 1, h)
                val stats = buffer(h * 12)
                vk.readImage(rowImage, stats)
                val rawRows = FloatArray(h * 12); stats.asFloatBuffer().get(rawRows)
                val rows = FloatArray(h * 8)
                var minX = w; var minY = h; var maxX = -1; var maxY = -1
                for (y in 0 until h) {
                    rawRows.copyInto(rows, y * 8, y * 12, y * 12 + 8)
                    val lo = rawRows[y * 12 + 8].toInt()
                    val hi = rawRows[y * 12 + 9].toInt()
                    if (hi >= lo) {
                        minX = minOf(minX, lo); maxX = maxOf(maxX, hi)
                        minY = minOf(minY, y); maxY = y
                    }
                }
                val chroma = OpposedHighlights.reduce(rows, h)
                val analyzed = System.nanoTime()
                phaseMs[1] = (analyzed - uploaded) / 1e6
                // Keep every statically used descriptor valid after releasing analysis images.
                vk.bindImage(pipeline, 1, eligibility)
                vk.bindImage(pipeline, 3, gainImage)
                release(mask); release(rowImage)
                if (maxX < minX) return Result(cfa, rows, chroma, IntArray(4), peakBytes, phaseMs)
                roi[0] = minX; roi[1] = minY; roi[2] = maxX - minX + 1; roi[3] = maxY - minY + 1
                val dst = image(roi[2], roi[3], 0)
                vk.bindImage(pipeline, 4, dst)
                dispatch(3, roi[2], roi[3], chroma)
                val reconstructed = System.nanoTime()
                phaseMs[2] = (reconstructed - analyzed) / 1e6
                val output = if (owned) cfa.values else cfa.values.copyOf()
                vk.readImageFloatsInto(dst, roi[2], roi[3], output, w, minX, minY)
                phaseMs[3] = (System.nanoTime() - reconstructed) / 1e6
                return Result(cfa.copy(values = output), rows, chroma, roi.copyOf(), peakBytes, phaseMs)
            } finally {
                images.asReversed().forEach { vk.destroyImage(it) }
                vk.releaseStaging()
            }
        }
    }
}
