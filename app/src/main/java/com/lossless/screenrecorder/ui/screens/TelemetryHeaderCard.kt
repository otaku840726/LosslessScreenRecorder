package com.lossless.screenrecorder.ui.screens

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.FiberManualRecord
import androidx.compose.material.icons.filled.Memory
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.lossless.screenrecorder.R
import com.lossless.screenrecorder.model.AlignedResolution
import com.lossless.screenrecorder.model.HardwareCapabilities
import com.lossless.screenrecorder.model.KeyFrameIntervalMode
import com.lossless.screenrecorder.model.RecorderConfig
import com.lossless.screenrecorder.model.RecordingSessionState
import java.util.Locale

@Composable
fun TelemetryHeaderCard(
    capabilities: HardwareCapabilities,
    config: RecorderConfig,
    alignedResolution: AlignedResolution,
    sessionState: RecordingSessionState,
    onStartRecordingClick: () -> Unit,
    onPauseRecordingClick: () -> Unit,
    onResumeRecordingClick: () -> Unit,
    onStopRecordingClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    val activeCodecCap = capabilities.capabilityFor(config.codecType)
    val isRecordingOrCountdown = sessionState is RecordingSessionState.Recording ||
        sessionState is RecordingSessionState.Countdown

    val borderStrokeColor by animateColorAsState(
        targetValue = when (sessionState) {
            is RecordingSessionState.Recording ->
                if (sessionState.isPaused) MaterialTheme.colorScheme.tertiary
                else MaterialTheme.colorScheme.secondary
            is RecordingSessionState.Countdown -> MaterialTheme.colorScheme.tertiary
            else -> MaterialTheme.colorScheme.primary.copy(alpha = 0.45f)
        },
        label = "telemetry_border_color"
    )

    val infiniteTransition = rememberInfiniteTransition(label = "rec_pulse")
    val pulseAlpha by infiniteTransition.animateFloat(
        initialValue = 1.0f,
        targetValue = 0.25f,
        animationSpec = infiniteRepeatable(
            animation = tween(700),
            repeatMode = RepeatMode.Reverse
        ),
        label = "rec_dot_alpha"
    )

    Card(
        modifier = modifier
            .fillMaxWidth()
            .testTag("telemetry_header_card"),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surface
        ),
        border = BorderStroke(1.5.dp, borderStrokeColor),
        shape = MaterialTheme.shapes.large
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            // Top Status Row: Signal Lamp + Device SoC Badge
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    val lampColor = when (sessionState) {
                        is RecordingSessionState.Recording ->
                            if (sessionState.isPaused) MaterialTheme.colorScheme.tertiary
                            else MaterialTheme.colorScheme.secondary
                        is RecordingSessionState.Countdown -> MaterialTheme.colorScheme.tertiary
                        else -> MaterialTheme.colorScheme.primary
                    }
                    Box(
                        modifier = Modifier
                            .size(10.dp)
                            .alpha(
                                if (sessionState is RecordingSessionState.Recording && !sessionState.isPaused)
                                    pulseAlpha else 1f
                            )
                            .clip(CircleShape)
                            .background(lampColor)
                    )
                    Text(
                        text = when (sessionState) {
                            is RecordingSessionState.Recording -> {
                                val timeStr = formatDurationMillis(sessionState.elapsedMillis)
                                val sizeStr = String.format(
                                    Locale.US,
                                    "%.1f MB",
                                    sessionState.bytesWritten / (1024f * 1024f)
                                )
                                if (sessionState.isPaused) "PAUSED · $timeStr · $sizeStr"
                                else "REC · $timeStr · $sizeStr"
                            }
                            is RecordingSessionState.Countdown ->
                                "COUNTDOWN · ${sessionState.remainingSeconds}s 後開錄"
                            is RecordingSessionState.Finalizing ->
                                "SAVING · 正在寫入 MP4 索引..."
                            is RecordingSessionState.Completed ->
                                "SAVED · 已儲存 ${sessionState.fileName}"
                            is RecordingSessionState.Error ->
                                "ERROR · ${sessionState.message}"
                            RecordingSessionState.Idle ->
                                stringResource(R.string.status_idle)
                        },
                        style = MaterialTheme.typography.labelLarge,
                        color = lampColor,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }

                Surface(
                    color = MaterialTheme.colorScheme.surfaceVariant,
                    shape = MaterialTheme.shapes.extraSmall
                ) {
                    Row(
                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(4.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Default.Memory,
                            contentDescription = "SoC Hardware",
                            modifier = Modifier.size(14.dp),
                            tint = MaterialTheme.colorScheme.primary
                        )
                        Text(
                            text = capabilities.deviceModel,
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                }
            }

            // 2x2 Telemetry Metric Grid
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                TelemetryMetricBox(
                    label = stringResource(R.string.telemetry_native_panel),
                    value = "${capabilities.nativePhysicalWidth} × ${capabilities.nativePhysicalHeight}",
                    subtext = "${capabilities.maxRefreshRateHz}Hz · ${capabilities.densityDpi} DPI",
                    highlight = false,
                    modifier = Modifier.weight(1f)
                )
                TelemetryMetricBox(
                    label = stringResource(R.string.telemetry_aligned_output),
                    value = alignedResolution.displayText,
                    subtext = stringResource(
                        R.string.telemetry_alignment_badge,
                        alignedResolution.widthAlignment,
                        alignedResolution.heightAlignment,
                        config.frameRate
                    ),
                    highlight = true,
                    modifier = Modifier.weight(1f)
                )
            }

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                val iFrameBadge = if (config.keyFrameIntervalMode == KeyFrameIntervalMode.ALL_I_ZERO) {
                    "All-I 無損幀"
                } else {
                    config.keyFrameIntervalMode.tag
                }
                TelemetryMetricBox(
                    label = stringResource(R.string.telemetry_codec_max),
                    value = "${config.codecType.shortTag} · ${config.bitrateMbps} Mbps",
                    subtext = "$iFrameBadge · 上限 ${activeCodecCap.maxBitrateMbps}M",
                    highlight = false,
                    modifier = Modifier.weight(1f)
                )
                TelemetryMetricBox(
                    label = stringResource(R.string.telemetry_estimated_size),
                    value = String.format(
                        Locale.US,
                        "~%.0f MB/分",
                        config.estimatedMegabytesPerMinute
                    ),
                    subtext = "${config.audioSourceMode.shortLabel} (${config.audioBitrateKbps}k)",
                    highlight = false,
                    modifier = Modifier.weight(1f)
                )
            }

            // Action Control Buttons Row
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(10.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                if (!isRecordingOrCountdown) {
                    Button(
                        onClick = onStartRecordingClick,
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(52.dp)
                            .testTag("start_recording_button"),
                        colors = ButtonDefaults.buttonColors(
                            containerColor = MaterialTheme.colorScheme.secondary,
                            contentColor = MaterialTheme.colorScheme.onSecondary
                        ),
                        shape = MaterialTheme.shapes.medium
                    ) {
                        Icon(
                            imageVector = Icons.Default.FiberManualRecord,
                            contentDescription = stringResource(R.string.btn_start_recording),
                            modifier = Modifier.size(20.dp)
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(
                            text = stringResource(R.string.btn_start_recording),
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold
                        )
                    }
                } else {
                    if (sessionState is RecordingSessionState.Recording) {
                        FilledTonalButton(
                            onClick = {
                                if (sessionState.isPaused) onResumeRecordingClick()
                                else onPauseRecordingClick()
                            },
                            modifier = Modifier
                                .weight(1f)
                                .height(52.dp)
                                .testTag("pause_resume_recording_button"),
                            shape = MaterialTheme.shapes.medium
                        ) {
                            Icon(
                                imageVector = if (sessionState.isPaused) Icons.Default.PlayArrow else Icons.Default.Pause,
                                contentDescription = if (sessionState.isPaused) {
                                    stringResource(R.string.btn_resume_recording)
                                } else {
                                    stringResource(R.string.btn_pause_recording)
                                }
                            )
                            Spacer(modifier = Modifier.width(6.dp))
                            Text(
                                text = if (sessionState.isPaused) {
                                    stringResource(R.string.btn_resume_recording)
                                } else {
                                    stringResource(R.string.btn_pause_recording)
                                },
                                style = MaterialTheme.typography.titleMedium
                            )
                        }
                    }

                    Button(
                        onClick = onStopRecordingClick,
                        modifier = Modifier
                            .weight(1.2f)
                            .height(52.dp)
                            .testTag("stop_recording_button"),
                        colors = ButtonDefaults.buttonColors(
                            containerColor = MaterialTheme.colorScheme.secondary,
                            contentColor = MaterialTheme.colorScheme.onSecondary
                        ),
                        shape = MaterialTheme.shapes.medium
                    ) {
                        Icon(
                            imageVector = Icons.Default.Stop,
                            contentDescription = stringResource(R.string.btn_stop_recording)
                        )
                        Spacer(modifier = Modifier.width(6.dp))
                        Text(
                            text = stringResource(R.string.btn_stop_recording),
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun TelemetryMetricBox(
    label: String,
    value: String,
    subtext: String,
    highlight: Boolean,
    modifier: Modifier = Modifier
) {
    Surface(
        modifier = modifier,
        color = if (highlight) {
            MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.45f)
        } else {
            MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.65f)
        },
        shape = MaterialTheme.shapes.small,
        border = if (highlight) {
            BorderStroke(1.dp, MaterialTheme.colorScheme.primary.copy(alpha = 0.5f))
        } else null
    ) {
        Column(
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp),
            verticalArrangement = Arrangement.spacedBy(2.dp)
        ) {
            Text(
                text = label,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            Text(
                text = value,
                style = MaterialTheme.typography.titleSmall,
                color = if (highlight) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface,
                fontWeight = FontWeight.Bold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            Text(
                text = subtext,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.85f),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }
    }
}

fun formatDurationMillis(millis: Long): String {
    val totalSecs = millis / 1000L
    val mins = totalSecs / 60L
    val secs = totalSecs % 60L
    return String.format(Locale.US, "%02d:%02d", mins, secs)
}
