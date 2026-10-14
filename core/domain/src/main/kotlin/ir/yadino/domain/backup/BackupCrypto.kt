package ir.yadino.domain.backup

import ir.yadino.domain.json.Json
import ir.yadino.domain.json.JsonObject
import ir.yadino.domain.json.JsonValue
import ir.yadino.domain.json.jsonObject
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.nio.CharBuffer
import java.security.GeneralSecurityException
import java.security.SecureRandom
import java.text.Normalizer
import java.time.Instant
import java.util.Base64
import java.util.zip.GZIPInputStream
import java.util.zip.GZIPOutputStream
import javax.crypto.AEADBadTagException
import javax.crypto.Cipher
import javax.crypto.Mac
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

/** Result of decrypting an encrypted backup envelope. */
sealed interface DecryptResult {
    data class Success(val plainText: String) : DecryptResult
    data class Failure(val error: BackupError) : DecryptResult
}

/**
 * Password based encryption of backup files.
 *
 * ```
 * { "format": "yadino-encrypted-backup", "formatVersion": 1, "appVersion": "1.1.0",
 *   "createdAt": "2026-10-14T08:00:00Z",
 *   "kdf": { "algorithm": "PBKDF2-HMAC-SHA256", "iterations": 600000, "salt": "<base64>",
 *            "passwordNormalization": "fa-v1" },
 *   "cipher": { "algorithm": "AES-256-GCM", "iv": "<base64>", "tagBits": 128 },
 *   "compression": "gzip",
 *   "payload": "<base64>" }
 * ```
 *
 * - The payload is the plain backup document ([BackupCodec]) compressed with gzip and
 *   encrypted with AES-256-GCM, so the usual checksum and validation still run after
 *   decryption. Record counts and all content are only inside the payload.
 * - The key is derived with PBKDF2-HMAC-SHA256 from the normalised password and a random
 *   16-byte salt (a fresh salt and IV for every file).
 * - Every header value is authenticated as GCM associated data: changing any of them
 *   (for example lowering the iteration count) makes decryption fail.
 * - Password normalisation `fa-v1` makes the same password typed on a Persian, Arabic or
 *   English keyboard derive the same key: Unicode NFC, Arabic Yeh/Kaf mapped to the
 *   Persian letters and Persian/Arabic-Indic digits mapped to ASCII digits.
 */
object BackupCrypto {
    const val FORMAT = "yadino-encrypted-backup"
    const val FORMAT_VERSION = 1
    const val KDF = "PBKDF2-HMAC-SHA256"
    const val CIPHER = "AES-256-GCM"
    const val NORMALIZATION = "fa-v1"
    const val COMPRESSION = "gzip"

    /** OWASP (2023) recommendation for PBKDF2-HMAC-SHA256. */
    const val DEFAULT_ITERATIONS = 600_000
    const val MIN_ITERATIONS = 100_000
    /** Files asking for more work than this are rejected (protects against hostile files). */
    const val MAX_ITERATIONS = 5_000_000
    const val MIN_PASSWORD_LENGTH = 8
    const val MAX_PASSWORD_LENGTH = 256

    /** Upper bound for the decompressed payload (guards against decompression bombs). */
    const val MAX_PLAIN_BYTES = 256 * 1024 * 1024

    private const val SALT_BYTES = 16
    private const val IV_BYTES = 12
    private const val TAG_BITS = 128
    private const val KEY_BYTES = 32

    private val b64e = Base64.getEncoder()
    private val b64d = Base64.getDecoder()

    /** Problems with a new password, or null when it is acceptable. */
    enum class PasswordProblem { TOO_SHORT, TOO_LONG, BLANK }

    fun checkPassword(password: CharArray): PasswordProblem? {
        val normalized = normalize(password)
        return when {
            normalized.isBlank() -> PasswordProblem.BLANK
            normalized.codePointCount(0, normalized.length) < MIN_PASSWORD_LENGTH -> PasswordProblem.TOO_SHORT
            normalized.length > MAX_PASSWORD_LENGTH -> PasswordProblem.TOO_LONG
            else -> null
        }
    }

    /** True when [root] is an encrypted backup envelope. */
    fun isEnvelope(root: JsonValue): Boolean = root is JsonObject && root.string("format") == FORMAT

    /**
     * Encrypts a plain backup document. The returned text is itself JSON, so it can be
     * stored and transferred exactly like a plain backup.
     */
    fun encrypt(
        plainText: String,
        password: CharArray,
        appVersion: String,
        createdAt: Instant,
        iterations: Int = DEFAULT_ITERATIONS,
        random: SecureRandom = SecureRandom(),
    ): String {
        require(iterations in MIN_ITERATIONS..MAX_ITERATIONS) { "iterations out of range" }
        require(checkPassword(password) == null) { "unacceptable password" }
        val salt = ByteArray(SALT_BYTES).also(random::nextBytes)
        val iv = ByteArray(IV_BYTES).also(random::nextBytes)
        val header = Header(FORMAT_VERSION, appVersion, createdAt.toString(), iterations, salt, iv, TAG_BITS, COMPRESSION)
        val key = deriveKey(password, salt, iterations)
        try {
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.ENCRYPT_MODE, SecretKeySpec(key, "AES"), GCMParameterSpec(TAG_BITS, iv))
            cipher.updateAAD(header.associatedData())
            val payload = cipher.doFinal(gzip(plainText.toByteArray(Charsets.UTF_8)))
            val root = jsonObject(
                "format" to FORMAT,
                "formatVersion" to FORMAT_VERSION,
                "appVersion" to appVersion,
                "createdAt" to header.createdAt,
                "kdf" to jsonObject(
                    "algorithm" to KDF, "iterations" to iterations, "salt" to b64e.encodeToString(salt),
                    "passwordNormalization" to NORMALIZATION,
                ),
                "cipher" to jsonObject("algorithm" to CIPHER, "iv" to b64e.encodeToString(iv), "tagBits" to TAG_BITS),
                "compression" to COMPRESSION,
                "payload" to b64e.encodeToString(payload),
            )
            return Json.stringify(root)
        } finally {
            key.fill(0)
        }
    }

    /** Decrypts the envelope [text]. */
    fun decrypt(text: String, password: CharArray): DecryptResult {
        val root = try {
            Json.parse(text)
        } catch (e: Exception) {
            return DecryptResult.Failure(BackupError.NOT_JSON)
        }
        return decrypt(root, password)
    }

    /** Decrypts an already parsed envelope. */
    fun decrypt(root: JsonValue, password: CharArray): DecryptResult {
        if (!isEnvelope(root)) return DecryptResult.Failure(BackupError.NOT_A_YADINO_BACKUP)
        val header = when (val h = readHeader(root as JsonObject)) {
            is HeaderResult.Ok -> h.header
            is HeaderResult.Bad -> return DecryptResult.Failure(h.error)
        }
        val payload = root.string("payload")?.let { decodeBase64(it) }
            ?: return DecryptResult.Failure(BackupError.INVALID_CONTENT)
        if (payload.size < TAG_BITS / 8) return DecryptResult.Failure(BackupError.INVALID_CONTENT)
        val key = deriveKey(password, header.salt, header.iterations)
        val compressed = try {
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.DECRYPT_MODE, SecretKeySpec(key, "AES"), GCMParameterSpec(header.tagBits, header.iv))
            cipher.updateAAD(header.associatedData())
            cipher.doFinal(payload)
        } catch (e: AEADBadTagException) {
            // wrong password, or the file was modified after it was written
            return DecryptResult.Failure(BackupError.WRONG_PASSWORD)
        } catch (e: GeneralSecurityException) {
            return DecryptResult.Failure(BackupError.INVALID_CONTENT)
        } finally {
            key.fill(0)
        }
        val plain = if (header.compression == COMPRESSION) {
            try {
                gunzip(compressed, MAX_PLAIN_BYTES)
            } catch (e: IOException) {
                return DecryptResult.Failure(BackupError.INVALID_CONTENT)
            }
        } else {
            compressed
        }
        return DecryptResult.Success(plain.toString(Charsets.UTF_8))
    }

    // ------------------------------------------------------------------------------- header
    private class Header(
        val formatVersion: Int,
        val appVersion: String,
        val createdAt: String,
        val iterations: Int,
        val salt: ByteArray,
        val iv: ByteArray,
        val tagBits: Int,
        val compression: String,
    ) {
        /** Canonical, unambiguous encoding of every header value (length-prefixed). */
        fun associatedData(): ByteArray {
            val parts = listOf(
                FORMAT, formatVersion.toString(), appVersion, createdAt, KDF, iterations.toString(),
                b64e.encodeToString(salt), NORMALIZATION, CIPHER, b64e.encodeToString(iv), tagBits.toString(), compression,
            )
            return parts.joinToString("") { "${it.length}:$it;" }.toByteArray(Charsets.UTF_8)
        }
    }

    private sealed interface HeaderResult {
        class Ok(val header: Header) : HeaderResult
        class Bad(val error: BackupError) : HeaderResult
    }

    private fun readHeader(root: JsonObject): HeaderResult {
        val version = root.int("formatVersion") ?: return HeaderResult.Bad(BackupError.NOT_A_YADINO_BACKUP)
        if (version > FORMAT_VERSION) return HeaderResult.Bad(BackupError.NEWER_FORMAT)
        if (version < 1) return HeaderResult.Bad(BackupError.NOT_A_YADINO_BACKUP)
        val kdf = root.obj("kdf") ?: return HeaderResult.Bad(BackupError.INVALID_CONTENT)
        val cipher = root.obj("cipher") ?: return HeaderResult.Bad(BackupError.INVALID_CONTENT)
        if (kdf.string("algorithm") != KDF || cipher.string("algorithm") != CIPHER) {
            return HeaderResult.Bad(BackupError.INVALID_CONTENT)
        }
        if ((kdf.string("passwordNormalization") ?: NORMALIZATION) != NORMALIZATION) {
            return HeaderResult.Bad(BackupError.NEWER_FORMAT)
        }
        val iterations = kdf.int("iterations") ?: return HeaderResult.Bad(BackupError.INVALID_CONTENT)
        if (iterations !in MIN_ITERATIONS..MAX_ITERATIONS) return HeaderResult.Bad(BackupError.INVALID_CONTENT)
        val salt = kdf.string("salt")?.let { decodeBase64(it) } ?: return HeaderResult.Bad(BackupError.INVALID_CONTENT)
        if (salt.size !in 16..64) return HeaderResult.Bad(BackupError.INVALID_CONTENT)
        val iv = cipher.string("iv")?.let { decodeBase64(it) } ?: return HeaderResult.Bad(BackupError.INVALID_CONTENT)
        if (iv.size != IV_BYTES) return HeaderResult.Bad(BackupError.INVALID_CONTENT)
        val tagBits = cipher.int("tagBits") ?: TAG_BITS
        if (tagBits != TAG_BITS) return HeaderResult.Bad(BackupError.INVALID_CONTENT)
        val compression = root.string("compression") ?: "none"
        if (compression != COMPRESSION && compression != "none") return HeaderResult.Bad(BackupError.INVALID_CONTENT)
        return HeaderResult.Ok(
            Header(version, root.string("appVersion") ?: "", root.string("createdAt") ?: "", iterations, salt, iv, tagBits, compression),
        )
    }

    private fun decodeBase64(s: String): ByteArray? = try {
        b64d.decode(s)
    } catch (e: IllegalArgumentException) {
        null
    }

    // ---------------------------------------------------------------------------------- KDF
    /**
     * Normalisation `fa-v1`: NFC, Arabic Yeh (ي، ى) → Persian Yeh (ی), Arabic Kaf (ك) →
     * Persian Kaf (ک), Persian and Arabic-Indic digits → ASCII digits.
     */
    fun normalize(password: CharArray): String {
        val nfc = Normalizer.normalize(CharBuffer.wrap(password), Normalizer.Form.NFC)
        val sb = StringBuilder(nfc.length)
        for (ch in nfc) {
            sb.append(
                when (ch) {
                    '\u064A', '\u0649' -> '\u06CC'
                    '\u0643' -> '\u06A9'
                    in '\u06F0'..'\u06F9' -> '0' + (ch - '\u06F0')
                    in '\u0660'..'\u0669' -> '0' + (ch - '\u0660')
                    else -> ch
                },
            )
        }
        return sb.toString()
    }

    private fun deriveKey(password: CharArray, salt: ByteArray, iterations: Int): ByteArray {
        val bytes = normalize(password).toByteArray(Charsets.UTF_8)
        try {
            return pbkdf2HmacSha256(bytes, salt, iterations, KEY_BYTES)
        } finally {
            bytes.fill(0)
        }
    }

    /**
     * PBKDF2 (RFC 8018) with HMAC-SHA256 over explicit UTF-8 password bytes, so the result
     * never depends on how a security provider converts characters to bytes.
     */
    internal fun pbkdf2HmacSha256(password: ByteArray, salt: ByteArray, iterations: Int, keyBytes: Int): ByteArray {
        require(password.isNotEmpty() && iterations >= 1 && keyBytes >= 1)
        val mac = Mac.getInstance("HmacSHA256")
        mac.init(SecretKeySpec(password, "HmacSHA256"))
        val hLen = mac.macLength
        val out = ByteArray(keyBytes)
        val u = ByteArray(hLen)
        val t = ByteArray(hLen)
        var block = 1
        var offset = 0
        while (offset < keyBytes) {
            mac.update(salt)
            mac.update(byteArrayOf((block ushr 24).toByte(), (block ushr 16).toByte(), (block ushr 8).toByte(), block.toByte()))
            mac.doFinal(u, 0)
            System.arraycopy(u, 0, t, 0, hLen)
            for (i in 1 until iterations) {
                mac.update(u)
                mac.doFinal(u, 0)
                for (j in 0 until hLen) t[j] = (t[j].toInt() xor u[j].toInt()).toByte()
            }
            val n = minOf(hLen, keyBytes - offset)
            System.arraycopy(t, 0, out, offset, n)
            offset += n
            block++
        }
        u.fill(0)
        t.fill(0)
        return out
    }

    // ---------------------------------------------------------------------------- compression
    private fun gzip(data: ByteArray): ByteArray {
        val out = ByteArrayOutputStream(data.size / 4 + 64)
        GZIPOutputStream(out).use { it.write(data) }
        return out.toByteArray()
    }

    internal fun gunzip(data: ByteArray, limit: Int): ByteArray {
        GZIPInputStream(ByteArrayInputStream(data)).use { input ->
            val out = ByteArrayOutputStream(data.size * 4)
            val buf = ByteArray(64 * 1024)
            var total = 0L
            while (true) {
                val n = input.read(buf)
                if (n < 0) break
                total += n
                if (total > limit) throw IOException("payload too large")
                out.write(buf, 0, n)
            }
            return out.toByteArray()
        }
    }
}
