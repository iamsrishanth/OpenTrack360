package me.srishanth.opentrack360.ui.coordination

import java.time.Instant
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class HistoryStopsTest {
    @Test
    fun derivesAStopOnlyAfterFiveMinutesOfCredibleStationaryFixes() {
        val fixes = (0L..300L step 15L).map(::sample)

        val stops = deriveHistoryStops(fixes)

        assertEquals(1, stops.size)
        assertEquals(at(0), stops.single().startedAt)
        assertEquals(at(300), stops.single().endedAt)
    }

    @Test
    fun doesNotTreatShortWaitGpsGapOrUnreliablePrecisionAsAStop() {
        val shortWait = (0L..285L step 15L).map(::sample)
        val gap = listOf(sample(0), sample(90), sample(181), sample(271), sample(361))
        val approximate = (0L..300L step 15L).map { sample(it, precision = "bubble_approximate") }
        val unknownSpeed = (0L..300L step 15L).map { sample(it, speedMps = null) }

        assertTrue(deriveHistoryStops(shortWait).isEmpty())
        assertTrue(deriveHistoryStops(gap).isEmpty())
        assertTrue(deriveHistoryStops(approximate).isEmpty())
        assertTrue(deriveHistoryStops(unknownSpeed).isEmpty())
    }

    @Test
    fun inaccurateFixesAndMovementBreakTheCandidate() {
        val fixes = (0L..240L step 15L).map(::sample) +
            sample(255, accuracy = 70.0) +
            (270L..570L step 15L).map { sample(it, latitude = 19.078) }

        val stops = deriveHistoryStops(fixes)
        assertEquals(1, stops.size)
        assertEquals(at(270), stops.single().startedAt)
    }

    private fun sample(
        second: Long,
        latitude: Double = BASE_LATITUDE,
        longitude: Double = BASE_LONGITUDE,
        accuracy: Double = 8.0,
        speedMps: Double? = 0.0,
        precision: String = "precise",
    ) = HistoryStopSample(
        latitude = latitude,
        longitude = longitude,
        accuracyMeters = accuracy,
        capturedAt = at(second),
        speedMps = speedMps,
        precision = precision,
    )

    private fun at(second: Long) = BASE_TIME.plusSeconds(second)

    private companion object {
        val BASE_TIME: Instant = Instant.parse("2026-11-26T10:00:00Z")
        const val BASE_LATITUDE = 19.076
        const val BASE_LONGITUDE = 72.8777
    }
}
