package me.srishanth.opentrack360.sharing

import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant

class ShareSheetPayloadTest {
    @Test
    fun androidSharesheetPayloadPreservesTheEntireFragmentLink() {
        val fullLink = "https://track.srishanth.me/s/$shareId#$secret"
        val payload = ShareSheetPayload.fromCreatedLink(fullLink)

        assertNotNull(payload)
        assertTrue("Sharesheet payload should preserve the complete fake fragment link", payload?.text == fullLink)
        assertTrue(payload?.text?.contains("#$secret") == true)
        assertFalse(payload.toString().contains(secret))
    }

    @Test
    fun payloadRejectsASecretlessOrMalformedLink() {
        assertNull(ShareSheetPayload.fromCreatedLink("https://track.srishanth.me/s/$shareId"))
        assertNull(ShareSheetPayload.fromCreatedLink("https://evil.example/s/$shareId#$secret"))
    }

    @Test
    fun createdShareDoesNotRevealTheOneTimeUrlInDebugString() {
        val link = "https://track.srishanth.me/s/$shareId#$secret"
        val created = CreatedShare(
            id = shareId,
            url = link,
            mode = ShareMode.DRIVE_END,
            status = SenderShareStatus.ACTIVE,
            startedAt = Instant.parse("2026-11-26T10:00:00Z"),
            createdAt = Instant.parse("2026-11-26T10:00:00Z"),
            expiresAt = null,
            endCondition = "When a confirmed drive ends",
            tripId = "71717171-7171-4717-8717-717171717171",
            tripStartedAt = Instant.parse("2026-11-26T09:30:00Z"),
            tripSessionMatchesCurrent = true,
            sharingEpoch = 1,
        )
        assertFalse(created.toString().contains(secret))
        assertFalse(created.toString().contains(link))
    }

    companion object {
        private const val shareId = "f28bd481-5c3c-4ea1-9fa1-0384703c23b0"
        // Base64url of the explicitly fake string TEST_ONLY_FAKE_SECRET_1234567xyz.
        private val secret = java.util.Base64.getUrlEncoder().withoutPadding()
            .encodeToString("TEST_ONLY_FAKE_SECRET_1234567xyz".toByteArray(Charsets.US_ASCII))
    }
}
