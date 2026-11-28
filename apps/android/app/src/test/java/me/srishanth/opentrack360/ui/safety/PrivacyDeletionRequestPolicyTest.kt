package me.srishanth.opentrack360.ui.safety

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class PrivacyDeletionRequestPolicyTest {
    @Test
    fun requestUsesSelectedReasonDetailsAndExactConfirmationPhrase() {
        val request = PrivacyDeletionRequestPolicy.build("other", "I no longer use this account.")

        requireNotNull(request)
        assertEquals("other", request.reason)
        assertEquals("I no longer use this account.", request.details)
        assertEquals("DELETE MY ACCOUNT", request.confirmationPhrase)
    }

    @Test
    fun detailsAreCappedAndUnsupportedReasonsAreRejected() {
        val request = PrivacyDeletionRequestPolicy.build("privacy_concern", "x".repeat(501))

        requireNotNull(request)
        assertEquals(500, request.details.length)
        assertNull(PrivacyDeletionRequestPolicy.build("invalid", "details"))
    }
}
