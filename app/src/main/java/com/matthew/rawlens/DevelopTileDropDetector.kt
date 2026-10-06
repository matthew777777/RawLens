// SPDX-FileCopyrightText: 2026 RawLens contributors
// SPDX-License-Identifier: GPL-3.0-or-later

package com.matthew.rawlens

/** Grid position of one AMaZE develop tile inside the full-frame output. */
data class DevelopTile(val originX: Int, val originY: Int, val width: Int, val height: Int)

/**
 * Detects dropped AMaZE tiles in a developed frame.
 *
 * The GLES AMaZE develop renders the frame in [AmazePipelineContract.TILE]-aligned tiles. A tile
 * whose dispatch chain sampled uninitialized memory renders as an exactly tile-sized pure-black
 * square, sharp at the tile grid (the scanline JPEG encoder faithfully preserves the bitmap).
 *
 * Only a *partially* black frame is suspicious: a uniformly black frame (lens cap, body cap)
 * legitimately quantizes to exact zero everywhere — the ±0.5 LSB output dither still rounds to
 * zero at encoded black — so it reports no drops. A razor-sharp full-tile zero region next to lit
 * content cannot come from a real scene (sensor noise alone guarantees non-zero samples across a
 * tile) and always indicates a dropped tile. Alpha is ignored: unwritten regions read back with
 * zero alpha too.
 *
 * Pure Kotlin with no Android types so the verdict stays JVM-testable; callers pass a pixel reader
 * (production: `Bitmap::getPixel`, which returns ARGB).
 */
object DevelopTileDropDetector {
    /** Samples per tile side; 8x8 centers catch any full-tile drop while costing ~1 ms a frame. */
    private const val SAMPLES_PER_SIDE = 8

    fun findZeroTiles(
        width: Int,
        height: Int,
        tileSize: Int = AmazePipelineContract.TILE,
        pixelAt: (x: Int, y: Int) -> Int
    ): List<DevelopTile> {
        require(width > 0 && height > 0) { "Frame dimensions must be positive" }
        require(tileSize > 0) { "Tile size must be positive" }
        val drops = ArrayList<DevelopTile>()
        var originY = 0
        var tileCount = 0
        while (originY < height) {
            val tileHeight = minOf(tileSize, height - originY)
            var originX = 0
            while (originX < width) {
                val tileWidth = minOf(tileSize, width - originX)
                tileCount++
                if (isZeroTile(originX, originY, tileWidth, tileHeight, pixelAt)) {
                    drops += DevelopTile(originX, originY, tileWidth, tileHeight)
                }
                originX += tileSize
            }
            originY += tileSize
        }
        // A uniformly black frame is a dark scene, not a tile drop.
        return if (drops.size == tileCount) emptyList() else drops
    }

    private fun isZeroTile(
        originX: Int,
        originY: Int,
        tileWidth: Int,
        tileHeight: Int,
        pixelAt: (x: Int, y: Int) -> Int
    ): Boolean {
        for (sy in 0 until SAMPLES_PER_SIDE) {
            val y = originY + (sy * tileHeight + tileHeight / 2) / SAMPLES_PER_SIDE
            for (sx in 0 until SAMPLES_PER_SIDE) {
                val x = originX + (sx * tileWidth + tileWidth / 2) / SAMPLES_PER_SIDE
                if ((pixelAt(x, y) and 0x00FFFFFF) != 0) return false
            }
        }
        return true
    }
}
