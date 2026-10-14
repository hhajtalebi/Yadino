package ir.yadino.app.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowLeft
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.Keyboard
import androidx.compose.material.icons.filled.Schedule
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TimeInput
import androidx.compose.material3.TimePicker
import androidx.compose.material3.rememberTimePickerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import ir.yadino.app.R
import ir.yadino.app.ui.theme.HolidayRed
import ir.yadino.app.ui.theme.HolidayRedDark
import ir.yadino.app.ui.theme.LocalAppSettings
import ir.yadino.app.ui.theme.LocalFormatter
import ir.yadino.app.ui.theme.LocalIsDark
import ir.yadino.calendar.DateFormatStyle
import ir.yadino.calendar.IranianOccasions
import ir.yadino.calendar.JalaliDate
import ir.yadino.calendar.PersianNames
import ir.yadino.calendar.WeekMath
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.LocalTime

/**
 * Jalali date picker: month grid with the configured first day of week, quick shortcuts,
 * month/year selection and optional "no date".
 */
@Composable
fun JalaliDatePickerDialog(
    initial: LocalDate?,
    today: LocalDate,
    onDismiss: () -> Unit,
    onPick: (LocalDate?) -> Unit,
    allowClear: Boolean = false,
    title: String? = null,
) {
    val settings = LocalAppSettings.current
    val fmt = LocalFormatter.current
    var selected by rememberSaveable { mutableStateOf(initial?.toEpochDay() ?: today.toEpochDay()) }
    var monthIndex by rememberSaveable { mutableIntStateOf(JalaliDate.ofEpochDay(selected).monthIndex) }
    var pickingMonth by rememberSaveable { mutableStateOf(false) }
    val selDate = LocalDate.ofEpochDay(selected)

    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Column {
                Text(title ?: stringResource(R.string.pick_date), style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Text(fmt.date(selDate, DateFormatStyle.FULL), style = MaterialTheme.typography.titleLarge)
                if (settings.showGregorian) Text(fmt.gregorian(selDate), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        },
        text = {
            Column {
                val month = JalaliDate.ofMonthIndex(monthIndex)
                Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
                    IconButton(onClick = { if (pickingMonth) monthIndex -= 12 else monthIndex -= 1 }) {
                        Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, stringResource(R.string.previous))
                    }
                    TextButton(onClick = { pickingMonth = !pickingMonth }, modifier = Modifier.weight(1f)) {
                        Text(if (pickingMonth) fmt.number(month.year) else fmt.monthYear(month), style = MaterialTheme.typography.titleMedium)
                    }
                    IconButton(onClick = { if (pickingMonth) monthIndex += 12 else monthIndex += 1 }) {
                        Icon(Icons.AutoMirrored.Filled.KeyboardArrowLeft, stringResource(R.string.next))
                    }
                }
                if (pickingMonth) {
                    MonthGridSelector(month.year, month.month) { m ->
                        monthIndex = month.year * 12 + (m - 1)
                        pickingMonth = false
                    }
                } else {
                    MonthDaysGrid(month, selDate, today, settings.firstDayOfWeek, settings.lunarAdjustment) { d -> selected = d.toEpochDay() }
                }
                Spacer(Modifier.height(8.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    AssistChip(onClick = { selected = today.toEpochDay(); monthIndex = JalaliDate.from(today).monthIndex }, label = { Text(stringResource(R.string.today)) })
                    AssistChip(onClick = { val t = today.plusDays(1); selected = t.toEpochDay(); monthIndex = JalaliDate.from(t).monthIndex }, label = { Text(stringResource(R.string.tomorrow)) })
                    AssistChip(onClick = { val t = today.plusWeeks(1); selected = t.toEpochDay(); monthIndex = JalaliDate.from(t).monthIndex }, label = { Text(stringResource(R.string.next_week)) })
                }
            }
        },
        confirmButton = { TextButton(onClick = { onPick(selDate); onDismiss() }) { Text(stringResource(R.string.confirm)) } },
        dismissButton = {
            Row {
                if (allowClear) TextButton(onClick = { onPick(null); onDismiss() }) { Text(stringResource(R.string.no_date)) }
                TextButton(onClick = onDismiss) { Text(stringResource(R.string.cancel)) }
            }
        },
    )
}

@Composable
private fun MonthGridSelector(year: Int, current: Int, onPick: (Int) -> Unit) {
    Column {
        for (row in 0 until 4) {
            Row(Modifier.fillMaxWidth()) {
                for (col in 0 until 3) {
                    val m = row * 3 + col + 1
                    val sel = m == current
                    Box(
                        Modifier.weight(1f).padding(4.dp).clip(MaterialTheme.shapes.small)
                            .background(if (sel) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.surfaceContainerHigh)
                            .clickable { onPick(m) }.padding(vertical = 12.dp),
                        contentAlignment = Alignment.Center,
                    ) {
                        Text(PersianNames.jalaliMonth(m), color = if (sel) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurface)
                    }
                }
            }
        }
    }
}

@Composable
fun MonthDaysGrid(
    month: JalaliDate,
    selected: LocalDate?,
    today: LocalDate,
    firstDay: DayOfWeek,
    lunarAdjustment: Int,
    onPick: (LocalDate) -> Unit,
) {
    val fmt = LocalFormatter.current
    val holidayColor = if (LocalIsDark.current) HolidayRedDark else HolidayRed
    Column {
        Row(Modifier.fillMaxWidth()) {
            WeekMath.orderedDays(firstDay).forEach { d ->
                Text(
                    PersianNames.weekdayShort(d),
                    modifier = Modifier.weight(1f),
                    textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                    style = MaterialTheme.typography.labelMedium,
                    color = if (d == DayOfWeek.FRIDAY) holidayColor else MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        val grid = WeekMath.monthGrid(month, firstDay)
        val rows = WeekMath.rowsNeeded(month, firstDay)
        for (r in 0 until rows) {
            Row(Modifier.fillMaxWidth()) {
                for (c in 0 until 7) {
                    val cell = grid[r * 7 + c]
                    val date = cell.date.toLocalDate()
                    val isSel = date == selected
                    val isToday = date == today
                    val holiday = date.dayOfWeek == DayOfWeek.FRIDAY || IranianOccasions.isHoliday(cell.date, lunarAdjustment)
                    val label = fmt.date(date, DateFormatStyle.FULL)
                    Box(
                        Modifier.weight(1f).aspectRatio(1f).padding(2.dp).clip(CircleShape)
                            .background(if (isSel) MaterialTheme.colorScheme.primary else Color.Transparent)
                            .then(if (isToday && !isSel) Modifier.border(1.5.dp, MaterialTheme.colorScheme.secondary, CircleShape) else Modifier)
                            .clickable(enabled = cell.inMonth, role = Role.Button) { onPick(date) }
                            .semantics { contentDescription = label },
                        contentAlignment = Alignment.Center,
                    ) {
                        if (cell.inMonth) {
                            Text(
                                fmt.number(cell.date.day),
                                color = when {
                                    isSel -> MaterialTheme.colorScheme.onPrimary
                                    holiday -> holidayColor
                                    else -> MaterialTheme.colorScheme.onSurface
                                },
                                fontWeight = if (isToday) FontWeight.Bold else FontWeight.Normal,
                            )
                        }
                    }
                }
            }
        }
    }
}

/** Month/year picker used by the calendar screen. */
@Composable
fun MonthYearPickerDialog(initial: JalaliDate, onDismiss: () -> Unit, onPick: (JalaliDate) -> Unit) {
    val fmt = LocalFormatter.current
    var year by rememberSaveable { mutableIntStateOf(initial.year) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.pick_month)) },
        text = {
            Column {
                Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
                    IconButton(onClick = { year-- }) { Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, stringResource(R.string.previous)) }
                    Text(fmt.number(year), style = MaterialTheme.typography.titleLarge, modifier = Modifier.weight(1f), textAlign = androidx.compose.ui.text.style.TextAlign.Center)
                    IconButton(onClick = { year++ }) { Icon(Icons.AutoMirrored.Filled.KeyboardArrowLeft, stringResource(R.string.next)) }
                }
                MonthGridSelector(year, if (year == initial.year) initial.month else -1) { m ->
                    onPick(JalaliDate.of(year, m, 1))
                    onDismiss()
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.cancel)) } },
    )
}

/** Material 3 time picker in a dialog (dial or keyboard input). Times are shown LTR. */
@Composable
fun TimePickerDialog(
    initial: LocalTime,
    onDismiss: () -> Unit,
    onPick: (LocalTime) -> Unit,
    onClear: (() -> Unit)? = null,
    title: String? = null,
) {
    val settings = LocalAppSettings.current
    val state = rememberTimePickerState(initial.hour, initial.minute, settings.use24Hour)
    var keyboard by remember { mutableStateOf(false) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(title ?: stringResource(R.string.pick_time), modifier = Modifier.weight(1f))
                IconButton(onClick = { keyboard = !keyboard }) {
                    Icon(if (keyboard) Icons.Default.Schedule else Icons.Default.Keyboard, stringResource(R.string.toggle_time_input))
                }
            }
        },
        text = {
            CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Ltr) {
                Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
                    if (keyboard) TimeInput(state) else TimePicker(state)
                }
            }
        },
        confirmButton = { TextButton(onClick = { onPick(LocalTime.of(state.hour, state.minute)); onDismiss() }) { Text(stringResource(R.string.confirm)) } },
        dismissButton = {
            Row {
                if (onClear != null) TextButton(onClick = { onClear(); onDismiss() }) { Text(stringResource(R.string.no_time)) }
                TextButton(onClick = onDismiss) { Text(stringResource(R.string.cancel)) }
            }
        },
    )
}
