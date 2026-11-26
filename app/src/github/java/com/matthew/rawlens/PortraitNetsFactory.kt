// SPDX-FileCopyrightText: 2026 RawLens contributors
// SPDX-License-Identifier: GPL-3.0-or-later

package com.matthew.rawlens

import android.content.Context
import android.os.SystemClock
import org.tensorflow.lite.DataType
import org.tensorflow.lite.Interpreter
import org.tensorflow.lite.nnapi.NnApiDelegate
import java.io.FileInputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.MappedByteBuffer
import java.nio.channels.FileChannel

/**
 * LiteRT runtime. Capture depth: Depth Anything V2 small (Apache-2.0, Qualcomm
 * AI Hub export, weights re-packed to fp16 + DEQUANTIZE, max output change
 * 0.05 %), 518^2 on 4 CPU threads: ~1.5-2 s on the Dimensity 7300 (its NNAPI
 * path takes 19 s and the GPU delegate fails). It beat MiDaS by +0.74 dB mean
 * PSNR against real Canon f/2 shots (RealBokeh, 2026-11-26). Fallback: MiDaS
 * v2.1 small w8a8 (MIT), 6 ms NPU via NNAPI. Person: MediaPipe selfie
 * segmentation (Apache-2.0), 3 ms CPU.
 */
object PortraitNetsFactory {
    private const val DEPTH_HQ = "models/portrait/depth_anything_v2_fp16.tflite"
    private const val DEPTH = "models/portrait/midas_w8a8.tflite"
    private const val PERSON = "models/portrait/mediapipe_selfie.tflite"

    fun available(context: Context): Boolean =
        has(context, DEPTH_HQ) || has(context, DEPTH)

    private fun has(context: Context, asset: String): Boolean =
        runCatching { context.assets.openFd(asset).close(); true }.getOrDefault(false)

    fun create(context: Context): PortraitNets = LiteRtPortraitNets(context)

    private class LiteRtPortraitNets(context: Context) : PortraitNets {
        private var nnapi: NnApiDelegate? = null
        private val depth: Interpreter
        private val person: Interpreter?
        override val description: String

        override val depthSize: Int

        init {
            val started = SystemClock.elapsedRealtime()
            if (has(context, DEPTH_HQ)) {
                depth = Interpreter(map(context, DEPTH_HQ), Interpreter.Options().setNumThreads(4))
                depthSize = depth.getInputTensor(0).shape()[1]
                description = "dav2-fp16/CPU + selfie/CPU"
            } else {
                val model = map(context, DEPTH)
                var backend = "NNAPI"
                depth = try {
                    val delegate = NnApiDelegate(NnApiDelegate.Options().apply {
                        setAllowFp16(true)
                        setCacheDir(context.cacheDir.absolutePath)
                        setModelToken("rawlens_midas_w8a8_v1")
                    })
                    nnapi = delegate
                    Interpreter(model, Interpreter.Options().addDelegate(delegate))
                } catch (failure: Throwable) {
                    Log.w(LOG_TAG, "NNAPI depth failed, using CPU", failure)
                    nnapi?.close(); nnapi = null
                    backend = "CPU"
                    Interpreter(model, Interpreter.Options().setNumThreads(4))
                }
                depthSize = PortraitDepth.NET_SIZE
                description = "midas-w8a8/$backend + selfie/CPU"
            }
            person = runCatching {
                Interpreter(map(context, PERSON), Interpreter.Options().setNumThreads(2))
            }.onFailure { Log.w(LOG_TAG, "Person model unavailable", it) }.getOrNull()
            Log.i(LOG_TAG, "Portrait networks ready ($description) in ${SystemClock.elapsedRealtime() - started} ms")
        }

        override fun depth(rgb: FloatArray): FloatArray {
            val n = depthSize * depthSize
            val input = depth.getInputTensor(0)
            val output = depth.getOutputTensor(0)
            val inBuf = ByteBuffer.allocateDirect(input.numBytes()).order(ByteOrder.nativeOrder())
            if (input.dataType() == DataType.UINT8) {
                val q = input.quantizationParams()
                for (v in rgb) inBuf.put((v / q.scale + q.zeroPoint).toInt().coerceIn(0, 255).toByte())
            } else {
                for (v in rgb) inBuf.putFloat(v)
            }
            inBuf.rewind()
            val outBuf = ByteBuffer.allocateDirect(output.numBytes()).order(ByteOrder.nativeOrder())
            depth.run(inBuf, outBuf)
            outBuf.rewind()
            return if (output.dataType() == DataType.UINT8) {
                val q = output.quantizationParams()
                FloatArray(n) { ((outBuf.get().toInt() and 0xff) - q.zeroPoint) * q.scale }
            } else FloatArray(n) { outBuf.getFloat() }
        }

        override fun person(rgb: FloatArray): FloatArray? {
            val net = person ?: return null
            val n = PortraitDepth.NET_SIZE * PortraitDepth.NET_SIZE
            val inBuf = ByteBuffer.allocateDirect(rgb.size * 4).order(ByteOrder.nativeOrder())
            for (v in rgb) inBuf.putFloat(v)
            inBuf.rewind()
            val outBuf = ByteBuffer.allocateDirect(n * 4).order(ByteOrder.nativeOrder())
            net.run(inBuf, outBuf)
            outBuf.rewind()
            return FloatArray(n) { outBuf.getFloat() }
        }

        override fun close() {
            depth.close()
            person?.close()
            nnapi?.close()
        }

        private fun map(context: Context, asset: String): MappedByteBuffer =
            context.assets.openFd(asset).use { fd ->
                FileInputStream(fd.fileDescriptor).channel.use {
                    it.map(FileChannel.MapMode.READ_ONLY, fd.startOffset, fd.declaredLength)
                }
            }
    }

    private const val LOG_TAG = "RawLensPortrait"
}
