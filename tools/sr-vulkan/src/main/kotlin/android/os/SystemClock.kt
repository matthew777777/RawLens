// SPDX-FileCopyrightText: 2026 RawLens contributors
// SPDX-License-Identifier: GPL-3.0-or-later
//
// DESKTOP HOST SHIM (sr-vulkan only). Mirrors android.os.SystemClock so
// ported timing lines compile byte-identical. Monotonic milliseconds like
// the device call; epoch differs (irrelevant: only intervals are used).
package android.os

object SystemClock {
    @JvmStatic
    fun elapsedRealtime(): Long = System.nanoTime() / 1_000_000L
}
