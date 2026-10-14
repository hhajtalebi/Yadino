package ir.yadino.domain.recurrence

import ir.yadino.calendar.CalendarFormatter
import ir.yadino.calendar.DateFormatStyle
import ir.yadino.calendar.Digits
import ir.yadino.calendar.JalaliDate
import ir.yadino.calendar.NumeralStyle
import ir.yadino.calendar.PersianNames
import ir.yadino.calendar.WeekMath
import java.time.DayOfWeek
import java.time.LocalDate

enum class Frequency { DAILY, WEEKLY, MONTHLY, YEARLY }

/** How a recurrence series ends. */
sealed interface RecurrenceEnd {
    data object Never : RecurrenceEnd
    /** Last allowed occurrence date (inclusive). */
    data class Until(val date: LocalDate) : RecurrenceEnd
    /** Total number of occurrences, including the first one. */
    data class Count(val count: Int) : RecurrenceEnd {
        init {
            require(count >= 1) { "count must be positive" }
        }
    }
}

/**
 * Recurrence rule. Monthly and yearly rules follow the **Jalali** calendar: a task on the
 * 31st of Shahrivar repeating monthly occurs on the 30th of Mehr (the month has 30 days)
 * and again on the 31st of Farvardin.
 *
 * @property weekDays for WEEKLY rules; empty means "the weekday of the first occurrence".
 */
data class RecurrenceRule(
    val frequency: Frequency,
    val interval: Int = 1,
    val weekDays: Set<DayOfWeek> = emptySet(),
    val end: RecurrenceEnd = RecurrenceEnd.Never,
) {
    init {
        require(interval in 1..999) { "interval must be in 1..999" }
    }

    /** Serialises to a compact RRULE-like string, e.g. `FREQ=WEEKLY;INTERVAL=2;DAYS=SA,MO`. */
    fun encode(): String = buildString {
        append("FREQ=").append(frequency.name)
        if (interval != 1) append(";INTERVAL=").append(interval)
        if (weekDays.isNotEmpty()) {
            append(";DAYS=")
            append(weekDays.sortedBy { it.value }.joinToString(",") { DAY_CODES.getValue(it) })
        }
        when (val e = end) {
            RecurrenceEnd.Never -> Unit
            is RecurrenceEnd.Until -> append(";UNTIL=").append(e.date.toString())
            is RecurrenceEnd.Count -> append(";COUNT=").append(e.count)
        }
    }

    companion object {
        private val DAY_CODES = mapOf(
            DayOfWeek.SATURDAY to "SA", DayOfWeek.SUNDAY to "SU", DayOfWeek.MONDAY to "MO",
            DayOfWeek.TUESDAY to "TU", DayOfWeek.WEDNESDAY to "WE", DayOfWeek.THURSDAY to "TH",
            DayOfWeek.FRIDAY to "FR",
        )
        private val CODE_DAYS = DAY_CODES.entries.associate { (k, v) -> v to k }

        /** Parses [encode] output. Returns null for malformed input. */
        fun decode(text: String?): RecurrenceRule? {
            if (text.isNullOrBlank()) return null
            return try {
                val parts = text.split(';').filter { it.isNotBlank() }.associate {
                    val idx = it.indexOf('=')
                    require(idx > 0)
                    it.substring(0, idx).trim().uppercase() to it.substring(idx + 1).trim()
                }
                val freq = Frequency.valueOf(parts.getValue("FREQ").uppercase())
                val interval = parts["INTERVAL"]?.toInt() ?: 1
                val days = parts["DAYS"]?.split(',')?.filter { it.isNotBlank() }
                    ?.map { CODE_DAYS.getValue(it.trim().uppercase()) }?.toSet() ?: emptySet()
                val end = when {
                    parts["UNTIL"] != null -> RecurrenceEnd.Until(LocalDate.parse(parts.getValue("UNTIL")))
                    parts["COUNT"] != null -> RecurrenceEnd.Count(parts.getValue("COUNT").toInt())
                    else -> RecurrenceEnd.Never
                }
                RecurrenceRule(freq, interval, days, end)
            } catch (e: Exception) {
                null
            }
        }

        fun daily(interval: Int = 1) = RecurrenceRule(Frequency.DAILY, interval)
        fun weekly(days: Set<DayOfWeek> = emptySet(), interval: Int = 1) = RecurrenceRule(Frequency.WEEKLY, interval, days)
        fun monthly(interval: Int = 1) = RecurrenceRule(Frequency.MONTHLY, interval)
        fun yearly(interval: Int = 1) = RecurrenceRule(Frequency.YEARLY, interval)
    }
}

/**
 * Computes occurrences of a [RecurrenceRule] anchored at a start date.
 * Week based rules use [firstDayOfWeek] to define week boundaries for intervals > 1.
 */
object RecurrenceEngine {

    /** Safety limit for open ended iterations. */
    private const val MAX_STEPS = 100_000

    /**
     * Occurrences in the inclusive range [from]..[to], at most [limit] results.
     */
    fun occurrencesBetween(
        rule: RecurrenceRule,
        start: LocalDate,
        from: LocalDate,
        to: LocalDate,
        firstDayOfWeek: DayOfWeek = DayOfWeek.SATURDAY,
        limit: Int = 1000,
    ): List<LocalDate> {
        if (to.isBefore(from)) return emptyList()
        val result = ArrayList<LocalDate>()
        iterate(rule, start, firstDayOfWeek) { date ->
            when {
                date.isAfter(to) -> false
                else -> {
                    if (!date.isBefore(from)) result += date
                    result.size < limit
                }
            }
        }
        return result
    }

    /** The first occurrence strictly after [after], or null when the series has ended. */
    fun nextOccurrence(
        rule: RecurrenceRule,
        start: LocalDate,
        after: LocalDate,
        firstDayOfWeek: DayOfWeek = DayOfWeek.SATURDAY,
    ): LocalDate? {
        var found: LocalDate? = null
        iterate(rule, start, firstDayOfWeek) { date ->
            if (date.isAfter(after)) {
                found = date
                false
            } else true
        }
        return found
    }

    /** True if [date] is an occurrence of the series. */
    fun isOccurrence(
        rule: RecurrenceRule,
        start: LocalDate,
        date: LocalDate,
        firstDayOfWeek: DayOfWeek = DayOfWeek.SATURDAY,
    ): Boolean {
        if (date.isBefore(start)) return false
        var hit = false
        iterate(rule, start, firstDayOfWeek) { d ->
            if (d == date) hit = true
            d.isBefore(date)
        }
        return hit
    }

    /** Number of occurrences in [start]..[upTo] (inclusive). */
    fun countUpTo(rule: RecurrenceRule, start: LocalDate, upTo: LocalDate, firstDayOfWeek: DayOfWeek = DayOfWeek.SATURDAY): Int {
        var n = 0
        iterate(rule, start, firstDayOfWeek) { d ->
            if (d.isAfter(upTo)) false else { n++; true }
        }
        return n
    }

    /**
     * Generates occurrences in chronological order and passes them to [visitor] until it
     * returns false or the series ends.
     */
    private inline fun iterate(
        rule: RecurrenceRule,
        start: LocalDate,
        firstDayOfWeek: DayOfWeek,
        visitor: (LocalDate) -> Boolean,
    ) {
        var emitted = 0
        val maxCount = (rule.end as? RecurrenceEnd.Count)?.count ?: Int.MAX_VALUE
        val until = (rule.end as? RecurrenceEnd.Until)?.date

        when (rule.frequency) {
            Frequency.DAILY -> {
                var k = 0L
                while (k < MAX_STEPS) {
                    val d = start.plusDays(k * rule.interval)
                    if ((until != null && d.isAfter(until)) || emitted >= maxCount) return
                    emitted++
                    if (!visitor(d)) return
                    k++
                }
            }
            Frequency.WEEKLY -> {
                val days = rule.weekDays.ifEmpty { setOf(start.dayOfWeek) }
                val startJ = JalaliDate.from(start)
                val weekStart = WeekMath.startOfWeek(startJ, firstDayOfWeek).toLocalDate()
                val ordered = WeekMath.orderedDays(firstDayOfWeek).filter { it in days }
                var w = 0L
                while (w < MAX_STEPS) {
                    val base = weekStart.plusWeeks(w * rule.interval)
                    for (day in ordered) {
                        val d = base.plusDays(WeekMath.column(day, firstDayOfWeek).toLong())
                        if (d.isBefore(start)) continue
                        if ((until != null && d.isAfter(until)) || emitted >= maxCount) return
                        emitted++
                        if (!visitor(d)) return
                    }
                    w++
                }
            }
            Frequency.MONTHLY -> {
                val startJ = JalaliDate.from(start)
                var k = 0L
                while (k < MAX_STEPS) {
                    val d = startJ.plusMonths(k * rule.interval).toLocalDate()
                    if ((until != null && d.isAfter(until)) || emitted >= maxCount) return
                    emitted++
                    if (!visitor(d)) return
                    k++
                }
            }
            Frequency.YEARLY -> {
                val startJ = JalaliDate.from(start)
                var k = 0L
                while (k < MAX_STEPS) {
                    val target = startJ.year + k * rule.interval
                    if (target > ir.yadino.calendar.JalaliCalendar.MAX_YEAR) return
                    val d = startJ.plusYears(k * rule.interval).toLocalDate()
                    if ((until != null && d.isAfter(until)) || emitted >= maxCount) return
                    emitted++
                    if (!visitor(d)) return
                    k++
                }
            }
        }
    }

    /** Persian human readable description, e.g. «هر ۲ هفته: شنبه، دوشنبه». */
    fun describe(rule: RecurrenceRule, start: LocalDate?, numerals: NumeralStyle): String {
        val n = Digits.format(rule.interval, numerals)
        val base = when (rule.frequency) {
            Frequency.DAILY -> if (rule.interval == 1) "هر روز" else "هر $n روز"
            Frequency.WEEKLY -> {
                val head = if (rule.interval == 1) "هر هفته" else "هر $n هفته"
                val days = rule.weekDays.ifEmpty { start?.let { setOf(it.dayOfWeek) } ?: emptySet() }
                if (days.isEmpty()) head
                else head + ": " + WeekMath.orderedDays(DayOfWeek.SATURDAY).filter { it in days }
                    .joinToString("، ") { PersianNames.weekday(it) }
            }
            Frequency.MONTHLY -> {
                val head = if (rule.interval == 1) "هر ماه" else "هر $n ماه"
                if (start != null) head + "، روز " + Digits.format(JalaliDate.from(start).day, numerals) else head
            }
            Frequency.YEARLY -> {
                val head = if (rule.interval == 1) "هر سال" else "هر $n سال"
                if (start != null) {
                    val j = JalaliDate.from(start)
                    head + "، " + Digits.format(j.day, numerals) + " " + PersianNames.jalaliMonth(j.month)
                } else head
            }
        }
        val end = when (val e = rule.end) {
            RecurrenceEnd.Never -> ""
            is RecurrenceEnd.Until -> " تا " + CalendarFormatter.formatJalali(JalaliDate.from(e.date), DateFormatStyle.LONG, numerals)
            is RecurrenceEnd.Count -> "، " + Digits.format(e.count, numerals) + " بار"
        }
        return base + end
    }
}
