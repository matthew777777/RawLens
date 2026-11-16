// SPDX-License-Identifier: GPL-3.0-or-later
package com.matthew.rawlens

import android.graphics.ImageFormat
import android.media.ImageReader
import android.os.Handler

/** Mode4 needs a streaming output beside RAW to avoid the slower RAW-only capture policy. */
internal object LapisVideoPreview {
    fun create(spec: LapisRootSession.Spec?, handler: Handler): ImageReader? {
        if(spec?.mode != LapisRootProfile.Mode.VIDEO60)return null
        return ImageReader.newInstance(640,480,ImageFormat.YUV_420_888,2).apply {
            setOnImageAvailableListener({ reader -> reader.acquireNextImage()?.close() },handler)
        }
    }
}
