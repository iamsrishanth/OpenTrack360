package me.srishanth.opentrack360.sharing

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertEquals
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
        assertNull(CircleInviteLinkParser.parse("http://track.srishanth.me/i#$TOKEN"))
        assertNull(CircleInviteLinkParser.parse("https://attacker.example/i#$TOKEN"))
        assertNull(CircleInviteLinkParser.parse("https://track.srishanth.me/i/$TOKEN"))
        assertNull(CircleInviteLinkParser.parse("https://track.srishanth.me/i?inviteToken=$TOKEN#$TOKEN"))
        assertNull(CircleInviteLinkParser.parse("https://track.srishanth.me/i#short"))
        assertNull(CircleInviteLinkParser.parse("https://track.srishanth.me/i"))
    }

    private companion object {
        const val TOKEN = "fixture-invite-token-7gGtDRkAgYws-Sw2bHDzSDTd4tOWKQx1Jm2Ts4M5"
        const val INVITE_URL = "https://track.srishanth.me/i#fixture-invite-token-7gGtDRkAgYws-Sw2bHDzSDTd4tOWKQx1Jm2Ts4M5"
    }
}
