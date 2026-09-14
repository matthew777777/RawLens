// SPDX-FileCopyrightText: 2026 RawLens contributors
// SPDX-License-Identifier: GPL-3.0-or-later

package com.matthew.rawlens

import android.content.Context
import android.opengl.GLES30
import android.opengl.GLES31
import java.io.Closeable
import kotlin.math.min

data class RawSrGpuOutput(
    val numeratorTextureId: Int,
    val denominatorTextureId: Int,
    val width: Int,
    val height: Int,
    val acceptedFrames: Int,
    val flowTextureIds: List<Int>
)

/** GLES 3.1 Phase-B executor. One instance is confined to RawCameraController's writer thread. */
class Gles31RawSrProcessor(context: Context) : Closeable {
    private val appContext = context.applicationContext
    private var session: Session? = null
    private var ownerThread: Long? = null

    fun <T> process(
        frames: List<UnpackedRawCfa>,
        config: RawSrAlignmentConfig = RawSrAlignmentConfig(),
        referenceOnly: Boolean = false,
        consume: (RawSrGpuOutput) -> T
    ): T {
        require(frames.isNotEmpty())
        val reference = frames.first()
        require(reference.width % 2 == 0 && reference.height % 2 == 0)
        require(frames.all {
            it.width == reference.width && it.height == reference.height && it.pattern == reference.pattern
        }) { "RAW-SR frames must have identical dimensions and CFA phase" }
        val active = ensureSession()
        active.egl.makeCurrent()
        val textures = ArrayList<Gles31AmazeProcessor.GlTexture>()
        fun texture(width: Int, height: Int, format: Int) =
            Gles31AmazeProcessor.GlTexture(width, height, format).also(textures::add)
        try {
            val pyramids = frames.map { frame ->
                val cfa = texture(frame.width, frame.height, GLES30.GL_R32F)
                cfa.uploadR32f(frame.values, active.uploads)
                val levels = ArrayList<Gles31AmazeProcessor.GlTexture>()
                var width = frame.width / 2
                var height = frame.height / 2
                var gray = texture(width, height, GLES30.GL_R32F)
                active.pass("rawsr/bayer_quad_gray.glsl") {
                    sampler("u_cfa", cfa); ivec2("u_size", width, height)
                    image(0, gray, GLES30.GL_R32F); dispatch(width, height, 8, 8)
                }
                levels += gray
                while (levels.size < config.levels && width >= 8 && height >= 8) {
                    width /= 2; height /= 2
                    val next = texture(width, height, GLES30.GL_R32F)
                    active.pass("rawsr/pyramid_downsample.glsl") {
                        sampler("u_source", gray); ivec2("u_size", width, height)
                        image(0, next, GLES30.GL_R32F); dispatch(width, height, 8, 8)
                    }
                    levels += next; gray = next
                }
                levels
            }

            val finalFlows = ArrayList<Gles31AmazeProcessor.GlTexture>()
            if (!referenceOnly) for (frameIndex in 1 until frames.size) {
                var previous: Gles31AmazeProcessor.GlTexture? = null
                for (level in pyramids[0].indices.reversed()) {
                    val ref = pyramids[0][level]
                    val moving = pyramids[frameIndex][level]
                    val columns = ceilDiv(ref.width, config.tileSize)
                    val rows = ceilDiv(ref.height, config.tileSize)
                    val matched = texture(columns, rows, GLES30.GL_RGBA32F)
                    active.pass("rawsr/block_match.glsl") {
                        sampler("u_reference", ref); sampler("u_moving", moving)
                        sampler("u_initial_flow", previous ?: ref)
                        ivec2("u_size", ref.width, ref.height)
                        ivec2("u_tile_grid", columns, rows)
                        ivec2("u_previous_grid", previous?.width ?: 1, previous?.height ?: 1)
                        integer("u_tile_size", config.tileSize)
                        integer("u_search_radius", min(config.searchRadius, 6))
                        integer("u_has_initial_flow", if (previous == null) 0 else 1)
                        image(0, matched, GLES30.GL_RGBA32F); dispatch(columns, rows, 1, 1)
                    }
                    val refined = texture(columns, rows, GLES30.GL_RGBA32F)
                    active.pass("rawsr/lk_refine.glsl") {
                        sampler("u_reference", ref); sampler("u_moving", moving); sampler("u_flow", matched)
                        ivec2("u_size", ref.width, ref.height); ivec2("u_tile_grid", columns, rows)
                        integer("u_tile_size", config.tileSize); integer("u_iterations", min(config.lkIterations, 6))
                        float("u_min_determinant", config.minHessianDeterminant)
                        float("u_max_residual", config.maxMeanAbsoluteResidual)
                        image(0, refined, GLES30.GL_RGBA32F); dispatch(columns, rows, 1, 1)
                    }
                    previous = refined
                }
                finalFlows += requireNotNull(previous)
            }

            var numerator = texture(reference.width, reference.height, GLES30.GL_RGBA32F).also {
                it.uploadRgba32f(FloatArray(reference.width * reference.height * 4), active.uploads)
            }
            var denominator = texture(reference.width, reference.height, GLES30.GL_R32F).also {
                it.uploadR32f(FloatArray(reference.width * reference.height), active.uploads)
            }
            val rgbFrames = (if (referenceOnly) frames.take(1) else frames).map(RawSrMergePrototype::demosaic)
            val dummyFlow = texture(1, 1, GLES30.GL_RGBA32F).also {
                it.uploadRgba32f(floatArrayOf(0f, 0f, 0f, 1f), active.uploads)
            }
            rgbFrames.forEachIndexed { index, rgb ->
                val rgbTexture = texture(rgb.width, rgb.height, GLES30.GL_RGBA32F)
                val rgba = FloatArray(rgb.width * rgb.height * 4)
                for (p in 0 until rgb.width * rgb.height) {
                    rgba[p * 4] = rgb.values[p * 3]
                    rgba[p * 4 + 1] = rgb.values[p * 3 + 1]
                    rgba[p * 4 + 2] = rgb.values[p * 3 + 2]
                    rgba[p * 4 + 3] = 1f
                }
                rgbTexture.uploadRgba32f(rgba, active.uploads)
                val nextNum = texture(reference.width, reference.height, GLES30.GL_RGBA32F)
                val nextDen = texture(reference.width, reference.height, GLES30.GL_R32F)
                val flow = if (index == 0) dummyFlow else finalFlows[index - 1]
                val gridX = if (index == 0) 1 else flow.width
                val gridY = if (index == 0) 1 else flow.height
                active.pass("rawsr/accumulate_rgb.glsl") {
                    sampler("u_rgb", rgbTexture); sampler("u_flow", flow)
                    sampler("u_numerator", numerator); sampler("u_denominator", denominator)
                    ivec2("u_size", reference.width, reference.height); ivec2("u_tile_grid", gridX, gridY)
                    integer("u_tile_size", config.tileSize); integer("u_reference_only", if (index == 0) 1 else 0)
                    image(0, nextNum, GLES30.GL_RGBA32F); image(1, nextDen, GLES30.GL_R32F)
                    dispatch(reference.width, reference.height, 8, 8)
                }
                numerator = nextNum; denominator = nextDen
            }
            return consume(RawSrGpuOutput(numerator.id, denominator.id, reference.width,
                reference.height, rgbFrames.size, finalFlows.map { it.id }))
        } finally {
            textures.asReversed().forEach(Gles31AmazeProcessor.GlTexture::close)
        }
    }

    override fun close() {
        val active = session ?: return
        check(ownerThread == Thread.currentThread().id)
        active.egl.makeCurrent(); active.uploads.close(); active.programs.close(); active.egl.close()
        session = null; ownerThread = null
    }

    private fun ensureSession(): Session {
        val thread = Thread.currentThread().id
        check(ownerThread == null || ownerThread == thread) { "RAW-SR GLES session crossed worker threads" }
        return session ?: Session(Gles31AmazeProcessor.EglComputeContext(),
            Gles31AmazeProcessor.ProgramCache(appContext), Gles31AmazeProcessor.UploadBuffers())
            .also { session = it; ownerThread = thread }
    }

    private data class Session(
        val egl: Gles31AmazeProcessor.EglComputeContext,
        val programs: Gles31AmazeProcessor.ProgramCache,
        val uploads: Gles31AmazeProcessor.UploadBuffers
    ) {
        fun pass(asset: String, block: Bound.() -> Unit) = Bound(programs.get(asset)).block()
    }

    private class Bound(private val program: Int) {
        private var unit = 0
        init { GLES31.glUseProgram(program) }
        fun sampler(name: String, texture: Gles31AmazeProcessor.GlTexture) {
            GLES31.glActiveTexture(GLES31.GL_TEXTURE0 + unit); GLES31.glBindTexture(GLES31.GL_TEXTURE_2D, texture.id)
            GLES31.glUniform1i(location(name), unit++)
        }
        fun image(binding: Int, texture: Gles31AmazeProcessor.GlTexture, format: Int) =
            GLES31.glBindImageTexture(binding, texture.id, 0, false, 0, GLES31.GL_WRITE_ONLY, format)
        fun ivec2(name: String, x: Int, y: Int) = GLES31.glUniform2i(location(name), x, y)
        fun integer(name: String, value: Int) = GLES31.glUniform1i(location(name), value)
        fun float(name: String, value: Float) = GLES31.glUniform1f(location(name), value)
        fun dispatch(width: Int, height: Int, localX: Int, localY: Int) {
            GLES31.glDispatchCompute(ceilDiv(width, localX), ceilDiv(height, localY), 1)
            GLES31.glMemoryBarrier(GLES31.GL_SHADER_IMAGE_ACCESS_BARRIER_BIT or GLES31.GL_TEXTURE_FETCH_BARRIER_BIT)
            check(GLES31.glGetError() == GLES31.GL_NO_ERROR) { "RAW-SR GLES dispatch failed" }
        }
        private fun location(name: String): Int = GLES31.glGetUniformLocation(program, name)
            .also { check(it >= 0) { "RAW-SR uniform $name missing" } }
    }

    private companion object {
        fun ceilDiv(value: Int, divisor: Int) = (value + divisor - 1) / divisor
    }
}
