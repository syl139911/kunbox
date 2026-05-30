package com.kunk.singbox.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.kunk.singbox.repository.BugLogEntry
import com.kunk.singbox.repository.BugLogRepository
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn

class BugLogViewModel : ViewModel() {
    private val repository = BugLogRepository.getInstance()

    val bugLogs: StateFlow<List<BugLogEntry>> = repository.bugLogs
        .stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())

    fun clearLogs() {
        repository.clearBugLogs()
    }

    fun getLogsForExport(): String {
        return repository.getBugLogsAsText()
    }
}
