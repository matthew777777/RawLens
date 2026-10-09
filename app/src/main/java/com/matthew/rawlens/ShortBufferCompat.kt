// SPDX-FileCopyrightText: 2026 RawLens contributors
// SPDX-License-Identifier: GPL-3.0-or-later

package com.matthew.rawlens

import java.nio.ShortBuffer

/**
 * Absolute bulk reads that work back to minSdk.
 *
 * ShortBuffer.get(index, dst, offset, length) compiles against recent SDKs but
 * is missing on older runtimes: a Redmi 9 on API 30 died with
 * NoSuchMethodError on the first sampled RAW frame because android.jar is the
 * compile SDK, not the device. duplicate() + position() + the relative bulk
 * get() are all API-1, keep the single-memcpy bulk speed, and leave the
 * source position untouched.
 */
object ShortBufferCompat {
    fun getBulk(src: ShortBuffer, index: Int, dst: ShortArray, offset: Int, length: Int) {
        val dup = src.duplicate()
        dup.position(index)
        dup.get(dst, offset, length)
    }
}
