// SPDX-FileCopyrightText: 2026 RawLens contributors
// SPDX-License-Identifier: GPL-3.0-or-later

package com.matthew.rawlens

import android.graphics.Bitmap
import android.os.Build
import android.annotation.TargetApi

/** Keeps Android 14-only Bitmap gainmap calls out of the normal JPEG path. */
@TargetApi(Build.VERSION_CODES.UPSIDE_DOWN_CAKE)
fun recycleUltraHdrGainmapContents(bitmap: Bitmap) {
    bitmap.gainmap?.gainmapContents?.recycle()
}
