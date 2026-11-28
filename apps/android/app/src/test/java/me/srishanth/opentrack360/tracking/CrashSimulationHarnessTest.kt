package me.srishanth.opentrack360.tracking

import java.time.Instant
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class CrashSimulationHarnessTest {
    @Test
    fun sensorCrashDetectionRemainsDisabledAndSimulatedPromptCanBeCanceled() {
        val gate = PossibleCrashGate(validatedGateOpen = false)
        assertNull(gate.evaluateSensorEvidence(hasSuddenImpact = true))

        val harness = CrashSimulationHarness()
        val startedAt = Instant.parse("2026-11-26T10:00:00Z")
        val prompt = harness.simulate(startedAt)
        assertEquals(CrashCheckInState.WAITING_FOR_CHECK_IN, prompt.state)
        assertTrue(prompt.cancelUntil.isAfter(startedAt))
        assertEquals(CrashCheckInState.CANCELED, harness.cancel()?.state)
        assertEquals(CrashCheckInState.CANCELED, harness.advance(prompt.cancelUntil)?.state)
    }

    @Test
    fun simulatedPromptCanBeCheckedInWithoutAnyDispatchDependency() {
        val harness = CrashSimulationHarness()
        harness.simulate(Instant.parse("2026-11-26T10:00:00Z"))
        assertEquals(CrashCheckInState.CHECKED_IN, harness.checkIn()?.state)
    }
}

