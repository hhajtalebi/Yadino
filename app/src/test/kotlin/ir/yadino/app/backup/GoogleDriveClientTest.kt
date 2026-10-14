package ir.yadino.app.backup

import ir.yadino.app.data.backup.DriveResult
import ir.yadino.app.data.backup.GoogleDriveClient
import ir.yadino.app.data.backup.HttpTransport
import ir.yadino.app.data.backup.TokenStore
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException
import java.security.MessageDigest

/**
 * Drive backup logic with a fake network: token refresh, revoked access, offline mode,
 * quota errors and request formats. No real network or Google account involved.
 */
class GoogleDriveClientTest {

    private class MemoryTokens : TokenStore {
        val map = HashMap<String, String>()
        override fun put(name: String, value: String?) { if (value == null) map.remove(name) else map[name] = value }
        override fun get(name: String): String? = map[name]
    }

    private class Request(val method: String, val url: String, val headers: Map<String, String>, val body: String)

    private class FakeTransport(private val handler: (Request) -> HttpTransport.Response) : HttpTransport {
        val requests = ArrayList<Request>()
        override fun request(method: String, url: String, headers: Map<String, String>, body: ByteArray?): HttpTransport.Response {
            val r = Request(method, url, headers, body?.toString(Charsets.UTF_8) ?: "")
            requests += r
            return handler(r)
        }
    }

    private val clientId = "1234-abc.apps.googleusercontent.com"

    private fun connectedTokens(expired: Boolean = false) = MemoryTokens().apply {
        put("refresh_token", "refresh-1")
        put("access_token", "access-1")
        put("access_expiry", (System.currentTimeMillis() + if (expired) -1000 else 3_600_000).toString())
    }

    private fun client(tokens: TokenStore, transport: HttpTransport) =
        GoogleDriveClient(tokens, transport, clientId, "com.googleusercontent.apps.1234-abc")

    private val ok = HttpTransport.Response(200, "{}")

    @Test
    fun notConfiguredOrNotConnected() = runBlocking {
        val t = FakeTransport { ok }
        assertEquals(DriveResult.NotConfigured, GoogleDriveClient(MemoryTokens(), t, "", "x").upload("a", "{}", emptyMap()))
        assertEquals(DriveResult.NotConnected, client(MemoryTokens(), t).upload("a", "{}", emptyMap()))
        assertTrue(t.requests.isEmpty())
    }

    @Test
    fun uploadUsesAppDataFolderAndMultipart() = runBlocking {
        val t = FakeTransport { ok }
        val r = client(connectedTokens(), t).upload("yadino-backup.yadino.json", "{\"format\":\"yadino-backup\"}", mapOf("formatVersion" to "1"))
        assertEquals(DriveResult.Success(), r)
        val req = t.requests.single()
        assertTrue(req.url.startsWith("https://www.googleapis.com/upload/drive/v3/files?uploadType=multipart"))
        assertEquals("Bearer access-1", req.headers["Authorization"])
        assertTrue(req.headers["Content-Type"]!!.startsWith("multipart/related; boundary="))
        assertTrue(req.body.contains("\"parents\":[\"appDataFolder\"]"))
        assertTrue(req.body.contains("{\"format\":\"yadino-backup\"}"))
        assertTrue(req.body.contains("\"formatVersion\":\"1\""))
    }

    @Test
    fun expiredAccessTokenIsRefreshedFirst() = runBlocking {
        val t = FakeTransport { r ->
            if (r.url.contains("oauth2.googleapis.com/token")) HttpTransport.Response(200, "{\"access_token\":\"access-2\",\"expires_in\":3600}") else ok
        }
        val tokens = connectedTokens(expired = true)
        assertEquals(DriveResult.Success(), client(tokens, t).upload("a", "{}", emptyMap()))
        assertTrue(t.requests[0].body.contains("grant_type=refresh_token"))
        assertEquals("Bearer access-2", t.requests[1].headers["Authorization"])
        assertEquals("access-2", tokens.get("access_token"))
    }

    @Test
    fun unauthorizedResponseTriggersOneRefreshAndRetry() = runBlocking {
        var uploads = 0
        val t = FakeTransport { r ->
            when {
                r.url.contains("/token") -> HttpTransport.Response(200, "{\"access_token\":\"access-3\",\"expires_in\":3600}")
                else -> { uploads++; if (uploads == 1) HttpTransport.Response(401, "{}") else ok }
            }
        }
        assertEquals(DriveResult.Success(), client(connectedTokens(), t).upload("a", "{}", emptyMap()))
        assertEquals(2, uploads)
    }

    @Test
    fun revokedAccessDisconnectsLocallyWithoutTouchingData() = runBlocking {
        val t = FakeTransport { r -> if (r.url.contains("/token")) HttpTransport.Response(400, "{\"error\":\"invalid_grant\"}") else ok }
        val tokens = connectedTokens(expired = true)
        val c = client(tokens, t)
        assertEquals(DriveResult.AccessRevoked, c.upload("a", "{}", emptyMap()))
        assertFalse(c.isConnected())
        assertTrue(tokens.map.isEmpty())
    }

    @Test
    fun offlineReturnsNetworkErrorAndKeepsTokens() = runBlocking {
        val t = FakeTransport { throw IOException("offline") }
        val tokens = connectedTokens()
        val c = client(tokens, t)
        assertEquals(DriveResult.NetworkError, c.upload("a", "{}", emptyMap()))
        assertEquals(DriveResult.NetworkError, c.list().first)
        assertTrue(c.isConnected())
        assertEquals("refresh-1", tokens.get("refresh_token"))
    }

    @Test
    fun quotaAndForbiddenAreDistinguished() = runBlocking {
        val quota = FakeTransport { HttpTransport.Response(403, "{\"error\":{\"errors\":[{\"reason\":\"storageQuotaExceeded\"}]}}") }
        assertEquals(DriveResult.Error(DriveResult.Kind.QUOTA, 403), client(connectedTokens(), quota).upload("a", "{}", emptyMap()))
        val forbidden = FakeTransport { HttpTransport.Response(403, "{\"error\":{\"message\":\"API disabled\"}}") }
        assertEquals(DriveResult.Error(DriveResult.Kind.FORBIDDEN, 403), client(connectedTokens(), forbidden).upload("a", "{}", emptyMap()))
    }

    @Test
    fun listParsesBackups() = runBlocking {
        val body = """{"files":[{"id":"f1","name":"yadino-backup-1.yadino.json","size":"2048","createdTime":"2026-10-14T08:00:00.000Z","appProperties":{"formatVersion":"1"}},
            {"id":"f2","name":"old.json","createdTime":"2026-09-01T08:00:00Z"}]}"""
        val t = FakeTransport { HttpTransport.Response(200, body) }
        val (r, files) = client(connectedTokens(), t).list()
        assertEquals(DriveResult.Success(), r)
        assertEquals(listOf("f1", "f2"), files.map { it.id })
        assertEquals(2048L, files[0].size)
        assertEquals(1, files[0].formatVersion)
        assertTrue(t.requests.single().url.contains("spaces=appDataFolder"))
    }

    @Test
    fun authorizationCodeExchangeWithPkceAndStateCheck() = runBlocking {
        val tokens = MemoryTokens()
        val t = FakeTransport { r ->
            when {
                r.url.contains("/token") -> HttpTransport.Response(200, "{\"access_token\":\"a\",\"refresh_token\":\"r\",\"expires_in\":3600}")
                r.url.contains("/about") -> HttpTransport.Response(200, "{\"user\":{\"emailAddress\":\"user@example.com\"}}")
                else -> ok
            }
        }
        val c = client(tokens, t)
        val (state, challenge) = c.beginAuthorization()
        // challenge must be the S256 hash of the stored verifier (RFC 7636)
        val verifier = tokens.get("pkce_verifier")!!
        val expected = java.util.Base64.getUrlEncoder().withoutPadding()
            .encodeToString(MessageDigest.getInstance("SHA-256").digest(verifier.toByteArray()))
        assertEquals(expected, challenge)
        assertEquals(DriveResult.Error(DriveResult.Kind.STATE_MISMATCH), client(MemoryTokens(), t).completeAuthorization("code", "wrong", null))
        assertEquals(DriveResult.Success(), c.completeAuthorization("code-1", state, null))
        assertTrue(t.requests.first().body.contains("code_verifier="))
        assertTrue(c.isConnected())
        assertEquals("user@example.com", c.accountEmail())
        // state is single use
        assertEquals(DriveResult.Error(DriveResult.Kind.STATE_MISMATCH), c.completeAuthorization("code-1", state, null))
    }

    @Test
    fun deniedConsentFails() = runBlocking {
        val c = client(MemoryTokens(), FakeTransport { ok })
        val (state, _) = c.beginAuthorization()
        assertEquals(DriveResult.Error(DriveResult.Kind.AUTH_FAILED), c.completeAuthorization(null, state, "access_denied"))
        assertFalse(c.isConnected())
    }
}
