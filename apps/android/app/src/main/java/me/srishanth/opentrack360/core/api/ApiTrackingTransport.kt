package me.srishanth.opentrack360.core.api

import me.srishanth.opentrack360.tracking.LocationBatchResult as TrackingBatchResult
import me.srishanth.opentrack360.tracking.LocationSample
import me.srishanth.opentrack360.tracking.PendingShareEvent
import me.srishanth.opentrack360.tracking.ShareEventResult as TrackingEventResult
import me.srishanth.opentrack360.tracking.ShareEventStatus
import me.srishanth.opentrack360.tracking.SharingEpochMismatchException
import me.srishanth.opentrack360.tracking.TrackingTransport
import me.srishanth.opentrack360.tracking.StopSharingResult as TrackingStopResult
import org.json.JSONArray
import org.json.JSONObject

/** Authenticated transport used by the location service; ownership always comes from the session. */
class ApiTrackingTransport(private val api: OpenTrackApi) : TrackingTransport {
    override suspend fun postLocationsBatch(sharingEpoch: Long, samples: List<LocationSample>): TrackingBatchResult {
        require(sharingEpoch >= 1)
        require(samples.isNotEmpty() && samples.size <= 100)
        val response = try {
            api.memberPost(
                "locations/batch",
                JSONObject()
                    .put("sharingEpoch", sharingEpoch)
                    .put("samples", JSONArray().apply { samples.forEach { put(it.toJson()) } }),
            )
        } catch (exception: ApiException) {
            if (exception.statusCode == 409 && exception.errorCode == "SHARING_EPOCH_MISMATCH") {
                val currentEpoch = runCatching { api.loadMemberProfile().sharingEpoch }.getOrNull()
                throw SharingEpochMismatchException(currentEpoch)
            }
            throw exception
        }
        val accepted = response.getJSONArray("accepted").strings().toSet()
        val duplicates = response.getJSONArray("duplicates").strings().toSet()
        val rejected = buildMap {
            val rows = response.getJSONArray("rejected")
            for (index in 0 until rows.length()) {
                val row = rows.getJSONObject(index)
                put(row.getString("sampleId"), row.getString("code"))
            }
        }
        return TrackingBatchResult(
            serverTime = response.getString("serverTime"),
            accepted = accepted,
            duplicates = duplicates,
            rejected = rejected,
        )
    }

    override suspend fun postShareEvent(shareId: String, event: PendingShareEvent): TrackingEventResult {
        val response = try {
            api.memberPost(
                "shares/${validatedUuid(shareId)}/events",
                JSONObject()
                    .put("clientEventId", event.clientEventId)
                    .put("eventType", event.eventType.wireValue)
                    .put("observedAt", event.observedAt)
                    .put("evidenceFixes", JSONArray().apply { event.evidenceFixes.forEach { put(it.toJson()) } }),
            )
        } catch (exception: ApiException) {
            if (exception.statusCode == 410 && exception.errorCode in setOf("SHARE_ENDED", "share_ended")) {
                throw me.srishanth.opentrack360.tracking.ShareAlreadyEndedException()
            }
            throw exception
        }
        return TrackingEventResult(
            clientEventId = response.getString("clientEventId"),
            status = when (response.getString("status")) {
                "confirmed" -> ShareEventStatus.CONFIRMED
                "duplicate" -> ShareEventStatus.DUPLICATE
                else -> ShareEventStatus.PENDING
            },
            serverTime = response.getString("serverTime"),
            endedAt = response.optNullableString("endedAt"),
            reason = response.optNullableString("reason"),
        )
    }

    override suspend fun postStopSharing(clientRequestId: String): TrackingStopResult {
        val result = api.stopAllSharing(clientRequestId)
        return TrackingStopResult(result.stopped, result.endedShareCount, result.sharingEpoch)
    }

    private fun LocationSample.toJson(): JSONObject = JSONObject()
        .put("sampleId", sampleId)
        .put("capturedAt", capturedAt)
        .put("latitude", latitude)
        .put("longitude", longitude)
        .put("accuracyMeters", accuracyMeters)
        .put("speedMps", speedMps ?: JSONObject.NULL)

    private fun JSONArray.strings(): List<String> = buildList {
        for (index in 0 until length()) add(getString(index))
    }

    private fun JSONObject.optNullableString(name: String): String? =
        if (!has(name) || isNull(name)) null else optString(name).takeIf(String::isNotBlank)

    private fun validatedUuid(value: String): String = java.util.UUID.fromString(value).toString()
}
