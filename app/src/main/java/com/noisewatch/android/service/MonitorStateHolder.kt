package com.noisewatch.android.service

import com.noisewatch.android.model.MonitorSettings
import com.noisewatch.android.model.MonitorState
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

/**
 * Shared in-process state holder connecting NoiseMonitorService and Compose UI.
 */
object MonitorStateHolder {
    private val _state = MutableStateFlow(MonitorState())
    val state: StateFlow<MonitorState> = _state.asStateFlow()

    private val _settings = MutableStateFlow(MonitorSettings())
    val settings: StateFlow<MonitorSettings> = _settings.asStateFlow()

    fun updateState(transform: (MonitorState) -> MonitorState) {
        _state.update(transform)
    }

    fun updateSettings(newSettings: MonitorSettings) {
        _settings.value = newSettings
    }
}
