// SPDX-FileCopyrightText: 2026 RawLens contributors
// SPDX-License-Identifier: GPL-3.0-or-later

package com.matthew.rawlens

import android.app.AlertDialog
import android.widget.Button
import android.widget.LinearLayout

/**
 * The Settings dialog locals each tab page uses (Settings*Tab.kt): the page
 * [content] column, the shared [kit], tab highlighting and the tab chips in
 * order General, Photo, Video, Exposure, Burst, Denoise, Lenses, Debug, About.
 */
internal class SettingsPage(
    val content: LinearLayout,
    val kit: SettingsKit,
    val markActive: (Button) -> Unit,
    val tabs: List<Button>,
) {
    lateinit var dialog: AlertDialog
}
