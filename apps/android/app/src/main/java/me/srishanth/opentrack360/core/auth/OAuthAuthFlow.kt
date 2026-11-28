package me.srishanth.opentrack360.core.auth

import org.json.JSONObject
import java.net.URI
import java.net.URLDecoder
import java.net.URLEncoder
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Base64

data class OAuthClientConfig(
    val issuer: String,
    val authorizationEndpoint: String,
    val tokenExchangeEndpoint: String,
    val refreshEndpoint: String,
    val clientId: String,
    val redirectUri: String,
    val responseType: String,
    val codeChallengeMethod: String,
    val scopes: List<String>,
) {
    override fun toString(): String = "OAuthClientConfig(clientId=<redacted>, endpoints=<validated>)"

    companion object {
        const val REDIRECT_URI = "https://track.srishanth.me/oauth/callback"

        fun parse(json: JSONObject, apiBaseUrl: String): OAuthClientConfig {
            val issuer = json.getString("issuer")
            val issuerUri = strictHttpsUri(issuer)
                ?: throw IllegalArgumentException("OAuth settings were invalid.")
            val authorizationEndpoint = json.getString("authorizationEndpoint")
            val authorizationUri = strictHttpsUri(authorizationEndpoint)
                ?: throw IllegalArgumentException("OAuth settings were invalid.")
            val issuerPath = issuerUri.rawPath.trimEnd('/')
            require(
                issuerUri.host.lowercase().endsWith(".supabase.co") &&
                    issuerPath.endsWith("/auth/v1") &&
                    authorizationUri.host.equals(issuerUri.host, ignoreCase = true) &&
                    authorizationUri.rawPath == "$issuerPath/oauth/authorize",
            ) { "OAuth settings were invalid." }

            val clientId = json.getString("clientId")
            require(clientId.length in 1..128 && clientId.none(Char::isISOControl) && clientId.none(Char::isWhitespace)) {
                "OAuth settings were invalid."
            }
            val redirectUri = json.getString("redirectUri")
            require(redirectUri == REDIRECT_URI) { "OAuth settings were invalid." }
            val responseType = json.getString("responseType")
            val codeChallengeMethod = json.getString("codeChallengeMethod")
            require(responseType == "code" && codeChallengeMethod == "S256") {
                "OAuth settings were invalid."
            }
            val scopesJson = json.getJSONArray("scopes")
            val scopes = (0 until scopesJson.length()).map { scopesJson.getString(it) }
            require(scopes == listOf("openid")) { "OAuth settings were invalid." }

            val tokenExchangeEndpoint = json.getString("tokenExchangeEndpoint")
            trustedApiEndpointPath(apiBaseUrl, tokenExchangeEndpoint, "auth/oauth/exchange")
            val refreshEndpoint = json.getString("refreshEndpoint")
            trustedApiEndpointPath(apiBaseUrl, refreshEndpoint, "auth/refresh")

            return OAuthClientConfig(
                issuer = issuer,
                authorizationEndpoint = authorizationEndpoint,
                tokenExchangeEndpoint = tokenExchangeEndpoint,
                refreshEndpoint = refreshEndpoint,
                clientId = clientId,
                redirectUri = redirectUri,
                responseType = responseType,
                codeChallengeMethod = codeChallengeMethod,
                scopes = scopes,
            )
        }

        internal fun strictHttpsUri(value: String): URI? = runCatching { URI(value) }.getOrNull()?.takeIf { uri ->
            uri.scheme.equals("https", ignoreCase = true) &&
                !uri.host.isNullOrBlank() &&
                uri.userInfo == null &&
                uri.port == -1 &&
                uri.rawQuery == null &&
                uri.rawFragment == null &&
                uri.rawPath.isNotBlank() &&
                !uri.rawPath.contains("..")
        }

        internal fun trustedApiEndpointPath(apiBaseUrl: String, endpoint: String, expectedPath: String): String {
            val base = strictHttpsUri(apiBaseUrl.trimEnd('/') + "/")
                ?: throw IllegalArgumentException("OAuth settings were invalid.")
            val target = strictHttpsUri(endpoint)
                ?: throw IllegalArgumentException("OAuth settings were invalid.")
            val basePath = base.rawPath.trimEnd('/')
            require(
                target.host.equals(base.host, ignoreCase = true) &&
                    target.port == base.port &&
                    target.rawPath == "$basePath/$expectedPath",
            ) { "OAuth settings were invalid." }
            return expectedPath
        }
    }
}

class PendingOAuthAuthorization(
    val state: String,
    val codeVerifier: String,
    val clientId: String,
    val tokenExchangeEndpoint: String,
    val refreshEndpoint: String,
    val redirectUri: String,
    val createdAtEpochMillis: Long,
) {
    override fun toString(): String = "PendingOAuthAuthorization(state=<redacted>, verifier=<redacted>)"
}

object OAuthPkce {
    private const val RANDOM_BYTES = 32
    private val verifierPattern = Regex("^[A-Za-z0-9_-]{43,128}$")

    fun newAuthorization(
        config: OAuthClientConfig,
        nowEpochMillis: Long = System.currentTimeMillis(),
        secureRandom: SecureRandom = SecureRandom(),
    ): PendingOAuthAuthorization {
        return PendingOAuthAuthorization(
            state = randomUrlSafe(secureRandom),
            codeVerifier = randomUrlSafe(secureRandom),
            clientId = config.clientId,
            tokenExchangeEndpoint = config.tokenExchangeEndpoint,
            refreshEndpoint = config.refreshEndpoint,
            redirectUri = config.redirectUri,
            createdAtEpochMillis = nowEpochMillis,
        )
    }

    fun codeChallenge(codeVerifier: String): String {
        require(verifierPattern.matches(codeVerifier)) { "OAuth settings were invalid." }
        val digest = MessageDigest.getInstance("SHA-256").digest(codeVerifier.toByteArray(StandardCharsets.US_ASCII))
        return Base64.getUrlEncoder().withoutPadding().encodeToString(digest)
    }

    fun authorizationUrl(config: OAuthClientConfig, pending: PendingOAuthAuthorization): String {
        require(pending.clientId == config.clientId && pending.redirectUri == config.redirectUri)
        val parameters = linkedMapOf(
            "client_id" to config.clientId,
            "redirect_uri" to config.redirectUri,
            "response_type" to config.responseType,
            "scope" to config.scopes.joinToString(" "),
            "state" to pending.state,
            "code_challenge" to codeChallenge(pending.codeVerifier),
            "code_challenge_method" to config.codeChallengeMethod,
        )
        return config.authorizationEndpoint + "?" + parameters.entries.joinToString("&") { (name, value) ->
            "${name.encodeQueryComponent()}=${value.encodeQueryComponent()}"
        }
    }

    private fun randomUrlSafe(random: SecureRandom): String {
        val bytes = ByteArray(RANDOM_BYTES)
        random.nextBytes(bytes)
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes)
    }

    private fun String.encodeQueryComponent(): String =
        URLEncoder.encode(this, StandardCharsets.UTF_8.name()).replace("+", "%20")
}

class OAuthCallbackRequest internal constructor(
    val code: String?,
    val state: String,
    val error: String?,
) {
    val isCancelled: Boolean get() = error != null

    override fun toString(): String = "OAuthCallbackRequest(code=<redacted>, state=<redacted>, error=${error != null})"
}

object OAuthCallbackParser {
    private const val EXPECTED_HOST = "track.srishanth.me"
    private const val EXPECTED_PATH = "/oauth/callback"
    private val statePattern = Regex("^[A-Za-z0-9_-]{43}$")
    private val allowedParameters = setOf("code", "state", "error", "error_description")

    fun parse(rawUri: String?): OAuthCallbackRequest? {
        if (rawUri.isNullOrBlank() || rawUri.length > 8_192) return null
        val uri = runCatching { URI(rawUri) }.getOrNull() ?: return null
        if (!uri.scheme.equals("https", ignoreCase = true) ||
            !uri.rawAuthority.equals(EXPECTED_HOST, ignoreCase = true) ||
            !uri.host.equals(EXPECTED_HOST, ignoreCase = true) ||
            uri.userInfo != null || uri.port != -1 || uri.rawPath != EXPECTED_PATH ||
            uri.rawFragment != null || uri.rawQuery.isNullOrBlank()
        ) return null

        val parameters = linkedMapOf<String, String>()
        for (component in uri.rawQuery.split('&')) {
            if (component.isBlank()) return null
            val separator = component.indexOf('=')
            if (separator <= 0) return null
            val name = decodeQueryComponent(component.substring(0, separator)) ?: return null
            val value = decodeQueryComponent(component.substring(separator + 1)) ?: return null
            if (name !in allowedParameters || parameters.put(name, value) != null) return null
        }

        val state = parameters["state"] ?: return null
        if (!statePattern.matches(state)) return null
        val code = parameters["code"]
        val error = parameters["error"]
        val description = parameters["error_description"]
        if ((code.isNullOrBlank() == error.isNullOrBlank()) ||
            (code != null && (code.length > 4_096 || code.any { it.isISOControl() || it.isWhitespace() })) ||
            (error != null && (error.length > 128 || error.any(Char::isISOControl))) ||
            (description != null && (error == null || description.length > 512))
        ) return null

        return OAuthCallbackRequest(code = code, state = state, error = error)
    }

    private fun decodeQueryComponent(value: String): String? =
        runCatching { URLDecoder.decode(value, StandardCharsets.UTF_8.name()) }.getOrNull()
}

sealed class OAuthCallbackDecision {
    class Exchange(val code: String, val pending: PendingOAuthAuthorization) : OAuthCallbackDecision() {
        override fun toString(): String = "OAuthCallbackDecision.Exchange(code=<redacted>, pending=<redacted>)"
    }

    data object Cancelled : OAuthCallbackDecision()
    data object Invalid : OAuthCallbackDecision()
}

object OAuthCallbackPolicy {
    const val MAX_AGE_MILLIS = 10 * 60 * 1_000L
    private const val FUTURE_CLOCK_SKEW_MILLIS = 30_000L

    fun validate(
        callback: OAuthCallbackRequest,
        pending: PendingOAuthAuthorization?,
        nowEpochMillis: Long,
    ): OAuthCallbackDecision {
        if (pending == null ||
            pending.redirectUri != OAuthClientConfig.REDIRECT_URI ||
            nowEpochMillis < pending.createdAtEpochMillis - FUTURE_CLOCK_SKEW_MILLIS ||
            nowEpochMillis - pending.createdAtEpochMillis > MAX_AGE_MILLIS ||
            !MessageDigest.isEqual(
                callback.state.toByteArray(StandardCharsets.US_ASCII),
                pending.state.toByteArray(StandardCharsets.US_ASCII),
            )
        ) return OAuthCallbackDecision.Invalid

        if (callback.isCancelled) return OAuthCallbackDecision.Cancelled
        val code = callback.code ?: return OAuthCallbackDecision.Invalid
        return OAuthCallbackDecision.Exchange(code, pending)
    }
}
