package me.srishanth.opentrack360.tracking

import java.time.Instant
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class TrackingEngineTest {
    @Test
    fun parkedStartDoesNotClassifyAsDrive() {
        val engine = startedEngine()
        val updates = listOf(0L, 10L, 20L).map { second ->
            engine.onLocation(scope, precise, true, sample(second, speed = 0.0), MotionEvidence.STATIONARY, at(second))
        }

        assertTrue(updates.all { it is LocationProcessResult.Accepted && it.driveUpdate == null })
        assertFalse(engine.isDriveActive)
    }

    @Test
    fun redLightAndShortTrafficStopDoNotEndDrive() {
        val engine = startedEngine()
        assertTrue(ingest(engine, sample(0, speed = 11.0, lon = 0.0), MotionEvidence.MOVING) is LocationProcessResult.Accepted)
        val started = ingest(engine, sample(10, speed = 12.0, lon = 0.001), MotionEvidence.MOVING)
        assertTrue((started as LocationProcessResult.Accepted).driveUpdate is DriveUpdate.Started)

        // A 90 second red light is a stop in the trip summary, but cannot end the trip.
        for (second in listOf(20L, 50L, 80L, 110L)) {
            val update = ingest(engine, sample(second, speed = 0.0, lon = 0.001), MotionEvidence.STATIONARY)
            assertFalse((update as LocationProcessResult.Accepted).driveUpdate is DriveUpdate.Ended)
        }
        val resumed = ingest(engine, sample(120, speed = 9.0, lon = 0.002), MotionEvidence.MOVING)
        assertFalse((resumed as LocationProcessResult.Accepted).driveUpdate is DriveUpdate.Ended)
        assertTrue(engine.isDriveActive)
    }

    @Test
    fun poorAccuracyFixIsRejectedBeforeDriveClassification() {
        val engine = startedEngine()
        val result = ingest(engine, sample(0, speed = 12.0, accuracy = 250.0), MotionEvidence.MOVING)

        assertEquals(SampleRejection.POOR_ACCURACY, (result as LocationProcessResult.Rejected).reason)
        assertFalse(engine.isDriveActive)
    }

    @Test
    fun outOfOrderFixIsRejected() {
        val engine = startedEngine()
        assertTrue(ingest(engine, sample(10, speed = 0.0), MotionEvidence.STATIONARY) is LocationProcessResult.Accepted)
        val result = ingest(engine, sample(9, speed = 0.0), MotionEvidence.STATIONARY)

        assertEquals(SampleRejection.OUT_OF_ORDER, (result as LocationProcessResult.Rejected).reason)
    }

    @Test
    fun stationaryGpsGapDoesNotEndDrive() {
        val engine = startedEngine()
        ingest(engine, sample(0, speed = 10.0, lon = 0.0), MotionEvidence.MOVING)
        ingest(engine, sample(10, speed = 10.0, lon = 0.001), MotionEvidence.MOVING)
        ingest(engine, sample(20, speed = 0.0, lon = 0.001), MotionEvidence.STATIONARY)
        val afterGap = ingest(engine, sample(400, speed = 0.0, lon = 0.001), MotionEvidence.STATIONARY)

        assertFalse((afterGap as LocationProcessResult.Accepted).driveUpdate is DriveUpdate.Ended)
        assertTrue(engine.isDriveActive)
    }

    @Test
    fun confirmedTripCanBeCorrectedToPassengerAfterItEnds() {
        val engine = startedEngine()
        ingest(engine, sample(0, speed = 10.0, lon = 0.0), MotionEvidence.MOVING)
        ingest(engine, sample(10, speed = 10.0, lon = 0.001), MotionEvidence.MOVING)
        var ended: DriveUpdate.Ended? = null
        for (second in listOf(20L, 50L, 80L, 110L, 140L, 170L, 200L, 230L, 260L, 290L, 320L)) {
            val result = ingest(engine, sample(second, speed = 0.0, lon = 0.001), MotionEvidence.STATIONARY)
            (result as LocationProcessResult.Accepted).driveUpdate.let { if (it is DriveUpdate.Ended) ended = it }
        }

        assertNotNull(ended)
        assertEquals(TripRole.PASSENGER, engine.correctLastTripRole(TripRole.PASSENGER)?.role)
    }

    private fun startedEngine(): TrackingEngine = TrackingEngine().also {
        assertTrue(it.start(scope, precise, consentEnabled = true, explicitUserAction = true))
    }

    private fun ingest(engine: TrackingEngine, fix: LocationSample, motion: MotionEvidence) =
        engine.onLocation(scope, precise, consentEnabled = true, sample = fix, motion = motion, now = at(fix.capturedAt))

    private fun sample(
        seconds: Long,
        speed: Double?,
        lon: Double = 0.0,
        accuracy: Double = 8.0,
    ) = LocationSample.create(at(seconds), 19.076, 72.8777 + lon, accuracy, speed)

    private fun at(seconds: Long) = at(BASE.plusSeconds(seconds).toString())
    private fun at(value: String) = Instant.parse(value)

    private companion object {
        val BASE: Instant = Instant.parse("2026-11-26T10:00:00Z")
        val scope = TrackingScope("member-1", "session-1", 1, 1, LocationPrecision.PRECISE)
        val precise = PermissionSnapshot(true, LocationPrecision.PRECISE, true, true, false)
    }
}

