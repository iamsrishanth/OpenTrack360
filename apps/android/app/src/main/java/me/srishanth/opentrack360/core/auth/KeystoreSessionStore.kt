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

/** Stores only an AES-GCM encrypted session blob in private app storage. */
class KeystoreSessionStore(context: Context) : SessionStore {
    private val preferences = context.applicationContext.getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE)

    override fun load(): MemberSession? {
        val encoded = preferences.getString(SESSION, null) ?: return null
        return try {
            val payload = Base64.decode(encoded, Base64.NO_WRAP)
            if (payload.size <= IV_BYTES) return null
            val iv = payload.copyOfRange(0, IV_BYTES)
            val ciphertext = payload.copyOfRange(IV_BYTES, payload.size)
            val cipher = Cipher.getInstance(TRANSFORMATION)
            cipher.init(Cipher.DECRYPT_MODE, getOrCreateKey(), GCMParameterSpec(TAG_BITS, iv))
            decode(cipher.doFinal(ciphertext))
        } catch (_: Exception) {
            // A lost/invalidated keystore key must fail closed; never fall back to plaintext.
            clear()
            null
        }
    }

    override fun save(session: MemberSession) {
        require(session.accessToken.isNotBlank() && session.refreshToken.isNotBlank())
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.ENCRYPT_MODE, getOrCreateKey())
        val encrypted = cipher.doFinal(encode(session))
        val payload = cipher.iv + encrypted
        check(preferences.edit().putString(SESSION, Base64.encodeToString(payload, Base64.NO_WRAP)).commit()) {
            "Unable to persist the encrypted session"
        }
    }

    override fun clear() {
        preferences.edit().remove(SESSION).commit()
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

    private fun encode(session: MemberSession): ByteArray = ByteArrayOutputStream().use { buffer ->
        DataOutputStream(buffer).use { out ->
            out.writeInt(FORMAT_VERSION)
            out.writeUTF(session.accessToken)
            out.writeUTF(session.refreshToken)
            out.writeLong(session.expiresAtEpochMillis)
            out.writeUTF(session.memberId)
            out.writeUTF(session.username)
            out.writeBoolean(session.mustRotatePassword)
        }
        buffer.toByteArray()
    }

    private fun decode(bytes: ByteArray): MemberSession = DataInputStream(ByteArrayInputStream(bytes)).use { input ->
        require(input.readInt() == FORMAT_VERSION)
        MemberSession(
            accessToken = input.readUTF(),
            refreshToken = input.readUTF(),
            expiresAtEpochMillis = input.readLong(),
            memberId = input.readUTF(),
            username = input.readUTF(),
            mustRotatePassword = input.readBoolean(),
        ).also { require(input.available() == 0) }
    }

    private companion object {
        const val PREFERENCES = "member_session_secure"
        const val SESSION = "encrypted_session"
        const val KEY_ALIAS = "opentrack360.member.session.v1"
        const val ANDROID_KEYSTORE = "AndroidKeyStore"
        const val TRANSFORMATION = "AES/GCM/NoPadding"
        const val IV_BYTES = 12
        const val TAG_BITS = 128
        const val FORMAT_VERSION = 1
    }
}
