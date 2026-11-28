package me.srishanth.opentrack360.core.auth

data class MemberSession(
    val accessToken: String,
    val refreshToken: String,
    val expiresAtEpochMillis: Long,
    val memberId: String,
    val username: String,
    val mustRotatePassword: Boolean,
    val oauthClientId: String? = null,
    val oauthRefreshEndpoint: String? = null,
) {
    init {
        require((oauthClientId == null) == (oauthRefreshEndpoint == null)) {
            "OAuth session metadata must be complete."
        }
    }

    override fun toString(): String = "MemberSession(member=<redacted>, accessToken=<redacted>, refreshToken=<redacted>)"
}

interface SessionStore {
    fun load(): MemberSession?
    fun save(session: MemberSession)
    fun clear()
}
