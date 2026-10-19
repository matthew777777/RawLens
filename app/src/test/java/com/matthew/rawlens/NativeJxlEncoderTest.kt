// SPDX-FileCopyrightText: 2026 RawLens contributors
// SPDX-License-Identifier: GPL-3.0-or-later

package com.matthew.rawlens

import org.junit.Assert.assertThrows
import org.junit.Test
import org.mockito.Mockito.mock
import java.nio.ByteBuffer

class NativeJxlEncoderTest {
    @Test fun encodeRejectsOutOfRangeOrientationBeforeTouchingNative() {
        val rgb = ByteBuffer.allocateDirect(4 * 4 * 3 * 2)
        val descriptor = mock(android.os.ParcelFileDescriptor::class.java)
        assertThrows(IllegalArgumentException::class.java) {
            NativeJxlEncoder.encode(rgb, 4, 4, descriptor, JxlOutputSettings(), orientation = 0)
        }
        assertThrows(IllegalArgumentException::class.java) {
            NativeJxlEncoder.encode(rgb, 4, 4, descriptor, JxlOutputSettings(), orientation = 9)
        }
    }
}
