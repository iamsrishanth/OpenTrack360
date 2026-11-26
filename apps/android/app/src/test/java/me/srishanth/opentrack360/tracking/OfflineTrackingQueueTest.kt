package me.srishanth.opentrack360.tracking

import java.time.Instant
import java.util.UUID
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class OfflineTrackingQueueTest {
    @Test
    fun offlineTerminalEventIsFlushedBeforeAnyLaterFix() = runBlocking {
        val queue = queue()
        val transport = RecordingTransport()
        val coordinator = TrackingSyncCoordinator(queue, transport, now = { at(40) })
        val before = sample(1)
        val evidence = PendingShareEvent(
            eventType = ShareEventType.DRIVE_END,
            observedAt = at(2).toString(),
            evidenceFixes = listOf(sample(0), sample(2)),
        )
        val after = sample(3)

        assertNull(queue.enqueueSample(scope, before, at(40)))
        assertTrue(queue.enqueueShareEvent(scope, SHARE_ID, evidence, at(40)))
        assertNull(queue.enqueueSample(scope, after, at(40)))
        coordinator.flush(scope, hasLocationPermission = true, consentEnabled = true)

        assertEquals(listOf("fix:${before.sampleId}", "event:${evidence.clientEventId}", "fix:${after.sampleId}"), transport.calls)
        assertEquals(listOf(scope.sharingEpoch, scope.sharingEpoch), transport.batchEpochs)
    }

    @Test
    fun offlineArrivalEventIsFlushedBeforeLaterMovementWhenConnectionReturns() = runBlocking {
        val queue = queue()
        val transport = TerminalShareTransport()
        val coordinator = TrackingSyncCoordinator(queue, transport, now = { at(80) })
        val target = ArrivalShareTarget(SHARE_ID, 19.076, 72.8777)
        val detector = DestinationArrivalDetector()
        val arrivalOne = sample(0)
        val arrivalTwo = sample(60)
        val terminalEvent = detector.observe(target, arrivalOne, at(0))
        assertNull(terminalEvent)
        val arrivalEvent = detector.observe(target, arrivalTwo, at(60))
        assertEquals(ShareEventType.ARRIVAL, arrivalEvent?.eventType)
        val laterMovement = LocationSample.create(at(70), 19.076, 72.8800, 8.0, 8.0)

        assertNull(queue.enqueueSample(scope, arrivalOne, at(80)))
        assertNull(queue.enqueueSample(scope, arrivalTwo, at(80)))
        assertTrue(queue.enqueueShareEvent(scope, SHARE_ID, arrivalEvent!!, at(80)))
        assertNull(queue.enqueueSample(scope, laterMovement, at(80)))

        coordinator.flush(scope, hasLocationPermission = true, consentEnabled = true)

        val eventIndex = transport.calls.indexOfFirst { it.startsWith("event:") }
        val laterMovementIndex = transport.calls.indexOf("fix:${laterMovement.sampleId}")
        assertTrue(eventIndex >= 0 && laterMovementIndex > eventIndex)
        assertTrue(transport.oldShareVisibleFixIds.none { it == laterMovement.sampleId })
    }

    @Test
    fun pendingTerminalEventStaysAtQueueHeadAcrossRestartUntilConfirmed() = runBlocking {
        val storage = InMemoryTrackingBlobStorage()
        val cipher = IdentityTrackingQueueCipher()
        val event = event(1)
        val laterFix = sample(2)
        val transport = PendingThenConfirmedTransport()
        val firstQueue = OfflineTrackingQueue(storage, cipher)
        assertTrue(firstQueue.enqueueShareEvent(scope, SHARE_ID, event, at(40)))
        assertNull(firstQueue.enqueueSample(scope, laterFix, at(40)))

        val pendingResult = TrackingSyncCoordinator(firstQueue, transport, now = { at(40) })
            .flush(scope, hasLocationPermission = true, consentEnabled = true)

        assertEquals(FlushStopReason.EVENT_PENDING, pendingResult.stopReason)
        assertEquals(1, firstQueue.pendingEventCount())
        assertEquals(1, firstQueue.pendingSampleCount())
        assertEquals(listOf("event:${event.clientEventId}"), transport.calls)

        // Recreate both the queue and coordinator to prove the event id and barrier are durable.
        val restoredQueue = OfflineTrackingQueue(storage, cipher)
        val confirmedResult = TrackingSyncCoordinator(restoredQueue, transport, now = { at(40) })
            .flush(scope, hasLocationPermission = true, consentEnabled = true)

        assertEquals(1, confirmedResult.eventsSent)
        assertEquals(1, confirmedResult.fixesSent)
        assertEquals(
            listOf("event:${event.clientEventId}", "event:${event.clientEventId}", "fix:${laterFix.sampleId}"),
            transport.calls,
        )
        assertEquals(0, restoredQueue.pendingEventCount())
        assertEquals(0, restoredQueue.pendingSampleCount())
    }

    @Test
    fun terminalServerStateResolvesPendingEventBeforeLaterFixes() = runBlocking {
        val queue = queue()
        val event = event(1)
        val laterFix = sample(2)
        val calls = mutableListOf<String>()
        queue.enqueueShareEvent(scope, SHARE_ID, event, at(40))
        queue.enqueueSample(scope, laterFix, at(40))
        val transport = object : TrackingTransport {
            override suspend fun postLocationsBatch(sharingEpoch: Long, samples: List<LocationSample>): LocationBatchResult {
                calls += samples.joinToString(prefix = "fix:", separator = ",") { it.sampleId }
                return LocationBatchResult(SERVER_TIME, samples.map { it.sampleId }.toSet(), emptySet(), emptyMap())
            }

            override suspend fun postShareEvent(shareId: String, event: PendingShareEvent): ShareEventResult {
                calls += "event:${event.clientEventId}"
                return ShareEventResult(
                    clientEventId = event.clientEventId,
                    status = ShareEventStatus.PENDING,
                    serverTime = SERVER_TIME,
                    endedAt = SERVER_TIME,
                    reason = "event_already_ended",
                )
            }

            override suspend fun postStopSharing(clientRequestId: String): StopSharingResult =
                StopSharingResult(true, 1, 2)
        }

        val result = TrackingSyncCoordinator(queue, transport, now = { at(40) })
            .flush(scope, hasLocationPermission = true, consentEnabled = true)

        assertEquals(1, result.eventsSent)
        assertEquals(1, result.fixesSent)
        assertEquals(listOf("event:${event.clientEventId}", "fix:${laterFix.sampleId}"), calls)
    }

    @Test
    fun endedShareResponseResolvesBarrierAndReleasesLaterFixes() = runBlocking {
        val queue = queue()
        val event = event(1)
        val laterFix = sample(2)
        val calls = mutableListOf<String>()
        queue.enqueueShareEvent(scope, SHARE_ID, event, at(40))
        queue.enqueueSample(scope, laterFix, at(40))
        val transport = object : RecordingTransport() {
            override suspend fun postShareEvent(shareId: String, event: PendingShareEvent): ShareEventResult {
                calls += "event:${event.clientEventId}"
                throw ShareAlreadyEndedException()
            }

            override suspend fun postLocationsBatch(sharingEpoch: Long, samples: List<LocationSample>): LocationBatchResult {
                calls += samples.joinToString(prefix = "fix:", separator = ",") { it.sampleId }
                return super.postLocationsBatch(sharingEpoch, samples)
            }
        }

        val result = TrackingSyncCoordinator(queue, transport, now = { at(40) })
            .flush(scope, hasLocationPermission = true, consentEnabled = true)

        assertEquals(1, result.eventsSent)
        assertEquals(1, result.fixesSent)
        assertEquals(listOf("event:${event.clientEventId}", "fix:${laterFix.sampleId}"), calls)
        assertEquals(0, queue.pendingEventCount())
        assertEquals(0, queue.pendingSampleCount())
    }

    @Test
    fun globalStopClearsPrivateQueueAndBlocksNewFixesUntilAcknowledged() = runBlocking {
        val queue = queue()
        val transport = RecordingTransport()
        val coordinator = TrackingSyncCoordinator(queue, transport, now = { at(40) })
        val requestId = UUID.randomUUID().toString()
        assertNull(queue.enqueueSample(scope, sample(0), at(40)))
        assertTrue(queue.enqueueShareEvent(scope, SHARE_ID, event(1), at(40)))

        assertTrue(coordinator.stopAllLocally(scope, requestId))
        assertEquals(0, queue.pendingSampleCount())
        assertEquals(0, queue.pendingEventCount())
        assertEquals(SampleRejection.STOP_PENDING, coordinator.enqueueFix(scope, sample(2)))

        coordinator.flush(scope, hasLocationPermission = false, consentEnabled = false)

        assertEquals(listOf("stop:$requestId"), transport.calls)
        assertTrue(queue.pendingStopIds().isEmpty())
    }

    @Test
    fun logoutDropsPrivateFixesAndRetainsStopOnlyForOriginalIdentity() = runBlocking {
        val queue = queue()
        val transport = RecordingTransport()
        val coordinator = TrackingSyncCoordinator(queue, transport, now = { at(40) })
        val requestId = UUID.randomUUID().toString()
        queue.enqueueSample(scope, sample(0), at(40))
        coordinator.stopAllLocally(scope, requestId)

        coordinator.flush(scope = null, hasLocationPermission = false, consentEnabled = false)
        assertEquals(0, queue.pendingSampleCount())
        assertEquals(listOf(requestId), queue.pendingStopIds())

        val changedIdentity = scope.copy(userId = "member-2", sessionId = "session-2")
        coordinator.flush(changedIdentity, hasLocationPermission = true, consentEnabled = true)
        assertTrue(transport.calls.isEmpty())

        coordinator.flush(scope, hasLocationPermission = true, consentEnabled = true)
        assertEquals(listOf("stop:$requestId"), transport.calls)
    }

    @Test
    fun queueIsEncryptedThroughInjectedCipherAndSurvivesRecreation() {
        val storage = InMemoryTrackingBlobStorage()
        val cipher = TestCipher()
        val first = OfflineTrackingQueue(storage, cipher)
        val fix = sample(0)
        assertNull(first.enqueueSample(scope, fix, at(40)))
        assertTrue(cipher.encryptCount > 0)

        val restored = OfflineTrackingQueue(storage, cipher)
        assertEquals(1, restored.pendingSampleCount())
        assertEquals(fix.sampleId, restored.nextFixBatchFor(scope.userId).single().second.sampleId)
    }

    @Test
    fun sharingEpochMismatchDiscardsQueuedPrivateDataAndRefreshesEpoch() = runBlocking {
        val queue = queue()
        queue.enqueueSample(scope, sample(0), at(40))
        var refreshedEpoch: Long? = null
        val transport = object : RecordingTransport() {
            override suspend fun postLocationsBatch(sharingEpoch: Long, samples: List<LocationSample>): LocationBatchResult {
                throw SharingEpochMismatchException(currentSharingEpoch = 5)
            }
        }
        val coordinator = TrackingSyncCoordinator(queue, transport, now = { at(40) }, onSharingEpoch = { _, epoch -> refreshedEpoch = epoch })

        val result = coordinator.flush(scope, hasLocationPermission = true, consentEnabled = true)

        assertEquals(FlushStopReason.SHARING_EPOCH_MISMATCH, result.stopReason)
        assertEquals(0, queue.pendingSampleCount())
        assertEquals(5L, refreshedEpoch)
    }

    private fun queue() = OfflineTrackingQueue(InMemoryTrackingBlobStorage(), IdentityTrackingQueueCipher())

    private fun event(second: Long) = PendingShareEvent(
        eventType = ShareEventType.ARRIVAL,
        observedAt = at(second).toString(),
        evidenceFixes = listOf(sample(second)),
    )

    private fun sample(second: Long) = LocationSample.create(at(second), 19.076, 72.8777 + second / 100000.0, 8.0, 0.0)
    private fun at(second: Long) = BASE.plusSeconds(second)

    private open class RecordingTransport : TrackingTransport {
        val calls = mutableListOf<String>()
        val batchEpochs = mutableListOf<Long>()

        open override suspend fun postLocationsBatch(sharingEpoch: Long, samples: List<LocationSample>): LocationBatchResult {
            batchEpochs += sharingEpoch
            calls += samples.joinToString(prefix = "fix:", separator = ",") { it.sampleId }
            return LocationBatchResult(SERVER_TIME, samples.map { it.sampleId }.toSet(), emptySet(), emptyMap())
        }

        override suspend fun postShareEvent(shareId: String, event: PendingShareEvent): ShareEventResult {
            calls += "event:${event.clientEventId}"
            return ShareEventResult(event.clientEventId, ShareEventStatus.CONFIRMED, SERVER_TIME)
        }

        override suspend fun postStopSharing(clientRequestId: String): StopSharingResult {
            calls += "stop:$clientRequestId"
            return StopSharingResult(true, 1, 2)
        }
    }

    private class TerminalShareTransport : RecordingTransport() {
        private var shareEndedAt: Instant? = null
        val oldShareVisibleFixIds = mutableListOf<String>()

        override suspend fun postLocationsBatch(sharingEpoch: Long, samples: List<LocationSample>): LocationBatchResult {
            val endedAt = shareEndedAt
            oldShareVisibleFixIds += samples.filter { sample ->
                val captured = sample.instantOrNull()
                endedAt == null || captured == null || !captured.isAfter(endedAt)
            }.map { it.sampleId }
            return super.postLocationsBatch(sharingEpoch, samples)
        }

        override suspend fun postShareEvent(shareId: String, event: PendingShareEvent): ShareEventResult {
            val result = super.postShareEvent(shareId, event)
            if (shareId == SHARE_ID && event.eventType == ShareEventType.ARRIVAL) {
                shareEndedAt = Instant.parse(event.observedAt)
            }
            return result
        }
    }

    private class PendingThenConfirmedTransport : TrackingTransport {
        val calls = mutableListOf<String>()
        private var eventAttempts = 0

        override suspend fun postLocationsBatch(sharingEpoch: Long, samples: List<LocationSample>): LocationBatchResult {
            calls += samples.joinToString(prefix = "fix:", separator = ",") { it.sampleId }
            return LocationBatchResult(SERVER_TIME, samples.map { it.sampleId }.toSet(), emptySet(), emptyMap())
        }

        override suspend fun postShareEvent(shareId: String, event: PendingShareEvent): ShareEventResult {
            calls += "event:${event.clientEventId}"
            eventAttempts++
            return ShareEventResult(
                clientEventId = event.clientEventId,
                status = if (eventAttempts == 1) ShareEventStatus.PENDING else ShareEventStatus.CONFIRMED,
                serverTime = SERVER_TIME,
            )
        }

        override suspend fun postStopSharing(clientRequestId: String): StopSharingResult =
            StopSharingResult(true, 1, 2)
    }

    private class TestCipher : TrackingQueueCipher {
        var encryptCount = 0
        override fun encrypt(plaintext: ByteArray): ByteArray { encryptCount++; return plaintext.copyOf() }
        override fun decrypt(ciphertext: ByteArray): ByteArray = ciphertext.copyOf()
    }

    private companion object {
        val BASE: Instant = Instant.parse("2026-11-26T10:00:00Z")
        const val SERVER_TIME = "2026-11-26T10:00:40Z"
        val scope = TrackingScope("member-1", "session-1", 1, 1, LocationPrecision.PRECISE)
        const val SHARE_ID = "e8b4c35c-7069-4b61-8e36-b81b87c3530e"
    }
}

