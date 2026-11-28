package me.srishanth.opentrack360.sharing

import me.srishanth.opentrack360.core.api.authorizationHeaderForRequest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Base64

class PetRecoveryLinkParserTest {
    @Test
    fun acceptsExactPetRecoveryOriginAndKeepsSecretInAuthorizationHeader() {
        val request = requireNotNull(PetRecoveryLinkParser.parse(validLink))

        assertEquals(petId, request.petId)
        assertTrue(
            "pet recovery authorization should match the fake test credential",
            request.authorizationHeader() == "Bearer $secret",
        )
        assertTrue(
            "pet recovery authorization fallback should match the fake test credential",
            authorizationHeaderForRequest(bearer = null, authorizationHeader = request.authorizationHeader()) ==
                "Bearer $secret",
        )

        // The production viewer builds its API path from the public pet ID only; the fragment
        // credential is carried by the Authorization header and never appears in the URL.
        val apiUrl = java.net.URI("https://track.srishanth.me/api/v1/public/pets/${request.petId}")
        assertNull(apiUrl.rawQuery)
        assertNull(apiUrl.rawFragment)
        assertFalse(apiUrl.toString().contains(secret))
        assertFalse(request.toString().contains(secret))
    }

    @Test
    fun rejectsUntrustedOriginPathQueryAndInvalidSecrets() {
        val invalidUris = listOf(
            "http://track.srishanth.me/p/$petId#$secret",
            "https://example.com/p/$petId#$secret",
            "https://track.srishanth.me:443/p/$petId#$secret",
            "https://track.srishanth.me/p/$petId?secret=$secret#$secret",
            "https://track.srishanth.me/p/$petId/extra#$secret",
            "https://track.srishanth.me/p/$petId#short",
            "https://track.srishanth.me/p/$petId#${"A".repeat(42)}=",
            "https://track.srishanth.me/p/not-a-uuid#$secret",
        )
        invalidUris.forEachIndexed { index, uri ->
            assertNull("Unexpected pet recovery parse for invalid case #$index", PetRecoveryLinkParser.parse(uri))
        }
    }

    companion object {
        private const val petId = "737fc4af-7735-46d6-98f4-0ee6db2cbb52"
        // Base64url of the explicitly fake string TEST_ONLY_FAKE_SECRET_1234567xyz.
        private val secret = Base64.getUrlEncoder().withoutPadding()
            .encodeToString("TEST_ONLY_FAKE_SECRET_1234567xyz".toByteArray(Charsets.US_ASCII))
        private val validLink = "https://track.srishanth.me/p/$petId#$secret"
    }
}
