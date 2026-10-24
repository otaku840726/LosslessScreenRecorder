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
import kotlin.math.roundToInt

class AudioCaptureMixerEngine(
    private val config: RecorderConfig,
    private val mediaProjection: MediaProjection,
    private val muxerWrapper: MediaMuxerWrapper
) {
    private val sampleRate = 48_000
    private val outputChannelCount = 2
    private val stereoMask = AudioFormat.CHANNEL_IN_STEREO
    private val monoMask = AudioFormat.CHANNEL_IN_MONO
    private val audioEncoding = AudioFormat.ENCODING_PCM_16BIT

    private var internalRecord: AudioRecord? = null
    private var micRecord: AudioRecord? = null
    private var isMicStereo: Boolean = true
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

        val minStereoBuf = AudioRecord.getMinBufferSize(sampleRate, stereoMask, audioEncoding)
            .coerceAtLeast(4096)
        val stereoBufferSize = minStereoBuf * 4

        // 1. Initialize Internal AudioPlaybackCapture (Stereo 48kHz 16-bit PCM)
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
                    .setChannelMask(stereoMask)
                    .build()

                AudioRecord.Builder()
                    .setAudioFormat(audioFormat)
                    .setBufferSizeInBytes(stereoBufferSize)
                    .setAudioPlaybackCaptureConfig(captureConfig)
                    .build()
                    .takeIf { it.state == AudioRecord.STATE_INITIALIZED }
            }.getOrNull()
        }

        // 2. Initialize Microphone AudioRecord (Try Stereo first, automatic fallback to Mono)
        if (config.audioSourceMode.needsMic) {
            val stereoMic = runCatching {
                val format = AudioFormat.Builder()
                    .setEncoding(audioEncoding)
                    .setSampleRate(sampleRate)
                    .setChannelMask(stereoMask)
                    .build()
                AudioRecord.Builder()
                    .setAudioSource(MediaRecorder.AudioSource.MIC)
                    .setAudioFormat(format)
                    .setBufferSizeInBytes(stereoBufferSize)
                    .build()
                    .takeIf { it.state == AudioRecord.STATE_INITIALIZED }
            }.getOrNull()

            if (stereoMic != null) {
                micRecord = stereoMic
                isMicStereo = true
            } else {
                val minMonoBuf = AudioRecord.getMinBufferSize(sampleRate, monoMask, audioEncoding)
                    .coerceAtLeast(2048)
                val monoMic = runCatching {
                    val format = AudioFormat.Builder()
                        .setEncoding(audioEncoding)
                        .setSampleRate(sampleRate)
                        .setChannelMask(monoMask)
                        .build()
                    AudioRecord.Builder()
                        .setAudioSource(MediaRecorder.AudioSource.MIC)
                        .setAudioFormat(format)
                        .setBufferSizeInBytes(minMonoBuf * 4)
                        .build()
                        .takeIf { it.state == AudioRecord.STATE_INITIALIZED }
                }.getOrNull()
                micRecord = monoMic
                isMicStereo = false
            }
        }

        if (internalRecord == null && micRecord == null) {
            muxerWrapper.disableAudioTrackExpectation()
            return
        }

        // 3. Configure 48kHz / 320kbps Stereo AAC-LC Hardware Encoder
        val codec = runCatching {
            val format = MediaFormat.createAudioFormat(
                MediaFormat.MIMETYPE_AUDIO_AAC,
                sampleRate,
                outputChannelCount
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
            priority = Thread.MAX_PRIORITY
            start()
        }
    }

    private fun captureMixAndEncodeLoop() {
        Process.setThreadPriority(Process.THREAD_PRIORITY_URGENT_AUDIO)
        val codec = audioCodec ?: return

        // 1024 stereo frames = 2048 shorts per AAC chunk (~21.333 ms at 48kHz)
        val stereoFrameShorts = 2048
        val monoFrameShorts = 1024
        val chunkDurationNs = (1024L * 1_000_000_000L) / sampleRate // 21,333,333 ns

        val internalStereoBuf = ShortArray(stereoFrameShorts)
        val micRawBuf = ShortArray(stereoFrameShorts)
        val micStereoBuf = ShortArray(stereoFrameShorts)
        val mixedBytes = ByteArray(stereoFrameShorts * 2)
        val bufferInfo = MediaCodec.BufferInfo()

        var nextChunkTargetNano = System.nanoTime()

        while (isRunning.get()) {
            val loopStartNano = System.nanoTime()
            val sysRec = internalRecord
            val mRec = micRecord

            // A. Read Microphone (if active). Blocking read on Mic naturally paces the loop at 48kHz!
            var hasMicSamples = false
            if (mRec != null) {
                if (isMicStereo) {
                    val readCount = mRec.read(micStereoBuf, 0, stereoFrameShorts, AudioRecord.READ_BLOCKING)
                    if (readCount > 0) {
                        hasMicSamples = true
                        if (readCount < stereoFrameShorts) {
                            micStereoBuf.fill(0, readCount, stereoFrameShorts)
                        }
                    }
                } else {
                    val readCount = mRec.read(micRawBuf, 0, monoFrameShorts, AudioRecord.READ_BLOCKING)
                    if (readCount > 0) {
                        hasMicSamples = true
                        // Upmix Mono to Stereo (L = sample, R = sample)
                        for (i in 0 until monoFrameShorts) {
                            val s = if (i < readCount) micRawBuf[i] else 0
                            micStereoBuf[i * 2] = s
                            micStereoBuf[i * 2 + 1] = s
                        }
                    }
                }
            }

            // B. Read Internal System Audio (non-blocking so silent moments in apps never stall the pipeline!)
            var hasInternalSamples = false
            if (sysRec != null) {
                val sysRead = sysRec.read(
                    internalStereoBuf,
                    0,
                    stereoFrameShorts,
                    AudioRecord.READ_NON_BLOCKING
                )
                if (sysRead > 0) {
                    hasInternalSamples = true
                    if (sysRead < stereoFrameShorts) {
                        internalStereoBuf.fill(0, sysRead, stereoFrameShorts)
                    }
                } else {
                    // System audio is currently silent; fill with digital silence so AAC stream stays continuous
                    internalStereoBuf.fill(0, 0, stereoFrameShorts)
                }
            }

            // C. If only Internal Audio is active (no blocking Mic), pace loop to exact 48kHz (21.33ms per 1024 frames)
            if (mRec == null) {
                nextChunkTargetNano += chunkDurationNs
                val sleepNs = nextChunkTargetNano - System.nanoTime()
                if (sleepNs > 1_000_000L) {
                    SystemClock.sleep(sleepNs / 1_000_000L)
                } else if (sleepNs < -chunkDurationNs * 3) {
                    nextChunkTargetNano = System.nanoTime()
                }
            } else {
                nextChunkTargetNano = loopStartNano + chunkDurationNs
            }

            if (muxerWrapper.isCurrentlyPaused()) {
                drainAudioCodecOutput(codec, bufferInfo)
                continue
            }

            val sysGain = internalGain
            val mGain = micGain

            // D. Mix Internal Stereo + Mic Stereo into 16-bit Little-Endian PCM
            val byteBuffer = ByteBuffer.wrap(mixedBytes).order(ByteOrder.LITTLE_ENDIAN)
            for (i in 0 until stereoFrameShorts) {
                val sysSample = if (hasInternalSamples) {
                    (internalStereoBuf[i] * sysGain).roundToInt()
                } else 0
                val micSample = if (hasMicSamples) {
                    (micStereoBuf[i] * mGain).roundToInt()
                } else 0
                val mixed = (sysSample + micSample).coerceIn(
                    Short.MIN_VALUE.toInt(),
                    Short.MAX_VALUE.toInt()
                ).toShort()
                byteBuffer.putShort(mixed)
            }

            val bytesToFeed = stereoFrameShorts * 2
            val ptsUs = System.nanoTime() / 1000L

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

        // Flush final audio frames on stop
        drainAudioCodecOutput(codec, bufferInfo)
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
