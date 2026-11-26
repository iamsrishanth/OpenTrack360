package me.srishanth.opentrack360.tracking

import android.content.Context
import java.time.Instant
import java.util.UUID

/** Location precision available to the app at the time a fix is captured. */
enum class LocationPrecision { PRECISE, APPROXIMATE }

data class PermissionSnapshot(
    val hasLocation: Boolean,
    val precision: LocationPrecision?,
    val hasBackgroundLocation: Boolean,
    val hasNotifications: Boolean,
    val batterySaverOn: Boolean,
) {
    val canCollect: Boolean get() = hasLocation && precision != null
}

/** All private data captured while sharing is enabled is tied to this exact scope. */
data class TrackingScope(
    val userId: String,
    val sessionId: String,
    val consentGeneration: Long,
    val sharingEpoch: Long,
    val precision: LocationPrecision,
) {
    init {
        require(userId.isNotBlank())
        require(sessionId.isNotBlank())
        require(consentGeneration >= 0)
        require(sharingEpoch >= 0)
    }

    fun sameCaptureScope(other: TrackingScope): Boolean =
        userId == other.userId &&
            sessionId == other.sessionId &&
            consentGeneration == other.consentGeneration &&
            sharingEpoch == other.sharingEpoch &&
            precision == other.precision
}

/** Wire shape for POST /locations/batch and event evidenceFixes. */
data class LocationSample(
    val sampleId: String,
    val capturedAt: String,
    val latitude: Double,
    val longitude: Double,
    val accuracyMeters: Double,
    val speedMps: Double?,
) {
    fun instantOrNull(): Instant? = runCatching { Instant.parse(capturedAt) }.getOrNull()

    companion object {
        fun create(
            capturedAt: Instant,
            latitude: Double,
            longitude: Double,
            accuracyMeters: Double,
            speedMps: Double?,
        ): LocationSample = LocationSample(
            sampleId = UUID.randomUUID().toString(),
            capturedAt = capturedAt.toString(),
            latitude = latitude,
            longitude = longitude,
            accuracyMeters = accuracyMeters,
            speedMps = speedMps,
        )
    }
}

enum class SampleRejection {
    INVALID_TIMESTAMP,
    FUTURE_TIMESTAMP,
    TOO_OLD,
    OUT_OF_ORDER,
    POOR_ACCURACY,
    INVALID_COORDINATE,
    INVALID_SPEED,
    WRONG_SCOPE,
    QUEUE_FULL,
    STOP_PENDING,
}

enum class ShareEventType(val wireValue: String) { ARRIVAL("arrival"), DRIVE_END("drive_end") }

data class PendingShareEvent(
    val clientEventId: String = UUID.randomUUID().toString(),
    val eventType: ShareEventType,
    val observedAt: String,
    val evidenceFixes: List<LocationSample>,
) {
    init {
        require(evidenceFixes.isNotEmpty() && evidenceFixes.size <= MAX_EVENT_EVIDENCE_FIXES)
        require(runCatching { Instant.parse(observedAt) }.isSuccess)
        require(runCatching { UUID.fromString(clientEventId) }.isSuccess)
    }

    companion object { const val MAX_EVENT_EVIDENCE_FIXES = 10 }
}

data class LocationBatchResult(
    val serverTime: String,
    val accepted: Set<String>,
    val duplicates: Set<String>,
    /** The contract calls this field `code` (never `reason`). */
    val rejected: Map<String, String>,
)

enum class ShareEventStatus { CONFIRMED, PENDING, DUPLICATE }

data class ShareEventResult(
    val clientEventId: String,
    val status: ShareEventStatus,
    val serverTime: String,
)

data class StopSharingResult(
    val stopped: Boolean,
    val endedShareCount: Int,
    val sharingEpoch: Long,
)

/** Injected transport adapter; implementations must use the authenticated Vercel API only. */
interface TrackingTransport {
    suspend fun postLocationsBatch(sharingEpoch: Long, samples: List<LocationSample>): LocationBatchResult
    suspend fun postShareEvent(shareId: String, event: PendingShareEvent): ShareEventResult
    suspend fun postStopSharing(clientRequestId: String): StopSharingResult
}

/** Adapters should throw this for HTTP 409 SHARING_EPOCH_MISMATCH so stale queued data is erased. */
class SharingEpochMismatchException(val currentSharingEpoch: Long? = null) : Exception("Sharing epoch changed")

/** The app/API layer installs the authenticated implementation before the service can start. */
interface TrackingRuntimeProvider {
    fun create(context: Context): TrackingRuntime?
}

data class TrackingRuntime(
    val queue: OfflineTrackingQueue,
    val transport: TrackingTransport,
    val engine: TrackingEngine = TrackingEngine(),
    val scopeProvider: () -> TrackingScope?,
    val sharingConsentEnabled: () -> Boolean,
    val sharingEnabled: () -> Boolean,
    val activeDriveEndShareIds: () -> List<String> = { emptyList() },
    val onSharingEpoch: (String, Long) -> Unit = { _, _ -> },
    val onTrackingStatus: (TrackingStatus) -> Unit = {},
    val onDriveUpdate: (DriveUpdate) -> Unit = {},
)

enum class TrackingStatus {
    STARTING,
    TRACKING_PRECISE,
    TRACKING_APPROXIMATE,
    TRACKING_APPROXIMATE_BACKGROUND_LIMITED,
    BATTERY_SAVER_LIMITED,
    BACKGROUND_ACCESS_LIMITED,
    LOCATION_UNAVAILABLE,
    PERMISSION_LOST,
    CONSENT_REVOKED,
    STOPPING,
    STOPPED,
}

