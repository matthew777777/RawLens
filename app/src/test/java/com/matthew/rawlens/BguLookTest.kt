// SPDX-FileCopyrightText: 2026 RawLens contributors
// SPDX-License-Identifier: GPL-3.0-or-later
package com.matthew.rawlens

import org.junit.Assert.assertArrayEquals
import org.junit.Test

/** Pure-layout pins for the BGU look facade (no native library needed). */
class BguLookTest {
    @Test fun transposeSwapsRowsAndColumns() {
        val colMajor = floatArrayOf(1f, 2f, 3f, 4f, 5f, 6f, 7f, 8f, 9f)
        assertArrayEquals(
            floatArrayOf(1f, 4f, 7f, 2f, 5f, 8f, 3f, 6f, 9f),
            BguLook.transpose(colMajor), 0f
        )
    }
}
