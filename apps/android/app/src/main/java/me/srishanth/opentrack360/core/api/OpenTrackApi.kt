package me.srishanth.opentrack360.core.api

import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.Dispatchers
import me.srishanth.opentrack360.BuildConfig
import me.srishanth.opentrack360.core.auth.MemberSession
import me.srishanth.opentrack360.core.auth.SessionStore
import org.json.JSONObject
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.nio.charset.StandardCharsets
import java.util.UUID

data class MemberProfile(
    val id: String,
    val username: String,
    val mustRotatePassword: Boolean,
    val sharingEpoch: Long,
)

data class StopSharingResult(
    val stopped: Boolean,
    val endedShareCount: Int,
    val sharingEpoch: Long,
)

data class ApiResponse(val statusCode: Int, val body: String)

class ApiException(
    val statusCode: Int?,
    val errorCode: String? = null,
    cause: Throwable? = null,
) : IOException(messageFor(statusCode, errorCode), cause) {
    companion object {
        private fun messageFor(status: Int?, code: String?): String = when {
            code == "password_rotation_required" || status == 428 -> "Change your temporary password to continue."
            status == 401 -> "Your session has expired. Sign in again."
            status == 403 -> "This action is not available for your account."
            status == 404 -> "That item is no longer available."
            status == 409 -> "This action conflicts with a recent change. Refresh and try again."
            status == 429 -> "Too many attempts. Wait a moment and try again."
            status != null && status >= 500 -> "OpenTrack360 is temporarily unavailable. Try again shortly."
            status != null -> "The request could not be completed. Check the details and try again."
            else -> "Could not connect. Check your internet connection and try again."
        }
    }
}

/** HTTPS API adapter. Request/response bodies are never written to logs or disk. */
class OpenTrackApi(
    private val sessions: SessionStore,
    private val baseUrl: String = BuildConfig.API_BASE_URL,
    private val nowMillis: () -> Long = System::currentTimeMillis,
) {
    private val refreshMutex = Mutex()

    init {
        require(baseUrl.startsWith("https://")) { "The production API must use HTTPS" }
    }

    suspend fun signIn(username: String, password: String): MemberSession {
        sessions.clear()
        val body = JSONObject().put("username", username.trim()).put("password", password)
        val response = request("POST", "auth/login", body, bearer = null)
        return parseSession(requireSuccess(response)).also(sessions::save)
    }

    suspend fun currentSession(): MemberSession? = sessions.load()

    suspend fun refreshSession(): MemberSession? = refreshMutex.withLock {
        val current = sessions.load() ?: return null
        if (current.expiresAtEpochMillis > nowMillis() + REFRESH_SKEW_MS) return current
        val body = JSONObject().put("refreshToken", current.refreshToken)
        val response = request("POST", "auth/refresh", body, bearer = null)
        if (response.statusCode == 401 || response.statusCode == 403) {
            sessions.clear()
            throw ApiException(response.statusCode, response.errorCode())
        }
        parseSession(requireSuccess(response)).also(sessions::save)
    }

    suspend fun accessToken(): String {
        val current = sessions.load() ?: throw ApiException(401)
        if (current.expiresAtEpochMillis <= nowMillis() + REFRESH_SKEW_MS) {
            return refreshSession()?.accessToken ?: throw ApiException(401)
        }
        return current.accessToken
    }

    suspend fun loadMemberProfile(): MemberProfile {
        val current = requireMemberAccess()
        val response = request("GET", "me", bearer = accessToken())
        val json = requireSuccess(response)
        return MemberProfile(
            id = json.getString("id"),
            username = json.optString("username", current.username),
            mustRotatePassword = json.getBoolean("mustRotatePassword"),
            sharingEpoch = json.getLong("sharingEpoch"),
        )
    }

    suspend fun changePassword(currentPassword: String, newPassword: String) {
        val body = JSONObject()
            .put("currentPassword", currentPassword)
            .put("newPassword", newPassword)
        requireSuccess(request("POST", "me/password", body, bearer = accessToken()))
        sessions.clear() // Contract invalidates prior sessions and requires a fresh login.
    }

    suspend fun logout() {
        val session = sessions.load() ?: return
        sessions.clear()
        try {
            requireSuccess(
                request(
                    method = "POST",
                    path = "auth/logout",
                    body = JSONObject().put("refreshToken", session.refreshToken),
                    bearer = session.accessToken,
                ),
            )
        } finally {
            sessions.clear()
        }
    }

    suspend fun stopAllSharing(clientRequestId: String = UUID.randomUUID().toString()): StopSharingResult {
        UUID.fromString(clientRequestId)
        val json = memberRequest("POST", "me/stop-sharing", JSONObject().put("clientRequestId", clientRequestId))
        return StopSharingResult(
            stopped = json.getBoolean("stopped"),
            endedShareCount = json.getInt("endedShareCount"),
            sharingEpoch = json.getLong("sharingEpoch"),
        )
    }

    suspend fun memberGet(path: String): JSONObject = memberRequest("GET", path)

    suspend fun memberGet(path: String, queryParameters: Map<String, String>): JSONObject =
        memberRequest("GET", path, queryParameters = queryParameters)

    suspend fun memberPost(path: String, body: JSONObject): JSONObject = memberRequest("POST", path, body)

    suspend fun memberPut(path: String, body: JSONObject): JSONObject = memberRequest("PUT", path, body)

    suspend fun memberPatch(path: String, body: JSONObject): JSONObject = memberRequest("PATCH", path, body)

    suspend fun memberDelete(path: String): JSONObject = memberRequest("DELETE", path)

    suspend fun publicGet(path: String, bearer: String? = null): ApiResponse =
        request("GET", path, bearer = bearer, clearMemberSessionOnUnauthorized = false)

    suspend fun publicPost(path: String, body: JSONObject, bearer: String): ApiResponse =
        request("POST", path, body, bearer, clearMemberSessionOnUnauthorized = false)

    /** Upload one private pet image to the short-lived HTTPS URL returned by the member API. */
    suspend fun putSignedUpload(uploadUrl: String, contentType: String, content: ByteArray) = withContext(Dispatchers.IO) {
        require(contentType in PET_PHOTO_CONTENT_TYPES && content.size in 1..MAX_PET_PHOTO_BYTES) {
            "The selected photo did not meet the upload requirements."
        }
        val url = URL(uploadUrl)
        val allowedHost = url.host.equals(API_UPLOAD_HOST, ignoreCase = true) ||
            url.host.endsWith(SUPABASE_STORAGE_SUFFIX, ignoreCase = true)
        require(url.protocol.equals("https", ignoreCase = true) && allowedHost && url.userInfo == null) {
            "The photo upload URL was invalid."
        }
        val connection = (url.openConnection() as HttpURLConnection).apply {
            requestMethod = "PUT"
            connectTimeout = CONNECT_TIMEOUT_MS
            readTimeout = READ_TIMEOUT_MS
            instanceFollowRedirects = false
            useCaches = false
            doOutput = true
            setFixedLengthStreamingMode(content.size)
            setRequestProperty("Content-Type", contentType)
        }
        try {
            connection.outputStream.use { it.write(content) }
            val status = connection.responseCode
            if (status !in 200..299) throw ApiException(status)
        } catch (exception: Exception) {
            if (exception is ApiException) throw exception
            throw ApiException(null, cause = exception)
        } finally {
            connection.disconnect()
        }
    }

    suspend fun memberRequest(
        method: String,
        path: String,
        body: JSONObject? = null,
        queryParameters: Map<String, String> = emptyMap(),
    ): JSONObject {
        requireMemberAccess()
        return requireSuccess(request(method, path, body, bearer = accessToken(), queryParameters = queryParameters))
    }

    private suspend fun requireMemberAccess(): MemberSession {
        val session = sessions.load() ?: throw ApiException(401)
        if (session.mustRotatePassword) throw ApiException(428, "password_rotation_required")
        return session
    }

    private suspend fun request(
        method: String,
        path: String,
        body: JSONObject? = null,
        bearer: String?,
        queryParameters: Map<String, String> = emptyMap(),
        clearMemberSessionOnUnauthorized: Boolean = true,
    ): ApiResponse = withContext(Dispatchers.IO) {
        if (path.startsWith('/') || path.contains("..") || path.contains('?') || path.contains('#')) {
            throw IllegalArgumentException("API paths must be relative and must not contain query parameters")
        }
        val query = queryParameters.entries.joinToString("&") { (key, value) ->
            "${key.encodeQueryComponent()}=${value.encodeQueryComponent()}"
        }
        val requestUrl = baseUrl.trimEnd('/') + "/" + path + query.takeIf(String::isNotEmpty)?.let { "?$it" }.orEmpty()
        val connection = (URL(requestUrl).openConnection() as HttpURLConnection).apply {
            requestMethod = method
            connectTimeout = CONNECT_TIMEOUT_MS
            readTimeout = READ_TIMEOUT_MS
            instanceFollowRedirects = false
            useCaches = false
            setRequestProperty("Accept", "application/json")
            setRequestProperty("User-Agent", "OpenTrack360-Android/${BuildConfig.VERSION_NAME}")
            if (bearer != null) setRequestProperty("Authorization", "Bearer $bearer")
            if (body != null) {
                doOutput = true
                setRequestProperty("Content-Type", "application/json; charset=utf-8")
            }
        }
        try {
            if (body != null) connection.outputStream.use { it.write(body.toString().toByteArray(Charsets.UTF_8)) }
            val status = connection.responseCode
            val stream = if (status in 200..299) connection.inputStream else connection.errorStream
            val responseBody = stream?.bufferedReader(Charsets.UTF_8)?.use { it.readText() }.orEmpty()
            if (status == 401 && bearer != null && clearMemberSessionOnUnauthorized) sessions.clear()
            ApiResponse(status, responseBody)
        } catch (exception: Exception) {
            if (exception is ApiException) throw exception
            throw ApiException(null, cause = exception)
        } finally {
            connection.disconnect()
        }
    }

    private fun requireSuccess(response: ApiResponse): JSONObject {
        val json = response.body.takeIf(String::isNotBlank)?.let { runCatching { JSONObject(it) }.getOrNull() }
        if (response.statusCode !in 200..299) {
            throw ApiException(response.statusCode, json?.errorCode())
        }
        return json ?: JSONObject()
    }

    private fun ApiResponse.errorCode(): String? = body.takeIf(String::isNotBlank)
        ?.let { runCatching { JSONObject(it).errorCode() }.getOrNull() }

    private fun JSONObject.errorCode(): String? = optString("code").takeIf(String::isNotBlank)
        ?: optJSONObject("error")?.optString("code")?.takeIf(String::isNotBlank)

    private fun String.encodeQueryComponent(): String = URLEncoder.encode(this, StandardCharsets.UTF_8.name())

    private fun parseSession(json: JSONObject): MemberSession {
        val user = json.getJSONObject("user")
        val expiresIn = json.getLong("expiresInSeconds").coerceAtLeast(0)
        return MemberSession(
            accessToken = json.getString("accessToken"),
            refreshToken = json.getString("refreshToken"),
            expiresAtEpochMillis = nowMillis() + expiresIn * 1_000L,
            memberId = user.getString("id"),
            username = user.getString("username"),
            mustRotatePassword = user.optBoolean("mustRotatePassword", false),
        )
    }

    private companion object {
        const val CONNECT_TIMEOUT_MS = 15_000
        const val READ_TIMEOUT_MS = 15_000
        const val REFRESH_SKEW_MS = 60_000L
        const val API_UPLOAD_HOST = "track.srishanth.me"
        const val SUPABASE_STORAGE_SUFFIX = ".supabase.co"
        const val MAX_PET_PHOTO_BYTES = 5 * 1024 * 1024
        val PET_PHOTO_CONTENT_TYPES = setOf("image/jpeg", "image/png", "image/webp")
    }
}
