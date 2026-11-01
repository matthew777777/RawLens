// SPDX-License-Identifier: GPL-3.0-or-later
package com.matthew.rawlens

import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout

/** Keep the portrait panel beside the capture controls, within one-handed reach. */
internal object QuickPanelLayout {
    fun place(panel: View, rotation: Float) {
        val params = panel.layoutParams as? FrameLayout.LayoutParams ?: return
        val gravity = Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL
        if (params.width != ViewGroup.LayoutParams.MATCH_PARENT || params.gravity != gravity) {
            params.width = ViewGroup.LayoutParams.MATCH_PARENT
            params.gravity = gravity
            panel.layoutParams = params
        }
        panel.rotation = 0f
        panel.translationX = 0f
        panel.translationY = 0f
        fun rotateLabels(view: View) {
            if (view is QuickControlTileView) view.contentRotation = rotation
            if (view is ViewGroup) for (i in 0 until view.childCount) rotateLabels(view.getChildAt(i))
        }
        rotateLabels(panel)
    }
}
