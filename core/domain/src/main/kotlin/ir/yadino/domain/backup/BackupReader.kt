package ir.yadino.domain.backup

import ir.yadino.domain.json.Json
import java.time.Instant
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.DateTimeParseException
import java.util.Locale

/**
 * Reads plain and encrypted backups with a single parse of the document.
 *
 * For encrypted files every candidate password is tried in order (typically the password
 * the user just typed, then the backup password saved on this device). The result tells
 * the caller whether a password is still needed ([BackupError.PASSWORD_REQUIRED]) or the
 * supplied ones were wrong ([BackupError.WRONG_PASSWORD]).
 */
object BackupReader {

    fun read(text: String, passwords: List<CharArray> = emptyList()): BackupReadResult {
        if (text.isBlank()) return BackupReadResult.Failure(BackupError.EMPTY_FILE)
        val root = try {
            Json.parse(text)
        } catch (e: Exception) {
            return BackupReadResult.Failure(BackupError.NOT_JSON, e.message)
        }
        if (!BackupCrypto.isEnvelope(root)) return BackupCodec.decode(root)
        if (passwords.isEmpty()) return BackupReadResult.Failure(BackupError.PASSWORD_REQUIRED)
        for (password in passwords) {
            when (val r = BackupCrypto.decrypt(root, password)) {
                is DecryptResult.Success -> return when (val inner = BackupCodec.decode(r.plainText)) {
                    is BackupReadResult.Success -> inner.copy(file = inner.file.copy(info = inner.file.info.copy(encrypted = true)))
                    // an envelope inside an envelope is not something Yadino writes
                    is BackupReadResult.Failure -> if (inner.error == BackupError.PASSWORD_REQUIRED) {
                        BackupReadResult.Failure(BackupError.INVALID_CONTENT, "nested")
                    } else {
                        inner
                    }
                }
                is DecryptResult.Failure -> if (r.error != BackupError.WRONG_PASSWORD) return BackupReadResult.Failure(r.error)
            }
        }
        return BackupReadResult.Failure(BackupError.WRONG_PASSWORD)
    }
}

/** File names of backups and rotation of automatic backups in a user-chosen folder. */
object BackupFiles {
    const val MANUAL_PREFIX = "yadino-backup-"
    const val AUTO_PREFIX = "yadino-auto-"
    private val SUFFIX = "." + BackupCodec.FILE_EXTENSION
    private val MINUTES = DateTimeFormatter.ofPattern("yyyyMMdd-HHmm", Locale.ROOT)
    private val SECONDS = DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss", Locale.ROOT)

    /** Name suggested for a backup the user saves by hand. */
    fun manualName(at: Instant, zone: ZoneId): String = MANUAL_PREFIX + MINUTES.format(at.atZone(zone)) + SUFFIX

    /** Name of an automatic backup; sorts chronologically. */
    fun autoName(at: Instant, zone: ZoneId): String = AUTO_PREFIX + SECONDS.format(at.atZone(zone)) + SUFFIX

    /** True only for names written by [autoName]; other files in the folder are never touched. */
    fun isAutoName(name: String): Boolean {
        if (!name.startsWith(AUTO_PREFIX) || !name.endsWith(SUFFIX)) return false
        val stamp = name.substring(AUTO_PREFIX.length, name.length - SUFFIX.length)
        return try {
            LocalDateTime.parse(stamp, SECONDS)
            true
        } catch (e: DateTimeParseException) {
            false
        }
    }

    /**
     * Automatic backups that should be deleted so that only the newest [keep] remain.
     * At least one backup is always kept.
     */
    fun toDelete(names: Collection<String>, keep: Int): List<String> =
        names.filter(::isAutoName).distinct().sortedDescending().drop(keep.coerceAtLeast(1))
}
