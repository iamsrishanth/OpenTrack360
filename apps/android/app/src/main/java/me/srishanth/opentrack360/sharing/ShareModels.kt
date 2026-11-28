package me.srishanth.opentrack360.sharing

import java.net.URI
import java.time.Instant
import java.util.Base64
import java.util.Locale
import java.util.UUID

/** Wire modes from POST /shares. Implementations should encode only the fields in each subtype. */
sealed interface CreateShareRequest {
    data class UntilTime(val expiresAt: Instant) : CreateShareRequest

    data class UntilDestination(
        val latitude: Double,
        val longitude: Double,
        val radiusMeters: Int = 150,
    ) : CreateShareRequest {
        init {
            require(latitude.isFinite() && latitude in -90.0..90.0)
            require(longitude.isFinite() && longitude in -180.0..180.0)
            require(radiusMeters in 50..1000)
        }
    }

    data class UntilDriveEnd(val confirmedDrive: Boolean) : CreateShareRequest {
        init {
            require(confirmedDrive) { "A drive-end share requires explicit driver confirmation." }
        }
    }
}

/** Enforces the API database's maximum time-share lifetime before a link is reviewed or created. */
internal object TimeShareDurationPolicy {
    const val MAX_DURATION_MINUTES = 43_200L

    fun resolve(durationMinutes: String, now: Instant): Instant? {
        val minutes = durationMinutes.toLongOrNull()
            ?.takeIf { it in 1..MAX_DURATION_MINUTES }
            ?: return null
        return runCatching { now.plusSeconds(minutes * 60L) }.getOrNull()
    }
}

enum class ShareMode(val wireValue: String) {
    TIME("time"),
    DESTINATION("destination"),
    DRIVE_END("drive_end"),
}

enum class SenderShareStatus(val wireValue: String) {
    ACTIVE("active"),
    ENDED("ended"),
}

/** Contains the one-time secret-bearing `url`; do not persist or log this response. */
class CreatedShare(
    val id: String,
    val url: String,
    val mode: ShareMode,
    val status: SenderShareStatus,
    val startedAt: Instant,
    val createdAt: Instant,
    val expiresAt: Instant?,
    val endCondition: String,
    val tripId: String?,
    val tripStartedAt: Instant?,
    val tripSessionMatchesCurrent: Boolean?,
    val sharingEpoch: Long,
) {
    init {
        require(sharingEpoch >= 1)
    }

    override fun toString(): String =
        "CreatedShare(id=$id, url=<redacted>, mode=${mode.wireValue}, status=${status.wireValue})"
}

/** Secret-free entry returned by GET /shares. */
data class SenderShare(
    val id: String,
    val mode: ShareMode,
    val status: SenderShareStatus,
    val createdAt: Instant,
    val startedAt: Instant,
    val expiresAt: Instant?,
    val endedAt: Instant?,
    val endCondition: String,
    val destination: ShareDestination? = null,
    val tripId: String? = null,
    val tripStartedAt: Instant? = null,
    val tripSessionMatchesCurrent: Boolean? = null,
    val sharingEpoch: Long = 1,
) {
    init {
        require(sharingEpoch >= 1)
    }

    override fun toString(): String =
        "SenderShare(id=$id, mode=${mode.wireValue}, status=${status.wireValue}, destination=${if (destination == null) "null" else "<redacted>"})"
}

data class ShareListPage(
    val data: List<SenderShare>,
    val nextCursor: String?,
)

/** Sender-only destination data needed to resume offline arrival checks after process death. */
data class ShareDestination(
    val latitude: Double,
    val longitude: Double,
    val radiusMeters: Int,
) {
    init {
        require(latitude.isFinite() && latitude in -90.0..90.0)
        require(longitude.isFinite() && longitude in -180.0..180.0)
        require(radiusMeters in 50..1_000)
    }

    override fun toString(): String = "ShareDestination(<redacted>)"
}

data class RevokedShare(
    val revoked: Boolean,
    val endedAt: Instant,
)

enum class ShareEventType(val wireValue: String) {
    ARRIVAL("arrival"),
    DRIVE_END("drive_end"),
}

data class LocationEvidenceFix(
    val sampleId: String,
    val capturedAt: Instant,
    val latitude: Double,
    val longitude: Double,
    val accuracyMeters: Double,
    val speedMps: Double?,
) {
    init {
        require(latitude.isFinite() && latitude in -90.0..90.0)
        require(longitude.isFinite() && longitude in -180.0..180.0)
        require(accuracyMeters.isFinite() && accuracyMeters in 0.0..10_000.0)
        require(speedMps == null || speedMps.isFinite() && speedMps in 0.0..150.0)
    }
}

data class ShareEventRequest(
    val clientEventId: String,
    val eventType: ShareEventType,
    val observedAt: Instant,
    val evidenceFixes: List<LocationEvidenceFix>,
) {
    init {
        require(evidenceFixes.size in 1..10)
    }
}

enum class ShareEventStatus(val wireValue: String) {
    CONFIRMED("confirmed"),
    PENDING("pending"),
    DUPLICATE("duplicate"),
}

data class ShareEventResult(
    val clientEventId: String,
    val status: ShareEventStatus,
    val serverTime: Instant,
    val endedAt: Instant?,
    val reason: String?,
)

/** Only the latest guest fix is represented here. This model has no Circle or history fields. */
enum class GuestShareStatus(val wireValue: String) {
    WAITING("waiting"),
    ACTIVE("active"),
    SIGNAL_LOST("signal_lost"),
}

data class PublicShareFix(
    val latitude: Double,
    val longitude: Double,
    val accuracyMeters: Double,
    val capturedAt: Instant,
    val speedMps: Double?,
)

data class PublicShareResponse(
    val status: GuestShareStatus,
    val serverTime: Instant,
    val viewValidUntil: Instant,
    val latest: PublicShareFix?,
)

/** The secret is intentionally not a data class property and never appears in toString(). */
class GuestShareRequest internal constructor(
    val shareId: String,
    private val secret: String,
) {
    /** Use only as the Authorization header value for GET /public/shares/{shareId}. */
    fun authorizationHeader(): String = "Bearer $secret"

    override fun toString(): String = "GuestShareRequest(shareId=$shareId, secret=<redacted>)"
}

enum class GuestLinkInvalidReason {
    MALFORMED_URI,
    WRONG_SCHEME,
    WRONG_ORIGIN,
    QUERY_NOT_ALLOWED,
    WRONG_PATH,
    INVALID_SHARE_ID,
    INVALID_SECRET,
}

sealed interface GuestLinkParseResult {
    data class Valid(val request: GuestShareRequest) : GuestLinkParseResult
    data class Invalid(val reason: GuestLinkInvalidReason) : GuestLinkParseResult
}

/** Strict parser for https://track.srishanth.me/s/{UUID}#{43-char base64url secret}. */
object GuestShareLinkParser {
    private const val EXPECTED_SCHEME = "https"
    private const val EXPECTED_AUTHORITY = "track.srishanth.me"
    private val sharePath = Regex("^/s/([0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12})$")
    private val secretPattern = Regex("^[A-Za-z0-9_-]{43}$")

    fun parse(rawUri: String?): GuestLinkParseResult {
        if (rawUri.isNullOrBlank()) return GuestLinkParseResult.Invalid(GuestLinkInvalidReason.MALFORMED_URI)
        val uri = try {
            URI(rawUri)
        } catch (_: Exception) {
            return GuestLinkParseResult.Invalid(GuestLinkInvalidReason.MALFORMED_URI)
        }

        if (!uri.scheme.equals(EXPECTED_SCHEME, ignoreCase = true)) {
            return GuestLinkParseResult.Invalid(GuestLinkInvalidReason.WRONG_SCHEME)
        }
        // Check the complete authority so credentials, explicit ports, and trailing-dot aliases fail.
        if (!uri.rawAuthority.equals(EXPECTED_AUTHORITY, ignoreCase = true)) {
            return GuestLinkParseResult.Invalid(GuestLinkInvalidReason.WRONG_ORIGIN)
        }
        if (uri.rawQuery != null) {
            return GuestLinkParseResult.Invalid(GuestLinkInvalidReason.QUERY_NOT_ALLOWED)
        }
        val pathMatch = sharePath.matchEntire(uri.rawPath.orEmpty())
            ?: return GuestLinkParseResult.Invalid(GuestLinkInvalidReason.WRONG_PATH)
        val rawId = pathMatch.groupValues[1]
        val parsedId = try {
            UUID.fromString(rawId).toString()
        } catch (_: IllegalArgumentException) {
            return GuestLinkParseResult.Invalid(GuestLinkInvalidReason.INVALID_SHARE_ID)
        }
        val id = parsedId.lowercase(Locale.ROOT)
        val secret = uri.rawFragment.orEmpty()
        if (!secretPattern.matches(secret)) {
            return GuestLinkParseResult.Invalid(GuestLinkInvalidReason.INVALID_SECRET)
        }
        val decoded = try {
            Base64.getUrlDecoder().decode(secret)
        } catch (_: IllegalArgumentException) {
            return GuestLinkParseResult.Invalid(GuestLinkInvalidReason.INVALID_SECRET)
        }
        // Require canonical unpadded encoding as well as exactly 32 decoded bytes.
        if (decoded.size != 32 || Base64.getUrlEncoder().withoutPadding().encodeToString(decoded) != secret) {
            return GuestLinkParseResult.Invalid(GuestLinkInvalidReason.INVALID_SECRET)
        }
        return GuestLinkParseResult.Valid(GuestShareRequest(id, secret))
    }
}

/**
 * Small, testable HTTP request description. A concrete adapter should use `url` for the request
 * target and `authorizationHeader` for the Authorization header; the secret is never in `url`.
 */
class GuestShareHttpRequest private constructor(
    val method: String,
    val url: URI,
    private val authorizationHeader: String,
) {
    fun header(name: String): String? =
        if (name.equals("Authorization", ignoreCase = true)) authorizationHeader else null

    override fun toString(): String = "GuestShareHttpRequest(method=$method, url=$url, Authorization=<redacted>)"

    companion object {
        fun from(request: GuestShareRequest): GuestShareHttpRequest = GuestShareHttpRequest(
            method = "GET",
            url = URI("https://track.srishanth.me/api/v1/public/shares/${request.shareId}"),
            authorizationHeader = request.authorizationHeader(),
        )
    }
}

sealed interface GuestShareReadOutcome {
    data class Success(val response: PublicShareResponse) : GuestShareReadOutcome
    data object NotFound : GuestShareReadOutcome
    data object Gone : GuestShareReadOutcome
    data object TransientFailure : GuestShareReadOutcome
}

/** Authenticated sender operations. Implement these against the shared API routes only. */
interface ShareGateway {
    /** POST /shares; the full secret-bearing URL is returned only for this call. */
    suspend fun create(request: CreateShareRequest): CreatedShare

    /** GET /shares; this response is always secret-free. */
    suspend fun list(): ShareListPage

    /** DELETE /shares/{shareId}; the API operation is idempotent. */
    suspend fun revoke(shareId: String): RevokedShare

    /** POST /shares/{shareId}/events with bounded event evidence. */
    suspend fun submitEvent(shareId: String, request: ShareEventRequest): ShareEventResult
}

/** Signed-out, single-share read. A successful implementation must send the bearer only as a header. */
interface GuestShareGateway {
    /** GET request.url, copy request.header("Authorization") into the Authorization header only. */
    suspend fun fetch(request: GuestShareHttpRequest): GuestShareReadOutcome
}
