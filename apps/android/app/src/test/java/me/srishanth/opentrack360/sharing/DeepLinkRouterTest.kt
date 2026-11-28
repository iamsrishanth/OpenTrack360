package me.srishanth.opentrack360.sharing

import android.content.Intent
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class DeepLinkRouterTest {
    @Test
    fun routesSharePetRecoveryAndInviteLinksToOnlyTheirMatchingRequest() {
        val share = route(shareUrl)
        assertTrue(share.guestShare != null)
        assertNull(share.petRecovery)
        assertNull(share.circleInvite)

        val pet = route(petUrl)
        assertNull(pet.guestShare)
        assertTrue(pet.petRecovery != null)
        assertNull(pet.circleInvite)

        val invite = route(inviteUrl)
        assertNull(invite.guestShare)
        assertNull(invite.petRecovery)
        assertTrue(invite.circleInvite != null)
    }

    @Test
    fun rejectsWrongOriginSchemeQueryAndNearMatchPathsAcrossAllRoutes() {
        val invalidUris = listOf(
            shareUrl.replace("https://", "http://"),
            shareUrl.replace("track.srishanth.me", "evil.example"),
            shareUrl.replace("/s/", "/share/"),
            petUrl.replace("/p/", "/pet/"),
            inviteUrl.replace("/i#", "/i/extra#"),
            inviteUrl.replace("/i#", "/s/$shareId?token=x#"),
        )

        invalidUris.forEachIndexed { index, uri ->
            val requests = route(uri)
            assertNull("Unexpected guest route for malformed case #$index", requests.guestShare)
            assertNull("Unexpected pet route for malformed case #$index", requests.petRecovery)
            assertNull("Unexpected invite route for malformed case #$index", requests.circleInvite)
        }
    }

    @Test
    fun clearsRetainedViewUriAfterValidatedRequestMovesToTransientState() {
        var retainedUri: String? = shareUrl
        var transientRequests: DeepLinkRouter.IncomingRequests? = null
        val order = mutableListOf<String>()

        DeepLinkRouter.routeAndClear(
            action = Intent.ACTION_VIEW,
            rawUri = retainedUri,
            onRequests = {
                transientRequests = it
                order += "captured"
            },
            clearRetainedUri = {
                retainedUri = null
                order += "cleared"
            },
        )

        assertTrue(transientRequests?.guestShare != null)
        assertNull(retainedUri)
        assertEquals(listOf("captured", "cleared"), order)
    }

    @Test
    fun clearsUnsupportedAndNonViewIntentUrisWithoutRoutingThem() {
        var retainedUri: String? = "https://track.srishanth.me/s/not-a-valid-link"
        var routed: DeepLinkRouter.IncomingRequests? = null

        DeepLinkRouter.routeAndClear(
            action = Intent.ACTION_VIEW,
            rawUri = retainedUri,
            onRequests = { routed = it },
            clearRetainedUri = { retainedUri = null },
        )
        assertEquals(DeepLinkRouter.IncomingRequests(), routed)
        assertNull(retainedUri)

        listOf(Intent.ACTION_MAIN, null).forEachIndexed { index, action ->
            retainedUri = shareUrl
            DeepLinkRouter.routeAndClear(
                action = action,
                rawUri = retainedUri,
                onRequests = { routed = it },
                clearRetainedUri = { retainedUri = null },
            )
            assertEquals(DeepLinkRouter.IncomingRequests(), routed)
            assertNull("retained URI should clear for non-VIEW intent case #$index", retainedUri)
        }
    }

    private fun route(uri: String): DeepLinkRouter.IncomingRequests {
        var result: DeepLinkRouter.IncomingRequests? = null
        DeepLinkRouter.routeAndClear(
            action = Intent.ACTION_VIEW,
            rawUri = uri,
            onRequests = { result = it },
            clearRetainedUri = {},
        )
        return requireNotNull(result)
    }

    private companion object {
        const val shareId = "f28bd481-5c3c-4ea1-9fa1-0384703c23b0"
        const val petId = "737fc4af-7735-46d6-98f4-0ee6db2cbb52"
        // Base64url of the explicitly fake string TEST_ONLY_FAKE_SECRET_1234567xyz.
        const val secret = "VEVTVF9PTkxZX0ZBS0VfU0VDUkVUXzEyMzQ1Njd4eXo"
        const val inviteToken = "test-only-fake-invite-token-for-unit-tests-123456789"
        const val shareUrl = "https://track.srishanth.me/s/$shareId#$secret"
        const val petUrl = "https://track.srishanth.me/p/$petId#$secret"
        const val inviteUrl = "https://track.srishanth.me/i#$inviteToken"
    }
}
