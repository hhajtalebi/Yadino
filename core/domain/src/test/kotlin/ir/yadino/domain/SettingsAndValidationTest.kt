package ir.yadino.domain

import ir.yadino.calendar.DateFormatStyle
import ir.yadino.calendar.NumeralStyle
import ir.yadino.domain.TestData.NOW
import ir.yadino.domain.TestData.j
import ir.yadino.domain.TestData.task
import ir.yadino.domain.model.AppSettings
import ir.yadino.domain.model.Category
import ir.yadino.domain.model.DashboardConfig
import ir.yadino.domain.model.DashboardSection
import ir.yadino.domain.model.Goal
import ir.yadino.domain.model.NoteColor
import ir.yadino.domain.model.NoteFilters
import ir.yadino.domain.model.NoteSort
import ir.yadino.domain.model.Note
import ir.yadino.domain.model.Palette
import ir.yadino.domain.model.ShortcutAction
import ir.yadino.domain.model.ThemeMode
import ir.yadino.domain.recurrence.RecurrenceEnd
import ir.yadino.domain.recurrence.RecurrenceRule
import ir.yadino.domain.settings.DashboardCodec
import ir.yadino.domain.settings.SettingsCodec
import ir.yadino.domain.validation.ValidationError
import ir.yadino.domain.validation.Validators
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.time.DayOfWeek
import java.time.Instant
import java.time.LocalTime

class SettingsAndValidationTest {

    @Test
    fun settingsRoundTrip() {
        val custom = AppSettings(
            dateFormat = DateFormatStyle.NUMERIC, use24Hour = false, timeZoneId = "Asia/Tehran",
            firstDayOfWeek = DayOfWeek.MONDAY, numerals = NumeralStyle.LATIN, fontScale = 1.2f,
            themeMode = ThemeMode.DARK, palette = Palette.OCEAN, workDays = setOf(DayOfWeek.MONDAY, DayOfWeek.FRIDAY),
            defaultTaskTime = null, defaultReminderOffsetMinutes = null, quietHoursEnabled = true,
            quietHoursStart = LocalTime.of(22, 15), lunarAdjustment = -1, defaultNoteColor = NoteColor.GOLD,
        )
        assertEquals(custom, SettingsCodec.decode(SettingsCodec.encode(custom)))
        assertEquals(AppSettings(), SettingsCodec.decode(SettingsCodec.encode(AppSettings())))
    }

    @Test
    fun invalidValuesFallBackToDefaults() {
        val decoded = SettingsCodec.decode(
            mapOf(
                "general.themeMode" to "PURPLE",
                "general.fontScale" to "9",
                "general.timeZoneId" to "Mars/Base",
                "calendar.workDayStart" to "99999",
                "calendar.lunarAdjustment" to "7",
                "notifications.snoozeMinutes" to "abc",
                "unknown.key" to "x",
            ),
        )
        assertEquals(ThemeMode.SYSTEM, decoded.themeMode)
        assertEquals(AppSettings.MAX_FONT_SCALE, decoded.fontScale)
        assertNull(decoded.timeZoneId)
        assertEquals(LocalTime.of(8, 0), decoded.workDayStart)
        assertEquals(2, decoded.lunarAdjustment)
        assertEquals(10, decoded.snoozeMinutes)
    }

    @Test
    fun settingsExportImport() {
        val s = AppSettings(palette = Palette.ROSE, noteSort = NoteSort.TITLE_ASC)
        val dash = DashboardConfig.DEFAULT.move(DashboardSection.HABITS, -3)
        val json = SettingsCodec.toJson(s, dash)
        val imported = SettingsCodec.fromJson(json)
        assertEquals(s, imported.settings)
        assertEquals(dash.normalized(), imported.dashboard)
        assertEquals(0, imported.ignoredKeys)
        for (bad in listOf("{}", "[]", "not json", """{"format":"yadino-settings","version":99,"settings":{}}""")) {
            try {
                SettingsCodec.fromJson(bad); fail("should reject $bad")
            } catch (expected: IllegalArgumentException) {
            }
        }
    }

    @Test
    fun dashboardCodecNormalizes() {
        val config = DashboardConfig.DEFAULT
        assertEquals(config, DashboardCodec.decode(DashboardCodec.encode(config)))
        val partial = DashboardCodec.decode("HABITS:1:3|UNKNOWN:1:2;NEW_NOTE,NEW_NOTE,BOGUS")
        assertEquals(DashboardSection.HABITS, partial.sections.first().section)
        assertEquals(3, partial.sections.first().itemCount)
        assertEquals(DashboardSection.entries.size, partial.sections.size)
        assertEquals(listOf(ShortcutAction.NEW_NOTE), partial.shortcuts)
        assertEquals(DashboardConfig.DEFAULT, DashboardCodec.decode("%%%"))
        val moved = config.move(DashboardSection.TODAY_PLAN, -1)
        assertEquals(DashboardSection.TODAY_PLAN, moved.sections.first().section)
        assertEquals(moved, moved.move(DashboardSection.TODAY_PLAN, -1))
    }

    @Test
    fun taskValidation() {
        assertTrue(Validators.task(task("درست", j(1405, 7, 22))).isEmpty())
        assertEquals(listOf(ValidationError.TITLE_REQUIRED), Validators.task(task("  ")))
        assertTrue(ValidationError.END_BEFORE_START in Validators.task(task("x", j(1405, 7, 22)) {
            copy(startTime = LocalTime.of(10, 0), endTime = LocalTime.of(9, 0))
        }))
        assertTrue(ValidationError.TIME_WITHOUT_DATE in Validators.task(task("x") { copy(startTime = LocalTime.NOON) }))
        assertTrue(ValidationError.DUE_BEFORE_DATE in Validators.task(task("x", j(1405, 7, 22)) { copy(dueDate = j(1405, 7, 20)) }))
        assertTrue(ValidationError.REMINDER_WITHOUT_DATE in Validators.task(task("x") { copy(reminderOffsetMinutes = 10) }))
        assertTrue(ValidationError.RECURRENCE_WITHOUT_DATE in Validators.task(task("x") { copy(recurrence = RecurrenceRule.daily()) }))
        assertTrue(ValidationError.RECURRENCE_UNTIL_BEFORE_START in Validators.task(task("x", j(1405, 7, 22)) {
            copy(recurrence = RecurrenceRule.daily().copy(end = RecurrenceEnd.Until(j(1405, 7, 1))))
        }))
        assertTrue(ValidationError.TITLE_TOO_LONG in Validators.task(task("x".repeat(201))))
        assertTrue(ValidationError.TOO_MANY_TAGS in Validators.task(task("x") { copy(tags = (1..21).map { "t$it" }) }))
    }

    @Test
    fun goalAndCategoryValidation() {
        val g = Goal("g", "هدف", startDate = j(1405, 7, 1), deadline = j(1405, 6, 1), createdAt = NOW, updatedAt = NOW)
        assertEquals(listOf(ValidationError.DEADLINE_BEFORE_START), Validators.goal(g))
        assertTrue(ValidationError.PROGRESS_OUT_OF_RANGE in Validators.goal(g.copy(deadline = null, manualProgress = 140)))
        val existing = listOf(Category("a", "کار", createdAt = NOW, updatedAt = NOW))
        assertEquals(listOf(ValidationError.NAME_DUPLICATE), Validators.category(Category("b", " کار ", createdAt = NOW, updatedAt = NOW), existing))
        assertTrue(Validators.category(Category("a", "کار", createdAt = NOW, updatedAt = NOW), existing).isEmpty())
        assertEquals(listOf(ValidationError.NAME_REQUIRED), Validators.category(Category("c", " ", createdAt = NOW, updatedAt = NOW), existing))
    }

    @Test
    fun tagNormalization() {
        assertEquals(listOf("کار", "Work"), Validators.normalizeTags(listOf(" #کار ", "Work", "work", "", "#")))
    }

    @Test
    fun noteSortingAndTitles() {
        val a = Note("a", "ب", "", createdAt = Instant.parse("2026-01-01T00:00:00Z"), updatedAt = Instant.parse("2026-01-05T00:00:00Z"))
        val b = Note("b", "الف", "", isPinned = true, createdAt = Instant.parse("2026-01-02T00:00:00Z"), updatedAt = Instant.parse("2026-01-03T00:00:00Z"))
        val c = Note("c", "", "خط اول\nخط دوم", createdAt = Instant.parse("2026-01-03T00:00:00Z"), updatedAt = Instant.parse("2026-01-04T00:00:00Z"))
        assertEquals(listOf(b, a, c), NoteFilters.sort(listOf(a, b, c), NoteSort.UPDATED_DESC))
        assertEquals(listOf(b, c, a), NoteFilters.sort(listOf(a, b, c), NoteSort.CREATED_DESC))
        assertEquals("خط اول", NoteFilters.displayTitle(c))
        assertEquals(listOf(c), NoteFilters.search(listOf(a, b, c), "دوم"))
    }
}
