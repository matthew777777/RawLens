// SPDX-FileCopyrightText: 2026 RawLens contributors
// SPDX-License-Identifier: GPL-3.0-or-later
package com.matthew.rawlens

import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraDevice
import android.hardware.camera2.CaptureRequest
import org.junit.Assert.*
import org.junit.Test
import org.mockito.ArgumentMatchers.any
import org.mockito.Mockito.*

/** Tests actual application with framework key objects supplied by an advertised catalog. */
class VendorKeyRoutingTest {
    @Suppress("UNCHECKED_CAST")
    private fun key(name: String): CaptureRequest.Key<Int> =
        (mock(CaptureRequest.Key::class.java) as CaptureRequest.Key<Int>).also { `when`(it.name).thenReturn(name) }

    private fun chars(request: List<CaptureRequest.Key<*>> = emptyList(), physical: List<CaptureRequest.Key<*>> = emptyList()): CameraCharacteristics =
        mock(CameraCharacteristics::class.java).also {
            `when`(it.availableCaptureRequestKeys).thenReturn(request)
            `when`(it.availablePhysicalCameraRequestKeys).thenReturn(physical)
            `when`(it.availableSessionKeys).thenReturn(emptyList())
        }

    private fun entry(name: String, scope: VendorKeyScope = VendorKeyScope.REQUEST) =
        VendorKeyEntry(name, VendorKeyType.INT, scope, "7")

    @Test fun `physical only control sets actual advertised key without a logical probe`() {
        val key = key("vendor.physical")
        val chars = chars(physical = listOf(key))
        val builder = mock(CaptureRequest.Builder::class.java)
        val report = VendorKeyApply.applyRequestKeys(builder, VendorKeyProfile(listOf(entry(key.name))), chars, "2", {})
        assertEquals(listOf(key.name), report.applied)
        verify(builder).setPhysicalCameraKey(key, 7, "2")
        verify(builder, never()).get(key)
        verify(builder, never()).set(key, 7)
    }

    @Test fun `physical refusal falls back to logical set without blocking other keys`() {
        val key = key("vendor.physical")
        val chars = chars(physical = listOf(key))
        val builder = mock(CaptureRequest.Builder::class.java)
        doThrow(IllegalArgumentException("not physical")).`when`(builder).setPhysicalCameraKey(key, 7, "2")
        val report = VendorKeyApply.applyRequestKeys(builder, VendorKeyProfile(listOf(entry(key.name))), chars, "2", {})
        assertEquals(listOf(key.name), report.applied)
        verify(builder).set(key, 7)
    }

    @Test fun `failed key is reported and does not suppress the next key`() {
        val bad = key("vendor.bad")
        val good = key("vendor.good")
        val chars = chars(listOf(bad, good))
        val builder = mock(CaptureRequest.Builder::class.java)
        doThrow(IllegalArgumentException("bad value")).`when`(builder).set(bad, 7)
        val report = VendorKeyApply.applyRequestKeys(builder, VendorKeyProfile(listOf(entry(bad.name), entry(good.name))), chars, null, {})
        assertEquals(listOf(good.name), report.applied)
        assertEquals(listOf(bad.name), report.skipped)
    }

    @Test fun `session probe uses logical destination even on a physical route`() {
        val key = key("vendor.session")
        val chars = chars(listOf(key), listOf(key))
        val builder = mock(CaptureRequest.Builder::class.java)
        assertTrue(VendorKeyApply.probe(builder, chars, entry(key.name, VendorKeyScope.SESSION), "2").supported)
        verify(builder).set(key, 7)
        verify(builder, never()).setPhysicalCameraKey(key, 7, "2")
    }

    @Test fun `unresolved numeric tag fails without constructing or writing a hex name`() {
        val chars = chars()
        val builder = mock(CaptureRequest.Builder::class.java)
        val result = VendorKeyApply.probe(builder, chars, entry("0xFFFFFFFF"), null)
        assertFalse(result.supported)
        assertTrue(result.detail.contains("not resolved"))
        verifyNoInteractions(builder)
    }

    @Test fun `discovery offers writable keys only including physical only controls`() {
        val chars = chars(listOf(key("vendor.request")), listOf(key("vendor.physical")))
        val found = VendorKeyAndroid.discover(chars)
        assertEquals(listOf("vendor.physical", "vendor.request"), found.map { it.name })
        assertEquals("physical request", found.first().source)
        verify(chars, never()).keys
    }

    @Test fun `session build attempts unadvertised session tags and uses the same key as probe`() {
        val key = key("vendor.session")
        val chars = chars(listOf(key))
        val device = mock(CameraDevice::class.java)
        val builder = mock(CaptureRequest.Builder::class.java)
        val request = mock(CaptureRequest::class.java)
        `when`(device.createCaptureRequest(CameraDevice.TEMPLATE_PREVIEW)).thenReturn(builder)
        `when`(builder.build()).thenReturn(request)
        val (params, report) = VendorKeyApply.buildSessionParameters(device,
            VendorKeyProfile(listOf(entry(key.name, VendorKeyScope.SESSION))), chars, {})
        assertSame(request, params)
        assertEquals(listOf(key.name), report.applied)
        verify(builder).set(key, 7)
    }
}
