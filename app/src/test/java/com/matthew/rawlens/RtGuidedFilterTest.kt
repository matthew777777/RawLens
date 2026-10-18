// SPDX-FileCopyrightText: 2026 RawLens contributors
// SPDX-License-Identifier: GPL-3.0-or-later

package com.matthew.rawlens

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Parity against RT boxblur ring variant and guidedFilter.
 *
 * Goldens below were machine-generated from the float32 C++ oracle using
 * verbatim copies (see /tmp/hlmap_gf.cpp); the generator script that embedded
 * them read /tmp/rt_fm_goldens.txt, so no hand transcription is involved.
 * Assertion is per-element ULP distance (HotSpot FMA fusion); the radius-0
 * path is a pure copy and asserts exact bits.
 */
class RtGuidedFilterTest {
    private fun pattern(h: Int, w: Int): Array<FloatArray> =
        Array(h) { i -> FloatArray(w) { j -> ((i * 131 + j * 137 + 7) % 1009).toFloat() / 1009f } }

    private fun maxUlp(expected: List<String>, actual: Array<FloatArray>): Long {
        val flat = expected.flatMap { it.split(" ") }
        var k = 0
        var worst = 0L
        for (row in actual) for (x in row) {
            val e = flat[k].toLong(16)
            val a = x.toBits().toLong() and 0xffffffffL
            fun t(u: Long) = if (u and 0x80000000L == 0L) u else 0x100000000L - u
            val d = kotlin.math.abs(t(e) - t(a))
            if (d > worst) worst = d
            k++
        }
        assertEquals(flat.size, k)
        return worst
    }

    private fun assertUlpClose(label: String, expected: List<String>, actual: Array<FloatArray>, bound: Long) {
        val worst = maxUlp(expected, actual)
        assertTrue("$label maxUlp=$worst exceeds $bound", worst <= bound)
    }

    @Test
    fun boxBlurRingMatchesOracle() {
        var dst = Array(16) { FloatArray(16) }
        RtGuidedFilter.boxBlurRing(pattern(16, 16), dst, 2, 16, 16)
        assertUlpClose("ring16", GOLDEN_RING16, dst, RING_BOUND)
        dst = Array(12) { FloatArray(20) }
        RtGuidedFilter.boxBlurRing(pattern(12, 20), dst, 3, 20, 12)
        assertUlpClose("ring20", GOLDEN_RING20, dst, RING_BOUND)
        dst = Array(10) { FloatArray(10) }
        RtGuidedFilter.boxBlurRing(pattern(10, 10), dst, 3, 10, 10)
        assertUlpClose("ring10", GOLDEN_RING10, dst, RING_BOUND)
    }

    @Test
    fun boxBlurRingRadiusZeroIsExactCopy() {
        val src = pattern(10, 10)
        val dst = Array(10) { FloatArray(10) }
        RtGuidedFilter.boxBlurRing(src, dst, 0, 10, 10)
        for (i in 0 until 10) for (j in 0 until 10) {
            assertEquals(src[i][j].toBits(), dst[i][j].toBits())
        }
    }

    @Test
    fun guidedFilterMatchesOracle() {
        fun scaled(h: Int, w: Int): Pair<Array<FloatArray>, Array<FloatArray>> {
            val guide = pattern(h, w)
            val src = pattern(h, w)
            for (i in 0 until h) for (j in 0 until w) {
                guide[i][j] = 0.1f + guide[i][j]
                src[i][j] = src[i][j] * 1000f
            }
            return guide to src
        }
        var (guide, src) = scaled(16, 16)
        var dst = Array(16) { FloatArray(16) }
        RtGuidedFilter.guidedFilter(guide, src, dst, 2, 0.1f, 1)
        assertUlpClose("gf16", GOLDEN_GF16, dst, GF_BOUND)
        val (guide2, src2) = scaled(10, 14)
        val dst2 = Array(10) { FloatArray(14) }
        RtGuidedFilter.guidedFilter(guide2, src2, dst2, 3, 655.35f, 1)
        assertUlpClose("gf10x14", GOLDEN_GF1014, dst2, GF_BOUND)
        val (guide3, src3) = scaled(10, 10)
        val dst3 = Array(10) { FloatArray(10) }
        RtGuidedFilter.guidedFilter(guide3, src3, dst3, 8, 0.1f, 1)
        assertUlpClose("gfclamp", GOLDEN_GFCLAMP, dst3, GF_BOUND)
    }

    @Test
    fun rescaleHelpersMatchOracle() {
        // Bilinear at integer coords reproduces source pixels exactly.
        val src = pattern(6, 8)
        for (i in 0 until 6) for (j in 0 until 8) {
            assertEquals(src[i][j].toBits(), RtGuidedFilter.bilinearSample(src, j.toFloat(), i.toFloat()).toBits())
        }
        // Nearest downsample 4x4 -> 2x2 picks even rows/cols.
        val big = Array(4) { i -> FloatArray(4) { j -> (i * 4 + j).toFloat() } }
        val small = Array(2) { FloatArray(2) }
        RtGuidedFilter.rescaleNearestInto(big, 0, 0, 4, 4, small)
        assertEquals(0f, small[0][0])
        assertEquals(2f, small[0][1])
        assertEquals(8f, small[1][0])
        assertEquals(10f, small[1][1])
        // LUT sampling: clamp below/above, exact nodes, lerp midpoint.
        val lut = FloatArray(65536) { i -> i.toFloat() }
        assertEquals(0f, RtGuidedFilter.lutSample(lut, -3f))
        assertEquals(65535f, RtGuidedFilter.lutSample(lut, 70000f))
        assertEquals(100f, RtGuidedFilter.lutSample(lut, 100f))
        assertEquals(100.5f, RtGuidedFilter.lutSample(lut, 100.5f))
        // Luminance is the xyz row dot product.
        assertEquals(
            (1f * 0.5f + 2f * 0.25f + 4f * 0.125f).toBits(),
            RtGuidedFilter.rgbLuminance(1f, 2f, 4f, floatArrayOf(0.5f, 0.25f, 0.125f)).toBits()
        )
        // Blur params follow the RT hLbl table with truncation.
        val p = RtGuidedFilter.guidedParams(2)
        assertEquals(3, p.radius1)
        assertEquals(0.1f, p.gainThresh)
        assertEquals(1, p.radius2)
    }

    private companion object {
        const val RING_BOUND = 16L
        const val GF_BOUND = 16L
        val GOLDEN_RING16 = listOf(
            "3e8b8b4a 3eae4da3 3ed10ffc 3f0b4a57 3f1cfb9f 3f1d9bd6 3f0d2afc 3ef97443 3ed8928d 3ed9d2fb 3efd3589 3f104c0d 3f10ec44 3f007b69 3ef085f8 3ebdf2fd",
            "3eacc7ee 3ecf8a46 3ef24ca0 3f0f1bdc 3f18449c 3f14a08e 3f042fb4 3ee77db3 3ee03597 3ef28716 3f026c4a 3f0b9509 3f07f0fc 3eef0042 3ed1c29c 3eb484f6",
            "3ece0492 3ef0c6ea 3eff0e62 3f0dcea8 3f11d8b0 3f0ba546 3ef668d8 3eea0203 3ef21612 3efa2a20 3f011f16 3f05291d 3efdeb68 3edd09b2 3ecc98d9 3ebc27ff",
            "3f083eec 3f0cd34c 3f0c48f3 3f1052fa 3f0a1f91 3ef35d6c 3ee6f699 3eef0aa5 3ef71eb5 3eff32c3 3f03a367 3efadffc 3ed9fe48 3ecd9773 3ec24552 3eba5d01",
            "3f186a7e 3f147656 3f0ecd44 3f0899db 3ef05202 3ee3eb2d 3eebff3b 3ef41348 3efc2758 3f021db3 3ef7d48f 3ed6f2dc 3eca8c09 3ed2a015 3ed18b65 3edab424",
            "3f1784ff 3f0f4c93 3f071425 3eed4696 3ee0dfc3 3ee8f3cf 3ef107de 3ef91beb 3f0097fd 3ef4c927 3ed3e76f 3ec7809d 3ecf94ac 3eec2399 3efa6b12 3f0e96b5",
            "3f058e6f 3efaac06 3eea3b2a 3eddd457 3ee5e865 3f013ba9 3f0545b1 3f094fb8 3f031c4f 3ee556e9 3ec47531 3ecc8940 3ee91830 3f02d38f 3f11a560 3f1ec248",
            "3ee72fbe 3ed6bee6 3edac8ec 3ee2dcfa 3eff6be9 3f0dfd6b 3f120773 3f0bd40a 3ef6c660 3ed5e4ab 3ec97dd4 3ee60cc4 3f014dda 3f0f9551 3f19486a 3f1ddcca",
            "3ec3429e 3ecc6b60 3edfd18f 3efc607f 3f0c77b7 3f1abf2e 3f148bc5 3f041aeb 3ee75422 3edaed4f 3ee30159 3eff9049 3f0e0f9d 3f1219a3 3f141ea8 3f0be63b",
            "3ec177a0 3edbb174 3ef95513 3f0af202 3f193979 3f1d4380 3f0cd2a6 3ef8c398 3eec5cc5 3ef470d4 3efc84de 3f0c89e7 3f1093ef 3f0a6084 3f022819 3ef3df58",
            "3ee1cec5 3f024891 3f096c4c 3f17b3c4 3f1bbdcb 3f158a61 3f051987 3efdcc3b 3f02f025 3f06fa2c 3f0b0432 3f0f0e39 3f08dad0 3ef0d3e9 3ee06313 3ecff23a",
            "3f122406 3f16b868 3f162e0e 3f1a3816 3f1404ac 3f0393d1 3efac0d0 3f016a6f 3f057477 3f097e7e 3f0d8884 3f07551a 3eedc880 3ee161ab 3ed60f8d 3ece273c",
            "3f224f98 3f1e5b72 3f18b25f 3f127ef7 3f020e1c 3ef7b563 3effc972 3f03eec0 3f07f8c8 3f0c02d0 3f05cf65 3eeabd14 3ede5642 3ee66a4e 3ee555a1 3eee7e60",
            "3f216a19 3f1931af 3f10f940 3f008867 3ef4a9f9 3efcbe05 3f02690a 3f067311 3f0a7d19 3f0449b1 3ee7b1aa 3edb4ad5 3ee35ee4 3effedd1 3f071aa7 3f187bd3",
            "3f253b9e 3f169cce 3f07fdf8 3eef1a3e 3ee7d221 3efa239d 3f063a8f 3f0f634e 3f0bbf42 3ef69cd3 3ed5bb1a 3ece72fd 3ee0c47d 3f0657c9 3f17b8f9 3f291a24",
            "3f208499 3f073b20 3efe0560 3edd23af 3ede6419 3f00e353 3f12949c 3f1334d3 3f02c3fb 3ee4a645 3ec3c48b 3ec504f5 3ee86787 3f16f61a 3f28574b 3f39b875"
        )
        val GOLDEN_RING20 = listOf(
            "3ecf8a46 3ef24ca0 3effb9a2 3f007af2 3f07cf94 3f05ffa4 3f042fb4 3f025fc4 3f008fd3 3efd7fc6 3ef9dfe5 3ef64004 3ef2a024 3eef0042 3eeb6061 3ee7c081 3ef669c4 3ef7a604 3f028984 3f13eab1",
            "3ef0c6ea 3eff0e62 3f018c34 3f00a43c 3f0624c1 3f0454d1 3f0284e1 3f00b4f1 3efdca00 3efa2a20 3ef68a3e 3ef2ea5d 3eef4a7d 3eebaa9b 3ee80aba 3ef30bc3 3efe0ccd 3f01c6a9 3f08ea65 3f17bc35",
            "3efcae38 3f00c959 3f011975 3f00317d 3f0479ee 3f02a9fe 3f00da0e 3efe143c 3efa7459 3ef6d479 3ef33498 3eef94b7 3eebf4d7 3ee854f5 3ef0e5d7 3ef976b9 3f0103ce 3f042c17 3f0a2c8d 3f152753",
            "3efc64c3 3efe3d0d 3efedd45 3efecb27 3f02cf1b 3f00ff2b 3efe5e76 3efabe96 3ef71eb2 3ef37ed2 3eefdef2 3eec3f11 3ee89f30 3eef723f 3ef6454f 3efd185e 3f01f5b7 3f048965 3f0881ba 3f0eea37",
            "3f04014e 3f031956 3f02315e 3f014966 3efef2eb 3efb530a 3ef7b32a 3ef4134a 3ef07366 3eecd386 3ee933a6 3ee593c5 3eec66d4 3ef339e3 3efa0cf3 3f007001 3f03d989 3f074c21 3f0c7c89 3f0b9491",
            "3f00aba8 3eff8760 3efdb770 3efbe780 3f015d48 3eff1aaf 3efb7acf 3ef7daef 3ef43b0b 3ef09b2b 3eecfb4b 3ee95b6a 3ef02e79 3ef70188 3efdd498 3f0253d4 3f05bd5c 3f0a0edd 3f0926e4 3f083eec",
            "3efaac05 3ef8dc15 3ef70c25 3effaf25 3f03411a 3f01712a 3eff4274 3efba294 3ef802b0 3ef462d0 3ef0c2f0 3eed230f 3ef3f61f 3efac92e 3f00ce1f 3f0437a7 3f07a12f 3f06b938 3f05d13f 3f04e947",
            "3ef400ba 3ef230ca 3efc919c 3f01bb65 3f0524ec 3f0354fc 3f01850c 3eff6a39 3efbca55 3ef82a75 3ef48a96 3ef0eab5 3ef7bdc5 3efe90d4 3f02b1f2 3f061b7a 3f044b8a 3f036393 3f027b9a 3f0193a2",
            "3eed556f 3efa2669 3f010b8a 3f039f38 3f0708bf 3f0538cf 3f0368df 3f0198ef 3eff91fb 3efbf21b 3ef8523c 3ef4b25b 3efb856b 3f012c3d 3f0495c5 3f02c5d5 3f00f5e5 3f000dee 3efe4beb 3efc7bfb",
            "3ee0db37 3ef6d0c4 3f0168d8 3f049121 3f08d991 3f0709a1 3f0539b2 3f0369c2 3f0199d0 3eff93c0 3efbf3e1 3ef85401 3f007272 3f04bae3 3f02eaf3 3f011b03 3efe9625 3efabe17 3ef88620 3ef92657",
            "3edbb175 3ef95516 3f03ce46 3f068e89 3f0c0f0c 3f0a3f1c 3f086f2e 3f069f3e 3f04cf4b 3f02ff5b 3f012f6c 3efebef8 3f04e002 3f031011 3f014021 3efee062 3efb4080 3ef7006d 3ef7a0a3 3f0064b2",
            "3ee3547f 3f030b6c 3f09c1ed 3f0a600e 3f11b4ae 3f0fe4be 3f0e14d1 3f0c44e1 3f0a74ed 3f08a4fd 3f06d50f 3f05051f 3f033530 3f01653f 3eff2a9e 3efb8abe 3ef7eadc 3ef61af0 3eff43af 3f0dcfd2"
        )
        val GOLDEN_RING10 = listOf(
            "3ecf8a46 3ef24ca0 3effb9a2 3f007af2 3f07cf94 3f05ffa4 3f042fb4 3f003b8b 3ee77db3 3ed0a672",
            "3ef0c6ea 3eff0e62 3f018c34 3f00a43c 3f0624c1 3f0454d1 3f0284e1 3ef7090e 3eea0203 3edeafe0",
            "3efcae38 3f00c959 3f011975 3f00317d 3f0479ee 3f02a9fe 3f00da0e 3efbd3eb 3ef6c3c5 3ef31fb7",
            "3efc64c3 3efe3d0d 3efedd45 3efecb27 3f02cf1b 3f00ff2b 3efe5e76 3efc8e86 3efabe93 3ef8eea2",
            "3f04014e 3f031956 3f02315e 3f014966 3efef2eb 3efb530a 3ef7b32a 3ef5e33a 3ef41347 3ef24356",
            "3f00aba8 3eff8760 3efdb770 3efbe780 3f015d48 3eff1aaf 3efb7acf 3efb68b1 3efc08e6 3efde12f",
            "3efaac05 3ef8dc15 3ef70c25 3effaf25 3f03411a 3f01712a 3eff4274 3f007714 3f01ff42 3f04bf84",
            "3ef13dfd 3ee955ac 3eefa63f 3efe1753 3f03541a 3f01842a 3eff6874 3f02dc82 3f08dcf7 3f092d13",
            "3ed6bee5 3edac8ed 3ee91066 3efd31d5 3f04196e 3f02497e 3f00798e 3f081a1e 3f0bd408 3f08ba54",
            "3ebe61ef 3ecdf116 3ee3e6a4 3efd846a 3f0616d6 3f0446e6 3f0276f6 3f07a75f 3f07f778 3eff7e18"
        )
        val GOLDEN_GF16 = listOf(
            "4389a837 43a90d25 43c72eca 43ed0fec 4407eb45 4417f93f 4425073c 442fffd4 439b4c50 43bd4ca7 43dd03cd 43fa835d 440c3da7 441a3751 442a0a7c 436177d2",
            "43a79906 43c5acb0 43e33815 44040ca1 441550fb 44254738 4432629c 43999202 43b82440 43d84918 43f6e30c 440a4894 44195337 4426b909 43702d0c 43928ccf",
            "43c3e3da 43e172b4 43fedd0e 441190eb 4422b68a 44326252 439b7280 43b6e03d 43d476c5 43f307bf 4408879d 44177541 44264125 437dd61f 43990fd3 43b416cd",
            "43e6f293 4401cd6c 441037e0 4420af22 44301637 4399f6f5 43b61bcc 43d3256c 43f06ba2 44070853 4415db11 44248312 4381a182 439e2033 43ba143a 43d60341",
            "4403580b 44119e51 441ff60f 442eef71 4397c1dd 43b54ada 43d30131 43f090e0 4406a18e 441511b2 44236911 4380d029 439f42d6 43bf944f 43dcbe82 43f96724",
            "4411e124 44201d9a 442e77da 43962523 43b45a41 43d2e3d8 43f127a0 44075fca 44155c5b 442350df 437e384c 439ddfd4 43bec31a 43e0f4a5 43ff0e3d 440e5b4b",
            "441d653a 442c0c78 4391e55a 43b268fa 43d2f350 43f24c59 44087c8e 44173ea0 44249864 437ea366 439cf493 43bd6fa2 43df9c88 4401a492 441197c7 442168e7",
            "442736a8 438b57e2 43ad7093 43d131ac 43f317a2 44099506 441923bd 44279bfc 4382ad53 439e7aee 43bd902c 43de7cb8 44008c26 4412fee9 4423ddfa 4434c33d",
            "43897bcd 43ab4656 43cd0479 43f1e9fe 440a42ca 441ab90f 442a43b0 44382a7c 43a3ca56 43c0bad5 43dfd840 43ffeaab 4410e84f 4422c8dc 4433d532 43a494fe",
            "43b0307a 43cfd2bc 43efb929 4409b2e1 441ac955 442b06db 4439d84c 43a9d188 43c4850e 43e20931 44005afa 4410077e 44204ca7 44308e77 439ae95f 43b837da",
            "43d64bb5 43f3f40e 440906d4 441a1f98 442a913b 4439f30f 43ac6091 43c8499b 43e442ba 4400ed56 44100f3a 441f5404 442e6e91 439612a0 43b2c71a 43cf4004",
            "43f99b28 440b21e6 4419b6ff 4429dfba 44391911 43ac3032 43c8deeb 43e5bf34 440138b7 4410008d 441edbb9 442da035 4395236e 43b31632 43cfc5a9 43ec5233",
            "440e7f86 441c7709 442a710d 4438b063 43a9fc2f 43c74c30 43e4f1a9 44014b56 440fee70 441e733e 442c85ab 4392d5e4 43b1a40c 43d2928e 43ef6773 440607da",
            "441f75c0 442cf93d 443a0b9f 43a68004 43c417cf 43e1f315 43fffa8f 440f45c3 441e1106 442bc7e2 438e3dac 43ad953b 43cfe077 43f4253c 4408c7c9 44178f35",
            "44303abf 443d75e3 43a59038 43c05be0 43de7127 43fc1f3e 440d5c9b 441d6f05 442cb823 438e3d17 43a873df 43c86059 43eae908 4407e51b 4416aaca 4425b49f",
            "44412332 439eed52 43be93a9 43da8700 43f87ef1 440aff3e 441adad4 442bdafd 439302a6 43a8f3d4 43c30fcf 43e32bc1 4402f92f 4415e9c0 4424fa90 4434ad07"
        )
        val GOLDEN_GF1014 = listOf(
            "43f51504 43f82421 43f997eb 43fa20af 43fc9015 43fc8c33 43fb5fd0 43f9c7ed 43f727f8 43f68531 43f6bc00 43f63dca 43f6416f 43f72851",
            "43f719ac 43f94d39 43fa2ea2 43fa4ed9 43fbb3b2 43fb15bf 43f97d9f 43f77277 43f4bded 43f3a0d1 43f33ec1 43f28c91 43f24775 43f2c137",
            "43f75cf0 43f95530 43fa0f12 43fa130c 43fb0462 43fa3de9 43f88c0b 43f68d09 43f381e0 43f1d508 43f0cedd 43efdaef 43ef39c7 43ef1930",
            "43f6fcbc 43f90296 43f9c58c 43f9d003 43faa422 43f9e5f2 43f8728d 43f670bd 43f33097 43f13268 43efdefd 43eec7cb 43ede511 43ed8aa8",
            "43f73275 43f8ee08 43f97f79 43f96692 43f950eb 43f8a2b4 43f7539c 43f532b5 43f185bd 43eeade7 43ec4a6b 43eabf3a 43e96106 43e83522",
            "43f3fb7c 43f63ee9 43f72aed 43f74289 43f7acdd 43f7a2da 43f6c3b7 43f4a2d3 43f085eb 43ed09e4 43ea264c 43e86aaa 43e6b1e8 43e4fe29",
            "43eedc61 43f23d7a 43f3d7c8 43f48799 43f64031 43f74e08 43f719ee 43f51827 43f098c6 43ecce5c 43ea9680 43e8d9fa 43e71ff4 43e56a4e",
            "43ed52c6 43f0f498 43f2d509 43f3ab6a 43f5b6e2 43f71bb8 43f72240 43f5234c 43f05f84 43ec8771 43ea672c 43e8a0e2 43e6d931 43e50f09",
            "43e9b1a0 43ee5fee 43f0e880 43f236fa 43f53912 43f7802b 43f82fd8 43f67f7a 43f172c6 43ed70bc 43eb717a 43e99d85 43e7c2ae 43e5dbce",
            "43e5f81a 43eb9e15 43eecbc5 43f09033 43f4a6ef 43f7e422 43f9343f 43f78bef 43f27fb1 43ee9bf8 43ed2b83 43eb71b3 43e9bb74 43e80b77"
        )
        val GOLDEN_GFCLAMP = listOf(
            "438fba04 43acb469 43c9cf8c 43e7284d 44031c4e 441220dd 442062c4 442f9307 439a5f64 43b74f5b",
            "43aade25 43c85fdd 43e5e56b 4401c36d 44116eba 44208634 442ec6fa 439a569e 43b789d8 43d495d6",
            "43c5f64e 43e41ea9 44010f2c 44100b0d 441fe38e 442f1cf7 439991fc 43b7d426 43d52df8 43f26291",
            "43e18725 44001f1e 440f530d 441e74c8 442e79df 4399a9a0 43b7445e 43d5aabe 43f334ea 44085213",
            "43fe4f11 440ed17b 441e3c28 442d8405 439897ab 43b70d62 43d57962 43f40fc4 4408f73e 4417e9c3",
            "440d0fa5 441d0616 442ca3c7 4395b1e2 43b51fc5 43d49bb6 43f3dd06 44097345 4418b3d0 44281fd2",
            "441a516a 442a916c 43927f01 43b1844b 43d1d182 43f22532 440924e9 4418fd60 4428aaec 4388a098",
            "4428b626 438f63c5 43af3557 43ce63e1 43eef3eb 4407e3b0 44185476 4428585c 4387b689 43a6dacd",
            "4389b6d6 43ab534f 43cb8854 43eb0718 44061647 4416f850 44280e30 44386701 43a69f7d 43c649d7",
            "43a55ca1 43c75b3c 43e7d5e6 4403c94d 4414a87b 44261ddb 4437fa96 43a663b4 43c5bed0 43e61418"
        )
    }
}
