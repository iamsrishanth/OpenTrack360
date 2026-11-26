package me.srishanth.opentrack360.ui.safety

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SosAcknowledgementPolicyTest {
    @Test
    fun acknowledgementRequiresCurrentMemberToBeUnacknowledgedRecipient() {
        assertTrue(SosAcknowledgementPolicy.canAcknowledge("member-1", mapOf("member-1" to "submitted")))
        assertFalse(SosAcknowledgementPolicy.canAcknowledge("member-2", mapOf("member-1" to "submitted")))
        assertFalse(SosAcknowledgementPolicy.canAcknowledge("member-1", mapOf("member-1" to "acknowledged")))
        assertFalse(SosAcknowledgementPolicy.canAcknowledge("member-1", mapOf("member-1" to "failed")))
        assertFalse(SosAcknowledgementPolicy.canAcknowledge(null, mapOf("member-1" to "submitted")))
    }
}
