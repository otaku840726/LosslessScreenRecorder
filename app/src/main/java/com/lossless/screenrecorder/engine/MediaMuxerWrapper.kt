package com.lossless.screenrecorder.engine

import android.content.ContentValues
import android.content.Context
import android.media.MediaCodec
import android.media.MediaFormat
import android.media.MediaMuxer
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.os.ParcelFileDescriptor
import android.os.SystemClock
import android.provider.MediaStore
import java.io.File
import java.nio.ByteBuffer
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong

class MediaMuxerWrapper(
    private val context: Context,
    private val expectAudioTrack: Boolean,
    private val codecShortTag: String
) {
    private val lock = Any()
    private var mediaMuxer: MediaMuxer? = null
    private var outputPfd: ParcelFileDescriptor? = null
    var outputContentUri: Uri? = null
        private set
    var outputFileName: String = ""
        private set

    private var videoTrackIndex: Int = -1
    private var audioTrackIndex: Int = -1
    private var isMuxerStarted = false
    private val isStopped = AtomicBoolean(false)

    private var lastVideoPtsUs: Long = 0L
    private var lastAudioPtsUs: Long = 0L

    private val isPaused = AtomicBoolean(false)
    private var pauseStartTimeElapsedNs: Long = 0L
    private var totalPausedDurationUs: Long = 0L

    private val totalBytesWritten = AtomicLong(0L)

    init {
        initializeOutputTarget()
    }

    private fun initializeOutputTarget() {
        val timestamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())
        outputFileName = "Lossless_${codecShortTag}_$timestamp.mp4"

        val resolver = context.contentResolver
        val collection = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            MediaStore.Video.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
        } else {
            MediaStore.Video.Media.EXTERNAL_CONTENT_URI
        }

        val values = ContentValues().apply {
            put(MediaStore.Video.Media.DISPLAY_NAME, outputFileName)
            put(MediaStore.Video.Media.MIME_TYPE, "video/mp4")
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                put(
                    MediaStore.Video.Media.RELATIVE_PATH,
                    "${Environment.DIRECTORY_MOVIES}/LosslessRecorder"
                )
                put(MediaStore.Video.Media.IS_PENDING, 1)
            }
        }

        val insertedUri = runCatching { resolver.insert(collection, values) }.getOrNull()
        if (insertedUri != null) {
            val pfd = runCatching { resolver.openFileDescriptor(insertedUri, "rw") }.getOrNull()
            if (pfd != null) {
                outputContentUri = insertedUri
                outputPfd = pfd
                mediaMuxer = MediaMuxer(pfd.fileDescriptor, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
                return
            }
        }

        // Fallback to app external Movies directory if MediaStore insert fails in restricted containers
        val fallbackDir = File(
            context.getExternalFilesDir(Environment.DIRECTORY_MOVIES),
            "LosslessRecorder"
        ).apply { mkdirs() }
        val fallbackFile = File(fallbackDir, outputFileName)
        outputContentUri = Uri.fromFile(fallbackFile)
        mediaMuxer = MediaMuxer(fallbackFile.absolutePath, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
    }

    fun addVideoTrack(format: MediaFormat) {
        synchronized(lock) {
            if (isStopped.get() || isMuxerStarted) return
            videoTrackIndex = mediaMuxer?.addTrack(format) ?: -1
            checkStartMuxerLocked()
        }
    }

    fun addAudioTrack(format: MediaFormat) {
        synchronized(lock) {
            if (isStopped.get() || isMuxerStarted) return
            audioTrackIndex = mediaMuxer?.addTrack(format) ?: -1
            checkStartMuxerLocked()
        }
    }

    fun disableAudioTrackExpectation() {
        synchronized(lock) {
            if (isStopped.get() || isMuxerStarted) return
            audioTrackIndex = -2 // Sentinel indicating audio fell back to muted
            checkStartMuxerLocked()
        }
    }

    private fun checkStartMuxerLocked() {
        if (isMuxerStarted) return
        val hasVideo = videoTrackIndex >= 0
        val hasAudioOrNotExpected = !expectAudioTrack || audioTrackIndex >= 0 || audioTrackIndex == -2
        if (hasVideo && hasAudioOrNotExpected) {
            runCatching {
                mediaMuxer?.start()
                isMuxerStarted = true
            }
        }
    }

    fun setPaused(paused: Boolean) {
        synchronized(lock) {
            if (paused && !isPaused.get()) {
                isPaused.set(true)
                pauseStartTimeElapsedNs = SystemClock.elapsedRealtimeNanos()
            } else if (!paused && isPaused.get()) {
                val nowNs = SystemClock.elapsedRealtimeNanos()
                val deltaUs = (nowNs - pauseStartTimeElapsedNs) / 1000L
                if (deltaUs > 0) {
                    totalPausedDurationUs += deltaUs
                }
                isPaused.set(false)
            }
        }
    }

    fun writeVideoSample(byteBuf: ByteBuffer, bufferInfo: MediaCodec.BufferInfo) {
        synchronized(lock) {
            if (isStopped.get() || !isMuxerStarted || videoTrackIndex < 0) return
            if (isPaused.get() && (bufferInfo.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG) == 0) {
                return
            }
            if (bufferInfo.size <= 0) return

            val adjustedPts = (bufferInfo.presentationTimeUs - totalPausedDurationUs)
                .coerceAtLeast(lastVideoPtsUs + 100L)
            lastVideoPtsUs = adjustedPts

            val adjustedInfo = MediaCodec.BufferInfo().apply {
                set(bufferInfo.offset, bufferInfo.size, adjustedPts, bufferInfo.flags)
            }

            runCatching {
                mediaMuxer?.writeSampleData(videoTrackIndex, byteBuf, adjustedInfo)
                totalBytesWritten.addAndGet(bufferInfo.size.toLong())
            }
        }
    }

    fun writeAudioSample(byteBuf: ByteBuffer, bufferInfo: MediaCodec.BufferInfo) {
        synchronized(lock) {
            if (isStopped.get() || !isMuxerStarted || audioTrackIndex < 0) return
            if (isPaused.get() && (bufferInfo.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG) == 0) {
                return
            }
            if (bufferInfo.size <= 0) return

            val adjustedPts = (bufferInfo.presentationTimeUs - totalPausedDurationUs)
                .coerceAtLeast(lastAudioPtsUs + 100L)
            lastAudioPtsUs = adjustedPts

            val adjustedInfo = MediaCodec.BufferInfo().apply {
                set(bufferInfo.offset, bufferInfo.size, adjustedPts, bufferInfo.flags)
            }

            runCatching {
                mediaMuxer?.writeSampleData(audioTrackIndex, byteBuf, adjustedInfo)
                totalBytesWritten.addAndGet(bufferInfo.size.toLong())
            }
        }
    }

    fun getBytesWritten(): Long = totalBytesWritten.get()

    fun stopAndRelease() {
        synchronized(lock) {
            if (isStopped.getAndSet(true)) return
            try {
                if (isMuxerStarted) {
                    runCatching { mediaMuxer?.stop() }
                }
            } finally {
                runCatching { mediaMuxer?.release() }
                mediaMuxer = null
                runCatching { outputPfd?.close() }
                outputPfd = null

                // Publish pending MediaStore item so it appears immediately in gallery & library
                val uri = outputContentUri
                if (uri != null && uri.scheme == "content" && Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    val values = ContentValues().apply {
                        put(MediaStore.Video.Media.IS_PENDING, 0)
                    }
                    runCatching {
                        context.contentResolver.update(uri, values, null, null)
                    }
                }
            }
        }
    }
}
