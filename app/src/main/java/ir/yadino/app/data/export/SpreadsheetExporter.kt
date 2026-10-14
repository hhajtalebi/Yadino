package ir.yadino.app.data.export

import android.content.Context
import android.net.Uri
import ir.yadino.app.R
import ir.yadino.app.core.AppClock
import ir.yadino.app.core.ErrorReporter
import ir.yadino.app.data.repository.DataRepository
import ir.yadino.app.data.settings.SettingsRepository
import ir.yadino.domain.export.CsvExporter
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream
import java.time.format.DateTimeFormatter
import java.util.Locale
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/**
 * Writes all records as CSV tables in one ZIP file for spreadsheet apps (see [CsvExporter]).
 * Read-only: nothing in the database or the backup status changes. The file is not
 * encrypted and cannot be restored; the JSON backup remains the restore format.
 */
class SpreadsheetExporter(
    private val context: Context,
    private val data: DataRepository,
    private val settings: SettingsRepository,
    private val clock: AppClock,
) {
    sealed interface Result {
        data class Success(val rows: Int) : Result
        data class Failure(val reason: String) : Result
    }

    fun suggestedFileName(): String =
        "yadino-csv-" + DateTimeFormatter.ofPattern("yyyyMMdd-HHmm", Locale.ROOT).format(clock.now().atZone(clock.zone())) + ".zip"

    suspend fun exportTo(uri: Uri): Result = withContext(Dispatchers.IO) {
        try {
            val snapshot = data.snapshot(emptyMap(), null)
            val tables = CsvExporter.export(snapshot, clock.today(), clock.zone(), settings.current().firstDayOfWeek)
            val bytes = zip(tables)
            val out = context.contentResolver.openOutputStream(uri, "wt") ?: return@withContext Result.Failure(context.getString(R.string.err_file_write))
            out.use { it.write(bytes) }
            // Read back: some storage providers fail silently.
            val written = context.contentResolver.openInputStream(uri)?.use { it.readBytes() }
            if (written == null || !written.contentEquals(bytes)) return@withContext Result.Failure(context.getString(R.string.err_backup_verify))
            Result.Success(tables.sumOf { it.rows })
        } catch (e: Exception) {
            ErrorReporter.log(context, e, "csv-export")
            Result.Failure(context.getString(R.string.err_file_write))
        }
    }

    private fun zip(tables: List<CsvExporter.Table>): ByteArray {
        val buffer = ByteArrayOutputStream()
        val time = clock.now().toEpochMilli()
        ZipOutputStream(buffer).use { zip ->
            for (t in tables) {
                zip.putNextEntry(ZipEntry(t.fileName).apply { this.time = time })
                zip.write(t.content.toByteArray(Charsets.UTF_8))
                zip.closeEntry()
            }
        }
        return buffer.toByteArray()
    }
}
