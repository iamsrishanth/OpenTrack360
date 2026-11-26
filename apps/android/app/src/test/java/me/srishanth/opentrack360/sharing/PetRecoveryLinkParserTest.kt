package me.srishanth.opentrack360.sharing

import me.srishanth.opentrack360.core.api.authorizationHeaderForRequest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Test
import java.util.Base64

class PetRecoveryLinkParserTest {
    @Test
    fun acceptsExactPetRecoveryOriginAndKeepsSecretInAuthorizationHeader() {
        val request = requireNotNull(PetRecoveryLinkParser.parse(validLink))

        assertEquals(petId, request.petId)
        assertEquals("Bearer $secret", request.authorizationHeader())
        assertEquals(
            "Bearer $secret",
            authorizationHeaderForRequest(bearer = null, authorizationHeader = request.authorizationHeader()),
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
        assertNull(PetRecoveryLinkParser.parse("http://track.srishanth.me/p/$petId#$secret"))
        assertNull(PetRecoveryLinkParser.parse("https://example.com/p/$petId#$secret"))
        assertNull(PetRecoveryLinkParser.parse("https://track.srishanth.me:443/p/$petId#$secret"))
        assertNull(PetRecoveryLinkParser.parse("https://track.srishanth.me/p/$petId?secret=$secret#$secret"))
        assertNull(PetRecoveryLinkParser.parse("https://track.srishanth.me/p/$petId/extra#$secret"))
        assertNull(PetRecoveryLinkParser.parse("https://track.srishanth.me/p/$petId#short"))
        assertNull(PetRecoveryLinkParser.parse("https://track.srishanth.me/p/$petId#${"A".repeat(42)}="))
        assertNull(PetRecoveryLinkParser.parse("https://track.srishanth.me/p/not-a-uuid#$secret"))
    }

    companion object {
        private const val petId = "737fc4af-7735-46d6-98f4-0ee6db2cbb52"
        private val secret = Base64.getUrlEncoder().withoutPadding().encodeToString(ByteArray(32) { it.toByte() })
        private val validLink = "https://track.srishanth.me/p/$petId#$secret"
    }
}
