package com.lossless.screenrecorder.domain

import android.content.Context
import android.hardware.display.DisplayManager
import android.media.MediaCodecInfo
import android.media.MediaCodecList
import android.os.Build
import android.util.DisplayMetrics
import android.view.Display
import android.view.WindowManager
import com.lossless.screenrecorder.model.AlignedResolution
import com.lossless.screenrecorder.model.CodecCapabilityInfo
import com.lossless.screenrecorder.model.HardwareCapabilities
import com.lossless.screenrecorder.model.RecorderConfig
import com.lossless.screenrecorder.model.ResolutionMode
import com.lossless.screenrecorder.model.VideoCodecType
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

class DeviceCapabilityDetector(private val context: Context) {

    fun detectCapabilities(): HardwareCapabilities {
        val displayManager = context.getSystemService(Context.DISPLAY_SERVICE) as DisplayManager
        val defaultDisplay = displayManager.getDisplay(Display.DEFAULT_DISPLAY)
        val windowManager = context.getSystemService(Context.WINDOW_SERVICE) as WindowManager

        // 1. Current Render Resolution & Density
        val currentWidth: Int
        val currentHeight: Int
        val densityDpi: Int

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            val bounds = windowManager.currentWindowMetrics.bounds
            currentWidth = bounds.width()
            currentHeight = bounds.height()
            densityDpi = context.resources.configuration.densityDpi
        } else {
            val metrics = DisplayMetrics()
            @Suppress("DEPRECATION")
            defaultDisplay?.getRealMetrics(metrics)
            currentWidth = metrics.widthPixels
            currentHeight = metrics.heightPixels
            densityDpi = metrics.densityDpi
        }

        // 2. True Native Physical Panel Resolution & Refresh Rates from Display.Mode
        var maxPhysicalWidth = currentWidth
        var maxPhysicalHeight = currentHeight
        val refreshRatesSet = mutableSetOf(60)

        defaultDisplay?.supportedModes?.forEach { mode ->
            val modePixels = mode.physicalWidth.toLong() * mode.physicalHeight.toLong()
            val currentMaxPixels = maxPhysicalWidth.toLong() * maxPhysicalHeight.toLong()
            if (modePixels >= currentMaxPixels) {
                // Keep orientation consistent with current screen orientation
                val isCurrentPortrait = currentHeight >= currentWidth
                val longer = max(mode.physicalWidth, mode.physicalHeight)
                val shorter = min(mode.physicalWidth, mode.physicalHeight)
                if (isCurrentPortrait) {
                    maxPhysicalWidth = shorter
                    maxPhysicalHeight = longer
                } else {
                    maxPhysicalWidth = longer
                    maxPhysicalHeight = shorter
                }
            }
            val roundedRate = mode.refreshRate.roundToInt()
            if (roundedRate >= 30) {
                refreshRatesSet.add(roundedRate)
            }
        }

        // Add standard high-refresh rates supported by common panels
        val sortedRates = (refreshRatesSet + listOf(30, 60)).distinct().sorted()
        val maxRefreshRate = sortedRates.maxOrNull() ?: 60

        // 3. Hardware MediaCodec Capabilities for HEVC & AVC
        val hevcCap = probeVideoCodec(
            codecType = VideoCodecType.HEVC,
            nativeWidth = maxPhysicalWidth,
            nativeHeight = maxPhysicalHeight,
            maxPanelRefresh = maxRefreshRate
        )
        val avcCap = probeVideoCodec(
            codecType = VideoCodecType.AVC,
            nativeWidth = maxPhysicalWidth,
            nativeHeight = maxPhysicalHeight,
            maxPanelRefresh = maxRefreshRate
        )

        val socModel = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            "${Build.SOC_MANUFACTURER} ${Build.SOC_MODEL}".trim().ifEmpty { Build.HARDWARE }
        } else {
            Build.HARDWARE
        }

        return HardwareCapabilities(
            deviceModel = "${Build.MANUFACTURER} ${Build.MODEL}".trim(),
            socHardware = socModel,
            nativePhysicalWidth = maxPhysicalWidth,
            nativePhysicalHeight = maxPhysicalHeight,
            currentRenderWidth = currentWidth,
            currentRenderHeight = currentHeight,
            densityDpi = densityDpi,
            maxRefreshRateHz = maxRefreshRate,
            supportedRefreshRates = sortedRates,
            hevcCapability = hevcCap,
            avcCapability = avcCap
        )
    }

    fun calculateAlignedResolution(
        capabilities: HardwareCapabilities,
        config: RecorderConfig
    ): AlignedResolution {
        val codecCap = capabilities.capabilityFor(config.codecType)
        val baseWidth: Int
        val baseHeight: Int

        when (config.resolutionMode) {
            ResolutionMode.NATIVE_PHYSICAL -> {
                baseWidth = capabilities.nativePhysicalWidth
                baseHeight = capabilities.nativePhysicalHeight
            }
            ResolutionMode.CURRENT_RENDER -> {
                baseWidth = capabilities.currentRenderWidth
                baseHeight = capabilities.currentRenderHeight
            }
            ResolutionMode.CUSTOM_SCALE_75 -> {
                baseWidth = (capabilities.nativePhysicalWidth * 0.75f).roundToInt()
                baseHeight = (capabilities.nativePhysicalHeight * 0.75f).roundToInt()
            }
        }

        val wAlign = max(2, codecCap.widthAlignment)
        val hAlign = max(2, codecCap.heightAlignment)

        // Clamp within hardware max dimensions (accounting for portrait vs landscape encoder bounds)
        val isPortrait = baseHeight >= baseWidth
        val hwMaxLong = max(codecCap.maxWidth, codecCap.maxHeight)
        val hwMaxShort = min(codecCap.maxWidth, codecCap.maxHeight)

        var targetW = baseWidth
        var targetH = baseHeight
        val currentLong = max(targetW, targetH)
        val currentShort = min(targetW, targetH)

        if (currentLong > hwMaxLong || currentShort > hwMaxShort) {
            val scale = min(
                hwMaxLong.toFloat() / currentLong.toFloat(),
                hwMaxShort.toFloat() / currentShort.toFloat()
            )
            targetW = (targetW * scale).toInt()
            targetH = (targetH * scale).toInt()
        }

        // Align down to nearest multiple of wAlign / hAlign (and ensure at least 160px)
        val alignedW = max(wAlign * 10, (targetW / wAlign) * wAlign)
        val alignedH = max(hAlign * 10, (targetH / hAlign) * hAlign)

        val checkW = if (isPortrait) alignedW else alignedW
        val checkH = if (isPortrait) alignedH else alignedH

        return AlignedResolution(
            rawWidth = baseWidth,
            rawHeight = baseHeight,
            alignedWidth = checkW,
            alignedHeight = checkH,
            widthAlignment = wAlign,
            heightAlignment = hAlign,
            isSupportedByHardware = true,
            maxSupportedFps = codecCap.maxSupportedFpsAtNative
        )
    }

    private fun probeVideoCodec(
        codecType: VideoCodecType,
        nativeWidth: Int,
        nativeHeight: Int,
        maxPanelRefresh: Int
    ): CodecCapabilityInfo {
        val codecList = MediaCodecList(MediaCodecList.REGULAR_CODECS)
        val encoders = codecList.codecInfos.filter { info ->
            info.isEncoder && info.supportedTypes.any { it.equals(codecType.mimeType, ignoreCase = true) }
        }

        // Prefer hardware-accelerated non-software encoders
        val bestEncoder = encoders.sortedByDescending { info ->
            val hwScore = if (info.isHardwareAccelerated) 10 else 0
            val vendorScore = if (!info.name.startsWith("OMX.google.", ignoreCase = true) &&
                !info.name.startsWith("c2.android.", ignoreCase = true)
            ) 5 else 0
            hwScore + vendorScore
        }.firstOrNull()

        if (bestEncoder == null) {
            return CodecCapabilityInfo(
                codecType = codecType,
                encoderName = "Fallback (${codecType.shortTag})",
                isHardwareAccelerated = false,
                maxWidth = 3840,
                maxHeight = 2160,
                widthAlignment = 16,
                heightAlignment = 16,
                minBitrateMbps = 5,
                maxBitrateMbps = 120,
                maxSupportedFpsAtNative = maxPanelRefresh,
                supportsCBR = true,
                supportsVBR = true,
                supportsCQ = false,
                supportsMain10 = false
            )
        }

        return try {
            val caps = bestEncoder.getCapabilitiesForType(codecType.mimeType)
            val videoCaps = caps.videoCapabilities
            val encoderCaps = caps.encoderCapabilities

            val maxW = videoCaps?.supportedWidths?.upper ?: 3840
            val maxH = videoCaps?.supportedHeights?.upper ?: 2160
            val wAlign = videoCaps?.widthAlignment ?: 2
            val hAlign = videoCaps?.heightAlignment ?: 2

            val rawMaxBitrateBps = videoCaps?.bitrateRange?.upper ?: 120_000_000
            val maxBitrateMbps = (rawMaxBitrateBps / 1_000_000).coerceIn(40, 200)
            val minBitrateMbps = ((videoCaps?.bitrateRange?.lower ?: 1_000_000) / 1_000_000).coerceAtLeast(5)

            // Check max FPS at native resolution (test both orientations as MediaCodec specs are often landscape)
            val testW = max(nativeWidth, nativeHeight).coerceAtMost(max(maxW, maxH))
            val testH = min(nativeWidth, nativeHeight).coerceAtMost(min(maxW, maxH))
            val alignedTestW = (testW / max(2, wAlign)) * max(2, wAlign)
            val alignedTestH = (testH / max(2, hAlign)) * max(2, hAlign)

            val supportedFpsUpper = try {
                videoCaps?.getSupportedFrameRatesFor(alignedTestW, alignedTestH)?.upper?.roundToInt()
                    ?: maxPanelRefresh
            } catch (_: Exception) {
                try {
                    videoCaps?.getSupportedFrameRatesFor(alignedTestH, alignedTestW)?.upper?.roundToInt()
                        ?: maxPanelRefresh
                } catch (_: Exception) {
                    maxPanelRefresh
                }
            }

            val supportsMain10 = caps.profileLevels?.any { pl ->
                codecType == VideoCodecType.HEVC &&
                    pl.profile == MediaCodecInfo.CodecProfileLevel.HEVCProfileMain10
            } ?: false

            CodecCapabilityInfo(
                codecType = codecType,
                encoderName = bestEncoder.name,
                isHardwareAccelerated = bestEncoder.isHardwareAccelerated,
                maxWidth = maxW,
                maxHeight = maxH,
                widthAlignment = wAlign,
                heightAlignment = hAlign,
                minBitrateMbps = minBitrateMbps,
                maxBitrateMbps = maxBitrateMbps,
                maxSupportedFpsAtNative = max(60, min(120, supportedFpsUpper)),
                supportsCBR = encoderCaps?.isBitrateModeSupported(
                    MediaCodecInfo.EncoderCapabilities.BITRATE_MODE_CBR
                ) ?: true,
                supportsVBR = encoderCaps?.isBitrateModeSupported(
                    MediaCodecInfo.EncoderCapabilities.BITRATE_MODE_VBR
                ) ?: true,
                supportsCQ = encoderCaps?.isBitrateModeSupported(
                    MediaCodecInfo.EncoderCapabilities.BITRATE_MODE_CQ
                ) ?: false,
                supportsMain10 = supportsMain10
            )
        } catch (_: Exception) {
            CodecCapabilityInfo(
                codecType = codecType,
                encoderName = bestEncoder.name,
                isHardwareAccelerated = bestEncoder.isHardwareAccelerated,
                maxWidth = 3840,
                maxHeight = 2160,
                widthAlignment = 8,
                heightAlignment = 8,
                minBitrateMbps = 5,
                maxBitrateMbps = 120,
                maxSupportedFpsAtNative = maxPanelRefresh,
                supportsCBR = true,
                supportsVBR = true,
                supportsCQ = false,
                supportsMain10 = false
            )
        }
    }
}
