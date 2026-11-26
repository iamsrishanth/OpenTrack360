package me.srishanth.opentrack360.core.api

import me.srishanth.opentrack360.sharing.CreateShareRequest
import me.srishanth.opentrack360.sharing.CreatedShare
import me.srishanth.opentrack360.sharing.GuestLinkParseResult
import me.srishanth.opentrack360.sharing.GuestShareGateway
import me.srishanth.opentrack360.sharing.GuestShareHttpRequest
import me.srishanth.opentrack360.sharing.GuestShareReadOutcome
import me.srishanth.opentrack360.sharing.GuestShareStatus
import me.srishanth.opentrack360.sharing.GuestShareLinkParser
import me.srishanth.opentrack360.sharing.LocationEvidenceFix
import me.srishanth.opentrack360.sharing.PublicShareFix
import me.srishanth.opentrack360.sharing.PublicShareResponse
import me.srishanth.opentrack360.sharing.RevokedShare
import me.srishanth.opentrack360.sharing.SenderShare
import me.srishanth.opentrack360.sharing.SenderShareStatus
import me.srishanth.opentrack360.sharing.ShareEventRequest
import me.srishanth.opentrack360.sharing.ShareEventResult
import me.srishanth.opentrack360.sharing.ShareEventStatus
import me.srishanth.opentrack360.sharing.ShareGateway
import me.srishanth.opentrack360.sharing.ShareListPage
import me.srishanth.opentrack360.sharing.ShareMode
import org.json.JSONArray
import org.json.JSONObject
import java.net.URI
import java.time.Instant
import java.util.UUID

/** Vercel API implementation for both sender and signed-out guest share screens. */
class ApiShareGateway(
    private val api: OpenTrackApi,
    private val onSharesChanged: (List<SenderShare>) -> Unit = {},
) : ShareGateway, GuestShareGateway {
    override suspend fun create(request: CreateShareRequest): CreatedShare {
        val body = when (request) {
            is CreateShareRequest.UntilTime -> JSONObject()
                .put("mode", ShareMode.TIME.wireValue)
                .put("expiresAt", request.expiresAt.toString())
            is CreateShareRequest.UntilDestination -> JSONObject()
                .put("mode", ShareMode.DESTINATION.wireValue)
                .put(
                    "destination",
                    JSONObject()
                        .put("latitude", request.latitude)
                        .put("longitude", request.longitude)
                        .put("radiusMeters", request.radiusMeters),
                )
            CreateShareRequest.UntilDriveEnd -> JSONObject().put("mode", ShareMode.DRIVE_END.wireValue)
        }
        val json = api.memberPost("shares", body)
        val created = CreatedShare(
            id = json.getString("id"),
            url = json.getString("url"),
            mode = parseShareMode(json.getString("mode")),
            status = parseSenderShareStatus(json.getString("status")),
            startedAt = json.getString("startedAt").toInstant(),
            createdAt = json.getString("createdAt").toInstant(),
            expiresAt = json.optNullableString("expiresAt")?.toInstant(),
            endCondition = json.optString("endCondition"),
        )
        require(created.status == SenderShareStatus.ACTIVE)
        require(GuestShareLinkParser.parse(created.url) is GuestLinkParseResult.Valid) {
            "The API returned an invalid guest link"
        }
        return created
    }

    override suspend fun list(): ShareListPage {
        val json = api.memberGet("shares")
        val entries = json.getJSONArray("data").mapObjects { item ->
            SenderShare(
                id = item.getString("id"),
                mode = parseShareMode(item.getString("mode")),
                status = parseSenderShareStatus(item.getString("status")),
                createdAt = item.getString("createdAt").toInstant(),
                startedAt = item.getString("startedAt").toInstant(),
                expiresAt = item.optNullableString("expiresAt")?.toInstant(),
                endedAt = item.optNullableString("endedAt")?.toInstant(),
                endCondition = item.optString("endCondition"),
            )
        }
        onSharesChanged(entries)
        return ShareListPage(entries, json.optNullableString("nextCursor"))
    }

    override suspend fun revoke(shareId: String): RevokedShare {
        val json = api.memberDelete("shares/${shareId.uuidPathSegment()}")
        return RevokedShare(
            revoked = json.optBoolean("revoked", true),
            endedAt = json.getString("endedAt").toInstant(),
        )
    }

    override suspend fun submitEvent(shareId: String, request: ShareEventRequest): ShareEventResult {
        require(request.evidenceFixes.isNotEmpty() && request.evidenceFixes.size <= 10)
        val body = JSONObject()
            .put("clientEventId", request.clientEventId.uuidPathSegment())
            .put("eventType", request.eventType.wireValue)
            .put("observedAt", request.observedAt.toString())
            .put("evidenceFixes", JSONArray().apply { request.evidenceFixes.forEach { put(it.toJson()) } })
        val json = api.memberPost("shares/${shareId.uuidPathSegment()}/events", body)
        return ShareEventResult(
            clientEventId = json.getString("clientEventId"),
            status = parseShareEventStatus(json.getString("status")),
            serverTime = json.getString("serverTime").toInstant(),
            endedAt = json.optNullableString("endedAt")?.toInstant(),
            reason = json.optNullableString("reason"),
        )
    }

    override suspend fun fetch(request: GuestShareHttpRequest): GuestShareReadOutcome {
        val uri = request.url
        if (uri.scheme != "https" || uri.host != "track.srishanth.me" || uri.rawQuery != null || uri.rawFragment != null) {
            return GuestShareReadOutcome.NotFound
        }
        val pathId = uri.path.substringAfterLast('/').takeIf { it.isNotBlank() }
            ?: return GuestShareReadOutcome.NotFound
        val bearer = request.header("Authorization") ?: return GuestShareReadOutcome.NotFound
        val response = try {
            api.publicGet("public/shares/${pathId.uuidPathSegment()}", bearer)
        } catch (_: Exception) {
            return GuestShareReadOutcome.TransientFailure
        }
        return when (response.statusCode) {
            404 -> GuestShareReadOutcome.NotFound
            410 -> GuestShareReadOutcome.Gone
            in 200..299 -> runCatching { response.body.toPublicResponse() }
                .fold(GuestShareReadOutcome::Success) { GuestShareReadOutcome.TransientFailure }
            else -> GuestShareReadOutcome.TransientFailure
        }
    }

    private fun String.toPublicResponse(): PublicShareResponse {
        val json = JSONObject(this)
        val latestJson = json.optJSONObject("latest")
        val latest = latestJson?.let {
            PublicShareFix(
                latitude = it.getDouble("latitude"),
                longitude = it.getDouble("longitude"),
                accuracyMeters = it.getDouble("accuracyMeters"),
                capturedAt = it.getString("capturedAt").toInstant(),
                speedMps = it.optNullableDouble("speedMps"),
            )
        }
        return PublicShareResponse(
            status = parseGuestShareStatus(json.getString("status")),
            serverTime = json.getString("serverTime").toInstant(),
            viewValidUntil = json.getString("viewValidUntil").toInstant(),
            latest = latest,
        )
    }

    private fun LocationEvidenceFix.toJson(): JSONObject = JSONObject()
        .put("sampleId", sampleId.uuidPathSegment())
        .put("capturedAt", capturedAt.toString())
        .put("latitude", latitude)
        .put("longitude", longitude)
        .put("accuracyMeters", accuracyMeters)
        .put("speedMps", speedMps ?: JSONObject.NULL)

    private fun JSONArray.mapObjects(block: (JSONObject) -> SenderShare): List<SenderShare> = buildList {
        for (index in 0 until length()) add(block(getJSONObject(index)))
    }

    private fun JSONObject.optNullableString(name: String): String? =
        if (!has(name) || isNull(name)) null else optString(name).takeIf(String::isNotBlank)

    private fun JSONObject.optNullableDouble(name: String): Double? =
        if (!has(name) || isNull(name)) null else optDouble(name).takeIf(Double::isFinite)

    private fun String.toInstant(): Instant = Instant.parse(this)

    private fun String.uuidPathSegment(): String = UUID.fromString(this).toString()

    private fun parseShareMode(value: String): ShareMode =
        ShareMode.entries.firstOrNull { it.wireValue == value } ?: error("Unknown share mode")

    private fun parseSenderShareStatus(value: String): SenderShareStatus =
        SenderShareStatus.entries.firstOrNull { it.wireValue == value } ?: error("Unknown share status")

    private fun parseGuestShareStatus(value: String): GuestShareStatus =
        GuestShareStatus.entries.firstOrNull { it.wireValue == value } ?: error("Unknown guest status")

    private fun parseShareEventStatus(value: String): ShareEventStatus =
        ShareEventStatus.entries.firstOrNull { it.wireValue == value } ?: error("Unknown share event status")
}
