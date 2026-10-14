package ir.yadino.app.core

import ir.yadino.calendar.DateFormatStyle
import ir.yadino.calendar.JalaliDate
import ir.yadino.calendar.NumeralStyle
import ir.yadino.domain.model.AppSettings
import org.junit.Assert.assertEquals
import org.junit.Test
import java.time.Instant
import java.time.LocalTime
import java.time.ZoneId

/** Settings must actually change what the user sees: numerals, 12/24h, date style. */
class FormatterTest {
    private val today = JalaliDate.of(1405, 7, 22).toLocalDate()

    @Test
    fun numeralsSettingIsApplied() {
        assertEquals("۱۴۰۵/۰۷/۲۲", Formatter(AppSettings(dateFormat = DateFormatStyle.NUMERIC)).date(today))
        assertEquals("1405/07/22", Formatter(AppSettings(dateFormat = DateFormatStyle.NUMERIC, numerals = NumeralStyle.LATIN)).date(today))
        assertEquals("۵۰٪", Formatter(AppSettings()).percent(50))
    }

    @Test
    fun timeFormatSettingIsApplied() {
        assertEquals("۱۴:۳۰", Formatter(AppSettings()).time(LocalTime.of(14, 30)))
        assertEquals("2:30 ب.ظ", Formatter(AppSettings(use24Hour = false, numerals = NumeralStyle.LATIN)).time(LocalTime.of(14, 30)))
        assertEquals("۹:۰۰ تا ۱۰:۳۰".replace("۹", "۰۹"), Formatter(AppSettings()).timeRange(LocalTime.of(9, 0), LocalTime.of(10, 30)))
    }

    @Test
    fun relativeDays() {
        val f = Formatter(AppSettings())
        assertEquals("امروز", f.relativeDay(today, today))
        assertEquals("فردا", f.relativeDay(today.plusDays(1), today))
        assertEquals("دیروز", f.relativeDay(today.minusDays(1), today))
        assertEquals("یکشنبه", f.relativeDay(today.plusDays(4), today))
        assertEquals("۲ آبان", f.relativeDay(today.plusDays(10), today))
        assertEquals("۱۴۰۶/۰۱/۰۱", f.relativeDay(JalaliDate.of(1406, 1, 1).toLocalDate(), today))
    }

    @Test
    fun offsetsAndDeadlines() {
        val f = Formatter(AppSettings(numerals = NumeralStyle.LATIN))
        assertEquals("سر وقت", f.minutesOffset(0))
        assertEquals("15 دقیقه قبل", f.minutesOffset(15))
        assertEquals("2 ساعت قبل", f.minutesOffset(120))
        assertEquals("1 روز قبل", f.minutesOffset(1440))
        assertEquals("3 روز مانده", f.daysLeft(3))
        assertEquals("2 روز گذشته", f.daysLeft(-2))
    }

    @Test
    fun dateTimeUsesZone() {
        val f = Formatter(AppSettings(numerals = NumeralStyle.LATIN))
        val instant = Instant.parse("2026-10-14T20:45:00Z")
        assertEquals("23 مهر 1405، 00:15", f.dateTime(instant, ZoneId.of("Asia/Tehran")))
        assertEquals("22 مهر 1405، 20:45", f.dateTime(instant, ZoneId.of("UTC")))
    }

    @Test
    fun timeZoneSettingOverridesDevice() {
        assertEquals(ZoneId.of("Asia/Tokyo"), AppClock.zoneFor(AppSettings(timeZoneId = "Asia/Tokyo")))
        assertEquals(ZoneId.systemDefault(), AppClock.zoneFor(AppSettings(timeZoneId = null)))
        assertEquals(ZoneId.systemDefault(), AppClock.zoneFor(AppSettings(timeZoneId = "Invalid/Zone")))
    }
}
