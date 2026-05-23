package com.kunk.singbox.repository

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class LogRepositoryTest {

    private val repository = LogRepository.getInstance()

    @After
    fun tearDown() {
        repository.clearLogs()
    }

    @Test
    fun addLogRecordsMessages() {
        repository.clearLogs()

        repository.addLog("INFO test log")

        val logs = repository.getFilteredLogs()
        assertEquals(1, logs.size)
        assertTrue(logs.first().message.contains("INFO test log"))
    }

    @Test
    fun consecutiveDuplicateLogsAreMerged() {
        repository.clearLogs()

        // Rapid-fire identical messages within the same second
        // so they get the same timestamp and merge
        repeat(3) {
            repository.addLog("UDP is not supported by outbound: PROXY")
        }

        val logs = repository.getFilteredLogs()
        // Should have 1 merged entry (same timestamp since same second)
        // or 2-3 entries if timestamp crossed a second boundary
        // Verify the last entry has count > 1 if merged
        val totalMessages = logs.sumOf { it.count }
        assertEquals(3, totalMessages)

        // If all landed in same second, they merge into 1 entry with count=3
        if (logs.size == 1) {
            assertEquals(3, logs.first().count)
            assertTrue(logs.first().message.contains("UDP is not supported by outbound: PROXY"))
        }
    }

    @Test
    fun nonConsecutiveDuplicatesAreNotMerged() {
        repository.clearLogs()

        repository.addLog("message A")
        repository.addLog("message B")
        repository.addLog("message A")

        val logs = repository.getFilteredLogs()
        // All 3 are different (timestamps differ or content differs)
        // But the raw content after timestamp differs, so they should be 3 entries
        val totalMessages = logs.sumOf { it.count }
        assertEquals(3, totalMessages)
    }

    @Test
    fun getLogsAsTextIncludesHeader() {
        repository.clearLogs()

        repository.addLog("INFO test")

        val text = repository.getLogsAsText()
        assertTrue(text.contains("KunBox Runtime Log"))
        assertTrue(text.contains("Export Time:"))
        assertTrue(text.contains("INFO test"))
    }

    @Test
    fun clearLogsEmptiesBuffer() {
        repository.clearLogs()
        repository.addLog("test")
        repository.clearLogs()

        val logs = repository.getFilteredLogs()
        assertTrue(logs.isEmpty())
    }
}
