package com.matthew.rawlens

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.FloatBuffer

class HdrFlowNetAlignerTest {
    private val w = HdrFlowNetAligner.MODEL_WIDTH
    private val h = HdrFlowNetAligner.MODEL_HEIGHT

    private fun buffer(fill: (x: Int, y: Int, c: Int) -> Float): FloatBuffer {
        val b = FloatBuffer.allocate(w * h * 4)
        for (y in 0 until h) for (x in 0 until w) for (c in 0..3) {
            b.put((y * w + x) * 4 + c, if (c == 3) 255f else fill(x, y, c))
        }
        return b
    }

    @Test fun tieFlowIsRejected() {
        // Identical proxies with a zero field: warped error exactly equals
        // identity error. Accepting would resample (soften) the alternate
        // for zero geometric benefit.
        val base = buffer { x, y, c -> (x * 0.3f + y * 0.7f + c) % 200f }
        val flow = FloatArray(w * h * 2)
        assertFalse(HdrFlowNetAligner.hasUsableCorrespondence(base, base.duplicate(), flow))
    }

    @Test fun improvingFlowIsAccepted() {
        // Moving content shifted +4px with a matching +4px field: warped
        // error collapses while identity error stays large.
        val base = buffer { x, y, c -> (x * 0.3f + y * 0.7f + c) % 200f }
        val moving = buffer { x, y, c ->
            val sx = (x - 4).coerceIn(0, w - 1)
            base.get((y * w + sx) * 4 + c)
        }
        val flow = FloatArray(w * h * 2) { i -> if (i % 2 == 0) 4f else 0f }
        assertTrue(HdrFlowNetAligner.hasUsableCorrespondence(base, moving, flow))
    }

    @Test fun wrongDirectionFlowIsRejected() {
        // Same shifted pair but the field points the wrong way: warped
        // error exceeds identity, so the field must not be used.
        val base = buffer { x, y, c -> (x * 0.3f + y * 0.7f + c) % 200f }
        val moving = buffer { x, y, c ->
            val sx = (x - 4).coerceIn(0, w - 1)
            base.get((y * w + sx) * 4 + c)
        }
        val flow = FloatArray(w * h * 2) { i -> if (i % 2 == 0) -4f else 0f }
        assertFalse(HdrFlowNetAligner.hasUsableCorrespondence(base, moving, flow))
    }
}
