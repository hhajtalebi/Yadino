package ir.yadino.domain

import ir.yadino.domain.TestData.NOW
import ir.yadino.domain.TestData.j
import ir.yadino.domain.TestData.task
import ir.yadino.domain.backup.BackupCodec
import ir.yadino.domain.backup.BackupCrypto
import ir.yadino.domain.backup.BackupData
import ir.yadino.domain.backup.BackupError
import ir.yadino.domain.backup.BackupFiles
import ir.yadino.domain.backup.BackupReadResult
import ir.yadino.domain.backup.BackupReader
import ir.yadino.domain.json.Json
import ir.yadino.domain.json.JsonNumber
import ir.yadino.domain.json.JsonObject
import ir.yadino.domain.json.JsonString
import ir.yadino.domain.json.JsonValue
import ir.yadino.domain.model.Note
import ir.yadino.domain.model.NoteColor
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.time.ZoneId
import java.util.Base64
import java.util.zip.GZIPOutputStream
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.PBEKeySpec

class BackupCryptoTest {

    private val iterations = BackupCrypto.MIN_ITERATIONS
    private val secret = "محتوای محرمانه یادداشت"

    private val sample = BackupData(
        tasks = listOf(task("جلسه تیم", j(1405, 7, 22))),
        notes = listOf(Note("n1", "یادداشت خصوصی", secret, NoteColor.GOLD, createdAt = NOW, updatedAt = NOW)),
        settings = mapOf("general.numerals" to "PERSIAN"),
    )

    private fun plain() = BackupCodec.encode(sample, "1.1.0", NOW)

    private fun encrypt(password: String, text: String = plain()) =
        BackupCrypto.encrypt(text, password.toCharArray(), "1.1.0", NOW, iterations)

    private fun hex(s: String) = ByteArray(s.length / 2) { s.substring(it * 2, it * 2 + 2).toInt(16).toByte() }

    private fun error(r: BackupReadResult) = (r as BackupReadResult.Failure).error

    /** Re-serialises an envelope with one member changed. */
    private fun modify(text: String, change: (MutableMap<String, JsonValue>) -> Unit): String {
        val root = Json.parse(text) as JsonObject
        val members = LinkedHashMap(root.members)
        change(members)
        return Json.stringify(JsonObject(members))
    }

    @Test
    fun pbkdf2MatchesRfc7914TestVectors() {
        assertArrayEquals(
            hex("55ac046e56e3089fec1691c22544b605f94185216dde0465e68b9d57c20dacbc49ca9cccf179b645991664b39d77ef317c71b845b1e30bd509112041d3a19783"),
            BackupCrypto.pbkdf2HmacSha256("passwd".toByteArray(), "salt".toByteArray(), 1, 64),
        )
        assertArrayEquals(
            hex("4ddcd8f60b98be21830cee5ef22701f9641a4418d04c0414aeff08876b34ab56a1d425a1225833549adb841b51c9b3176a272bdebba1d078478f62b397f33c8d"),
            BackupCrypto.pbkdf2HmacSha256("Password".toByteArray(), "NaCl".toByteArray(), 80000, 64),
        )
    }

    @Test
    fun pbkdf2MatchesTheJdkForPersianPasswords() {
        val salt = ByteArray(16) { it.toByte() }
        for (password in listOf("رمز‌عبور۱۴۰۵", "یادینو-Yadino!", "پشتیبان امن 😀")) {
            val normalized = BackupCrypto.normalize(password.toCharArray())
            // the JDK's PBKDF2WithHmacSHA256 encodes the password as UTF-8
            val jdk = SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256")
                .generateSecret(PBEKeySpec(normalized.toCharArray(), salt, 2000, 256)).encoded
            assertArrayEquals(password, jdk, BackupCrypto.pbkdf2HmacSha256(normalized.toByteArray(Charsets.UTF_8), salt, 2000, 32))
        }
    }

    @Test
    fun encryptedBackupRoundTrip() {
        val password = "رمز‌قوی-۱۴۰۵"
        val r = BackupReader.read(encrypt(password), listOf(password.toCharArray()))
        assertTrue(r.toString(), r is BackupReadResult.Success)
        val file = (r as BackupReadResult.Success).file
        assertTrue(file.info.encrypted)
        assertEquals(sample.notes, file.data.notes)
        assertEquals(sample.tasks, file.data.tasks)
        assertEquals(sample.settings, file.data.settings)
        // plain backups are still read without any password
        val plain = BackupReader.read(plain())
        assertTrue(plain is BackupReadResult.Success)
        assertFalse((plain as BackupReadResult.Success).file.info.encrypted)
    }

    @Test
    fun samePasswordFromPersianArabicAndEnglishKeyboardsWorks() {
        // Arabic Kaf/Yeh and ASCII digits when writing, Persian letters and digits when reading
        val written = encrypt("\u0643\u062A\u0627\u0628 \u064A\u0627\u062F\u064A\u0646\u0648 1405")
        val r = BackupReader.read(written, listOf("کتاب یادینو ۱۴۰۵".toCharArray()))
        assertTrue(r is BackupReadResult.Success)
        assertEquals("0123456789", BackupCrypto.normalize("۰۱۲۳۴۵۶۷۸۹".toCharArray()))
        assertEquals("0123456789", BackupCrypto.normalize("٠١٢٣٤٥٦٧٨٩".toCharArray()))
    }

    @Test
    fun missingOrWrongPasswordsAreReported() {
        val enc = encrypt("correct horse battery")
        assertEquals(BackupError.PASSWORD_REQUIRED, error(BackupReader.read(enc)))
        assertEquals(BackupError.PASSWORD_REQUIRED, error(BackupCodec.decode(enc)))
        assertEquals(BackupError.WRONG_PASSWORD, error(BackupReader.read(enc, listOf("wrong password".toCharArray()))))
        // candidates are tried in order: a stale saved password does not block the right one
        val r = BackupReader.read(enc, listOf("old saved password".toCharArray(), "correct horse battery".toCharArray()))
        assertTrue(r is BackupReadResult.Success)
    }

    @Test
    fun tamperingWithHeaderOrPayloadIsDetected() {
        val password = "a very good password"
        val enc = encrypt(password)
        val pw = listOf(password.toCharArray())

        val newerDate = modify(enc) { it["createdAt"] = JsonString("2030-01-01T00:00:00Z") }
        assertEquals(BackupError.WRONG_PASSWORD, error(BackupReader.read(newerDate, pw)))

        val otherVersion = modify(enc) { it["appVersion"] = JsonString("9.9.9") }
        assertEquals(BackupError.WRONG_PASSWORD, error(BackupReader.read(otherVersion, pw)))

        val fewerRounds = modify(enc) { m ->
            val kdf = LinkedHashMap((m["kdf"] as JsonObject).members)
            kdf["iterations"] = JsonNumber((iterations + 1).toString())
            m["kdf"] = JsonObject(kdf)
        }
        assertEquals(BackupError.WRONG_PASSWORD, error(BackupReader.read(fewerRounds, pw)))

        val flipped = modify(enc) { m ->
            val bytes = Base64.getDecoder().decode((m["payload"] as JsonString).value)
            bytes[bytes.size / 2] = (bytes[bytes.size / 2].toInt() xor 1).toByte()
            m["payload"] = JsonString(Base64.getEncoder().encodeToString(bytes))
        }
        assertEquals(BackupError.WRONG_PASSWORD, error(BackupReader.read(flipped, pw)))

        assertEquals(BackupError.NOT_JSON, error(BackupReader.read(enc.substring(0, enc.length / 2), pw)))
    }

    @Test
    fun hostileOrUnsupportedHeadersAreRejectedWithoutDecrypting() {
        val password = "a very good password"
        val enc = encrypt(password)
        val pw = listOf(password.toCharArray())
        fun withKdf(key: String, value: JsonValue) = modify(enc) { m ->
            val kdf = LinkedHashMap((m["kdf"] as JsonObject).members)
            kdf[key] = value
            m["kdf"] = JsonObject(kdf)
        }
        // absurd work factors would freeze the phone: refused before any key derivation
        assertEquals(BackupError.INVALID_CONTENT, error(BackupReader.read(withKdf("iterations", JsonNumber("1000000000")), pw)))
        assertEquals(BackupError.INVALID_CONTENT, error(BackupReader.read(withKdf("iterations", JsonNumber("1000")), pw)))
        assertEquals(BackupError.INVALID_CONTENT, error(BackupReader.read(withKdf("algorithm", JsonString("MD5")), pw)))
        assertEquals(BackupError.NEWER_FORMAT, error(BackupReader.read(withKdf("passwordNormalization", JsonString("fa-v9")), pw)))
        assertEquals(BackupError.INVALID_CONTENT, error(BackupReader.read(withKdf("salt", JsonString("***")), pw)))
        val newer = modify(enc) { it["formatVersion"] = JsonNumber("2") }
        assertEquals(BackupError.NEWER_FORMAT, error(BackupReader.read(newer, pw)))
        val noPayload = modify(enc) { it.remove("payload") }
        assertEquals(BackupError.INVALID_CONTENT, error(BackupReader.read(noPayload, pw)))
    }

    @Test
    fun envelopeRevealsNoContentAndIsFreshEveryTime() {
        val password = "a very good password"
        val a = encrypt(password)
        val b = encrypt(password)
        assertNotEquals("fresh salt and IV for every file", a, b)
        for (leak in listOf(secret, "یادداشت خصوصی", "جلسه تیم", "counts", "tasks", "checksum")) {
            assertFalse("envelope must not contain '$leak'", a.contains(leak))
        }
        val root = Json.parse(a) as JsonObject
        assertEquals(BackupCrypto.FORMAT, root.string("format"))
        assertEquals("1.1.0", root.string("appVersion"))
        assertTrue(BackupReader.read(b, listOf(password.toCharArray())) is BackupReadResult.Success)
        // the plain document compresses well, so encryption does not inflate the file
        assertTrue(a.length < plain().length * 2)
    }

    @Test
    fun passwordRules() {
        assertEquals(BackupCrypto.PasswordProblem.TOO_SHORT, BackupCrypto.checkPassword("1234567".toCharArray()))
        assertEquals(BackupCrypto.PasswordProblem.BLANK, BackupCrypto.checkPassword("          ".toCharArray()))
        assertNull(BackupCrypto.checkPassword("رمزهشتحرف".toCharArray()))
        assertNull(BackupCrypto.checkPassword("۱۲۳۴۵۶۷۸".toCharArray()))
        assertEquals(BackupCrypto.PasswordProblem.TOO_LONG, BackupCrypto.checkPassword(CharArray(300) { 'a' }))
        try {
            encrypt("short")
            fail("weak passwords are refused")
        } catch (expected: IllegalArgumentException) {
        }
    }

    @Test
    fun decompressionIsBounded() {
        val out = ByteArrayOutputStream()
        GZIPOutputStream(out).use { it.write(ByteArray(1_000_000)) }
        try {
            BackupCrypto.gunzip(out.toByteArray(), 10_000)
            fail("limit must be enforced")
        } catch (expected: IOException) {
        }
        assertEquals(1_000_000, BackupCrypto.gunzip(out.toByteArray(), 2_000_000).size)
    }

    @Test
    fun automaticBackupNamesAndRotation() {
        val tehran = ZoneId.of("Asia/Tehran")
        assertEquals("yadino-auto-20261014-113000.yadino.json", BackupFiles.autoName(NOW, tehran))
        assertEquals("yadino-backup-20261014-1130.yadino.json", BackupFiles.manualName(NOW, tehran))
        val autos = (10..14).map { "yadino-auto-202610${it}-090000.yadino.json" }
        val others = listOf(
            "yadino-backup-20261001-0900.yadino.json", // saved by hand
            "notes.txt",
            "yadino-auto-garbage.yadino.json",
            "yadino-auto-20261001-090000 (1).yadino.json", // renamed by the file provider
        )
        assertTrue(autos.all(BackupFiles::isAutoName))
        assertTrue(others.none(BackupFiles::isAutoName))
        val toDelete = BackupFiles.toDelete((others + autos).shuffled(java.util.Random(7)), keep = 2)
        assertEquals(autos.take(3).sortedDescending(), toDelete)
        assertEquals("at least one backup is always kept", autos.take(4).sortedDescending(), BackupFiles.toDelete(autos, keep = 0))
        assertTrue(BackupFiles.toDelete(autos, keep = 10).isEmpty())
    }
}
