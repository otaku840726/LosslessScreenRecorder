package com.lossless.screenrecorder.ui

import android.app.Application
import android.content.Context
import android.provider.Settings
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.lossless.screenrecorder.data.RecorderPreferencesRepository
import com.lossless.screenrecorder.data.RecordingsRepository
import com.lossless.screenrecorder.domain.DeviceCapabilityDetector
import com.lossless.screenrecorder.model.AlignedResolution
import com.lossless.screenrecorder.model.AudioSourceMode
import com.lossless.screenrecorder.model.BitrateControlMode
import com.lossless.screenrecorder.model.HardwareCapabilities
import com.lossless.screenrecorder.model.KeyFrameIntervalMode
import com.lossless.screenrecorder.model.RecordedVideoItem
import com.lossless.screenrecorder.model.RecorderConfig
import com.lossless.screenrecorder.model.RecordingSessionState
import com.lossless.screenrecorder.model.ResolutionMode
import com.lossless.screenrecorder.model.VideoCodecType
import com.lossless.screenrecorder.service.RecordingStateHolder
import com.lossless.screenrecorder.service.ScreenRecordService
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

data class RecorderUiState(
    val capabilities: HardwareCapabilities,
    val config: RecorderConfig,
    val alignedResolution: AlignedResolution,
    val sessionState: RecordingSessionState,
    val recordings: List<RecordedVideoItem>,
    val isLoadingRecordings: Boolean,
    val canDrawOverlays: Boolean,
    val statusBannerMessage: String? = null
)

class RecorderViewModel(application: Application) : AndroidViewModel(application) {

    private val appContext: Context = application.applicationContext
    private val capabilityDetector = DeviceCapabilityDetector(appContext)
    private val preferencesRepository = RecorderPreferencesRepository(appContext)
    private val recordingsRepository = RecordingsRepository(appContext)

    private val hardwareCapabilities: HardwareCapabilities = capabilityDetector.detectCapabilities()

    private val _recordings = MutableStateFlow<List<RecordedVideoItem>>(emptyList())
    private val _isLoadingRecordings = MutableStateFlow(false)
    private val _canDrawOverlays = MutableStateFlow(Settings.canDrawOverlays(appContext))
    private val _bannerMessage = MutableStateFlow<String?>(null)

    private val _selectedInspectionVideo = MutableStateFlow<RecordedVideoItem?>(null)
    val selectedInspectionVideo: StateFlow<RecordedVideoItem?> = _selectedInspectionVideo.asStateFlow()

    val uiState: StateFlow<RecorderUiState> = combine(
        preferencesRepository.configFlow,
        RecordingStateHolder.sessionState,
        _recordings,
        _isLoadingRecordings,
        combine(_canDrawOverlays, _bannerMessage) { overlay, msg -> overlay to msg }
    ) { config, session, videos, isLoading, (overlayOk, banner) ->
        val aligned = capabilityDetector.calculateAlignedResolution(hardwareCapabilities, config)
        RecorderUiState(
            capabilities = hardwareCapabilities,
            config = config,
            alignedResolution = aligned,
            sessionState = session,
            recordings = videos,
            isLoadingRecordings = isLoading,
            canDrawOverlays = overlayOk,
            statusBannerMessage = banner
        )
    }.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5000L),
        initialValue = RecorderUiState(
            capabilities = hardwareCapabilities,
            config = RecorderConfig(),
            alignedResolution = capabilityDetector.calculateAlignedResolution(
                hardwareCapabilities,
                RecorderConfig()
            ),
            sessionState = RecordingSessionState.Idle,
            recordings = emptyList(),
            isLoadingRecordings = false,
            canDrawOverlays = Settings.canDrawOverlays(appContext)
        )
    )

    init {
        refreshRecordings()
        viewModelScope.launch {
            RecordingStateHolder.recordingsRefreshTrigger.collect { trigger ->
                if (trigger > 0L) {
                    refreshRecordings()
                }
            }
        }
    }

    fun refreshOverlayPermissionState() {
        _canDrawOverlays.value = Settings.canDrawOverlays(appContext)
    }

    fun refreshRecordings() {
        viewModelScope.launch {
            _isLoadingRecordings.value = true
            _recordings.value = recordingsRepository.loadRecordings()
            _isLoadingRecordings.value = false
        }
    }

    fun selectVideoForInspection(video: RecordedVideoItem?) {
        _selectedInspectionVideo.value = video
    }

    fun deleteVideo(video: RecordedVideoItem) {
        viewModelScope.launch {
            val deleted = recordingsRepository.deleteRecording(video.uriString)
            if (deleted) {
                showBannerMessage("已刪除錄影檔案：${video.displayName}")
            }
            if (_selectedInspectionVideo.value?.id == video.id) {
                _selectedInspectionVideo.value = null
            }
            refreshRecordings()
        }
    }

    fun showBannerMessage(message: String?) {
        _bannerMessage.value = message
    }

    fun setResolutionMode(mode: ResolutionMode) {
        viewModelScope.launch {
            preferencesRepository.updateConfig { it.copy(resolutionMode = mode) }
        }
    }

    fun setCodecType(codec: VideoCodecType) {
        viewModelScope.launch {
            val maxMbps = hardwareCapabilities.capabilityFor(codec).maxBitrateMbps
            preferencesRepository.updateConfig { current ->
                current.copy(
                    codecType = codec,
                    bitrateMbps = current.bitrateMbps.coerceAtMost(maxMbps)
                )
            }
        }
    }

    fun setBitrateMbps(mbps: Int) {
        viewModelScope.launch {
            preferencesRepository.updateConfig { it.copy(bitrateMbps = mbps) }
        }
    }

    fun setFrameRate(fps: Int) {
        viewModelScope.launch {
            preferencesRepository.updateConfig { it.copy(frameRate = fps) }
        }
    }

    fun setBitrateControlMode(mode: BitrateControlMode) {
        viewModelScope.launch {
            preferencesRepository.updateConfig { it.copy(bitrateControlMode = mode) }
        }
    }

    fun setKeyFrameIntervalMode(mode: KeyFrameIntervalMode) {
        viewModelScope.launch {
            preferencesRepository.updateConfig { it.copy(keyFrameIntervalMode = mode) }
        }
    }

    fun setEnable10BitColor(enabled: Boolean) {
        viewModelScope.launch {
            preferencesRepository.updateConfig { it.copy(enable10BitColor = enabled) }
        }
    }

    fun setAudioSourceMode(mode: AudioSourceMode) {
        viewModelScope.launch {
            preferencesRepository.updateConfig { it.copy(audioSourceMode = mode) }
        }
    }

    fun setInternalAudioGain(gain: Float) {
        viewModelScope.launch {
            preferencesRepository.updateConfig { current ->
                val updated = current.copy(internalAudioGain = gain)
                RecordingStateHolder.onLiveGainChanged?.invoke(
                    updated.internalAudioGain,
                    updated.micAudioGain
                )
                updated
            }
        }
    }

    fun setMicAudioGain(gain: Float) {
        viewModelScope.launch {
            preferencesRepository.updateConfig { current ->
                val updated = current.copy(micAudioGain = gain)
                RecordingStateHolder.onLiveGainChanged?.invoke(
                    updated.internalAudioGain,
                    updated.micAudioGain
                )
                updated
            }
        }
    }

    fun setCountdownSeconds(seconds: Int) {
        viewModelScope.launch {
            preferencesRepository.updateConfig { it.copy(countdownSeconds = seconds) }
        }
    }

    fun setEnableNotificationController(enabled: Boolean) {
        viewModelScope.launch {
            preferencesRepository.updateConfig { it.copy(enableNotificationController = enabled) }
        }
    }

    fun setEnableFloatingBall(enabled: Boolean) {
        viewModelScope.launch {
            val currentHide = uiState.value.config.hideFloatingBallWhileRecording
            preferencesRepository.updateConfig { it.copy(enableFloatingBall = enabled) }
            if (!enabled || Settings.canDrawOverlays(appContext)) {
                ScreenRecordService.syncFloatingBall(appContext, enabled, currentHide)
            }
        }
    }

    fun setHideFloatingBallWhileRecording(hide: Boolean) {
        viewModelScope.launch {
            val enabled = uiState.value.config.enableFloatingBall
            preferencesRepository.updateConfig { it.copy(hideFloatingBallWhileRecording = hide) }
            if (enabled && Settings.canDrawOverlays(appContext)) {
                ScreenRecordService.syncFloatingBall(appContext, true, hide)
            }
        }
    }

    fun setAutoStopOnScreenOff(autoStop: Boolean) {
        viewModelScope.launch {
            preferencesRepository.updateConfig { it.copy(autoStopOnScreenOff = autoStop) }
        }
    }
}
