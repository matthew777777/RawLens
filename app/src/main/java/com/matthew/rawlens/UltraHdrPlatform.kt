// SPDX-FileCopyrightText: 2026 RawLens contributors
// SPDX-License-Identifier: GPL-3.0-or-later

package com.matthew.rawlens

/** Recycles the transient Ultra HDR gain-map bitmap after the file writer is done with it. */
fun recycleUltraHdrGainmapContents(developed: DevelopedJpeg) {
    developed.gainmap?.recycle()
}
