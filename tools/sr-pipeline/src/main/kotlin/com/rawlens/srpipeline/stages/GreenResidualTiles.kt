package com.rawlens.srpipeline.stages

import com.rawlens.srpipeline.SrpBridge
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer
import java.nio.channels.FileChannel
import java.nio.file.Files
import java.nio.file.StandardOpenOption.*
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.sqrt

/** Desktop GPU replay. Disk-backed input cache and bounded GPU tile scratch. */
internal class GreenResidualTiles(
    private val width: Int, private val height: Int, private val config: PipelineConfig,
) : AutoCloseable {
    private data class Frame(val raw: FloatBuffer, val rob: FloatBuffer, val flow: FloatArray, val fw: Int, val fh: Int)
    private val folder = Files.createTempDirectory("srp-green-fit-")
    private val frames = mutableListOf<Frame>()
    private val scale = config.scale.toInt()
    private val wb = requireNotNull(config.colourDiff)

    fun record(raw: ByteBuffer, flow: Array<Array<FloatArray>>, rob: ByteBuffer) {
        fun cache(name: String, buffer: ByteBuffer, count: Int): FloatBuffer {
            val path = folder.resolve("$name-${frames.size}.f32")
            return FileChannel.open(path, CREATE_NEW, READ, WRITE).use { channel ->
                val input = buffer.duplicate().apply { clear(); limit(count * 4) }
                while (input.hasRemaining()) channel.write(input)
                channel.map(FileChannel.MapMode.READ_ONLY, 0, count * 4L)
                    .order(ByteOrder.nativeOrder()).asFloatBuffer()
            }
        }
        frames += Frame(cache("raw", raw, width * height), cache("rob", rob, width * height / 4),
            AlignGpu.flowToFlat(flow), flow[0].size, flow.size)
    }

    fun finish(rgb: ByteBuffer, handle: Long, shader: ByteArray, tileSize: Int = 256, step: Float = 0.75f,
        guarded2x: Boolean = false, pedestal: Float = 0f) {
        require(config.scale in listOf(1f, 2f) && frames.isNotEmpty())
        require(!guarded2x || scale == 2)
        val outW = width * scale
        val outH = height * scale
        val pixels = rgb.order(ByteOrder.nativeOrder()).asFloatBuffer()
        val bound = frames.maxOf { frame -> frame.flow.maxOf { abs(it) } }
        require(bound.isFinite()) { "Non-finite flow in green fit" }
        val halo = minOf(ceil(bound).toInt().coerceAtLeast(0), 64) + 3
        if (bound > 64f) println("sr-pipeline: green fit rejects samples displaced over 64 sensor pixels")
        val patch = tileSize / scale + 2 * halo + 4
        val baseSize = (tileSize + 2 * scale) * (tileSize + 2 * scale)
        val counts = longArrayOf(baseSize.toLong(), patch.toLong() * patch,
            frames.maxOf { it.flow.size }.toLong(), (patch.toLong() * patch), 2L * tileSize * tileSize)
        // A pixel can only receive samples within the bounded flow radius.
        val maxContrib = frames.size.toLong() * (2L * halo + 3) * (2L * halo + 3)
        val quantum = minOf(1 shl 20, (Int.MAX_VALUE / 2L / maxContrib).toInt()).toFloat()
        require(quantum > 0f)
        val residents = LongArray(counts.size)
        SrpBridge.NativeFloats().use { scratch ->
            try {
                for (i in counts.indices) {
                    residents[i] = SrpBridge.createResident(handle, counts[i])
                    check(residents[i] != 0L) { "Green fit resident allocation failed" }
                }
                val staging = scratch.floats(counts.max().toInt())
                val correction = scratch.floats(2 * tileSize * tileSize)
                val baseGreenSize = outW * outH
                // Keep an immutable green plane so a finished tile cannot affect its neighbor.
                val original = scratch.floats(baseGreenSize).asFloatBuffer()
                val updates = if (guarded2x) scratch.floats(baseGreenSize).asFloatBuffer() else null
                val cameraRange = 1f - pedestal
                val alpha = config.kernelAlpha[1]
                val beta = config.kernelBeta[1]
                val clip = 2f * sqrt(maxOf(alpha * 0.42f + beta, 1e-8f))
                fun upload(index: Int, count: Int, fill: (FloatBuffer) -> Unit) {
                    fill(staging.clear().asFloatBuffer())
                    staging.clear()
                    check(SrpBridge.uploadResident(handle, residents[index], staging, count.toLong()) == 0)
                }
                fun rectangle(index: Int, input: FloatBuffer, stride: Int, x: Int, y: Int, w: Int, h: Int) {
                    upload(index, w * h) { dst ->
                        for (j in 0 until h) for (i in 0 until w) dst.put(input.get((y + j) * stride + x + i))
                    }
                }
                fun update() {
                    for (i in 0 until baseGreenSize) original.put(i, pixels.get(i * 3 + 1) * cameraRange + pedestal)
                    for (ty in 0 until outH step tileSize) for (tx in 0 until outW step tileSize) {
                        val tw = minOf(tileSize, outW - tx)
                        val th = minOf(tileSize, outH - ty)
                        val bx = maxOf(0, tx - scale)
                        val by = maxOf(0, ty - scale)
                        val bw = minOf(outW, tx + tw + scale) - bx
                        val bh = minOf(outH, ty + th + scale) - by
                        rectangle(0, original, outW, bx, by, bw, bh)
                        val push = IntArray(32)
                        val sx = maxOf(0, tx / scale - halo)
                        val sy = maxOf(0, ty / scale - halo)
                        val sw = minOf(width, (tx + tw + scale - 1) / scale + halo) - sx
                        val sh = minOf(height, (ty + th + scale - 1) / scale + halo) - sy
                        val rx = maxOf(0, (tx - scale) / (2 * scale) - 1)
                        val ry = maxOf(0, (ty - scale) / (2 * scale) - 1)
                        val rw = minOf(width / 2, (tx + tw + 3 * scale) / (2 * scale) + 1) - rx
                        val rh = minOf(height / 2, (ty + th + 3 * scale) / (2 * scale) + 1) - ry
                        intArrayOf(0, tw, th, tx, ty, bw, bx, by, sw, sh, sx, sy, width, height, scale,
                            config.tileSizes.first(), 0, 0, alpha.toRawBits(), beta.toRawBits(), quantum.toRawBits(),
                            rw, rx, ry, 0, bh).copyInto(push)
                        fun dispatch(items: Int) = check(SrpBridge.dispatchRes(handle, shader, residents, counts, push, items.toLong()) == 0)
                        dispatch(2 * tw * th)
                        push[0] = if (guarded2x) 6 else 1
                        for (frame in frames) {
                            rectangle(1, frame.raw, width, sx, sy, sw, sh)
                            upload(2, frame.flow.size) { it.put(frame.flow) }
                            rectangle(3, frame.rob, width / 2, rx, ry, rw, rh)
                            push[16] = frame.fw
                            push[17] = frame.fh
                            dispatch(sw * sh)
                        }
                        check(SrpBridge.downloadResident(handle, residents[4], correction.clear(), 2L * tw * th) == 0)
                        val sums = correction.asIntBuffer()
                        for (y in ty until ty + th) for (x in tx until tx + tw) {
                            val i = y * outW + x
                            val g = original.get(i)
                            val contrast = (abs(original.get(y * outW + maxOf(x - scale, 0)) - g) +
                                abs(original.get(y * outW + minOf(x + scale, outW - 1)) - g) +
                                abs(original.get(maxOf(y - scale, 0) * outW + x) - g) +
                                abs(original.get(minOf(y + scale, outH - 1) * outW + x) - g)) * 0.25f
                            val noise = sqrt(maxOf(alpha * maxOf(g, 0f) + beta, 1e-8f))
                            val t = ((contrast / noise - 0.65f) / 0.85f).coerceIn(0f, 1f)
                            val residual = sums.get() / quantum
                            val mass = sums.get() / quantum
                            val confidence = t * t * (3f - 2f * t)
                            if (updates != null) {
                                // Sparse or rejected sample coverage leaves the guarded merge in control.
                                val coverage = (mass / 0.5f).coerceIn(0f, 1f)
                                val limit = 1.5f * noise
                                updates.put(i, (step * residual / (mass + 0.25f))
                                    .coerceIn(-limit, limit) * confidence * coverage / cameraRange)
                            } else {
                                val delta = (step * residual / (mass + 0.25f)).coerceIn(-clip, clip) * confidence
                                val p = 3 * i
                                pixels.put(p, pixels.get(p) + delta / wb[0])
                                pixels.put(p + 1, g + delta)
                                pixels.put(p + 2, pixels.get(p + 2) + delta / wb[1])
                            }
                        }
                    }
                }
                if (updates == null) update() else repeat(2) {
                    update()
                    java.util.stream.IntStream.range(0, outH).parallel().forEach { y ->
                        for (x in 0 until outW) {
                            val i = y * outW + x
                            val p = 3 * i
                            val center = updates.get(i)
                            fun axial(distance: Int): Float =
                                updates.get(y * outW + maxOf(x - distance, 0)) +
                                updates.get(y * outW + minOf(x + distance, outW - 1)) +
                                updates.get(maxOf(y - distance, 0) * outW + x) +
                                updates.get(minOf(y + distance, outH - 1) * outW + x)
                            // Damp two- and four-output-pixel repeats on the correction layer.
                            val delta = (4f * center + 2f * axial(1) + axial(2)) / 16f
                            val red = pixels.get(p)
                            val green = pixels.get(p + 1)
                            val blue = pixels.get(p + 2)
                            val budget = 0.09f * maxOf(0f, minOf(green, red * wb[0], blue * wb[1]))
                            val guarded = maxOf(delta, -budget)
                            pixels.put(p, red + guarded / wb[0])
                            pixels.put(p + 1, green + guarded)
                            pixels.put(p + 2, blue + guarded / wb[1])
                        }
                    }
                }
            } finally {
                for (resident in residents) if (resident != 0L) SrpBridge.freeResident(handle, resident)
            }
        }
    }

    override fun close() {
        Files.list(folder).use { it.forEach(Files::deleteIfExists) }
        Files.deleteIfExists(folder)
    }
}
