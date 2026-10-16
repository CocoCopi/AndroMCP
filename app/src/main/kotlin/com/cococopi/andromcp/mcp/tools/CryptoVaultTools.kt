@file:Suppress("MatchingDeclarationName", "TooManyFunctions")

package com.cococopi.andromcp.mcp.tools

import android.content.Context
import android.content.SharedPreferences
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import android.util.Log
import com.cococopi.andromcp.data.model.ToolPermissionsConfig
import com.cococopi.andromcp.mcp.McpToolException
import io.modelcontextprotocol.kotlin.sdk.types.CallToolResult
import io.modelcontextprotocol.kotlin.sdk.types.ToolSchema
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject
import java.security.KeyPairGenerator
import java.security.KeyStore
import java.security.PrivateKey
import java.security.SecureRandom
import java.security.Signature
import java.security.spec.ECGenParameterSpec
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.PBEKeySpec
import javax.crypto.spec.SecretKeySpec
import kotlin.math.ln

/**
 * Cryptography and secret-storage tool bundle.
 *
 * An agent that handles credentials, tokens or personal text needs somewhere safe to put them, and
 * a way to prove it produced the bytes it claims. These tools cover both: generate and manage keys
 * in the hardware-backed Android KeyStore, sign and verify data, encrypt or decrypt text either
 * with a device-held key or with a caller-supplied passphrase, and keep named secrets at rest
 * through the same KeyStore key. Passwords can be generated and their strength estimated.
 *
 * Nothing here needs a permission and nothing leaves the device. Private key material is generated
 * inside the KeyStore and can never be exported, so keystore_sign works precisely because the key
 * never has to leave that boundary.
 */

/** Base64 output without line wrapping, so a payload survives a single-line JSON field. */
private const val BASE64_FLAGS = Base64.NO_WRAP

/** Signature algorithm matching a KeyStore key's own algorithm. */
private fun signatureAlgorithmFor(keyAlgorithm: String): String =
    when (keyAlgorithm.uppercase()) {
        "RSA" -> "SHA256withRSA"
        "EC", "ECDSA" -> "SHA256withECDSA"
        else -> throw McpToolException.InvalidParams(
            "Keys of type '$keyAlgorithm' cannot sign. Generate an RSA or EC key with keystore_generate_key.",
        )
    }

/** Prefix identifying a payload protected by a KeyStore key. */
private const val KEYSTORE_PREFIX = "key"

/** Prefix identifying a payload protected by a passphrase. */
private const val PASSPHRASE_PREFIX = "v1"

/** Name of the SharedPreferences file that holds encrypted secrets. */
private const val VAULT_PREFS = "andromcp_vault"

/** Default KeyStore alias for the AES key that protects stored secrets. */
private const val VAULT_ALIAS = "andromcp_vault_key"

/** Iteration count for the passphrase key derivation. */
private const val PBKDF2_ITERATIONS = 120_000

/** Shared crypto plumbing for the vault bundle. */
internal object CryptoVault {
    private const val PROVIDER = "AndroidKeyStore"
    private val random = SecureRandom()

    /** The device KeyStore, loaded and ready for use. */
    @Suppress("TooGenericExceptionCaught")
    fun keyStore(): KeyStore =
        try {
            KeyStore.getInstance(PROVIDER).apply { load(null) }
        } catch (exception: Exception) {
            throw McpToolException.ActionFailed(
                "The Android KeyStore is unavailable on this device: " +
                    (exception.message ?: exception.javaClass.simpleName),
            )
        }

    /** Base64-encodes raw bytes for transport inside a tool result. */
    fun encode(bytes: ByteArray): String = Base64.encodeToString(bytes, BASE64_FLAGS)

    /** Decodes base64 produced by [encode]; rejects malformed input with a clear message. */
    fun decode(text: String, field: String): ByteArray =
        try {
            Base64.decode(text, BASE64_FLAGS)
        } catch (_: IllegalArgumentException) {
            throw McpToolException.InvalidParams("Parameter '$field' is not valid base64.")
        }

    /** Turns a caller-supplied payload into bytes, honouring the `utf8`/`base64` switch. */
    fun decodeInput(
        content: String,
        encoding: String,
        field: String,
    ): ByteArray =
        when (encoding.lowercase()) {
            "utf8", "text" -> content.toByteArray(Charsets.UTF_8)
            "base64" -> decode(content, field)
            else -> throw McpToolException.InvalidParams(
                "Parameter 'input_encoding' must be utf8 or base64. Got '$encoding'.",
            )
        }

    /** Returns the AES key for [alias], creating it inside the KeyStore when absent. */
    @Suppress("TooGenericExceptionCaught")
    fun ensureAesKey(alias: String): SecretKey {
        val keyStore = keyStore()
        val existing = keyStore.getKey(alias, null) as? SecretKey
        if (existing != null) return existing
        try {
            val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, PROVIDER)
            generator.init(
                KeyGenParameterSpec
                    .Builder(alias, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                    .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                    .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                    .setKeySize(256)
                    .build(),
            )
            return generator.generateKey()
        } catch (exception: Exception) {
            throw McpToolException.ActionFailed(
                "Could not create the KeyStore key '$alias': " +
                    (exception.message ?: exception.javaClass.simpleName),
            )
        }
    }

    /** Encrypts [plaintext] with the KeyStore key [alias] and returns a self-describing payload. */
    @Suppress("TooGenericExceptionCaught")
    fun encryptWithKeyStore(
        alias: String,
        plaintext: String,
    ): String {
        val key = ensureAesKey(alias)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        try {
            cipher.init(Cipher.ENCRYPT_MODE, key)
        } catch (exception: Exception) {
            throw McpToolException.ActionFailed(
                "Could not initialize encryption with '$alias': " +
                    (exception.message ?: exception.javaClass.simpleName),
            )
        }
        val ciphertext = cipher.doFinal(plaintext.toByteArray(Charsets.UTF_8))
        return "$KEYSTORE_PREFIX:$alias:${encode(cipher.iv)}:${encode(ciphertext)}"
    }

    /** Decrypts a payload produced by [encryptWithKeyStore]. */
    @Suppress("TooGenericExceptionCaught")
    fun decryptWithKeyStore(payload: String): String {
        val parts = payload.split(':')
        if (parts.size != 4 || parts[0] != KEYSTORE_PREFIX) {
            throw McpToolException.InvalidParams(
                "Payload is not in the key:<alias>:<iv>:<ciphertext> form produced by encrypt_text.",
            )
        }
        val alias = parts[1]
        val key =
            keyStore().getKey(alias, null) as? SecretKey
                ?: throw McpToolException.ActionFailed(
                    "No KeyStore key named '$alias' exists any more, so this payload cannot be decrypted.",
                )
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        try {
            cipher.init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(GCM_TAG_BITS, decode(parts[2], "iv")))
            return String(cipher.doFinal(decode(parts[3], "ciphertext")), Charsets.UTF_8)
        } catch (exception: Exception) {
            throw McpToolException.ActionFailed(
                "Decryption failed: ${exception.message ?: exception.javaClass.simpleName}. " +
                    "The payload may be corrupted or was encrypted with a different key.",
            )
        }
    }

    /** Derives an AES key from [passphrase] with PBKDF2-HMAC-SHA256. */
    @Suppress("TooGenericExceptionCaught")
    fun passwordKey(
        passphrase: String,
        salt: ByteArray,
    ): SecretKey {
        val spec = PBEKeySpec(passphrase.toCharArray(), salt, PBKDF2_ITERATIONS, KEY_BITS)
        return try {
            val derived = SecretKeyFactory.getInstance(PBKDF2_ALGORITHM).generateSecret(spec).encoded
            SecretKeySpec(derived, "AES")
        } catch (exception: Exception) {
            throw McpToolException.ActionFailed(
                "Could not derive a key from the passphrase: " +
                    (exception.message ?: exception.javaClass.simpleName),
            )
        } finally {
            spec.clearPassword()
        }
    }

    /** Encrypts [plaintext] under [passphrase] and returns a self-describing payload. */
    fun encryptWithPassphrase(
        passphrase: String,
        plaintext: String,
    ): String {
        val salt = ByteArray(SALT_BYTES).also { random.nextBytes(it) }
        val key = passwordKey(passphrase, salt)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, key)
        val ciphertext = cipher.doFinal(plaintext.toByteArray(Charsets.UTF_8))
        return "$PASSPHRASE_PREFIX:${encode(salt)}:${encode(cipher.iv)}:${encode(ciphertext)}"
    }

    /** Decrypts a payload produced by [encryptWithPassphrase]. */
    @Suppress("TooGenericExceptionCaught")
    fun decryptWithPassphrase(
        passphrase: String,
        payload: String,
    ): String {
        val parts = payload.split(':')
        if (parts.size != 4 || parts[0] != PASSPHRASE_PREFIX) {
            throw McpToolException.InvalidParams(
                "Payload is not in the v1:<salt>:<iv>:<ciphertext> form produced by encrypt_text " +
                    "with a passphrase.",
            )
        }
        val key = passwordKey(passphrase, decode(parts[1], "salt"))
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        try {
            cipher.init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(GCM_TAG_BITS, decode(parts[2], "iv")))
            return String(cipher.doFinal(decode(parts[3], "ciphertext")), Charsets.UTF_8)
        } catch (exception: Exception) {
            throw McpToolException.ActionFailed(
                "Decryption failed: ${exception.message ?: exception.javaClass.simpleName}. " +
                    "The passphrase is most likely wrong — a wrong passphrase fails authentication " +
                    "rather than returning garbage.",
            )
        }
    }

    /** SharedPreferences holding encrypted secrets, private to this app. */
    fun secrets(context: Context): SharedPreferences = context.getSharedPreferences(VAULT_PREFS, Context.MODE_PRIVATE)

    private const val GCM_TAG_BITS = 128
    private const val KEY_BITS = 256
    private const val SALT_BYTES = 16
    private const val PBKDF2_ALGORITHM = "PBKDF2WithHmacSHA256"
}

// ─────────────────────────────────────────────────────────────────────────────
// encrypt_text
// ─────────────────────────────────────────────────────────────────────────────

/**
 * MCP tool handler for `encrypt_text`.
 * Input: `{ "content": <string>, "passphrase": <string>?, "alias": <string>? }`.
 */
class EncryptTextHandler(
    private val context: Context,
) {
    suspend fun execute(arguments: JsonObject?): CallToolResult {
        val content = McpToolUtils.requireString(arguments, "content")
        val passphrase = McpToolUtils.optionalString(arguments, "passphrase", "")
        val alias = McpToolUtils.optionalString(arguments, "alias", VAULT_ALIAS)
        Log.d(TAG, "Executing encrypt_text")

        val payload =
            withContext(Dispatchers.IO) {
                if (passphrase.isEmpty()) {
                    CryptoVault.encryptWithKeyStore(alias, content)
                } else {
                    CryptoVault.encryptWithPassphrase(passphrase, content)
                }
            }
        val json =
            buildJsonObject {
                put("mode", if (passphrase.isEmpty()) "keystore" else "passphrase")
                put("alias", if (passphrase.isEmpty()) alias else "")
                put("algorithm", "AES-256-GCM")
                put("input_chars", content.length)
                put("payload", payload)
            }
        return McpToolUtils.textResult(json.toString())
    }

    fun register(
        registrar: LoggedToolRegistrar,
        toolNamePrefix: String,
    ) {
        registrar.addTool(
            toolName = TOOL_NAME,
            name = "$toolNamePrefix$TOOL_NAME",
            description =
                "Encrypts text with AES-256-GCM and returns a self-describing payload that " +
                    "decrypt_text can open. Two modes: without a 'passphrase' the payload is sealed " +
                    "by a key held inside the Android KeyStore under 'alias', which never leaves the " +
                    "device and is the right choice for data that stays here; with a 'passphrase' the " +
                    "key is derived from that passphrase (PBKDF2-HMAC-SHA256, unique random salt), " +
                    "which is the right choice for data that must travel. Use it before storing or " +
                    "sending anything sensitive — tokens, addresses, personal notes — and keep the " +
                    "payload rather than the plaintext.",
            inputSchema =
                ToolSchema(
                    properties =
                        buildJsonObject {
                            putJsonObject("content") {
                                put("type", "string")
                                put("description", "Plain text to encrypt")
                            }
                            putJsonObject("passphrase") {
                                put("type", "string")
                                put("description", "Optional passphrase; when omitted a device KeyStore key is used")
                            }
                            putJsonObject("alias") {
                                put("type", "string")
                                put("description", "KeyStore key alias, created on demand (default $VAULT_ALIAS)")
                            }
                        },
                    required = listOf("content"),
                ),
        ) { request -> execute(request.arguments) }
    }

    companion object {
        const val TOOL_NAME = "encrypt_text"
        private const val TAG = "MCP:EncryptTextHandler"
    }
}

// ─────────────────────────────────────────────────────────────────────────────
// decrypt_text
// ─────────────────────────────────────────────────────────────────────────────

/** MCP tool handler for `decrypt_text`. Input: `{ "payload": <string>, "passphrase": <string>? }`. */
class DecryptTextHandler(
    private val context: Context,
) {
    suspend fun execute(arguments: JsonObject?): CallToolResult {
        val payload = McpToolUtils.requireString(arguments, "payload").trim()
        val passphrase = McpToolUtils.optionalString(arguments, "passphrase", "")
        Log.d(TAG, "Executing decrypt_text")

        val mode = payload.substringBefore(':')
        val plaintext =
            withContext(Dispatchers.IO) {
                when (mode) {
                    PASSPHRASE_PREFIX -> {
                        if (passphrase.isEmpty()) {
                            throw McpToolException.InvalidParams(
                                "This payload was encrypted with a passphrase; supply it in 'passphrase'.",
                            )
                        }
                        CryptoVault.decryptWithPassphrase(passphrase, payload)
                    }

                    KEYSTORE_PREFIX -> CryptoVault.decryptWithKeyStore(payload)
                    else -> throw McpToolException.InvalidParams(
                        "Unrecognized payload prefix '$mode'. Expected 'key:' or 'v1:' as produced by " +
                            "encrypt_text.",
                    )
                }
            }
        val json =
            buildJsonObject {
                put("mode", if (mode == KEYSTORE_PREFIX) "keystore" else "passphrase")
                put("chars", plaintext.length)
                put("content", plaintext)
            }
        return McpToolUtils.textResult(json.toString())
    }

    fun register(
        registrar: LoggedToolRegistrar,
        toolNamePrefix: String,
    ) {
        registrar.addTool(
            toolName = TOOL_NAME,
            name = "$toolNamePrefix$TOOL_NAME",
            description =
                "Decrypts a payload produced by encrypt_text and returns the original text. The " +
                    "payload itself says which mode it uses, so you only need to add 'passphrase' for " +
                    "passphrase-encrypted payloads — a wrong passphrase fails authentication rather " +
                    "than returning plausible-looking garbage, which makes it safe to branch on. A " +
                    "keystore payload can only be opened on this device, and only while the key it " +
                    "names still exists; keystore_list_keys shows what is available.",
            inputSchema =
                ToolSchema(
                    properties =
                        buildJsonObject {
                            putJsonObject("payload") {
                                put("type", "string")
                                put("description", "Payload returned by encrypt_text")
                            }
                            putJsonObject("passphrase") {
                                put("type", "string")
                                put("description", "Passphrase, required for passphrase-encrypted payloads")
                            }
                        },
                    required = listOf("payload"),
                ),
        ) { request -> execute(request.arguments) }
    }

    companion object {
        const val TOOL_NAME = "decrypt_text"
        private const val TAG = "MCP:DecryptTextHandler"
    }
}

// ─────────────────────────────────────────────────────────────────────────────
// keystore_generate_key
// ─────────────────────────────────────────────────────────────────────────────

/**
 * MCP tool handler for `keystore_generate_key`.
 * Input: `{ "alias": <string>, "algorithm": <string>?, "key_size": <int>? }`.
 */
class KeystoreGenerateKeyHandler(
    private val context: Context,
) {
    @Suppress("TooGenericExceptionCaught", "LongMethod")
    suspend fun execute(arguments: JsonObject?): CallToolResult {
        val alias = McpToolUtils.requireString(arguments, "alias").trim()
        val algorithm = McpToolUtils.optionalString(arguments, "algorithm", "RSA").uppercase()
        Log.d(TAG, "Executing keystore_generate_key")

        if (alias.isEmpty()) throw McpToolException.InvalidParams("Parameter 'alias' must not be empty.")
        val keyStore = CryptoVault.keyStore()
        if (keyStore.containsAlias(alias)) {
            throw McpToolException.InvalidParams(
                "A key named '$alias' already exists. Delete it with keystore_delete_key first, or pick " +
                    "another alias.",
            )
        }
        val generated =
            withContext(Dispatchers.IO) {
                try {
                    when (algorithm) {
                        "RSA" -> {
                            val size = McpToolUtils.optionalInt(arguments, "key_size", RSA_DEFAULT_BITS)
                            require(size in RSA_MIN_BITS..RSA_MAX_BITS) {
                                "'key_size' for RSA must be between $RSA_MIN_BITS and $RSA_MAX_BITS."
                            }
                            val generator = KeyPairGenerator.getInstance("RSA", "AndroidKeyStore")
                            generator.initialize(
                                KeyGenParameterSpec
                                    .Builder(alias, SIGN_PURPOSES)
                                    .setKeySize(size)
                                    .setDigests(KeyProperties.DIGEST_SHA256, KeyProperties.DIGEST_SHA512)
                                    .setSignaturePaddings(KeyProperties.SIGNATURE_PADDING_RSA_PKCS1)
                                    .build(),
                            )
                            generator.generateKeyPair()
                            "$algorithm-$size"
                        }

                        "EC" -> {
                            val curve = McpToolUtils.optionalString(arguments, "curve", "secp256r1")
                            val generator = KeyPairGenerator.getInstance("EC", "AndroidKeyStore")
                            generator.initialize(
                                KeyGenParameterSpec
                                    .Builder(alias, SIGN_PURPOSES)
                                    .setAlgorithmParameterSpec(ECGenParameterSpec(curve))
                                    .setDigests(KeyProperties.DIGEST_SHA256, KeyProperties.DIGEST_SHA512)
                                    .build(),
                            )
                            generator.generateKeyPair()
                            "$algorithm-$curve"
                        }

                        "AES" -> {
                            CryptoVault.ensureAesKey(alias)
                            "$algorithm-256"
                        }

                        else -> throw McpToolException.InvalidParams(
                            "Parameter 'algorithm' must be RSA, EC or AES. Got '$algorithm'.",
                        )
                    }
                } catch (exception: IllegalArgumentException) {
                    throw McpToolException.InvalidParams(exception.message ?: "Invalid key parameters.")
                } catch (exception: Exception) {
                    throw McpToolException.ActionFailed(
                        "Could not generate the key '$alias': " +
                            (exception.message ?: exception.javaClass.simpleName),
                    )
                }
            }
        val json =
            buildJsonObject {
                put("alias", alias)
                put("algorithm", algorithm)
                put("spec", generated)
                put("hardware_backed", true)
                put("exportable", false)
                put("usable_for", if (algorithm == "AES") "encryption" else "signing")
            }
        return McpToolUtils.textResult(json.toString())
    }

    fun register(
        registrar: LoggedToolRegistrar,
        toolNamePrefix: String,
    ) {
        registrar.addTool(
            toolName = TOOL_NAME,
            name = "$toolNamePrefix$TOOL_NAME",
            description =
                "Creates a key inside the Android KeyStore, where the private half can never be " +
                    "exported — that is the point of using it, because keystore_sign can then prove " +
                    "authorship without the secret ever entering the app's memory. 'algorithm' picks " +
                    "the shape: RSA (default, sign and verify, 2048-4096 bits), EC (smaller keys, a " +
                    "named curve such as secp256r1) or AES-256 for symmetric encryption. Aliases must " +
                    "be unique — list what exists with keystore_list_keys. Hardware backing depends " +
                    "on the device; the key works either way.",
            inputSchema =
                ToolSchema(
                    properties =
                        buildJsonObject {
                            putJsonObject("alias") {
                                put("type", "string")
                                put("description", "Unique name for the new key, e.g. \"my-signing-key\"")
                            }
                            putJsonObject("algorithm") {
                                put("type", "string")
                                put("description", "RSA, EC or AES (default RSA)")
                            }
                            putJsonObject("key_size") {
                                put("type", "integer")
                                put("description", "RSA modulus size in bits (default $RSA_DEFAULT_BITS)")
                            }
                            putJsonObject("curve") {
                                put("type", "string")
                                put("description", "EC curve name when algorithm is EC (default secp256r1)")
                            }
                        },
                    required = listOf("alias"),
                ),
        ) { request -> execute(request.arguments) }
    }

    companion object {
        const val TOOL_NAME = "keystore_generate_key"
        private const val TAG = "MCP:KeystoreGenerateKeyHandler"
        private const val RSA_DEFAULT_BITS = 2048
        private const val RSA_MIN_BITS = 2048
        private const val RSA_MAX_BITS = 4096
        private const val SIGN_PURPOSES = KeyProperties.PURPOSE_SIGN or KeyProperties.PURPOSE_VERIFY
    }
}

// ─────────────────────────────────────────────────────────────────────────────
// keystore_list_keys
// ─────────────────────────────────────────────────────────────────────────────

/** MCP tool handler for `keystore_list_keys`. Input: `{}`. */
class KeystoreListKeysHandler(
    private val context: Context,
) {
    @Suppress("TooGenericExceptionCaught")
    suspend fun execute(arguments: JsonObject?): CallToolResult {
        Log.d(TAG, "Executing keystore_list_keys")
        val entries =
            withContext(Dispatchers.IO) {
                val keyStore = CryptoVault.keyStore()
                val aliases = keyStore.aliases().toList().sorted()
                aliases.map { alias ->
                    buildJsonObject {
                        put("alias", alias)
                        put("is_key_pair", keyStore.isKeyEntry(alias))
                        put("is_certificate", keyStore.isCertificateEntry(alias))
                        val certificate = keyStore.getCertificate(alias)
                        put("public_algorithm", certificate?.publicKey?.algorithm.orEmpty())
                        put("certificate_type", certificate?.type.orEmpty())
                        put("created_epoch_ms", keyStore.getCreationDate(alias)?.time ?: 0L)
                    }
                }
            }
        val json =
            buildJsonObject {
                put("count", entries.size)
                putJsonArray("keys") { entries.forEach { add(it) } }
            }
        return McpToolUtils.textResult(json.toString())
    }

    fun register(
        registrar: LoggedToolRegistrar,
        toolNamePrefix: String,
    ) {
        registrar.addTool(
            toolName = TOOL_NAME,
            name = "$toolNamePrefix$TOOL_NAME",
            description =
                "Lists every key this app holds in the Android KeyStore, with its alias, whether it " +
                    "is a key pair, the public-key algorithm and the creation date. Call it before " +
                    "generating a key so you do not collide on an alias, and before decrypting or " +
                    "signing to confirm the key still exists — a payload sealed to a deleted key can " +
                    "never be opened again. Private key material is never returned, because the " +
                    "KeyStore does not allow it to leave.",
            inputSchema = ToolSchema(properties = buildJsonObject {}, required = emptyList()),
        ) { request -> execute(request.arguments) }
    }

    companion object {
        const val TOOL_NAME = "keystore_list_keys"
        private const val TAG = "MCP:KeystoreListKeysHandler"
    }
}

// ─────────────────────────────────────────────────────────────────────────────
// keystore_delete_key
// ─────────────────────────────────────────────────────────────────────────────

/** MCP tool handler for `keystore_delete_key`. Input: `{ "alias": <string> }`. */
class KeystoreDeleteKeyHandler(
    private val context: Context,
) {
    @Suppress("TooGenericExceptionCaught")
    suspend fun execute(arguments: JsonObject?): CallToolResult {
        val alias = McpToolUtils.requireString(arguments, "alias").trim()
        Log.d(TAG, "Executing keystore_delete_key")

        val existed =
            withContext(Dispatchers.IO) {
                val keyStore = CryptoVault.keyStore()
                if (!keyStore.containsAlias(alias)) {
                    false
                } else {
                    keyStore.deleteEntry(alias)
                    true
                }
            }
        if (!existed) {
            throw McpToolException.InvalidParams(
                "No KeyStore key named '$alias'. Use keystore_list_keys to see the aliases that exist.",
            )
        }
        val json =
            buildJsonObject {
                put("alias", alias)
                put("deleted", true)
                put("note", "Any data sealed with this key can no longer be decrypted.")
            }
        return McpToolUtils.textResult(json.toString())
    }

    fun register(
        registrar: LoggedToolRegistrar,
        toolNamePrefix: String,
    ) {
        registrar.addTool(
            toolName = TOOL_NAME,
            name = "$toolNamePrefix$TOOL_NAME",
            description =
                "Permanently removes a key from the Android KeyStore. Use it when a key is " +
                    "compromised, is superseded, or was created by mistake, and to clear an alias " +
                    "before regenerating it. This is irreversible and has a real consequence: " +
                    "everything encrypted with that key — including secrets stored through " +
                    "secret_store — becomes permanently unreadable, so check with keystore_list_keys " +
                    "and secret_list what depends on it first.",
            inputSchema =
                ToolSchema(
                    properties =
                        buildJsonObject {
                            putJsonObject("alias") {
                                put("type", "string")
                                put("description", "Alias of the key to delete")
                            }
                        },
                    required = listOf("alias"),
                ),
        ) { request -> execute(request.arguments) }
    }

    companion object {
        const val TOOL_NAME = "keystore_delete_key"
        private const val TAG = "MCP:KeystoreDeleteKeyHandler"
    }
}

// ─────────────────────────────────────────────────────────────────────────────
// keystore_sign
// ─────────────────────────────────────────────────────────────────────────────

/**
 * MCP tool handler for `keystore_sign`.
 * Input: `{ "alias": <string>, "content": <string>, "input_encoding": <string>? }`.
 */
class KeystoreSignHandler(
    private val context: Context,
) {
    @Suppress("TooGenericExceptionCaught")
    suspend fun execute(arguments: JsonObject?): CallToolResult {
        val alias = McpToolUtils.requireString(arguments, "alias").trim()
        val content = McpToolUtils.requireString(arguments, "content")
        val encoding = McpToolUtils.optionalString(arguments, "input_encoding", "utf8")
        Log.d(TAG, "Executing keystore_sign")

        val data = CryptoVault.decodeInput(content, encoding, "content")
        val result =
            withContext(Dispatchers.IO) {
                val keyStore = CryptoVault.keyStore()
                val privateKey =
                    keyStore.getKey(alias, null)
                        ?: throw McpToolException.InvalidParams(
                            "No KeyStore key named '$alias'. Use keystore_list_keys to see the aliases.",
                        )
                val algorithm = signatureAlgorithmFor(privateKey.algorithm)
                try {
                    val signer = Signature.getInstance(algorithm)
                    signer.initSign(privateKey as PrivateKey)
                    signer.update(data)
                    Triple(algorithm, CryptoVault.encode(signer.sign()), privateKey.algorithm)
                } catch (exception: Exception) {
                    throw McpToolException.ActionFailed(
                        "Could not sign with '$alias': ${exception.message ?: exception.javaClass.simpleName}. " +
                            "The key may not have been created for signing — regenerate it with " +
                            "keystore_generate_key.",
                    )
                }
            }
        val json =
            buildJsonObject {
                put("alias", alias)
                put("key_algorithm", result.third)
                put("signature_algorithm", result.first)
                put("input_chars", content.length)
                put("input_bytes", data.size)
                put("signature", result.second)
                put("verify_with", "keystore_verify using the same alias, content and signature")
            }
        return McpToolUtils.textResult(json.toString())
    }

    fun register(
        registrar: LoggedToolRegistrar,
        toolNamePrefix: String,
    ) {
        registrar.addTool(
            toolName = TOOL_NAME,
            name = "$toolNamePrefix$TOOL_NAME",
            description =
                "Signs text with a KeyStore private key and returns the base64 signature. Use it to " +
                    "produce a tamper-evident receipt: the signed payload can later be checked with " +
                    "keystore_verify, which succeeds only if the exact same bytes are presented, so a " +
                    "third party can tell that nothing changed. Pass 'input_encoding': \"base64\" when " +
                    "the thing being signed is binary (a file hash, a token, a certificate) rather " +
                    "than text. The private key stays inside the KeyStore throughout, which is why " +
                    "this can be offered at all.",
            inputSchema =
                ToolSchema(
                    properties =
                        buildJsonObject {
                            putJsonObject("alias") {
                                put("type", "string")
                                put("description", "Alias of the signing key")
                            }
                            putJsonObject("content") {
                                put("type", "string")
                                put("description", "Text (or base64 data) to sign")
                            }
                            putJsonObject("input_encoding") {
                                put("type", "string")
                                put("description", "utf8 or base64 (default utf8)")
                            }
                        },
                    required = listOf("alias", "content"),
                ),
        ) { request -> execute(request.arguments) }
    }

    companion object {
        const val TOOL_NAME = "keystore_sign"
        private const val TAG = "MCP:KeystoreSignHandler"
    }
}

// ─────────────────────────────────────────────────────────────────────────────
// keystore_verify
// ─────────────────────────────────────────────────────────────────────────────

/**
 * MCP tool handler for `keystore_verify`.
 * Input: `{ "alias": <string>, "content": <string>, "signature": <string>, "input_encoding": <string>? }`.
 */
class KeystoreVerifyHandler(
    private val context: Context,
) {
    @Suppress("TooGenericExceptionCaught")
    suspend fun execute(arguments: JsonObject?): CallToolResult {
        val alias = McpToolUtils.requireString(arguments, "alias").trim()
        val content = McpToolUtils.requireString(arguments, "content")
        val signatureText = McpToolUtils.requireString(arguments, "signature").trim()
        val encoding = McpToolUtils.optionalString(arguments, "input_encoding", "utf8")
        Log.d(TAG, "Executing keystore_verify")

        val data = CryptoVault.decodeInput(content, encoding, "content")
        val signature = CryptoVault.decode(signatureText, "signature")
        val result =
            withContext(Dispatchers.IO) {
                val keyStore = CryptoVault.keyStore()
                val certificate =
                    keyStore.getCertificate(alias)
                        ?: throw McpToolException.InvalidParams(
                            "No certificate for '$alias'. Use keystore_list_keys to see the aliases.",
                        )
                val algorithm = signatureAlgorithmFor(certificate.publicKey.algorithm)
                try {
                    val verifier = Signature.getInstance(algorithm)
                    verifier.initVerify(certificate.publicKey)
                    verifier.update(data)
                    Triple(verifier.verify(signature), algorithm, certificate.publicKey.algorithm)
                } catch (exception: Exception) {
                    throw McpToolException.ActionFailed(
                        "Could not verify with '$alias': " +
                            (exception.message ?: exception.javaClass.simpleName),
                    )
                }
            }
        val json =
            buildJsonObject {
                put("alias", alias)
                put("valid", result.first)
                put("signature_algorithm", result.second)
                put("key_algorithm", result.third)
                val verdict =
                    if (result.first) {
                        "Signature matches the content byte for byte."
                    } else {
                        "Signature does not match."
                    }
                put("note", verdict)
            }
        return McpToolUtils.textResult(json.toString())
    }

    fun register(
        registrar: LoggedToolRegistrar,
        toolNamePrefix: String,
    ) {
        registrar.addTool(
            toolName = TOOL_NAME,
            name = "$toolNamePrefix$TOOL_NAME",
            description =
                "Checks a signature produced by keystore_sign against the content it should cover, " +
                    "returning 'valid': true only when they match exactly. Use it to confirm that a " +
                    "payload is unchanged — before acting on a record an earlier session signed, when " +
                    "comparing two versions of a document, or to prove to the user that a value came " +
                    "from this device and not from somewhere else. The same 'input_encoding' used for " +
                    "signing must be used here, and whitespace or a re-encoded string will make the " +
                    "check fail.",
            inputSchema =
                ToolSchema(
                    properties =
                        buildJsonObject {
                            putJsonObject("alias") {
                                put("type", "string")
                                put("description", "Alias of the key that signed the content")
                            }
                            putJsonObject("content") {
                                put("type", "string")
                                put("description", "Content to check, byte for byte as it was signed")
                            }
                            putJsonObject("signature") {
                                put("type", "string")
                                put("description", "Base64 signature returned by keystore_sign")
                            }
                            putJsonObject("input_encoding") {
                                put("type", "string")
                                put("description", "utf8 or base64, matching the signing call (default utf8)")
                            }
                        },
                    required = listOf("alias", "content", "signature"),
                ),
        ) { request -> execute(request.arguments) }
    }

    companion object {
        const val TOOL_NAME = "keystore_verify"
        private const val TAG = "MCP:KeystoreVerifyHandler"
    }
}

// ─────────────────────────────────────────────────────────────────────────────
// secret_store
// ─────────────────────────────────────────────────────────────────────────────

/** MCP tool handler for `secret_store`. Input: `{ "name": <string>, "value": <string> }`. */
class SecretStoreHandler(
    private val context: Context,
) {
    suspend fun execute(arguments: JsonObject?): CallToolResult {
        val name = McpToolUtils.requireString(arguments, "name").trim()
        val value = McpToolUtils.requireString(arguments, "value")
        Log.d(TAG, "Executing secret_store")

        if (name.isEmpty()) throw McpToolException.InvalidParams("Parameter 'name' must not be empty.")
        val payload =
            withContext(Dispatchers.IO) {
                CryptoVault.encryptWithKeyStore(VAULT_ALIAS, value)
            }
        val prefs = CryptoVault.secrets(context)
        val now = System.currentTimeMillis()
        val created = prefs.getLong("$name.created", now)
        prefs
            .edit()
            .putString(name, payload)
            .putLong("$name.created", created)
            .putLong("$name.updated", now)
            .apply()
        val json =
            buildJsonObject {
                put("name", name)
                put("stored", true)
                put("value_chars", value.length)
                put("ciphertext_chars", payload.length)
                put("created_epoch_ms", created)
                put("updated_epoch_ms", now)
            }
        return McpToolUtils.textResult(json.toString())
    }

    fun register(
        registrar: LoggedToolRegistrar,
        toolNamePrefix: String,
    ) {
        registrar.addTool(
            toolName = TOOL_NAME,
            name = "$toolNamePrefix$TOOL_NAME",
            description =
                "Saves a named secret — an API token, a passphrase, a PIN, a one-time code — " +
                    "encrypted with a key held in the Android KeyStore, and returns only metadata. " +
                    "Use it instead of writing credentials into a plain file or into agent memory, " +
                    "where any file-reading tool would expose them. Reusing a name overwrites that " +
                    "secret, so later reads always return the newest value; secret_list shows which " +
                    "names exist and when each changed. Nothing is ever returned in plaintext except " +
                    "through secret_get.",
            inputSchema =
                ToolSchema(
                    properties =
                        buildJsonObject {
                            putJsonObject("name") {
                                put("type", "string")
                                put("description", "Identifier for the secret, e.g. \"github_token\"")
                            }
                            putJsonObject("value") {
                                put("type", "string")
                                put("description", "Secret value to store, encrypted at rest")
                            }
                        },
                    required = listOf("name", "value"),
                ),
        ) { request -> execute(request.arguments) }
    }

    companion object {
        const val TOOL_NAME = "secret_store"
        private const val TAG = "MCP:SecretStoreHandler"
    }
}

// ─────────────────────────────────────────────────────────────────────────────
// secret_get
// ─────────────────────────────────────────────────────────────────────────────

/** MCP tool handler for `secret_get`. Input: `{ "name": <string> }`. */
class SecretGetHandler(
    private val context: Context,
) {
    suspend fun execute(arguments: JsonObject?): CallToolResult {
        val name = McpToolUtils.requireString(arguments, "name").trim()
        Log.d(TAG, "Executing secret_get")

        val payload =
            CryptoVault.secrets(context).getString(name, null)
                ?: throw McpToolException.InvalidParams(
                    "No secret named '$name'. Call secret_list to see the names that exist.",
                )
        val value =
            withContext(Dispatchers.IO) {
                CryptoVault.decryptWithKeyStore(payload)
            }
        val json =
            buildJsonObject {
                put("name", name)
                put("found", true)
                put("value_chars", value.length)
                put("value", value)
            }
        return McpToolUtils.textResult(json.toString())
    }

    fun register(
        registrar: LoggedToolRegistrar,
        toolNamePrefix: String,
    ) {
        registrar.addTool(
            toolName = TOOL_NAME,
            name = "$toolNamePrefix$TOOL_NAME",
            description =
                "Reads back a secret stored with secret_store and returns its plaintext. Use it at " +
                    "the moment a credential is needed — filling a form, calling an authenticated " +
                    "API with http_request, signing in — rather than copying the value anywhere else. " +
                    "The value only exists in this app's memory and in the KeyStore-protected store, " +
                    "and it lives on this device only: it cannot be recovered on another device or " +
                    "after the protecting key is deleted.",
            inputSchema =
                ToolSchema(
                    properties =
                        buildJsonObject {
                            putJsonObject("name") {
                                put("type", "string")
                                put("description", "Name of the secret to read")
                            }
                        },
                    required = listOf("name"),
                ),
        ) { request -> execute(request.arguments) }
    }

    companion object {
        const val TOOL_NAME = "secret_get"
        private const val TAG = "MCP:SecretGetHandler"
    }
}

// ─────────────────────────────────────────────────────────────────────────────
// secret_delete
// ─────────────────────────────────────────────────────────────────────────────

/** MCP tool handler for `secret_delete`. Input: `{ "name": <string> }`. */
class SecretDeleteHandler(
    private val context: Context,
) {
    suspend fun execute(arguments: JsonObject?): CallToolResult {
        val name = McpToolUtils.requireString(arguments, "name").trim()
        Log.d(TAG, "Executing secret_delete")

        val prefs = CryptoVault.secrets(context)
        if (!prefs.contains(name)) {
            throw McpToolException.InvalidParams(
                "No secret named '$name'. Call secret_list to see the names that exist.",
            )
        }
        val updated = prefs.getLong("$name.updated", 0L)
        prefs
            .edit()
            .remove(name)
            .remove("$name.created")
            .remove("$name.updated")
            .apply()
        val json =
            buildJsonObject {
                put("name", name)
                put("deleted", true)
                put("had_updated_epoch_ms", updated)
            }
        return McpToolUtils.textResult(json.toString())
    }

    fun register(
        registrar: LoggedToolRegistrar,
        toolNamePrefix: String,
    ) {
        registrar.addTool(
            toolName = TOOL_NAME,
            name = "$toolNamePrefix$TOOL_NAME",
            description =
                "Permanently deletes a stored secret. Use it when a credential is rotated, when a " +
                    "session is over, or to tidy up after a one-off task — a store full of stale " +
                    "tokens is a liability, not a convenience. Deletion is immediate and cannot be " +
                    "undone, so make sure nothing still needs the value; secret_get is the last " +
                    "chance to read it.",
            inputSchema =
                ToolSchema(
                    properties =
                        buildJsonObject {
                            putJsonObject("name") {
                                put("type", "string")
                                put("description", "Name of the secret to delete")
                            }
                        },
                    required = listOf("name"),
                ),
        ) { request -> execute(request.arguments) }
    }

    companion object {
        const val TOOL_NAME = "secret_delete"
        private const val TAG = "MCP:SecretDeleteHandler"
    }
}

// ─────────────────────────────────────────────────────────────────────────────
// secret_list
// ─────────────────────────────────────────────────────────────────────────────

/** MCP tool handler for `secret_list`. Input: `{}`. */
class SecretListHandler(
    private val context: Context,
) {
    suspend fun execute(arguments: JsonObject?): CallToolResult {
        Log.d(TAG, "Executing secret_list")
        val prefs = CryptoVault.secrets(context)
        val names =
            prefs.all.keys
                .filterNot { it.endsWith(".created") || it.endsWith(".updated") }
                .sorted()
        val json =
            buildJsonObject {
                put("count", names.size)
                putJsonArray("secrets") {
                    names.forEach { name ->
                        add(
                            buildJsonObject {
                                put("name", name)
                                put("created_epoch_ms", prefs.getLong("$name.created", 0L))
                                put("updated_epoch_ms", prefs.getLong("$name.updated", 0L))
                                put("ciphertext_chars", prefs.getString(name, "").orEmpty().length)
                            },
                        )
                    }
                }
            }
        return McpToolUtils.textResult(json.toString())
    }

    fun register(
        registrar: LoggedToolRegistrar,
        toolNamePrefix: String,
    ) {
        registrar.addTool(
            toolName = TOOL_NAME,
            name = "$toolNamePrefix$TOOL_NAME",
            description =
                "Lists the names of the secrets stored on this device with their creation and update " +
                    "times and ciphertext sizes — never their values. Use it to see which credentials " +
                    "are already available before asking the user for one, to check whether a secret " +
                    "is stale enough to rotate, and as the reference list before secret_get or " +
                    "secret_delete.",
            inputSchema = ToolSchema(properties = buildJsonObject {}, required = emptyList()),
        ) { request -> execute(request.arguments) }
    }

    companion object {
        const val TOOL_NAME = "secret_list"
        private const val TAG = "MCP:SecretListHandler"
    }
}

// ─────────────────────────────────────────────────────────────────────────────
// generate_password
// ─────────────────────────────────────────────────────────────────────────────

/**
 * MCP tool handler for `generate_password`.
 * Input: `{ "length": <int>?, "count": <int>?, "symbols": <bool>?, "digits": <bool>?, "exclude_ambiguous": <bool>? }`.
 */
class GeneratePasswordHandler(
    private val context: Context,
) {
    suspend fun execute(arguments: JsonObject?): CallToolResult {
        val length = McpToolUtils.optionalInt(arguments, "length", DEFAULT_LENGTH).coerceIn(MIN_LENGTH, MAX_LENGTH)
        val count = McpToolUtils.optionalInt(arguments, "count", 1).coerceIn(1, MAX_COUNT)
        val symbols = McpToolUtils.optionalBoolean(arguments, "symbols", true)
        val digits = McpToolUtils.optionalBoolean(arguments, "digits", true)
        val excludeAmbiguous = McpToolUtils.optionalBoolean(arguments, "exclude_ambiguous", false)
        Log.d(TAG, "Executing generate_password")

        val alphabet = PasswordTools.alphabet(symbols, digits, excludeAmbiguous)
        val passwords = (1..count).map { PasswordTools.generate(length, alphabet) }
        val perEntryEntropy = length * (ln(alphabet.length.toDouble()) / ln(2.0))
        val json =
            buildJsonObject {
                put("count", count)
                put("length", length)
                put("alphabet_size", alphabet.length)
                put("entropy_bits_per_password", Math.round(perEntryEntropy * 10.0) / 10.0)
                putJsonArray("passwords") { passwords.forEach { add(it) } }
            }
        return McpToolUtils.textResult(json.toString())
    }

    fun register(
        registrar: LoggedToolRegistrar,
        toolNamePrefix: String,
    ) {
        registrar.addTool(
            toolName = TOOL_NAME,
            name = "$toolNamePrefix$TOOL_NAME",
            description =
                "Generates cryptographically random passwords, one or several at a time, and reports " +
                    "the entropy each one carries. Use it whenever a task needs a credential rather " +
                    "than inventing one — and pair it with secret_store so the value is encrypted at " +
                    "rest instead of sitting in the transcript. 'length' defaults to $DEFAULT_LENGTH " +
                    "characters of a case-sensitive alphanumeric alphabet, widened with symbols when " +
                    "'symbols' is true. Set 'exclude_ambiguous': false keeps 0/O, 1/l and similar " +
                    "pairs; setting it true is useful when a human has to retype the password.",
            inputSchema =
                ToolSchema(
                    properties =
                        buildJsonObject {
                            putJsonObject("length") {
                                put("type", "integer")
                                put("description", "Characters per password (default $DEFAULT_LENGTH)")
                            }
                            putJsonObject("count") {
                                put("type", "integer")
                                put("description", "How many passwords to generate (default 1)")
                            }
                            putJsonObject("symbols") {
                                put("type", "boolean")
                                put("description", "Include punctuation (default true)")
                            }
                            putJsonObject("digits") {
                                put("type", "boolean")
                                put("description", "Include digits (default true)")
                            }
                            putJsonObject("exclude_ambiguous") {
                                put("type", "boolean")
                                put("description", "Skip look-alike characters such as 0/O and 1/l (default false)")
                            }
                        },
                    required = emptyList(),
                ),
        ) { request -> execute(request.arguments) }
    }

    companion object {
        const val TOOL_NAME = "generate_password"
        private const val TAG = "MCP:GeneratePasswordHandler"
        private const val DEFAULT_LENGTH = 20
        private const val MIN_LENGTH = 8
        private const val MAX_LENGTH = 128
        private const val MAX_COUNT = 20
    }
}

// ─────────────────────────────────────────────────────────────────────────────
// password_strength
// ─────────────────────────────────────────────────────────────────────────────

/** MCP tool handler for `password_strength`. Input: `{ "password": <string> }`. */
class PasswordStrengthHandler(
    private val context: Context,
) {
    suspend fun execute(arguments: JsonObject?): CallToolResult {
        val password = McpToolUtils.requireString(arguments, "password")
        Log.d(TAG, "Executing password_strength")

        val classes = PasswordTools.characterClassCount(password)
        val effectiveAlphabet = PasswordTools.effectiveAlphabetSize(password)
        val entropy = if (password.isEmpty()) 0.0 else password.length * (ln(effectiveAlphabet.toDouble()) / ln(2.0))
        val json =
            buildJsonObject {
                put("length", password.length)
                put("character_classes", classes)
                put("effective_alphabet_size", effectiveAlphabet)
                put("entropy_bits", Math.round(entropy * 10.0) / 10.0)
                put("assessment", PasswordTools.assess(entropy, password))
                putJsonArray("suggestions") {
                    PasswordTools.suggestions(password).forEach { add(it) }
                }
            }
        return McpToolUtils.textResult(json.toString())
    }

    fun register(
        registrar: LoggedToolRegistrar,
        toolNamePrefix: String,
    ) {
        registrar.addTool(
            toolName = TOOL_NAME,
            name = "$toolNamePrefix$TOOL_NAME",
            description =
                "Estimates the strength of a password and explains why, without sending it anywhere. " +
                    "It reports length, how many character classes are used, the effective alphabet " +
                    "size, the resulting entropy in bits, a plain assessment (very weak to very " +
                    "strong) and concrete suggestions. Use it before storing a user-supplied " +
                    "credential with secret_store, or when a task requires a policy check — a 16 " +
                    "character password and a 6 character one with every symbol are not comparable, " +
                    "and the entropy figure shows by how much.",
            inputSchema =
                ToolSchema(
                    properties =
                        buildJsonObject {
                            putJsonObject("password") {
                                put("type", "string")
                                put("description", "Password to evaluate; it is never stored or transmitted")
                            }
                        },
                    required = listOf("password"),
                ),
        ) { request -> execute(request.arguments) }
    }

    companion object {
        const val TOOL_NAME = "password_strength"
        private const val TAG = "MCP:PasswordStrengthHandler"
    }
}

/** Password generation and analysis helpers shared by the vault bundle. */
internal object PasswordTools {
    private const val LOWER = "abcdefghijklmnopqrstuvwxyz"
    private const val UPPER = "ABCDEFGHIJKLMNOPQRSTUVWXYZ"
    private const val DIGITS = "0123456789"
    private const val SYMBOLS = "!@#\$%^&*()-_=+[]{};:,.?/"
    private const val AMBIGUOUS = "0O1lI|`'\""
    private val random = SecureRandom()

    /** Builds the alphabet a generated password should draw from. */
    fun alphabet(
        symbols: Boolean,
        digits: Boolean,
        excludeAmbiguous: Boolean,
    ): String {
        val base = LOWER + UPPER + (if (digits) DIGITS else "") + (if (symbols) SYMBOLS else "")
        if (!excludeAmbiguous) return base
        val filtered = base.filterNot { AMBIGUOUS.contains(it) }
        return filtered.ifEmpty { base }
    }

    /** Returns [length] random characters from [alphabet]. */
    fun generate(
        length: Int,
        alphabet: String,
    ): String =
        buildString(length) {
            repeat(length) { append(alphabet[random.nextInt(alphabet.length)]) }
        }

    /** How many of the four character classes the password uses. */
    fun characterClassCount(password: String): Int =
        listOf(
            password.any { it.isLowerCase() },
            password.any { it.isUpperCase() },
            password.any { it.isDigit() },
            password.any { !it.isLetterOrDigit() },
        ).count { it }

    /** Alphabet size implied by the classes actually present, never below 10. */
    fun effectiveAlphabetSize(password: String): Int {
        var size = 0
        if (password.any { it.isLowerCase() }) size += LOWER.length
        if (password.any { it.isUpperCase() }) size += UPPER.length
        if (password.any { it.isDigit() }) size += DIGITS.length
        if (password.any { !it.isLetterOrDigit() }) size += SYMBOLS.length
        return size.coerceAtLeast(DIGITS.length)
    }

    /** Plain-language verdict for an entropy figure. */
    fun assess(
        entropyBits: Double,
        password: String,
    ): String =
        when {
            password.isEmpty() -> "empty"
            entropyBits < 30 -> "very weak"
            entropyBits < 50 -> "weak"
            entropyBits < 70 -> "reasonable"
            entropyBits < 100 -> "strong"
            else -> "very strong"
        }

    /** Actionable advice for the password that was checked. */
    fun suggestions(password: String): List<String> {
        val advice = mutableListOf<String>()
        if (password.length < 12) advice.add("Use at least 12 characters — length buys more strength than symbols.")
        if (!password.any { it.isLowerCase() } || !password.any { it.isUpperCase() }) {
            advice.add("Mix upper and lower case.")
        }
        if (!password.any { it.isDigit() }) advice.add("Add digits.")
        if (!password.any { !it.isLetterOrDigit() }) advice.add("Add punctuation.")
        if (password.toSet().size <= password.length / 2 && password.length > 3) {
            advice.add("Avoid repeating the same few characters.")
        }
        if (password.length >= 16 && characterClassCount(password) >= 3) {
            advice.add("Long enough to be usable as-is; store it with secret_store rather than reusing it.")
        }
        return advice
    }
}

/** Registers the crypto bundle: KeyStore keys, encryption, signing, secrets and passwords. */
fun registerCryptoVaultTools(
    registrar: LoggedToolRegistrar,
    context: Context,
    toolNamePrefix: String,
    perms: ToolPermissionsConfig,
) {
    EncryptTextHandler(context).register(registrar, toolNamePrefix)
    DecryptTextHandler(context).register(registrar, toolNamePrefix)
    KeystoreGenerateKeyHandler(context).register(registrar, toolNamePrefix)
    KeystoreListKeysHandler(context).register(registrar, toolNamePrefix)
    KeystoreDeleteKeyHandler(context).register(registrar, toolNamePrefix)
    KeystoreSignHandler(context).register(registrar, toolNamePrefix)
    KeystoreVerifyHandler(context).register(registrar, toolNamePrefix)
    SecretStoreHandler(context).register(registrar, toolNamePrefix)
    SecretGetHandler(context).register(registrar, toolNamePrefix)
    SecretDeleteHandler(context).register(registrar, toolNamePrefix)
    SecretListHandler(context).register(registrar, toolNamePrefix)
    GeneratePasswordHandler(context).register(registrar, toolNamePrefix)
    PasswordStrengthHandler(context).register(registrar, toolNamePrefix)
}
