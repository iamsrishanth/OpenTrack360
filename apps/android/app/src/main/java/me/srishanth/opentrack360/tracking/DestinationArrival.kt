package me.srishanth.opentrack360.tracking

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.time.Duration
import java.time.Instant
import java.util.UUID
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

/** Private destination coordinates retained locally so an active link can end while offline. */
data class ArrivalShareTarget(
    val shareId: String,
    val latitude: Double,
    val longitude: Double,
    val radiusMeters: Int = DEFAULT_RADIUS_METERS,
) {
    init {
        require(runCatching { UUID.fromString(shareId) }.isSuccess)
        require(latitude.isFinite() && latitude in -90.0..90.0)
        require(longitude.isFinite() && longitude in -180.0..180.0)
        require(radiusMeters in MIN_RADIUS_METERS..MAX_RADIUS_METERS)
    }

    companion object {
        const val DEFAULT_RADIUS_METERS = 150
        const val MIN_RADIUS_METERS = 50
        const val MAX_RADIUS_METERS = 1_000
    }
}

/**
 * Detects destination arrival from two accurate, fresh fixes inside the private target radius.
 * The first fix stays the candidate until another qualifying fix is at least sixty seconds later.
 */
class DestinationArrivalDetector(
    private val minSeparation: Duration = MIN_FIX_SEPARATION,
    private val maxFixAge: Duration = MAX_FIX_AGE,
    private val maxAccuracyMeters: Double = MAX_ACCURACY_METERS,
) {
    private val candidates = mutableMapOf<String, LocationSample>()

    fun observe(target: ArrivalShareTarget, sample: LocationSample, now: Instant): PendingShareEvent? {
        val capturedAt = sample.instantOrNull() ?: return null
        if (!isFreshAccurate(sample, now) || !insideTarget(target, sample.latitude, sample.longitude)) {
            candidates.remove(target.shareId)
            return null
        }

        val first = candidates[target.shareId]
        if (first == null || !isFreshAccurate(first, now) ||
            !insideTarget(target, first.latitude, first.longitude)
        ) {
            candidates[target.shareId] = sample
            return null
        }

        val firstAt = first.instantOrNull() ?: run {
            candidates[target.shareId] = sample
            return null
        }
        if (!capturedAt.isAfter(firstAt)) return null
        if (Duration.between(firstAt, capturedAt) < minSeparation) return null

        candidates.remove(target.shareId)
        return PendingShareEvent(
            eventType = ShareEventType.ARRIVAL,
            observedAt = capturedAt.toString(),
            evidenceFixes = listOf(first, sample),
        )
    }

    fun reset() = candidates.clear()

    private fun isFreshAccurate(sample: LocationSample, now: Instant): Boolean =
        LocationSampleValidator.validate(
            sample = sample,
            now = now,
            policy = SampleValidationPolicy(
                maxAccuracyMeters = maxAccuracyMeters,
                maxAge = maxFixAge,
            ),
        ) == null

    private fun insideTarget(target: ArrivalShareTarget, latitude: Double, longitude: Double): Boolean =
        distanceMeters(target.latitude, target.longitude, latitude, longitude) <= target.radiusMeters

    private fun distanceMeters(latitudeA: Double, longitudeA: Double, latitudeB: Double, longitudeB: Double): Double {
        val earthRadiusMeters = 6_371_000.0
        val latA = Math.toRadians(latitudeA)
        val latB = Math.toRadians(latitudeB)
        val deltaLat = latB - latA
        val deltaLon = Math.toRadians(longitudeB - longitudeA)
        val value = sin(deltaLat / 2) * sin(deltaLat / 2) +
            cos(latA) * cos(latB) * sin(deltaLon / 2) * sin(deltaLon / 2)
        return earthRadiusMeters * 2 * atan2(sqrt(value), sqrt(1 - value))
    }

    companion object {
        val MIN_FIX_SEPARATION: Duration = Duration.ofSeconds(60)
        val MAX_FIX_AGE: Duration = Duration.ofMinutes(5)
        const val MAX_ACCURACY_METERS = 25.0
    }
}

/** Encrypted owner-scoped cache for sender-only destination metadata returned by GET /shares. */
class ArrivalShareTargetStore(
    private val storage: TrackingBlobStorage,
    private val cipher: TrackingQueueCipher,
    private val maxTargets: Int = DEFAULT_MAX_TARGETS,
) {
    private data class Key(val ownerId: String, val sessionId: String, val shareId: String)

    private val lock = Any()
    private val targets = linkedMapOf<Key, ArrivalShareTarget>()

    init {
        synchronized(lock) {
            runCatching { storage.read()?.let { decode(cipher.decrypt(it)) } }
                .onFailure { storage.clear(); targets.clear() }
        }
    }

    fun put(ownerId: String, sessionId: String, target: ArrivalShareTarget): Boolean = synchronized(lock) {
        if (ownerId.isBlank() || sessionId.isBlank()) return false
        val key = Key(ownerId, sessionId, target.shareId)
        if (key !in targets && targets.size >= maxTargets) return false
        val previous = targets.put(key, target)
        if (persist()) return true
        if (previous == null) targets.remove(key) else targets[key] = previous
        false
    }

    fun activeFor(ownerId: String, sessionId: String): List<ArrivalShareTarget> = synchronized(lock) {
        targets.entries.filter { it.key.ownerId == ownerId && it.key.sessionId == sessionId }.map { it.value }
    }

    fun remove(ownerId: String, sessionId: String, shareId: String): Boolean = synchronized(lock) {
        val removed = targets.remove(Key(ownerId, sessionId, shareId)) ?: return false
        if (persist()) return true
        targets[Key(ownerId, sessionId, shareId)] = removed
        false
    }

    fun removeForShare(shareId: String): Boolean = synchronized(lock) {
        val removed = targets.filterKeys { it.shareId == shareId }
        if (removed.isEmpty()) return true
        targets.keys.removeAll { it.shareId == shareId }
        if (persist()) return true
        targets.putAll(removed)
        false
    }

    fun clearFor(ownerId: String): Boolean = synchronized(lock) {
        val old = targets.toMap()
        targets.keys.removeAll { it.ownerId == ownerId }
        if (persist()) return true
        targets.clear()
        targets.putAll(old)
        false
    }

    fun clearExcept(ownerId: String, sessionId: String): Boolean = synchronized(lock) {
        val old = targets.toMap()
        targets.keys.removeAll { it.ownerId != ownerId || it.sessionId != sessionId }
        if (persist()) return true
        targets.clear()
        targets.putAll(old)
        false
    }

    fun clearAll(): Boolean = synchronized(lock) {
        if (targets.isEmpty()) return true
        val old = targets.toMap()
        targets.clear()
        if (persist()) return true
        targets.putAll(old)
        false
    }

    private fun persist(): Boolean = runCatching {
        val bytes = encode()
        val encrypted = cipher.encrypt(bytes)
        encrypted.size <= MAX_CIPHERTEXT_BYTES && storage.write(encrypted)
    }.getOrDefault(false)

    private fun encode(): ByteArray {
        val bytes = ByteArrayOutputStream()
        DataOutputStream(bytes).use { output ->
            output.writeInt(FORMAT_VERSION)
            output.writeInt(targets.size)
            targets.forEach { (key, target) ->
                output.writeUTF(key.ownerId)
                output.writeUTF(key.sessionId)
                output.writeUTF(target.shareId)
                output.writeDouble(target.latitude)
                output.writeDouble(target.longitude)
                output.writeInt(target.radiusMeters)
            }
        }
        return bytes.toByteArray()
    }

    private fun decode(plaintext: ByteArray) {
        DataInputStream(ByteArrayInputStream(plaintext)).use { input ->
            require(input.readInt() == FORMAT_VERSION)
            val count = input.readInt()
            require(count in 0..maxTargets)
            repeat(count) {
                val ownerId = input.readUTF()
                val sessionId = input.readUTF()
                val target = ArrivalShareTarget(
                    shareId = input.readUTF(),
                    latitude = input.readDouble(),
                    longitude = input.readDouble(),
                    radiusMeters = input.readInt(),
                )
                require(ownerId.isNotBlank() && sessionId.isNotBlank())
                targets[Key(ownerId, sessionId, target.shareId)] = target
            }
            require(input.available() == 0)
        }
    }

    companion object {
        const val DEFAULT_MAX_TARGETS = 100
        private const val FORMAT_VERSION = 1
        private const val MAX_CIPHERTEXT_BYTES = 32 * 1024
    }
}
