package ir.yadino.calendar

/** Numeral systems supported by the UI. */
enum class NumeralStyle {
    /** Extended Arabic-Indic (Persian) digits: ۰۱۲۳۴۵۶۷۸۹ */
    PERSIAN,

    /** ASCII digits: 0123456789 */
    LATIN,
}

/** Utilities for converting digits between numeral systems. */
object Digits {
    private const val PERSIAN_ZERO = '\u06F0'
    private const val ARABIC_ZERO = '\u0660'

    /** Converts every ASCII digit in [text] to a Persian digit. */
    fun toPersian(text: String): String {
        var changed = false
        val chars = CharArray(text.length) { i ->
            val c = text[i]
            if (c in '0'..'9') {
                changed = true
                PERSIAN_ZERO + (c - '0')
            } else c
        }
        return if (changed) String(chars) else text
    }

    /** Converts Persian and Arabic-Indic digits in [text] to ASCII digits. */
    fun toLatin(text: String): String {
        var changed = false
        val chars = CharArray(text.length) { i ->
            val c = text[i]
            when (c) {
                in PERSIAN_ZERO..(PERSIAN_ZERO + 9) -> { changed = true; '0' + (c - PERSIAN_ZERO) }
                in ARABIC_ZERO..(ARABIC_ZERO + 9) -> { changed = true; '0' + (c - ARABIC_ZERO) }
                else -> c
            }
        }
        return if (changed) String(chars) else text
    }

    fun apply(text: String, style: NumeralStyle): String =
        if (style == NumeralStyle.PERSIAN) toPersian(text) else toLatin(text)

    /** Formats an integer, optionally zero padded to [minDigits], in the requested style. */
    fun format(value: Long, style: NumeralStyle, minDigits: Int = 1): String {
        val raw = if (value < 0) "-" + (-value).toString().padStart(minDigits, '0')
        else value.toString().padStart(minDigits, '0')
        return apply(raw, style)
    }

    fun format(value: Int, style: NumeralStyle, minDigits: Int = 1): String =
        format(value.toLong(), style, minDigits)
}
