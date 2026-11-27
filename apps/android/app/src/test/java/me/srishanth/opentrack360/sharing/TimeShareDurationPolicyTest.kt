package me.srishanth.opentrack360.sharing

import java.time.Instant
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class TimeShareDurationPolicyTest {
    private val now = Instant.parse("2026-11-27T10:00:00Z")

    @Test
    fun acceptsThirtyDaysAndResolvesTheExpiry() {
        assertEquals(
            now.plusSeconds(30L * 24 * 60 * 60),
            TimeShareDurationPolicy.resolve("43200", now),
        )
    }

    @Test
    fun rejectsDurationsAboveTheApiMaximum() {
        assertNull(TimeShareDurationPolicy.resolve("43201", now))
        assertNull(TimeShareDurationPolicy.resolve("99999", now))
    }

    @Test
    fun rejectsMissingMalformedAndNonPositiveDurations() {
        assertNull(TimeShareDurationPolicy.resolve("", now))
        assertNull(TimeShareDurationPolicy.resolve("one hour", now))
        assertNull(TimeShareDurationPolicy.resolve("0", now))
        assertNull(TimeShareDurationPolicy.resolve("-1", now))
    }
}
