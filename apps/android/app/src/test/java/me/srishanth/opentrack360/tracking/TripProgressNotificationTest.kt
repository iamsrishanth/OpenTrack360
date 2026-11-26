package me.srishanth.opentrack360.tracking

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class TripProgressNotificationTest {
    @Test
    fun optedInNotificationFormatsAvailableTripProgress() {
        val result = TripProgressNotificationFormatter.format(
            TripProgressNotificationContent(
                destinationLabel = "Home",
                etaLabel = "6:40 PM",
                distanceRemainingMeters = 12_400,
            ),
        )

        assertEquals("Trip to Home", result?.title)
        assertEquals("ETA 6:40 PM · 12.4 km remaining", result?.body)
    }

    @Test
    fun publicLockScreenVersionDoesNotExposeTripDetails() {
        val result = TripProgressNotificationFormatter.format(
            TripProgressNotificationContent("Private destination", "6:40 PM", 4_500),
        )

        assertEquals("OpenTrack360 trip progress", result?.publicTitle)
        assertEquals("Trip is active. Details are hidden.", result?.publicBody)
        assertEquals(false, result?.publicBody?.contains("Private destination"))
        assertEquals(false, result?.publicBody?.contains("6:40 PM"))
    }

    @Test
    fun absentDestinationAndEtaDoNotInventProgress() {
        val result = TripProgressNotificationFormatter.format(
            TripProgressNotificationContent(null, null, null),
        )

        assertEquals("Trip progress", result?.title)
        assertEquals("Trip progress is active.", result?.body)
        assertNull(TripProgressNotificationFormatter.format(null))
    }
}
