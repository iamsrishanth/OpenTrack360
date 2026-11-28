package me.srishanth.opentrack360.core.auth

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

interface OAuthPendingAuthorizationStore {
    fun save(pending: PendingOAuthAuthorization)
    /** Reads and removes the pending authorization so a callback can only be consumed once. */
    fun consume(): PendingOAuthAuthorization?
    fun clear()
}

object OAuthPendingAuthorizationCodec {
    private const val FORMAT_VERSION = 1
    private val statePattern = Regex("^[A-Za-z0-9_-]{43}$")
    private val verifierPattern = Regex("^[A-Za-z0-9_-]{43,128}$")

    fun encode(pending: PendingOAuthAuthorization): ByteArray {
        validate(pending)
        return ByteArrayOutputStream().use { buffer ->
            DataOutputStream(buffer).use { out ->
                out.writeInt(FORMAT_VERSION)
                out.writeUTF(pending.state)
                out.writeUTF(pending.codeVerifier)
                out.writeUTF(pending.clientId)
                out.writeUTF(pending.tokenExchangeEndpoint)
                out.writeUTF(pending.refreshEndpoint)
                out.writeUTF(pending.redirectUri)
                out.writeLong(pending.createdAtEpochMillis)
            }
            buffer.toByteArray()
        }
    }

    fun decode(bytes: ByteArray): PendingOAuthAuthorization? = runCatching {
        DataInputStream(ByteArrayInputStream(bytes)).use { input ->
            require(input.readInt() == FORMAT_VERSION)
            PendingOAuthAuthorization(
                state = input.readUTF(),
                codeVerifier = input.readUTF(),
                clientId = input.readUTF(),
                tokenExchangeEndpoint = input.readUTF(),
                refreshEndpoint = input.readUTF(),
                redirectUri = input.readUTF(),
                createdAtEpochMillis = input.readLong(),
            ).also {
                require(input.available() == 0)
                validate(it)
            }
        }
    }.getOrNull()

    private fun validate(pending: PendingOAuthAuthorization) {
        require(statePattern.matches(pending.state))
        require(verifierPattern.matches(pending.codeVerifier))
        require(pending.clientId.length in 1..128)
        require(pending.clientId.none(Char::isISOControl) && pending.clientId.none(Char::isWhitespace))
        require(pending.redirectUri == OAuthClientConfig.REDIRECT_URI)
        require(pending.createdAtEpochMillis > 0)
        require(
            OAuthClientConfig.strictHttpsUri(pending.tokenExchangeEndpoint) != null &&
                OAuthClientConfig.strictHttpsUri(pending.refreshEndpoint) != null,
        )
    }
}

/** Encrypts the single pending OAuth verifier and state with an Android Keystore AES key. */
class AndroidKeystoreOAuthPendingAuthorizationStore(context: Context) : OAuthPendingAuthorizationStore {
    private val preferences = context.applicationContext.getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE)

    @Synchronized
    override fun save(pending: PendingOAuthAuthorization) {
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.ENCRYPT_MODE, getOrCreateKey())
        val encrypted = cipher.doFinal(OAuthPendingAuthorizationCodec.encode(pending))
        val payload = cipher.iv + encrypted
        check(
            preferences.edit()
                .putString(PENDING, Base64.encodeToString(payload, Base64.NO_WRAP))
                .commit(),
        ) { "Unable to save secure browser sign-in state." }
    }

    @Synchronized
    override fun consume(): PendingOAuthAuthorization? {
        val encoded = preferences.getString(PENDING, null) ?: return null
        if (!preferences.edit().remove(PENDING).commit()) return null
        return try {
            val payload = Base64.decode(encoded, Base64.NO_WRAP)
            if (payload.size <= IV_BYTES) return null
            val iv = payload.copyOfRange(0, IV_BYTES)
            val ciphertext = payload.copyOfRange(IV_BYTES, payload.size)
            val cipher = Cipher.getInstance(TRANSFORMATION)
            cipher.init(Cipher.DECRYPT_MODE, getOrCreateKey(), GCMParameterSpec(TAG_BITS, iv))
            OAuthPendingAuthorizationCodec.decode(cipher.doFinal(ciphertext))
        } catch (_: Exception) {
            null
        }
    }

    @Synchronized
    override fun clear() {
        check(preferences.edit().remove(PENDING).commit()) { "Unable to clear secure browser sign-in state." }
    }

    private fun getOrCreateKey(): SecretKey {
        val keyStore = KeyStore.getInstance(ANDROID_KEYSTORE).apply { load(null) }
        (keyStore.getKey(KEY_ALIAS, null) as? SecretKey)?.let { return it }

        val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, ANDROID_KEYSTORE)
        generator.init(
            KeyGenParameterSpec.Builder(
                KEY_ALIAS,
                KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT,
            )
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256)
                .build(),
        )
        return generator.generateKey()
    }

    private companion object {
        const val PREFERENCES = "oauth_pending_secure"
        const val PENDING = "pending_authorization"
        const val KEY_ALIAS = "opentrack360.oauth.pending.v1"
        const val ANDROID_KEYSTORE = "AndroidKeyStore"
        const val TRANSFORMATION = "AES/GCM/NoPadding"
        const val IV_BYTES = 12
        const val TAG_BITS = 128
    }
}
