package ir.yadino.domain

import ir.yadino.domain.notes.ChecklistEditor
import ir.yadino.domain.notes.NoteBlock
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ChecklistEditorTest {
    private fun ChecklistEditor.keyOf(index: Int) = entries[index].key
    private fun ChecklistEditor.blocks() = entries.map { it.block }

    @Test
    fun emptyNoteHasOneTextBlock() {
        val e = ChecklistEditor("")
        assertEquals(listOf(NoteBlock.Text("")), e.blocks())
        assertEquals("", e.content())
        assertFalse(e.hasItems)
        assertNull(e.progress())
    }

    @Test
    fun loadingKeepsContentExactly() {
        val content = "عنوان\n- [ ] یک\n- [x] دو\n\nپایان"
        val e = ChecklistEditor(content)
        assertEquals(content, e.content())
        assertEquals(1 to 2, e.progress())
        assertEquals(e.entries.size, e.entries.map { it.key }.toSet().size)
    }

    @Test
    fun typingInTextAndItems() {
        val e = ChecklistEditor("متن\n- [ ] شی")
        assertNull(e.editText(e.keyOf(0), "متن تازه"))
        assertNull(e.editText(e.keyOf(1), "شیر"))
        assertEquals("متن تازه\n- [ ] شیر", e.content())
        // A line break typed in a text block stays text.
        e.editText(e.keyOf(0), "خط ۱\nخط ۲")
        assertEquals("خط ۱\nخط ۲\n- [ ] شیر", e.content())
    }

    @Test
    fun enterAtTheEndOfAnItemStartsANewItem() {
        val e = ChecklistEditor("- [x] شیر")
        val first = e.keyOf(0)
        val focus = e.editText(first, "شیر\n")
        assertNotNull(focus)
        assertEquals(listOf(NoteBlock.Item("شیر", true), NoteBlock.Item("")), e.blocks())
        assertEquals(e.keyOf(1), focus!!.key)
        assertEquals(0, focus.cursor)
        assertEquals(first, e.keyOf(0))
    }

    @Test
    fun enterInTheMiddleSplitsTheItem() {
        val e = ChecklistEditor("- [ ] شیرنان")
        val focus = e.editText(e.keyOf(0), "شیر\nنان")
        assertEquals(listOf(NoteBlock.Item("شیر"), NoteBlock.Item("نان")), e.blocks())
        assertEquals(ChecklistEditor.Focus(e.keyOf(1), 0), focus)
    }

    @Test
    fun enterOnAnEmptyItemLeavesTheList() {
        val e = ChecklistEditor("- [ ] شیر\n- [ ] ")
        val key = e.keyOf(1)
        val focus = e.editText(key, "\n")
        assertEquals(listOf(NoteBlock.Item("شیر"), NoteBlock.Text("")), e.blocks())
        assertEquals(ChecklistEditor.Focus(key, 0), focus)
        assertEquals("- [ ] شیر\n", e.content())
    }

    @Test
    fun pastingSeveralLinesCreatesSeveralItems() {
        val e = ChecklistEditor("- [ ] ")
        val focus = e.editText(e.keyOf(0), "شیر\r\n\n  نان  \n- [x] تخم‌مرغ\n")
        assertEquals(listOf(NoteBlock.Item("شیر"), NoteBlock.Item("نان"), NoteBlock.Item("تخم‌مرغ", true)), e.blocks())
        assertEquals(ChecklistEditor.Focus(e.keyOf(2), null), focus)
    }

    @Test
    fun toggleAndRemove() {
        val e = ChecklistEditor("متن\n- [ ] الف\n- [ ] ب")
        e.toggle(e.keyOf(2))
        assertEquals("متن\n- [ ] الف\n- [x] ب", e.content())
        e.toggle(e.keyOf(0)) // text blocks ignore toggles
        assertEquals("متن\n- [ ] الف\n- [x] ب", e.content())
        val previous = e.keyOf(1)
        assertEquals(ChecklistEditor.Focus(previous), e.remove(e.keyOf(2)))
        assertEquals("متن\n- [ ] الف", e.content())
        assertNull(e.remove(-42))
    }

    @Test
    fun removingTheLastBlockLeavesAnEmptyText() {
        val e = ChecklistEditor("- [ ] تنها")
        val focus = e.remove(e.keyOf(0))
        assertEquals(listOf(NoteBlock.Text("")), e.blocks())
        assertEquals(e.keyOf(0), focus!!.key)
    }

    @Test
    fun appendItemReplacesTrailingBlankText() {
        val e = ChecklistEditor("- [ ] شیر\n")
        val focus = e.appendItem()
        assertEquals(listOf(NoteBlock.Item("شیر"), NoteBlock.Item("")), e.blocks())
        assertEquals(e.keyOf(1), focus.key)
        val fresh = ChecklistEditor("")
        fresh.appendItem()
        assertEquals(listOf(NoteBlock.Item("")), fresh.blocks())
        val withText = ChecklistEditor("پاراگراف")
        withText.appendItem()
        assertEquals("پاراگراف\n- [ ] ", withText.content())
    }

    @Test
    fun checklistButtonConvertsTheParagraphUnderTheCursor() {
        val text = "مقدمه\n\nشیر\nنان\n\nپایان"
        val e = ChecklistEditor(text)
        val key = e.keyOf(0)
        val cursorInNan = text.indexOf("نان") + 1
        val result = e.toggleChecklist(key, cursorInNan)
        assertFalse(result.toText)
        assertEquals(
            listOf(NoteBlock.Text("مقدمه\n"), NoteBlock.Item("شیر"), NoteBlock.Item("نان"), NoteBlock.Text("\nپایان")),
            e.blocks(),
        )
        assertEquals(key, e.keyOf(0))
        assertEquals(e.keyOf(2), result.focus!!.key)
        assertEquals("مقدمه\n\n- [ ] شیر\n- [ ] نان\n\nپایان", e.content())
    }

    @Test
    fun checklistButtonOnABlankLineMakesOneEmptyItem() {
        val text = "مقدمه\n\nپایان"
        val e = ChecklistEditor(text)
        e.toggleChecklist(e.keyOf(0), text.indexOf('\n') + 1)
        assertEquals(listOf(NoteBlock.Text("مقدمه"), NoteBlock.Item(""), NoteBlock.Text("پایان")), e.blocks())
    }

    @Test
    fun checklistButtonConvertsSelectedLinesOnly() {
        val text = "یک\nدو\nسه\nچهار"
        val e = ChecklistEditor(text)
        e.toggleChecklist(e.keyOf(0), text.indexOf("دو"), text.indexOf("سه") + 1)
        assertEquals("یک\n- [ ] دو\n- [ ] سه\nچهار", e.content())
    }

    @Test
    fun checklistButtonOnAnItemTurnsItsRunBackIntoText() {
        val e = ChecklistEditor("مقدمه\n- [ ] شیر\n- [x] نان\nپایان\n- [ ] جدا")
        val introKey = e.keyOf(0)
        val result = e.toggleChecklist(e.keyOf(1))
        assertTrue(result.toText)
        assertTrue(result.droppedChecks)
        assertEquals(listOf(NoteBlock.Text("مقدمه\nشیر\nنان\nپایان"), NoteBlock.Item("جدا")), e.blocks())
        assertEquals(ChecklistEditor.Focus(introKey, "مقدمه\nشیر".length), result.focus)
    }

    @Test
    fun checklistButtonRoundTripsAParagraph() {
        val e = ChecklistEditor("شیر\nنان")
        e.toggleChecklist(e.keyOf(0), 0)
        assertEquals("- [ ] شیر\n- [ ] نان", e.content())
        val back = e.toggleChecklist(e.keyOf(1))
        assertFalse(back.droppedChecks)
        assertEquals("شیر\nنان", e.content())
        assertEquals("شیر\nنان".length, back.focus!!.cursor)
    }

    @Test
    fun checklistButtonWithoutFocus() {
        val e = ChecklistEditor("شیر\n\nنان")
        val result = e.toggleChecklist(null)
        assertEquals(listOf(NoteBlock.Item("شیر"), NoteBlock.Item("نان")), e.blocks())
        assertEquals(e.keyOf(1), result.focus!!.key)
        // With items already present a new empty item is appended.
        e.toggleChecklist(null)
        assertEquals("- [ ] شیر\n- [ ] نان\n- [ ] ", e.content())
        val empty = ChecklistEditor("")
        empty.toggleChecklist(null)
        assertEquals(listOf(NoteBlock.Item("")), empty.blocks())
    }

    @Test
    fun clearCheckedAndUncheckAll() {
        val e = ChecklistEditor("- [x] الف\n- [ ] ب\n- [x] پ")
        assertEquals(2, e.uncheckAll())
        assertEquals(0, e.uncheckAll())
        e.toggle(e.keyOf(0))
        e.toggle(e.keyOf(2))
        assertEquals(2, e.clearChecked())
        assertEquals("- [ ] ب", e.content())
        assertEquals(0, e.clearChecked())
        val allDone = ChecklistEditor("- [x] الف\n- [x] ب")
        assertEquals(2, allDone.clearChecked())
        assertEquals(listOf(NoteBlock.Text("")), allDone.blocks())
    }

    @Test
    fun resetRenewsKeys() {
        val e = ChecklistEditor("- [ ] الف")
        val old = e.keyOf(0)
        e.reset("- [ ] الف")
        assertTrue(e.keyOf(0) != old)
    }
}
