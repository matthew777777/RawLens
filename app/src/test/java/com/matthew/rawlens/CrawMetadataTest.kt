// SPDX-License-Identifier: GPL-3.0-or-later
package com.matthew.rawlens

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.mockito.Mockito.*

class CrawMetadataTest {
    private fun withJson(action: (MutableMap<String, Any>) -> Unit) {
        val written = linkedMapOf<String, Any>()
        mockConstruction(JSONObject::class.java) { json, _ ->
            `when`(json.getLong("timestamp")).thenReturn(1000L)
            `when`(json.getInt("width")).thenReturn(4096)
            `when`(json.getInt("height")).thenReturn(3072)
            doAnswer { invocation ->
                written[invocation.getArgument(0)] = invocation.getArgument<Boolean>(1)
                json
            }.`when`(json).put(anyString(), anyBoolean())
            doAnswer { invocation ->
                val value = invocation.getArgument<Any?>(1)
                requireNotNull(value) { "optional null entered JSON" }
                written[invocation.getArgument(0)] = value
                json
            }.`when`(json).put(anyString(), any<Any>())
        }.use {
            mockConstruction(JSONArray::class.java).use { action(written) }
        }
    }

    @Test fun `missing optional metadata still serializes matched frame`() = withJson { written ->
        CrawMetadata.frameValues("{}", 1000L, 900L, null, null,
            mapOf("aperture" to null, "digitalZoomRatio" to Float.NaN))
        assertEquals("unavailable", written["whiteBalanceSource"])
        assertFalse(written.containsKey("asShotNeutral"))
        assertFalse(written.containsKey("aperture"))
        assertFalse(written.containsKey("digitalZoomRatio"))
        assertEquals("100", written["timestamp"])
        assertEquals(true, written["metadataMatched"])
    }

    @Test fun `invalid vendor neutral uses RAW estimate without crashing`() = withJson { written ->
        CrawMetadata.frameValues("{}", 1000L, 900L, DoubleArray(3) { Double.NaN },
            FloatArray(4) { 1f }, emptyMap()) { doubleArrayOf(0.7451, 1.0, 0.3004) }
        assertEquals("rawEstimate", written["whiteBalanceSource"])
        assertTrue(written.containsKey("asShotNeutral"))
    }

    @Test fun `mismatched exposure is still rejected`() = withJson {
        assertThrows(IllegalArgumentException::class.java) {
            CrawMetadata.frameValues("{}", 999L, 900L, null, null, emptyMap())
        }
    }
}
