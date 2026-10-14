package ir.yadino.domain

import ir.yadino.domain.TestData.NOW
import ir.yadino.domain.TestData.j
import ir.yadino.domain.TestData.task
import ir.yadino.domain.model.PlanTemplate
import ir.yadino.domain.model.Priority
import ir.yadino.domain.model.Subtask
import ir.yadino.domain.model.TaskKind
import ir.yadino.domain.model.TaskStatus
import ir.yadino.domain.model.TemplateItem
import ir.yadino.domain.planning.PlanTemplates
import ir.yadino.domain.recurrence.RecurrenceRule
import ir.yadino.domain.validation.Limits
import ir.yadino.domain.validation.ValidationError
import ir.yadino.domain.validation.Validators
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.DayOfWeek
import java.time.LocalTime

class TemplatesTest {

    private var n = 0
    private val newId: () -> String = { "new-${++n}" }

    /** Wednesday 22 Mehr 1405 (2026-10-14). */
    private val day = j(1405, 7, 22)

    /** Saturday 18 Mehr 1405: first day of that week in Iran. */
    private val saturday = j(1405, 7, 18)

    private val dayTemplate = PlanTemplate("tpl", "روز", createdAt = NOW, updatedAt = NOW)
    private val weekTemplate = PlanTemplate("tpl", "هفته", spanDays = PlanTemplate.WEEK, createdAt = NOW, updatedAt = NOW)

    private fun item(title: String, dayOffset: Int? = 0, build: TemplateItem.() -> TemplateItem = { this }) =
        TemplateItem(TestData.id("item"), "tpl", title, dayOffset = dayOffset, createdAt = NOW, updatedAt = NOW).build()

    @Test
    fun calendarFactsUsedByTheseTests() {
        assertEquals(DayOfWeek.WEDNESDAY, day.dayOfWeek)
        assertEquals(DayOfWeek.SATURDAY, saturday.dayOfWeek)
        assertEquals(DayOfWeek.SATURDAY, PlanTemplates.weekdayOf(0))
        assertEquals(DayOfWeek.FRIDAY, PlanTemplates.weekdayOf(6))
        assertEquals(2, PlanTemplates.offsetOf(DayOfWeek.MONDAY))
    }

    @Test
    fun dayPlanBecomesTemplateWithTimesDeadlinesAndSubtasks() {
        val timed = task("کار عمیق", day) {
            copy(startTime = LocalTime.of(9, 0), endTime = LocalTime.of(11, 30), priority = Priority.HIGH, tags = listOf("کار"),
                reminderOffsetMinutes = 10, recurrence = RecurrenceRule.daily(), recurrenceStart = day, status = TaskStatus.DONE, sortOrder = 2)
        }
        val untimed = task("خرید", day) { copy(sortOrder = 1, dueDate = day.plusDays(2), dueTime = LocalTime.of(18, 0)) }
        val deadlineOnly = task("پرداخت قبض") { copy(dueDate = day, kind = TaskKind.REMINDER) }
        val archived = task("بایگانی", day) { copy(isArchived = true) }
        val cancelled = task("لغو", day) { copy(status = TaskStatus.CANCELLED) }
        val otherDay = task("فردا", day.plusDays(1))
        val subtasks = listOf(
            Subtask("s2", untimed.id, "میوه", true, 1, NOW, NOW),
            Subtask("s1", untimed.id, "نان\nتازه", false, 0, NOW, NOW),
            Subtask("s3", untimed.id, "   ", false, 2, NOW, NOW),
        )
        val (template, items) = PlanTemplates.fromPlan(
            listOf(timed, untimed, deadlineOnly, archived, cancelled, otherDay), subtasks, day, PlanTemplate.DAY, "  روز کاری ", NOW, newId,
        )!!
        assertEquals("روز کاری", template.name)
        assertEquals(PlanTemplate.DAY, template.spanDays)
        assertEquals(listOf("پرداخت قبض", "خرید", "کار عمیق"), items.map { it.title })
        assertTrue(items.all { it.templateId == template.id })
        assertEquals(listOf(0, 1, 2), items.map { it.sortOrder })

        val deep = items.first { it.title == "کار عمیق" }
        assertEquals(0, deep.dayOffset)
        assertEquals(LocalTime.of(9, 0), deep.startTime)
        assertEquals(LocalTime.of(11, 30), deep.endTime)
        assertEquals(Priority.HIGH, deep.priority)
        assertEquals(10, deep.reminderOffsetMinutes)
        assertEquals(listOf("کار"), deep.tags)

        val shopping = items.first { it.title == "خرید" }
        assertEquals(2, shopping.dueOffset)
        assertEquals(2, shopping.dueAfterDays)
        assertEquals(LocalTime.of(18, 0), shopping.dueTime)
        assertEquals(listOf("نان تازه", "میوه"), shopping.subtasks)

        val bill = items.first { it.title == "پرداخت قبض" }
        assertNull(bill.dayOffset)
        assertEquals(0, bill.dueOffset)
        assertEquals(TaskKind.REMINDER, bill.kind)
        assertTrue(Validators.template(template, items, emptyList()).isEmpty())
    }

    @Test
    fun weekPlanStoresWeekdaysAndEmptyPlanGivesNothing() {
        val tasks = listOf(
            task("شنبه", saturday),
            task("سه‌شنبه", saturday.plusDays(3)) { copy(startTime = LocalTime.of(14, 0)) },
            task("جمعه", saturday.plusDays(6)) { copy(dueDate = saturday.plusDays(9)) },
            task("هفته بعد", saturday.plusDays(7)),
        )
        val (template, items) = PlanTemplates.fromPlan(tasks, emptyList(), saturday, PlanTemplate.WEEK, "هفته", NOW, newId)!!
        assertTrue(template.isWeek)
        assertEquals(listOf(0, 3, 6), items.map { it.dayOffset })
        assertEquals(9, items.last().dueOffset)
        assertEquals(3, items.last().dueAfterDays)
        assertTrue(Validators.template(template, items, emptyList()).isEmpty())

        assertNull(PlanTemplates.fromPlan(tasks, emptyList(), saturday.plusDays(14), PlanTemplate.DAY, "خالی", NOW, newId))
    }

    @Test
    fun weekTemplatesKeepTheirWeekdaysWhateverTheFirstDayOfWeek() {
        // saved by someone whose week starts on Monday (20 Mehr)
        val monday = saturday.plusDays(2)
        val tasks = listOf(
            task("دوشنبه", monday),
            task("جمعه", monday.plusDays(4)) { copy(dueDate = monday.plusDays(5)) }, // deadline on Saturday
            task("یکشنبه", monday.plusDays(6)),
        )
        val (template, items) = PlanTemplates.fromPlan(tasks, emptyList(), monday, PlanTemplate.WEEK, "هفته", NOW, newId)!!
        assertEquals(listOf(2, 6, 1), items.map { it.dayOffset })
        assertEquals(7, items.first { it.title == "جمعه" }.dueOffset)

        // applied by a Saturday-first user to the week of Wednesday 29 Mehr
        val bySaturday = PlanTemplates.apply(template, items, day.plusDays(7), DayOfWeek.SATURDAY, emptyList(), true, NOW, newId)
        assertEquals(saturday.plusDays(7), bySaturday.start)
        assertEquals(
            mapOf("دوشنبه" to DayOfWeek.MONDAY, "جمعه" to DayOfWeek.FRIDAY, "یکشنبه" to DayOfWeek.SUNDAY),
            bySaturday.tasks.associate { it.title to it.date!!.dayOfWeek },
        )
        bySaturday.tasks.forEach { t -> assertTrue(t.date!! >= bySaturday.start && t.date!! <= bySaturday.start.plusDays(6)) }
        val friday = bySaturday.tasks.first { it.title == "جمعه" }
        assertEquals(friday.date!!.plusDays(1), friday.dueDate)

        // and by a Monday-first user: same weekdays, inside that user's week
        val byMonday = PlanTemplates.apply(template, items, day.plusDays(7), DayOfWeek.MONDAY, emptyList(), true, NOW, newId)
        assertEquals(monday.plusDays(7), byMonday.start)
        assertEquals(listOf(monday.plusDays(7), monday.plusDays(11), monday.plusDays(13)), byMonday.tasks.map { it.date })
        assertEquals(monday.plusDays(12), byMonday.tasks.first { it.title == "جمعه" }.dueDate)
        assertEquals(listOf("دوشنبه", "جمعه", "یکشنبه"), items.sortedWith(PlanTemplates.itemOrder(template, DayOfWeek.MONDAY)).map { it.title })
        assertEquals(listOf("یکشنبه", "دوشنبه", "جمعه"), items.sortedWith(PlanTemplates.itemOrder(template, DayOfWeek.SATURDAY)).map { it.title })
    }

    @Test
    fun applyingCreatesFreshTasksAfterTheExistingOrder() {
        val items = listOf(
            item("ورزش") { copy(startTime = LocalTime.of(7, 0), endTime = LocalTime.of(7, 45), reminderOffsetMinutes = 5) },
            item("مرور ایمیل", dayOffset = 2) { copy(sortOrder = 1, subtasks = listOf("صندوق کاری", "صندوق شخصی"), goalId = "g1") },
            item("گزارش", dayOffset = null) { copy(dueOffset = 4, dueTime = LocalTime.of(12, 0), priority = Priority.URGENT) },
        )
        assertTrue(Validators.template(weekTemplate, items, emptyList()).isEmpty())
        val start = j(1405, 8, 2) // Saturday 2 Aban
        val existing = listOf(task("قبلی", start) { copy(sortOrder = 5) }, task("قبلی ۲", start.plusDays(2)) { copy(sortOrder = 1) })
        val applied = PlanTemplates.apply(weekTemplate, items, start.plusDays(3), DayOfWeek.SATURDAY, existing, true, NOW, newId)
        assertEquals(start, applied.start)
        assertEquals(0, applied.skipped)
        assertEquals(3, applied.tasks.size)

        val sport = applied.tasks.first { it.title == "ورزش" }
        assertEquals(start, sport.date)
        assertEquals(LocalTime.of(7, 0), sport.startTime)
        assertEquals(LocalTime.of(7, 45), sport.endTime)
        assertEquals(5, sport.reminderOffsetMinutes)
        assertEquals(6, sport.sortOrder)
        assertEquals(TaskStatus.TODO, sport.status)
        assertNull(sport.recurrence)

        val mail = applied.tasks.first { it.title == "مرور ایمیل" }
        assertEquals(start.plusDays(2), mail.date)
        assertEquals(2, mail.sortOrder)
        assertEquals("g1", mail.goalId)
        val subs = applied.subtasks.filter { it.taskId == mail.id }
        assertEquals(listOf("صندوق کاری", "صندوق شخصی"), subs.map { it.title })
        assertTrue(subs.none { it.isDone })

        val report = applied.tasks.first { it.title == "گزارش" }
        assertNull(report.date)
        assertEquals(start.plusDays(4), report.dueDate)
        assertEquals(LocalTime.of(12, 0), report.dueTime)
        assertEquals(Priority.URGENT, report.priority)

        // every created task is valid and has its own id
        assertTrue(applied.tasks.all { Validators.task(it).isEmpty() })
        assertEquals(applied.tasks.size + applied.subtasks.size, (applied.tasks.map { it.id } + applied.subtasks.map { it.id }).toSet().size)

        // a day template lands on the chosen day itself
        val single = PlanTemplates.apply(dayTemplate, listOf(item("ورزش") { copy(dueOffset = 1) }), day, DayOfWeek.SATURDAY, emptyList(), true, NOW, newId)
        assertEquals(day, single.start)
        assertEquals(day, single.tasks.single().date)
        assertEquals(day.plusDays(1), single.tasks.single().dueDate)
    }

    @Test
    fun duplicatesAreDetectedAndCanBeSkipped() {
        val items = listOf(item("مطالعهٔ کتاب"), item("می‌روم باشگاه"), item("تماس"))
        val existing = listOf(
            task("مطالعهٔ  کتاب", day) { copy(status = TaskStatus.DONE) },
            // Arabic Yeh and no zero-width non-joiner: still the same title
            task("مي روم باشگاه", day),
            task("تماس", day) { copy(status = TaskStatus.CANCELLED) },
            task("تماس", day.plusDays(1)),
        )
        val preview = PlanTemplates.preview(dayTemplate, items, day, DayOfWeek.SATURDAY, existing)
        assertEquals(listOf(true, true, false), items.map { i -> preview.first { it.item.id == i.id }.isDuplicate })
        assertTrue(preview.all { it.date == day })
        val skipping = PlanTemplates.apply(dayTemplate, items, day, DayOfWeek.SATURDAY, existing, skipDuplicates = true, now = NOW, newId = newId)
        assertEquals(2, skipping.skipped)
        assertEquals(listOf("تماس"), skipping.tasks.map { it.title })
        val all = PlanTemplates.apply(dayTemplate, items, day, DayOfWeek.SATURDAY, existing, skipDuplicates = false, now = NOW, newId = newId)
        assertEquals(0, all.skipped)
        assertEquals(3, all.tasks.size)
    }

    @Test
    fun titleKeyIgnoresPersianWritingVariants() {
        assertEquals(PlanTemplates.titleKey("کتاب‌خوانی ۲"), PlanTemplates.titleKey(" كتاب خواني 2 "))
        assertEquals(PlanTemplates.titleKey("Report"), PlanTemplates.titleKey("report"))
        assertNotEquals(PlanTemplates.titleKey("کتاب"), PlanTemplates.titleKey("کتابها"))
    }

    @Test
    fun validationCoversNamesDaysTimesAndLimits() {
        val t = PlanTemplate("t1", "روز کاری", createdAt = NOW, updatedAt = NOW)
        val ok = item("کار")
        assertTrue(Validators.template(t, listOf(ok), emptyList()).isEmpty())
        assertEquals(listOf(ValidationError.NAME_REQUIRED, ValidationError.ITEMS_REQUIRED), Validators.template(t.copy(name = "  "), emptyList(), emptyList()))
        // Arabic Kaf and Yeh look the same as the Persian letters: still a duplicate name
        val other = PlanTemplate("t2", "روز كاري", createdAt = NOW, updatedAt = NOW)
        assertEquals(listOf(ValidationError.NAME_DUPLICATE), Validators.template(t, listOf(ok), listOf(other, t)))
        assertTrue(ValidationError.NAME_TOO_LONG in Validators.template(t.copy(name = "ا".repeat(Limits.TEMPLATE_NAME + 1)), listOf(ok), emptyList()))

        assertEquals(listOf(ValidationError.DAY_OUT_OF_RANGE), Validators.template(t, listOf(item("سه‌شنبه", dayOffset = 3)), emptyList()))
        assertTrue(Validators.template(t.copy(spanDays = PlanTemplate.WEEK), listOf(item("سه‌شنبه", dayOffset = 3)), emptyList()).isEmpty())
        assertEquals(listOf(ValidationError.DAY_OUT_OF_RANGE), Validators.templateItem(item("بی‌روز", dayOffset = null), 1))
        assertEquals(listOf(ValidationError.DAY_OUT_OF_RANGE), Validators.templateItem(item("مهلت", dayOffset = null) { copy(dueOffset = 1) }, 1))
        assertTrue(Validators.templateItem(item("مهلت", dayOffset = null) { copy(dueOffset = 6) }, 7).isEmpty())
        assertTrue(Validators.templateItem(item("دور", dayOffset = 6) { copy(dueOffset = 6 + Limits.TEMPLATE_DUE_OFFSET) }, 7).isEmpty())

        assertEquals(listOf(ValidationError.END_BEFORE_START),
            Validators.templateItem(ok.copy(startTime = LocalTime.of(10, 0), endTime = LocalTime.of(9, 0)), 1))
        assertEquals(listOf(ValidationError.TIME_WITHOUT_DATE),
            Validators.templateItem(ok.copy(dayOffset = null, dueOffset = 0, startTime = LocalTime.of(10, 0)), 1))
        assertEquals(listOf(ValidationError.DUE_BEFORE_DATE), Validators.templateItem(item("هفته", dayOffset = 3) { copy(dueOffset = 1) }, 7))
        assertEquals(listOf(ValidationError.REMINDER_OUT_OF_RANGE), Validators.templateItem(ok.copy(reminderOffsetMinutes = -1), 1))
        assertEquals(listOf(ValidationError.TOO_MANY_ITEMS),
            Validators.templateItem(ok.copy(subtasks = List(Limits.TEMPLATE_SUBTASKS + 1) { "زیرکار $it" }), 1))
        assertEquals(listOf(ValidationError.TITLE_REQUIRED), Validators.templateItem(ok.copy(title = " "), 1))
        assertTrue(ValidationError.TOO_MANY_ITEMS in Validators.template(t, List(Limits.TEMPLATE_ITEMS + 1) { item("کار $it") }, emptyList()))
    }

    @Test
    fun changingTheSpanMovesItemsAndKeepsDeadlineDistances() {
        val weekItems = listOf(
            item("سه‌شنبه", 3) { copy(dueOffset = 5) },
            item("مهلت پنجشنبه", null) { copy(dueOffset = 5) },
        )
        val asDay = PlanTemplates.changeSpan(weekItems, toWeek = false, firstDayOfWeek = DayOfWeek.SATURDAY)
        assertEquals(listOf(0, null), asDay.map { it.dayOffset })
        assertEquals(listOf(2, 0), asDay.map { it.dueOffset })
        assertTrue(Validators.template(dayTemplate, asDay, emptyList()).isEmpty())
        val asWeek = PlanTemplates.changeSpan(asDay, toWeek = true, firstDayOfWeek = DayOfWeek.MONDAY)
        assertEquals(listOf(2, null), asWeek.map { it.dayOffset })
        assertEquals(listOf(4, 2), asWeek.map { it.dueOffset })
        assertTrue(Validators.template(weekTemplate, asWeek, emptyList()).isEmpty())
    }

    @Test
    fun duplicatingAndOrdering() {
        val items = listOf(
            item("بدون ساعت", 1) { copy(sortOrder = 0) },
            item("عصر", 1) { copy(startTime = LocalTime.of(17, 0)) },
            item("صبح", 1) { copy(startTime = LocalTime.of(8, 0)) },
            item("روز اول", 0) { copy(sortOrder = 9) },
            item("مهلت", null) { copy(dueOffset = 1, sortOrder = 1) },
        )
        assertEquals(listOf("روز اول", "صبح", "عصر", "بدون ساعت", "مهلت"), items.sortedWith(PlanTemplates.storedOrder).map { it.title })
        val (copy, copiedItems) = PlanTemplates.duplicate(weekTemplate, items, "مطالعه (کپی)", NOW, newId)
        assertNotEquals(weekTemplate.id, copy.id)
        assertEquals("مطالعه (کپی)", copy.name)
        assertTrue(copy.isWeek)
        assertTrue(copiedItems.all { it.templateId == copy.id })
        assertTrue(copiedItems.map { it.id }.none { id -> items.any { it.id == id } })
        assertEquals(items.map { it.title }, copiedItems.map { it.title })
        assertTrue(PlanTemplates.cleanSubtasks(listOf(" ", "\n")).isEmpty())
    }
}
