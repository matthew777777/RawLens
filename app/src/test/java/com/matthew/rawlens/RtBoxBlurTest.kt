// SPDX-FileCopyrightText: 2026 RawLens contributors
// SPDX-License-Identifier: GPL-3.0-or-later

package com.matthew.rawlens

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Parity against RT boxblur2 / boxblur_resamp.
 *
 * Goldens below were machine-generated from the float32 C++ oracle replicating
 * references/RawTherapee/hilite_recon.cc:48-129 and :131-293 verbatim
 * (see /tmp/rt_blur_oracle.cpp); the generator script that embedded them read
 * /tmp/rt_blur_goldens.txt, so no hand transcription is involved.
 *
 * Assertion is per-element ULP distance, not raw bits: HotSpot on ARM64 fuses
 * a*b+c recurrences into FMA (single rounding) while the C++ oracle rounds
 * twice, which the parity gate itself caught as 1-ulp deviations. The C++
 * oracle is contraction-stable (identical goldens at -O0 and -O2
 * -ffp-contract=fast), so the bound below measures JVM fusion only. GLSL
 * compilers are likewise allowed to fuse, so GPU stages inherit this envelope.
 */
class RtBoxBlurTest {
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
    fun boxBlur2SmallMatchesRt() {
        val dst = RtBoxBlur.boxBlur2(pattern(16, 16), 0, 0, 16, 16, 1)
        assertUlpClose("blur2_16", GOLDEN_BLUR2_16, dst, 12)
    }

    @Test
    fun boxBlur2RtSizedMatchesRt() {
        val dst = RtBoxBlur.boxBlur2(pattern(24, 32), 0, 0, 24, 32, 4)
        assertUlpClose("blur2_32", GOLDEN_BLUR2_32, dst, 40)
    }

    @Test
    fun boxBlur2TailMatchesBlockedBitwise() {
        // Same content through w=48 (all blocked) and w=40 (blocked prefix
        // + scalar tail): cols 0..35 must agree bit-for-bit — the tail runs
        // the identical recurrence on identical inputs. Cols 36..39
        // legitimately differ (different right-edge widths shrink
        // differently, exactly as RT would); they must only stay finite.
        val src48 = pattern(24, 48)
        val blocked = RtBoxBlur.boxBlur2(src48, 0, 0, 24, 48, 4)
        val src40 = Array(24) { i -> FloatArray(40) { j -> src48[i][j] } }
        val tailed = RtBoxBlur.boxBlur2(src40, 0, 0, 24, 40, 4)
        for (i in 0 until 24) for (j in 0 until 36) {
            assertEquals(
                "cell ($i,$j): blocked=${blocked[i][j]} tail=${tailed[i][j]}",
                blocked[i][j].toBits(), tailed[i][j].toBits()
            )
        }
        for (i in 0 until 24) for (j in 36 until 40) {
            assertTrue("tail cell ($i,$j) non-finite: ${tailed[i][j]}", tailed[i][j].isFinite())
        }
    }

    @Test
    fun boxBlurResampScalarRemainderPathMatchesRt() {
        // W/samp = 10 -> blocked col 0 only, scalar cols 8..9: covers both paths.
        val dst = RtBoxBlur.boxBlurResamp(pattern(12, 20), 2, 2)
        assertEquals(6, dst.size)
        assertEquals(10, dst[0].size)
        assertUlpClose("resamp_20", GOLDEN_RESAMP_20, dst, 8)
    }

    @Test
    fun boxBlurResampRtSizedMatchesRt() {
        val dst = RtBoxBlur.boxBlurResamp(pattern(24, 32), 2, 4)
        assertEquals(6, dst.size)
        assertEquals(8, dst[0].size)
        assertUlpClose("resamp_32", GOLDEN_RESAMP_32, dst, 16)
    }

    private companion object {
        val GOLDEN_BLUR2_16 = listOf(
            "3e0f189c 3e549d4e 3eafd358 3ef5580a 3f1d6e5e 3f4030b7 3f384865 3f05b568 3ea644d7 3e967433 3edbf8e5 3f10becc 3f338124 3f2b98d3 3ef20bac 3e94ce05",
            "3e5191e3 3e8b8b4a 3ed10ffb 3f0b4a57 3f2e0cb0 3f345d41 3f1e3c0c 3ed7521f 3eab0fb3 3eb7b0d7 3efd3588 3f215d1d 3f27adaf 3f118c79 3ebdf2f9 3e8b5ffd",
            "3eab4238 3ece0491 3f09c4a1 3f2c86fb 3f32d78c 3f1cb656 3ed446b4 3ea80449 3eb4a56c 3efa2a1e 3f1fd768 3f2627fa 3f1006c4 3ebae78e 3e8ea524 3e7107dd",
            "3eedbb7f 3f083eec 3f2b0144 3f3151d7 3f1b30a1 3ed13b47 3ea4f8de 3eb19a01 3ef71eb3 3f1e51b2 3f24a244 3f0e810f 3eb7dc22 3e8b99b7 3e983adb 3ebafd34",
            "3f181a63 3f297b90 3f2fcc20 3f19aaec 3ece2fdd 3ea1ed70 3eae8e96 3ef41348 3f1ccbfd 3f231c8e 3f0cfb59 3eb4d0b9 3e888e4c 3e952f70 3edab422 3efd767b",
            "3f395707 3f2e466c 3f182535 3ecb2474 3e9ee208 3eab8329 3ef107dd 3f1b4648 3f2196da 3f0b75a4 3eb1c54e 3e8582e4 3e922405 3ed7a8b8 3f0e96b5 3f1ff7e2",
            "3f2fe900 3f169f81 3ec81906 3e9bd69e 3ea877c0 3eedfc70 3f19c092 3f201124 3f09efef 3eaeb9e2 3e827777 3e8f189c 3ed49d4c 3f0d10ff 3f2fd358 3f413485",
            "3ef7a09c 3ec50d9e 3e98cb30 3ea56c57 3eeaf107 3f183adc 3f3afd36 3f24dc00 3ee49208 3e7ed818 3e8c0d2f 3ed191e3 3f0b8b49 3f2e4da2 3f349e34 3f37c67d",
            "3e8f6f38 3e95bfc8 3ea260e8 3ee7e59e 3f16b527 3f39777f 3f3fc812 3f0d3515 3eb54431 3e8901c3 3ece8675 3f0a0595 3f2cc7ec 3f33187e 3f1cf749 3f03adca",
            "3e792653 3e9f5581 3ee4da2f 3f152f73 3f37f1cb 3f3e425c 3f282128 3eeb1c55 3ebed9eb 3ecb7b0c 3f087fdf 3f2b4239 3f3192c9 3f1b7194 3ed1bd2e 3e9f2a31",
            "3ebf0c71 3ee1cec8 3f13a9bb 3f366c17 3f3cbca7 3f269b71 3ee810eb 3ebbce7f 3ec86fa4 3f06fa2a 3f29bc83 3f300d15 3f19ebde 3eceb1c4 3ea26f59 3e8c4e23",
            "3f00c2dc 3f122408 3f34e65f 3f3b36f4 3f2515bd 3ee5057e 3eb8c316 3ec56439 3f057476 3f2836ce 3f2e8760 3f18662b 3ecba658 3e9f63ef 3eac0512 3ecec76b",
            "3f21ff80 3f3360ac 3f39b13b 3f239009 3ee1fa15 3eb5b7a7 3ec258ce 3f03eec0 3f26b119 3f2d01aa 3f16e075 3ec89af1 3e9c5882 3ea8f9a8 3eee7e59 3f08a059",
            "3f433c23 3f382b88 3f220a50 3edeeead 3eb2ac3f 3ebf4d60 3f02690b 3f252b64 3f2b7bf6 3f155abf 3ec58f86 3e994d1c 3ea5ee3b 3eeb72ef 3f187bd0 3f29dcfd",
            "3f39ce1c 3f20849d 3edbe33b 3eafa0d6 3ebc41f6 3f00e353 3f23a5ae 3f29f640 3f13d50a 3ec28418 3e9641af 3ea2e2d3 3ee86781 3f16f61b 3f39b873 3f4b19a0",
            "3f351719 3f067844 3ea7ca86 3e97f9ec 3edd7e99 3f1181a4 3f344400 3f2c5bae 3ef39162 3e8e6b65 3e7d3587 3ec41f76 3f04d211 3f27946c 3f4a56c4 3f5bb7f1"
        )
        val GOLDEN_BLUR2_32 = listOf(
            "3eff0e62 3f018c34 3f00a43c 3efbd04e 3ef3e7fc 3f00448f 3f069521 3f073558 3f022533 3efa2a20 3ef009d7 3ee5e98c 3ee729fb 3ef3cb1e 3f003620 3f00d657 3f01768e 3f0216c4 3f02b6fc 3f035732 3f09a7c4 3f0ff856 3f10988d 3f0b8869 3f067845 3f016820 3ef8aff9 3ef9f066 3f00207c 3efe7109 3ef07056 3ee3694c",
            "3f00c959 3f011975 3f00317d 3efd0cf1 3ef8b2d8 3f02a9fd 3f043cee 3f01123d 3efbcf19 3ef579b9 3eef2458 3ee8cef5 3eebf4d7 3ef895fb 3efbbbdb 3efee1bc 3f0103cf 3f0296bf 3f0429b0 3f05bca0 3f0c0d32 3f0da023 3f0a7573 3f074ac2 3f042012 3f00f561 3efb9561 3efebb43 3f00becd 3efb1b63 3ef53b31 3ef02b0d",
            "3efe3d0d 3efedd45 3efecb27 3efe4995 3efd7db5 3f00ff2a 3efe5e75 3efabe93 3ef71eb2 3ef37ed3 3eefdef2 3eec3f10 3ef0bfb2 3ef54055 3ef9c0f5 3efe4197 3f01611d 3f03a16d 3f05e1be 3f08220e 3f0a625f 3f08926f 3f06c27f 3f04f28f 3f03229f 3f0152ae 3eff057d 3f01c30f 3f015d1e 3f011c56 3f011347 3f016362",
            "3ef73f2d 3efa0187 3efcc3df 3eff8638 3efb2c20 3ef9945d 3ef7fc9e 3ef664dd 3ef4cd1d 3ef3355e 3ef19d9d 3ef005dc 3eee6e1c 3ef3f2cf 3ef97780 3efefc32 3f024072 3f0502cb 3f07c524 3f06f943 3f062d63 3f056184 3f0495a4 3f03c9c4 3f02fde4 3f023204 3f016624 3f009a44 3f01fb70 3f035c9d 3f04bdc9 3f061ef5",
            "3eedd125 3ef421b9 3efa7249 3f00616d 3efa6f15 3efa6bde 3efa68ab 3efa6574 3efa6240 3efa5f0b 3efa5bd5 3ef40674 3eedb112 3ef4000b 3efa4f00 3f004efc 3f037678 3f069df4 3f069c5a 3f0371a8 3f03700d 3f036e74 3f036cd9 3f036b3f 3f0369a4 3f03680a 3f003d59 3f003bbf 3f0299c2 3f04bdfb 3f068b84 3f07ce58",
            "3ef766de 3efdb772 3f020401 3f019e10 3efdb2a1 3efdaf6a 3efdac37 3efda900 3efda5cc 3efda297 3ef74d35 3ef0f7d4 3ef0f49e 3ef74397 3efd928c 3f01f0c2 3f05183e 3f0516a4 3f01ebf4 3f01ea58 3f01e8bd 3f01e724 3f01e589 3f01e3ef 3f01e254 3efd6f47 3efd6c12 3f01dd85 3f03d665 3f057896 3f0698bf 3f06e8d9",
            "3f007e4b 3f03a695 3f02be9c 3f02dab3 3f007b17 3f00797b 3f0077e2 3f007646 3f0074ac 3efa93f7 3ef43e95 3ef43b60 3ef4382a 3efa8723 3f006b0c 3f039288 3f0390ee 3f00663e 3f0064a4 3f006308 3f00616d 3f005fd4 3f005e39 3f005c9f 3efa63dc 3efa60a7 3f0057cf 3f037f4b 3f051308 3f063331 3f06a5fa 3f06035a",
            "3f054927 3f03b3d0 3f037937 3f041756 3f021cdd 3f021b41 3f0219a8 3f02180c 3efddab8 3ef78557 3ef78221 3ef77eec 3ef77bb6 3efdcaaf 3f020cd2 3f020b38 3efdc110 3efdbddc 3efdbaa8 3efdb770 3efdb43a 3efdb108 3efdadd2 3ef75871 3ef7553c 3efda433 3f01f995 3f052111 3f064fab 3f06edcc 3f06b335 3f051ddb",
            "3f0463a8 3f03c10b 3f0433d2 3f0553f9 3f03bea3 3f03bd07 3f03bb6e 3f0090bc 3efacc18 3efac8e3 3efac5ad 3efac278 3efabf43 3f00871e 3f008582 3efab5a4 3efab270 3efaaf3c 3efaac08 3efaa8d0 3efaa59a 3efaa268 3ef44d06 3ef449d1 3efa98c8 3f0073e0 3f039b5b 3f06c2d7 3f078c4e 3f07a867 3f06c070 3f09e8b7",
            "3f037e29 3f03ce46 3f04ee6d 3f06909c 3f056069 3f055ecd 3f02341e 3efe12d8 3efe0fa4 3efe0c6f 3efe0939 3efe0604 3efe02cf 3efdff9c 3ef7aa38 3ef7a704 3ef7a3d0 3ef7a09c 3ef79d68 3ef79a30 3ef796fa 3ef1419b 3ef13e66 3ef78d5d 3efddc54 3f0215a6 3f053d21 3f08649d 3f08c8f1 3f086302 3f0b8b4c 3f0eb393",
            "3f0298aa 3f03db81 3f05a908 3f07cd3f 3f07022f 3f03d77d 3f00acce 3f00ab32 3f00a998 3f00a7fe 3f00a663 3f00a4c8 3f00a32e 3efaf0fc 3efaedc4 3efaea90 3efae75d 3efae429 3efae0f5 3efaddbd 3ef4885a 3eee32fb 3ef481f3 3efad0ea 3f008ff0 3f03b76c 3f06dee7 3f06dd4d 3f06775c 3f091d9e 3f0b9888 3f0dce15",
            "3f01b32b 3f03e8bc 3f0663a3 3f0909e2 3f057adf 3f02502d 3f024e94 3f024cf8 3f024b5e 3f0249c4 3f024829 3f02468e 3efe37bc 3efe3488 3efe3150 3efe2e1d 3efe2aea 3efe27b6 3efe2482 3ef7cf1d 3ef179ba 3ef17688 3ef7c580 3efe1477 3f0231b6 3f055932 3f055797 3f022ce7 3f0425c7 3f05c7f8 3f06e822 3f07383b",
            "3f00cdac 3f03f5f7 3f071e3e 3f06b84c 3f03f38f 3f03f1f3 3f03f05a 3f03eebe 3f03ed24 3f03eb8a 3f03e9ef 3f00bf3e 3f00bda4 3f00bc0a 3f00ba6e 3f00b8d5 3f00b73b 3f00b5a1 3efb15e2 3ef4c07d 3ef4bd46 3ef4ba14 3efb090c 3f00ac02 3f03d37c 3f03d1e2 3f00a731 3f00a597 3f01d432 3f027252 3f0237bc 3f00a261",
            "3f059888 3f08c0d3 3f07d8d9 3f0466b6 3f026c3f 3f026aa3 3f02690a 3f02676e 3f0265d4 3f02643a 3efe7311 3efe6fdc 3f025f6a 3f025dd0 3f025c34 3f025a9b 3f025901 3efe5ca2 3ef80742 3ef80409 3ef800d2 3ef7fda0 3efe4c98 3f024dc8 3f024c2c 3efe42f8 3efe3fc2 3efe3c8e 3eff0539 3efe3958 3efb0eac 3ef4190e",
            "3f0a6364 3f08ce0e 3f048333 3f021521 3f00e4ef 3f00e353 3f00e1ba 3f00e01e 3f00de84 3efb67a8 3efb6471 3f00d9b4 3f040130 3f03ff96 3f03fdfa 3f03fc61 3f00d1b1 3efb4e02 3efb4ace 3efb4795 3efb445e 3efb412d 3f00c812 3f00c678 3efb378c 3efb3458 3efb3122 3efb2dee 3efa620e 3ef78e0d 3ef1ade0 3ee6ed5b",
            "3f097de5 3f041da8 3f012d8d 3eff8717 3efebb3e 3efeb806 3efeb4d4 3efeb19c 3ef85c3b 3ef85908 3efea7fd 3f027b7a 3f05a2f6 3f05a15c 3f059fc0 3f027511 3efe94c2 3efe918e 3efe8e5a 3efe8b21 3efe87ea 3efe84b9 3efe8184 3ef82c24 3ef828ec 3ef825b8 3ef82282 3ef81f4e 3ef5bee2 3ef0e2c1 3ee84d13 3ee5225d",
            "3f02e80b 3efeda84 3efbafce 3efae3ec 3efbac9e 3efba966 3efba634 3ef550cf 3ef54d9b 3efb9c94 3f00f5c5 3f041d40 3f0744bc 3f074322 3f041870 3f00edc1 3f00ec27 3f00ea8d 3f00e8f3 3f00e757 3f00e5bb 3f00e423 3efb72e4 3efb6fb0 3efb6c78 3efb6944 3efb660e 3efb62da 3ef83829 3ef257f8 3ee86789 3eeeb816",
            "3ef8a463 3ef579b8 3ef50482 3ef640c1 3ef89dfe 3ef89ac6 3ef24568 3ef2422f 3ef89127 3efee020 3f02978b 3f05bf06 3f08e682 3f05bbd2 3f029120 3f028f87 3f028ded 3f028c53 3f028ab9 3f02891d 3f028781 3efeb9a6 3efeb670 3efeb33c 3efeb004 3efeacd0 3efea99a 3efea666 3efab170 3ef3cd2f 3ef1fd42 3ef84dcf",
            "3ef6d966 3ef5942e 3ef679b8 3ef8ba08 3efbe18a 3ef58c26 3eef36c8 3ef585bb 3efbd4b3 3f0111d6 3f043951 3f0760cc 3f075f32 3f043482 3f0432e6 3f04314d 3f042fb3 3f042e19 3f042c7f 3f042ae3 3f010031 3f00fe99 3f00fcfe 3f00fb64 3f00f9c8 3f00f82e 3f00f693 3efb97c6 3ef60e45 3ef54266 3ef217b8 3ef682d2",
            "3ef50e69 3ef5aea4 3ef7eeef 3efb334f 3ef8d2ea 3ef27d86 3ef27a54 3ef8c947 3eff183f 3f02b39c 3f05db17 3f05d97c 3f02aecc 3f02ad32 3f02ab96 3f02a9fd 3f02a863 3f02a6c9 3f02a52f 3efef4f9 3efef1c2 3f02a05f 3f029ec4 3f029d2a 3f029b8e 3f0299f4 3efede86 3ef88926 3ef8878b 3ef6b79c 3ef2322e 3ef4b7d4",
            "3ef86d2e 3ef6eb47 3ef6a169 3ef71a9c 3ef815e0 3ef67e1d 3ef4e660 3efa6b0d 3effefc0 3f02ba3a 3f057c92 3f04b0b1 3f03e4d2 3f0318f2 3f024d11 3f018132 3f00b553 3effd2e6 3efe3b28 3efca363 3f011409 3f03d665 3f030a84 3f023ea5 3f0172c3 3f00a6e4 3effb606 3efe1e48 3efb8b4b 3ef77237 3ef11001 3ef3d255",
            "3f00f22a 3efb341b 3ef5e87f 3efaa062 3efde4cd 3efa44e9 3ef6a50b 3efb25a8 3effa64b 3f021377 3f0453c7 3f069417 3f04c428 3f02f437 3f012446 3efea8ae 3efb08d0 3ef768ef 3efbe992 3f003517 3f027567 3f04b5bb 3f02e5ca 3f0115da 3efe8bd0 3f01863a 3f03c689 3f01f69a 3eff1113 3ef9560a 3ef24ca6 3ef7cd25",
            "3f01b77d 3ef5d654 3ef60e7d 3efee934 3f020535 3efdb504 3ef75fa7 3efa8583 3efdab65 3f0068a4 3f06b935 3f084c25 3f052175 3f01f6c4 3efd9825 3ef742c7 3ef0ed68 3ef41349 3f005a37 3f01ed25 3f038014 3f051309 3f01e857 3efd7b4d 3f005095 3f06a129 3f083417 3f050968 3f01acf3 3efcf7af 3ef71783 3f018c35",
            "3ef84c0a 3ef218a6 3ef79923 3f02350d 3f055d5d 3f004d36 3ef67a28 3ef7ba90 3ef8fafe 3f02ce12 3f091ea3 3f09bed9 3f04aeb5 3eff3d20 3ef51cd6 3eeafc92 3eec3d02 3ef8de25 3f02bfa5 3f035fd9 3f04000e 3f04a04a 3eff2048 3f00305a 3f0680ea 3f0cd17e 3f0d71b2 3f086190 3f046d66 3f01b152 3f00c95e 3f0d0bce"
        )
        val GOLDEN_RESAMP_20 = listOf(
            "3e8b8b4a 3ed10ffd 3f1cfb9f 3f0d2afc 3ed8928e 3efd358c 3f10ec44 3ee0151f 3ec073d7 3f034c60",
            "3ece0492 3eff0e62 3f11d8b0 3ef668d8 3ef21612 3f011f17 3efdeb68 3ed0a2e0 3ef545dd 3f17bc36",
            "3f186a7e 3f0ecd44 3ef05202 3eebff3b 3efc2758 3ef7d491 3eca8c09 3eef2f06 3f142672 3f1a357d",
            "3f058e6f 3eea3b2a 3ee5e865 3f0545b1 3f031c4f 3ec47533 3ee91830 3f111b07 3f0ef1a5 3eec90ba",
            "3ec3429e 3edfd18f 3f0c77b7 3f148bc6 3ee75422 3ee3015b 3f0e0f9e 3f0be63a 3eea83ec 3ef18348",
            "3ec09222 3f030b6a 3f2229b6 3f0e14d0 3ef3ffcf 3f0c5166 3f11d619 3ee1e8c5 3eecf228 3f0dcfcc"
        )
        val GOLDEN_RESAMP_32 = listOf(
            "3e8b8b4a 3f1cfb9f 3ed8928e 3f10ec44 3ec073d7 3f26ff0b 3eec9966 3f09dea0",
            "3f186a7e 3ef05202 3efc2758 3eca8c09 3f142673 3ef6b195 3f0b80e9 3ee56681",
            "3ec3429e 3f0c77b7 3ee75422 3f0e0f9e 3eea83ef 3f056a11 3ed938d9 3f1b7cd8",
            "3f224f98 3f020e1b 3f07f8c8 3ede5644 3f0990af 3ecd0b28 3f156605 3ef930b7",
            "3ed70cd3 3f01e1f0 3ed22898 3f17f4b9 3efe4e29 3f0f4f2d 3eed0312 3ef8d864",
            "3f1b23a1 3ec5fae4 3f11dde3 3ef22077 3f093859 3ee0d55f 3f150daf 3ee4052c"
        )
    }
}
