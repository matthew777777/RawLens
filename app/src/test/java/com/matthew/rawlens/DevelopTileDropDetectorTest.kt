// SPDX-FileCopyrightText: 2026 RawLens contributors
// SPDX-License-Identifier: GPL-3.0-or-later

package com.matthew.rawlens

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class DevelopTileDropDetectorTest {
    @Test
    fun defaultTileSizeMatchesAmazeContract() {
        assertEquals(1024, AmazePipelineContract.TILE)
    }

    @Test
    fun flatNonzeroFrameHasNoDrops() {
        val frame = Frame(4080, 3060, 0xFF102030.toInt())
        assertTrue(frame.drops().isEmpty())
    }

    @Test
    fun uniformBlackFrameHasNoDrops() {
        // A lens-cap frame legitimately quantizes to exact zero everywhere (the output dither
        // still rounds to zero at encoded black), so it must never read as a tile drop.
        assertTrue(Frame(4080, 3060, 0xFF000000.toInt()).drops().isEmpty())
        assertTrue(Frame(4080, 3060, 0x00000000).drops().isEmpty())
    }

    @Test
    fun exactZeroFirstTileIsReported() {
        val frame = Frame(4080, 3060, 0xFF102030.toInt())
        frame.zeroRect(0, 0, 1024, 1024)
        assertEquals(listOf(DevelopTile(0, 0, 1024, 1024)), frame.drops())
    }

    @Test
    fun partialEdgeTileDropUsesClippedSize() {
        val frame = Frame(4080, 3060, 0xFF102030.toInt())
        frame.zeroRect(3072, 2048, 1008, 1012)
        assertEquals(listOf(DevelopTile(3072, 2048, 1008, 1012)), frame.drops())
    }

    @Test
    fun customTileSizeGrid() {
        val frame = Frame(200, 100, 0xFF445566.toInt())
        frame.zeroRect(0, 0, 100, 100)
        assertEquals(
            listOf(DevelopTile(0, 0, 100, 100)),
            frame.drops(tileSize = 100)
        )
        assertTrue(Frame(100, 100, 0x00000000).drops(tileSize = 100).isEmpty())
    }

    @Test
    fun smallFrameClean() {
        val frame = Frame(100, 100, 0xFF445566.toInt())
        assertTrue(frame.drops().isEmpty())
    }

    @Test
    fun nonAlignedBlackSquareIsNotATileDrop() {
        val frame = Frame(2048, 1536, 0xFF102030.toInt())
        frame.zeroRect(100, 100, 512, 512)
        assertTrue(frame.drops().isEmpty())
    }

    @Test
    fun zeroAlphaDefectTileIsStillReported() {
        // Unwritten regions read back with zero alpha; the verdict ignores alpha.
        val frame = Frame(2048, 2048, 0xFF102030.toInt())
        frame.zeroRect(0, 0, 1024, 1024)
        assertEquals(listOf(DevelopTile(0, 0, 1024, 1024)), frame.drops())
    }

    /** ARGB frame with a Bitmap.getPixel-shaped reader. Zeroed pixels use alpha 0. */
    private class Frame(val width: Int, val height: Int, fill: (x: Int, y: Int) -> Int) {
        constructor(width: Int, height: Int, fillColor: Int) : this(width, height, { _, _ -> fillColor })

        private val pixels = IntArray(width * height) { index ->
            fill(index % width, index / width)
        }

        fun zeroRect(left: Int, top: Int, rectWidth: Int, rectHeight: Int) {
            for (y in top until top + rectHeight) {
                pixels.fill(0x00000000, y * width + left, y * width + left + rectWidth)
            }
        }

        fun drops(tileSize: Int = AmazePipelineContract.TILE): List<DevelopTile> =
            DevelopTileDropDetector.findZeroTiles(width, height, tileSize) { x, y -> pixels[y * width + x] }
    }
}
