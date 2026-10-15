package com.lifedeck.app

import com.lifedeck.app.data.model.Recurrence
import com.lifedeck.app.data.model.RecurrenceFreq
import com.lifedeck.app.domain.RecurrenceEngine
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate
import java.time.ZoneId

class RecurrenceEngineTest {

    private fun at(date: LocalDate): Long =
        date.atStartOfDay(ZoneId.systemDefault()).toInstant().toEpochMilli()

    private fun dateOf(millis: Long): LocalDate =
        java.time.Instant.ofEpochMilli(millis).atZone(ZoneId.systemDefault()).toLocalDate()

    @Test
    fun `daily advances by one day`() {
        val start = LocalDate.of(2026, 10, 15)
        val next = RecurrenceEngine.nextOccurrence(at(start), Recurrence(RecurrenceFreq.DAILY))
        assertNotNull(next)
        assertEquals(LocalDate.of(2026, 10, 16), dateOf(next!!))
    }

    @Test
    fun `daily with interval advances by n days`() {
        val start = LocalDate.of(2026, 10, 15)
        val next = RecurrenceEngine.nextOccurrence(at(start), Recurrence(RecurrenceFreq.DAILY, interval = 3))
        assertEquals(LocalDate.of(2026, 10, 18), dateOf(next!!))
    }

    @Test
    fun `weekly advances by a week`() {
        val start = LocalDate.of(2026, 10, 15)
        val next = RecurrenceEngine.nextOccurrence(at(start), Recurrence(RecurrenceFreq.WEEKLY))
        assertEquals(LocalDate.of(2026, 10, 22), dateOf(next!!))
    }

    @Test
    fun `monthly keeps day of month`() {
        val start = LocalDate.of(2026, 1, 31)
        val next = RecurrenceEngine.nextOccurrence(at(start), Recurrence(RecurrenceFreq.MONTHLY))
        assertNotNull(next)
        // February has no 31st → clamps to month end
        assertEquals(LocalDate.of(2026, 2, 28), dateOf(next!!))
    }

    @Test
    fun `yearly advances a year`() {
        val start = LocalDate.of(2026, 6, 1)
        val next = RecurrenceEngine.nextOccurrence(at(start), Recurrence(RecurrenceFreq.YEARLY))
        assertEquals(LocalDate.of(2027, 6, 1), dateOf(next!!))
    }

    @Test
    fun `weekdays skips the weekend`() {
        val friday = LocalDate.of(2026, 10, 16) // a Friday
        val next = RecurrenceEngine.nextOccurrence(at(friday), Recurrence(RecurrenceFreq.WEEKDAYS))
        assertEquals(LocalDate.of(2026, 10, 19), dateOf(next!!)) // Monday
    }

    @Test
    fun `custom days picks the next matching day`() {
        val start = LocalDate.of(2026, 10, 15) // Thursday
        val next = RecurrenceEngine.nextOccurrence(
            at(start),
            Recurrence(RecurrenceFreq.CUSTOM_DAYS, daysOfWeek = listOf(1, 3, 5)), // Mon, Wed, Fri
        )
        assertNotNull(next)
        assertTrue(dateOf(next!!).dayOfWeek.value in listOf(1, 3, 5))
        assertTrue(dateOf(next).isAfter(start))
    }

    @Test
    fun `occurrence limit ends the series`() {
        val rule = Recurrence(RecurrenceFreq.DAILY, maxOccurrences = 2, occurrencesDone = 1)
        val next = RecurrenceEngine.nextOccurrence(at(LocalDate.of(2026, 10, 15)), rule)
        assertNull(next) // 1 done + 1 next == max → then stop (done+1 >= max)
    }

    @Test
    fun `end date stops the series`() {
        val rule = Recurrence(RecurrenceFreq.DAILY, endAt = at(LocalDate.of(2026, 10, 16)))
        val next = RecurrenceEngine.nextOccurrence(at(LocalDate.of(2026, 10, 16)), rule)
        assertNull(next) // next would be 17 Oct > end date
    }

    @Test
    fun `inactive rule returns null`() {
        assertNull(RecurrenceEngine.nextOccurrence(at(LocalDate.now()), Recurrence(RecurrenceFreq.NONE)))
    }

    @Test
    fun `advanced increments the counter`() {
        val rule = Recurrence(RecurrenceFreq.DAILY, occurrencesDone = 3)
        assertEquals(4, RecurrenceEngine.advanced(rule).occurrencesDone)
    }

    @Test
    fun `labels are human friendly`() {
        assertEquals("Every day", RecurrenceEngine.label(Recurrence(RecurrenceFreq.DAILY)))
        assertEquals("Every 2 weeks", RecurrenceEngine.label(Recurrence(RecurrenceFreq.WEEKLY, interval = 2)))
        assertEquals("Does not repeat", RecurrenceEngine.label(null))
        assertEquals("Every Mon, Wed", RecurrenceEngine.label(Recurrence(RecurrenceFreq.CUSTOM_DAYS, daysOfWeek = listOf(1, 3))))
    }
}
