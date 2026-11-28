package me.srishanth.opentrack360.sharing

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class ShareDestinationTest {
    @Test
    fun acceptsTheSenderOnlyDestinationShape() {
        val destination = ShareDestination(latitude = 19.076, longitude = 72.8777, radiusMeters = 150)

        assertEquals(19.076, destination.latitude, 0.0)
        assertEquals(72.8777, destination.longitude, 0.0)
        assertEquals(150, destination.radiusMeters)
    }

    @Test
    fun rejectsInvalidCoordinatesAndRadius() {
        assertThrows(IllegalArgumentException::class.java) {
            ShareDestination(latitude = 91.0, longitude = 72.0, radiusMeters = 150)
        }
        assertThrows(IllegalArgumentException::class.java) {
            ShareDestination(latitude = 19.0, longitude = 181.0, radiusMeters = 150)
        }
        assertThrows(IllegalArgumentException::class.java) {
            ShareDestination(latitude = 19.0, longitude = 72.0, radiusMeters = 49)
        }
    }
}
