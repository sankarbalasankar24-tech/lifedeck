package com.lifedeck.app

import com.lifedeck.app.data.model.EntryType
import com.lifedeck.app.data.model.Priority
import com.lifedeck.app.data.model.RecurrenceFreq
import com.lifedeck.app.domain.CaptureParser
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate

class CaptureParserTest {

    private fun parse(text: String) = CaptureParser.parse(text)

    @Test
    fun `shopping sentence classifies as shopping`() {
        val result = parse("buy milk tomorrow at 6pm")
        assertEquals(EntryType.SHOPPING, result.type)
        assertNotNull(result.dueAt)
        assertEquals(LocalDate.now().plusDays(1), result.dueAt!!.let {
            java.time.Instant.ofEpochMilli(it).atZone(java.time.ZoneId.systemDefault()).toLocalDate()
        })
    }

    @Test
    fun `time is parsed to evening`() {
        val result = parse("water plants at 6pm")
        val ldt = java.time.Instant.ofEpochMilli(result.scheduledAt!!)
            .atZone(java.time.ZoneId.systemDefault()).toLocalDateTime()
        assertEquals(18, ldt.hour)
        assertEquals(0, ldt.minute)
    }

    @Test
    fun `appointment words classify as appointment`() {
        assertEquals(EntryType.APPOINTMENT, parse("meeting with HR at 12").type)
        assertEquals(EntryType.APPOINTMENT, parse("dentist appointment next week").type)
    }

    @Test
    fun `idea words without date stay in inbox`() {
        val result = parse("idea: app to track chores")
        assertEquals(EntryType.IDEA, result.type)
        assertNull(result.dueAt)
    }

    @Test
    fun `project words classify as project step`() {
        assertEquals(EntryType.PROJECT_STEP, parse("research solar panel specs").type)
    }

    @Test
    fun `goal words classify as goal step`() {
        assertEquals(EntryType.GOAL_STEP, parse("goal: run a marathon").type)
    }

    @Test
    fun `urgent keyword raises priority`() {
        assertEquals(Priority.URGENT, parse("urgent: file taxes asap").priority)
        assertEquals(Priority.HIGH, parse("important call to HR").priority)
    }

    @Test
    fun `hashtag becomes category hint`() {
        val result = parse("fix leaking tap #home")
        assertEquals("home", result.categoryHint)
        assertTrue(result.title.contains("Fix leaking tap", ignoreCase = true))
    }

    @Test
    fun `at-context becomes context hint`() {
        val result = parse("reply to HR @lunch")
        assertEquals("lunch", result.contextHint)
    }

    @Test
    fun `recurrence expressions parse`() {
        assertEquals(RecurrenceFreq.DAILY, parse("water plants every day").recurrence?.freq)
        assertEquals(RecurrenceFreq.WEEKLY, parse("invoice client every monday").recurrence?.freq)
        assertEquals(RecurrenceFreq.WEEKLY, parse("gym weekly").recurrence?.freq)
        assertEquals(RecurrenceFreq.MONTHLY, parse("pay rent monthly").recurrence?.freq)
        assertEquals(RecurrenceFreq.WEEKDAYS, parse("standup every weekday").recurrence?.freq)
        assertEquals(RecurrenceFreq.NONE, parse("one off task").recurrence?.freq ?: RecurrenceFreq.NONE)
    }

    @Test
    fun `estimate parses minutes and hours`() {
        assertEquals(45, parse("deep work for 45 min").estimateMinutes)
        assertEquals(120, parse("workshop 2h").estimateMinutes)
    }

    @Test
    fun `relative dates parse`() {
        val tomorrow = parse("call mom tomorrow").dueAt
        assertNotNull(tomorrow)
        val nextWeek = parse("submit form next week").dueAt
        assertNotNull(nextWeek)
        val inThree = parse("renew license in 3 days").dueAt
        assertNotNull(inThree)
    }

    @Test
    fun `weekday resolves forward`() {
        val result = parse("team sync on friday")
        val date = java.time.Instant.ofEpochMilli(result.dueAt!!)
            .atZone(java.time.ZoneId.systemDefault()).toLocalDate()
        assertTrue(date.dayOfWeek.value in 1..7)
        assertTrue(!date.isBefore(LocalDate.now()))
    }

    @Test
    fun `title is cleaned of scheduling words`() {
        val result = parse("tomorrow 12:00 call the bank")
        assertFalse(result.title.contains("tomorrow", ignoreCase = true))
        assertTrue(result.title.contains("bank", ignoreCase = true))
    }

    @Test
    fun `toEntry sends dated captures to active and undated to inbox`() {
        val dated = CaptureParser.toEntry(CaptureParser.parse("buy milk tomorrow"))
        assertEquals(com.lifedeck.app.data.model.EntryStatus.ACTIVE, dated.status)
        val undated = CaptureParser.toEntry(CaptureParser.parse("someday learn piano"))
        assertEquals(com.lifedeck.app.data.model.EntryStatus.INBOX, undated.status)
    }

    private fun assertFalse(condition: Boolean) {
        org.junit.Assert.assertFalse(condition)
    }
}
