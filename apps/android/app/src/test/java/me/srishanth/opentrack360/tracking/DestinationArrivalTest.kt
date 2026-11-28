package me.srishanth.opentrack360.tracking

import java.time.Instant
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class DestinationArrivalTest {
    @Test
    fun destinationEventNeedsTwoFreshAccurateFixesAtLeastSixtySecondsApart() {
        val detector = DestinationArrivalDetector()
        val target = ArrivalShareTarget(SHARE_ID, 19.076, 72.8777)
        val first = sample(0)
        val tooSoon = sample(59)
        val confirmed = sample(60)

        assertNull(detector.observe(target, first, at(0)))
        assertNull(detector.observe(target, tooSoon, at(59)))
        val event = detector.observe(target, confirmed, at(60))

        assertEquals(ShareEventType.ARRIVAL, event?.eventType)
        assertEquals(at(60).toString(), event?.observedAt)
        assertEquals(listOf(first.sampleId, confirmed.sampleId), event?.evidenceFixes?.map { it.sampleId })
    }

    @Test
    fun destinationDetectorResetsForPoorStaleOrOutOfRadiusFixes() {
        val detector = DestinationArrivalDetector()
        val target = ArrivalShareTarget(SHARE_ID, 19.076, 72.8777)
        val first = sample(0)
        assertNull(detector.observe(target, first, at(0)))

        assertNull(detector.observe(target, sample(60, accuracy = 40.0), at(60)))
        assertNull(detector.observe(target, sample(120), at(120)))
        assertNull(detector.observe(target, sample(181, longitude = 72.8800), at(181)))

        val freshCandidate = sample(240)
        assertNull(detector.observe(target, freshCandidate, at(240)))
        assertNull(detector.observe(target, sample(300), at(300).plusSeconds(301)))
        val restarted = sample(360)
        assertNull(detector.observe(target, restarted, at(360)))
        val arrived = detector.observe(target, sample(420), at(420))
        assertEquals(ShareEventType.ARRIVAL, arrived?.eventType)
    }

    @Test
    fun arrivalTargetsPersistEncryptedAndRemainOwnerAndSessionScoped() {
        val storage = InMemoryTrackingBlobStorage()
        val target = ArrivalShareTarget(SHARE_ID, 19.076, 72.8777)
        val firstStore = ArrivalShareTargetStore(storage, PrefixCipher())

        assertTrue(firstStore.put(MEMBER_ID, SESSION_ID, target))
        assertEquals(PrefixCipher.MARKER, storage.read()!!.first())
        val restored = ArrivalShareTargetStore(storage, PrefixCipher())

        assertEquals(listOf(target), restored.activeFor(MEMBER_ID, SESSION_ID))
        assertTrue(restored.activeFor(MEMBER_ID, "session-2").isEmpty())
        assertTrue(restored.activeFor("member-2", SESSION_ID).isEmpty())
        assertTrue(restored.clearExcept(MEMBER_ID, "session-2"))
        assertTrue(restored.activeFor(MEMBER_ID, SESSION_ID).isEmpty())
    }

    @Test
    fun arrivalTargetStoreIsBoundedAndClearsOnRevokeOrGlobalStop() {
        val store = ArrivalShareTargetStore(
            InMemoryTrackingBlobStorage(),
            IdentityTrackingQueueCipher(),
            maxTargets = 1,
        )
        val first = ArrivalShareTarget(SHARE_ID, 19.076, 72.8777)
        val second = ArrivalShareTarget(OTHER_SHARE_ID, 19.077, 72.8777)

        assertTrue(store.put(MEMBER_ID, SESSION_ID, first))
        assertTrue(!store.put(MEMBER_ID, SESSION_ID, second))
        assertTrue(store.removeForShare(first.shareId))
        assertTrue(store.put(MEMBER_ID, SESSION_ID, second))
        assertTrue(store.clearFor(MEMBER_ID))
        assertTrue(store.activeFor(MEMBER_ID, SESSION_ID).isEmpty())
        assertTrue(store.put(MEMBER_ID, SESSION_ID, first))
        assertTrue(store.clearAll())
        assertTrue(store.activeFor(MEMBER_ID, SESSION_ID).isEmpty())
    }

    private class PrefixCipher : TrackingQueueCipher {
        override fun encrypt(plaintext: ByteArray): ByteArray = byteArrayOf(MARKER) + plaintext
        override fun decrypt(ciphertext: ByteArray): ByteArray {
            require(ciphertext.first() == MARKER)
            return ciphertext.copyOfRange(1, ciphertext.size)
        }

        companion object { const val MARKER: Byte = 0x5a }
    }

    private fun sample(
        second: Long,
        accuracy: Double = 8.0,
        longitude: Double = 72.8777,
    ) = LocationSample.create(at(second), 19.076, longitude, accuracy, 0.0)

    private fun at(second: Long) = BASE.plusSeconds(second)

    private companion object {
        val BASE: Instant = Instant.parse("2026-11-26T10:00:00Z")
        const val SHARE_ID = "e8b4c35c-7069-4b61-8e36-b81b87c3530e"
        const val OTHER_SHARE_ID = "0c7e9418-2cb2-458b-a192-bb8b6f9e2e31"
        const val MEMBER_ID = "member-1"
        const val SESSION_ID = "session-1"
    }
}
