package com.lossless.screenrecorder.engine

import android.annotation.SuppressLint
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioPlaybackCaptureConfiguration
import android.media.AudioRecord
import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaFormat
import android.media.MediaRecorder
import android.media.projection.MediaProjection
import android.os.Process
import android.os.SystemClock
import com.lossless.screenrecorder.model.AudioSourceMode
import com.lossless.screenrecorder.model.RecorderConfig
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.math.max
import kotlin.math.roundToInt

class AudioCaptureMixerEngine(
    private val config: RecorderConfig,
    private val mediaProjection: MediaProjection,
    private val muxerWrapper: MediaMuxerWrapper
) {
    private val sampleRate = 48_000
    private val channelCount = 2
    private val channelConfig = AudioFormat.CHANNEL_IN_STEREO
    private val audioEncoding = AudioFormat.ENCODING_PCM_16BIT

    private var internalRecord: AudioRecord? = null
    private var micRecord: AudioRecord? = null
    private var audioCodec: MediaCodec? = null

    private val isRunning = AtomicBoolean(false)
    private var audioThread: Thread? = null

    @Volatile
    var internalGain: Float = config.internalAudioGain

    @Volatile
    var micGain: Float = config.micAudioGain

    @SuppressLint("MissingPermission")
    fun start() {
        if (config.audioSourceMode == AudioSourceMode.MUTE) {
            muxerWrapper.disableAudioTrackExpectation()
            return
        }

        val minBufSize = AudioRecord.getMinBufferSize(sampleRate, channelConfig, audioEncoding)
            .coerceAtLeast(4096)
        val bufferSizeInBytes = minBufSize * 4

        if (config.audioSourceMode.needsInternal) {
            internalRecord = runCatching {
                val captureConfig = AudioPlaybackCaptureConfiguration.Builder(mediaProjection)
                    .addMatchingUsage(AudioAttributes.USAGE_MEDIA)
                    .addMatchingUsage(AudioAttributes.USAGE_GAME)
                    .addMatchingUsage(AudioAttributes.USAGE_UNKNOWN)
                    .build()

                val audioFormat = AudioFormat.Builder()
                    .setEncoding(audioEncoding)
                    .setSampleRate(sampleRate)
                    .setChannelMask(channelConfig)
                    .build()

                AudioRecord.Builder()
                    .setAudioFormat(audioFormat)
                    .setBufferSizeInBytes(bufferSizeInBytes)
                    .setAudioPlaybackCaptureConfig(captureConfig)
                    .build()
                    .takeIf { it.state == AudioRecord.STATE_INITIALIZED }
            }.getOrNull()
        }

        if (config.audioSourceMode.needsMic) {
            micRecord = runCatching {
                val audioFormat = AudioFormat.Builder()
                    .setEncoding(audioEncoding)
                    .setSampleRate(sampleRate)
                    .setChannelMask(channelConfig)
                    .build()

                AudioRecord.Builder()
                    .setAudioSource(MediaRecorder.AudioSource.MIC)
                    .setAudioFormat(audioFormat)
                    .setBufferSizeInBytes(bufferSizeInBytes)
                    .build()
                    .takeIf { it.state == AudioRecord.STATE_INITIALIZED }
            }.getOrNull()
        }

        if (internalRecord == null && micRecord == null) {
            // If neither audio source could initialize (e.g. missing permission), fall back safely
            muxerWrapper.disableAudioTrackExpectation()
            return
        }

        // Configure 48kHz / 320kbps Stereo AAC-LC Hardware Encoder
        val codec = runCatching {
            val format = MediaFormat.createAudioFormat(
                MediaFormat.MIMETYPE_AUDIO_AAC,
                sampleRate,
                channelCount
            ).apply {
                setInteger(
                    MediaFormat.KEY_AAC_PROFILE,
                    MediaCodecInfo.CodecProfileLevel.AACObjectLC
                )
                setInteger(MediaFormat.KEY_BIT_RATE, config.audioBitrateKbps * 1000)
                setInteger(MediaFormat.KEY_MAX_INPUT_SIZE, 16384)
            }
            MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_AUDIO_AAC).apply {
                configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
                start()
            }
        }.getOrNull()

        if (codec == null) {
            muxerWrapper.disableAudioTrackExpectation()
            releaseAudioRecords()
            return
        }

        audioCodec = codec
        isRunning.set(true)

        runCatching { internalRecord?.startRecording() }
        runCatching { micRecord?.startRecording() }

        audioThread = Thread({ captureMixAndEncodeLoop() }, "LosslessAudioMixerThread").apply {
            start()
        }
    }

    private fun captureMixAndEncodeLoop() {
        Process.setThreadPriority(Process.THREAD_PRIORITY_URGENT_AUDIO)
        val codec = audioCodec ?: return
        val frameShorts = 2048 // 1024 stereo samples per read chunk
        val internalBuf = ShortArray(frameShorts)
        val micBuf = ShortArray(frameShorts)
        val mixedBytes = ByteArray(frameShorts * 2)
        val bufferInfo = MediaCodec.BufferInfo()

        while (isRunning.get()) {
            val sysRec = internalRecord
            val mRec = micRecord

            val sysRead = if (sysRec != null) {
                sysRec.read(internalBuf, 0, frameShorts, AudioRecord.READ_NON_BLOCKING)
            } else 0

            val micRead = if (mRec != null) {
                mRec.read(micBuf, 0, frameShorts, AudioRecord.READ_BLOCKING)
            } else if (sysRec != null) {
                // If only internal audio is active, read blocking on internal audio
                sysRec.read(internalBuf, 0, frameShorts, AudioRecord.READ_BLOCKING)
            } else 0

            val samplesToProcess = max(sysRead, micRead)
            if (samplesToProcess <= 0) {
                SystemClock.sleep(5)
                continue
            }

            val sysGain = internalGain
            val mGain = micGain

            // Real-time 16-bit PCM mixing with independent gain and anti-clipping clamp
            val byteBuffer = ByteBuffer.wrap(mixedBytes).order(ByteOrder.LITTLE_ENDIAN)
            for (i in 0 until samplesToProcess) {
                val sysSample = if (i < sysRead) (internalBuf[i] * sysGain).roundToInt() else 0
                val micSample = if (i < micRead) (micBuf[i] * mGain).roundToInt() else 0
                val mixed = (sysSample + micSample).coerceIn(
                    Short.MIN_VALUE.toInt(),
                    Short.MAX_VALUE.toInt()
                ).toShort()
                byteBuffer.putShort(mixed)
            }

            val bytesToFeed = samplesToProcess * 2
            val ptsUs = SystemClock.elapsedRealtimeNanos() / 1000L

            val inIndex = runCatching { codec.dequeueInputBuffer(10_000L) }.getOrDefault(-1)
            if (inIndex >= 0) {
                val codecInBuf = runCatching { codec.getInputBuffer(inIndex) }.getOrNull()
                if (codecInBuf != null) {
                    codecInBuf.clear()
                    val capacity = codecInBuf.remaining().coerceAtMost(bytesToFeed)
                    codecInBuf.put(mixedBytes, 0, capacity)
                    runCatching {
                        codec.queueInputBuffer(inIndex, 0, capacity, ptsUs, 0)
                    }
                }
            }

            drainAudioCodecOutput(codec, bufferInfo)
        }
    }

    private fun drainAudioCodecOutput(codec: MediaCodec, bufferInfo: MediaCodec.BufferInfo) {
        while (true) {
            val outIndex = runCatching { codec.dequeueOutputBuffer(bufferInfo, 0L) }.getOrDefault(-1)
            when {
                outIndex == MediaCodec.INFO_TRY_AGAIN_LATER -> break
                outIndex == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                    runCatching { muxerWrapper.addAudioTrack(codec.outputFormat) }
                }
                outIndex >= 0 -> {
                    val outBuf = runCatching { codec.getOutputBuffer(outIndex) }.getOrNull()
                    if (outBuf != null) {
                        if ((bufferInfo.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG) != 0) {
                            bufferInfo.size = 0
                        }
                        if (bufferInfo.size > 0) {
                            outBuf.position(bufferInfo.offset)
                            outBuf.limit(bufferInfo.offset + bufferInfo.size)
                            muxerWrapper.writeAudioSample(outBuf, bufferInfo)
                        }
                    }
                    runCatching { codec.releaseOutputBuffer(outIndex, false) }
                }
                else -> break
            }
        }
    }

    private fun releaseAudioRecords() {
        runCatching { internalRecord?.stop() }
        runCatching { internalRecord?.release() }
        internalRecord = null
        runCatching { micRecord?.stop() }
        runCatching { micRecord?.release() }
        micRecord = null
    }

    fun stopAndRelease() {
        if (!isRunning.getAndSet(false)) {
            releaseAudioRecords()
            return
        }
        runCatching { audioThread?.join(1000L) }
        audioThread = null
        releaseAudioRecords()
        runCatching { audioCodec?.stop() }
        runCatching { audioCodec?.release() }
        audioCodec = null
    }
}
