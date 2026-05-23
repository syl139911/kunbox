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

        repository.addLog("UDP is not supported by outbound: PROXY")
        repository.addLog("UDP is not supported by outbound: PROXY")
        repository.addLog("UDP is not supported by outbound: PROXY")

        val logs = repository.getFilteredLogs()
        assertEquals(1, logs.size)
        assertEquals(3, logs.first().count)
        assertTrue(logs.first().message.contains("UDP is not supported by outbound: PROXY"))
    }

    @Test
    fun nonConsecutiveDuplicatesAreNotMerged() {
        repository.clearLogs()

        repository.addLog("UDP is not supported by outbound: PROXY")
        repository.addLog("some other message")
        repository.addLog("UDP is not supported by outbound: PROXY")

        val logs = repository.getFilteredLogs()
        assertEquals(3, logs.size)
        assertEquals(1, logs[0].count)
        assertEquals(1, logs[1].count)
        assertEquals(1, logs[2].count)
    }
}
