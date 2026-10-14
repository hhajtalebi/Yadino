package ir.yadino.app

import androidx.compose.material3.TextFieldDefaults
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.isFocused
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import ir.yadino.app.ui.notes.NoteBodyActions
import ir.yadino.app.ui.notes.NoteBodyEditor
import ir.yadino.app.ui.notes.NoteBodyFocus
import ir.yadino.app.ui.theme.YadinoTheme
import ir.yadino.domain.model.AppSettings
import ir.yadino.domain.notes.ChecklistEditor
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** The checklist editor of notes, wired like NoteEditorViewModel but without the database. */
@RunWith(AndroidJUnit4::class)
class NoteChecklistUiTest {
    @get:Rule
    val compose = createComposeRule()

    private class Host(content: String) : NoteBodyActions {
        val editor = ChecklistEditor(content)
        var blocks by mutableStateOf(editor.entries)
        var focus by mutableStateOf<ChecklistEditor.Focus?>(null)

        private fun changed(f: ChecklistEditor.Focus? = null) {
            blocks = editor.entries
            if (f != null) focus = f
        }

        override fun editBlock(key: Long, text: String) = changed(editor.editText(key, text))
        override fun toggleItem(key: Long) {
            editor.toggle(key)
            changed()
        }
        override fun removeBlock(key: Long, moveFocus: Boolean) {
            val f = editor.remove(key)
            changed(if (moveFocus) f else null)
        }
        override fun addItem() = changed(editor.appendItem())
        override fun consumeFocus() {
            focus = null
        }
        override fun moveItem(key: Long, delta: Int): Boolean = editor.move(key, delta).also { if (it) changed() }
    }

    private fun show(host: Host) {
        compose.setContent {
            YadinoTheme(AppSettings(fontScale = 1.3f)) {
                NoteBodyEditor(host.blocks, host.focus, remember { NoteBodyFocus() }, host, TextFieldDefaults.colors())
            }
        }
    }

    @Test
    fun checkingAnItemUpdatesTheNoteAndTheProgress() {
        val host = Host("- [ ] شیر\n- [ ] نان")
        show(host)
        compose.onNodeWithText("۰ از ۲ انجام شده").assertIsDisplayed()
        compose.onNodeWithContentDescription("شیر").assertIsDisplayed().performClick()
        compose.runOnIdle { assertEquals("- [x] شیر\n- [ ] نان", host.editor.content()) }
        compose.onNodeWithText("۱ از ۲ انجام شده").assertIsDisplayed()
    }

    @Test
    fun enterStartsTheNextItemAndMovesTheCursorThere() {
        val host = Host("- [ ] شیر")
        show(host)
        compose.onNodeWithText("شیر").performTextInput("\n")
        compose.runOnIdle { assertEquals("- [ ] شیر\n- [ ] ", host.editor.content()) }
        compose.onNode(isFocused()).performTextInput("نان")
        compose.runOnIdle { assertEquals("- [ ] شیر\n- [ ] نان", host.editor.content()) }
    }

    @Test
    fun removeButtonDeletesOnlyThatItem() {
        val host = Host("- [ ] شیر\n- [ ] نان")
        show(host)
        compose.onAllNodesWithContentDescription("حذف مورد")[1].performClick()
        compose.runOnIdle { assertEquals("- [ ] شیر", host.editor.content()) }
    }

    @Test
    fun addButtonAppendsAnEmptyItem() {
        val host = Host("- [x] شیر")
        show(host)
        compose.onNodeWithText("افزودن مورد").performClick()
        compose.runOnIdle { assertEquals("- [x] شیر\n- [ ] ", host.editor.content()) }
        compose.onNode(isFocused()).performTextInput("نان")
        compose.runOnIdle { assertEquals("- [x] شیر\n- [ ] نان", host.editor.content()) }
    }

    @Test
    fun itemsCanBeReorderedWithAccessibilityActions() {
        val host = Host("- [ ] شیر\n- [ ] نان\n- [ ] میوه")
        show(host)
        val moveDown = InstrumentationRegistry.getInstrumentation().targetContext.getString(ir.yadino.app.R.string.move_down)
        val node = compose.onNodeWithContentDescription("شیر").fetchSemanticsNode()
        val action = node.config[SemanticsActions.CustomActions].first { it.label == moveDown }
        compose.runOnIdle { action.action() }
        compose.runOnIdle { assertEquals("- [ ] نان\n- [ ] شیر\n- [ ] میوه", host.editor.content()) }
        // the last item offers no "down" any more
        val last = compose.onNodeWithContentDescription("میوه").fetchSemanticsNode()
        assertFalse(last.config[SemanticsActions.CustomActions].any { it.label == moveDown })
    }
}
