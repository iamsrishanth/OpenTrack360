package me.srishanth.opentrack360.core.auth

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.net.URI
import java.net.URLDecoder
import java.nio.charset.StandardCharsets

class OAuthAuthFlowTest {
    @Test
    fun parsesOnlyTheExpectedSupabaseAndOpenTrackEndpoints() {
        assertNotNull(OAuthClientConfig.parse(configJson(), API_BASE_URL))

        listOf(
            configJson().put("authorizationEndpoint", "http://project-ref.supabase.co/auth/v1/oauth/authorize"),
            configJson().put("authorizationEndpoint", "https://evil.example/auth/v1/oauth/authorize"),
            configJson().put("tokenExchangeEndpoint", "https://evil.example/api/v1/auth/oauth/exchange"),
            configJson().put("refreshEndpoint", "https://track.srishanth.me/api/v1/auth/logout"),
            configJson().put("redirectUri", "https://evil.example/oauth/callback"),
            configJson().put("responseType", "token"),
            configJson().put("codeChallengeMethod", "plain"),
            configJson().put("scopes", org.json.JSONArray(listOf("openid", "email"))),
        ).forEach { json ->
            assertNull(runCatching { OAuthClientConfig.parse(json, API_BASE_URL) }.getOrNull())
        }
    }

    @Test
    fun generatesRfc7636S256ChallengeAndUrlEncodedAuthorizationParameters() {
        val verifier = "dBjftJeZ4CVP-mB92K27uhbUJU1p1r_wW1gFWFOEjXk"
        assertEquals("E9Melhoa2OwvFrEMTJguCHaoeK1t8URWbuGJSstw-cM", OAuthPkce.codeChallenge(verifier))

        val config = OAuthClientConfig.parse(configJson(), API_BASE_URL)
        val pending = OAuthPkce.newAuthorization(config, nowEpochMillis = 10_000)
        val url = URI(OAuthPkce.authorizationUrl(config, pending))
        val params = url.rawQuery.split('&').associate { component ->
            val parts = component.split('=', limit = 2)
            URLDecoder.decode(parts[0], StandardCharsets.UTF_8.name()) to
                URLDecoder.decode(parts[1], StandardCharsets.UTF_8.name())
        }

        assertEquals("code", params["response_type"])
        assertEquals(config.clientId, params["client_id"])
        assertEquals(config.redirectUri, params["redirect_uri"])
        assertEquals("openid", params["scope"])
        assertEquals(pending.state, params["state"])
        assertEquals(OAuthPkce.codeChallenge(pending.codeVerifier), params["code_challenge"])
        assertEquals("S256", params["code_challenge_method"])
        assertEquals(43, pending.state.length)
        assertEquals(43, pending.codeVerifier.length)
    }

    @Test
    fun validatesCodeStateAndSingleUseWindowWithoutReflectingSecrets() {
        val pending = pending()
        val callback = requireNotNull(OAuthCallbackParser.parse("$REDIRECT_URI?code=fake-auth-code&state=${pending.state}"))
        val decision = OAuthCallbackPolicy.validate(callback, pending, pending.createdAtEpochMillis + 1_000)
        assertTrue(decision is OAuthCallbackDecision.Exchange)
        assertFalse(callback.toString().contains("fake-auth-code"))
        assertFalse(callback.toString().contains(pending.state))
        assertFalse(decision.toString().contains("fake-auth-code"))
        assertFalse(pending.toString().contains(pending.codeVerifier))

        val mismatch = requireNotNull(OAuthCallbackParser.parse("$REDIRECT_URI?code=fake-auth-code&state=${"B".repeat(43)}"))
        assertEquals(OAuthCallbackDecision.Invalid, OAuthCallbackPolicy.validate(mismatch, pending, pending.createdAtEpochMillis + 1_000))
        assertEquals(OAuthCallbackDecision.Invalid, OAuthCallbackPolicy.validate(callback, pending, pending.createdAtEpochMillis + OAuthCallbackPolicy.MAX_AGE_MILLIS + 1))
        assertEquals(OAuthCallbackDecision.Invalid, OAuthCallbackPolicy.validate(callback, null, pending.createdAtEpochMillis + 1_000))
    }

    @Test
    fun acceptsCancellationOnlyAfterStateValidation() {
        val pending = pending()
        val callback = requireNotNull(OAuthCallbackParser.parse("$REDIRECT_URI?error=access_denied&error_description=Cancelled&state=${pending.state}"))
        assertEquals(
            OAuthCallbackDecision.Cancelled,
            OAuthCallbackPolicy.validate(callback, pending, pending.createdAtEpochMillis + 1_000),
        )
        val mismatched = requireNotNull(OAuthCallbackParser.parse("$REDIRECT_URI?error=access_denied&state=${"B".repeat(43)}"))
        assertEquals(
            OAuthCallbackDecision.Invalid,
            OAuthCallbackPolicy.validate(mismatched, pending, pending.createdAtEpochMillis + 1_000),
        )
    }

    @Test
    fun pendingAuthorizationCodecRoundTripsAndRejectsTrailingData() {
        val original = pending()
        val encoded = OAuthPendingAuthorizationCodec.encode(original)
        val decoded = OAuthPendingAuthorizationCodec.decode(encoded)

        assertTrue(decoded?.let {
            it.state == original.state &&
                it.codeVerifier == original.codeVerifier &&
                it.clientId == original.clientId &&
                it.tokenExchangeEndpoint == original.tokenExchangeEndpoint &&
                it.refreshEndpoint == original.refreshEndpoint &&
                it.redirectUri == original.redirectUri &&
                it.createdAtEpochMillis == original.createdAtEpochMillis
        } == true)
        assertNull(OAuthPendingAuthorizationCodec.decode(encoded + byteArrayOf(0)))
    }

    @Test
    fun rejectsWrongOriginsDuplicatesMissingStateAndAmbiguousResponses() {
        val state = "A".repeat(43)
        listOf(
            "http://track.srishanth.me/oauth/callback?code=fake&state=$state",
            "https://evil.example/oauth/callback?code=fake&state=$state",
            "https://track.srishanth.me/oauth/callback/extra?code=fake&state=$state",
            "https://track.srishanth.me/oauth/callback?code=fake",
            "$REDIRECT_URI?code=first&code=second&state=$state",
            "$REDIRECT_URI?code=fake&error=access_denied&state=$state",
            "$REDIRECT_URI?error=access_denied&error_description=bad",
            "$REDIRECT_URI?code=fake&state=short",
            "$REDIRECT_URI?code=fake&state=$state&unexpected=value",
            "$REDIRECT_URI?code=fake&state=$state#fragment",
        ).forEach { assertNull(OAuthCallbackParser.parse(it)) }
    }

    private fun configJson() = JSONObject()
        .put("issuer", "https://project-ref.supabase.co/auth/v1")
        .put("authorizationEndpoint", "https://project-ref.supabase.co/auth/v1/oauth/authorize")
        .put("tokenExchangeEndpoint", "https://track.srishanth.me/api/v1/auth/oauth/exchange")
        .put("refreshEndpoint", "https://track.srishanth.me/api/v1/auth/refresh")
        .put("clientId", "public-client-id-example")
        .put("redirectUri", REDIRECT_URI)
        .put("responseType", "code")
        .put("codeChallengeMethod", "S256")
        .put("scopes", org.json.JSONArray(listOf("openid")))

    private fun pending(): PendingOAuthAuthorization = PendingOAuthAuthorization(
        state = "A".repeat(43),
        codeVerifier = "B".repeat(43),
        clientId = "public-client-id-example",
        tokenExchangeEndpoint = "https://track.srishanth.me/api/v1/auth/oauth/exchange",
        refreshEndpoint = "https://track.srishanth.me/api/v1/auth/refresh",
        redirectUri = REDIRECT_URI,
        createdAtEpochMillis = 10_000,
    )

    private companion object {
        const val API_BASE_URL = "https://track.srishanth.me/api/v1/"
        const val REDIRECT_URI = "https://track.srishanth.me/oauth/callback"
    }
}
