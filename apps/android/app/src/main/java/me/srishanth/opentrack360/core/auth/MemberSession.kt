package me.srishanth.opentrack360.core.auth

data class MemberSession(
    val accessToken: String,
    val refreshToken: String,
    val expiresAtEpochMillis: Long,
    val memberId: String,
    val username: String,
    val mustRotatePassword: Boolean,
)

interface SessionStore {
    fun load(): MemberSession?
    fun save(session: MemberSession)
    fun clear()
}
