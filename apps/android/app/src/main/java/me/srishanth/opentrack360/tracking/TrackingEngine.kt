package me.srishanth.opentrack360.tracking

import java.time.Duration
import java.time.Instant
import java.util.UUID
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.sin
import kotlin.math.sqrt

enum class MotionEvidence { MOVING, STATIONARY, UNKNOWN }
enum class TripRole { UNCONFIRMED, DRIVER, PASSENGER }

data class DriveStop(val startedAt: String, val durationSeconds: Long)

/** Phone-measurable trip summary. No inferred vehicle, fuel, cost, or crash metrics are included. */
data class DriveTrip(
    val tripId: String,
    val startedAt: String,
    val endedAt: String?,
    val role: TripRole,
    val elapsedSeconds: Long,
    val distanceMeters: Double,
    val averageMeasuredSpeedMps: Double?,
    val maxMeasuredSpeedMps: Double?,
    val stopCount: Int,
    val stops: List<DriveStop>,
)

sealed interface DriveUpdate {
    data class Started(val trip: DriveTrip) : DriveUpdate
    data class Progress(val trip: DriveTrip) : DriveUpdate
    data class Ended(val trip: DriveTrip, val evidenceFixes: List<LocationSample>) : DriveUpdate
    data class RoleCorrected(val trip: DriveTrip) : DriveUpdate
}

sealed interface LocationProcessResult {
    data class Accepted(val driveUpdate: DriveUpdate?) : LocationProcessResult
    data class Rejected(val reason: SampleRejection) : LocationProcessResult
}

/**
 * Stateful local location processor. It requires two credible moving fixes to start a drive and
 * five minutes of continuous, accurate, measured stationary evidence to end one. A gap resets the
 * stationary timer; a stop light and a parked start therefore never end/create a drive by themselves.
 */
class TrackingEngine(
    private val validationPolicy: SampleValidationPolicy = SampleValidationPolicy(),
    private val stationaryEndThreshold: Duration = Duration.ofMinutes(5),
) {
    private var activeScope: TrackingScope? = null
    private var lastAcceptedAt: Instant? = null
    private val classifier = DriveClassifier(stationaryEndThreshold)

    val isRunning: Boolean get() = activeScope != null
    val currentScope: TrackingScope? get() = activeScope
    val isDriveActive: Boolean get() = classifier.isDriveActive

    fun start(
        scope: TrackingScope?,
        permission: PermissionSnapshot,
        consentEnabled: Boolean,
        explicitUserAction: Boolean,
    ): Boolean {
        if (!explicitUserAction || !permission.canCollect || !consentEnabled || scope == null) return false
        if (scope.precision != permission.precision) return false
        activeScope = scope
        lastAcceptedAt = null
        classifier.reset()
        return true
    }

    fun stop() {
        activeScope = null
        lastAcceptedAt = null
        classifier.reset()
    }

    fun onLocation(
        scope: TrackingScope,
        permission: PermissionSnapshot,
        consentEnabled: Boolean,
        sample: LocationSample,
        motion: MotionEvidence,
        now: Instant,
    ): LocationProcessResult {
        val active = activeScope
        if (!permission.canCollect) {
            stop()
            return LocationProcessResult.Rejected(SampleRejection.WRONG_SCOPE)
        }
        if (!consentEnabled || active == null || !active.sameCaptureScope(scope) || scope.precision != permission.precision) {
            stop()
            return LocationProcessResult.Rejected(SampleRejection.WRONG_SCOPE)
        }
        val rejection = LocationSampleValidator.validate(sample, now, validationPolicy, lastAcceptedAt)
        if (rejection != null) return LocationProcessResult.Rejected(rejection)
        lastAcceptedAt = sample.instantOrNull()
        return LocationProcessResult.Accepted(classifier.onFix(sample, motion))
    }

    fun correctLastTripRole(role: TripRole): DriveTrip? = classifier.correctLastTripRole(role)
}

private class DriveClassifier(private val endThreshold: Duration) {
    private data class Candidate(val sample: LocationSample)
    private data class ActiveTrip(
        val tripId: String,
        val startedAt: Instant,
        var lastCredibleFix: LocationSample,
        var distanceMeters: Double = 0.0,
        var speedSum: Double = 0.0,
        var speedCount: Int = 0,
        var maxSpeed: Double? = null,
        var stationaryStartedAt: Instant? = null,
        var stationaryLastAt: Instant? = null,
        var recordedStopStart: Instant? = null,
        val stops: MutableList<DriveStop> = mutableListOf(),
        val movementEvidence: ArrayDeque<LocationSample> = ArrayDeque(),
        val stationaryEvidence: ArrayDeque<LocationSample> = ArrayDeque(),
    )

    private var lastFix: LocationSample? = null
    private var movementCandidate: Candidate? = null
    private var active: ActiveTrip? = null
    private var lastCompletedTrip: DriveTrip? = null

    val isDriveActive: Boolean get() = active != null

    fun reset() {
        lastFix = null
        movementCandidate = null
        active = null
        lastCompletedTrip = null
    }

    fun onFix(sample: LocationSample, motion: MotionEvidence): DriveUpdate? {
        val captured = sample.instantOrNull() ?: return null
        if (sample.accuracyMeters > MAX_DRIVE_ACCURACY_METERS) {
            lastFix = null
            movementCandidate = null
            // Poor quality must not advance a stationary timer or produce a terminal event.
            active?.let {
                it.stationaryStartedAt = null
                it.stationaryLastAt = null
                it.stationaryEvidence.clear()
            }
            return active?.let { DriveUpdate.Progress(snapshot(it, captured, ended = false)) }
        }

        val trip = active
        if (trip == null) {
            val priorCandidate = movementCandidate
            val priorFixForEvidence = priorCandidate?.sample ?: lastFix
            if (isCredibleMovement(sample, motion, priorFixForEvidence)) {
                if (priorCandidate != null && withinMovementWindow(priorCandidate.sample, sample)) {
                    val startAt = priorCandidate.sample.instantOrNull() ?: captured
                    val created = ActiveTrip(UUID.randomUUID().toString(), startAt, priorCandidate.sample)
                    if (isCredibleMovingFix(priorCandidate.sample)) created.movementEvidence.add(priorCandidate.sample)
                    if (isCredibleMovingFix(sample)) created.movementEvidence.add(sample)
                    addMetrics(created, priorCandidate.sample, sample)
                    priorCandidate.sample.speedMps?.let {
                        created.speedSum += it
                        created.speedCount++
                        created.maxSpeed = max(created.maxSpeed ?: 0.0, it)
                    }
                    active = created
                    movementCandidate = null
                    lastFix = sample
                    return DriveUpdate.Started(snapshot(created, captured, ended = false))
                }
                movementCandidate = Candidate(sample)
            } else {
                movementCandidate = null
            }
            lastFix = sample
            return null
        }

        val prior = trip.lastCredibleFix
        val priorAt = prior.instantOrNull()
        val gap = priorAt?.let { Duration.between(it, captured) }
        val continuityGap = gap == null || gap.isNegative || gap > MAX_CONTINUOUS_FIX_GAP
        if (continuityGap) {
            trip.stationaryStartedAt = null
            trip.stationaryLastAt = null
            trip.recordedStopStart = null
            trip.stationaryEvidence.clear()
        }

        addMetrics(trip, prior, sample)
        if (isCredibleMovingFix(sample, motion)) appendMovementEvidence(trip, sample)
        trip.lastCredibleFix = sample
        lastFix = sample

        if (isCredibleStationary(sample, motion)) {
            val started = trip.stationaryStartedAt
            val lastStationary = trip.stationaryLastAt
            if (started == null || lastStationary == null || continuityGap ||
                Duration.between(lastStationary, captured) > MAX_CONTINUOUS_FIX_GAP
            ) {
                trip.stationaryStartedAt = captured
                trip.stationaryLastAt = captured
                trip.recordedStopStart = null
                trip.stationaryEvidence.clear()
                trip.stationaryEvidence.addLast(sample)
            } else {
                trip.stationaryLastAt = captured
                appendStationaryEvidence(trip, sample)
                val duration = Duration.between(started, captured)
                if (duration >= STOP_COUNT_THRESHOLD && trip.recordedStopStart == null) {
                    trip.recordedStopStart = started
                    trip.stops += DriveStop(started.toString(), duration.seconds)
                } else if (duration >= STOP_COUNT_THRESHOLD && trip.stops.isNotEmpty()) {
                    val last = trip.stops.last()
                    if (last.startedAt == started.toString()) trip.stops[trip.stops.lastIndex] = last.copy(durationSeconds = duration.seconds)
                }
                if (duration >= endThreshold) {
                    val summary = snapshot(trip, captured, ended = true)
                    lastCompletedTrip = summary
                    active = null
                    movementCandidate = null
                    val terminalEvidence = selectTerminalEvidence(
                        movementSamples = trip.movementEvidence.toList(),
                        stationarySamples = trip.stationaryEvidence.toList(),
                        stationaryStartedAt = started,
                        endedAt = captured,
                    )
                    return DriveUpdate.Ended(summary, terminalEvidence)
                }
            }
        } else {
            trip.stationaryStartedAt = null
            trip.stationaryLastAt = null
            trip.recordedStopStart = null
            trip.stationaryEvidence.clear()
        }
        return DriveUpdate.Progress(snapshot(trip, captured, ended = false))
    }

    fun correctLastTripRole(role: TripRole): DriveTrip? {
        val previous = lastCompletedTrip ?: return null
        val updated = previous.copy(role = role)
        if (lastCompletedTrip != null) lastCompletedTrip = updated
        return updated
    }

    private fun isCredibleMovement(sample: LocationSample, motion: MotionEvidence, prior: LocationSample?): Boolean {
        val speed = sample.speedMps ?: return false
        if (speed < MIN_DRIVE_SPEED_MPS) return false
        if (motion == MotionEvidence.MOVING) return true
        if (motion != MotionEvidence.UNKNOWN || prior == null) return false
        val priorAt = prior.instantOrNull() ?: return false
        val currentAt = sample.instantOrNull() ?: return false
        val elapsed = Duration.between(priorAt, currentAt).seconds.coerceAtLeast(1)
        return speed >= UNKNOWN_MOTION_MIN_SPEED_MPS && distanceMeters(prior, sample) >= max(15.0, elapsed * UNKNOWN_MOTION_MIN_SPEED_MPS * 0.5)
    }

    private fun withinMovementWindow(first: LocationSample, second: LocationSample): Boolean {
        val a = first.instantOrNull() ?: return false
        val b = second.instantOrNull() ?: return false
        val seconds = Duration.between(a, b).seconds
        return seconds in 1..MAX_MOVEMENT_CANDIDATE_GAP.seconds && distanceMeters(first, second) >= MIN_MOVEMENT_DISTANCE_METERS
    }

    private fun isCredibleStationary(sample: LocationSample, motion: MotionEvidence): Boolean =
        motion == MotionEvidence.STATIONARY && sample.speedMps != null &&
            sample.speedMps <= MAX_STATIONARY_SPEED_MPS && sample.accuracyMeters <= MAX_STATIONARY_ACCURACY_METERS

    private fun addMetrics(trip: ActiveTrip, from: LocationSample, to: LocationSample) {
        val at = to.instantOrNull() ?: return
        val previousAt = from.instantOrNull() ?: return
        val seconds = Duration.between(previousAt, at).seconds
        if (seconds in 1..MAX_METRIC_GAP.seconds) {
            val distance = distanceMeters(from, to)
            val maximumPlausible = max(MAX_SINGLE_STEP_METERS, seconds * MAX_PLAUSIBLE_SPEED_MPS)
            if (distance <= maximumPlausible) trip.distanceMeters += distance
        }
        to.speedMps?.takeIf { it in 0.0..150.0 }?.let {
            trip.speedSum += it
            trip.speedCount++
            trip.maxSpeed = max(trip.maxSpeed ?: 0.0, it)
        }
    }

    private fun isCredibleMovingFix(sample: LocationSample, motion: MotionEvidence = MotionEvidence.MOVING): Boolean =
        motion == MotionEvidence.MOVING && sample.accuracyMeters <= MAX_DRIVE_ACCURACY_METERS &&
            (sample.speedMps ?: 0.0) >= MIN_DRIVE_SPEED_MPS

    private fun appendMovementEvidence(trip: ActiveTrip, sample: LocationSample) {
        trip.movementEvidence.addLast(sample)
        while (trip.movementEvidence.size > MOVEMENT_EVIDENCE_HISTORY_LIMIT) trip.movementEvidence.removeFirst()
    }

    private fun appendStationaryEvidence(trip: ActiveTrip, sample: LocationSample) {
        trip.stationaryEvidence.addLast(sample)
        while (trip.stationaryEvidence.size > STATIONARY_EVIDENCE_HISTORY_LIMIT) {
            trip.stationaryEvidence.removeFirst()
        }
    }

    /**
     * The API caps evidence at ten fixes, while local detection needs a continuous five minute
     * stationary window. Downsample across the complete qualifying window instead of taking the
     * most recent ten 15 second callbacks (which would cover only 135 seconds).
     */
    private fun selectTerminalEvidence(
        movementSamples: List<LocationSample>,
        stationarySamples: List<LocationSample>,
        stationaryStartedAt: Instant,
        endedAt: Instant,
    ): List<LocationSample> {
        val moving = movementSamples.mapNotNull { sample ->
            sample.instantOrNull()?.let { it to sample }
        }.filter { (captured, sample) ->
            !captured.isAfter(stationaryStartedAt) &&
                !captured.isBefore(stationaryStartedAt.minus(MOVEMENT_EVIDENCE_LOOKBACK)) &&
                sample.accuracyMeters <= MAX_DRIVE_ACCURACY_METERS &&
                (sample.speedMps ?: 0.0) in MIN_DRIVE_SPEED_MPS..MAX_PRE_STOP_SPEED_MPS
        }.sortedBy { it.first }
        var movementPair: List<LocationSample>? = null
        for (lastIndex in moving.indices.reversed()) {
            val (lastAt, lastSample) = moving[lastIndex]
            if (Duration.between(lastAt, stationaryStartedAt) > MOVEMENT_EVIDENCE_LOOKBACK) continue
            for (firstIndex in lastIndex - 1 downTo 0) {
                val (firstAt, firstSample) = moving[firstIndex]
                val intervalSeconds = Duration.between(firstAt, lastAt).seconds
                if (intervalSeconds < MIN_MOVING_EVIDENCE_SPAN.seconds) continue
                val distance = distanceMeters(firstSample, lastSample)
                val maximumPlausibleDistance = MAX_PRE_STOP_SPEED_MPS * intervalSeconds + MAX_PRE_STOP_DISTANCE_SLACK_METERS
                if (distance < MIN_MOVING_EVIDENCE_DISTANCE_METERS || distance > maximumPlausibleDistance) continue
                movementPair = listOf(firstSample, lastSample)
                break
            }
            if (movementPair != null) break
        }
        val selectedMovement = movementPair ?: return emptyList()

        val window = stationarySamples.filter { sample ->
            val captured = sample.instantOrNull() ?: return@filter false
            !captured.isBefore(stationaryStartedAt) && !captured.isAfter(endedAt) &&
                isCredibleStationary(sample, MotionEvidence.STATIONARY)
        }
        if (window.size < 2) return emptyList()
        val first = window.first().instantOrNull() ?: return emptyList()
        val last = window.last().instantOrNull() ?: return emptyList()
        if (Duration.between(first, last) < endThreshold) return emptyList()
        val stationaryLimit = PendingShareEvent.MAX_EVENT_EVIDENCE_FIXES - selectedMovement.size
        if (window.size <= stationaryLimit) return selectedMovement + window
        val lastIndex = window.lastIndex
        val intervals = stationaryLimit - 1
        val selectedStationary = (0 until stationaryLimit).map { step ->
            val index = (step * lastIndex + intervals / 2) / intervals
            window[index]
        }.distinctBy { it.sampleId }
        return selectedMovement + selectedStationary
    }

    private fun snapshot(trip: ActiveTrip, at: Instant, ended: Boolean): DriveTrip {
        val elapsed = Duration.between(trip.startedAt, at).seconds.coerceAtLeast(0)
        return DriveTrip(
            tripId = trip.tripId,
            startedAt = trip.startedAt.toString(),
            endedAt = if (ended) at.toString() else null,
            role = TripRole.UNCONFIRMED,
            elapsedSeconds = elapsed,
            distanceMeters = trip.distanceMeters,
            averageMeasuredSpeedMps = if (trip.speedCount > 0) trip.speedSum / trip.speedCount else null,
            maxMeasuredSpeedMps = trip.maxSpeed,
            stopCount = trip.stops.size,
            stops = trip.stops.toList(),
        )
    }

    private fun distanceMeters(a: LocationSample, b: LocationSample): Double {
        val earthRadiusMeters = 6_371_000.0
        val lat1 = Math.toRadians(a.latitude)
        val lat2 = Math.toRadians(b.latitude)
        val deltaLat = lat2 - lat1
        val deltaLon = Math.toRadians(b.longitude - a.longitude)
        val value = sin(deltaLat / 2) * sin(deltaLat / 2) + cos(lat1) * cos(lat2) * sin(deltaLon / 2) * sin(deltaLon / 2)
        return earthRadiusMeters * 2 * atan2(sqrt(value), sqrt(1 - value))
    }

    private companion object {
        const val MAX_DRIVE_ACCURACY_METERS = 50.0
        const val MAX_STATIONARY_ACCURACY_METERS = 25.0
        const val MIN_DRIVE_SPEED_MPS = 5.5
        const val UNKNOWN_MOTION_MIN_SPEED_MPS = 9.0
        const val MIN_MOVEMENT_DISTANCE_METERS = 20.0
        const val MAX_STATIONARY_SPEED_MPS = 1.5
        const val STOP_COUNT_THRESHOLD_SECONDS = 60L
        val STOP_COUNT_THRESHOLD: Duration = Duration.ofSeconds(STOP_COUNT_THRESHOLD_SECONDS)
        val MAX_MOVEMENT_CANDIDATE_GAP: Duration = Duration.ofSeconds(45)
        val MAX_CONTINUOUS_FIX_GAP: Duration = Duration.ofSeconds(60)
        val MAX_METRIC_GAP: Duration = Duration.ofSeconds(60)
        const val MAX_SINGLE_STEP_METERS = 120.0
        const val MAX_PLAUSIBLE_SPEED_MPS = 80.0
        const val MIN_MOVING_EVIDENCE_DISTANCE_METERS = 150.0
        const val MAX_PRE_STOP_SPEED_MPS = 55.0
        const val MAX_PRE_STOP_DISTANCE_SLACK_METERS = 100.0
        const val MOVEMENT_EVIDENCE_HISTORY_LIMIT = 32
        const val STATIONARY_EVIDENCE_HISTORY_LIMIT = 512
        val MIN_MOVING_EVIDENCE_SPAN: Duration = Duration.ofSeconds(30)
        val MOVEMENT_EVIDENCE_LOOKBACK: Duration = Duration.ofSeconds(90)
    }
}

/** Verifies that a bounded drive_end payload still satisfies the server's moving/stationary rules. */
object DriveEndEvidencePolicy {
    /**
     * Allows only moving fixes from the linked confirmed trip to predate share creation. The
     * server-provided session flag and the locally current epoch must both still match; all
     * stationary evidence remains at or after share creation.
     */
    fun forLinkedTrip(
        evidenceFixes: List<LocationSample>,
        shareStartedAt: Instant,
        tripStartedAt: Instant,
        tripSessionMatchesCurrent: Boolean,
        currentSharingEpoch: Long,
        shareSharingEpoch: Long,
    ): List<LocationSample> {
        if (!tripSessionMatchesCurrent || shareSharingEpoch < 1 || currentSharingEpoch != shareSharingEpoch ||
            tripStartedAt.isAfter(shareStartedAt)
        ) return emptyList()

        val eligible = evidenceFixes.filter { sample ->
            val at = sample.instantOrNull() ?: return@filter false
            !at.isBefore(tripStartedAt) &&
                (!at.isBefore(shareStartedAt) || isCredibleMovementEvidence(sample))
        }
        return eligible.takeIf(::isSufficient).orEmpty()
    }

    fun afterShareStart(evidenceFixes: List<LocationSample>, shareStartedAt: Instant): List<LocationSample> {
        val eligible = evidenceFixes.filter { sample ->
            sample.instantOrNull()?.let { !it.isBefore(shareStartedAt) } == true
        }
        return eligible.takeIf(::isSufficient).orEmpty()
    }

    fun isSufficient(evidenceFixes: List<LocationSample>): Boolean {
        if (evidenceFixes.size !in 4..PendingShareEvent.MAX_EVENT_EVIDENCE_FIXES) return false
        val times = evidenceFixes.map { it.instantOrNull() ?: return false }
        if (times.zipWithNext().any { (first, second) -> !second.isAfter(first) }) return false

        val stationaryIndex = evidenceFixes.indexOfFirst(::isCredibleStationaryEvidence)
        if (stationaryIndex < 2) return false
        val stationary = evidenceFixes.drop(stationaryIndex)
        if (stationary.size < 2 || stationary.any { !isCredibleStationaryEvidence(it) }) return false
        val stationaryTimes = stationary.map { it.instantOrNull() ?: return false }
        if (Duration.between(stationaryTimes.first(), stationaryTimes.last()) < STATIONARY_WINDOW) return false
        if (stationaryTimes.zipWithNext().any { (first, second) ->
                val gap = Duration.between(first, second)
                gap.isNegative || gap.isZero || gap > MAX_STATIONARY_GAP
            }
        ) return false

        val movement = evidenceFixes.take(stationaryIndex).mapIndexedNotNull { index, sample ->
            val at = sample.instantOrNull() ?: return@mapIndexedNotNull null
            if (isCredibleMovementEvidence(sample)) index to (at to sample) else null
        }
        if (movement.size < 2) return false
        val firstStationaryAt = stationaryTimes.first()
        val transitionSamples = movement.map { it.second }
        val hasPair = transitionSamples.indices.any { firstIndex ->
            (firstIndex + 1 until transitionSamples.size).any { secondIndex ->
                val (firstAt, firstSample) = transitionSamples[firstIndex]
                val (secondAt, secondSample) = transitionSamples[secondIndex]
                val span = Duration.between(firstAt, secondAt).seconds
                val transition = Duration.between(secondAt, firstStationaryAt)
                val distance = distanceMeters(firstSample, secondSample)
                span >= MIN_MOVING_SPAN.seconds &&
                    !transition.isNegative && transition <= MAX_MOVING_TO_STATIONARY_GAP &&
                    distance >= MIN_MOVING_DISTANCE_METERS &&
                    distance <= MAX_MOVING_SPEED_MPS * span + DISTANCE_SLACK_METERS
            }
        }
        return hasPair
    }

    private fun isCredibleMovementEvidence(sample: LocationSample): Boolean =
        sample.accuracyMeters in 0.0..MAX_MOVING_ACCURACY_METERS &&
            sample.speedMps?.let { it in MIN_MOVING_SPEED_MPS..MAX_MOVING_SPEED_MPS } == true

    private fun isCredibleStationaryEvidence(sample: LocationSample): Boolean =
        sample.accuracyMeters in 0.0..MAX_STATIONARY_ACCURACY_METERS &&
            sample.speedMps?.let { it in 0.0..MAX_STATIONARY_SPEED_MPS } == true

    private fun distanceMeters(a: LocationSample, b: LocationSample): Double {
        val earthRadiusMeters = 6_371_000.0
        val latA = Math.toRadians(a.latitude)
        val latB = Math.toRadians(b.latitude)
        val deltaLat = latB - latA
        val deltaLon = Math.toRadians(b.longitude - a.longitude)
        val value = sin(deltaLat / 2) * sin(deltaLat / 2) +
            cos(latA) * cos(latB) * sin(deltaLon / 2) * sin(deltaLon / 2)
        return earthRadiusMeters * 2 * atan2(sqrt(value), sqrt(1 - value))
    }

    private val STATIONARY_WINDOW: Duration = Duration.ofMinutes(5)
    private val MAX_STATIONARY_GAP: Duration = Duration.ofSeconds(60)
    private val MIN_MOVING_SPAN: Duration = Duration.ofSeconds(30)
    private val MAX_MOVING_TO_STATIONARY_GAP: Duration = Duration.ofSeconds(90)
    private val MIN_MOVING_SPEED_MPS = 5.0
    private val MAX_MOVING_ACCURACY_METERS = 50.0
    private val MAX_STATIONARY_ACCURACY_METERS = 25.0
    private val MAX_STATIONARY_SPEED_MPS = 1.5
    private val MIN_MOVING_DISTANCE_METERS = 150.0
    private val MAX_MOVING_SPEED_MPS = 55.0
    private val DISTANCE_SLACK_METERS = 100.0
}

/** Rolling linear-acceleration estimate using only the native accelerometer. */
class AccelerometerMotionTracker(private val windowSize: Int = 12) {
    private val gravity = FloatArray(3)
    private val magnitudes = ArrayDeque<Float>()
    private var hasGravity = false

    fun addAcceleration(x: Float, y: Float, z: Float): MotionEvidence {
        if (!hasGravity) {
            gravity[0] = x; gravity[1] = y; gravity[2] = z; hasGravity = true
        }
        val alpha = 0.85f
        gravity[0] = alpha * gravity[0] + (1 - alpha) * x
        gravity[1] = alpha * gravity[1] + (1 - alpha) * y
        gravity[2] = alpha * gravity[2] + (1 - alpha) * z
        val lx = x - gravity[0]; val ly = y - gravity[1]; val lz = z - gravity[2]
        magnitudes.addLast(sqrt(lx * lx + ly * ly + lz * lz))
        while (magnitudes.size > windowSize) magnitudes.removeFirst()
        if (magnitudes.size < windowSize) return MotionEvidence.UNKNOWN
        val rms = sqrt(magnitudes.map { it * it }.average()).toFloat()
        return when {
            rms >= MOVING_RMS_THRESHOLD -> MotionEvidence.MOVING
            rms <= STATIONARY_RMS_THRESHOLD -> MotionEvidence.STATIONARY
            else -> MotionEvidence.UNKNOWN
        }
    }

    fun current(): MotionEvidence {
        if (magnitudes.size < windowSize) return MotionEvidence.UNKNOWN
        val rms = sqrt(magnitudes.map { it * it }.average()).toFloat()
        return when {
            rms >= MOVING_RMS_THRESHOLD -> MotionEvidence.MOVING
            rms <= STATIONARY_RMS_THRESHOLD -> MotionEvidence.STATIONARY
            else -> MotionEvidence.UNKNOWN
        }
    }

    fun reset() {
        magnitudes.clear()
        gravity.fill(0f)
        hasGravity = false
    }

    private companion object {
        const val MOVING_RMS_THRESHOLD = 0.45f
        const val STATIONARY_RMS_THRESHOLD = 0.25f
    }
}

enum class CrashCheckInState { WAITING_FOR_CHECK_IN, CANCELED, CHECKED_IN, COUNTDOWN_FINISHED }

data class SimulatedCrashPrompt(val startedAt: Instant, val cancelUntil: Instant, val state: CrashCheckInState)

/** Possible-crash network detection is hard-disabled until a separate validation gate is opened. */
class PossibleCrashGate(private val validatedGateOpen: Boolean = false) {
    fun evaluateSensorEvidence(/* evidence: */ hasSuddenImpact: Boolean): SimulatedCrashPrompt? {
        if (!validatedGateOpen || !hasSuddenImpact) return null
        // No automatic alert delivery is implemented; returning null keeps dispatch disabled.
        return null
    }
}

/** Private testing hook only; it has no transport or dispatch dependency. */
class CrashSimulationHarness(private val cancelWindow: Duration = Duration.ofSeconds(30)) {
    private var prompt: SimulatedCrashPrompt? = null

    fun simulate(now: Instant): SimulatedCrashPrompt {
        return SimulatedCrashPrompt(now, now.plus(cancelWindow), CrashCheckInState.WAITING_FOR_CHECK_IN)
            .also { prompt = it }
    }

    fun cancel(): SimulatedCrashPrompt? = update(CrashCheckInState.CANCELED)
    fun checkIn(): SimulatedCrashPrompt? = update(CrashCheckInState.CHECKED_IN)

    fun advance(now: Instant): SimulatedCrashPrompt? {
        val current = prompt ?: return null
        if (current.state == CrashCheckInState.WAITING_FOR_CHECK_IN && !now.isBefore(current.cancelUntil)) {
            return current.copy(state = CrashCheckInState.COUNTDOWN_FINISHED).also { prompt = it }
        }
        return current
    }

    private fun update(state: CrashCheckInState): SimulatedCrashPrompt? {
        val current = prompt ?: return null
        if (current.state != CrashCheckInState.WAITING_FOR_CHECK_IN) return current
        return current.copy(state = state).also { prompt = it }
    }
}

