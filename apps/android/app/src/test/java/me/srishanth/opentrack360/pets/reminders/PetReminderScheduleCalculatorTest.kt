package me.srishanth.opentrack360.pets.reminders

import java.time.Instant
import java.time.LocalTime
import java.time.ZoneId
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class PetReminderScheduleCalculatorTest {
    private val kolkata = ZoneId.of("Asia/Kolkata")

    @Test
    fun dailyReminderUsesLocalTimeAndMovesToNextDayWhenTodayHasPassed() {
        val recurrence = PetReminderRecurrence(
            frequency = PetReminderRecurrence.Frequency.DAILY,
            interval = 1,
            weekdays = emptySet(),
            localTime = LocalTime.of(9, 0),
            timeZone = kolkata,
        )

        val next = PetReminderScheduleCalculator.nextAfter(
            after = Instant.parse("2026-11-27T04:00:00Z"), // 09:30 in Kolkata
            anchor = Instant.parse("2026-11-27T00:00:00Z"),
            recurrence = recurrence,
        )

        assertEquals(Instant.parse("2026-11-28T03:30:00Z"), next)
    }

    @Test
    fun weeklyReminderUsesConfiguredWeekdayInItsTimezone() {
        val recurrence = PetReminderRecurrence(
            frequency = PetReminderRecurrence.Frequency.WEEKLY,
            interval = 1,
            weekdays = setOf(6), // Saturday
            localTime = LocalTime.of(10, 30),
            timeZone = kolkata,
        )

        val next = PetReminderScheduleCalculator.nextAfter(
            after = Instant.parse("2026-11-27T04:00:00Z"), // Friday 09:30 in Kolkata
            anchor = Instant.parse("2026-11-27T00:00:00Z"),
            recurrence = recurrence,
        )

        assertEquals(Instant.parse("2026-11-28T05:00:00Z"), next)
    }

    @Test
    fun biweeklyReminderKeepsTheAnchorWeekAndSkipsTheOffWeek() {
        val recurrence = PetReminderRecurrence(
            frequency = PetReminderRecurrence.Frequency.WEEKLY,
            interval = 2,
            weekdays = setOf(1), // Monday
            localTime = LocalTime.of(8, 15),
            timeZone = kolkata,
        )

        val next = PetReminderScheduleCalculator.nextAfter(
            after = Instant.parse("2026-12-01T00:00:00Z"),
            anchor = Instant.parse("2026-11-30T02:45:00Z"), // Monday 08:15 in Kolkata
            recurrence = recurrence,
        )

        assertEquals(Instant.parse("2026-12-14T02:45:00Z"), next)
        assertTrue(next!!.isAfter(Instant.parse("2026-12-01T00:00:00Z")))
    }
}
