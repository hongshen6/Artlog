package com.artlog.app.ui.screens

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.artlog.app.data.BackupManager
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/**
 * Backup / restore / integrity-check UI state (round 4).
 *
 * All heavy work runs on Dispatchers.IO inside [BackupManager]; this
 * ViewModel only carries the working flag, the last status message, and
 * the latest integrity report. A failure is always shown as an error
 * message — never a silent success.
 */
class BackupViewModel(
    private val backupManager: BackupManager
) : ViewModel() {

    data class Status(val text: String, val isError: Boolean)

    private val _isWorking = MutableStateFlow(false)
    val isWorking: StateFlow<Boolean> = _isWorking.asStateFlow()

    private val _status = MutableStateFlow<Status?>(null)
    val status: StateFlow<Status?> = _status.asStateFlow()

    private val _integrityReport = MutableStateFlow<BackupManager.IntegrityReport?>(null)
    val integrityReport: StateFlow<BackupManager.IntegrityReport?> =
        _integrityReport.asStateFlow()

    fun backup() {
        if (_isWorking.value) return
        viewModelScope.launch {
            _isWorking.value = true
            _status.value = null
            try {
                when (val result = backupManager.createBackup()) {
                    is BackupManager.BackupOutcome.Success -> {
                        val degraded = if (result.coversDegraded > 0) {
                            "\n其中 ${result.coversDegraded} 首音乐的封面文件缺失，" +
                                "已跳过封面（封面可从音频重新提取，不影响备份，" +
                                "恢复后该曲目显示占位图）。"
                        } else {
                            ""
                        }
                        _status.value = Status(
                            text = "备份成功：${result.fileName}\n" +
                                "位置：${result.location}\n" +
                                "共 ${result.artworkCount} 幅作品、${result.imageCount} 张图片，" +
                                "已按清单校验通过。$degraded",
                            isError = false
                        )
                    }
                    is BackupManager.BackupOutcome.Failure -> {
                        _status.value = Status(result.message, isError = true)
                    }
                }
            } finally {
                _isWorking.value = false
            }
        }
    }

    fun restore(uri: android.net.Uri) {
        if (_isWorking.value) return
        viewModelScope.launch {
            _isWorking.value = true
            _status.value = null
            try {
                when (val result = backupManager.restoreBackup(uri)) {
                    is BackupManager.RestoreOutcome.Success -> {
                        val skipped = if (result.skippedUnreferencedFiles > 0) {
                            "\n备份中有 ${result.skippedUnreferencedFiles} 个未被数据库引用的文件，已忽略。"
                        } else {
                            ""
                        }
                        val music = if (result.musicTrackCount > 0) {
                            "、${result.musicTrackCount} 首音乐"
                        } else {
                            ""
                        }
                        val background = if (result.backgroundReset) {
                            "\n备份中的背景图片缺失，背景已恢复为默认纯色。"
                        } else {
                            ""
                        }
                        val settings = if (result.settingsWarning != null) {
                            "\n${result.settingsWarning}"
                        } else {
                            ""
                        }
                        _status.value = Status(
                            text = "恢复成功：${result.artworkCount} 幅作品、" +
                                "${result.imageCount} 张图片、${result.tagCount} 个标签、" +
                                "${result.journalEntryCount} 篇手记、" +
                                "${result.favoriteCount} 个灵感收藏$music。$skipped$background$settings",
                            isError = false
                        )
                    }
                    is BackupManager.RestoreOutcome.Failure -> {
                        _status.value = Status(result.message, isError = true)
                    }
                }
            } finally {
                _isWorking.value = false
            }
        }
    }

    fun checkIntegrity() {
        if (_isWorking.value) return
        viewModelScope.launch {
            _isWorking.value = true
            try {
                val report = backupManager.checkIntegrity()
                _integrityReport.value = report
                _status.value = Status(report.summary(), isError = false)
            } finally {
                _isWorking.value = false
            }
        }
    }

    fun dismissIntegrityReport() {
        _integrityReport.value = null
    }

    fun clearStatus() {
        _status.value = null
    }
}
