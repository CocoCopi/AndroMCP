package com.cococopi.andromcp.agent

import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import android.util.Log
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Encrypts provider API keys with a hardware-backed AES key held in the Android Keystore.
 *
 * The app never sends a key anywhere except the provider it belongs to, and the stored form is
 * ciphertext, so a settings backup or a file-level read of the app's data store cannot recover it.
 * The key material is non-exportable: it exists only inside the Keystore, and deleting the app
 * removes it with the rest of the app's data.
 *
 * A decryption failure (key invalidated by a lock-screen change or a restore to a new device) returns
 * an empty string rather than throwing, so the settings screen simply asks for the key again.
 */
@Singleton
class AgentKeyStore
    @Inject
    constructor() {
        private fun secretKey(): SecretKey {
            val keyStore = KeyStore.getInstance(ANDROID_KEYSTORE).apply { load(null) }
            (keyStore.getEntry(ALIAS, null) as? KeyStore.SecretKeyEntry)?.let { return it.secretKey }

            val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, ANDROID_KEYSTORE)
            generator.init(
                KeyGenParameterSpec
                    .Builder(ALIAS, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                    .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                    .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                    .setKeySize(KEY_SIZE_BITS)
                    .build(),
            )
            return generator.generateKey()
        }

        /** Encrypts [plainText]; the returned value is `base64(iv || ciphertext)` and safe to store. */
        fun encrypt(plainText: String): String {
            if (plainText.isEmpty()) return ""
            return try {
                val cipher = Cipher.getInstance(TRANSFORMATION)
                cipher.init(Cipher.ENCRYPT_MODE, secretKey())
                val encrypted = cipher.doFinal(plainText.toByteArray())
                Base64.encodeToString(cipher.iv + encrypted, Base64.NO_WRAP)
            } catch (
                @Suppress("TooGenericExceptionCaught") e: Exception,
            ) {
                Log.w(TAG, "Could not encrypt the agent API key", e)
                ""
            }
        }

        /** Decrypts a value produced by [encrypt], or returns an empty string when it cannot. */
        fun decrypt(stored: String): String {
            if (stored.isEmpty()) return ""
            return try {
                val payload = Base64.decode(stored, Base64.NO_WRAP)
                val iv = payload.copyOfRange(0, IV_LENGTH_BYTES)
                val cipherText = payload.copyOfRange(IV_LENGTH_BYTES, payload.size)
                val cipher = Cipher.getInstance(TRANSFORMATION)
                cipher.init(Cipher.DECRYPT_MODE, secretKey(), GCMParameterSpec(TAG_LENGTH_BITS, iv))
                String(cipher.doFinal(cipherText))
            } catch (
                @Suppress("TooGenericExceptionCaught") e: Exception,
            ) {
                Log.w(TAG, "Could not decrypt the stored agent API key; the user must re-enter it", e)
                ""
            }
        }

        private companion object {
            const val TAG = "MCP:AgentKeyStore"
            const val ANDROID_KEYSTORE = "AndroidKeyStore"
            const val ALIAS = "cococopi_agent_provider_key"
            const val TRANSFORMATION = "AES/GCM/NoPadding"
            const val KEY_SIZE_BITS = 256
            const val TAG_LENGTH_BITS = 128
            const val IV_LENGTH_BYTES = 12
        }
    }
