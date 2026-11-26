// SPDX-FileCopyrightText: 2026 RawLens contributors
// SPDX-License-Identifier: GPL-3.0-or-later

package com.matthew.rawlens

import android.content.Context

/** F-Droid builds ship no prebuilt network runtime: portrait mode is unavailable. */
object PortraitNetsFactory {
    fun available(context: Context): Boolean = false
    fun create(context: Context): PortraitNets? = null
}
