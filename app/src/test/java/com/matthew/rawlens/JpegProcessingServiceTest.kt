// SPDX-License-Identifier: GPL-3.0-or-later
package com.matthew.rawlens

import android.content.Context
import android.content.Intent
import org.junit.Test
import org.mockito.Mockito.*

class JpegProcessingServiceTest {
    @Test fun `fast completion queues stop behind foreground startup`() {
        val context = mock(Context::class.java)
        mockConstruction(Intent::class.java).use { intents ->
            JpegProcessingService.start(context)
            JpegProcessingService.stop(context)
            val start = intents.constructed()[0]
            val stop = intents.constructed()[1]
            val calls = inOrder(context)
            calls.verify(context).startForegroundService(start)
            calls.verify(context).startService(stop)
            verify(stop).setAction("com.matthew.rawlens.STOP_JPEG_PROCESSING")
            verify(context, never()).stopService(any(Intent::class.java))
        }
    }

    @Test fun `stop releases directly when background delivery is rejected`() {
        val context = mock(Context::class.java)
        mockConstruction(Intent::class.java).use { intents ->
            `when`(context.startService(any(Intent::class.java)))
                .thenThrow(IllegalStateException("background start not allowed"))
            JpegProcessingService.stop(context)
            verify(context).stopService(intents.constructed()[1])
        }
    }
}
