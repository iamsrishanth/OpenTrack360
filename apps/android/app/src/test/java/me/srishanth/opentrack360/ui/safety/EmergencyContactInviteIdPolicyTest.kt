package me.srishanth.opentrack360.ui.safety

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class EmergencyContactInviteIdPolicyTest {
    @Test
    fun normalizesUuidWithoutTreatingItAsASecret() {
        assertEquals(
            "4dd0921f-9a43-46a1-9d4c-2c8100bc0e13",
            EmergencyContactInviteIdPolicy.normalize(" 4DD0921F-9A43-46A1-9D4C-2C8100BC0E13 "),
        )
    }

    @Test
    fun rejectsNonCanonicalOrMalformedIds() {
        assertNull(EmergencyContactInviteIdPolicy.normalize(""))
        assertNull(EmergencyContactInviteIdPolicy.normalize("4dd0921f9a4346a19d4c2c8100bc0e13"))
        assertNull(EmergencyContactInviteIdPolicy.normalize("1-1-1-1-1"))
    }
}
