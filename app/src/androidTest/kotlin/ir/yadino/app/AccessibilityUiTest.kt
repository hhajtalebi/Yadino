package ir.yadino.app

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.material.icons.filled.Search
import androidx.test.ext.junit.runners.AndroidJUnit4
import ir.yadino.app.ui.common.TaskRow
import ir.yadino.app.ui.components.EmptyState
import ir.yadino.app.ui.theme.YadinoTheme
import ir.yadino.calendar.JalaliDate
import ir.yadino.domain.model.AppSettings
import ir.yadino.domain.model.Task
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.time.Instant

/** UI checks with the largest text size: content stays visible and controls are labelled. */
@RunWith(AndroidJUnit4::class)
class AccessibilityUiTest {
    @get:Rule
    val compose = createComposeRule()

    private val today = JalaliDate.of(1405, 7, 22).toLocalDate()

    @Test
    fun taskRowIsReadableAndLabelledAtLargestFontScale() {
        var toggled = 0
        val task = Task(id = "1", title = "تحویل گزارش ماهانه", date = today, createdAt = Instant.EPOCH, updatedAt = Instant.EPOCH)
        compose.setContent {
            YadinoTheme(AppSettings(fontScale = AppSettings.MAX_FONT_SCALE)) {
                TaskRow(task = task, today = today, onToggle = { toggled++ }, onClick = {})
            }
        }
        compose.onNodeWithText("تحویل گزارش ماهانه").assertIsDisplayed()
        compose.onNodeWithContentDescription("تغییر وضعیت انجام «تحویل گزارش ماهانه»").assertIsDisplayed().performClick()
        assertEquals(1, toggled)
    }

    @Test
    fun emptyStateShowsPersianText() {
        compose.setContent {
            YadinoTheme(AppSettings(fontScale = 1.3f)) {
                EmptyState(androidx.compose.material.icons.Icons.Default.Search, "فهرست خالی است", "کاری برای نمایش نیست")
            }
        }
        compose.onNodeWithText("فهرست خالی است").assertIsDisplayed()
    }
}
