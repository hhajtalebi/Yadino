package ir.yadino.app

import androidx.compose.foundation.rememberScrollState
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import ir.yadino.app.core.Formatter
import ir.yadino.app.ui.calendar.DayTimeline
import ir.yadino.app.ui.theme.YadinoTheme
import ir.yadino.domain.model.AppSettings
import ir.yadino.domain.model.Task
import ir.yadino.domain.planning.PlannedItem
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime

/** The day timeline: overlapping items side by side, accessibility actions and adding at an hour. */
@RunWith(AndroidJUnit4::class)
class DayTimelineUiTest {
    @get:Rule
    val compose = createComposeRule()

    private val day = LocalDate.of(2026, 10, 14)
    private val created = Instant.parse("2026-10-14T08:00:00Z")
    private val settings = AppSettings()
    private val fmt = Formatter(settings)
    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private val retimed = ArrayList<Triple<String, Int, Int?>>()
    private val added = ArrayList<Int>()

    private fun task(id: String, title: String, start: LocalTime, end: LocalTime?) =
        Task(id = id, title = title, date = day, startTime = start, endTime = end, createdAt = created, updatedAt = created)

    private fun show(vararg items: PlannedItem) {
        compose.setContent {
            YadinoTheme(settings) {
                DayTimeline(
                    items.toList(), emptyMap(), day, isToday = false, now = LocalTime.NOON, scroll = rememberScrollState(),
                    onAddAt = { added += it }, onOpen = {}, onRetime = { t, s, e -> retimed += Triple(t.id, s, e) },
                )
            }
        }
    }

    private fun action(title: String, label: Int): () -> Boolean {
        val node = compose.onNodeWithText(title).fetchSemanticsNode()
        val text = context.getString(label, fmt.number(15))
        return node.config[SemanticsActions.CustomActions].first { it.label == text }.action
    }

    @Test
    fun overlappingItemsAreShownSideBySide() {
        show(PlannedItem(task("a", "جلسه تیم", LocalTime.of(9, 0), LocalTime.of(10, 0)), day, false),
            PlannedItem(task("b", "تماس با مشتری", LocalTime.of(9, 30), LocalTime.of(10, 30)), day, false))
        val a = compose.onNodeWithText("جلسه تیم").performScrollTo().assertIsDisplayed().fetchSemanticsNode().boundsInRoot
        val b = compose.onNodeWithText("تماس با مشتری").performScrollTo().assertIsDisplayed().fetchSemanticsNode().boundsInRoot
        assertTrue("blocks must not cover each other: $a / $b", a.right <= b.left + 1f || b.right <= a.left + 1f)
    }

    @Test
    fun accessibilityActionsMoveAndResize() {
        show(PlannedItem(task("a", "جلسه تیم", LocalTime.of(9, 0), LocalTime.of(10, 0)), day, false),
            PlannedItem(task("n", "بدون پایان", LocalTime.of(13, 0), null), day, false))
        compose.onNodeWithText("جلسه تیم").performScrollTo()
        compose.runOnIdle {
            action("جلسه تیم", R.string.a11y_move_later)()
            action("جلسه تیم", R.string.a11y_extend)()
            action("جلسه تیم", R.string.a11y_shorten)()
        }
        compose.onNodeWithText("بدون پایان").performScrollTo()
        compose.runOnIdle { action("بدون پایان", R.string.a11y_move_earlier)() }
        assertEquals(
            listOf(Triple("a", 9 * 60 + 15, 10 * 60 + 15), Triple("a", 9 * 60, 10 * 60 + 15), Triple("a", 9 * 60, 9 * 60 + 45), Triple("n", 12 * 60 + 45, null)),
            retimed,
        )
    }

    @Test
    fun projectedOccurrencesCannotBeMoved() {
        show(PlannedItem(task("r", "ورزش", LocalTime.of(7, 0), LocalTime.of(8, 0)), day, isProjected = true))
        val node = compose.onNodeWithText("ورزش").performScrollTo().fetchSemanticsNode()
        assertNull(node.config.getOrNull(SemanticsActions.CustomActions))
    }

    @Test
    fun tappingAnHourAddsAtThatTime() {
        show()
        compose.onNodeWithText(fmt.time(LocalTime.of(14, 0))).performScrollTo().performClick()
        compose.runOnIdle { assertEquals(listOf(14 * 60), added) }
    }
}
