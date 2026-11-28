package me.srishanth.opentrack360.tracking

import java.time.Instant
import java.util.UUID
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TrackingReliabilityPolicyTest {
    @Test
    fun identicalValidatedProviderFixIsDeduplicatedAcrossSampleIds() {
        val deduplicator = LocationFixDeduplicator()
        val fix = sample(0)
        val now = fix.instantOrNull()!!

        assertEquals(null, LocationSampleValidator.validate(fix, now))
        assertFalse(deduplicator.isDuplicate(fix))
        assertTrue(deduplicator.isDuplicate(fix.copy(
            sampleId = UUID.randomUUID().toString(),
            accuracyMeters = fix.accuracyMeters + 1.0,
            speedMps = 1.0,
        )))
        assertFalse(deduplicator.isDuplicate(fix.copy(longitude = fix.longitude + 0.0001)))

        deduplicator.reset()
        assertFalse(deduplicator.isDuplicate(fix))
    }

    @Test
    fun deduplicatorForgetsOldFixesWithinItsBound() {
        val deduplicator = LocationFixDeduplicator(maxRememberedFixes = 2)
        assertFalse(deduplicator.isDuplicate(sample(0)))
        assertFalse(deduplicator.isDuplicate(sample(1)))
        assertFalse(deduplicator.isDuplicate(sample(2)))
        assertFalse(deduplicator.isDuplicate(sample(0)))
    }

    @Test
    fun interruptionMarkerIsEncryptedScopedAndSurvivesStoreRecreation() {
        val storage = MemoryBlob()
        val scope = TrackingScope("member-1", "session-1", 4, 9, LocationPrecision.PRECISE)
        val firstStore = TrackingInterruptionStore(storage, TestCipher)

        assertTrue(firstStore.markActive(scope))
        assertFalse(String(storage.read()!!, Charsets.UTF_8).contains("member-1"))

        val restoredStore = TrackingInterruptionStore(storage, TestCipher)
        assertTrue(restoredStore.hasActiveMarkerFor("member-1", "session-1", 4, 9))
        assertFalse(restoredStore.hasActiveMarkerFor("member-1", "other-session", 4, 9))
        assertFalse(restoredStore.hasActiveMarkerFor("member-1", "session-1", 5, 9))
        assertFalse(restoredStore.hasActiveMarkerFor("member-1", "session-1", 4, 10))
        assertTrue(restoredStore.hasActiveMarkerFor("member-1", "session-1", 4, 9))

        assertTrue(restoredStore.clearForUser("member-1"))
        assertFalse(TrackingInterruptionStore(storage, TestCipher).hasActiveMarkerFor("member-1", "session-1", 4, 9))
    }

    @Test
    fun batterySaverCadenceLeavesTimeForDelayedCallbacksAndKeepsStationaryFixes() {
        val saver = PermissionSnapshot(true, LocationPrecision.PRECISE, true, true, batterySaverOn = true)

        assertEquals(30_000L, TrackingCadencePolicy.intervalMillis(saver, driving = true))
        assertEquals(60_000L, TrackingCadencePolicy.intervalMillis(saver, driving = false))
        assertEquals(0f, TrackingCadencePolicy.minimumDistanceMeters(saver), 0f)
        assertEquals(30_000L, TrackingCadencePolicy.MAX_DRIVE_EVIDENCE_GAP.toMillis() - TrackingCadencePolicy.BATTERY_SAVER_INTERVAL_MS)

        val engine = TrackingEngine()
        assertTrue(engine.start(scope, saver, consentEnabled = true, explicitUserAction = true))
        ingest(engine, sample(0, speed = 10.0, longitudeOffset = 0.0), MotionEvidence.MOVING)
        val start = ingest(engine, sample(30, speed = 10.0, longitudeOffset = 0.002), MotionEvidence.MOVING)
        assertTrue((start as LocationProcessResult.Accepted).driveUpdate is DriveUpdate.Started)

        var ended: DriveUpdate.Ended? = null
        for (second in 60L..360L step 30L) {
            val result = ingest(engine, sample(second, speed = 0.0, longitudeOffset = 0.002), MotionEvidence.STATIONARY)
            (result as LocationProcessResult.Accepted).driveUpdate.let { if (it is DriveUpdate.Ended) ended = it }
        }

        val evidence = ended?.evidenceFixes.orEmpty()
        assertTrue(evidence.isNotEmpty())
        val stationary = evidence.drop(2)
        assertTrue(stationary.size >= 2)
        assertTrue(Instant.parse(stationary.last().capturedAt).epochSecond - Instant.parse(stationary.first().capturedAt).epochSecond >= 300)
        assertTrue(stationary.zipWithNext().all { (a, b) ->
            Instant.parse(b.capturedAt).epochSecond - Instant.parse(a.capturedAt).epochSecond <=
                TrackingCadencePolicy.MAX_DRIVE_EVIDENCE_GAP.seconds
        })
        assertTrue(DriveEndEvidencePolicy.isSufficient(evidence))
    }

    private fun ingest(engine: TrackingEngine, fix: LocationSample, motion: MotionEvidence) =
        engine.onLocation(scope, precise, consentEnabled = true, sample = fix, motion = motion, now = fix.instantOrNull()!!)

    private fun sample(seconds: Long, speed: Double? = 0.0, longitudeOffset: Double = 0.0): LocationSample =
        LocationSample.create(
            capturedAt = BASE.plusSeconds(seconds),
            latitude = 19.076,
            longitude = 72.8777 + longitudeOffset,
            accuracyMeters = 8.0,
            speedMps = speed,
        )

    private class MemoryBlob : TrackingBlobStorage {
        private var value: ByteArray? = null
        override fun read(): ByteArray? = value?.copyOf()
        override fun write(value: ByteArray): Boolean {
            this.value = value.copyOf()
            return true
        }
        override fun clear() { value = null }
    }

    private object TestCipher : TrackingQueueCipher {
        override fun encrypt(plaintext: ByteArray): ByteArray = plaintext.map { (it.toInt() xor 0x5a).toByte() }.toByteArray()
        override fun decrypt(ciphertext: ByteArray): ByteArray = encrypt(ciphertext)
    }

    private companion object {
        val BASE = Instant.parse("2026-11-27T10:00:00Z")
        val scope = TrackingScope("member-1", "session-1", 4, 9, LocationPrecision.PRECISE)
        val precise = PermissionSnapshot(true, LocationPrecision.PRECISE, true, true, false)
    }
}
