// SPDX-License-Identifier: GPL-3.0-or-later
package com.matthew.rawlens

import android.content.Context
import android.view.ContextThemeWrapper
import com.google.android.material.materialswitch.MaterialSwitch
import com.google.android.material.checkbox.MaterialCheckBox
import com.google.android.material.slider.Slider
import com.google.android.material.slider.TickVisibilityMode
import kotlin.math.roundToInt

/** Material 3 controls with RawLens colors, without changing the camera activity theme. */
internal class SettingsSwitch @JvmOverloads constructor(
    context: Context,
    attrs: android.util.AttributeSet? = null
) : MaterialSwitch(
    ContextThemeWrapper(context, R.style.Theme_RawLens_SettingsControls), attrs
)

/** Multi-selection remains a checkbox; uses the same palette and Material state handling. */
internal class SettingsCheckBox(context: Context) : MaterialCheckBox(
    ContextThemeWrapper(context, R.style.Theme_RawLens_SettingsControls)
)

/**
 * Integer setting ranges and commit-on-release callbacks shared by settings pages.
 * Uses the actual Material slider for drawing, gestures, keyboard and accessibility.
 */
internal class SettingsSlider(context: Context) : Slider(
    ContextThemeWrapper(context, R.style.Theme_RawLens_SettingsControls)
) {
    private var trackingTouch = false
    private var settingsListener: OnSettingsSliderChangeListener? = null

    var max: Int
        get() = valueTo.roundToInt()
        set(maximum) {
            require(maximum > 0)
            if (value > maximum) value = maximum.toFloat()
            valueTo = maximum.toFloat()
        }

    var progress: Int
        get() = value.roundToInt()
        set(progress) { value = progress.coerceIn(0, max).toFloat() }

    init {
        valueFrom = 0f
        valueTo = 100f
        stepSize = 1f
        tickVisibilityMode = TickVisibilityMode.TICK_VISIBILITY_HIDDEN
        addOnChangeListener { _, value, fromUser ->
            settingsListener?.onProgressChanged(this, value.roundToInt(), fromUser)
            // Keyboard and accessibility changes have no touch-release event.
            if (fromUser && !trackingTouch) settingsListener?.onStopTrackingTouch(this)
        }
        addOnSliderTouchListener(object : OnSliderTouchListener {
            override fun onStartTrackingTouch(slider: Slider) {
                trackingTouch = true
                settingsListener?.onStartTrackingTouch(this@SettingsSlider)
            }
            override fun onStopTrackingTouch(slider: Slider) {
                trackingTouch = false
                settingsListener?.onStopTrackingTouch(this@SettingsSlider)
            }
        })
    }

    fun setOnSettingsSliderChangeListener(listener: OnSettingsSliderChangeListener) {
        settingsListener = listener
    }

    interface OnSettingsSliderChangeListener {
        fun onProgressChanged(seekBar: SettingsSlider, progress: Int, fromUser: Boolean)
        fun onStartTrackingTouch(seekBar: SettingsSlider)
        fun onStopTrackingTouch(seekBar: SettingsSlider)
    }
}
