package ir.yadino.app

import android.content.Context
import android.net.Uri
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import ir.yadino.app.data.backup.BackupPasswordStore
import ir.yadino.app.data.backup.DriveResult
import ir.yadino.app.data.backup.ExportResult
import ir.yadino.app.data.backup.FolderBackupResult
import ir.yadino.app.data.backup.TextReadResult
import ir.yadino.domain.backup.BackupCrypto
import ir.yadino.domain.backup.BackupError
import ir.yadino.domain.backup.BackupReadResult
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/**
 * Password-protected backups through the real [ir.yadino.app.data.backup.BackupManager]:
 * the Keystore-backed password store, export to a file, reading back with and without the
 * saved password, and refusing to fall back to plain backups when the password is gone.
 */
@RunWith(AndroidJUnit4::class)
class EncryptedBackupTest {
    private val app = ApplicationProvider.getApplicationContext<Context>() as YadinoApplication
    private val c = app.container
    private val password = "رمز آزمایشی ۱۴۰۵"
    private val file = File(app.cacheDir, "encrypted-test.yadino.json")

    @After
    fun cleanUp() = runBlocking {
        c.backupPasswords.clear()
        file.delete()
    }

    @Test
    fun exportIsEncryptedAndReadableOnlyWithThePassword() = runBlocking {
        c.backupPasswords.set(password.toCharArray())
        assertEquals(BackupPasswordStore.State.ON, c.backupPasswords.state())

        val export = c.backup.exportTo(Uri.fromFile(file))
        assertTrue(export.toString(), export is ExportResult.Success && export.encrypted)
        val text = (c.backup.readText(Uri.fromFile(file)) as TextReadResult.Success).text
        assertTrue(text.contains(BackupCrypto.FORMAT))
        assertFalse("counts are not visible", text.contains("\"counts\""))

        // the saved password opens it automatically
        val withSaved = c.backup.decode(text)
        assertTrue(withSaved is BackupReadResult.Success && withSaved.file.info.encrypted)

        // without a saved password the user must type it
        c.backupPasswords.clear()
        assertEquals(BackupPasswordStore.State.OFF, c.backupPasswords.state())
        assertEquals(BackupError.PASSWORD_REQUIRED, (c.backup.decode(text) as BackupReadResult.Failure).error)
        assertEquals(BackupError.WRONG_PASSWORD, (c.backup.decode(text, "not the password".toCharArray()) as BackupReadResult.Failure).error)
        assertTrue(c.backup.decode(text, password.toCharArray()) is BackupReadResult.Success)
    }

    @Test
    fun protectionNeverSilentlyFallsBackToPlainBackups() = runBlocking {
        // protection on, but the secret is missing (as after moving to another phone)
        c.backupPasswords.set(password.toCharArray())
        c.tokenStore.put("backup_password", null)
        assertEquals(BackupPasswordStore.State.UNAVAILABLE, c.backupPasswords.state())

        val export = c.backup.exportTo(Uri.fromFile(file))
        assertTrue(export is ExportResult.Failure)
        assertFalse("nothing readable may be written", file.exists() && file.readText().contains("\"yadino-backup\""))
        val drive = c.backup.backupToDrive(automatic = true)
        assertTrue(drive is DriveResult.NotConfigured || drive is DriveResult.NotConnected ||
            (drive is DriveResult.Error && drive.kind == DriveResult.Kind.ENCRYPTION_UNAVAILABLE))
    }

    @Test
    fun folderBackupWithoutAFolderIsReported() = runBlocking {
        c.backup.clearBackupFolder()
        assertEquals(FolderBackupResult.NotConfigured, c.backup.backupToFolder(automatic = false))
    }
}
