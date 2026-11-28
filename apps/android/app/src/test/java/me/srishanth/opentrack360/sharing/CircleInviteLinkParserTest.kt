package me.srishanth.opentrack360.sharing

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Test

class CircleInviteLinkParserTest {
    @Test
    fun acceptsExactHttpsFragmentLinkAndRedactsToken() {
        val request = requireNotNull(CircleInviteLinkParser.parse(INVITE_URL))
        assertEquals("CircleInviteRequest(inviteToken=<redacted>)", request.toString())
        assertFalse(request.toString().contains(TOKEN))
    }

    @Test
    fun rejectsWrongOriginPathQuerySchemeAndMalformedTokens() {
        val invalidUris = listOf(
            "http://track.srishanth.me/i#$TOKEN",
            "https://attacker.example/i#$TOKEN",
            "https://track.srishanth.me/i/$TOKEN",
            "https://track.srishanth.me/i?inviteToken=$TOKEN#$TOKEN",
            "https://track.srishanth.me/i#short",
            "https://track.srishanth.me/i",
        )
        invalidUris.forEachIndexed { index, uri ->
            assertNull("Unexpected invite parse for invalid case #$index", CircleInviteLinkParser.parse(uri))
        }
    }

    private companion object {
        const val TOKEN = "test-only-fake-invite-token-for-unit-tests-123456789"
        const val INVITE_URL = "https://track.srishanth.me/i#$TOKEN"
    }
}
