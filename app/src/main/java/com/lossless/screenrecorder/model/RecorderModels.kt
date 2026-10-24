package com.lossless.screenrecorder.model

import android.media.MediaCodecInfo
import android.media.MediaFormat
import kotlinx.serialization.Serializable

@Serializable
enum class VideoCodecType(
    val mimeType: String,
    val displayName: String,
    val shortTag: String
) {
    HEVC(MediaFormat.MIMETYPE_VIDEO_HEVC, "H.265 (HEVC)", "HEVC"),
    AVC(MediaFormat.MIMETYPE_VIDEO_AVC, "H.264 (AVC)", "AVC")
}

@Serializable
enum class ResolutionMode(val scaleFactor: Float) {
    NATIVE_PHYSICAL(1.0f),
    CURRENT_RENDER(1.0f),
    CUSTOM_SCALE_75(0.75f)
}

@Serializable
enum class BitrateControlMode(val codecConstant: Int, val label: String) {
    CBR(MediaCodecInfo.EncoderCapabilities.BITRATE_MODE_CBR, "CBR 固定高碼率"),
    VBR(MediaCodecInfo.EncoderCapabilities.BITRATE_MODE_VBR, "VBR 動態高畫質"),
    CQ(MediaCodecInfo.EncoderCapabilities.BITRATE_MODE_CQ, "CQ 品質優先")
}

@Serializable
enum class KeyFrameIntervalMode(val intervalSeconds: Int, val tag: String) {
    ALL_I_ZERO(0, "All-I (0s)"),
    ONE_SECOND(1, "1s GOP"),
    TWO_SECONDS(2, "2s GOP")
}

@Serializable
enum class AudioSourceMode(
    val needsInternal: Boolean,
    val needsMic: Boolean,
    val shortLabel: String
) {
    MUTE(needsInternal = false, needsMic = false, shortLabel = "靜音無音軌"),
    INTERNAL_ONLY(needsInternal = true, needsMic = false, shortLabel = "僅系統內錄"),
    MIC_ONLY(needsInternal = false, needsMic = true, shortLabel = "僅麥克風"),
    INTERNAL_AND_MIC(needsInternal = true, needsMic = true, shortLabel = "內錄+麥克風混音");

    val needsRecordAudioPermission: Boolean
        get() = needsInternal || needsMic
}

@Serializable
enum class OverlayControlStyle(val label: String) {
    EDGE_SIDEBAR("貼邊隱形側邊欄"),
    FLOATING_BALL("傳統懸浮球")
}

@Serializable
enum class SidebarTapBehavior(val label: String) {
    EXPAND_PANEL("展開側邊控制欄"),
    INSTANT_STOP("點擊直接停止錄影")
}

@Serializable
data class RecorderConfig(
    val resolutionMode: ResolutionMode = ResolutionMode.NATIVE_PHYSICAL,
    val codecType: VideoCodecType = VideoCodecType.HEVC,
    val bitrateMbps: Int = 80,
    val frameRate: Int = 60,
    val bitrateControlMode: BitrateControlMode = BitrateControlMode.CBR,
    val keyFrameIntervalMode: KeyFrameIntervalMode = KeyFrameIntervalMode.ALL_I_ZERO,
    val enable10BitColor: Boolean = false,
    val audioSourceMode: AudioSourceMode = AudioSourceMode.INTERNAL_AND_MIC,
    val internalAudioGain: Float = 1.0f,
    val micAudioGain: Float = 1.0f,
    val audioBitrateKbps: Int = 320,
    val audioSampleRateHz: Int = 48000,
    val countdownSeconds: Int = 3,
    val enableNotificationController: Boolean = true,
    val enableFloatingBall: Boolean = true,
    val overlayControlStyle: OverlayControlStyle = OverlayControlStyle.EDGE_SIDEBAR,
    val sidebarTapBehavior: SidebarTapBehavior = SidebarTapBehavior.EXPAND_PANEL,
    val hideFloatingBallWhileRecording: Boolean = false,
    val autoStopOnScreenOff: Boolean = true
) {
    val estimatedMegabytesPerMinute: Float
        get() {
            val audioMbps = if (audioSourceMode == AudioSourceMode.MUTE) 0f else audioBitrateKbps / 1000f
            val totalMbps = bitrateMbps + audioMbps
            return (totalMbps * 60f) / 8f
        }
}

data class CodecCapabilityInfo(
    val codecType: VideoCodecType,
    val encoderName: String,
    val isHardwareAccelerated: Boolean,
    val maxWidth: Int,
    val maxHeight: Int,
    val widthAlignment: Int,
    val heightAlignment: Int,
    val minBitrateMbps: Int,
    val maxBitrateMbps: Int,
    val maxSupportedFpsAtNative: Int,
    val supportsCBR: Boolean,
    val supportsVBR: Boolean,
    val supportsCQ: Boolean,
    val supportsMain10: Boolean
)

data class HardwareCapabilities(
    val deviceModel: String,
    val socHardware: String,
    val nativePhysicalWidth: Int,
    val nativePhysicalHeight: Int,
    val currentRenderWidth: Int,
    val currentRenderHeight: Int,
    val densityDpi: Int,
    val maxRefreshRateHz: Int,
    val supportedRefreshRates: List<Int>,
    val hevcCapability: CodecCapabilityInfo,
    val avcCapability: CodecCapabilityInfo
) {
    fun capabilityFor(codecType: VideoCodecType): CodecCapabilityInfo {
        return when (codecType) {
            VideoCodecType.HEVC -> hevcCapability
            VideoCodecType.AVC -> avcCapability
        }
    }
}

data class AlignedResolution(
    val rawWidth: Int,
    val rawHeight: Int,
    val alignedWidth: Int,
    val alignedHeight: Int,
    val widthAlignment: Int,
    val heightAlignment: Int,
    val isSupportedByHardware: Boolean,
    val maxSupportedFps: Int
) {
    val wasAdjustedForAlignment: Boolean
        get() = rawWidth != alignedWidth || rawHeight != alignedHeight

    val displayText: String
        get() = "${alignedWidth} × ${alignedHeight}"
}

sealed interface RecordingSessionState {
    data object Idle : RecordingSessionState
    data class Countdown(val remainingSeconds: Int) : RecordingSessionState
    data class Recording(
        val elapsedMillis: Long,
        val bytesWritten: Long,
        val isPaused: Boolean,
        val resolutionLabel: String,
        val codecLabel: String,
        val bitrateMbps: Int
    ) : RecordingSessionState
    data object Finalizing : RecordingSessionState
    data class Completed(
        val fileName: String,
        val contentUriString: String
    ) : RecordingSessionState
    data class Error(val message: String) : RecordingSessionState
}

data class RecordedVideoItem(
    val id: Long,
    val uriString: String,
    val displayName: String,
    val width: Int,
    val height: Int,
    val durationMillis: Long,
    val sizeBytes: Long,
    val bitrateMbps: Float,
    val frameRate: Int,
    val codecLabel: String,
    val dateAddedMillis: Long
)
