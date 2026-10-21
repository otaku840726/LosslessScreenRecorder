package com.lossless.screenrecorder.ui.screens

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.lossless.screenrecorder.R
import com.lossless.screenrecorder.model.AlignedResolution
import com.lossless.screenrecorder.model.BitrateControlMode
import com.lossless.screenrecorder.model.HardwareCapabilities
import com.lossless.screenrecorder.model.KeyFrameIntervalMode
import com.lossless.screenrecorder.model.RecorderConfig
import com.lossless.screenrecorder.model.ResolutionMode
import com.lossless.screenrecorder.model.VideoCodecType
import java.util.Locale
import kotlin.math.roundToInt

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun EncoderConfigScreen(
    capabilities: HardwareCapabilities,
    config: RecorderConfig,
    alignedResolution: AlignedResolution,
    onResolutionModeChange: (ResolutionMode) -> Unit,
    onCodecTypeChange: (VideoCodecType) -> Unit,
    onBitrateChange: (Int) -> Unit,
    onFrameRateChange: (Int) -> Unit,
    onBitrateControlModeChange: (BitrateControlMode) -> Unit,
    onKeyFrameIntervalModeChange: (KeyFrameIntervalMode) -> Unit,
    onEnable10BitChange: (Boolean) -> Unit,
    modifier: Modifier = Modifier
) {
    val activeCodecCap = capabilities.capabilityFor(config.codecType)

    Column(
        modifier = modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        // Section 1: Native Physical Resolution vs Current Render vs 75% Scale
        SectionContainerCard(title = stringResource(R.string.section_resolution_title)) {
            val scale75W = (capabilities.nativePhysicalWidth * 0.75f).roundToInt()
            val scale75H = (capabilities.nativePhysicalHeight * 0.75f).roundToInt()

            ResolutionOptionRow(
                title = stringResource(R.string.res_mode_native),
                subtitle = stringResource(R.string.res_mode_native_desc),
                dimensionBadge = "${capabilities.nativePhysicalWidth} × ${capabilities.nativePhysicalHeight}",
                selected = config.resolutionMode == ResolutionMode.NATIVE_PHYSICAL,
                onClick = { onResolutionModeChange(ResolutionMode.NATIVE_PHYSICAL) },
                testTag = "res_option_native_physical"
            )

            ResolutionOptionRow(
                title = stringResource(R.string.res_mode_current),
                subtitle = stringResource(R.string.res_mode_current_desc),
                dimensionBadge = "${capabilities.currentRenderWidth} × ${capabilities.currentRenderHeight}",
                selected = config.resolutionMode == ResolutionMode.CURRENT_RENDER,
                onClick = { onResolutionModeChange(ResolutionMode.CURRENT_RENDER) },
                testTag = "res_option_current_render"
            )

            ResolutionOptionRow(
                title = stringResource(R.string.res_mode_custom_scale),
                subtitle = stringResource(R.string.res_mode_custom_scale_desc),
                dimensionBadge = "$scale75W × $scale75H",
                selected = config.resolutionMode == ResolutionMode.CUSTOM_SCALE_75,
                onClick = { onResolutionModeChange(ResolutionMode.CUSTOM_SCALE_75) },
                testTag = "res_option_scale_75"
            )

            if (alignedResolution.wasAdjustedForAlignment) {
                Surface(
                    color = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.4f),
                    shape = MaterialTheme.shapes.small,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text(
                        text = "硬體編碼器自動對齊：原始 ${alignedResolution.rawWidth}×${alignedResolution.rawHeight} 已自動對齊至 ${alignedResolution.displayText} (倍數 ${alignedResolution.widthAlignment}×${alignedResolution.heightAlignment})",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.padding(10.dp)
                    )
                }
            }
        }

        // Section 2: Hardware Video Codec (HEVC vs AVC)
        SectionContainerCard(title = stringResource(R.string.section_codec_title)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                CodecSelectCard(
                    title = stringResource(R.string.codec_hevc_title),
                    subtitle = stringResource(R.string.codec_hevc_desc),
                    encoderChip = capabilities.hevcCapability.encoderName,
                    selected = config.codecType == VideoCodecType.HEVC,
                    onClick = { onCodecTypeChange(VideoCodecType.HEVC) },
                    modifier = Modifier
                        .weight(1f)
                        .testTag("codec_select_hevc")
                )
                CodecSelectCard(
                    title = stringResource(R.string.codec_avc_title),
                    subtitle = stringResource(R.string.codec_avc_desc),
                    encoderChip = capabilities.avcCapability.encoderName,
                    selected = config.codecType == VideoCodecType.AVC,
                    onClick = { onCodecTypeChange(VideoCodecType.AVC) },
                    modifier = Modifier
                        .weight(1f)
                        .testTag("codec_select_avc")
                )
            }

            if (config.codecType == VideoCodecType.HEVC && activeCodecCap.supportsMain10) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 6.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = "10-bit HEVC Main10 色彩深度",
                            style = MaterialTheme.typography.titleMedium,
                            color = MaterialTheme.colorScheme.onSurface
                        )
                        Text(
                            text = "減少漸層色帶斷層 (Banding)，硬體編碼器已支援 Main10 Profile",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    Switch(
                        checked = config.enable10BitColor,
                        onCheckedChange = onEnable10BitChange,
                        modifier = Modifier.testTag("switch_10bit_color")
                    )
                }
            }
        }

        // Section 3: Ultra-High Bitrate & Bitrate Mode
        SectionContainerCard(title = stringResource(R.string.section_bitrate_title)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.Bottom
            ) {
                Column {
                    Text(
                        text = "${config.bitrateMbps} Mbps",
                        style = MaterialTheme.typography.headlineMedium,
                        color = MaterialTheme.colorScheme.primary
                    )
                    Text(
                        text = String.format(
                            Locale.US,
                            "預估每分鐘檔案大小：約 %.1f MB / 分鐘",
                            config.estimatedMegabytesPerMinute
                        ),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }

                Surface(
                    color = MaterialTheme.colorScheme.surfaceVariant,
                    shape = MaterialTheme.shapes.extraSmall
                ) {
                    Text(
                        text = "硬體上限 ${activeCodecCap.maxBitrateMbps} Mbps",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp)
                    )
                }
            }

            Slider(
                value = config.bitrateMbps.toFloat(),
                onValueChange = { onBitrateChange(it.roundToInt()) },
                valueRange = activeCodecCap.minBitrateMbps.toFloat()..activeCodecCap.maxBitrateMbps.toFloat(),
                modifier = Modifier
                    .fillMaxWidth()
                    .testTag("bitrate_slider")
            )

            // Quick Bitrate Presets
            val presets = listOf(25, 50, 80, 120, activeCodecCap.maxBitrateMbps).distinct().sorted()
            FlowRow(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                presets.forEach { presetMbps ->
                    FilterChip(
                        selected = config.bitrateMbps == presetMbps,
                        onClick = { onBitrateChange(presetMbps) },
                        label = {
                            Text(
                                text = if (presetMbps == activeCodecCap.maxBitrateMbps) {
                                    "極限 ${presetMbps}M"
                                } else {
                                    "${presetMbps} Mbps"
                                },
                                style = MaterialTheme.typography.labelMedium
                            )
                        },
                        modifier = Modifier.testTag("bitrate_preset_$presetMbps")
                    )
                }
            }

            Spacer(modifier = Modifier.height(4.dp))

            // Bitrate Control Mode (CBR / VBR / CQ)
            Text(
                text = "碼率控制模式 (Bitrate Mode)",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            FlowRow(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                BitrateControlMode.entries.forEach { mode ->
                    FilterChip(
                        selected = config.bitrateControlMode == mode,
                        onClick = { onBitrateControlModeChange(mode) },
                        label = {
                            Text(
                                text = mode.label,
                                style = MaterialTheme.typography.labelMedium
                            )
                        },
                        modifier = Modifier.testTag("bitrate_mode_${mode.name.lowercase()}")
                    )
                }
            }
        }

        // Section 4: High Refresh Frame Rate & Keyframe Interval (All-I)
        SectionContainerCard(title = stringResource(R.string.section_framerate_iframe_title)) {
            Text(
                text = "錄製目標幀率 (面板最高支援 ${capabilities.maxRefreshRateHz} Hz)",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )

            val fpsChoices = listOf(30, 60, 90, 120)
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                fpsChoices.forEach { fps ->
                    FilterChip(
                        selected = config.frameRate == fps,
                        onClick = { onFrameRateChange(fps) },
                        label = {
                            Text(
                                text = "$fps FPS",
                                style = MaterialTheme.typography.labelLarge
                            )
                        },
                        colors = FilterChipDefaults.filterChipColors(
                            selectedContainerColor = MaterialTheme.colorScheme.primaryContainer,
                            selectedLabelColor = MaterialTheme.colorScheme.primary
                        ),
                        modifier = Modifier
                            .weight(1f)
                            .testTag("fps_chip_$fps")
                    )
                }
            }

            Spacer(modifier = Modifier.height(6.dp))

            KeyFrameOptionRow(
                title = stringResource(R.string.iframe_all_i_title),
                subtitle = stringResource(R.string.iframe_all_i_desc),
                badgeText = "ALL-I 無損",
                isHighlightAmber = true,
                selected = config.keyFrameIntervalMode == KeyFrameIntervalMode.ALL_I_ZERO,
                onClick = { onKeyFrameIntervalModeChange(KeyFrameIntervalMode.ALL_I_ZERO) },
                testTag = "iframe_option_all_i"
            )

            KeyFrameOptionRow(
                title = stringResource(R.string.iframe_1s_title),
                subtitle = stringResource(R.string.iframe_1s_desc),
                badgeText = "1s GOP",
                isHighlightAmber = false,
                selected = config.keyFrameIntervalMode == KeyFrameIntervalMode.ONE_SECOND,
                onClick = { onKeyFrameIntervalModeChange(KeyFrameIntervalMode.ONE_SECOND) },
                testTag = "iframe_option_1s"
            )

            KeyFrameOptionRow(
                title = stringResource(R.string.iframe_2s_title),
                subtitle = stringResource(R.string.iframe_2s_desc),
                badgeText = "2s GOP",
                isHighlightAmber = false,
                selected = config.keyFrameIntervalMode == KeyFrameIntervalMode.TWO_SECONDS,
                onClick = { onKeyFrameIntervalModeChange(KeyFrameIntervalMode.TWO_SECONDS) },
                testTag = "iframe_option_2s"
            )
        }
    }
}

@Composable
fun SectionContainerCard(
    title: String,
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit
) {
    Card(
        modifier = modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surface
        ),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
        shape = MaterialTheme.shapes.medium
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Text(
                text = title,
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.primary,
                fontWeight = FontWeight.Bold
            )
            content()
        }
    }
}

@Composable
private fun ResolutionOptionRow(
    title: String,
    subtitle: String,
    dimensionBadge: String,
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
            Surface(
                color = MaterialTheme.colorScheme.surface,
                shape = MaterialTheme.shapes.extraSmall,
                border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline)
            ) {
                Text(
                    text = dimensionBadge,
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp)
                )
            }
        }
    }
}

@Composable
private fun CodecSelectCard(
    title: String,
    subtitle: String,
    encoderChip: String,
    selected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    Surface(
        modifier = modifier.clickable(onClick = onClick),
        color = if (selected) {
            MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.4f)
        } else {
            MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.4f)
        },
        shape = MaterialTheme.shapes.small,
        border = BorderStroke(
            width = if (selected) 1.5.dp else 1.dp,
            color = if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outlineVariant
        )
    ) {
        Column(
            modifier = Modifier.padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            Text(
                text = title,
                style = MaterialTheme.typography.titleMedium,
                color = if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface,
                fontWeight = FontWeight.Bold
            )
            Text(
                text = subtitle,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Text(
                text = encoderChip,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.8f)
            )
        }
    }
}

@Composable
private fun KeyFrameOptionRow(
    title: String,
    subtitle: String,
    badgeText: String,
    isHighlightAmber: Boolean,
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
            Surface(
                color = if (isHighlightAmber) {
                    MaterialTheme.colorScheme.tertiaryContainer
                } else {
                    MaterialTheme.colorScheme.surface
                },
                shape = MaterialTheme.shapes.extraSmall
            ) {
                Text(
                    text = badgeText,
                    style = MaterialTheme.typography.labelSmall,
                    color = if (isHighlightAmber) {
                        MaterialTheme.colorScheme.tertiary
                    } else {
                        MaterialTheme.colorScheme.onSurfaceVariant
                    },
                    modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp)
                )
            }
        }
    }
}
