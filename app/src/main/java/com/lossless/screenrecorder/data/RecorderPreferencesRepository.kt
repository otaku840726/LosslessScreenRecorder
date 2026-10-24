package com.lossless.screenrecorder.data

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.floatPreferencesKey
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.lossless.screenrecorder.model.AudioSourceMode
import com.lossless.screenrecorder.model.BitrateControlMode
import com.lossless.screenrecorder.model.KeyFrameIntervalMode
import com.lossless.screenrecorder.model.OverlayControlStyle
import com.lossless.screenrecorder.model.RecorderConfig
import com.lossless.screenrecorder.model.ResolutionMode
import com.lossless.screenrecorder.model.SidebarTapBehavior
import com.lossless.screenrecorder.model.VideoCodecType
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

private val Context.recorderDataStore: DataStore<Preferences> by preferencesDataStore(
    name = "lossless_recorder_prefs"
)

class RecorderPreferencesRepository(private val context: Context) {

    private object Keys {
        val RESOLUTION_MODE = stringPreferencesKey("resolution_mode")
        val CODEC_TYPE = stringPreferencesKey("codec_type")
        val BITRATE_MBPS = intPreferencesKey("bitrate_mbps")
        val FRAME_RATE = intPreferencesKey("frame_rate")
        val BITRATE_CONTROL_MODE = stringPreferencesKey("bitrate_control_mode")
        val KEY_FRAME_INTERVAL_MODE = stringPreferencesKey("key_frame_interval_mode")
        val ENABLE_10BIT_COLOR = booleanPreferencesKey("enable_10bit_color")
        val AUDIO_SOURCE_MODE = stringPreferencesKey("audio_source_mode")
        val INTERNAL_AUDIO_GAIN = floatPreferencesKey("internal_audio_gain")
        val MIC_AUDIO_GAIN = floatPreferencesKey("mic_audio_gain")
        val COUNTDOWN_SECONDS = intPreferencesKey("countdown_seconds")
        val ENABLE_NOTIFICATION_CTRL = booleanPreferencesKey("enable_notification_ctrl")
        val ENABLE_FLOATING_BALL = booleanPreferencesKey("enable_floating_ball")
        val OVERLAY_CONTROL_STYLE = stringPreferencesKey("overlay_control_style")
        val SIDEBAR_TAP_BEHAVIOR = stringPreferencesKey("sidebar_tap_behavior")
        val HIDE_BALL_ON_RECORD = booleanPreferencesKey("hide_ball_on_record_v2")
        val AUTO_STOP_SCREEN_OFF = booleanPreferencesKey("auto_stop_screen_off")
    }

    private fun mapPrefsToConfig(prefs: Preferences): RecorderConfig {
        return RecorderConfig(
            resolutionMode = prefs[Keys.RESOLUTION_MODE]?.let {
                runCatching { ResolutionMode.valueOf(it) }.getOrNull()
            } ?: ResolutionMode.NATIVE_PHYSICAL,
            codecType = prefs[Keys.CODEC_TYPE]?.let {
                runCatching { VideoCodecType.valueOf(it) }.getOrNull()
            } ?: VideoCodecType.HEVC,
            bitrateMbps = prefs[Keys.BITRATE_MBPS] ?: 80,
            frameRate = prefs[Keys.FRAME_RATE] ?: 60,
            bitrateControlMode = prefs[Keys.BITRATE_CONTROL_MODE]?.let {
                runCatching { BitrateControlMode.valueOf(it) }.getOrNull()
            } ?: BitrateControlMode.CBR,
            keyFrameIntervalMode = prefs[Keys.KEY_FRAME_INTERVAL_MODE]?.let {
                runCatching { KeyFrameIntervalMode.valueOf(it) }.getOrNull()
            } ?: KeyFrameIntervalMode.ALL_I_ZERO,
            enable10BitColor = prefs[Keys.ENABLE_10BIT_COLOR] ?: false,
            audioSourceMode = prefs[Keys.AUDIO_SOURCE_MODE]?.let {
                runCatching { AudioSourceMode.valueOf(it) }.getOrNull()
            } ?: AudioSourceMode.INTERNAL_AND_MIC,
            internalAudioGain = prefs[Keys.INTERNAL_AUDIO_GAIN] ?: 1.0f,
            micAudioGain = prefs[Keys.MIC_AUDIO_GAIN] ?: 1.0f,
            countdownSeconds = prefs[Keys.COUNTDOWN_SECONDS] ?: 3,
            enableNotificationController = prefs[Keys.ENABLE_NOTIFICATION_CTRL] ?: true,
            enableFloatingBall = prefs[Keys.ENABLE_FLOATING_BALL] ?: true,
            overlayControlStyle = prefs[Keys.OVERLAY_CONTROL_STYLE]?.let {
                runCatching { OverlayControlStyle.valueOf(it) }.getOrNull()
            } ?: OverlayControlStyle.EDGE_SIDEBAR,
            sidebarTapBehavior = prefs[Keys.SIDEBAR_TAP_BEHAVIOR]?.let {
                runCatching { SidebarTapBehavior.valueOf(it) }.getOrNull()
            } ?: SidebarTapBehavior.EXPAND_PANEL,
            hideFloatingBallWhileRecording = prefs[Keys.HIDE_BALL_ON_RECORD] ?: false,
            autoStopOnScreenOff = prefs[Keys.AUTO_STOP_SCREEN_OFF] ?: true
        )
    }

    val configFlow: Flow<RecorderConfig> = context.recorderDataStore.data.map { prefs ->
        mapPrefsToConfig(prefs)
    }

    suspend fun updateConfig(transform: (RecorderConfig) -> RecorderConfig) {
        context.recorderDataStore.edit { prefs ->
            val current = mapPrefsToConfig(prefs)
            val updated = transform(current)
            prefs[Keys.RESOLUTION_MODE] = updated.resolutionMode.name
            prefs[Keys.CODEC_TYPE] = updated.codecType.name
            prefs[Keys.BITRATE_MBPS] = updated.bitrateMbps
            prefs[Keys.FRAME_RATE] = updated.frameRate
            prefs[Keys.BITRATE_CONTROL_MODE] = updated.bitrateControlMode.name
            prefs[Keys.KEY_FRAME_INTERVAL_MODE] = updated.keyFrameIntervalMode.name
            prefs[Keys.ENABLE_10BIT_COLOR] = updated.enable10BitColor
            prefs[Keys.AUDIO_SOURCE_MODE] = updated.audioSourceMode.name
            prefs[Keys.INTERNAL_AUDIO_GAIN] = updated.internalAudioGain
            prefs[Keys.MIC_AUDIO_GAIN] = updated.micAudioGain
            prefs[Keys.COUNTDOWN_SECONDS] = updated.countdownSeconds
            prefs[Keys.ENABLE_NOTIFICATION_CTRL] = updated.enableNotificationController
            prefs[Keys.ENABLE_FLOATING_BALL] = updated.enableFloatingBall
            prefs[Keys.OVERLAY_CONTROL_STYLE] = updated.overlayControlStyle.name
            prefs[Keys.SIDEBAR_TAP_BEHAVIOR] = updated.sidebarTapBehavior.name
            prefs[Keys.HIDE_BALL_ON_RECORD] = updated.hideFloatingBallWhileRecording
            prefs[Keys.AUTO_STOP_SCREEN_OFF] = updated.autoStopOnScreenOff
        }
    }
}
