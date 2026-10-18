// SPDX-FileCopyrightText: 2026 RawLens contributors
// SPDX-License-Identifier: GPL-3.0-or-later
//
// CPU reference for RawTherapee's fast single-precision log/exp used by the
// highlight-reconstruction gamma table (pow_F(a,b) = xexpf(b*xlogf(a))).
// Bit-trick helpers and polynomial coefficients are verbatim from
// rtengine/sleef.h (branch dev, fetched 2026-10-16); mlaf is a plain x*y+z
// expression exactly like upstream, so HotSpot may fuse it just as a C++
// compiler may — the parity test measures the envelope instead of assuming it.
// Original copyright: (c) 2007-2019 Takuya OOURA, Ingo Weyrich, GPL-3.0-or-later.

package com.matthew.rawlens

/** Fast single-precision math, line-by-line from RT sleef.h. */
object RtFastMath {
    private fun f2i(d: Float): Int = d.toBits()

    private fun ilogbp1f(d: Float): Int {
        var dd = d
        var m = if (dd < 5.421010862427522E-20f) 1 else 0
        dd = if (m != 0) 1.8446744073709552E19f * dd else dd
        var q = (f2i(dd) shr 23) and 0xff
        q = if (m != 0) q - (64 + 0x7e) else q - 0x7e
        return q
    }

    private fun ldexpkf(x: Float, q: Int): Float {
        var m = q shr 31
        m = (((m + q) shr 6) - m) shl 4
        val qq = q - (m shl 2)
        var u = Float.fromBits((m + 0x7f) shl 23)
        u *= u
        var xx = x * u * u
        u = Float.fromBits((qq + 0x7f) shl 23)
        return xx * u
    }

    private fun xrintf(x: Float): Int = (x + if (x < 0) -0.5f else 0.5f).toInt()

    private fun mlaf(x: Float, y: Float, z: Float): Float = x * y + z

    /** RT xlogf (sleef.h). Inputs here are finite non-negative LUT positions. */
    fun xlogf(d: Float): Float {
        val e = ilogbp1f(d * 0.7071f)
        val m = ldexpkf(d, -e)
        val x = (m - 1.0f) / (m + 1.0f)
        val x2 = x * x
        var t = 0.2371599674224853515625f
        t = mlaf(t, x2, 0.285279005765914916992188f)
        t = mlaf(t, x2, 0.400005519390106201171875f)
        t = mlaf(t, x2, 0.666666567325592041015625f)
        t = mlaf(t, x2, 2.0f)
        var xx = x * t + 0.693147180559945286226764f * e
        if (xx == Float.POSITIVE_INFINITY) xx = Float.POSITIVE_INFINITY
        if (d < 0) xx = Float.NaN
        if (d == 0f) xx = Float.NEGATIVE_INFINITY
        return xx
    }

    /** RT xexpf (sleef.h). */
    fun xexpf(d: Float): Float {
        if (d <= -104.0f) return 0.0f
        val q = xrintf(d * 1.442695040888963407359924681001892137426645954152985934135449406931f)
        var s = mlaf(q.toFloat(), -0.693145751953125f, d)
        s = mlaf(q.toFloat(), -1.428606765330187045e-06f, s)
        var u = 0.00136324646882712841033936f
        u = mlaf(u, s, 0.00836596917361021041870117f)
        u = mlaf(u, s, 0.0416710823774337768554688f)
        u = mlaf(u, s, 0.166665524244308471679688f)
        u = mlaf(u, s, 0.499999850988388061523438f)
        u = mlaf(s, mlaf(s, u, 1f), 1f)
        return ldexpkf(u, q)
    }

    /** RT pow_F(a,b) = xexpf(b*xlogf(a)) (opthelper.h). */
    fun powF(a: Float, b: Float): Float = xexpf(b * xlogf(a))
}
