// SPDX-License-Identifier: GPL-3.0-or-later
package com.matthew.rawlens

import android.content.Context
import android.content.Intent
import org.junit.Test
import org.mockito.Mockito.*

class ProcessingWorkTest {
    @Test fun `overlapping saves and merges keep service until last completion`() {
        val context = mock(Context::class.java)
        `when`(context.applicationContext).thenReturn(context)
        mockConstruction(Intent::class.java).use {
            ProcessingWork.begin(context)
            ProcessingWork.begin(context)
            verify(context, times(1)).startForegroundService(any(Intent::class.java))
            ProcessingWork.end(context)
            verify(context, never()).startService(any(Intent::class.java))
            ProcessingWork.end(context)
            verify(context, times(1)).startService(any(Intent::class.java))
        }
    }

    @Test fun `rejected foreground start still balances completion`() {
        val context = mock(Context::class.java)
        `when`(context.applicationContext).thenReturn(context)
        mockConstruction(Intent::class.java).use {
            `when`(context.startForegroundService(any(Intent::class.java)))
                .thenThrow(IllegalStateException("background start rejected"))
            ProcessingWork.begin(context)
            ProcessingWork.end(context)
            verify(context).startService(any(Intent::class.java))
        }
    }
}
