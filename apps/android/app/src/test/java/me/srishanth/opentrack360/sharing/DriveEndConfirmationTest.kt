package me.srishanth.opentrack360.sharing

import org.junit.Assert.assertTrue
import org.junit.Test

class DriveEndConfirmationTest {
    @Test
    fun driveEndShareRequestRequiresExplicitDriverConfirmation() {
        val failure = runCatching { CreateShareRequest.UntilDriveEnd(confirmedDrive = false) }.exceptionOrNull()

        assertTrue(failure is IllegalArgumentException)
        assertTrue(CreateShareRequest.UntilDriveEnd(confirmedDrive = true).confirmedDrive)
    }
}
