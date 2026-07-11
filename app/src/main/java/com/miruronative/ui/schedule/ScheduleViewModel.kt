package com.miruronative.ui.schedule

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.miruronative.data.AppGraph
import com.miruronative.data.model.AiringSchedule
import com.miruronative.ui.UiState
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

class ScheduleViewModel : ViewModel() {
    private val repo = AppGraph.repository

    private val _state = MutableStateFlow<UiState<List<AiringSchedule>>>(UiState.Loading)
    val state = _state.asStateFlow()

    var selectedDay by mutableIntStateOf(0) // -1 yesterday, 0 today, +1 tomorrow
        private set

    init { load() }

    fun selectDay(offset: Int) {
        if (offset == selectedDay) return
        selectedDay = offset
        load()
    }

    fun load() {
        viewModelScope.launch {
            _state.value = UiState.Loading
            try {
                _state.value = UiState.Success(repo.schedule(selectedDay))
            } catch (e: Exception) {
                _state.value = UiState.Error(e.message ?: "Failed to load schedule")
            }
        }
    }
}
