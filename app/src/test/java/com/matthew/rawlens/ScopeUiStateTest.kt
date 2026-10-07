// SPDX-FileCopyrightText: 2026 RawLens contributors
// SPDX-License-Identifier: GPL-3.0-or-later

package com.matthew.rawlens

import org.junit.Assert.assertEquals
import org.junit.Test

class ScopeUiStateTest {
    @Test
    fun tapTogglesPowerAndKeepsMode() {
        assertEquals(
            ScopeUiState(false, ScopeMode.HISTOGRAM),
            ScopeUiState(true, ScopeMode.HISTOGRAM).onTap()
        )
        assertEquals(
            ScopeUiState(true, ScopeMode.WAVEFORM),
            ScopeUiState(false, ScopeMode.WAVEFORM).onTap()
        )
    }

    @Test
    fun longPressSwitchesGraphAndWakesScope() {
        assertEquals(
            ScopeUiState(true, ScopeMode.WAVEFORM),
            ScopeUiState(true, ScopeMode.HISTOGRAM).onLongPress()
        )
        assertEquals(
            ScopeUiState(true, ScopeMode.HISTOGRAM),
            ScopeUiState(true, ScopeMode.WAVEFORM).onLongPress()
        )
        // Switching from off shows the newly selected graph immediately.
        assertEquals(
            ScopeUiState(true, ScopeMode.WAVEFORM),
            ScopeUiState(false, ScopeMode.HISTOGRAM).onLongPress()
        )
    }
}
