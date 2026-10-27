// SPDX-License-Identifier: GPL-3.0-or-later
package com.matthew.rawlens

/**
 * CRS container constants shared by the staging/rescue checks and the
 * CRS backend (see [CrsWriter] / [CrsReader]). A take starts with
 * [HEADER_TAG] plus a [VERSION] byte and ends with a 24-byte little-endian
 * footer: `0, 16, FOOTER_MAGIC, frameCount, indexOffset (int64)`.
 */
internal object CrsFormat {
    const val HEADER_TAG = "CRSRAW "
    const val VERSION = 1
    const val FOOTER_MAGIC = 0x43525346 // "CRSF"
}
