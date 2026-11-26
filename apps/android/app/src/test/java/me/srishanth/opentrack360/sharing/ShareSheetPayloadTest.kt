package me.srishanth.opentrack360.sharing

import org.junit.Assert.assertEquals
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
        assertEquals(fullLink, payload?.text)
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
        )
        assertFalse(created.toString().contains(secret))
        assertFalse(created.toString().contains(link))
    }

    companion object {
        private const val shareId = "f28bd481-5c3c-4ea1-9fa1-0384703c23b0"
        private const val secret = "AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA"
    }
}
