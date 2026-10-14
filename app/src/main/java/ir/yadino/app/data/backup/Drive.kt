package ir.yadino.app.data.backup

import android.content.Context
import android.content.SharedPreferences
import android.net.Uri
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import ir.yadino.app.BuildConfig
import ir.yadino.domain.json.Json
import ir.yadino.domain.json.JsonObject
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.security.KeyStore
import java.security.MessageDigest
import java.security.SecureRandom
import java.time.Instant
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/** Storage for OAuth tokens (abstracted so the Drive logic can be unit tested). */
interface TokenStore {
    fun put(name: String, value: String?)
    fun get(name: String): String?
}

/**
 * Small encrypted key/value store for secrets (OAuth tokens and the optional backup
 * password). Values are encrypted with an AES-GCM key kept in the Android Keystore (never
 * leaves secure hardware where available). SharedPreferences are not part of Android Auto
 * Backup (see res/xml/backup_rules.xml).
 */
class SecureTokenStore(context: Context) : TokenStore {
    private val prefs: SharedPreferences = context.applicationContext.getSharedPreferences("secure_tokens", Context.MODE_PRIVATE)
    private val alias = "yadino_tokens_v1"

    private fun key(): SecretKey {
        val ks = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (ks.getEntry(alias, null) as? KeyStore.SecretKeyEntry)?.let { return it.secretKey }
        val gen = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore")
        gen.init(
            KeyGenParameterSpec.Builder(alias, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256)
                .build(),
        )
        return gen.generateKey()
    }

    override fun put(name: String, value: String?) {
        if (value == null) {
            prefs.edit().remove(name).apply()
            return
        }
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, key())
        val encrypted = cipher.doFinal(value.toByteArray(Charsets.UTF_8))
        val blob = Base64.encodeToString(cipher.iv, Base64.NO_WRAP) + ":" + Base64.encodeToString(encrypted, Base64.NO_WRAP)
        prefs.edit().putString(name, blob).apply()
    }

    override fun get(name: String): String? {
        val blob = prefs.getString(name, null) ?: return null
        return try {
            val (iv, data) = blob.split(':', limit = 2).let { Base64.decode(it[0], Base64.NO_WRAP) to Base64.decode(it[1], Base64.NO_WRAP) }
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(128, iv))
            String(cipher.doFinal(data), Charsets.UTF_8)
        } catch (e: Exception) {
            // key invalidated (e.g. device restored to another phone): forget the value
            prefs.edit().remove(name).apply()
            null
        }
    }

    fun clear() = prefs.edit().clear().apply()
}

/** Minimal HTTP abstraction so Drive logic can be tested without network. */
interface HttpTransport {
    class Response(val code: Int, val body: String)

    @Throws(IOException::class)
    fun request(method: String, url: String, headers: Map<String, String>, body: ByteArray?): Response
}

class UrlConnectionTransport : HttpTransport {
    override fun request(method: String, url: String, headers: Map<String, String>, body: ByteArray?): HttpTransport.Response {
        val conn = URL(url).openConnection() as HttpURLConnection
        try {
            conn.requestMethod = method
            conn.connectTimeout = 15_000
            conn.readTimeout = 30_000
            conn.useCaches = false
            headers.forEach { (k, v) -> conn.setRequestProperty(k, v) }
            if (body != null) {
                conn.doOutput = true
                conn.setFixedLengthStreamingMode(body.size)
                conn.outputStream.use { it.write(body) }
            }
            val code = conn.responseCode
            val stream = if (code in 200..299) conn.inputStream else conn.errorStream
            val text = stream?.use { it.readBytes().toString(Charsets.UTF_8) } ?: ""
            return HttpTransport.Response(code, text)
        } finally {
            conn.disconnect()
        }
    }
}

/** Outcome of a Drive operation. Local data is never modified by a failed operation. */
sealed interface DriveResult {
    data class Success(val message: String? = null) : DriveResult
    data object NotConfigured : DriveResult
    data object NotConnected : DriveResult
    data object NetworkError : DriveResult
    /** Access was revoked or the refresh token expired; the account was disconnected locally. */
    data object AccessRevoked : DriveResult
    data class Error(val kind: Kind, val code: Int = 0) : DriveResult

    enum class Kind { FORBIDDEN, QUOTA, SERVER, INVALID_RESPONSE, AUTH_FAILED, STATE_MISMATCH, INVALID_BACKUP, ENCRYPTION_UNAVAILABLE }
}

data class DriveBackupEntry(
    val id: String,
    val name: String,
    val size: Long,
    val createdAt: Instant?,
    val formatVersion: Int?,
    /** Password protected ([ir.yadino.domain.backup.BackupCrypto]). */
    val encrypted: Boolean = false,
)

/**
 * Google Drive backup over the Drive REST API v3 using OAuth 2.0 for installed apps
 * (authorization code + PKCE, custom URI scheme redirect). Only the
 * `drive.appdata` scope is requested: the app can see nothing but its own hidden
 * application-data folder.
 *
 * This is backup/restore only — not live synchronisation. The [DriveBackupProvider]
 * interface keeps the door open for other providers and a future sync engine.
 */
interface DriveBackupProvider {
    fun isConfigured(): Boolean
    fun isConnected(): Boolean
    suspend fun upload(name: String, content: String, appProperties: Map<String, String>): DriveResult
    suspend fun list(): Pair<DriveResult, List<DriveBackupEntry>>
    suspend fun download(fileId: String): Pair<DriveResult, String?>
    suspend fun delete(fileId: String): DriveResult
}

class GoogleDriveClient(
    private val tokens: TokenStore,
    private val transport: HttpTransport = UrlConnectionTransport(),
    private val clientId: String = BuildConfig.DRIVE_CLIENT_ID,
    private val redirectScheme: String = BuildConfig.DRIVE_REDIRECT_SCHEME,
) : DriveBackupProvider {

    companion object {
        const val SCOPE = "https://www.googleapis.com/auth/drive.appdata"
        private const val AUTH_URL = "https://accounts.google.com/o/oauth2/v2/auth"
        private const val TOKEN_URL = "https://oauth2.googleapis.com/token"
        private const val REVOKE_URL = "https://oauth2.googleapis.com/revoke"
        private const val API = "https://www.googleapis.com/drive/v3"
        private const val UPLOAD = "https://www.googleapis.com/upload/drive/v3/files?uploadType=multipart&fields=id,name,size,createdTime"
        private const val K_REFRESH = "refresh_token"
        private const val K_ACCESS = "access_token"
        private const val K_EXPIRY = "access_expiry"
        private const val K_VERIFIER = "pkce_verifier"
        private const val K_STATE = "pkce_state"
        private const val K_EMAIL = "account_email"
    }

    val redirectUri: String get() = "$redirectScheme:/oauth2redirect"

    /** RFC 7636 S256 code challenge. */
    internal fun pkceChallenge(verifier: String): String =
        java.util.Base64.getUrlEncoder().withoutPadding()
            .encodeToString(MessageDigest.getInstance("SHA-256").digest(verifier.toByteArray(Charsets.US_ASCII)))

    override fun isConfigured(): Boolean = clientId.isNotBlank() && clientId.endsWith(".apps.googleusercontent.com")

    override fun isConnected(): Boolean = tokens.get(K_REFRESH) != null

    fun accountEmail(): String? = tokens.get(K_EMAIL)

    private fun enc(s: String) = URLEncoder.encode(s, "UTF-8")

    private fun randomToken(bytes: Int): String {
        val b = ByteArray(bytes)
        SecureRandom().nextBytes(b)
        return java.util.Base64.getUrlEncoder().withoutPadding().encodeToString(b)
    }

    /** Creates and stores a PKCE verifier and an anti-CSRF state. Returns (state, S256 challenge). */
    internal fun beginAuthorization(): Pair<String, String> {
        val verifier = randomToken(48)
        val state = randomToken(16)
        tokens.put(K_VERIFIER, verifier)
        tokens.put(K_STATE, state)
        return state to pkceChallenge(verifier)
    }

    /** Builds the consent URL and stores the PKCE verifier/state for the redirect. */
    fun authorizationUrl(): Uri {
        val (state, challenge) = beginAuthorization()
        return Uri.parse(AUTH_URL).buildUpon()
            .appendQueryParameter("client_id", clientId)
            .appendQueryParameter("redirect_uri", redirectUri)
            .appendQueryParameter("response_type", "code")
            .appendQueryParameter("scope", SCOPE)
            .appendQueryParameter("code_challenge", challenge)
            .appendQueryParameter("code_challenge_method", "S256")
            .appendQueryParameter("state", state)
            .appendQueryParameter("prompt", "consent")
            .build()
    }

    /** Handles the OAuth redirect, exchanging the authorization code for tokens. */
    suspend fun completeAuthorization(redirect: Uri): DriveResult =
        completeAuthorization(redirect.getQueryParameter("code"), redirect.getQueryParameter("state"), redirect.getQueryParameter("error"))

    suspend fun completeAuthorization(code: String?, state: String?, error: String?): DriveResult = withContext(Dispatchers.IO) {
        val expectedState = tokens.get(K_STATE)
        val verifier = tokens.get(K_VERIFIER)
        tokens.put(K_STATE, null)
        tokens.put(K_VERIFIER, null)
        if (error != null) return@withContext DriveResult.Error(DriveResult.Kind.AUTH_FAILED)
        if (expectedState == null || state != expectedState || verifier == null || code == null) {
            return@withContext DriveResult.Error(DriveResult.Kind.STATE_MISMATCH)
        }
        val body = "code=${enc(code)}&client_id=${enc(clientId)}&redirect_uri=${enc(redirectUri)}" +
            "&grant_type=authorization_code&code_verifier=${enc(verifier)}"
        try {
            val r = transport.request("POST", TOKEN_URL, mapOf("Content-Type" to "application/x-www-form-urlencoded"), body.toByteArray())
            if (r.code !in 200..299) return@withContext DriveResult.Error(DriveResult.Kind.AUTH_FAILED, r.code)
            val json = Json.parse(r.body) as? JsonObject ?: return@withContext DriveResult.Error(DriveResult.Kind.INVALID_RESPONSE)
            val refresh = json.string("refresh_token") ?: return@withContext DriveResult.Error(DriveResult.Kind.AUTH_FAILED)
            storeAccess(json)
            tokens.put(K_REFRESH, refresh)
            fetchAccountEmail()
            DriveResult.Success()
        } catch (e: IOException) {
            DriveResult.NetworkError
        } catch (e: Exception) {
            DriveResult.Error(DriveResult.Kind.INVALID_RESPONSE)
        }
    }

    private fun storeAccess(json: JsonObject) {
        val access = json.string("access_token") ?: return
        val expiresIn = json.long("expires_in") ?: 3600
        tokens.put(K_ACCESS, access)
        tokens.put(K_EXPIRY, (System.currentTimeMillis() + (expiresIn - 60) * 1000).toString())
    }

    private sealed interface TokenResult {
        data class Ok(val token: String) : TokenResult
        data class Fail(val result: DriveResult) : TokenResult
    }

    private fun accessToken(forceRefresh: Boolean = false): TokenResult {
        if (!isConfigured()) return TokenResult.Fail(DriveResult.NotConfigured)
        val refresh = tokens.get(K_REFRESH) ?: return TokenResult.Fail(DriveResult.NotConnected)
        val cached = tokens.get(K_ACCESS)
        val expiry = tokens.get(K_EXPIRY)?.toLongOrNull() ?: 0
        if (!forceRefresh && cached != null && System.currentTimeMillis() < expiry) return TokenResult.Ok(cached)
        val body = "client_id=${enc(clientId)}&refresh_token=${enc(refresh)}&grant_type=refresh_token"
        val r = try {
            transport.request("POST", TOKEN_URL, mapOf("Content-Type" to "application/x-www-form-urlencoded"), body.toByteArray())
        } catch (e: IOException) {
            return TokenResult.Fail(DriveResult.NetworkError)
        }
        if (r.code == 400 || r.code == 401) {
            // invalid_grant: the user revoked access or the token expired -> disconnect locally
            forgetTokens()
            return TokenResult.Fail(DriveResult.AccessRevoked)
        }
        if (r.code !in 200..299) return TokenResult.Fail(DriveResult.Error(DriveResult.Kind.SERVER, r.code))
        val json = runCatching { Json.parse(r.body) as? JsonObject }.getOrNull()
            ?: return TokenResult.Fail(DriveResult.Error(DriveResult.Kind.INVALID_RESPONSE))
        storeAccess(json)
        return tokens.get(K_ACCESS)?.let { TokenResult.Ok(it) } ?: TokenResult.Fail(DriveResult.Error(DriveResult.Kind.INVALID_RESPONSE))
    }

    private fun forgetTokens() {
        listOf(K_REFRESH, K_ACCESS, K_EXPIRY, K_EMAIL).forEach { tokens.put(it, null) }
    }

    /** Executes an authorised request, refreshing the token once on 401. */
    private fun authorized(method: String, url: String, headers: Map<String, String> = emptyMap(), body: ByteArray? = null): Pair<DriveResult, HttpTransport.Response?> {
        var token = when (val t = accessToken()) {
            is TokenResult.Ok -> t.token
            is TokenResult.Fail -> return t.result to null
        }
        repeat(2) { attempt ->
            val r = try {
                transport.request(method, url, headers + ("Authorization" to "Bearer $token"), body)
            } catch (e: IOException) {
                return DriveResult.NetworkError to null
            }
            when {
                r.code in 200..299 -> return DriveResult.Success() to r
                r.code == 401 && attempt == 0 -> {
                    token = when (val t = accessToken(forceRefresh = true)) {
                        is TokenResult.Ok -> t.token
                        is TokenResult.Fail -> return t.result to null
                    }
                }
                r.code == 403 -> {
                    val quota = r.body.contains("storageQuotaExceeded") || r.body.contains("quotaExceeded")
                    return DriveResult.Error(if (quota) DriveResult.Kind.QUOTA else DriveResult.Kind.FORBIDDEN, r.code) to r
                }
                else -> return DriveResult.Error(DriveResult.Kind.SERVER, r.code) to r
            }
        }
        return DriveResult.Error(DriveResult.Kind.AUTH_FAILED) to null
    }

    private fun fetchAccountEmail() {
        val (res, r) = authorized("GET", "$API/about?fields=user(emailAddress)")
        if (res is DriveResult.Success && r != null) {
            val email = runCatching { ((Json.parse(r.body) as JsonObject).obj("user"))?.string("emailAddress") }.getOrNull()
            tokens.put(K_EMAIL, email)
        }
    }

    override suspend fun upload(name: String, content: String, appProperties: Map<String, String>): DriveResult = withContext(Dispatchers.IO) {
        val boundary = "yadino-" + randomToken(12)
        val metadata = Json.stringify(Json.toJsonValue(linkedMapOf(
            "name" to name, "parents" to listOf("appDataFolder"), "mimeType" to "application/json", "appProperties" to appProperties,
        )))
        val body = buildString {
            append("--").append(boundary).append("\r\n")
            append("Content-Type: application/json; charset=UTF-8\r\n\r\n").append(metadata).append("\r\n")
            append("--").append(boundary).append("\r\n")
            append("Content-Type: application/json; charset=UTF-8\r\n\r\n").append(content).append("\r\n")
            append("--").append(boundary).append("--\r\n")
        }.toByteArray(Charsets.UTF_8)
        authorized("POST", UPLOAD, mapOf("Content-Type" to "multipart/related; boundary=$boundary"), body).first
    }

    override suspend fun list(): Pair<DriveResult, List<DriveBackupEntry>> = withContext(Dispatchers.IO) {
        val url = "$API/files?spaces=appDataFolder&pageSize=100&orderBy=createdTime%20desc" +
            "&fields=files(id,name,size,createdTime,appProperties)"
        val (res, r) = authorized("GET", url)
        if (res !is DriveResult.Success || r == null) return@withContext res to emptyList()
        val files = runCatching { (Json.parse(r.body) as JsonObject).array("files")?.items ?: emptyList() }.getOrElse {
            return@withContext DriveResult.Error(DriveResult.Kind.INVALID_RESPONSE) to emptyList()
        }
        DriveResult.Success() to files.mapNotNull { f ->
            val o = f as? JsonObject ?: return@mapNotNull null
            DriveBackupEntry(
                id = o.string("id") ?: return@mapNotNull null,
                name = o.string("name") ?: "",
                size = o.string("size")?.toLongOrNull() ?: 0,
                createdAt = o.string("createdTime")?.let { runCatching { Instant.parse(it) }.getOrNull() },
                formatVersion = o.obj("appProperties")?.string("formatVersion")?.toIntOrNull(),
                encrypted = o.obj("appProperties")?.string("encrypted") == "true",
            )
        }
    }

    override suspend fun download(fileId: String): Pair<DriveResult, String?> = withContext(Dispatchers.IO) {
        val (res, r) = authorized("GET", "$API/files/${enc(fileId)}?alt=media")
        res to r?.body?.takeIf { res is DriveResult.Success }
    }

    override suspend fun delete(fileId: String): DriveResult = withContext(Dispatchers.IO) {
        authorized("DELETE", "$API/files/${enc(fileId)}").first
    }

    /** Revokes the grant at Google (best effort) and forgets all tokens locally. */
    suspend fun disconnect(): DriveResult = withContext(Dispatchers.IO) {
        val refresh = tokens.get(K_REFRESH)
        forgetTokens()
        if (refresh == null) return@withContext DriveResult.Success()
        try {
            transport.request("POST", "$REVOKE_URL?token=${enc(refresh)}", mapOf("Content-Type" to "application/x-www-form-urlencoded"), ByteArray(0))
            DriveResult.Success()
        } catch (e: IOException) {
            // tokens are gone locally anyway; the grant can be removed from the Google account page
            DriveResult.NetworkError
        }
    }
}
