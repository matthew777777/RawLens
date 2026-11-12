// SPDX-FileCopyrightText: 2026 RawLens contributors
// SPDX-License-Identifier: GPL-3.0-or-later

package com.matthew.rawlens

import android.app.Dialog
import android.widget.Button
import android.widget.LinearLayout

/**
 * The Settings dialog locals each tab page uses (Settings*Tab.kt): the page
 * [content] column, the shared [kit], and one handle per page in the order
 * General, Photo, Video, Exposure, Burst, Merge, Denoise, Lenses, Debug, About.
 * A page calls [markActive] with its own handle once it has filled [content].
 */
internal class SettingsPage(
    val content: LinearLayout,
    val kit: SettingsKit,
    val markActive: (Button) -> Unit,
    val tabs: List<Button>,
) {
    lateinit var dialog: Dialog
}
