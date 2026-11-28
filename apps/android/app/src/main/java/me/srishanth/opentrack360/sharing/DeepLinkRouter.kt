package me.srishanth.opentrack360.sharing

import android.content.Intent
import me.srishanth.opentrack360.core.auth.OAuthCallbackParser
import me.srishanth.opentrack360.core.auth.OAuthCallbackRequest

/**
 * Entry points for the activity to call from onCreate (cold start) and onNewIntent (warm start).
 * A valid guest link only returns a read-only request; these functions never touch sender tracking.
 */
object DeepLinkRouter {
    /**
     * The supported App Link payloads. Credentials remain only in these validated,
     * transient request objects; callers must not persist this value.
     */
    data class IncomingRequests(
        val guestShare: GuestShareRequest? = null,
        val petRecovery: PetRecoveryRequest? = null,
        val circleInvite: CircleInviteRequest? = null,
        val oauthCallback: OAuthCallbackRequest? = null,
    )

    /**
     * Routes a VIEW URI and clears its source as soon as the result has been handed to the
     * transient-state callback. This is shared by cold-start and onNewIntent entry points.
     * Any supplied Intent URI is cleared, including for non-VIEW intents and malformed links,
     * so credentials cannot remain in the Activity's retained launch Intent. Only VIEW intents
     * are eligible for routing.
     */
    fun routeAndClear(
        action: String?,
        rawUri: String?,
        onRequests: (IncomingRequests) -> Unit,
        clearRetainedUri: () -> Unit,
    ) {
        val requests = if (action == Intent.ACTION_VIEW) {
            IncomingRequests(
                guestShare = (GuestShareLinkParser.parse(rawUri) as? GuestLinkParseResult.Valid)?.request,
                petRecovery = PetRecoveryLinkParser.parse(rawUri),
                circleInvite = CircleInviteLinkParser.parse(rawUri),
                oauthCallback = OAuthCallbackParser.parse(rawUri),
            )
        } else {
            IncomingRequests()
        }

        try {
            onRequests(requests)
        } finally {
            clearRetainedUri()
        }
    }

    fun fromColdStart(intent: Intent?): GuestShareRequest? = guestRequestFrom(intent)

    fun fromNewIntent(intent: Intent?): GuestShareRequest? = guestRequestFrom(intent)

    fun routeColdStart(intent: Intent?, onGuestRequest: (GuestShareRequest) -> Unit): Boolean =
        fromColdStart(intent)?.let { onGuestRequest(it); true } ?: false

    fun routeNewIntent(intent: Intent?, onGuestRequest: (GuestShareRequest) -> Unit): Boolean =
        fromNewIntent(intent)?.let { onGuestRequest(it); true } ?: false

    private fun guestRequestFrom(intent: Intent?): GuestShareRequest? {
        if (intent?.action != Intent.ACTION_VIEW) return null
        val result = GuestShareLinkParser.parse(intent.dataString)
        return (result as? GuestLinkParseResult.Valid)?.request
    }
}

/** Validated, transient text payload used for Android Sharesheet and clipboard actions. */
class ShareSheetPayload private constructor(val text: String) {
    override fun toString(): String = "ShareSheetPayload(text=<secret-bearing link redacted>)"

    companion object {
        fun fromCreatedLink(url: String): ShareSheetPayload? =
            when (GuestShareLinkParser.parse(url)) {
                is GuestLinkParseResult.Valid -> ShareSheetPayload(url)
                is GuestLinkParseResult.Invalid -> null
            }
    }
}
