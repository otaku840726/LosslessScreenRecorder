package com.lossless.screenrecorder.ui.screens

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AddToHomeScreen
import androidx.compose.material.icons.filled.GraphicEq
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.VolumeOff
import androidx.compose.material.icons.filled.VolumeUp
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.lossless.screenrecorder.R
import com.lossless.screenrecorder.model.AudioSourceMode
import com.lossless.screenrecorder.model.RecorderConfig
import kotlin.math.roundToInt

@Composable
fun AudioAndControlsScreen(
    config: RecorderConfig,
    canDrawOverlays: Boolean,
    onAudioSourceModeChange: (AudioSourceMode) -> Unit,
    onInternalGainChange: (Float) -> Unit,
    onMicGainChange: (Float) -> Unit,
    onEnableNotificationChange: (Boolean) -> Unit,
    onEnableFloatingBallChange: (Boolean) -> Unit,
    onHideFloatingBallOnRecordChange: (Boolean) -> Unit,
    onRequestOverlayPermission: () -> Unit,
    onAddQuickSettingsTileClick: () -> Unit,
    onCountdownSecondsChange: (Int) -> Unit,
    onAutoStopScreenOffChange: (Boolean) -> Unit,
    modifier: Modifier = Modifier
) {
    Column(
        modifier = modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        // Section 1: 4 Audio Source Modes + Dual PCM Mixer Gain Controls
        SectionContainerCard(title = stringResource(R.string.section_audio_source_title)) {
            AudioModeOptionCard(
                title = stringResource(R.string.audio_mode_both),
                subtitle = "雙路 AudioRecord 16-bit PCM 即時混音，可獨立調節內錄與麥克風音量比例",
                icon = Icons.Default.GraphicEq,
                selected = config.audioSourceMode == AudioSourceMode.INTERNAL_AND_MIC,
                onClick = { onAudioSourceModeChange(AudioSourceMode.INTERNAL_AND_MIC) },
                testTag = "audio_mode_both"
            )

            AudioModeOptionCard(
                title = stringResource(R.string.audio_mode_internal),
                subtitle = "直接擷取系統內部數位音訊流，零環境噪音干擾",
                icon = Icons.Default.VolumeUp,
                selected = config.audioSourceMode == AudioSourceMode.INTERNAL_ONLY,
                onClick = { onAudioSourceModeChange(AudioSourceMode.INTERNAL_ONLY) },
                testTag = "audio_mode_internal"
            )

            AudioModeOptionCard(
                title = stringResource(R.string.audio_mode_mic),
                subtitle = "僅錄製麥克風人聲解說 (48kHz / 320kbps AAC-LC)",
                icon = Icons.Default.Mic,
                selected = config.audioSourceMode == AudioSourceMode.MIC_ONLY,
                onClick = { onAudioSourceModeChange(AudioSourceMode.MIC_ONLY) },
                testTag = "audio_mode_mic"
            )

            AudioModeOptionCard(
                title = stringResource(R.string.audio_mode_mute),
                subtitle = "不封裝音訊軌，僅輸出純淨零拷貝視訊流",
                icon = Icons.Default.VolumeOff,
                selected = config.audioSourceMode == AudioSourceMode.MUTE,
                onClick = { onAudioSourceModeChange(AudioSourceMode.MUTE) },
                testTag = "audio_mode_mute"
            )

            // Independent Volume Gain Sliders when Internal or Mic is enabled
            if (config.audioSourceMode.needsInternal) {
                Spacer(modifier = Modifier.height(4.dp))
                GainSliderControl(
                    label = stringResource(R.string.audio_internal_gain_label),
                    gainValue = config.internalAudioGain,
                    onGainChange = onInternalGainChange,
                    testTag = "slider_internal_audio_gain"
                )
            }

            if (config.audioSourceMode.needsMic) {
                Spacer(modifier = Modifier.height(4.dp))
                GainSliderControl(
                    label = stringResource(R.string.audio_mic_gain_label),
                    gainValue = config.micAudioGain,
                    onGainChange = onMicGainChange,
                    testTag = "slider_mic_audio_gain"
                )
            }

            // DRM / Android 10+ AudioPlaybackCapture Note
            Surface(
                color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.55f),
                shape = MaterialTheme.shapes.small,
                modifier = Modifier.fillMaxWidth()
            ) {
                Row(
                    modifier = Modifier.padding(10.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(
                        imageVector = Icons.Default.Info,
                        contentDescription = "Audio Capture Info",
                        tint = MaterialTheme.colorScheme.primary
                    )
                    Text(
                        text = stringResource(R.string.audio_drm_notice),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        }

        // Section 2: Three Control Entry Points (Notification, Floating Ball, Quick Settings Tile)
        SectionContainerCard(title = stringResource(R.string.section_control_entries_title)) {
            // Entry 1: Persistent Notification Controller
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = stringResource(R.string.control_notif_title),
                        style = MaterialTheme.typography.titleMedium,
                        color = MaterialTheme.colorScheme.onSurface,
                        fontWeight = FontWeight.Bold
                    )
                    Text(
                        text = stringResource(R.string.control_notif_desc),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                Switch(
                    checked = config.enableNotificationController,
                    onCheckedChange = onEnableNotificationChange,
                    modifier = Modifier.testTag("switch_notification_controller")
                )
            }

            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)

            // Entry 2: Desktop Floating Overlay Ball
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = stringResource(R.string.control_floating_title),
                        style = MaterialTheme.typography.titleMedium,
                        color = MaterialTheme.colorScheme.onSurface,
                        fontWeight = FontWeight.Bold
                    )
                    Text(
                        text = stringResource(R.string.control_floating_desc),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                Switch(
                    checked = config.enableFloatingBall && canDrawOverlays,
                    onCheckedChange = { wantEnable ->
                        if (wantEnable && !canDrawOverlays) {
                            onRequestOverlayPermission()
                        } else {
                            onEnableFloatingBallChange(wantEnable)
                        }
                    },
                    modifier = Modifier.testTag("switch_floating_ball")
                )
            }

            if (config.enableFloatingBall || !canDrawOverlays) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(start = 12.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = stringResource(R.string.control_floating_hide_on_record),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.weight(1f)
                    )
                    Switch(
                        checked = config.hideFloatingBallWhileRecording,
                        onCheckedChange = onHideFloatingBallOnRecordChange,
                        modifier = Modifier.testTag("switch_hide_floating_ball_on_record")
                    )
                }
            }

            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)

            // Entry 3: Quick Settings Tile
            Column(
                modifier = Modifier.fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Text(
                    text = stringResource(R.string.control_qs_tile_title),
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.onSurface,
                    fontWeight = FontWeight.Bold
                )
                Text(
                    text = stringResource(R.string.control_qs_tile_desc),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                FilledTonalButton(
                    onClick = onAddQuickSettingsTileClick,
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag("add_qs_tile_button")
                ) {
                    Icon(
                        imageVector = Icons.Default.AddToHomeScreen,
                        contentDescription = stringResource(R.string.control_qs_tile_action)
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        text = stringResource(R.string.control_qs_tile_action),
                        style = MaterialTheme.typography.labelLarge
                    )
                }
            }
        }

        // Section 3: Countdown & Screen-Off Auto-Save Protection
        SectionContainerCard(title = stringResource(R.string.section_safety_stop_title)) {
            Text(
                text = stringResource(R.string.countdown_label),
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )

            val countdownOptions = listOf(0, 3, 5)
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                countdownOptions.forEach { secs ->
                    FilterChip(
                        selected = config.countdownSeconds == secs,
                        onClick = { onCountdownSecondsChange(secs) },
                        label = {
                            Text(
                                text = if (secs == 0) "立即開錄 (0s)" else "倒數 $secs 秒",
                                style = MaterialTheme.typography.labelLarge
                            )
                        },
                        modifier = Modifier
                            .weight(1f)
                            .testTag("countdown_chip_$secs")
                    )
                }
            }

            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = stringResource(R.string.auto_stop_screen_off_title),
                        style = MaterialTheme.typography.titleMedium,
                        color = MaterialTheme.colorScheme.onSurface,
                        fontWeight = FontWeight.Bold
                    )
                    Text(
                        text = stringResource(R.string.auto_stop_screen_off_desc),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                Switch(
                    checked = config.autoStopOnScreenOff,
                    onCheckedChange = onAutoStopScreenOffChange,
                    modifier = Modifier.testTag("switch_auto_stop_screen_off")
                )
            }
        }
    }
}

@Composable
private fun AudioModeOptionCard(
    title: String,
    subtitle: String,
    icon: ImageVector,
    selected: Boolean,
    onClick: () -> Unit,
    testTag: String
) {
    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .testTag(testTag),
        color = if (selected) {
            MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.35f)
        } else {
            MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.4f)
        },
        shape = MaterialTheme.shapes.small,
        border = BorderStroke(
            width = 1.dp,
            color = if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outlineVariant
        )
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            RadioButton(selected = selected, onClick = onClick)
            Icon(
                imageVector = icon,
                contentDescription = title,
                tint = if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant
            )
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = title,
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.onSurface
                )
                Text(
                    text = subtitle,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}

@Composable
private fun GainSliderControl(
    label: String,
    gainValue: Float,
    onGainChange: (Float) -> Unit,
    testTag: String
) {
    Column(modifier = Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Text(
                text = label,
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurface
            )
            Text(
                text = "${(gainValue * 100).roundToInt()}%",
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.primary
            )
        }
        Slider(
            value = gainValue,
            onValueChange = onGainChange,
            valueRange = 0.0f..2.0f,
            modifier = Modifier
                .fillMaxWidth()
                .testTag(testTag)
        )
    }
}
