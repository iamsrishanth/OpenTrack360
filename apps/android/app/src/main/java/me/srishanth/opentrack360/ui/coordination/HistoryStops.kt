package me.srishanth.opentrack360.ui.coordination

import java.time.Duration
import java.time.Instant
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

/** A history fix with enough metadata to decide whether a stop is evidenced by the route. */
data class HistoryStopSample(
    val latitude: Double,
    val longitude: Double,
    val accuracyMeters: Double,
    val capturedAt: Instant,
    val speedMps: Double?,
    val precision: String,
)

data class HistoryStopInterval(val startedAt: Instant, val endedAt: Instant)

/**
 * Derives stop intervals only from continuous, accurate, precise stationary fixes. Missing,
 * approximate, inaccurate, or moving samples break a candidate so a GPS gap cannot become a stop.
 */
fun deriveHistoryStops(samples: List<HistoryStopSample>): List<HistoryStopInterval> {
    if (samples.size < 2) return emptyList()

    val sorted = samples.sortedBy(HistoryStopSample::capturedAt)
    val result = mutableListOf<HistoryStopInterval>()
    val candidate = mutableListOf<HistoryStopSample>()

    fun finishCandidate() {
        if (candidate.size >= 2) {
            val start = candidate.first().capturedAt
            val end = candidate.last().capturedAt
            if (Duration.between(start, end) >= HistoryStopPolicy.MIN_STOP_DURATION) {
                result += HistoryStopInterval(startedAt = start, endedAt = end)
            }
        }
        candidate.clear()
    }

    for (sample in sorted) {
        if (!sample.isCredibleStationaryFix()) {
            finishCandidate()
            continue
        }

        val first = candidate.firstOrNull()
        if (first == null) {
            candidate += sample
            continue
        }

        val previous = candidate.last()
        val gap = Duration.between(previous.capturedAt, sample.capturedAt)
        if (gap.isNegative || gap.isZero) continue

        val brokeContinuity = gap > HistoryStopPolicy.MAX_FIX_GAP ||
            distanceMeters(first.latitude, first.longitude, sample.latitude, sample.longitude) >
            HistoryStopPolicy.MAX_DISPERSION_METERS
        if (brokeContinuity) {
            finishCandidate()
        }
        candidate += sample
    }

    finishCandidate()
    return result
}

private fun HistoryStopSample.isCredibleStationaryFix(): Boolean =
    latitude.isFinite() && latitude in -90.0..90.0 &&
        longitude.isFinite() && longitude in -180.0..180.0 &&
        accuracyMeters.isFinite() && accuracyMeters in 0.0..HistoryStopPolicy.MAX_ACCURACY_METERS &&
        precision == "precise" &&
        speedMps?.let { it.isFinite() && it in 0.0..HistoryStopPolicy.MAX_STATIONARY_SPEED_MPS } == true

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

object HistoryStopPolicy {
    val MIN_STOP_DURATION: Duration = Duration.ofMinutes(5)
    val MAX_FIX_GAP: Duration = Duration.ofSeconds(90)
    const val MAX_ACCURACY_METERS = 50.0
    const val MAX_STATIONARY_SPEED_MPS = 1.5
    const val MAX_DISPERSION_METERS = 75.0
}
