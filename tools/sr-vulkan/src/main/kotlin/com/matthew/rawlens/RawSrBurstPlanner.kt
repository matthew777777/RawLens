package com.matthew.rawlens

import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

/** CPU-only admission policy. Thresholds are conservative RawLens v1 policy, not paper constants. */
internal object RawSrBurstPlanner {
    const val MAX_EXPOSURE_RATIO = 1.10
    const val MAX_SATURATION_FRACTION = 0.10f
    enum class Reason { CAMERA, PHYSICAL_CAMERA, CROP, GEOMETRY, DIMENSIONS, PIXEL_STRIDE,
        CFA, NORMALIZATION, EXPOSURE, COLOR, LENS_SHADING, PLANE, SATURATION, DISPLACEMENT, REGISTRATION,
        UNSHARP, OVERFLOW }
    data class Input(val metadata: RawFrameMetadata, val plane: ByteBuffer, val motion: Float)
    data class Rejected(val index: Int, val reasons: Set<Reason>)
    data class Plan(val selected: List<Int>, val accepted: List<Int>, val rejected: List<Rejected>,
                    val reference: Int?,
                    /** Sampled sharpness per input (null when the preview failed); reference-anchored UNSHARP gate. */
                    val sharpness: List<Double?> = emptyList(),
                    /** EV of each input relative to the reference (null when uncomputable); 0.0 at the reference. */
                    val evRelative: List<Double?> = emptyList()) {
        val canMerge get() = accepted.size >= 2
    }
    /**
     * Reference priority. STABILITY_FIRST (default) ranks stable AF/AE/lens
     * state, then angular travel, then sampled sharpness; SHARPEST_FIRST
     * ranks sampled sharpness first with the same tiebreaks, so the merge
     * anchors on the sharpest eligible frame instead of the most central
     * stable one. Eligibility and rejection reasons are identical in both.
     */
    enum class ReferenceMode { STABILITY_FIRST, SHARPEST_FIRST }
    private data class Preview(val pixels: FloatArray, val width: Int, val height: Int,
                               val step: Int, val sharpness: Double, val saturation: Float)

    fun plan(inputs: List<Input>, cameraId: String,
             referenceMode: ReferenceMode = ReferenceMode.STABILITY_FIRST,
             policy: RawSrFrameRejection.Policy = RawSrFrameRejection.Policy()): Plan {
        require(inputs.size in 2..30)
        val reasons = inputs.map { linkedSetOf<Reason>() }
        val previews = arrayOfNulls<Preview>(inputs.size)
        inputs.forEachIndexed { i, input ->
            reasons[i].addAll(invalid(input.metadata))
            if (input.metadata.cameraId != cameraId) reasons[i] += Reason.CAMERA
            if (!input.motion.isFinite() || input.motion < 0) reasons[i] += Reason.REGISTRATION
            if (reasons[i].isEmpty()) {
                previews[i] = runCatching { preview(input) }.getOrElse { reasons[i] += Reason.PLANE; null }
                if ((previews[i]?.saturation ?: 0f) > MAX_SATURATION_FRACTION) reasons[i] += Reason.SATURATION
            }
        }
        val eligible = inputs.indices.filter { reasons[it].isEmpty() }
        val median = eligible.map { inputs[it].metadata.timestampNanos }.sorted().let { it.getOrNull(it.size / 2) ?: 0L }
        fun travel(it: Int): Double {
            val m = inputs[it].metadata
            return inputs[it].motion.toDouble() *
                ((m.exposureTimeNanos ?: 0).toDouble() + (m.rollingShutterSkewNanos ?: 0))
        }
        val ordering = if (referenceMode == ReferenceMode.SHARPEST_FIRST) {
            compareByDescending<Int> { previews[it]!!.sharpness }
                .thenBy { stability(inputs[it].metadata) }
                .thenBy { travel(it) }
                .thenBy { abs(inputs[it].metadata.timestampNanos.toDouble() - median) }
                .thenBy { inputs[it].metadata.timestampNanos }.thenBy { it }
        } else {
            compareBy<Int> { stability(inputs[it].metadata) }
                .thenBy { travel(it) }
                .thenByDescending { previews[it]!!.sharpness }
                .thenBy { abs(inputs[it].metadata.timestampNanos.toDouble() - median) }
                .thenBy { inputs[it].metadata.timestampNanos }.thenBy { it }
        }
        val reference = eligible.sortedWith(ordering).firstOrNull()
        if (reference != null) eligible.filter { it != reference }.forEach { i ->
            reasons[i].addAll(incompatible(inputs[reference].metadata, inputs[i].metadata))
            if (reasons[i].isEmpty()) registration(previews[reference]!!, previews[i]!!)?.let { reasons[i] += it }
        }
        // Relative sharpness gate (reference-anchored UNSHARP): absolute
        // sharpness varies by scene, so compare against the burst's own
        // reference instead of a fixed floor. Skipped when the reference
        // itself is near-flat (sharpness ~0) to avoid rejecting the whole
        // burst on a textureless scene.
        if (reference != null) {
            val refSharp = previews[reference]?.sharpness ?: 0.0
            if (refSharp > 1e-9) {
                val floor = refSharp * policy.minSharpnessRatio
                for (i in inputs.indices) {
                    if (i == reference || reasons[i].isNotEmpty()) continue
                    val s = previews[i]?.sharpness ?: continue
                    if (s < floor) reasons[i] += Reason.UNSHARP
                }
            }
        }
        // HDR+ N-cap (Hasinoff §5: N=2..8 preferred): keep the reference plus
        // the lowest-travel survivors; reject the overflow as OVERFLOW so the
        // merge set stays bounded even for 30-frame input bursts.
        if (reference != null) {
            var accepted = inputs.indices.filter { reasons[it].isEmpty() }
            if (accepted.size > policy.maxMergeFrames) {
                val keep = setOf(reference) +
                    accepted.filter { it != reference }.sortedWith(
                        compareBy({ travel(it) },
                            { abs(inputs[it].metadata.timestampNanos.toDouble() - median) },
                            { inputs[it].metadata.timestampNanos }, { it })
                    ).take(policy.maxMergeFrames - 1)
                for (i in accepted) if (i !in keep) reasons[i] += Reason.OVERFLOW
                accepted = inputs.indices.filter { reasons[it].isEmpty() }
            }
        }
        val sharpness = inputs.indices.map { previews[it]?.sharpness }
        val evRelative = inputs.indices.map { evRelativeTo(inputs, reference, it) }
        return Plan(inputs.indices.toList(), inputs.indices.filter { reasons[it].isEmpty() },
            inputs.indices.filter { reasons[it].isNotEmpty() }.map { Rejected(it, reasons[it].toSet()) }, reference,
            sharpness, evRelative)
    }

    /** EV of frame [index] relative to [reference] from exposure*ISO ratio; null when uncomputable. */
    private fun evRelativeTo(inputs: List<Input>, reference: Int?, index: Int): Double? {
        if (reference == null) return null
        if (index == reference) return 0.0
        val ref = inputs[reference].metadata
        val other = inputs[index].metadata
        val refEv = (ref.exposureTimeNanos ?: 0).toDouble() * (ref.sensitivityIso ?: 0).toDouble()
        val othEv = (other.exposureTimeNanos ?: 0).toDouble() * (other.sensitivityIso ?: 0).toDouble()
        if (refEv <= 0.0 || othEv <= 0.0) return null
        return kotlin.math.log2(othEv / refEv)
    }

    fun invalid(m: RawFrameMetadata): Set<Reason> = buildSet {
        if (m.imageWidth < 4 || m.imageHeight < 4) add(Reason.DIMENSIONS)
        if (m.rawPlaneCount != 1 || m.rawPlaneRowStride == null) add(Reason.PLANE)
        if (m.rawPlanePixelStride != 2) add(Reason.PIXEL_STRIDE)
        if (m.cfaPattern == null) add(Reason.CFA)
        if (m.normalizationOrNull() == null) add(Reason.NORMALIZATION)
        val g = m.bufferGeometry as? RawBufferGeometry.Supported
        if (g == null || m.rawDevelopmentUnsupportedReason != null) add(Reason.GEOMETRY)
        else if (g.processingCrop.width % 2 != 0 || g.processingCrop.height % 2 != 0 ||
            g.processingCrop.left.toLong() + g.processingCrop.width > m.imageWidth ||
            g.processingCrop.top.toLong() + g.processingCrop.height > m.imageHeight) add(Reason.CROP)
        if ((m.exposureTimeNanos ?: 0) <= 0 || (m.sensitivityIso ?: 0) <= 0 ||
            (m.rollingShutterSkewNanos ?: 0) < 0) add(Reason.EXPOSURE)
        fun validMatrix(v: ImmutableDoubleValues?) = v != null && v.size == 9 && v.toDoubleArray().all { it.isFinite() } && v.toDoubleArray().any { it != 0.0 }
        if (!validMatrix(m.colorMatrix1) || m.neutralColorPoint?.let {
                it.size == 3 && it.toDoubleArray().all { v -> v.isFinite() && v > 0 }
            } != true) add(Reason.COLOR)
        if (!m.lensShadingAlreadyApplied) {
            val map = m.lensShadingMap
            if (map == null || map.rows <= 0 || map.columns <= 0 ||
                map.gains.size.toLong() != map.rows.toLong() * map.columns * 4 ||
                map.gains.toFloatArray().any { !it.isFinite() || it <= 0 } || m.activeArray == null)
                add(Reason.LENS_SHADING)
        }
    }

    fun incompatible(base: RawFrameMetadata, other: RawFrameMetadata): Set<Reason> = buildSet {
        if (base.cameraId != other.cameraId) add(Reason.CAMERA)
        if (base.activePhysicalCameraId != other.activePhysicalCameraId) add(Reason.PHYSICAL_CAMERA)
        if (base.imageWidth != other.imageWidth || base.imageHeight != other.imageHeight) add(Reason.DIMENSIONS)
        if (base.imageCrop != other.imageCrop || base.activeArray != other.activeArray ||
            base.preCorrectionActiveArray != other.preCorrectionActiveArray || base.rawCropRegion != other.rawCropRegion) add(Reason.CROP)
        val a = base.bufferGeometry as? RawBufferGeometry.Supported
        val b = other.bufferGeometry as? RawBufferGeometry.Supported
        if (a == null || b == null || a.sensorOriginX != b.sensorOriginX || a.sensorOriginY != b.sensorOriginY ||
            a.processingCrop != b.processingCrop || base.sensorPixelMode != other.sensorPixelMode ||
            base.rawBinningFactorUsed != other.rawBinningFactorUsed) add(Reason.GEOMETRY)
        if (base.rawPlanePixelStride != other.rawPlanePixelStride) add(Reason.PIXEL_STRIDE)
        if (base.cfaPattern != other.cfaPattern) add(Reason.CFA)
        if (base.whiteLevel != other.whiteLevel) add(Reason.NORMALIZATION)
        fun ratio(a: Double, b: Double) = if (min(a,b) <= 0) Double.POSITIVE_INFINITY else max(a,b) / min(a,b)
        if (ratio((base.exposureTimeNanos ?: 0).toDouble(), (other.exposureTimeNanos ?: 0).toDouble()) > MAX_EXPOSURE_RATIO ||
            ratio((base.sensitivityIso ?: 0).toDouble(), (other.sensitivityIso ?: 0).toDouble()) > MAX_EXPOSURE_RATIO) add(Reason.EXPOSURE)
        fun equal(a: ImmutableDoubleValues?, b: ImmutableDoubleValues?) =
            if (a == null || b == null) a == b else a.toDoubleArray().contentEquals(b.toDoubleArray())
        if (!equal(base.colorMatrix1, other.colorMatrix1) || !equal(base.colorMatrix2, other.colorMatrix2) ||
            !equal(base.forwardMatrix1, other.forwardMatrix1) || !equal(base.forwardMatrix2, other.forwardMatrix2)) add(Reason.COLOR)
        if (base.lensShadingAlreadyApplied != other.lensShadingAlreadyApplied) add(Reason.LENS_SHADING)
    }

    private fun stability(m: RawFrameMetadata): Int =
        (if (m.afState in listOf(2, 4)) 0 else 1) +
        (if (m.aeState in listOf(2, 3)) 0 else 1) + (if (m.lensState == 0) 0 else 1)

    private fun preview(input: Input): Preview {
        val m = input.metadata
        val g = m.bufferGeometry as RawBufferGeometry.Supported
        val c = g.processingCrop
        val row = requireNotNull(m.rawPlaneRowStride)
        require(row.toLong() >= m.imageWidth.toLong() * 2)
        val data = input.plane.duplicate().order(ByteOrder.nativeOrder())
        val start = data.position()
        require(start.toLong() + (m.imageHeight - 1L) * row + m.imageWidth * 2L <= data.limit())
        val step = max(2, ((max(c.width,c.height) + 63) / 64 + 1) / 2 * 2)
        val w = (c.width - 2) / step + 1; val h = (c.height - 2) / step + 1
        val norm = requireNotNull(m.normalizationOrNull())
        val pixels = FloatArray(w*h); var saturated = 0
        for (y in 0 until h) for (x in 0 until w) {
            var sum = 0f
            for (dy in 0..1) for (dx in 0..1) {
                val px = c.left + x*step + dx; val py = c.top + y*step + dy
                val code = data.getShort(start + py*row + px*2).toInt() and 65535
                val black = norm.blackAt(g.sensorOriginX + px, g.sensorOriginY + py)
                val v = (code-black)/(norm.whiteLevel-black)
                if (v >= .98f) saturated++
                sum += v
            }
            pixels[y*w+x] = sum/4
        }
        var sharp = 0.0
        for (y in 0 until h-1) for (x in 0 until w-1) {
            val p=y*w+x; val dx=pixels[p+1]-pixels[p]; val dy=pixels[p+w]-pixels[p]
            sharp += dx*dx+dy*dy
        }
        return Preview(pixels,w,h,step,sharp/max(1,(w-1)*(h-1)),saturated.toFloat()/(w*h*4))
    }

    /** Sparse global translation screen; ±32 RAW pixels is a conservative preflight range. */
    private fun registration(a: Preview,b: Preview): Reason? {
        if (a.sharpness < 1e-6 || b.sharpness < 1e-6 || a.width < 8 || a.height < 8) return Reason.REGISTRATION
        val radius = min(6, min(a.width,a.height)/4)
        var best=Double.POSITIVE_INFINITY; var bx=0; var by=0
        for (dy in -radius..radius) for (dx in -radius..radius) {
            var error=0.0; var n=0
            for (y in radius until a.height-radius) for (x in radius until a.width-radius) {
                error += abs(a.pixels[y*a.width+x]-b.pixels[(y+dy)*b.width+x+dx]); n++
            }
            val score=error/max(1,n)
            if (score < best) { best=score; bx=dx; by=dy }
        }
        if (max(abs(bx),abs(by)) == radius || max(abs(bx),abs(by))*a.step > 32) return Reason.DISPLACEMENT
        return if (best > .12) Reason.REGISTRATION else null
    }
}
