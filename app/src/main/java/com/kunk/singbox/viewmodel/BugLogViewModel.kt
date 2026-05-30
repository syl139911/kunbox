package com.kunk.singbox.viewmodel

import androidx.lifecycle.ViewModel
import com.kunk.singbox.repository.BugLogEntry
import com.kunk.singbox.repository.BugLogRepository
import kotlinx.coroutines.flow.StateFlow

class BugLogViewModel : ViewModel() {
    private val repository = BugLogRepository.getInstance()

    // 直接暴露 Repository 的 StateFlow，不用 stateIn 包装
    // stateIn(WhileSubscribed) 在清空后上游重新发射时可能丢失更新
    val bugLogs: StateFlow<List<BugLogEntry>> = repository.bugLogs

    fun clearLogs() {
        repository.clearBugLogs()
    }

    fun getLogsForExport(): String {
        return repository.getBugLogsAsText()
    }
}
