package me.srishanth.opentrack360.sharing

import me.srishanth.opentrack360.core.api.authorizationHeaderForRequest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class GuestShareLinkParserTest {
    @Test
    fun acceptsExactHttpsShareUrlAndReadsFragmentAsBearerOnly() {
        val result = GuestShareLinkParser.parse(validLink)

        assertTrue(result is GuestLinkParseResult.Valid)
        val request = (result as GuestLinkParseResult.Valid).request
        assertEquals(shareId, request.shareId)
        assertFalse(request.toString().contains(secret))

        val httpRequest = GuestShareHttpRequest.from(request)
        assertEquals("GET", httpRequest.method)
        assertEquals("https://track.srishanth.me/api/v1/public/shares/$shareId", httpRequest.url.toString())
        assertNull(httpRequest.url.rawQuery)
        assertNull(httpRequest.url.rawFragment)
        assertFalse(httpRequest.url.toString().contains(secret))
        assertTrue(
            "share authorization header should match the fake test credential",
            httpRequest.header("Authorization") == "Bearer $secret",
        )
        assertTrue(
            "share authorization fallback should match the fake test credential",
            authorizationHeaderForRequest(bearer = null, authorizationHeader = httpRequest.header("Authorization")) ==
                "Bearer $secret",
        )
        assertFalse(httpRequest.toString().contains(secret))
    }

    @Test
    fun rejectsWrongSchemesOriginsQueriesAndPaths() {
        assertInvalid("http://track.srishanth.me/s/$shareId#$secret", GuestLinkInvalidReason.WRONG_SCHEME)
        assertInvalid("https://evil.example/s/$shareId#$secret", GuestLinkInvalidReason.WRONG_ORIGIN)
        assertInvalid("https://track.srishanth.me:443/s/$shareId#$secret", GuestLinkInvalidReason.WRONG_ORIGIN)
        assertInvalid("https://track.srishanth.me/s/$shareId?secret=$secret#$secret", GuestLinkInvalidReason.QUERY_NOT_ALLOWED)
        assertInvalid("https://track.srishanth.me/s/$shareId/extra#$secret", GuestLinkInvalidReason.WRONG_PATH)
        assertInvalid("https://track.srishanth.me/s/$shareId%2F#$secret", GuestLinkInvalidReason.WRONG_PATH)
    }

    @Test
    fun rejectsMalformedIdsAndSecrets() {
        assertInvalid("https://track.srishanth.me/s/not-a-uuid#$secret", GuestLinkInvalidReason.WRONG_PATH)
        assertInvalid("https://track.srishanth.me/s/$shareId#short", GuestLinkInvalidReason.INVALID_SECRET)
        assertInvalid("https://track.srishanth.me/s/$shareId#${"A".repeat(42)}", GuestLinkInvalidReason.INVALID_SECRET)
        assertInvalid("https://track.srishanth.me/s/$shareId#${"A".repeat(42)}=", GuestLinkInvalidReason.INVALID_SECRET)
        assertInvalid("https://track.srishanth.me/s/$shareId#${"A".repeat(42)}+", GuestLinkInvalidReason.INVALID_SECRET)
    }

    @Test
    fun acceptedRequestKeepsSecretOutOfUrlEvenForTheContractFixture() {
        val request = (GuestShareLinkParser.parse(validLink) as GuestLinkParseResult.Valid).request
        val call = GuestShareHttpRequest.from(request)

        assertNotNull(call.header("authorization"))
        assertTrue(call.header("authorization")!!.endsWith(secret))
        assertFalse(call.url.toString().contains("#"))
        assertFalse(call.url.toString().contains(secret))
    }

    private fun assertInvalid(url: String, expectedReason: GuestLinkInvalidReason) {
        val result = GuestShareLinkParser.parse(url)
        assertEquals("unexpected result for guest rejection case $expectedReason", GuestLinkParseResult.Invalid(expectedReason), result)
    }

    companion object {
        private const val shareId = "f28bd481-5c3c-4ea1-9fa1-0384703c23b0"
        // Base64url of the explicitly fake string TEST_ONLY_FAKE_SECRET_1234567xyz.
        private val secret = java.util.Base64.getUrlEncoder().withoutPadding()
            .encodeToString("TEST_ONLY_FAKE_SECRET_1234567xyz".toByteArray(Charsets.US_ASCII))
        private val validLink = "https://track.srishanth.me/s/$shareId#$secret"
    }
}
