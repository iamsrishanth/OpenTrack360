package me.srishanth.opentrack360.sharing

import android.content.Intent
import org.json.JSONObject
import java.net.URI

/** Transient bearer access for one invitation; the token is redacted from diagnostics. */
class CircleInviteRequest internal constructor(private val inviteToken: String) {
    fun acceptanceBody(): JSONObject = JSONObject().put("inviteToken", inviteToken)

    override fun toString(): String = "CircleInviteRequest(inviteToken=<redacted>)"
}

/** Accepts only https://track.srishanth.me/i#{inviteToken}. */
object CircleInviteLinkParser {
    private const val EXPECTED_AUTHORITY = "track.srishanth.me"
    private val tokenPattern = Regex("^[A-Za-z0-9_-]{32,512}$")

    fun fromIntent(intent: Intent?): CircleInviteRequest? {
        if (intent?.action != Intent.ACTION_VIEW) return null
        return parse(intent.dataString)
    }

    fun parse(rawUri: String?): CircleInviteRequest? {
        if (rawUri.isNullOrBlank() || rawUri.length > 4_096) return null
        val uri = runCatching { URI(rawUri) }.getOrNull() ?: return null
        if (!uri.scheme.equals("https", ignoreCase = true)) return null
        if (!uri.rawAuthority.equals(EXPECTED_AUTHORITY, ignoreCase = true)) return null
        if (uri.rawPath != "/i" || uri.rawQuery != null) return null
        val token = uri.rawFragment.orEmpty()
        if (!tokenPattern.matches(token)) return null
        return CircleInviteRequest(token)
    }
}
