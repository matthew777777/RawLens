package com.rawlens.srpipeline.stages

/**
 * Demosaic-to-grey stage.
 *
 * Faithful port of `demosaicing_CFA_Bayer_Menon2007` (DDFAPD, Menon, Andriani
 * & Calvagno 2007) as invoked by `demosaic_to_grey` in
 * `handheld_super_resolution/utils_image.py` with `pattern="RGGB"` and the
 * default refining step enabled: directional green interpolation (horizontal
 * and vertical estimates blended by the per-pixel decision mask `M` derived
 * from chroma-gradient energies `d_H`/`d_V`) followed by chroma correction
 * of red/blue at green and opposite-colour sites, then the refining step
 * (green update at R/B sites, R/B update at green sites, R/B cross update),
 * finally `mean(rgb, axis=-1)`.
 *
 * Same contract as the Python path: input 2D RGGB CFA `[H, W]`, output 2D
 * grey `[H, W]` (same shape), unclipped (values may leave `[0, 1]`, as in the
 * reference). All intermediates are `Double`, matching the reference float64
 * pipeline (`as_float_array`); only the final grey is rounded once to
 * `Float`, matching the reference `astype(float32)` mean.
 *
 * Edge handling mirrors the Python body exactly:
 * - 1-D convolutions (`_cnv_h`/`_cnv_v`: green interpolation kernels
 *   `h_0`/`h_1`, chroma kernel `k_b`, refining kernel `FIR`) use
 *   `scipy.ndimage.convolve1d` `mode="mirror"` (out-of-range index `i` of an
 *   axis of length `n > 1` maps via `r = i mod (2n - 2)`, folded back as
 *   `2n - 2 - r` when `r >= n`; length-1 axes always read index 0). All
 *   these kernels are symmetric, so convolution/correlation agree.
 * - The decision energies `d_H`/`d_V` use `scipy.ndimage.convolve`
 *   `mode="constant"` (zero padding, true convolution, i.e. the kernel is
 *   applied flipped; this matters because the 5x5 decision kernel is
 *   asymmetric) with `d_V` using the transposed kernel.
 * - The chroma gradients `D_H`/`D_V` use `np.pad(..., mode="reflect")`
 *   shifted by 2, which for the accessed (non-negative) indices coincides
 *   with the same mirror map.
 *
 * NaN safety (what the pipeline relies on): the Menon convolutions would
 * smear a NaN sample across its neighbourhood, so any input containing NaN
 * falls back to the previous NaN-isolating bilinear interpolation (kept as a
 * private fallback): NaN samples are excluded from every interpolation
 * average and from the final channel mean, finite inputs always yield finite
 * outputs, and all-NaN channels yield NaN. Finite inputs always take the
 * Menon path.
 */
object Demosaic {

    fun demosaicToGrey(image: Array<FloatArray>): Array<FloatArray> {
        requireRectImage(image, "image")
        if (containsNaN(image)) return bilinearToGrey(image)
        val h = image.size
        val w = image[0].size
        val cfa = DoubleArray(h * w) { image[it / w][it % w].toDouble() }
        val (r, g, b) = menon2007(cfa, h, w)
        return Array(h) { y ->
            FloatArray(w) { x ->
                val i = y * w + x
                ((r[i] + g[i] + b[i]) / 3.0).toFloat()
            }
        }
    }

    private fun containsNaN(image: Array<FloatArray>): Boolean {
        for (row in image) for (v in row) if (v.isNaN()) return true
        return false
    }

    // Green interpolation kernels.
    private val H0 = doubleArrayOf(0.0, 0.5, 0.0, 0.5, 0.0)
    private val H1 = doubleArrayOf(-0.25, 0.0, 0.5, 0.0, -0.25)
    // Chroma interpolation kernel.
    private val KB = doubleArrayOf(0.5, 0.0, 0.5)
    // Refining-step averaging kernel (ones(3) / 3).
    private val FIR = doubleArrayOf(1.0 / 3.0, 1.0 / 3.0, 1.0 / 3.0)

    // Decision kernel k and its transpose (applied flipped = true convolution).
    private val K = arrayOf(
        doubleArrayOf(0.0, 0.0, 1.0, 0.0, 1.0),
        doubleArrayOf(0.0, 0.0, 0.0, 1.0, 0.0),
        doubleArrayOf(0.0, 0.0, 3.0, 0.0, 3.0),
        doubleArrayOf(0.0, 0.0, 0.0, 1.0, 0.0),
        doubleArrayOf(0.0, 0.0, 1.0, 0.0, 1.0),
    )
    private val KT = Array(5) { y -> DoubleArray(5) { x -> K[x][y] } }

    private data class Rgb(val r: DoubleArray, val g: DoubleArray, val b: DoubleArray)

    private fun menon2007(cfa: DoubleArray, h: Int, w: Int): Rgb {
        val isR = BooleanArray(h * w) { i -> (i / w) % 2 == 0 && (i % w) % 2 == 0 }
        val isB = BooleanArray(h * w) { i -> (i / w) % 2 == 1 && (i % w) % 2 == 1 }
        val isG = BooleanArray(h * w) { i -> !isR[i] && !isB[i] }

        val r = DoubleArray(h * w) { if (isR[it]) cfa[it] else 0.0 }
        val b = DoubleArray(h * w) { if (isB[it]) cfa[it] else 0.0 }

        // Directional green estimates at non-green sites.
        val gh = add(convH(cfa, h, w, H0), convH(cfa, h, w, H1))
        val gv = add(convV(cfa, h, w, H0), convV(cfa, h, w, H1))
        val gH = DoubleArray(h * w) { if (isG[it]) cfa[it] else gh[it] }
        val gV = DoubleArray(h * w) { if (isG[it]) cfa[it] else gv[it] }

        // Chroma differences and their gradients.
        val chH = DoubleArray(h * w) { if (isR[it]) r[it] - gH[it] else if (isB[it]) b[it] - gH[it] else 0.0 }
        val chV = DoubleArray(h * w) { if (isR[it]) r[it] - gV[it] else if (isB[it]) b[it] - gV[it] else 0.0 }
        val dH = DoubleArray(h * w) { i ->
            val y = i / w
            val x = i % w
            kotlin.math.abs(chH[i] - chH[y * w + mirrorIndex(x + 2, w)])
        }
        val dV = DoubleArray(h * w) { i ->
            val y = i / w
            val x = i % w
            kotlin.math.abs(chV[i] - chV[mirrorIndex(y + 2, h) * w + x])
        }
        val eH = conv2Const(dH, h, w, K)
        val eV = conv2Const(dV, h, w, KT)

        // Decision mask: horizontal estimate wins ties (d_V >= d_H).
        val m = BooleanArray(h * w) { eV[it] >= eH[it] }
        val g = DoubleArray(h * w) { if (m[it]) gH[it] else gV[it] }
        val mF = DoubleArray(h * w) { if (m[it]) 1.0 else 0.0 }

        // Red rows / blue rows (rows containing R / B sites).
        val rRow = BooleanArray(h) { y -> (y % 2 == 0) }
        val bRow = BooleanArray(h) { y -> (y % 2 == 1) }
        // Red columns / blue columns.
        val rCol = BooleanArray(w) { x -> (x % 2 == 0) }
        val bCol = BooleanArray(w) { x -> (x % 2 == 1) }

        // Red and blue at green sites adjacent to red / blue rows.
        updateGR(r, g, h, w, isG, rRow, horizontal = true)
        updateGR(r, g, h, w, isG, bRow, horizontal = false)
        updateGR(b, g, h, w, isG, bRow, horizontal = true)
        updateGR(b, g, h, w, isG, rRow, horizontal = false)

        // Red at blue sites / blue at red sites along the decision direction.
        for (i in 0 until h * w) {
            val y = i / w
            if (bRow[y] && isB[i]) {
                r[i] = b[i] + if (m[i]) {
                    convHAt(r, h, w, KB, y, i % w) - convHAt(b, h, w, KB, y, i % w)
                } else {
                    convVAt(r, h, w, KB, y, i % w) - convVAt(b, h, w, KB, y, i % w)
                }
            }
        }
        for (i in 0 until h * w) {
            val y = i / w
            if (rRow[y] && isR[i]) {
                b[i] = r[i] + if (m[i]) {
                    convHAt(b, h, w, KB, y, i % w) - convHAt(r, h, w, KB, y, i % w)
                } else {
                    convVAt(b, h, w, KB, y, i % w) - convVAt(r, h, w, KB, y, i % w)
                }
            }
        }

        refiningStep(r, g, b, isR, isG, isB, rRow, bRow, rCol, bCol, mF, m, h, w)
        return Rgb(r, g, b)
    }

    /** `G + conv(R or B) - conv(G)` update of [target] at green sites in the given rows. */
    private fun updateGR(
        target: DoubleArray,
        g: DoubleArray,
        h: Int,
        w: Int,
        isG: BooleanArray,
        rows: BooleanArray,
        horizontal: Boolean,
    ) {
        val cT = if (horizontal) convH(target, h, w, KB) else convV(target, h, w, KB)
        val cG = if (horizontal) convH(g, h, w, KB) else convV(g, h, w, KB)
        for (i in 0 until h * w) {
            if (isG[i] && rows[i / w]) target[i] = g[i] + cT[i] - cG[i]
        }
    }

    private fun refiningStep(
        r: DoubleArray,
        g: DoubleArray,
        b: DoubleArray,
        isR: BooleanArray,
        isG: BooleanArray,
        isB: BooleanArray,
        rRow: BooleanArray,
        bRow: BooleanArray,
        rCol: BooleanArray,
        bCol: BooleanArray,
        mF: DoubleArray,
        m: BooleanArray,
        h: Int,
        w: Int,
    ) {
        // Green update at R/B sites.
        var rg = sub(r, g)
        var bg = sub(b, g)
        var rgM = DoubleArray(h * w) {
            if (isR[it]) {
                val c = if (m[it]) convHAt(rg, h, w, FIR, it / w, it % w) else convVAt(rg, h, w, FIR, it / w, it % w)
                c
            } else 0.0
        }
        var bgM = DoubleArray(h * w) {
            if (isB[it]) {
                val c = if (m[it]) convHAt(bg, h, w, FIR, it / w, it % w) else convVAt(bg, h, w, FIR, it / w, it % w)
                c
            } else 0.0
        }
        for (i in 0 until h * w) {
            if (isR[i]) g[i] = r[i] - rgM[i]
            if (isB[i]) g[i] = b[i] - bgM[i]
        }

        // Red and blue update at green sites.
        rg = sub(r, g)
        bg = sub(b, g)
        // Note: R_G_m / B_G_m keep values computed above; only green-row/col
        // sites are overwritten, exactly as in the reference.
        for (i in 0 until h * w) {
            val y = i / w
            val x = i % w
            if (isG[i] && bRow[y]) rgM[i] = convVAt(rg, h, w, KB, y, x)
        }
        for (i in 0 until h * w) {
            val y = i / w
            if (isG[i] && bRow[y]) r[i] = g[i] + rgM[i]
        }
        for (i in 0 until h * w) {
            val y = i / w
            val x = i % w
            if (isG[i] && bCol[x]) rgM[i] = convHAt(rg, h, w, KB, y, x)
        }
        for (i in 0 until h * w) {
            val y = i / w
            val x = i % w
            if (isG[i] && bCol[x]) r[i] = g[i] + rgM[i]
        }
        for (i in 0 until h * w) {
            val y = i / w
            val x = i % w
            if (isG[i] && rRow[y]) bgM[i] = convVAt(bg, h, w, KB, y, x)
        }
        for (i in 0 until h * w) {
            val y = i / w
            if (isG[i] && rRow[y]) b[i] = g[i] + bgM[i]
        }
        for (i in 0 until h * w) {
            val y = i / w
            val x = i % w
            if (isG[i] && rCol[x]) bgM[i] = convHAt(bg, h, w, KB, y, x)
        }
        for (i in 0 until h * w) {
            val y = i / w
            val x = i % w
            if (isG[i] && rCol[x]) b[i] = g[i] + bgM[i]
        }

        // Red/blue cross update.
        val rb = sub(r, b)
        var rbM = DoubleArray(h * w) {
            if (isB[it]) {
                if (m[it]) convHAt(rb, h, w, FIR, it / w, it % w) else convVAt(rb, h, w, FIR, it / w, it % w)
            } else 0.0
        }
        for (i in 0 until h * w) if (isB[i]) r[i] = b[i] + rbM[i]
        rbM = DoubleArray(h * w) {
            if (isR[it]) {
                if (m[it]) convHAt(rb, h, w, FIR, it / w, it % w) else convVAt(rb, h, w, FIR, it / w, it % w)
            } else 0.0
        }
        for (i in 0 until h * w) if (isR[i]) b[i] = r[i] - rbM[i]
    }

    /**
     * `scipy.ndimage.convolve1d` `mode="mirror"` index map: out-of-range
     * index `i` on an axis of length `n`. Single-element axes always read 0.
     */
    internal fun mirrorIndex(i: Int, n: Int): Int {
        if (n <= 1) return 0
        val p = 2 * n - 2
        var r = i % p
        if (r < 0) r += p
        return if (r >= n) p - r else r
    }

    private fun convH(src: DoubleArray, h: Int, w: Int, k: DoubleArray): DoubleArray {
        val out = DoubleArray(h * w)
        for (y in 0 until h) for (x in 0 until w) out[y * w + x] = convHAt(src, h, w, k, y, x)
        return out
    }

    private fun convHAt(src: DoubleArray, h: Int, w: Int, k: DoubleArray, y: Int, x: Int): Double {
        val c = k.size / 2
        var s = 0.0
        for (j in k.indices) s += k[j] * src[y * w + mirrorIndex(x + j - c, w)]
        return s
    }

    private fun convV(src: DoubleArray, h: Int, w: Int, k: DoubleArray): DoubleArray {
        val out = DoubleArray(h * w)
        for (y in 0 until h) for (x in 0 until w) out[y * w + x] = convVAt(src, h, w, k, y, x)
        return out
    }

    private fun convVAt(src: DoubleArray, h: Int, w: Int, k: DoubleArray, y: Int, x: Int): Double {
        val c = k.size / 2
        var s = 0.0
        for (j in k.indices) s += k[j] * src[mirrorIndex(y + j - c, h) * w + x]
        return s
    }

    /**
     * `scipy.ndimage.convolve` `mode="constant"` (zero padding, true
     * convolution: the kernel is applied flipped).
     */
    private fun conv2Const(src: DoubleArray, h: Int, w: Int, k: Array<DoubleArray>): DoubleArray {
        val kh = k.size
        val kw = k[0].size
        val cy = kh / 2
        val cx = kw / 2
        return DoubleArray(h * w) { i ->
            val y = i / w
            val x = i % w
            var s = 0.0
            for (ky in 0 until kh) {
                val yy = y + ky - cy
                if (yy < 0 || yy >= h) continue
                for (kx in 0 until kw) {
                    val xx = x + kx - cx
                    if (xx < 0 || xx >= w) continue
                    s += k[kh - 1 - ky][kw - 1 - kx] * src[yy * w + xx]
                }
            }
            s
        }
    }

    private fun add(a: DoubleArray, b: DoubleArray) = DoubleArray(a.size) { a[it] + b[it] }

    private fun sub(a: DoubleArray, b: DoubleArray) = DoubleArray(a.size) { a[it] - b[it] }

    /**
     * Previous bilinear RGGB interpolation + channel mean, kept as the
     * private NaN fallback (see class docs).
     */
    private fun bilinearToGrey(image: Array<FloatArray>): Array<FloatArray> {
        val h = image.size
        val w = image[0].size
        return Array(h) { y ->
            FloatArray(w) { x ->
                val isR = y % 2 == 0 && x % 2 == 0
                val isB = y % 2 == 1 && x % 2 == 1
                val r = if (isR) {
                    image[y][x].toDouble()
                } else if (y % 2 == 0) {
                    meanOf(image, h, w, intArrayOf(y, y), intArrayOf(x - 1, x + 1))
                } else if (x % 2 == 0) {
                    meanOf(image, h, w, intArrayOf(y - 1, y + 1), intArrayOf(x, x))
                } else {
                    meanOf(
                        image, h, w,
                        intArrayOf(y - 1, y - 1, y + 1, y + 1),
                        intArrayOf(x - 1, x + 1, x - 1, x + 1),
                    )
                }
                val b = if (isB) {
                    image[y][x].toDouble()
                } else if (y % 2 == 1) {
                    meanOf(image, h, w, intArrayOf(y, y), intArrayOf(x - 1, x + 1))
                } else if (x % 2 == 1) {
                    meanOf(image, h, w, intArrayOf(y - 1, y + 1), intArrayOf(x, x))
                } else {
                    meanOf(
                        image, h, w,
                        intArrayOf(y - 1, y - 1, y + 1, y + 1),
                        intArrayOf(x - 1, x + 1, x - 1, x + 1),
                    )
                }
                val g = if (!isR && !isB) {
                    image[y][x].toDouble()
                } else {
                    meanOf(
                        image, h, w,
                        intArrayOf(y - 1, y + 1, y, y),
                        intArrayOf(x, x, x - 1, x + 1),
                    )
                }
                var sum = 0.0
                var count = 0
                if (!r.isNaN()) {
                    sum += r
                    count++
                }
                if (!g.isNaN()) {
                    sum += g
                    count++
                }
                if (!b.isNaN()) {
                    sum += b
                    count++
                }
                if (count == 0) Float.NaN else (sum / count).toFloat()
            }
        }
    }

    /** Mean of the finite, in-bounds samples; NaN when there are none. */
    private fun meanOf(
        image: Array<FloatArray>,
        h: Int,
        w: Int,
        ys: IntArray,
        xs: IntArray,
    ): Double {
        var sum = 0.0
        var count = 0
        for (k in ys.indices) {
            val y = ys[k]
            val x = xs[k]
            if (y < 0 || y >= h || x < 0 || x >= w) continue
            val v = image[y][x].toDouble()
            if (v.isNaN()) continue
            sum += v
            count++
        }
        return if (count == 0) Double.NaN else sum / count
    }
}
