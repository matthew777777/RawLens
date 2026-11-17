// SPDX-License-Identifier: AGPL-3.0-only
package com.matthew.rawlens

import android.content.Context
import android.opengl.GLES30
import android.opengl.GLES31
import android.os.SystemClock
import java.nio.ByteBuffer
import java.nio.ByteOrder

/** RapidRAW v1.7.0 (Furka engine) RAW preprocessing: develop.wgsl chroma denoise + sharpen.
 *
 * Mirrors upstream decode/develop.rs `layout()`/`run()` with `camera = None` (RawLens has
 * already reconstructed highlights and converted to scene-linear). Everything runs on the
 * caller's EGL context; the noise medians are radix-selected on the GPU exactly as upstream
 * (md_sample/md_hist/md_pick), so nothing is read back to the CPU and no pass waits on one.
 * The final out_band pass is fused into the JPEG output shader via [fusedSource].
 */
internal class RapidRawDevelop(context: Context, private val dispatchBudget: Int = 262144) {
    private val source = context.assets.open(SHADER).bufferedReader().use { it.readText() }
    private val programs = IntArray(11)
    private var owner: Thread? = null

    private data class Texture(val id: Int, val w: Int, val h: Int, val format: Int, val bytes: Long)

    /** GPU state the fused output pass reads. Valid only inside [withPrepared]. */
    data class Prepared(val source: AmazeGpuOutput, val halfPlane: Int, val cleanChroma: Int,
                        val half: Pair<Int, Int>, val medians: Int, val amounts: Amounts)

    data class Amounts(val colorNoiseReduction: Float, val sharpening: Float) {
        init { require(colorNoiseReduction.isFinite() && sharpening.isFinite()) }
        /** Upstream clamps color NR to [0,1] in image_loader.rs and again in layout(). */
        val denoise get() = colorNoiseReduction.coerceIn(0f, 1f)
        val sharpen get() = sharpening.coerceAtLeast(0f)
        val active get() = denoise > 0f || sharpen > 0f
    }

    fun fusedSource(output: String, basic: Boolean, gainmap: Boolean): String =
        "#define MODE 8\n#define RAPIDRAW_OUTPUT_FUSED 1\n#define RAPIDRAW_BASIC ${if (basic) 1 else 0}\n" +
            "#define RAPIDRAW_GAINMAP ${if (gainmap) 1 else 0}\nlayout(local_size_x=8,local_size_y=8) in;\n$source\n$output"

    fun bindPrepared(program: Int, prepared: Prepared) {
        intArrayOf(prepared.source.textureId, prepared.halfPlane, prepared.cleanChroma).forEachIndexed { unit, id ->
            GLES30.glActiveTexture(GLES30.GL_TEXTURE0 + unit)
            GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, id)
        }
        GLES31.glBindBufferBase(GLES31.GL_SHADER_STORAGE_BUFFER, 2, prepared.medians)
        ivec(program, "u_full_size", prepared.source.width, prepared.source.height)
        ivec(program, "u_half", prepared.half.first, prepared.half.second)
        float(program, "u_amount", prepared.amounts.denoise)
        float(program, "u_sharpen", prepared.amounts.sharpen)
    }

    /** Standalone out_band into a new RGBA16F texture; used by device parity tests. */
    fun <T> withDeveloped(scene: AmazeGpuOutput, amounts: Amounts, consume: (AmazeGpuOutput) -> T): T =
        withPrepared(scene, amounts) { prepared ->
            val ids = IntArray(1); GLES30.glGenTextures(1, ids, 0); check(ids[0] != 0)
            val bytes = scene.width.toLong() * scene.height * 8
            var tracked = false
            try {
                GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, ids[0])
                GLES30.glTexStorage2D(GLES30.GL_TEXTURE_2D, 1, GLES30.GL_RGBA16F, scene.width, scene.height)
                MemoryLeakDiagnostics.glTextureAllocated(bytes); tracked = true
                val p = program(8); GLES31.glUseProgram(p); bindPrepared(p, prepared)
                ivec(p, "u_size", scene.width, scene.height)
                GLES31.glBindImageTexture(0, ids[0], 0, false, 0, GLES31.GL_WRITE_ONLY, GLES30.GL_RGBA16F)
                dispatchSlices(p, scene.width, scene.height)
                GLES31.glMemoryBarrier(GLES31.GL_SHADER_IMAGE_ACCESS_BARRIER_BIT or GLES31.GL_TEXTURE_FETCH_BARRIER_BIT)
                checkGl("standalone out_band")
                consume(scene.copy(textureId = ids[0]))
            } finally {
                GLES30.glDeleteTextures(1, ids, 0)
                if (tracked) MemoryLeakDiagnostics.glTextureReleased(bytes)
            }
        }

    fun <T> withPrepared(scene: AmazeGpuOutput, amounts: Amounts, consume: (Prepared) -> T): T {
        require(scene.internalFormat == AmazeTextureFormat.RGBA16F && scene.colorDomain == AmazeColorDomain.ACESCG)
        require(amounts.active) { "RapidRAW develop with zero amounts" }
        check(owner == null || owner == Thread.currentThread()) { "RapidRAW develop must stay on its EGL owner" }
        owner = Thread.currentThread()
        val start = SystemClock.elapsedRealtime()
        val textures = mutableListOf<Texture>()
        var live = 0L; var peak = 0L
        fun texture(w: Int, h: Int, format: Int): Texture {
            val ids = IntArray(1); GLES30.glGenTextures(1, ids, 0); check(ids[0] != 0)
            val bytes = w.toLong() * h * (if (format == GLES30.GL_RGBA32F) 16 else 8)
            val t = Texture(ids[0], w, h, format, bytes)
            textures.add(t); MemoryLeakDiagnostics.glTextureAllocated(bytes)
            live += bytes; peak = maxOf(peak, live)
            GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, t.id)
            GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_MIN_FILTER, GLES30.GL_NEAREST)
            GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_MAG_FILTER, GLES30.GL_NEAREST)
            GLES30.glTexStorage2D(GLES30.GL_TEXTURE_2D, 1, format, w, h)
            checkGl("develop texture allocation")
            return t
        }
        fun release(t: Texture) {
            if (textures.remove(t)) {
                GLES30.glDeleteTextures(1, intArrayOf(t.id), 0)
                MemoryLeakDiagnostics.glTextureReleased(t.bytes); live -= t.bytes
            }
        }
        val w = scene.width; val h = scene.height
        val l = Layout(w, h)
        val denoise = amounts.denoise > 0f
        val sharpen = amounts.sharpen > 0f && l.sharpenSamples > 0
        val buffers = IntArray(3)
        try {
            GLES30.glGenBuffers(3, buffers, 0); check(buffers.all { it != 0 })
            val maxSamples = maxOf(1L, if (denoise) l.maxDenoiseSamples else 0L, if (sharpen) l.sharpenSamples.toLong() else 0L)
            require(maxSamples * 4 <= Int.MAX_VALUE)
            storage(buffers[0], (maxSamples * 4).toInt())
            storage(buffers[1], STATS_WORDS * 4, zeroed = true)
            storage(buffers[2], 8 * 4, zeroed = true)
            for (i in 0..2) GLES31.glBindBufferBase(GLES31.GL_SHADER_STORAGE_BUFFER, i, buffers[i])
            val run = Run(scene, amounts, l)
            var halfS: Texture? = null; var clean: Texture? = null
            if (denoise) {
                halfS = texture(l.pw, l.ph, GLES30.GL_RGBA32F)
                run.grid(0, halfS)
                run.median(0, 0, l.halfSamples, a = halfS)
                run.median(1, 1, 2 * l.halfSamples, a = halfS)
                val quarterS = texture(l.qpw, l.qph, GLES30.GL_RGBA32F)
                run.grid(1, quarterS, a = halfS)
                run.median(2, 2, l.quarterSamples, a = quarterS)
                run.median(3, 3, 2 * l.quarterSamples, a = quarterS)
                val quarterW = texture(l.qpw, l.qph, GLES30.GL_RGBA16F)
                run.grid(2, quarterW, a = quarterS) { int(it, "u_scale", 1) }
                val quarterF = texture(l.qw, l.qh, GLES30.GL_RGBA16F)
                run.grid(3, quarterF, a = quarterS, b = quarterW) { int(it, "u_scale", 1); int(it, "u_use_ref", 0) }
                release(quarterW)
                val refUp = texture(l.hw, l.hh, GLES30.GL_RGBA16F)
                run.grid(4, refUp, a = quarterS, b = quarterF, c = halfS)
                release(quarterS); release(quarterF)
                val halfW = texture(l.pw, l.ph, GLES30.GL_RGBA16F)
                run.grid(2, halfW, a = halfS) { int(it, "u_scale", 0) }
                val halfF = texture(l.hw, l.hh, GLES30.GL_RGBA16F)
                run.grid(3, halfF, a = halfS, b = halfW, c = refUp) { int(it, "u_scale", 0); int(it, "u_use_ref", 1) }
                release(halfW)
                run.median(4, 4, l.coreSamples, a = halfF, b = refUp)
                clean = texture(l.hw, l.hh, GLES30.GL_RGBA16F)
                run.grid(5, clean, a = halfS, b = refUp, c = halfF)
                release(refUp); release(halfF)
                run.median(5, 5, l.fullSamples)
            }
            if (sharpen) run.median(6, 6, l.sharpenSamples)
            // Fused output still needs bindable textures even when only sharpening.
            val halfId = halfS?.id ?: scene.textureId
            val cleanId = clean?.id ?: scene.textureId
            Log.i(TAG, "GPU ${w}x$h nr=${amounts.denoise} sharpen=${amounts.sharpen} submit=" +
                "${SystemClock.elapsedRealtime() - start}ms peakScratch=${peak + maxSamples * 4}B readback=0B")
            return consume(Prepared(scene, halfId, cleanId, l.hw to l.hh, buffers[2],
                Amounts(amounts.denoise, if (sharpen) amounts.sharpen else 0f)))
        } finally {
            textures.toList().forEach(::release)
            for (i in 0..2) GLES31.glBindBufferBase(GLES31.GL_SHADER_STORAGE_BUFFER, i, 0)
            GLES30.glBindBuffer(GLES31.GL_SHADER_STORAGE_BUFFER, 0)
            if (buffers.any { it != 0 }) GLES30.glDeleteBuffers(3, buffers, 0)
        }
    }

    /** Sizes and sample counts of upstream layout(), orientation already applied. */
    internal class Layout(val w: Int, val h: Int) {
        val hw = (w + 1) / 2; val hh = (h + 1) / 2
        val pw = hw + 2 * PAD; val ph = hh + 2 * PAD
        val qw = (hw + 1) / 2; val qh = (hh + 1) / 2
        val qpw = qw + 2 * PAD; val qph = qh + 2 * PAD
        val halfSamples = noiseSamples(pw, ph, 1)
        val quarterSamples = noiseSamples(qpw, qph, 1)
        val coreSamples = ((hw.toLong() * hh + 6) / 7).toInt()
        val fullSamples = noiseSamples(w, h, 2)
        val sharpenSamples = ((h + 15) / 16) * ((w + 4) / 5)
        val maxDenoiseSamples = maxOf(2L * halfSamples, 2L * quarterSamples, coreSamples.toLong(), fullSamples.toLong())
        companion object {
            fun noiseSamples(w: Int, h: Int, channels: Int) = ((h + 7) / 8) * (((w - 4).coerceAtLeast(0) + 6) / 7) * channels
        }
    }

    private inner class Run(val scene: AmazeGpuOutput, val amounts: Amounts, val l: Layout) {
        fun common(p: Int) {
            ivec(p, "u_full_size", scene.width, scene.height)
            ivec(p, "u_half", l.hw, l.hh); ivec(p, "u_quarter", l.qw, l.qh)
            float(p, "u_amount", amounts.denoise); float(p, "u_sharpen", amounts.sharpen)
        }
        fun bind(a: Texture?, b: Texture?, c: Texture?) {
            val ids = intArrayOf(scene.textureId, a?.id ?: 0, b?.id ?: 0, c?.id ?: 0)
            for (i in ids.indices) { GLES30.glActiveTexture(GLES30.GL_TEXTURE0 + i); GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, ids[i]) }
        }
        fun grid(mode: Int, out: Texture, a: Texture? = null, b: Texture? = null, c: Texture? = null, uniforms: (Int) -> Unit = {}) {
            val p = program(mode); GLES31.glUseProgram(p); bind(a, b, c); common(p)
            ivec(p, "u_size", out.w, out.h); uniforms(p)
            GLES31.glBindImageTexture(0, out.id, 0, false, 0, GLES31.GL_WRITE_ONLY, out.format)
            // The 25-tap filter is the heaviest kernel; slice it finer on large frames.
            dispatchSlices(p, out.w, out.h, if (mode == 3) minOf(dispatchBudget, 131072) else dispatchBudget)
            barrier(); checkGl("develop pass $mode")
        }
        /** md_sample + three md_hist/md_pick radix passes; result lands in medians[slot]. */
        fun median(kind: Int, slot: Int, n: Int, a: Texture? = null, b: Texture? = null) {
            val groups = ((n + 255) / 256).coerceIn(1, STRIDE_GROUPS)
            linear(7, groups, a, b) { int(it, "u_kind", kind); int(it, "u_count", n) }
            for (pass in 0..2) {
                linear(9, groups) { int(it, "u_pass", pass); int(it, "u_count", n) }
                linear(10, 1) { int(it, "u_pass", pass); int(it, "u_count", n); int(it, "u_slot", slot) }
            }
        }
        private fun linear(mode: Int, groups: Int, a: Texture? = null, b: Texture? = null, uniforms: (Int) -> Unit) {
            val p = program(mode); GLES31.glUseProgram(p); bind(a, b, null); common(p); uniforms(p)
            GLES31.glDispatchCompute(groups, 1, 1)
            barrier(); checkGl("develop median pass $mode")
        }
        private fun barrier() = GLES31.glMemoryBarrier(GLES31.GL_SHADER_IMAGE_ACCESS_BARRIER_BIT or
            GLES31.GL_TEXTURE_FETCH_BARRIER_BIT or GLES31.GL_SHADER_STORAGE_BARRIER_BIT)
    }

    /** Workgroup and pixel origins move together, so shared-memory halos stay identical.
     * Bounds in-flight work so a still save cannot monopolize the mobile GPU queue. */
    fun dispatchSlices(program: Int, width: Int, height: Int, budget: Int = dispatchBudget) {
        if (width.toLong() * height <= budget) {
            ivec(program, "u_dispatch_offset", 0, 0)
            GLES31.glDispatchCompute((width + 7) / 8, (height + 7) / 8, 1)
            return
        }
        for ((index, slice) in GpuScheduling.slices(width, height, 8, 8, budget).withIndex()) {
            ivec(program, "u_dispatch_offset", slice.x, slice.y)
            GLES31.glDispatchCompute(slice.groupsX, slice.groupsY, 1)
            val sync = GLES30.glFenceSync(GLES30.GL_SYNC_GPU_COMMANDS_COMPLETE, 0)
            check(sync != 0L) { "Develop slice fence allocation failed" }
            try {
                val deadline = SystemClock.elapsedRealtime() + 10_000L
                while (true) {
                    val r = GLES30.glClientWaitSync(sync, GLES30.GL_SYNC_FLUSH_COMMANDS_BIT, 1_000_000L)
                    if (r == GLES30.GL_ALREADY_SIGNALED || r == GLES30.GL_CONDITION_SATISFIED) break
                    check(r != GLES30.GL_WAIT_FAILED && SystemClock.elapsedRealtime() < deadline) { "Develop slice fence failed/timed out" }
                    Thread.yield()
                }
            } finally { GLES30.glDeleteSync(sync) }
            if (index % 4 == 3) Thread.sleep(1)
        }
    }

    fun close() {
        check(owner == null || owner == Thread.currentThread())
        programs.forEach { if (it != 0) { GLES30.glDeleteProgram(it); MemoryLeakDiagnostics.glProgramReleased() } }
        programs.fill(0); owner = null
    }

    private fun storage(buffer: Int, bytes: Int, zeroed: Boolean = false) {
        GLES30.glBindBuffer(GLES31.GL_SHADER_STORAGE_BUFFER, buffer)
        val data = if (zeroed) ByteBuffer.allocateDirect(bytes).order(ByteOrder.nativeOrder()) else null
        GLES30.glBufferData(GLES31.GL_SHADER_STORAGE_BUFFER, bytes, data, GLES30.GL_DYNAMIC_COPY)
        checkGl("develop buffer allocation")
    }

    private fun program(mode: Int): Int {
        if (programs[mode] != 0) return programs[mode]
        val shader = GLES31.glCreateShader(GLES31.GL_COMPUTE_SHADER); check(shader != 0)
        try {
            GLES31.glShaderSource(shader, "#version 310 es\n#define MODE $mode\n$source")
            GLES31.glCompileShader(shader); val ok = IntArray(1)
            GLES31.glGetShaderiv(shader, GLES31.GL_COMPILE_STATUS, ok, 0)
            check(ok[0] != 0) { "RapidRAW develop shader $mode: ${GLES31.glGetShaderInfoLog(shader)}" }
            val p = GLES31.glCreateProgram(); check(p != 0)
            try {
                GLES31.glAttachShader(p, shader); GLES31.glLinkProgram(p)
                GLES31.glGetProgramiv(p, GLES31.GL_LINK_STATUS, ok, 0)
                check(ok[0] != 0) { "RapidRAW develop program $mode: ${GLES31.glGetProgramInfoLog(p)}" }
            } catch (e: Throwable) { GLES31.glDeleteProgram(p); throw e }
            programs[mode] = p; MemoryLeakDiagnostics.glProgramAllocated(); return p
        } finally { GLES31.glDeleteShader(shader) }
    }
    private fun int(p: Int, n: String, v: Int) = GLES30.glUniform1i(GLES30.glGetUniformLocation(p, n), v)
    private fun float(p: Int, n: String, v: Float) = GLES30.glUniform1f(GLES30.glGetUniformLocation(p, n), v)
    private fun ivec(p: Int, n: String, x: Int, y: Int) = GLES30.glUniform2i(GLES30.glGetUniformLocation(p, n), x, y)
    private fun checkGl(stage: String) { val e = GLES30.glGetError(); check(e == GLES30.GL_NO_ERROR) { "$stage GL 0x${e.toString(16)}" } }

    companion object {
        private const val TAG = "RapidRawDevelop"
        const val SHADER = "shaders/display/rapidraw_develop.glsl"
        private const val PAD = 2
        private const val STATS_WORDS = 2050
        private const val STRIDE_GROUPS = 1024
    }
}
