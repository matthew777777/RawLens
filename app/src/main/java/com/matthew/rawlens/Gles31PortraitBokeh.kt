// SPDX-FileCopyrightText: 2026 RawLens contributors
// SPDX-License-Identifier: GPL-3.0-or-later

package com.matthew.rawlens

import android.content.Context
import android.opengl.GLES20
import android.opengl.GLES30
import android.opengl.GLES31
import android.os.SystemClock
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.max

/**
 * Portrait bokeh on the develop's scene-linear ACEScg texture, in place, before
 * tone mapping (so recovered highlights become bright bokeh discs).
 *
 *  1. thumb: 1024^2 display-encoded guide; area-downsized copies feed the depth
 *     (Depth Anything V2, 518^2) and person (256^2) networks (CPU side).
 *  2. [PortraitDepth.fuse]: subject on the focal plane; colour guided-filter
 *     coefficients at 1024^2 (hair keeps its own depth).
 *  3. prep: full-res disparity = a . rgb + b, packed into alpha; mip chain.
 *  4. gather: one disc gather per depth layer at its pyramid level (radius <= 2 texels).
 *  5. composite: coarse-to-fine back/front chains; final level adds the near-focus
 *     band and writes the scene texture.
 *
 * Measured with the standalone Vulkan twin on Mali-G615 MC2: ~130 ms GPU at 12.5 MP.
 * Must run on the thread whose EGL context owns [AmazeGpuOutput.textureId].
 */
class Gles31PortraitBokeh(context: Context) {
    private val assets = context.applicationContext.assets
    private val common by lazy { read("common.glsl") }
    private var thumbProgram = 0
    private var prepProgram = 0
    private var gatherProgram = 0
    private var compositeProgram = 0
    private var finalProgram = 0
    private var framebuffer = 0

    data class Result(
        val totalMs: Long, val netsMs: Long, val gpuMs: Long,
        val focus: Float, val personFraction: Float, val layers: Int, val nets: String,
    )

    fun apply(
        scene: AmazeGpuOutput,
        settings: PortraitSettings,
        nets: PortraitNets,
        exifOrientation: Int,
        seed: Int,
    ): Result {
        require(scene.internalFormat == AmazeTextureFormat.RGBA16F && scene.colorDomain == AmazeColorDomain.ACESCG)
        val started = SystemClock.elapsedRealtime()
        ensurePrograms()
        val w = scene.width
        val h = scene.height
        val n = PortraitDepth.NET_SIZE
        val textures = ArrayList<Int>()
        val profile = dumpEnabled()
        val stages = StringBuilder()
        var mark = SystemClock.elapsedRealtime()
        fun stage(name: String) {
            if (!profile) return
            GLES31.glFinish()
            val now = SystemClock.elapsedRealtime()
            stages.append(name).append('=').append(now - mark).append("ms ")
            mark = now
        }
        try {
            // 1. guide thumbnail (GUIDE_SIZE^2, display-encoded); the networks get area-downsized copies.
            val gs = PortraitDepth.GUIDE_SIZE
            val thumb = texture2d(gs, gs, GLES30.GL_RGBA8, 1).also(textures::add)
            GLES31.glUseProgram(thumbProgram)
            GLES31.glBindImageTexture(0, thumb, 0, false, 0, GLES31.GL_WRITE_ONLY, GLES30.GL_RGBA8)
            GLES31.glBindImageTexture(1, scene.textureId, 0, false, 0, GLES31.GL_READ_ONLY, GLES30.GL_RGBA16F)
            GLES31.glUniform2i(loc(thumbProgram, "u_size"), w, h)
            GLES31.glUniform2i(loc(thumbProgram, "u_tsize"), gs, gs)
            dispatch(gs, gs)
            val pixels = readRgba8(thumb, gs, gs)
            stage("thumb")
            val guide = FloatArray(gs * gs * 3)
            for (i in 0 until gs * gs) for (c in 0 until 3) {
                guide[i * 3 + c] = (pixels.get(i * 4 + c).toInt() and 0xff) / 255f
            }
            // 2. networks see the picture upright; maps come back in sensor orientation.
            val turns = clockwiseTurns(exifOrientation)
            val back = (4 - turns) % 4
            val stored = PortraitDepth.resizeArea(guide, gs, n, 3)
            val upright = rotateCw(stored, n, 3, turns)
            val ds = nets.depthSize
            val uprightDepthIn = if (ds == n) upright else rotateCw(PortraitDepth.resizeArea(guide, gs, ds, 3), ds, 3, turns)
            stage("resize")
            val netsStarted = SystemClock.elapsedRealtime()
            val depthRaw = rotateCw(nets.depth(uprightDepthIn), ds, 1, back)
            val person = nets.person(upright)?.let { rotateCw(it, n, 1, back) }
            val netsMs = SystemClock.elapsedRealtime() - netsStarted
            val depth = PortraitDepth.resizeArea(depthRaw, ds, n, 1)
            stage("nets")
            val fused = PortraitDepth.fuse(depth, person)
            val coefficients = PortraitDepth.colorGuidedCoefficients(guide, gs, fused.disparity)
            stage("fuse")
            if (profile) {
                PortraitDebugDump.write(stored, depth, person, fused.disparity, n)
                stage("dump")
            }
            val plan = PortraitDepth.plan(
                fused.focus, settings.radiusFraction * max(w, h), PortraitDepth.mipLevels(w, h)
            )
            val gpuStarted = SystemClock.elapsedRealtime()

            // 3. full-res disparity + mip chain
            val coef = texture2d(gs, gs, GLES30.GL_RGBA32F, 1, linear = false).also(textures::add)
            GLES31.glBindTexture(GLES31.GL_TEXTURE_2D, coef)
            val coefBuf = ByteBuffer.allocateDirect(coefficients.size * 4).order(ByteOrder.nativeOrder())
            coefBuf.asFloatBuffer().put(coefficients)
            GLES31.glPixelStorei(GLES31.GL_UNPACK_ALIGNMENT, 4)
            GLES31.glTexSubImage2D(GLES31.GL_TEXTURE_2D, 0, 0, 0, gs, gs, GLES30.GL_RGBA, GLES31.GL_FLOAT, coefBuf)
            val img = texture2d(w, h, GLES30.GL_RGBA16F, plan.levels, linear = false).also(textures::add)
            GLES31.glUseProgram(prepProgram)
            GLES31.glBindImageTexture(0, img, 0, false, 0, GLES31.GL_WRITE_ONLY, GLES30.GL_RGBA16F)
            GLES31.glBindImageTexture(1, scene.textureId, 0, false, 0, GLES31.GL_READ_ONLY, GLES30.GL_RGBA16F)
            bindSampler(prepProgram, "u_coef", 0, GLES31.GL_TEXTURE_2D, coef)
            GLES31.glUniform2i(loc(prepProgram, "u_size"), w, h)
            GLES31.glUniform1i(loc(prepProgram, "u_csize"), gs)
            dispatch(w, h)
            stage("prep")
            if (plan.levels > 1) {
                GLES31.glBindTexture(GLES31.GL_TEXTURE_2D, img)
                GLES31.glGenerateMipmap(GLES31.GL_TEXTURE_2D)
            }
            stage("mips")

            // 4. per-layer gathers into one texture array per level
            val dummy = textureArray(1, 1, 2, linear = true).also(textures::add)
            val layerArrays = IntArray(plan.levels)
            val layerSlot = HashMap<PortraitDepth.Layer, Int>()
            for (level in 0 until plan.levels) {
                val here = plan.layers.filter { it.level == level && it.kind != PortraitDepth.Kind.NEAR }
                if (here.isEmpty()) continue
                val lw = max(1, w shr level)
                val lh = max(1, h shr level)
                val array = textureArray(lw, lh, here.size, linear = false).also(textures::add)
                layerArrays[level] = array
                GLES31.glUseProgram(gatherProgram)
                bindSampler(gatherProgram, "u_img", 0, GLES31.GL_TEXTURE_2D, img)
                GLES31.glUniform2i(loc(gatherProgram, "u_size"), lw, lh)
                GLES31.glUniform1i(loc(gatherProgram, "u_level"), level)
                GLES31.glUniform1f(loc(gatherProgram, "u_cat"), settings.catEye)
                here.forEachIndexed { slot, layer ->
                    layerSlot[layer] = slot
                    GLES31.glBindImageTexture(0, array, 0, false, slot, GLES31.GL_WRITE_ONLY, GLES30.GL_RGBA16F)
                    GLES31.glUniform1f(loc(gatherProgram, "u_r"), layer.levelRadius)
                    GLES31.glUniform3f(loc(gatherProgram, "u_tent"), layer.centre, layer.lo, layer.hi)
                    dispatch(lw, lh)
                }
            }

            stage("gathers")
            // 5. coarse-to-fine compositing
            var prev = 0
            for (level in plan.maxLevel downTo 0) {
                val lw = max(1, w shr level)
                val lh = max(1, h shr level)
                val order = PortraitDepth.compositeOrder(plan, level)
                val kinds = IntArray(order.size * 2)
                val meta = FloatArray(order.size * 4)
                order.forEachIndexed { i, l ->
                    kinds[i * 2] = l.kind.shader
                    kinds[i * 2 + 1] = layerSlot[l] ?: 0
                    meta[i * 4] = l.centre; meta[i * 4 + 1] = l.lo; meta[i * 4 + 2] = l.hi; meta[i * 4 + 3] = l.bandHalfWidth
                }
                val program = if (level == 0) finalProgram else compositeProgram
                GLES31.glUseProgram(program)
                bindSampler(program, "u_prev", 0, GLES30.GL_TEXTURE_2D_ARRAY, if (prev != 0) prev else dummy)
                bindSampler(program, "u_layers", 1, GLES30.GL_TEXTURE_2D_ARRAY,
                    if (layerArrays[level] != 0) layerArrays[level] else dummy)
                GLES31.glUniform1i(loc(program, "u_has_prev"), if (prev != 0) 1 else 0)
                GLES31.glUniform1i(loc(program, "u_n"), order.size)
                if (order.isNotEmpty()) {
                    GLES31.glUniform2iv(loc(program, "u_kind"), order.size, kinds, 0)
                    GLES31.glUniform4fv(loc(program, "u_meta"), order.size, meta, 0)
                }
                GLES31.glUniform2i(loc(program, "u_size"), lw, lh)
                if (level == 0) {
                    bindSampler(program, "u_img", 2, GLES31.GL_TEXTURE_2D, img)
                    GLES31.glBindImageTexture(0, scene.textureId, 0, false, 0, GLES31.GL_WRITE_ONLY, GLES30.GL_RGBA16F)
                    GLES31.glUniform1f(loc(program, "u_df"), plan.focus)
                    GLES31.glUniform1f(loc(program, "u_k"), plan.k)
                    GLES31.glUniform1f(loc(program, "u_rnear"), PortraitDepth.NEAR_RADIUS)
                    GLES31.glUniform1f(loc(program, "u_grain"), settings.grain)
                    GLES31.glUniform1ui(loc(program, "u_seed"), seed)
                } else {
                    val acc = textureArray(lw, lh, 2, linear = true).also(textures::add)
                    GLES31.glBindImageTexture(0, acc, 0, true, 0, GLES31.GL_WRITE_ONLY, GLES30.GL_RGBA16F)
                    prev = acc
                }
                dispatch(lw, lh)
                if (level == 1) stage("compositeCoarse")
            }
            GLES31.glFinish()
            stage("final")
            if (profile) Log.i("RawLensPortrait", "Portrait stages: $stages")
            checkGl("portrait bokeh")
            val done = SystemClock.elapsedRealtime()
            return Result(done - started, netsMs, done - gpuStarted, fused.focus, fused.personFraction,
                plan.layers.size, nets.description)
        } finally {
            GLES31.glDeleteTextures(textures.size, textures.toIntArray(), 0)
        }
    }

    /** Must be called on the owning EGL thread. */
    fun close() {
        for (p in intArrayOf(thumbProgram, prepProgram, gatherProgram, compositeProgram, finalProgram)) {
            if (p != 0) GLES31.glDeleteProgram(p)
        }
        thumbProgram = 0; prepProgram = 0; gatherProgram = 0; compositeProgram = 0; finalProgram = 0
        if (framebuffer != 0) GLES30.glDeleteFramebuffers(1, intArrayOf(framebuffer), 0)
        framebuffer = 0
    }

    private fun ensurePrograms() {
        if (thumbProgram != 0) return
        thumbProgram = compile("", "thumb.glsl")
        prepProgram = compile("", "prep.glsl")
        gatherProgram = compile("", "gather.glsl")
        compositeProgram = compile("", "composite.glsl")
        finalProgram = compile("#define FINAL 1\n", "composite.glsl")
    }

    private fun read(name: String) = assets.open("shaders/portrait/$name").bufferedReader().use { it.readText() }

    private fun compile(defines: String, name: String): Int {
        val shader = GLES31.glCreateShader(GLES31.GL_COMPUTE_SHADER)
        GLES31.glShaderSource(shader, "#version 310 es\n$defines$common\n${read(name)}")
        GLES31.glCompileShader(shader)
        val status = IntArray(1)
        GLES31.glGetShaderiv(shader, GLES31.GL_COMPILE_STATUS, status, 0)
        if (status[0] == 0) {
            val log = GLES31.glGetShaderInfoLog(shader)
            GLES31.glDeleteShader(shader)
            error("Portrait shader $name failed: $log")
        }
        val program = GLES31.glCreateProgram()
        GLES31.glAttachShader(program, shader)
        GLES31.glLinkProgram(program)
        GLES31.glDeleteShader(shader)
        GLES31.glGetProgramiv(program, GLES31.GL_LINK_STATUS, status, 0)
        if (status[0] == 0) {
            val log = GLES31.glGetProgramInfoLog(program)
            GLES31.glDeleteProgram(program)
            error("Portrait program $name link failed: $log")
        }
        return program
    }

    private fun texture2d(w: Int, h: Int, format: Int, levels: Int, linear: Boolean = false): Int {
        val id = IntArray(1).also { GLES31.glGenTextures(1, it, 0) }[0]
        GLES31.glBindTexture(GLES31.GL_TEXTURE_2D, id)
        GLES31.glTexStorage2D(GLES31.GL_TEXTURE_2D, levels, format, w, h)
        val min = if (levels > 1) GLES31.GL_NEAREST_MIPMAP_NEAREST else if (linear) GLES31.GL_LINEAR else GLES31.GL_NEAREST
        GLES31.glTexParameteri(GLES31.GL_TEXTURE_2D, GLES31.GL_TEXTURE_MIN_FILTER, min)
        GLES31.glTexParameteri(GLES31.GL_TEXTURE_2D, GLES31.GL_TEXTURE_MAG_FILTER, if (linear) GLES31.GL_LINEAR else GLES31.GL_NEAREST)
        GLES31.glTexParameteri(GLES31.GL_TEXTURE_2D, GLES31.GL_TEXTURE_WRAP_S, GLES31.GL_CLAMP_TO_EDGE)
        GLES31.glTexParameteri(GLES31.GL_TEXTURE_2D, GLES31.GL_TEXTURE_WRAP_T, GLES31.GL_CLAMP_TO_EDGE)
        checkGl("portrait texture ${w}x$h")
        return id
    }

    private fun textureArray(w: Int, h: Int, layers: Int, linear: Boolean): Int {
        val id = IntArray(1).also { GLES31.glGenTextures(1, it, 0) }[0]
        GLES31.glBindTexture(GLES30.GL_TEXTURE_2D_ARRAY, id)
        GLES31.glTexStorage3D(GLES30.GL_TEXTURE_2D_ARRAY, 1, GLES30.GL_RGBA16F, w, h, layers)
        val filter = if (linear) GLES31.GL_LINEAR else GLES31.GL_NEAREST
        GLES31.glTexParameteri(GLES30.GL_TEXTURE_2D_ARRAY, GLES31.GL_TEXTURE_MIN_FILTER, filter)
        GLES31.glTexParameteri(GLES30.GL_TEXTURE_2D_ARRAY, GLES31.GL_TEXTURE_MAG_FILTER, filter)
        GLES31.glTexParameteri(GLES30.GL_TEXTURE_2D_ARRAY, GLES31.GL_TEXTURE_WRAP_S, GLES31.GL_CLAMP_TO_EDGE)
        GLES31.glTexParameteri(GLES30.GL_TEXTURE_2D_ARRAY, GLES31.GL_TEXTURE_WRAP_T, GLES31.GL_CLAMP_TO_EDGE)
        checkGl("portrait array ${w}x${h}x$layers")
        return id
    }

    private fun bindSampler(program: Int, name: String, unit: Int, target: Int, texture: Int) {
        GLES31.glActiveTexture(GLES31.GL_TEXTURE0 + unit)
        GLES31.glBindTexture(target, texture)
        GLES31.glUniform1i(loc(program, name), unit)
        GLES31.glActiveTexture(GLES31.GL_TEXTURE0)
    }

    private fun dispatch(w: Int, h: Int) {
        GLES31.glDispatchCompute((w + 15) / 16, (h + 15) / 16, 1)
        GLES31.glMemoryBarrier(GLES31.GL_ALL_BARRIER_BITS)
    }

    private fun readRgba8(texture: Int, w: Int, h: Int): ByteBuffer {
        if (framebuffer == 0) framebuffer = IntArray(1).also { GLES30.glGenFramebuffers(1, it, 0) }[0]
        GLES30.glBindFramebuffer(GLES30.GL_FRAMEBUFFER, framebuffer)
        try {
            GLES30.glFramebufferTexture2D(GLES30.GL_FRAMEBUFFER, GLES30.GL_COLOR_ATTACHMENT0, GLES30.GL_TEXTURE_2D, texture, 0)
            check(GLES30.glCheckFramebufferStatus(GLES30.GL_FRAMEBUFFER) == GLES30.GL_FRAMEBUFFER_COMPLETE) {
                "Portrait thumbnail framebuffer incomplete"
            }
            val buf = ByteBuffer.allocateDirect(w * h * 4).order(ByteOrder.nativeOrder())
            GLES30.glPixelStorei(GLES30.GL_PACK_ALIGNMENT, 1)
            GLES30.glReadPixels(0, 0, w, h, GLES30.GL_RGBA, GLES30.GL_UNSIGNED_BYTE, buf)
            checkGl("portrait thumbnail readback")
            return buf
        } finally {
            GLES30.glBindFramebuffer(GLES30.GL_FRAMEBUFFER, 0)
        }
    }

    private fun loc(program: Int, name: String) = GLES31.glGetUniformLocation(program, name)

    private fun checkGl(op: String) {
        val e = GLES20.glGetError()
        check(e == GLES20.GL_NO_ERROR) { "$op failed with GLES error 0x${e.toString(16)}" }
    }

    companion object {
        /** EXIF orientation -> clockwise quarter turns that make the stored image upright (mirrors ignored). */
        fun clockwiseTurns(exifOrientation: Int): Int = when (exifOrientation) {
            6, 7 -> 1
            3, 4 -> 2
            8, 5 -> 3
            else -> 0
        }

        /** Rotates a square n x n map with [channels] interleaved channels by [turns] clockwise quarter turns. */
        fun rotateCw(src: FloatArray, n: Int, channels: Int, turns: Int): FloatArray {
            var cur = src
            repeat(turns and 3) {
                val dst = FloatArray(cur.size)
                for (y in 0 until n) for (x in 0 until n) {
                    // dst(x, y) = src(x = y, y = n - 1 - x)
                    val s = ((n - 1 - x) * n + y) * channels
                    val d = (y * n + x) * channels
                    for (c in 0 until channels) dst[d + c] = cur[s + c]
                }
                cur = dst
            }
            return cur
        }

        fun dumpEnabled(): Boolean = try {
            Class.forName("android.os.SystemProperties").getMethod("get", String::class.java)
                .invoke(null, "debug.rawlens.portrait_dump") == "1"
        } catch (_: Exception) { false }
    }
}
