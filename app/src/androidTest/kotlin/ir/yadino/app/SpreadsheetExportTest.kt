package ir.yadino.app

import android.content.Context
import android.net.Uri
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import ir.yadino.app.data.export.SpreadsheetExporter
import ir.yadino.domain.model.Ids
import ir.yadino.domain.model.Note
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.util.zip.ZipInputStream

/** CSV export through the real database and [SpreadsheetExporter]: a ZIP with five UTF-8 tables. */
@RunWith(AndroidJUnit4::class)
class SpreadsheetExportTest {
    private val app = ApplicationProvider.getApplicationContext<Context>() as YadinoApplication
    private val c = app.container
    private val file = File(app.cacheDir, "csv-export-test.zip")
    private var noteId: String? = null

    @After
    fun cleanUp() = runBlocking {
        noteId?.let { c.notes.delete(it) }
        file.delete()
    }

    @Test
    fun exportWritesFiveBomPrefixedTables() = runBlocking {
        val now = c.clock.now()
        val marker = "آزمون خروجی " + Ids.newId().take(8)
        val saved = c.notes.save(Note(Ids.newId(), marker, "- [x] نان\n- [ ] شیر", createdAt = now, updatedAt = now))
        noteId = saved?.id

        val result = c.spreadsheet.exportTo(Uri.fromFile(file))
        assertTrue(result.toString(), result is SpreadsheetExporter.Result.Success)

        val entries = LinkedHashMap<String, ByteArray>()
        ZipInputStream(file.inputStream()).use { zip ->
            while (true) {
                val e = zip.nextEntry ?: break
                entries[e.name] = zip.readBytes()
            }
        }
        assertEquals(listOf("tasks.csv", "notes.csv", "goals.csv", "habits.csv", "habit_logs.csv"), entries.keys.toList())
        for ((name, bytes) in entries) {
            assertTrue("$name starts with a UTF-8 BOM", bytes.size >= 3 && bytes[0] == 0xEF.toByte() && bytes[1] == 0xBB.toByte() && bytes[2] == 0xBF.toByte())
        }
        val notes = entries.getValue("notes.csv").toString(Charsets.UTF_8)
        assertTrue(notes.contains(marker))
        assertTrue(notes.contains("☑ نان\n☐ شیر"))
        assertTrue(entries.getValue("tasks.csv").toString(Charsets.UTF_8).contains("تاریخ (شمسی)"))
    }
}
