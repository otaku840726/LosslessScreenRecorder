package com.lossless.screenrecorder.service

import com.lossless.screenrecorder.model.RecordingSessionState
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

object RecordingStateHolder {
    private val _sessionState = MutableStateFlow<RecordingSessionState>(RecordingSessionState.Idle)
    val sessionState: StateFlow<RecordingSessionState> = _sessionState.asStateFlow()

    private val _floatingBallShowing = MutableStateFlow(false)
    val floatingBallShowing: StateFlow<Boolean> = _floatingBallShowing.asStateFlow()

    private val _recordingsRefreshTrigger = MutableStateFlow(0L)
    val recordingsRefreshTrigger: StateFlow<Long> = _recordingsRefreshTrigger.asStateFlow()

    var onLiveGainChanged: ((internalGain: Float, micGain: Float) -> Unit)? = null

    fun updateState(newState: RecordingSessionState) {
        _sessionState.value = newState
    }

    fun setFloatingBallShowing(showing: Boolean) {
        _floatingBallShowing.value = showing
    }

    fun notifyRecordingSaved() {
        _recordingsRefreshTrigger.value = System.currentTimeMillis()
    }
}
