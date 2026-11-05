package com.custom.treadmill.ui.viewmodels

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.custom.treadmill.TreadmillApp
import com.custom.treadmill.data.database.ProgramData
import com.custom.treadmill.data.database.ProgramEntity
import com.custom.treadmill.data.database.WorkoutLogEntity
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

class ProgramViewModel(app: Application) : AndroidViewModel(app) {

    private val repository = TreadmillApp.instance.programRepository

    val programs: StateFlow<List<ProgramEntity>> = repository.programs.stateIn(
        viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList()
    )

    val logs: StateFlow<List<WorkoutLogEntity>> = repository.logs.stateIn(
        viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList()
    )

    fun save(data: ProgramData, id: Long = 0L) {
        viewModelScope.launch { repository.save(repository.toEntity(data, id)) }
    }

    fun delete(entity: ProgramEntity) {
        viewModelScope.launch { repository.delete(entity) }
    }

    fun toData(entity: ProgramEntity): ProgramData = repository.toData(entity)
    fun exportJson(data: ProgramData): String = repository.exportJson(data)
    fun importJson(text: String): ProgramData = repository.importJson(text)

    /** Добавляет демо-тренировки в журнал (без стирания реальных). */
    fun addDemoLogs() {
        viewModelScope.launch { repository.addDemoLogs() }
    }

    /** Очищает журнал. */
    fun clearLogs() {
        viewModelScope.launch { repository.clearLogs() }
    }
}
