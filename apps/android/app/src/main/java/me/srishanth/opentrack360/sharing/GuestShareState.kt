package me.srishanth.opentrack360.sharing

import java.time.Duration
import java.time.Instant
import kotlin.math.roundToInt

enum class GuestDisplayPhase {
    LOADING,
    WAITING,
    ACTIVE,
    STALE,
    SIGNAL_LOST,
    DISPLAY_EXPIRED,
    INVALID_LINK,
    LINK_ENDED,
    CONNECTION_PROBLEM,
}

sealed interface GuestSpeedDisplay {
    data class Available(val kilometersPerHour: Int) : GuestSpeedDisplay
    data object Unavailable : GuestSpeedDisplay
}

data class GuestDisplayState(
    val phase: GuestDisplayPhase = GuestDisplayPhase.LOADING,
    val latest: PublicShareFix? = null,
    val speed: GuestSpeedDisplay = GuestSpeedDisplay.Unavailable,
    val lastUpdate: Instant? = null,
    val displayDeadlineElapsedMillis: Long? = null,
)

/**
 * Pure state model for the signed-out viewer. Elapsed realtime is used for display-window timers so
 * wall-clock changes cannot keep a coordinate visible after `viewValidUntil`.
 * This client marks a fix stale at 30 seconds, withholds speed from stale fixes, and withholds speed
 * when horizontal accuracy is worse than 50 m. The server's signal-lost cutoff remains 90 seconds.
 *
 * The UI calls [accept] for every HTTP outcome and [snapshot] while waiting between polls/resumes.
 */
class GuestShareStateMachine(
    private val displayWindowCap: Duration = MAX_DISPLAY_WINDOW,
    private val staleAfter: Duration = FIX_STALE_AFTER,
    private val signalLostAfter: Duration = SIGNAL_LOST_AFTER,
    private val maxAccurateSpeedFixMeters: Double = MAX_ACCURACY_FOR_SPEED_METERS,
) {
    private var state = GuestDisplayState()
    private var terminal = false
    private var acceptedAtElapsedMillis: Long? = null
    private var fixAgeAtServerMillis: Long? = null
    private var displayDeadlineElapsedMillis: Long? = null

    fun accept(
        outcome: GuestShareReadOutcome,
        requestStartedElapsedMillis: Long,
        responseReceivedElapsedMillis: Long,
    ): GuestDisplayState {
        if (terminal) return state
        when (outcome) {
            GuestShareReadOutcome.NotFound -> {
                terminal = true
                clear(GuestDisplayPhase.INVALID_LINK)
            }
            GuestShareReadOutcome.Gone -> {
                terminal = true
                clear(GuestDisplayPhase.LINK_ENDED)
            }
            GuestShareReadOutcome.TransientFailure -> {
                if (state.latest == null && state.phase == GuestDisplayPhase.LOADING) {
                    state = state.copy(phase = GuestDisplayPhase.CONNECTION_PROBLEM)
                }
            }
            is GuestShareReadOutcome.Success -> acceptResponse(
                outcome.response,
                requestStartedElapsedMillis,
                responseReceivedElapsedMillis,
            )
        }
        return snapshot(responseReceivedElapsedMillis)
    }

    fun snapshot(nowElapsedMillis: Long): GuestDisplayState {
        if (terminal) return state
        val deadline = displayDeadlineElapsedMillis
        if (deadline != null && nowElapsedMillis >= deadline) {
            clear(GuestDisplayPhase.DISPLAY_EXPIRED)
            return state
        }

        val latest = state.latest ?: return state.copy(displayDeadlineElapsedMillis = deadline)
        val receivedAt = acceptedAtElapsedMillis ?: return state
        val elapsedSinceResponse = (nowElapsedMillis - receivedAt).coerceAtLeast(0)
        val ageMillis = (fixAgeAtServerMillis ?: 0L) + elapsedSinceResponse
        if (ageMillis >= signalLostAfter.toMillis()) {
            clear(GuestDisplayPhase.SIGNAL_LOST)
            return state
        }

        val stale = ageMillis >= staleAfter.toMillis()
        state = state.copy(
            phase = if (stale) GuestDisplayPhase.STALE else GuestDisplayPhase.ACTIVE,
            speed = speedDisplay(latest, stale, maxAccurateSpeedFixMeters),
            displayDeadlineElapsedMillis = deadline,
        )
        return state
    }

    /** Hide cached location before a resumed viewer makes another authenticated read. */
    fun requireRevalidation(): GuestDisplayState {
        if (!terminal) clear(GuestDisplayPhase.LOADING)
        return state
    }

    private fun acceptResponse(
        response: PublicShareResponse,
        requestStartedElapsedMillis: Long,
        responseReceivedElapsedMillis: Long,
    ) {
        val responseWindowMillis = minOf(
            Duration.between(response.serverTime, response.viewValidUntil).toMillis(),
            displayWindowCap.toMillis(),
        ).coerceAtLeast(0)
        val requestElapsedMillis = (responseReceivedElapsedMillis - requestStartedElapsedMillis).coerceAtLeast(0)
        val remainingMillis = (responseWindowMillis - requestElapsedMillis).coerceAtLeast(0)
        displayDeadlineElapsedMillis = responseReceivedElapsedMillis + remainingMillis
        acceptedAtElapsedMillis = responseReceivedElapsedMillis
        fixAgeAtServerMillis = null

        if (remainingMillis == 0L) {
            clear(GuestDisplayPhase.DISPLAY_EXPIRED)
            return
        }
        when (response.status) {
            GuestShareStatus.WAITING -> {
                state = GuestDisplayState(
                    phase = GuestDisplayPhase.WAITING,
                    displayDeadlineElapsedMillis = displayDeadlineElapsedMillis,
                )
            }
            GuestShareStatus.SIGNAL_LOST -> clear(GuestDisplayPhase.SIGNAL_LOST)
            GuestShareStatus.ACTIVE -> {
                val fix = response.latest
                if (fix == null || !fix.isDisplayableAt(response.serverTime)) {
                    state = GuestDisplayState(
                        phase = GuestDisplayPhase.WAITING,
                        displayDeadlineElapsedMillis = displayDeadlineElapsedMillis,
                    )
                    return
                }
                val age = Duration.between(fix.capturedAt, response.serverTime).toMillis()
                if (age >= signalLostAfter.toMillis()) {
                    clear(GuestDisplayPhase.SIGNAL_LOST)
                    return
                }
                fixAgeAtServerMillis = age.coerceAtLeast(0)
                val stale = age >= staleAfter.toMillis()
                state = GuestDisplayState(
                    phase = if (stale) GuestDisplayPhase.STALE else GuestDisplayPhase.ACTIVE,
                    latest = fix,
                    speed = speedDisplay(fix, stale, maxAccurateSpeedFixMeters),
                    lastUpdate = fix.capturedAt,
                    displayDeadlineElapsedMillis = displayDeadlineElapsedMillis,
                )
            }
        }
    }

    private fun clear(phase: GuestDisplayPhase) {
        state = GuestDisplayState(
            phase = phase,
            latest = null,
            speed = GuestSpeedDisplay.Unavailable,
            lastUpdate = null,
            displayDeadlineElapsedMillis = null,
        )
        acceptedAtElapsedMillis = null
        fixAgeAtServerMillis = null
        displayDeadlineElapsedMillis = null
    }

    private fun PublicShareFix.isDisplayableAt(serverTime: Instant): Boolean =
        latitude.isFinite() && latitude in -90.0..90.0 &&
            longitude.isFinite() && longitude in -180.0..180.0 &&
            accuracyMeters.isFinite() && accuracyMeters >= 0.0 &&
            !capturedAt.isAfter(serverTime)

    companion object {
        val MAX_DISPLAY_WINDOW: Duration = Duration.ofSeconds(30)
        val FIX_STALE_AFTER: Duration = Duration.ofSeconds(30)
        val SIGNAL_LOST_AFTER: Duration = Duration.ofSeconds(90)
        const val MAX_ACCURACY_FOR_SPEED_METERS = 50.0

        fun speedDisplay(
            fix: PublicShareFix,
            stale: Boolean,
            maxAccurateFixMeters: Double = MAX_ACCURACY_FOR_SPEED_METERS,
        ): GuestSpeedDisplay {
            val speedMps = fix.speedMps
            if (stale || fix.accuracyMeters > maxAccurateFixMeters || speedMps == null ||
                !speedMps.isFinite() || speedMps < 0.0
            ) {
                return GuestSpeedDisplay.Unavailable
            }
            return GuestSpeedDisplay.Available((speedMps * METERS_PER_SECOND_TO_KMH).roundToInt())
        }

        private const val METERS_PER_SECOND_TO_KMH = 3.6
    }
}
