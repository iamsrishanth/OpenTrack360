package me.srishanth.opentrack360.ui.pets

import org.junit.Assert.assertEquals
import org.junit.Test

class PetActivityMappingTest {
    @Test
    fun latestCompletionUsesRoutineIdAndCompletedAt() {
        val completions = listOf(
            PetActivityUi(
                routineId = "walk",
                completedByMemberId = "member-1",
                completedAtIso = "2026-11-26T08:00:00Z",
                routineTitle = "Morning walk",
                completedAtLabel = "8:00 AM",
                memberLabel = "Aarav",
            ),
            PetActivityUi(
                routineId = "feed",
                completedByMemberId = "member-2",
                completedAtIso = "2026-11-26T09:00:00Z",
                routineTitle = "Dinner",
                completedAtLabel = "9:00 AM",
                memberLabel = "Saanvi",
            ),
            PetActivityUi(
                routineId = "walk",
                completedByMemberId = "member-2",
                completedAtIso = "2026-11-26T10:00:00Z",
                routineTitle = "Morning walk",
                completedAtLabel = "10:00 AM",
                memberLabel = "Saanvi",
            ),
        )

        assertEquals(
            mapOf("walk" to "10:00 AM", "feed" to "9:00 AM"),
            latestPetRoutineCompletionLabels(completions),
        )
    }

    @Test
    fun invalidOrUnlinkedCompletionDoesNotInventRoutineCompletion() {
        val completions = listOf(
            PetActivityUi(
                routineId = "",
                completedByMemberId = "member-1",
                completedAtIso = "2026-11-26T08:00:00Z",
                routineTitle = "Care routine",
                completedAtLabel = "8:00 AM",
                memberLabel = null,
            ),
            PetActivityUi(
                routineId = "walk",
                completedByMemberId = "member-1",
                completedAtIso = "not-a-timestamp",
                routineTitle = "Walk",
                completedAtLabel = "",
                memberLabel = null,
            ),
        )

        assertEquals(emptyMap<String, String>(), latestPetRoutineCompletionLabels(completions))
    }
}
