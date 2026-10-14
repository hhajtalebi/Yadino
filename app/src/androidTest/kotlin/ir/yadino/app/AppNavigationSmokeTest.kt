package ir.yadino.app

import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.SemanticsNodeInteraction
import androidx.compose.ui.test.hasScrollToNodeAction
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performImeAction
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.test.performTextInput
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * End-to-end smoke test on a device/emulator: launches the real app and walks through every
 * main screen, creating a task, a note, a goal and a habit on the way. Any crash or missing
 * screen element fails the test. All UI texts are the Persian strings the user sees.
 */
@RunWith(AndroidJUnit4::class)
class AppNavigationSmokeTest {

    @get:Rule
    val rule = createAndroidComposeRule<MainActivity>()

    private val timeout = 15_000L

    private fun exists(text: String, substring: Boolean = false) =
        rule.onAllNodesWithText(text, substring = substring, useUnmergedTree = true).fetchSemanticsNodes().isNotEmpty()

    private fun waitForText(text: String, substring: Boolean = false) =
        rule.waitUntil(timeout) { exists(text, substring) }

    private fun SemanticsNodeInteraction.scrollAndClick() {
        try {
            performScrollTo()
        } catch (_: AssertionError) {
            // not inside a scrollable container
        }
        performClick()
        rule.waitForIdle()
    }

    private fun clickText(text: String, substring: Boolean = false) {
        if (!exists(text, substring)) {
            // the element may be in a lazy list that has not composed it yet
            val scrollables = rule.onAllNodes(hasScrollToNodeAction()).fetchSemanticsNodes().size
            for (i in 0 until scrollables) {
                try {
                    rule.onAllNodes(hasScrollToNodeAction())[i].performScrollToNode(hasText(text, substring = substring))
                    break
                } catch (_: AssertionError) {
                }
            }
        }
        waitForText(text, substring)
        rule.onAllNodesWithText(text, substring = substring)[0].scrollAndClick()
    }

    private fun clickDescription(description: String) {
        rule.waitUntil(timeout) { rule.onAllNodesWithContentDescription(description).fetchSemanticsNodes().isNotEmpty() }
        rule.onAllNodesWithContentDescription(description)[0].scrollAndClick()
    }

    private fun clickTab(label: String) {
        rule.onNode(hasText(label) and SemanticsMatcher.expectValue(SemanticsProperties.Role, Role.Tab)).performClick()
        rule.waitForIdle()
    }

    private fun typeInFirstField(text: String, submit: Boolean = false) {
        rule.waitUntil(timeout) { rule.onAllNodes(hasSetTextAction()).fetchSemanticsNodes().isNotEmpty() }
        val field = rule.onAllNodes(hasSetTextAction())[0]
        field.performTextInput(text)
        if (submit) field.performImeAction()
        rule.waitForIdle()
    }

    private fun back() {
        rule.runOnUiThread { rule.activity.onBackPressedDispatcher.onBackPressed() }
        rule.waitForIdle()
    }

    @Test
    fun walkThroughAllScreens() {
        // first launch shows the welcome screen
        rule.waitUntil(timeout) { exists("شروع") || exists("برنامه امروز", substring = true) }
        if (exists("شروع")) clickText("شروع")

        // dashboard + quick add to today's plan
        waitForText("برنامه امروز", substring = true)
        typeInFirstField("خرید نان آزمایشی", submit = true)
        waitForText("خرید نان آزمایشی")

        // calendar: month, week and day views
        clickTab("تقویم")
        clickText("هفته")
        clickText("روز")
        clickText("ماه")

        // tasks: lists and a new task through the editor
        clickTab("کارها")
        waitForText("خرید نان آزمایشی")
        clickText("آینده")
        clickText("عقب‌افتاده")
        clickText("همه")
        clickDescription("کار جدید")
        typeInFirstField("کار آزمایشی دوم")
        clickText("ذخیره")
        waitForText("کار آزمایشی دوم")

        // notes: autosave on back
        clickTab("یادداشت‌ها")
        clickDescription("یادداشت جدید")
        typeInFirstField("یادداشت آزمایشی")
        back()
        waitForText("یادداشت آزمایشی")

        // goals: editor -> detail
        clickTab("اهداف")
        clickDescription("هدف جدید")
        typeInFirstField("هدف آزمایشی")
        clickText("ذخیره")
        waitForText("مراحل", substring = true)
        back()

        // habits
        clickTab("عادت‌ها")
        clickDescription("عادت جدید")
        typeInFirstField("عادت آزمایشی")
        clickText("ذخیره")
        waitForText("عادت آزمایشی")

        // dashboard actions: reports and search
        clickTab("خانه")
        clickDescription("گزارش‌ها")
        waitForText("نرخ انجام برنامه")
        back()
        clickDescription("جستجو")
        typeInFirstField("آزمایشی")
        waitForText("خرید نان آزمایشی")
        back()

        // every settings page
        clickDescription("تنظیمات")
        listOf(
            "عمومی" to "قالب نمایش تاریخ",
            "تقویم و برنامه‌ریزی" to "نمای پیش‌فرض تقویم",
            "اعلان‌ها و یادآوری" to "ساعات سکوت",
            "یادداشت‌ها و اهداف" to "ذخیره خودکار یادداشت‌ها",
            "ابزارک‌ها" to "چگونه ابزارک اضافه کنم؟",
            "اطلاعات و پشتیبان‌گیری" to "ذخیره پشتیبان در فایل",
            "پیشرفته" to "نسخه برنامه",
            "دسته‌بندی‌ها" to "دسته جدید",
            "شخصی‌سازی داشبورد" to "بخش‌ها را روشن یا خاموش کنید",
            "درباره یادینو" to "حریم خصوصی",
        ).forEach { (row, expected) ->
            clickText(row)
            waitForText(expected, substring = true)
            back()
        }
    }
}
