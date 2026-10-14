package ir.yadino.domain

import ir.yadino.domain.TestData.NOW
import ir.yadino.domain.model.Note
import ir.yadino.domain.model.NoteCardText
import ir.yadino.domain.model.NoteFilters
import ir.yadino.domain.model.NoteSort
import ir.yadino.domain.notes.Checklist
import ir.yadino.domain.notes.NoteBlock
import ir.yadino.domain.validation.Limits
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ChecklistTest {
    private val mixed = "خرید هفته\n- [ ] شیر\n- [x] نان\n\nیادم باشد\nکیسه ببرم\n- [ ] میوه"

    @Test
    fun parsesTextAndItemsInOrder() {
        assertEquals(
            listOf(
                NoteBlock.Text("خرید هفته"),
                NoteBlock.Item("شیر", false),
                NoteBlock.Item("نان", true),
                NoteBlock.Text("\nیادم باشد\nکیسه ببرم"),
                NoteBlock.Item("میوه", false),
            ),
            Checklist.parse(mixed),
        )
    }

    @Test
    fun serializeIsTheInverseOfParse() {
        val samples = listOf(
            mixed, "", "فقط متن", "متن\n", "\n\n", "- [ ] ", "- [ ] \n- [x] ", "- [x] تمام\n- [ ] نیمه",
            "پاراگراف اول\n\nپاراگراف دوم", "- [ ] a\n\n- [ ] b", "😀 - [ ] not an item",
        )
        for (s in samples) assertEquals(s, Checklist.serialize(Checklist.parse(s)))
    }

    @Test
    fun acceptsCommonVariantsAndNormalizesThem() {
        val blocks = Checklist.parse("* [X] الف\n- [x]ب\n  - [ ] ج\n-\t[ ]\tد\n- [ ]")
        assertEquals(
            listOf(NoteBlock.Item("الف", true), NoteBlock.Item("ب", true), NoteBlock.Item("ج"), NoteBlock.Item("د"), NoteBlock.Item("")),
            blocks,
        )
        assertEquals("- [x] الف\n- [x] ب\n- [ ] ج\n- [ ] د\n- [ ] ", Checklist.serialize(blocks))
    }

    @Test
    fun ordinaryMarkdownIsNotAnItem() {
        for (line in listOf("- خرید", "[ ] بدون خط تیره", "-[ ] بدون فاصله", "- [] خالی", "- [y] نامعتبر", "متن - [ ] وسط خط", "1. [ ] شماره")) {
            assertFalse(line, Checklist.hasItems(line))
            assertEquals(listOf(NoteBlock.Text(line)), Checklist.parse(line))
        }
    }

    @Test
    fun windowsLineEndingsAreTolerated() {
        val content = "عنوان\r\n- [x] یک\r\n- [ ] دو"
        assertEquals(1 to 2, Checklist.progress(content))
        assertEquals(listOf(NoteBlock.Item("یک", true), NoteBlock.Item("دو")), Checklist.items(content))
    }

    @Test
    fun itemTextNeverSpansLines() {
        val text = Checklist.serialize(listOf(NoteBlock.Item("دو\nخط", false), NoteBlock.Item("سه\r\nخط", true)))
        assertEquals("- [ ] دو خط\n- [x] سه  خط", text)
        assertEquals(2, Checklist.items(text).size)
    }

    @Test
    fun progressCountsCheckedItems() {
        assertEquals(1 to 3, Checklist.progress(mixed))
        assertNull(Checklist.progress("بدون فهرست"))
        assertNull(Checklist.progress(""))
        assertEquals(0 to 1, Checklist.progress("- [ ] "))
    }

    @Test
    fun toggleChangesOnlyTheRequestedItem() {
        val toggled = Checklist.toggle(mixed, 0)
        assertEquals("خرید هفته\n- [x] شیر\n- [x] نان\n\nیادم باشد\nکیسه ببرم\n- [ ] میوه", toggled)
        assertEquals("خرید هفته\n- [ ] شیر\n- [ ] نان\n\nیادم باشد\nکیسه ببرم\n- [ ] میوه", Checklist.toggle(mixed, 1))
        assertEquals(mixed, Checklist.toggle(toggled, 0))
        assertEquals(mixed, Checklist.toggle(mixed, 3))
        assertEquals(mixed, Checklist.toggle(mixed, -1))
    }

    @Test
    fun plainAndDisplayText() {
        assertEquals("خرید هفته\nشیر\nنان\n\nیادم باشد\nکیسه ببرم\nمیوه", Checklist.plainText(mixed))
        assertEquals("خرید هفته\n☐ شیر\n☑ نان\n\nیادم باشد\nکیسه ببرم\n☐ میوه", Checklist.displayText(mixed))
        assertEquals("متن ساده", Checklist.displayText("متن ساده"))
    }

    @Test
    fun splitSeparatesTextFromNonEmptyItems() {
        val long = "ب".repeat(Limits.TITLE + 50)
        val split = Checklist.split("$mixed\n- [ ]   \n- [ ]  $long ")
        assertEquals("خرید هفته\n\nیادم باشد\nکیسه ببرم", split.text)
        assertEquals(listOf("شیر", "نان", "میوه", "ب".repeat(Limits.TITLE)), split.items.map { it.text })
        assertEquals(listOf(false, true, false, false), split.items.map { it.checked })
        assertEquals("", Checklist.split("- [ ] الف").text)
    }

    @Test
    fun linesToItemsSkipsBlankLines() {
        assertEquals(listOf(NoteBlock.Item("نان"), NoteBlock.Item("شیر")), Checklist.linesToItems("  نان \n\n شیر\n"))
        assertEquals(listOf(NoteBlock.Item("")), Checklist.linesToItems("  \n"))
    }

    @Test
    fun clearCheckedAndUncheckAll() {
        val blocks = Checklist.parse(mixed)
        assertEquals("خرید هفته\n- [ ] شیر\n\nیادم باشد\nکیسه ببرم\n- [ ] میوه", Checklist.serialize(Checklist.clearChecked(blocks)))
        assertEquals(0 to 3, Checklist.progress(Checklist.serialize(Checklist.uncheckAll(blocks))))
    }

    @Test
    fun noteTitlesSortingAndBlanknessIgnoreChecklistSyntax() {
        fun note(title: String, content: String) = Note("id-$title-$content", title, content, createdAt = NOW, updatedAt = NOW)
        assertEquals("شیر", NoteFilters.displayTitle(note("", "- [ ] \n- [x] شیر\n- [ ] نان")))
        assertEquals("عنوان", NoteFilters.displayTitle(note("عنوان", "- [ ] شیر")))
        assertTrue(note("", "- [ ] ").isBlank)
        assertTrue(note("", "- [ ] \n- [x]   ").isBlank)
        assertFalse(note("", "- [ ] شیر").isBlank)
        val sorted = NoteFilters.sort(listOf(note("", "- [ ] ب"), note("", "- [x] الف")), NoteSort.TITLE_ASC)
        assertEquals(listOf("الف", "ب"), sorted.map { NoteFilters.displayTitle(it) })
    }

    @Test
    fun cardTextNeverHidesOrRepeatsContent() {
        fun note(title: String, content: String) = Note("n", title, content, createdAt = NOW, updatedAt = NOW)
        assertEquals(NoteCardText("عنوان", "متن"), NoteFilters.card(note(" عنوان ", "متن")))
        // Untitled checklist: no title line, all items stay in the body.
        assertEquals(NoteCardText(null, "- [ ] شیر\n- [x] نان"), NoteFilters.card(note("", "- [ ] شیر\n- [x] نان")))
        // Untitled text: first line is the title and is not repeated.
        assertEquals(NoteCardText("خط اول", "خط دوم\nخط سوم"), NoteFilters.card(note("", "\n  خط اول \nخط دوم\nخط سوم\n")))
        assertEquals(NoteCardText("فقط یک خط", ""), NoteFilters.card(note("", "فقط یک خط")))
        // A long first line is shortened in the title, so the body keeps the full text.
        val long = "الف".repeat(30)
        val card = NoteFilters.card(note("", long), maxTitleLength = 20)
        assertEquals("الف".repeat(30).take(20) + "…", card.title)
        assertEquals(long, card.body)
        assertEquals(NoteCardText("", ""), NoteFilters.card(note("", "  \n ")))
    }
}
