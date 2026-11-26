package me.srishanth.opentrack360.sharing

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant

class GuestShareStateMachineTest {
    @Test
    fun waitingAndSignalLostResponsesNeverExposeCoordinates() {
        val machine = GuestShareStateMachine()
        val waiting = machine.accept(
            GuestShareReadOutcome.Success(response(GuestShareStatus.WAITING, latest = null)),
            requestStartedElapsedMillis = 1_000,
            responseReceivedElapsedMillis = 1_100,
        )
        assertEquals(GuestDisplayPhase.WAITING, waiting.phase)
        assertNull(waiting.latest)

        val signalLost = machine.accept(
            GuestShareReadOutcome.Success(response(GuestShareStatus.SIGNAL_LOST, latest = null)),
            requestStartedElapsedMillis = 11_000,
            responseReceivedElapsedMillis = 11_100,
        )
        assertEquals(GuestDisplayPhase.SIGNAL_LOST, signalLost.phase)
        assertNull(signalLost.latest)
    }

    @Test
    fun staleLocationRemainsVisibleButSpeedIsWithheld() {
        val machine = GuestShareStateMachine()
        val state = machine.accept(
            GuestShareReadOutcome.Success(
                response(
                    GuestShareStatus.ACTIVE,
                    latest = fix(capturedAt = serverTime.minusSeconds(40), speedMps = 12.0),
                ),
            ),
            requestStartedElapsedMillis = 2_000,
            responseReceivedElapsedMillis = 2_100,
        )

        assertEquals(GuestDisplayPhase.STALE, state.phase)
        assertEquals(fix(capturedAt = serverTime.minusSeconds(40), speedMps = 12.0), state.latest)
        assertEquals(GuestSpeedDisplay.Unavailable, state.speed)
    }

    @Test
    fun zeroSpeedIsMeasuredZeroAndNullSpeedIsUnavailable() {
        val zero = GuestShareStateMachine.speedDisplay(fix(capturedAt = serverTime, speedMps = 0.0), stale = false)
        val unknown = GuestShareStateMachine.speedDisplay(fix(capturedAt = serverTime, speedMps = null), stale = false)

        assertEquals(GuestSpeedDisplay.Available(0), zero)
        assertEquals(GuestSpeedDisplay.Unavailable, unknown)
        assertEquals(GuestSpeedDisplay.Unavailable, GuestShareStateMachine.speedDisplay(
            fix(capturedAt = serverTime, speedMps = 7.0, accuracyMeters = 80.0),
            stale = false,
        ))
    }

    @Test
    fun terminal404And410ClearAnAlreadyVisibleLocationImmediately() {
        listOf(
            GuestShareReadOutcome.NotFound to GuestDisplayPhase.INVALID_LINK,
            GuestShareReadOutcome.Gone to GuestDisplayPhase.LINK_ENDED,
        ).forEach { (terminal, expectedPhase) ->
            val machine = GuestShareStateMachine()
            val active = machine.accept(
                GuestShareReadOutcome.Success(
                    response(GuestShareStatus.ACTIVE, latest = fix(capturedAt = serverTime, speedMps = 4.0)),
                ),
                requestStartedElapsedMillis = 1_000,
                responseReceivedElapsedMillis = 1_100,
            )
            assertTrue(active.latest != null)

            val state = machine.accept(terminal, 2_000, 2_010)
            assertEquals(expectedPhase, state.phase)
            assertNull(state.latest)
            assertNull(state.lastUpdate)
            assertEquals(GuestSpeedDisplay.Unavailable, state.speed)
        }
    }

    @Test
    fun markerClearsWhenViewWindowExpiresOrAppMustRevalidate() {
        val machine = GuestShareStateMachine()
        val active = machine.accept(
            GuestShareReadOutcome.Success(
                response(GuestShareStatus.ACTIVE, latest = fix(capturedAt = serverTime, speedMps = 5.0)),
            ),
            requestStartedElapsedMillis = 1_000,
            responseReceivedElapsedMillis = 1_100,
        )
        assertTrue(active.latest != null)
        val expired = machine.snapshot(nowElapsedMillis = 31_000)
        assertEquals(GuestDisplayPhase.DISPLAY_EXPIRED, expired.phase)
        assertNull(expired.latest)
        assertEquals(GuestSpeedDisplay.Unavailable, expired.speed)

        val resumedMachine = GuestShareStateMachine()
        resumedMachine.accept(
            GuestShareReadOutcome.Success(
                response(GuestShareStatus.ACTIVE, latest = fix(capturedAt = serverTime, speedMps = 5.0)),
            ),
            requestStartedElapsedMillis = 1_000,
            responseReceivedElapsedMillis = 1_100,
        )
        val revalidating = resumedMachine.requireRevalidation()
        assertEquals(GuestDisplayPhase.LOADING, revalidating.phase)
        assertNull(revalidating.latest)
    }

    @Test
    fun successfulReadNeverGrantsMoreThanThirtySeconds() {
        val machine = GuestShareStateMachine()
        val state = machine.accept(
            GuestShareReadOutcome.Success(
                response(
                    GuestShareStatus.ACTIVE,
                    latest = fix(capturedAt = serverTime, speedMps = 1.0),
                    viewValidUntil = serverTime.plusSeconds(90),
                ),
            ),
            requestStartedElapsedMillis = 1_000,
            responseReceivedElapsedMillis = 1_100,
        )
        assertEquals(31_000L, state.displayDeadlineElapsedMillis)
    }

    @Test
    fun signalIsLostAtNinetySecondsAndFutureFixIsDiscarded() {
        val machine = GuestShareStateMachine()
        val tooOld = machine.accept(
            GuestShareReadOutcome.Success(
                response(GuestShareStatus.ACTIVE, latest = fix(capturedAt = serverTime.minusSeconds(90), speedMps = 0.0)),
            ),
            requestStartedElapsedMillis = 1_000,
            responseReceivedElapsedMillis = 1_100,
        )
        assertEquals(GuestDisplayPhase.SIGNAL_LOST, tooOld.phase)
        assertNull(tooOld.latest)

        val future = GuestShareStateMachine().accept(
            GuestShareReadOutcome.Success(
                response(GuestShareStatus.ACTIVE, latest = fix(capturedAt = serverTime.plusSeconds(1), speedMps = 0.0)),
            ),
            requestStartedElapsedMillis = 1_000,
            responseReceivedElapsedMillis = 1_100,
        )
        assertEquals(GuestDisplayPhase.WAITING, future.phase)
        assertNull(future.latest)
    }

    private fun response(
        status: GuestShareStatus,
        latest: PublicShareFix?,
        viewValidUntil: Instant = serverTime.plusSeconds(30),
    ) = PublicShareResponse(status, serverTime, viewValidUntil, latest)

    private fun fix(
        capturedAt: Instant,
        speedMps: Double?,
        accuracyMeters: Double = 10.0,
    ) = PublicShareFix(
        latitude = 19.076,
        longitude = 72.8777,
        accuracyMeters = accuracyMeters,
        capturedAt = capturedAt,
        speedMps = speedMps,
    )

    companion object {
        private val serverTime = Instant.parse("2026-11-26T10:00:00Z")
    }
}
