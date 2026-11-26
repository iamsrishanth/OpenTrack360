package me.srishanth.opentrack360.tracking

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.time.Duration
import java.time.Instant
import java.util.UUID
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** Small encrypted blob boundary. Android production storage uses Keystore and private prefs. */
interface TrackingBlobStorage {
    fun read(): ByteArray?
    fun write(value: ByteArray): Boolean
    fun clear()
}

interface TrackingQueueCipher {
    fun encrypt(plaintext: ByteArray): ByteArray
    fun decrypt(ciphertext: ByteArray): ByteArray
}

private sealed interface QueueRecord {
    val userId: String
    data class Fix(val scope: TrackingScope, val sample: LocationSample) : QueueRecord {
        override val userId: String get() = scope.userId
    }
    data class Event(val scope: TrackingScope, val shareId: String, val event: PendingShareEvent) : QueueRecord {
        override val userId: String get() = scope.userId
    }
    /** Contains no location; it is safe to retain across logout until the same user signs in again. */
    data class Stop(val scope: TrackingScope, val clientRequestId: String) : QueueRecord {
        override val userId: String get() = scope.userId
    }
}

data class SampleValidationPolicy(
    val maxAccuracyMeters: Double = 100.0,
    val maxAge: Duration = Duration.ofMinutes(5),
    val futureClockSkew: Duration = Duration.ofSeconds(30),
)

object LocationSampleValidator {
    fun validate(
        sample: LocationSample,
        now: Instant,
        policy: SampleValidationPolicy = SampleValidationPolicy(),
        previous: Instant? = null,
    ): SampleRejection? {
        val captured = sample.instantOrNull() ?: return SampleRejection.INVALID_TIMESTAMP
        if (sample.latitude !in -90.0..90.0 || sample.longitude !in -180.0..180.0) {
            return SampleRejection.INVALID_COORDINATE
        }
        if (sample.accuracyMeters.isNaN() || sample.accuracyMeters.isInfinite() ||
            sample.accuracyMeters < 0.0 || sample.accuracyMeters > policy.maxAccuracyMeters
        ) return SampleRejection.POOR_ACCURACY
        if (sample.speedMps != null &&
            (sample.speedMps.isNaN() || sample.speedMps.isInfinite() || sample.speedMps !in 0.0..150.0)
        ) return SampleRejection.INVALID_SPEED
        if (runCatching { UUID.fromString(sample.sampleId) }.isFailure) return SampleRejection.INVALID_TIMESTAMP
        if (captured.isAfter(now.plus(policy.futureClockSkew))) return SampleRejection.FUTURE_TIMESTAMP
        if (captured.isBefore(now.minus(policy.maxAge))) return SampleRejection.TOO_OLD
        if (previous != null && !captured.isAfter(previous)) return SampleRejection.OUT_OF_ORDER
        return null
    }
}

enum class FlushStopReason {
    EMPTY,
    NO_AUTHORITY,
    TRANSPORT_ERROR,
    SHARING_EPOCH_MISMATCH,
    SERVER_OMITTED_RESULT,
    EVENT_PENDING,
    COMPLETE,
}

data class FlushSummary(
    val fixesSent: Int = 0,
    val eventsSent: Int = 0,
    val stopCommandsSent: Int = 0,
    val staleFixesDropped: Int = 0,
    val stopReason: FlushStopReason,
)

/**
 * Bounded, encrypted, ordered offline queue. Each persisted private record carries its capture
 * identity, session, consent generation, sharing epoch, and location precision.
 */
class OfflineTrackingQueue(
    private val storage: TrackingBlobStorage,
    private val cipher: TrackingQueueCipher,
    private val maxSamples: Int = DEFAULT_MAX_SAMPLES,
    private val maxEvents: Int = DEFAULT_MAX_EVENTS,
    private val maxCiphertextBytes: Int = DEFAULT_MAX_CIPHERTEXT_BYTES,
    private val validationPolicy: SampleValidationPolicy = SampleValidationPolicy(),
) {
    private val lock = Any()
    private val records = mutableListOf<QueueRecord>()
    private val lastCapturedAt = linkedMapOf<TrackingScope, String>()
    private val lastUploadedAt = linkedMapOf<TrackingScope, String>()

    init {
        synchronized(lock) {
            runCatching {
                storage.read()?.let { decode(cipher.decrypt(it)) }
            }.onFailure {
                // Corrupt or undecryptable private data is discarded, never sent unverified.
                storage.clear()
                records.clear()
                lastCapturedAt.clear()
                lastUploadedAt.clear()
            }
        }
    }

    fun enqueueSample(scope: TrackingScope, sample: LocationSample, now: Instant): SampleRejection? = synchronized(lock) {
        if (records.any { it is QueueRecord.Stop && it.userId == scope.userId }) return SampleRejection.STOP_PENDING
        if (records.any { it is QueueRecord.Fix && it.sample.sampleId == sample.sampleId }) return SampleRejection.INVALID_TIMESTAMP
        val previous = lastCapturedAt[scope]?.let { runCatching { Instant.parse(it) }.getOrNull() }
        val rejection = LocationSampleValidator.validate(sample, now, validationPolicy, previous)
        if (rejection != null) return rejection
        if (records.count { it is QueueRecord.Fix } >= maxSamples) return SampleRejection.QUEUE_FULL
        val fix = QueueRecord.Fix(scope, sample)
        records += fix
        lastCapturedAt[scope] = sample.capturedAt
        trimTimestampHistory(lastCapturedAt)
        if (!persistOrRollback { records.remove(fix); if (previous == null) lastCapturedAt.remove(scope) else lastCapturedAt[scope] = previous.toString() }) {
            return SampleRejection.QUEUE_FULL
        }
        null
    }

    fun enqueueShareEvent(scope: TrackingScope, shareId: String, event: PendingShareEvent, now: Instant): Boolean = synchronized(lock) {
        if (records.any { it is QueueRecord.Stop && it.userId == scope.userId }) return false
        if (runCatching { UUID.fromString(shareId) }.isFailure) return false
        if (records.any {
                it is QueueRecord.Event && it.userId == scope.userId && it.shareId == shareId &&
                    it.event.eventType == event.eventType
            }
        ) return false
        val observed = runCatching { Instant.parse(event.observedAt) }.getOrNull() ?: return false
        if (observed.isAfter(now.plus(validationPolicy.futureClockSkew))) return false
        if (records.count { it is QueueRecord.Event } >= maxEvents) return false
        var previous: Instant? = null
        val eventEvidencePolicy = if (event.eventType == ShareEventType.DRIVE_END) {
            validationPolicy.copy(maxAge = MAX_DRIVE_END_EVIDENCE_AGE)
        } else validationPolicy
        for (sample in event.evidenceFixes) {
            val rejection = LocationSampleValidator.validate(sample, now, eventEvidencePolicy, previous)
            if (rejection != null) return false
            previous = sample.instantOrNull()
        }
        if (previous?.isAfter(observed) == true) return false
        val record = QueueRecord.Event(scope, shareId, event)
        records += record
        if (!persistOrRollback { records.remove(record) }) return false
        true
    }

    /** Local call is synchronous so collection can stop before any network work starts. */
    fun requestGlobalStop(scope: TrackingScope, clientRequestId: String = UUID.randomUUID().toString()): Boolean = synchronized(lock) {
        if (runCatching { UUID.fromString(clientRequestId) }.isFailure) return false
        if (records.count { it is QueueRecord.Stop } >= MAX_PENDING_STOP_COMMANDS &&
            records.none { it is QueueRecord.Stop && it.userId == scope.userId }
        ) return false
        val old = records.toList()
        records.removeAll { it !is QueueRecord.Stop }
        records.removeAll { it is QueueRecord.Stop && it.userId == scope.userId }
        records += QueueRecord.Stop(scope, clientRequestId)
        lastCapturedAt.keys.removeAll { it.userId == scope.userId }
        if (!persist()) {
            records.clear()
            records += old.filterIsInstance<QueueRecord.Stop>().filter { it.userId != scope.userId }
            records += QueueRecord.Stop(scope, clientRequestId)
            // Never let the old persisted ciphertext resurrect private fixes after a failed stop write.
            storage.clear()
            return persist()
        }
        true
    }

    /** Logout, a changed consent generation, or a permission change must call this immediately. */
    fun invalidatePrivateRecords() = synchronized(lock) {
        records.removeAll { it !is QueueRecord.Stop }
        lastCapturedAt.clear()
        if (!persist()) {
            storage.clear()
            persist()
        }
    }

    fun invalidatePrivateRecordsExcept(scope: TrackingScope) = synchronized(lock) {
        records.removeAll { record ->
            when (record) {
                is QueueRecord.Fix -> !record.scope.sameCaptureScope(scope)
                is QueueRecord.Event -> !record.scope.sameCaptureScope(scope)
                is QueueRecord.Stop -> false
            }
        }
        lastCapturedAt.keys.removeAll { !it.sameCaptureScope(scope) }
        if (!persist()) {
            storage.clear()
            persist()
        }
    }

    fun pendingSampleCount(): Int = synchronized(lock) { records.count { it is QueueRecord.Fix } }
    fun pendingEventCount(): Int = synchronized(lock) { records.count { it is QueueRecord.Event } }
    fun pendingStopIds(): List<String> = synchronized(lock) { records.filterIsInstance<QueueRecord.Stop>().map { it.clientRequestId } }
    fun hasPendingStop(userId: String): Boolean = synchronized(lock) { records.any { it is QueueRecord.Stop && it.userId == userId } }

    internal fun hasPendingEvent(userId: String, shareId: String, eventType: ShareEventType): Boolean = synchronized(lock) {
        records.any {
            it is QueueRecord.Event && it.userId == userId && it.shareId == shareId && it.event.eventType == eventType
        }
    }

    internal fun recordStopAcknowledged(userId: String, clientRequestId: String, updatedEpoch: Long): Boolean = synchronized(lock) {
        val removed = records.removeAll { it is QueueRecord.Stop && it.userId == userId && it.clientRequestId == clientRequestId }
        if (removed) {
            lastCapturedAt.keys.removeAll { it.userId == userId && it.sharingEpoch < updatedEpoch }
            persist()
        } else true
    }

    internal fun firstRecordFor(userId: String): Any? = synchronized(lock) {
        records.firstOrNull { it.userId == userId }?.toPublicRecord()
    }

    internal fun stopRecordFor(userId: String): Pair<TrackingScope, String>? = synchronized(lock) {
        records.firstOrNull { it is QueueRecord.Stop && it.userId == userId }
            ?.let { (it as QueueRecord.Stop).let { stop -> stop.scope to stop.clientRequestId } }
    }

    internal fun firstEventFor(userId: String): Triple<TrackingScope, String, PendingShareEvent>? = synchronized(lock) {
        records.firstOrNull { it is QueueRecord.Event && it.userId == userId }
            ?.let { (it as QueueRecord.Event).let { event -> Triple(event.scope, event.shareId, event.event) } }
    }

    internal fun nextFixBatchFor(userId: String, limit: Int = MAX_BATCH_SAMPLES): List<Pair<TrackingScope, LocationSample>> = synchronized(lock) {
        val first = records.indexOfFirst { it.userId == userId }
        if (first < 0 || records[first] !is QueueRecord.Fix) return emptyList()
        val out = mutableListOf<Pair<TrackingScope, LocationSample>>()
        for (index in first until records.size) {
            val record = records[index]
            if (record.userId != userId) continue
            if (record !is QueueRecord.Fix || out.size >= limit) break
            out += record.scope to record.sample
        }
        out
    }

    internal fun removeFinalizedFixes(userId: String, ids: Set<String>, acceptedOrDuplicate: Set<String>) = synchronized(lock) {
        val fixes = records.filterIsInstance<QueueRecord.Fix>().filter { it.userId == userId && it.sample.sampleId in ids }
        records.removeAll { it is QueueRecord.Fix && it.userId == userId && it.sample.sampleId in ids }
        val latestSent = fixes.filter { it.sample.sampleId in acceptedOrDuplicate }
            .mapNotNull { fix -> fix.sample.instantOrNull()?.let { fix.scope to it } }
            .groupBy({ it.first }, { it.second })
        latestSent.forEach { (scope, dates) -> dates.maxOrNull()?.let { lastUploadedAt[scope] = it.toString() } }
        trimTimestampHistory(lastUploadedAt)
        persist()
    }

    internal fun removeEvent(userId: String, clientEventId: String) = synchronized(lock) {
        val removed = records.removeAll { it is QueueRecord.Event && it.userId == userId && it.event.clientEventId == clientEventId }
        if (removed) persist()
    }

    internal fun dropStaleFixes(userId: String, now: Instant): Int = synchronized(lock) {
        val stale = records.filterIsInstance<QueueRecord.Fix>().filter { fix ->
            if (fix.userId != userId) false else {
                val at = fix.sample.instantOrNull()
                at == null || at.isBefore(now.minus(validationPolicy.maxAge)) || at.isAfter(now.plus(validationPolicy.futureClockSkew))
            }
        }
        if (stale.isNotEmpty()) {
            records.removeAll(stale.toSet())
            persist()
        }
        stale.size
    }

    private fun QueueRecord.toPublicRecord(): Any = when (this) {
        is QueueRecord.Fix -> this
        is QueueRecord.Event -> this
        is QueueRecord.Stop -> this
    }

    private fun trimTimestampHistory(map: LinkedHashMap<TrackingScope, String>) {
        while (map.size > 20) map.remove(map.keys.first())
    }

    private inline fun persistOrRollback(rollback: () -> Unit): Boolean {
        if (persist()) return true
        rollback()
        return false
    }

    private fun persist(): Boolean {
        return runCatching {
            val encrypted = cipher.encrypt(encode())
            encrypted.size <= maxCiphertextBytes && storage.write(encrypted)
        }.getOrDefault(false)
    }

    private fun encode(): ByteArray {
        val bytes = ByteArrayOutputStream()
        DataOutputStream(bytes).use { output ->
            output.writeInt(FORMAT_VERSION)
            output.writeInt(records.size)
            records.forEach { record ->
                when (record) {
                    is QueueRecord.Fix -> {
                        output.writeByte(RECORD_FIX); output.writeScope(record.scope); output.writeSample(record.sample)
                    }
                    is QueueRecord.Event -> {
                        output.writeByte(RECORD_EVENT); output.writeScope(record.scope); output.writeUTF(record.shareId)
                        output.writeUTF(record.event.clientEventId); output.writeUTF(record.event.eventType.wireValue)
                        output.writeUTF(record.event.observedAt); output.writeInt(record.event.evidenceFixes.size)
                        record.event.evidenceFixes.forEach { sample -> output.writeSample(sample) }
                    }
                    is QueueRecord.Stop -> {
                        output.writeByte(RECORD_STOP); output.writeScope(record.scope); output.writeUTF(record.clientRequestId)
                    }
                }
            }
            output.writeInt(lastCapturedAt.size)
            lastCapturedAt.forEach { (scope, capturedAt) -> output.writeScope(scope); output.writeUTF(capturedAt) }
            output.writeInt(lastUploadedAt.size)
            lastUploadedAt.forEach { (scope, capturedAt) -> output.writeScope(scope); output.writeUTF(capturedAt) }
        }
        return bytes.toByteArray()
    }

    private fun decode(bytes: ByteArray) {
        DataInputStream(ByteArrayInputStream(bytes)).use { input ->
            require(input.readInt() == FORMAT_VERSION)
            val recordCount = input.readInt().also { require(it in 0..MAX_DECODE_RECORDS) }
            repeat(recordCount) {
                when (input.readByte().toInt()) {
                    RECORD_FIX -> records += QueueRecord.Fix(input.readScope(), input.readSample())
                    RECORD_EVENT -> {
                        val scope = input.readScope(); val shareId = input.readUTF(); val eventId = input.readUTF()
                        val eventType = ShareEventType.entries.first { it.wireValue == input.readUTF() }
                        val observedAt = input.readUTF(); val size = input.readInt().also { require(it in 1..PendingShareEvent.MAX_EVENT_EVIDENCE_FIXES) }
                        val samples = List(size) { input.readSample() }
                        records += QueueRecord.Event(scope, shareId, PendingShareEvent(eventId, eventType, observedAt, samples))
                    }
                    RECORD_STOP -> records += QueueRecord.Stop(input.readScope(), input.readUTF())
                    else -> error("Unknown queue record")
                }
            }
            val capturedCount = input.readInt().also { require(it in 0..20) }
            repeat(capturedCount) { lastCapturedAt[input.readScope()] = input.readUTF() }
            val uploadedCount = input.readInt().also { require(it in 0..20) }
            repeat(uploadedCount) { lastUploadedAt[input.readScope()] = input.readUTF() }
            require(input.available() == 0)
            require(records.count { it is QueueRecord.Fix } <= maxSamples)
            require(records.count { it is QueueRecord.Event } <= maxEvents)
        }
    }

    private fun DataOutputStream.writeScope(scope: TrackingScope) {
        writeUTF(scope.userId); writeUTF(scope.sessionId); writeLong(scope.consentGeneration); writeLong(scope.sharingEpoch)
        writeUTF(scope.precision.name)
    }

    private fun DataInputStream.readScope() = TrackingScope(
        userId = readUTF(), sessionId = readUTF(), consentGeneration = readLong(), sharingEpoch = readLong(),
        precision = LocationPrecision.valueOf(readUTF()),
    )

    private fun DataOutputStream.writeSample(sample: LocationSample) {
        writeUTF(sample.sampleId); writeUTF(sample.capturedAt); writeDouble(sample.latitude); writeDouble(sample.longitude)
        writeDouble(sample.accuracyMeters); writeBoolean(sample.speedMps != null); sample.speedMps?.let { writeDouble(it) }
    }

    private fun DataInputStream.readSample() = LocationSample(
        sampleId = readUTF(), capturedAt = readUTF(), latitude = readDouble(), longitude = readDouble(),
        accuracyMeters = readDouble(), speedMps = if (readBoolean()) readDouble() else null,
    )

    companion object {
        const val MAX_BATCH_SAMPLES = 100
        const val DEFAULT_MAX_SAMPLES = 500
        const val DEFAULT_MAX_EVENTS = 50
        const val DEFAULT_MAX_CIPHERTEXT_BYTES = 1_000_000
        // A prior moving pair can end up to 90 seconds before a five-minute stationary window,
        // with the earlier member of that pair up to another 90 seconds before it.
        val MAX_DRIVE_END_EVIDENCE_AGE: Duration = Duration.ofMinutes(8)
        private const val MAX_PENDING_STOP_COMMANDS = 20
        private const val FORMAT_VERSION = 1
        private const val MAX_DECODE_RECORDS = 600
        private const val RECORD_FIX = 1
        private const val RECORD_EVENT = 2
        private const val RECORD_STOP = 3
    }
}

/** Serially flushes one user's ordered queue. No credential or token is stored here. */
class TrackingSyncCoordinator(
    private val queue: OfflineTrackingQueue,
    private val transport: TrackingTransport,
    private val now: () -> Instant = Instant::now,
    private val onSharingEpoch: (String, Long) -> Unit = { _, _ -> },
) {
    private val flushMutex = Mutex()

    fun enqueueFix(scope: TrackingScope, sample: LocationSample): SampleRejection? = queue.enqueueSample(scope, sample, now())

    fun enqueueTerminalEvent(scope: TrackingScope, shareId: String, event: PendingShareEvent): Boolean =
        queue.enqueueShareEvent(scope, shareId, event, now())

    fun hasPendingTerminalEvent(scope: TrackingScope, shareId: String, eventType: ShareEventType): Boolean =
        queue.hasPendingEvent(scope.userId, shareId, eventType)

    fun stopAllLocally(scope: TrackingScope, clientRequestId: String = UUID.randomUUID().toString()): Boolean =
        queue.requestGlobalStop(scope, clientRequestId)

    fun onLogoutOrConsentChange() = queue.invalidatePrivateRecords()
    fun hasPendingGlobalStop(userId: String): Boolean = queue.hasPendingStop(userId)

    suspend fun flush(
        scope: TrackingScope?,
        hasLocationPermission: Boolean,
        consentEnabled: Boolean,
        privateAuthorityStillValid: () -> Boolean = { hasLocationPermission && consentEnabled },
        stopAuthorityStillValid: () -> Boolean = { scope != null },
    ): FlushSummary = flushMutex.withLock {
        flushLocked(scope, hasLocationPermission, consentEnabled, privateAuthorityStillValid, stopAuthorityStillValid)
    }

    private suspend fun flushLocked(
        scope: TrackingScope?,
        hasLocationPermission: Boolean,
        consentEnabled: Boolean,
        privateAuthorityStillValid: () -> Boolean,
        stopAuthorityStillValid: () -> Boolean,
    ): FlushSummary {
        if (scope == null) {
            queue.invalidatePrivateRecords()
            return FlushSummary(staleFixesDropped = 0, stopReason = FlushStopReason.NO_AUTHORITY)
        }
        if (!hasLocationPermission || !consentEnabled) queue.invalidatePrivateRecords()
        else queue.invalidatePrivateRecordsExcept(scope)
        var sentFixes = 0
        var sentEvents = 0
        var sentStops = 0
        var staleDropped = queue.dropStaleFixes(scope.userId, now())

        while (true) {
            val stop = queue.stopRecordFor(scope.userId)
            val first = queue.firstRecordFor(scope.userId)
            if (first == null) {
                return FlushSummary(sentFixes, sentEvents, sentStops, staleDropped, if (sentFixes + sentEvents + sentStops > 0) FlushStopReason.COMPLETE else FlushStopReason.EMPTY)
            }
            // A stop command is sent before any other record belonging to this identity.
            if (stop != null) {
                if (!stopAuthorityStillValid()) {
                    return FlushSummary(sentFixes, sentEvents, sentStops, staleDropped, FlushStopReason.NO_AUTHORITY)
                }
                val (stopScope, requestId) = stop
                try {
                    val response = transport.postStopSharing(requestId)
                    queue.recordStopAcknowledged(scope.userId, requestId, response.sharingEpoch)
                    onSharingEpoch(stopScope.userId, response.sharingEpoch)
                    sentStops++
                } catch (_: Exception) {
                    return FlushSummary(sentFixes, sentEvents, sentStops, staleDropped, FlushStopReason.TRANSPORT_ERROR)
                }
                continue
            }

            when (first) {
                is QueueRecord.Event -> {
                    if (!privateAuthorityStillValid()) {
                        queue.invalidatePrivateRecords()
                        return FlushSummary(sentFixes, sentEvents, sentStops, staleDropped, FlushStopReason.NO_AUTHORITY)
                    }
                    try {
                        val result = transport.postShareEvent(first.shareId, first.event)
                        if (result.clientEventId != first.event.clientEventId) {
                            return FlushSummary(sentFixes, sentEvents, sentStops, staleDropped, FlushStopReason.SERVER_OMITTED_RESULT)
                        }
                        val serverShowsTerminal = result.endedAt != null || result.reason == "event_already_ended"
                        if (result.status == ShareEventStatus.PENDING && !serverShowsTerminal) {
                            return FlushSummary(sentFixes, sentEvents, sentStops, staleDropped, FlushStopReason.EVENT_PENDING)
                        }
                        queue.removeEvent(scope.userId, first.event.clientEventId)
                        sentEvents++
                    } catch (_: ShareAlreadyEndedException) {
                        queue.removeEvent(scope.userId, first.event.clientEventId)
                        sentEvents++
                    } catch (_: Exception) {
                        return FlushSummary(sentFixes, sentEvents, sentStops, staleDropped, FlushStopReason.TRANSPORT_ERROR)
                    }
                }
                is QueueRecord.Fix -> {
                    if (!privateAuthorityStillValid()) {
                        queue.invalidatePrivateRecords()
                        return FlushSummary(sentFixes, sentEvents, sentStops, staleDropped, FlushStopReason.NO_AUTHORITY)
                    }
                    val batch = queue.nextFixBatchFor(scope.userId)
                    if (batch.isEmpty()) return FlushSummary(sentFixes, sentEvents, sentStops, staleDropped, FlushStopReason.EMPTY)
                    val ids = batch.map { it.second.sampleId }.toSet()
                    try {
                        val response = transport.postLocationsBatch(scope.sharingEpoch, batch.map { it.second })
                        val finalized = response.accepted + response.duplicates + response.rejected.keys
                        if (finalized.none { it in ids }) {
                            return FlushSummary(sentFixes, sentEvents, sentStops, staleDropped, FlushStopReason.SERVER_OMITTED_RESULT)
                        }
                        queue.removeFinalizedFixes(scope.userId, finalized.intersect(ids), response.accepted + response.duplicates)
                        sentFixes += finalized.intersect(ids).size
                    } catch (mismatch: SharingEpochMismatchException) {
                        queue.invalidatePrivateRecords()
                        mismatch.currentSharingEpoch?.let { epoch -> onSharingEpoch(scope.userId, epoch) }
                        return FlushSummary(sentFixes, sentEvents, sentStops, staleDropped, FlushStopReason.SHARING_EPOCH_MISMATCH)
                    } catch (_: Exception) {
                        return FlushSummary(sentFixes, sentEvents, sentStops, staleDropped, FlushStopReason.TRANSPORT_ERROR)
                    }
                }
                else -> return FlushSummary(sentFixes, sentEvents, sentStops, staleDropped, FlushStopReason.EMPTY)
            }
        }
    }
}

/** Deterministic in-memory boundaries for local tests. */
class InMemoryTrackingBlobStorage : TrackingBlobStorage {
    private var value: ByteArray? = null
    override fun read(): ByteArray? = synchronized(this) { value?.copyOf() }
    override fun write(value: ByteArray): Boolean = synchronized(this) { this.value = value.copyOf(); true }
    override fun clear() = synchronized(this) { value = null }
}

class IdentityTrackingQueueCipher : TrackingQueueCipher {
    override fun encrypt(plaintext: ByteArray): ByteArray = plaintext.copyOf()
    override fun decrypt(ciphertext: ByteArray): ByteArray = ciphertext.copyOf()
}

