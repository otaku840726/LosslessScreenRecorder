package com.lossless.screenrecorder.engine

import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaFormat
import android.os.Build
import android.os.Bundle
import android.view.Surface
import com.lossless.screenrecorder.model.AlignedResolution
import com.lossless.screenrecorder.model.RecorderConfig
import com.lossless.screenrecorder.model.VideoCodecType
import java.util.concurrent.atomic.AtomicBoolean

class VideoEncoderCore(
    private val config: RecorderConfig,
    private val alignedResolution: AlignedResolution,
    private val muxerWrapper: MediaMuxerWrapper
) {
    private var mediaCodec: MediaCodec? = null
    var inputSurface: Surface? = null
        private set

    private val isRunning = AtomicBoolean(false)
    private var drainThread: Thread? = null

    fun prepare(): Surface {
        val mimeType = config.codecType.mimeType
        val width = alignedResolution.alignedWidth
        val height = alignedResolution.alignedHeight
        val bitrateBps = (config.bitrateMbps.coerceIn(5, 200)) * 1_000_000

        val codec = MediaCodec.createEncoderByType(mimeType)
        val format = createMediaFormat(
            mimeType = mimeType,
            width = width,
            height = height,
            bitrateBps = bitrateBps,
            includeAdvancedFlags = true
        )

        try {
            codec.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
        } catch (_: Exception) {
            // Fallback with standard baseline format if specific SoC rejects CBR or Main10 flag
            codec.reset()
            val fallbackFormat = createMediaFormat(
                mimeType = mimeType,
                width = width,
                height = height,
                bitrateBps = bitrateBps,
                includeAdvancedFlags = false
            )
            codec.configure(fallbackFormat, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
        }

        val surface = codec.createInputSurface()
        codec.start()

        mediaCodec = codec
        inputSurface = surface
        isRunning.set(true)

        drainThread = Thread({ drainEncoderLoop() }, "LosslessVideoEncoderDrain").apply {
            priority = Thread.MAX_PRIORITY
            start()
        }

        return surface
    }

    private fun createMediaFormat(
        mimeType: String,
        width: Int,
        height: Int,
        bitrateBps: Int,
        includeAdvancedFlags: Boolean
    ): MediaFormat {
        return MediaFormat.createVideoFormat(mimeType, width, height).apply {
            setInteger(
                MediaFormat.KEY_COLOR_FORMAT,
                MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface
            )
            setInteger(MediaFormat.KEY_BIT_RATE, bitrateBps)
            setInteger(MediaFormat.KEY_FRAME_RATE, config.frameRate)

            // Key-frame interval: 0 means All-I frame mode (every frame is an IDR/keyframe)
            setInteger(
                MediaFormat.KEY_I_FRAME_INTERVAL,
                config.keyFrameIntervalMode.intervalSeconds
            )

            // Repeat previous frame every 100ms if screen is static so Muxer & VFR/CFR stays healthy
            setLong(MediaFormat.KEY_REPEAT_PREVIOUS_FRAME_AFTER, 100_000L)

            if (includeAdvancedFlags) {
                setInteger(MediaFormat.KEY_BITRATE_MODE, config.bitrateControlMode.codecConstant)
                setInteger(
                    MediaFormat.KEY_COLOR_STANDARD,
                    MediaFormat.COLOR_STANDARD_BT709
                )
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                    setInteger(MediaFormat.KEY_ALLOW_FRAME_DROP, 0)
                }
                if (config.enable10BitColor && config.codecType == VideoCodecType.HEVC) {
                    setInteger(
                        MediaFormat.KEY_PROFILE,
                        MediaCodecInfo.CodecProfileLevel.HEVCProfileMain10
                    )
                }
            }
        }
    }

    fun requestSyncFrame() {
        runCatching {
            val params = Bundle().apply {
                putInt(MediaCodec.PARAMETER_KEY_REQUEST_SYNC_FRAME, 0)
            }
            mediaCodec?.setParameters(params)
        }
    }

    private fun drainEncoderLoop() {
        val codec = mediaCodec ?: return
        val bufferInfo = MediaCodec.BufferInfo()

        while (isRunning.get()) {
            val outIndex = try {
                codec.dequeueOutputBuffer(bufferInfo, 10_000L)
            } catch (_: Exception) {
                break
            }

            when {
                outIndex == MediaCodec.INFO_TRY_AGAIN_LATER -> {
                    // Continue waiting for next frame
                }
                outIndex == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                    runCatching {
                        muxerWrapper.addVideoTrack(codec.outputFormat)
                    }
                }
                outIndex >= 0 -> {
                    val encodedData = runCatching { codec.getOutputBuffer(outIndex) }.getOrNull()
                    if (encodedData != null) {
                        if ((bufferInfo.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG) != 0) {
                            bufferInfo.size = 0
                        }
                        if (bufferInfo.size > 0) {
                            encodedData.position(bufferInfo.offset)
                            encodedData.limit(bufferInfo.offset + bufferInfo.size)
                            muxerWrapper.writeVideoSample(encodedData, bufferInfo)
                        }
                    }
                    runCatching { codec.releaseOutputBuffer(outIndex, false) }
                    if ((bufferInfo.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0) {
                        break
                    }
                }
            }
        }
    }

    fun stopAndRelease() {
        if (!isRunning.getAndSet(false)) return
        runCatching { mediaCodec?.signalEndOfInputStream() }
        runCatching { drainThread?.join(1200L) }
        drainThread = null
        runCatching { inputSurface?.release() }
        inputSurface = null
        runCatching { mediaCodec?.stop() }
        runCatching { mediaCodec?.release() }
        mediaCodec = null
    }
}
