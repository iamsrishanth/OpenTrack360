package me.srishanth.opentrack360.sharing

import android.content.Intent
import java.net.URI
import java.util.Base64
import java.util.Locale
import java.util.UUID

/** Transient, redacted authorization for one owner-approved pet recovery card. */
class PetRecoveryRequest internal constructor(
    val petId: String,
    private val secret: String,
) {
    fun authorizationHeader(): String = "Bearer $secret"

    override fun toString(): String = "PetRecoveryRequest(petId=$petId, secret=<redacted>)"
}

/** Accepts only https://track.srishanth.me/p/{UUID}#{32-byte-base64url-secret}. */
object PetRecoveryLinkParser {
    private const val EXPECTED_AUTHORITY = "track.srishanth.me"
    private val pathPattern = Regex("^/p/([0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12})$")
    private val secretPattern = Regex("^[A-Za-z0-9_-]{43}$")

    fun fromIntent(intent: Intent?): PetRecoveryRequest? {
        if (intent?.action != Intent.ACTION_VIEW) return null
        return parse(intent.dataString)
    }

    fun parse(rawUri: String?): PetRecoveryRequest? {
        if (rawUri.isNullOrBlank()) return null
        val uri = runCatching { URI(rawUri) }.getOrNull() ?: return null
        if (!uri.scheme.equals("https", ignoreCase = true)) return null
        if (!uri.rawAuthority.equals(EXPECTED_AUTHORITY, ignoreCase = true)) return null
        if (uri.rawQuery != null) return null
        val path = pathPattern.matchEntire(uri.rawPath.orEmpty()) ?: return null
        val petId = runCatching { UUID.fromString(path.groupValues[1]).toString().lowercase(Locale.ROOT) }.getOrNull() ?: return null
        val secret = uri.rawFragment.orEmpty()
        if (!secretPattern.matches(secret)) return null
        val bytes = runCatching { Base64.getUrlDecoder().decode(secret) }.getOrNull() ?: return null
        if (bytes.size != 32 || Base64.getUrlEncoder().withoutPadding().encodeToString(bytes) != secret) return null
        return PetRecoveryRequest(petId, secret)
    }
}
