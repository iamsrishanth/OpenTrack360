package me.srishanth.opentrack360.sharing

import android.content.Intent

/**
 * Entry points for the activity to call from onCreate (cold start) and onNewIntent (warm start).
 * A valid guest link only returns a read-only request; these functions never touch sender tracking.
 */
object DeepLinkRouter {
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
