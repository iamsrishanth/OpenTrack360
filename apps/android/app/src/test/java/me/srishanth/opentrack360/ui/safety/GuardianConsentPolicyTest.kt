package me.srishanth.opentrack360.ui.safety

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class GuardianConsentPolicyTest {
    @Test
    fun acceptsOnlyValidChildIdUsernameAndRelationship() {
        assertTrue(
            GuardianConsentPolicy.canRequest(
                "4dd0921f-9a43-46a1-9d4c-2c8100bc0e13",
                "guardian_user",
                "parent",
            ),
        )
        assertFalse(GuardianConsentPolicy.canRequest("not-a-uuid", "guardian_user", "parent"))
        assertFalse(GuardianConsentPolicy.canRequest("4dd0921f-9a43-46a1-9d4c-2c8100bc0e13", "ab", "parent"))
        assertFalse(GuardianConsentPolicy.canRequest("4dd0921f-9a43-46a1-9d4c-2c8100bc0e13", "guardian_user", "other"))
    }

    @Test
    fun approvalIdMustBeCanonicalUuidAndIsNormalized() {
        assertEquals(
            "4dd0921f-9a43-46a1-9d4c-2c8100bc0e13",
            GuardianConsentPolicy.normalizeConsentId(" 4DD0921F-9A43-46A1-9D4C-2C8100BC0E13 "),
        )
        assertNull(GuardianConsentPolicy.normalizeConsentId("1-1-1-1-1"))
    }
}
