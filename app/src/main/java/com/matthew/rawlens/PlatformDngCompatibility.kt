// SPDX-License-Identifier: GPL-3.0-or-later
package com.matthew.rawlens

/** DngCreator accepts pixel-array or pre-correction active-array dimensions only. */
internal object PlatformDngCompatibility {
    fun matches(width: Int, height: Int, activeWidth: Int?, activeHeight: Int?,
                pixelWidth: Int?, pixelHeight: Int?): Boolean =
        width > 0 && height > 0 &&
            ((width == activeWidth && height == activeHeight) ||
                (width == pixelWidth && height == pixelHeight))
}
