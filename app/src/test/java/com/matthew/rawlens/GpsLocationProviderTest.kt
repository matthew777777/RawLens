package com.matthew.rawlens

import android.location.Location
import android.location.LocationManager
import org.junit.Assert.*
import org.junit.Test
import org.mockito.Mockito.mock
import org.mockito.Mockito.`when`

class GpsLocationProviderTest {
    @Test fun fromLocationMapsGpsFix() {
        val fix = fix(
            provider = LocationManager.GPS_PROVIDER,
            latitude = 48.858222, longitude = 2.2945,
            altitude = 35.5, hasAltitude = true,
            time = 1780276800000L
        )
        val location = GpsLocationProvider.fromLocation(fix)
        assertNotNull(location)
        assertEquals(48.858222, location!!.latitude, 0.0)
        assertEquals(2.2945, location.longitude, 0.0)
        assertEquals(35.5, location.altitudeMeters!!, 0.0)
        assertEquals(GpsLocation.METHOD_GPS, location.processingMethod)
        assertEquals(1780276800000L, location.timeMillis)
    }

    @Test fun fromLocationMarksNetworkFixes() {
        val fix = fix(
            provider = LocationManager.NETWORK_PROVIDER,
            latitude = 51.5, longitude = -0.12,
            altitude = 0.0, hasAltitude = false,
            time = 1000L
        )
        val location = GpsLocationProvider.fromLocation(fix)
        assertNotNull(location)
        assertEquals(GpsLocation.METHOD_NETWORK, location!!.processingMethod)
        assertNull(location.altitudeMeters)
    }

    @Test fun fromLocationRejectsUnrepresentableFixes() {
        assertNull(
            GpsLocationProvider.fromLocation(
                fix(LocationManager.GPS_PROVIDER, Double.NaN, 0.0, null, false, 0L)
            )
        )
        assertNull(
            GpsLocationProvider.fromLocation(
                fix(LocationManager.GPS_PROVIDER, 91.0, 0.0, null, false, 0L)
            )
        )
    }

    @Test fun permissionsRequestsBothGranularities() {
        assertArrayEquals(
            arrayOf(
                "android.permission.ACCESS_FINE_LOCATION",
                "android.permission.ACCESS_COARSE_LOCATION"
            ),
            GpsLocationProvider.permissions()
        )
    }

    private fun fix(
        provider: String,
        latitude: Double,
        longitude: Double,
        altitude: Double?,
        hasAltitude: Boolean,
        time: Long
    ): Location {
        val fix = mock(Location::class.java)
        `when`(fix.provider).thenReturn(provider)
        `when`(fix.latitude).thenReturn(latitude)
        `when`(fix.longitude).thenReturn(longitude)
        `when`(fix.hasAltitude()).thenReturn(hasAltitude)
        if (altitude != null) `when`(fix.altitude).thenReturn(altitude)
        `when`(fix.hasAccuracy()).thenReturn(false)
        `when`(fix.time).thenReturn(time)
        return fix
    }
}
