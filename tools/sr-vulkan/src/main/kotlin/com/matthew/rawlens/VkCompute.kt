// SPDX-FileCopyrightText: 2026 RawLens contributors
// SPDX-License-Identifier: GPL-3.0-or-later
package com.matthew.rawlens

import android.content.res.AssetManager
import android.opengl.GLES30
import java.io.Closeable
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger

/**
 * Unified Vulkan compute host (shared phone/desktop bytes): the exact
 * analogue of the retired GLES host — [VkImage]/[VkArena] mirror its
 * texture/arena twins, [VkProgramCache]/[VkBound]/[VkSession] mirror
 * `ProgramCache`/`Bound`/`Session` with identical call shapes, so the pass
 * code keeps the same lines. Descriptor bindings and uniform-block layout
 * come from the shader manifest (`spirv/manifest.json`, read through the
 * platform [AssetManager]: APK assets on Android, the staged directory or
 * classpath fallback on desktop).
 */

// ---------------------------------------------------------------------------
// Manifest (tools/srvk_shader_transform.py, version 2).

private sealed interface JVal {
    data class Str(val v: String) : JVal
    data class Num(val v: Long) : JVal
    data object Null : JVal
    data class Obj(val v: Map<String, JVal>) : JVal
}

/** Minimal exact-shape JSON reader for the generated manifest (no arrays). */
private object SrvkJson {
    fun parse(text: String): Map<String, JVal> {
        val p = Parser(text)
        val root = p.value()
        require(root is JVal.Obj) { "manifest root must be an object" }
        p.end()
        return root.v
    }

    private class Parser(val s: String) {
        var i = 0
        fun end() {
            ws()
            require(i == s.length) { "trailing bytes in manifest at $i" }
        }
        fun ws() {
            while (i < s.length && s[i].isWhitespace()) i++
        }

        fun value(): JVal {
            ws()
            require(i < s.length) { "truncated manifest" }
            return when (s[i]) {
                '"' -> JVal.Str(string())
                '{' -> obj()
                'n' -> {
                    require(s.startsWith("null", i)) { "bad literal at $i" }
                    i += 4
                    JVal.Null
                }
                '-', in '0'..'9' -> JVal.Num(number())
                else -> throw IllegalArgumentException("unexpected char at $i")
            }
        }

        fun obj(): JVal.Obj {
            i++ // {
            val map = LinkedHashMap<String, JVal>()
            ws()
            if (i < s.length && s[i] == '}') {
                i++
                return JVal.Obj(map)
            }
            while (true) {
                ws()
                require(i < s.length && s[i] == '"') { "object key expected at $i" }
                val key = string()
                ws()
                require(i < s.length && s[i] == ':') { "colon expected at $i" }
                i++
                map[key] = value()
                ws()
                require(i < s.length) { "truncated object" }
                if (s[i] == '}') {
                    i++
                    return JVal.Obj(map)
                }
                require(s[i] == ',') { "comma expected at $i" }
                i++
            }
        }

        fun string(): String {
            i++ // "
            val out = StringBuilder()
            while (true) {
                require(i < s.length) { "truncated string" }
                val c = s[i++]
                if (c == '"') return out.toString()
                if (c == '\\') {
                    require(i < s.length) { "truncated escape" }
                    when (val e = s[i++]) {
                        '"', '\\', '/' -> out.append(e)
                        'n' -> out.append('\n')
                        't' -> out.append('\t')
                        else -> throw IllegalArgumentException("bad escape $e")
                    }
                } else {
                    out.append(c)
                }
            }
        }

        fun number(): Long {
            val start = i
            if (i < s.length && s[i] == '-') i++
            while (i < s.length && s[i].isDigit()) i++
            require(i > start) { "bad number at $start" }
            return s.substring(start, i).toLong()
        }
    }
}

private fun JVal.asObj(what: String): Map<String, JVal> {
    require(this is JVal.Obj) { "$what must be an object" }
    return v
}

private fun Map<String, JVal>.str(key: String, what: String): String {
    val v = get(key) ?: throw IllegalArgumentException("$what.$key missing")
    require(v is JVal.Str) { "$what.$key must be a string" }
    return v.v
}

private fun Map<String, JVal>.long(key: String, what: String): Long {
    val v = get(key) ?: throw IllegalArgumentException("$what.$key missing")
    require(v is JVal.Num) { "$what.$key must be a number" }
    return v.v
}

private fun Map<String, JVal>.obj(key: String, what: String): Map<String, JVal> =
    (get(key) ?: throw IllegalArgumentException("$what.$key missing")).asObj("$what.$key")

/** One shader's binding contract, exactly as the transform emitted it. */
internal data class SrvkShaderEntry(
    val blockBinding: Int,
    val blockBytes: Int,
    val uniforms: Map<String, SrvkUniform>,
    val opaque: Map<String, SrvkOpaque>
)

internal data class SrvkUniform(val offset: Int, val bytes: Int, val type: String)
internal data class SrvkOpaque(
    val binding: Int,
    val kind: String,
    val otype: String,
    val format: String?,
    val esslBinding: Int?
)

internal class SrvkManifest(private val assets: AssetManager) {
    val shaders: Map<String, SrvkShaderEntry> by lazy {
        val text = assets.open("spirv/manifest.json").bufferedReader().readText()
        val root = SrvkJson.parse(text)
        val version = root.long("version", "manifest")
        require(version == 2L) { "unsupported shader manifest version $version" }
        root.obj("shaders", "manifest").mapValues { (name, v) ->
            val e = v.asObj("shaders.$name")
            SrvkShaderEntry(
                blockBinding = e.long("blockBinding", name).toInt(),
                blockBytes = e.long("blockBytes", name).toInt(),
                uniforms = e.obj("uniforms", name).mapValues { (u, uv) ->
                    val m = uv.asObj("$name.uniforms.$u")
                    SrvkUniform(
                        m.long("offset", u).toInt(),
                        m.long("bytes", u).toInt(),
                        m.str("type", u)
                    )
                },
                opaque = e.obj("opaque", name).mapValues { (o, ov) ->
                    val m = ov.asObj("$name.opaque.$o")
                    val fmt = m["format"]
                    val essl = m["esslBinding"]
                    SrvkOpaque(
                        m.long("binding", o).toInt(),
                        m.str("kind", o),
                        m.str("otype", o),
                        if (fmt is JVal.Str) fmt.v else null,
                        if (essl is JVal.Num) essl.v.toInt() else null
                    )
                }
            )
        }
    }

    fun entry(asset: String): SrvkShaderEntry =
        shaders[asset] ?: throw IllegalArgumentException("no manifest entry for $asset")
}

// ---------------------------------------------------------------------------
// Images (GL-texture twin).

internal object VkFormats {
    /** GLES internal-format token -> SrvkFormat int (srvk_compute.h). */
    fun toSrvk(glFormat: Int): Int = when (glFormat) {
        GLES30.GL_R32F -> 0
        GLES30.GL_R32UI -> 1
        GLES30.GL_RGBA32F -> 2
        GLES30.GL_R16UI -> 3
        else -> throw IllegalArgumentException("unsupported pooled texture format 0x${glFormat.toString(16)}")
    }

    fun isUint(glFormat: Int): Boolean = when (glFormat) {
        GLES30.GL_R32UI, GLES30.GL_R16UI -> true
        GLES30.GL_R32F, GLES30.GL_RGBA32F -> false
        else -> throw IllegalArgumentException("unsupported pooled texture format 0x${glFormat.toString(16)}")
    }
}

/**
 * Vulkan image twin of the retired GL texture type: same constructor shape,
 * same upload preamble lines, same close discipline. `id` is a
 * host-assigned serial (GL texture names are driver-assigned; both are
 * opaque Int handles to the orchestration).
 */
internal class VkImage(
    private val vk: SrVulkan.Handle,
    val width: Int,
    val height: Int,
    val internalFormat: Int,
    internal val handle: Long
) : Closeable {
    val byteSize: Long = width.toLong() * height * when (internalFormat) {
        GLES30.GL_R32F -> 4L
        GLES30.GL_R32UI -> 4L
        GLES30.GL_RGBA32F -> 16L
        GLES30.GL_R16UI -> 2L
        else -> throw IllegalArgumentException(
            "Unsupported pooled texture format 0x${internalFormat.toString(16)}"
        )
    }
    val id: Int = nextId.getAndIncrement()
    private var closed = false

    fun uploadR32f(values: FloatArray, uploads: UploadBuffers) {
        require(internalFormat == GLES30.GL_R32F && values.size == width * height)
        val buffer = uploads.floats(values.size)
        buffer.put(values).flip()
        vk.writeImage(handle, floatsToDirect(buffer, values.size))
    }

    fun uploadRgba32f(values: FloatArray, uploads: UploadBuffers) {
        require(internalFormat == GLES30.GL_RGBA32F && values.size == width * height * 4)
        val buffer = uploads.floats(values.size)
        buffer.put(values).flip()
        vk.writeImage(handle, floatsToDirect(buffer, values.size))
    }

    fun uploadRaw16(source: ByteBuffer, layout: RawPlaneLayout, crop: RawCrop) {
        require(internalFormat == GLES30.GL_R16UI && width == crop.width && height == crop.height)
        val input = source.duplicate().order(ByteOrder.nativeOrder())
        val origin = input.position()
        val offset = origin + crop.top * layout.rowStride + crop.left * layout.pixelStride
        val lastByte = offset.toLong() +
            (crop.height - 1).toLong() * layout.rowStride +
            (crop.width - 1).toLong() * layout.pixelStride + Short.SIZE_BYTES
        require(lastByte <= input.limit().toLong()) { "RAW_SENSOR buffer is truncated" }
        // Vulkan has no UNPACK_ROW_LENGTH: gather the crop into tight rows.
        val tight = ByteBuffer.allocateDirect(width * height * Short.SIZE_BYTES)
            .order(ByteOrder.nativeOrder())
        if (layout.pixelStride == Short.SIZE_BYTES) {
            for (y in 0 until height) {
                input.position(offset + y * layout.rowStride)
                val row = input.slice().order(ByteOrder.nativeOrder())
                row.limit(width * Short.SIZE_BYTES)
                tight.put(row)
            }
        } else {
            for (y in 0 until height) {
                for (x in 0 until width) {
                    tight.putShort(input.getShort(offset + y * layout.rowStride + x * layout.pixelStride))
                }
            }
        }
        tight.flip()
        vk.writeImage(handle, tight)
    }

    fun downloadR32f(): FloatArray {
        require(internalFormat == GLES30.GL_R32F)
        return directToFloats(width * height)
    }

    fun downloadRgba32f(): FloatArray {
        require(internalFormat == GLES30.GL_RGBA32F)
        return directToFloats(width * height * 4)
    }

    fun downloadR32ui(): IntArray {
        require(internalFormat == GLES30.GL_R32UI)
        val direct = ByteBuffer.allocateDirect(byteSize.toInt()).order(ByteOrder.nativeOrder())
        vk.readImage(handle, direct)
        val out = IntArray(width * height)
        direct.asIntBuffer().get(out)
        return out
    }

    fun downloadRgba32fRegion(x: Int, y: Int, w: Int, h: Int): FloatArray {
        require(internalFormat == GLES30.GL_RGBA32F)
        require(x >= 0 && y >= 0 && w > 0 && h > 0 && x + w <= width && y + h <= height)
        return directToFloatsRegion(x, y, w, h, 4)
    }

    fun downloadR32fRegion(x: Int, y: Int, w: Int, h: Int): FloatArray {
        require(internalFormat == GLES30.GL_R32F)
        require(x >= 0 && y >= 0 && w > 0 && h > 0 && x + w <= width && y + h <= height)
        return directToFloatsRegion(x, y, w, h, 1)
    }

    private fun floatsToDirect(view: java.nio.FloatBuffer, count: Int): ByteBuffer {
        val direct = ByteBuffer.allocateDirect(count * Float.SIZE_BYTES).order(ByteOrder.nativeOrder())
        direct.asFloatBuffer().put(view.duplicate()).flip()
        return direct
    }

    private fun directToFloats(count: Int): FloatArray {
        val direct = ByteBuffer.allocateDirect(count * Float.SIZE_BYTES).order(ByteOrder.nativeOrder())
        vk.readImage(handle, direct)
        val out = FloatArray(count)
        direct.asFloatBuffer().get(out)
        return out
    }

    private fun directToFloatsRegion(x: Int, y: Int, w: Int, h: Int, channels: Int): FloatArray {
        val direct = ByteBuffer.allocateDirect(w * h * channels * Float.SIZE_BYTES)
            .order(ByteOrder.nativeOrder())
        vk.readImageRegion(handle, x, y, w, h, direct)
        val out = FloatArray(w * h * channels)
        direct.asFloatBuffer().get(out)
        return out
    }

    override fun close() {
        if (closed) return
        closed = true
        vk.destroyImage(handle)
    }

    private companion object {
        val nextId = AtomicInteger(1)
    }
}

// ---------------------------------------------------------------------------
// Arena (Arena twin).

internal class VkArena(private val vk: SrVulkan.Handle) : Closeable {
    val memory = RawSrTextureMemory()
    private val live = LinkedHashSet<VkImage>()
    fun texture(width: Int, height: Int, format: Int) =
        VkImage(vk, width, height, format, vk.createImage(width, height, VkFormats.toSrvk(format))).also {
            live.add(it); memory.allocate(it.byteSize); register(it)
        }

    fun release(image: VkImage) {
        check(live.remove(image)) { "Texture released twice" }
        unregister(image)
        image.close(); memory.release(image.byteSize)
    }

    override fun close() {
        // Same rationale as the GL arena: every submit fence-waited, so no
        // terminal drain; command order sequences merges.
        live.toList().asReversed().forEach(::release)
        check(memory.liveBytes == 0L)
    }

    companion object {
        // Process-wide Int-handle registry: the Vulkan analogue of GL
        // texture names, letting consume() read live outputs by ID exactly
        // like the phone path. Entries exist only while the arena holds
        // the image; stale IDs fail loudly instead of aliasing.
        private val liveById = ConcurrentHashMap<Int, VkImage>()

        internal fun register(image: VkImage) {
            liveById[image.id] = image
        }

        internal fun unregister(image: VkImage) {
            liveById.remove(image.id)
        }

        internal fun lookup(id: Int): VkImage =
            liveById[id] ?: throw IllegalArgumentException("Vulkan image $id is not live")
    }
}

/**
 * Download live GPU outputs by their Int handle inside `consume` (mirrors
 * reading GL textures by name on the phone). Only valid while the merge
 * arena holds the image.
 */
fun vkDownloadRgba32f(id: Int): FloatArray = VkArena.lookup(id).downloadRgba32f()
fun vkDownloadR32f(id: Int): FloatArray = VkArena.lookup(id).downloadR32f()
fun vkDownloadR32ui(id: Int): IntArray = VkArena.lookup(id).downloadR32ui()
fun vkDownloadRgba32fRegion(id: Int, x: Int, y: Int, w: Int, h: Int): FloatArray =
    VkArena.lookup(id).downloadRgba32fRegion(x, y, w, h)
fun vkDownloadR32fRegion(id: Int, x: Int, y: Int, w: Int, h: Int): FloatArray =
    VkArena.lookup(id).downloadR32fRegion(x, y, w, h)

// ---------------------------------------------------------------------------
// Programs (ProgramCache/Bound/Session twins). Call shapes are identical to
// the GL versions; sampler units and image units resolve through the shader
// manifest, and free uniforms pack into the pass uniform block.

internal class VkProgram(
    val pipeline: Long,
    val module: Long,
    val entry: SrvkShaderEntry
) {
    // Program-persistent uniform staging (zeroed at creation): exactly the
    // GL program-state semantics — values a pass block does not rewrite
    // (e.g. a shrunken u_weights tail) keep their previous contents.
    val uboStaging: ByteBuffer =
        ByteBuffer.allocateDirect(entry.blockBytes).order(ByteOrder.nativeOrder()).also {
            for (i in 0 until entry.blockBytes) it.put(i, 0)
        }
}

internal class VkProgramCache(
    private val vk: SrVulkan.Handle,
    private val assets: AssetManager
) : Closeable {
    private val programs = LinkedHashMap<String, VkProgram>()
    private val manifest = SrvkManifest(assets)

    fun get(asset: String): VkProgram = programs.getOrPut(asset) {
        val stem = asset.substringAfterLast("/").removeSuffix(".glsl")
        val spirv = assets.open("spirv/$stem.spv").readBytes()
        val entry = manifest.entry(asset)
        val module = vk.loadModule(spirv)
        try {
            val opaque = entry.opaque.values.sortedBy { it.binding }
            val pipeline = vk.createPipeline(
                module,
                entry.blockBinding,
                entry.blockBytes,
                opaque.map { it.binding }.toIntArray(),
                opaque.map { if (it.kind == "storage") 1 else 0 }.toIntArray()
            )
            VkProgram(pipeline, module, entry)
        } catch (t: Throwable) {
            vk.destroyModule(module)
            throw t
        }
    }

    override fun close() {
        programs.values.toList().asReversed().forEach {
            vk.destroyPipeline(it.pipeline)
            vk.destroyModule(it.module)
        }
        programs.clear()
    }
}

internal class VkBound(private val vk: SrVulkan.Handle, private val program: VkProgram, private val asset: String) {
    private val entry = program.entry
    private val ubo = program.uboStaging

    fun sampler(name: String, image: VkImage) {
        val e = entry.opaque[name] ?: throw IllegalArgumentException("Vulkan sampler $name missing")
        require(e.kind == "sampled") { "Vulkan $name is not a sampler" }
        val wantUint = e.otype == "usampler2D"
        require(VkFormats.isUint(image.internalFormat) == wantUint) {
            "Vulkan sampler $name (${e.otype}) mismatches image format 0x${image.internalFormat.toString(16)}"
        }
        vk.bindImage(program.pipeline, e.binding, image.handle)
    }

    fun image(binding: Int, image: VkImage, format: Int) {
        val e = entry.opaque.values.firstOrNull { it.kind == "storage" && it.esslBinding == binding }
            ?: throw IllegalArgumentException("Vulkan image unit $binding missing")
        require(format == image.internalFormat) { "Vulkan image format must match the texture" }
        require(manifestFormat(e.format) == image.internalFormat) {
            "Vulkan image unit $binding (${e.format}) mismatches texture 0x${image.internalFormat.toString(16)}"
        }
        vk.bindImage(program.pipeline, e.binding, image.handle)
    }

    fun ivec2(name: String, x: Int, y: Int) = ints(name, "ivec2", intArrayOf(x, y))
    fun vec2(name: String, x: Float, y: Float) = floatsAt(name, "vec2", floatArrayOf(x, y))
    fun ivec4(name: String, values: IntArray) = ints(name, "ivec4", values)
    fun vec3(name: String, values: FloatArray) = floatsAt(name, "vec3", values)
    fun vec4(name: String, values: FloatArray) = floatsAt(name, "vec4", values)
    fun integer(name: String, value: Int) = ints(name, "int", intArrayOf(value))
    fun float(name: String, value: Float) = floatsAt(name, "float", floatArrayOf(value))

    fun floats(name: String, values: FloatArray) {
        // GL call sites address arrays as "u_weights[0]"; the manifest keys
        // the bare array name. Only element 0 is ever addressed (pyramid).
        val e = uniform(name.removeSuffix("[0]").also {
            require(name == it || name == "$it[0]") { "Vulkan array element $name unsupported" }
        })
        val count = e.type.removePrefix("float[").removeSuffix("]").toIntOrNull()
            ?: throw IllegalArgumentException("Vulkan $name is not a float array (${e.type})")
        // GL prefix-upload semantics: fewer values than the array holds
        // update the head and leave the tail (program-persistent staging).
        require(values.size <= count) { "Vulkan $name holds $count floats, got ${values.size}" }
        // std140 arrays stride 16 bytes per element.
        for (i in values.indices) ubo.putFloat(e.offset + i * 16, values[i])
    }

    fun dispatch(width: Int, height: Int, localX: Int, localY: Int) {
        // Watchdog slicing, the same schedule the retired GLES host used:
        // the budget bounds each submit (block_match/lk_refine are heavy
        // enough for their own tiny budget), every submit fence-waits in
        // native code (10s, like the GL fence; a hang throws instead of
        // hanging), and the sleep yields to the viewfinder queue between
        // slices. Desktop sets srvk.sliceBudget huge for one full-grid
        // slice per pass (offset 0, identical math — the offset only
        // translates global IDs); unset means phone budgets.
        val budgetOverride = System.getProperty("srvk.sliceBudget")?.toIntOrNull()
        val budget = budgetOverride
            ?: if (asset.endsWith("block_match.glsl") || asset.endsWith("lk_refine.glsl")) 64 else 131072
        for (slice in RawSrGpuScheduling.slices(width, height, localX, localY, budget)) {
            setDispatchOffset(slice.x, slice.y)
            vk.writeUniforms(program.pipeline, ubo.duplicate().order(ByteOrder.nativeOrder()))
            vk.dispatch(program.pipeline, slice.groupsX, slice.groupsY, 1)
            // Leave a submission opportunity for the independent viewfinder queue.
            Thread.sleep(1)
        }
    }

    private fun uniform(name: String): SrvkUniform =
        entry.uniforms[name] ?: throw IllegalArgumentException("Vulkan uniform $name missing")

    private fun ints(name: String, want: String, values: IntArray) {
        val e = uniform(name)
        require(e.type == want) { "Vulkan $name is ${e.type}, want $want" }
        require(e.bytes == values.size * 4) { "Vulkan $name size mismatch" }
        for (i in values.indices) ubo.putInt(e.offset + i * 4, values[i])
    }

    private fun floatsAt(name: String, want: String, values: FloatArray) {
        val e = uniform(name)
        require(e.type == want) { "Vulkan $name is ${e.type}, want $want" }
        require(e.bytes == values.size * 4) { "Vulkan $name size mismatch" }
        for (i in values.indices) ubo.putFloat(e.offset + i * 4, values[i])
    }

    private fun setDispatchOffset(x: Int, y: Int) {
        val e = uniform("u_dispatch_offset")
        require(e.type == "uvec3") { "u_dispatch_offset must be uvec3" }
        ubo.putInt(e.offset, x)
        ubo.putInt(e.offset + 4, y)
        ubo.putInt(e.offset + 8, 0)
    }

    private fun manifestFormat(token: String?): Int = when (token) {
        "rgba32f" -> GLES30.GL_RGBA32F
        "r32f" -> GLES30.GL_R32F
        "r32ui" -> GLES30.GL_R32UI
        "rgba16f" -> 0x881A
        "r16ui" -> GLES30.GL_R16UI
        else -> throw IllegalArgumentException("unknown manifest image format $token")
    }

}

internal data class VkSession(
    val vk: SrVulkan.Handle,
    val programs: VkProgramCache,
    val uploads: UploadBuffers
) {
    fun pass(asset: String, block: VkBound.() -> Unit) = VkBound(vk, programs.get(asset), asset).block()
}
