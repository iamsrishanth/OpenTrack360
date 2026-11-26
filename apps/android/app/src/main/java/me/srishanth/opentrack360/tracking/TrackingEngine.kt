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
        val evidence: ArrayDeque<LocationSample> = ArrayDeque(),
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
            active?.let { it.stationaryStartedAt = null; it.stationaryLastAt = null }
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
                    created.evidence.add(priorCandidate.sample)
                    created.evidence.add(sample)
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
        }

        addMetrics(trip, prior, sample)
        appendEvidence(trip, sample)
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
            } else {
                trip.stationaryLastAt = captured
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
                    return DriveUpdate.Ended(summary, trip.evidence.toList().takeLast(PendingShareEvent.MAX_EVENT_EVIDENCE_FIXES))
                }
            }
        } else {
            trip.stationaryStartedAt = null
            trip.stationaryLastAt = null
            trip.recordedStopStart = null
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

    private fun appendEvidence(trip: ActiveTrip, sample: LocationSample) {
        trip.evidence.addLast(sample)
        while (trip.evidence.size > EVIDENCE_HISTORY_LIMIT) trip.evidence.removeFirst()
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
        const val EVIDENCE_HISTORY_LIMIT = 20
    }
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

