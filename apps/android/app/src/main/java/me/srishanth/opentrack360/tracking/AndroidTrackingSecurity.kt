package me.srishanth.opentrack360.tracking

import android.content.Context
import android.os.Build
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/** Encrypted bytes are stored in app-private SharedPreferences with synchronous durable writes. */
class SharedPreferencesTrackingBlobStorage(
    context: Context,
    private val keyName: String = KEY_QUEUE,
) : TrackingBlobStorage {
    private val preferences = context.getSharedPreferences(PREFERENCES_NAME, Context.MODE_PRIVATE)

    override fun read(): ByteArray? = preferences.getString(keyName, null)?.let {
        runCatching { Base64.decode(it, Base64.NO_WRAP) }.getOrNull()
    }

    override fun write(value: ByteArray): Boolean = preferences.edit()
        .putString(keyName, Base64.encodeToString(value, Base64.NO_WRAP))
        .commit()

    override fun clear() {
        preferences.edit().remove(keyName).commit()
    }

    private companion object {
        const val PREFERENCES_NAME = "opentrack360_tracking_queue"
        const val KEY_QUEUE = "encrypted_queue_v1"
    }
}

/** AES-GCM key is non-exportable and generated/stored by Android Keystore. */
class AndroidKeystoreTrackingQueueCipher(
    private val alias: String = DEFAULT_KEY_ALIAS,
    private val unlockedDeviceRequired: Boolean = true,
) : TrackingQueueCipher {
    override fun encrypt(plaintext: ByteArray): ByteArray {
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.ENCRYPT_MODE, getOrCreateKey())
        val encrypted = cipher.doFinal(plaintext)
        return byteArrayOf(VERSION.toByte()) + cipher.iv + encrypted
    }

    override fun decrypt(ciphertext: ByteArray): ByteArray {
        require(ciphertext.size > IV_BYTES + 1)
        require(ciphertext[0].toInt() == VERSION)
        val iv = ciphertext.copyOfRange(1, 1 + IV_BYTES)
        val encrypted = ciphertext.copyOfRange(1 + IV_BYTES, ciphertext.size)
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.DECRYPT_MODE, getOrCreateKey(), GCMParameterSpec(TAG_BITS, iv))
        return cipher.doFinal(encrypted)
    }

    private fun getOrCreateKey(): SecretKey {
        val keyStore = KeyStore.getInstance(ANDROID_KEY_STORE).apply { load(null) }
        (keyStore.getKey(alias, null) as? SecretKey)?.let { return it }
        val keyGenerator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, ANDROID_KEY_STORE)
        val builder = KeyGenParameterSpec.Builder(
            alias,
            KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT,
        ).setBlockModes(KeyProperties.BLOCK_MODE_GCM)
            .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
            .setRandomizedEncryptionRequired(true)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P && unlockedDeviceRequired) builder.setUnlockedDeviceRequired(true)
        keyGenerator.init(builder.build())
        return keyGenerator.generateKey()
    }

    private companion object {
        const val ANDROID_KEY_STORE = "AndroidKeyStore"
        const val TRANSFORMATION = "AES/GCM/NoPadding"
        const val IV_BYTES = 12
        const val TAG_BITS = 128
        const val VERSION = 1
        const val DEFAULT_KEY_ALIAS = "opentrack360.location.queue.v1"
    }
}

