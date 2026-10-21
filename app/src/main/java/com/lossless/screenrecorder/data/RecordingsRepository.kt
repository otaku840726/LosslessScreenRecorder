package com.lossless.screenrecorder.data

import android.content.ContentUris
import android.content.Context
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.os.Build
import android.provider.MediaStore
import com.lossless.screenrecorder.model.RecordedVideoItem
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlin.math.roundToInt

class RecordingsRepository(private val context: Context) {

    suspend fun loadRecordings(): List<RecordedVideoItem> = withContext(Dispatchers.IO) {
        val items = mutableListOf<RecordedVideoItem>()
        val collection = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            MediaStore.Video.Media.getContentUri(MediaStore.VOLUME_EXTERNAL)
        } else {
            MediaStore.Video.Media.EXTERNAL_CONTENT_URI
        }

        val projection = arrayOf(
            MediaStore.Video.Media._ID,
            MediaStore.Video.Media.DISPLAY_NAME,
            MediaStore.Video.Media.WIDTH,
            MediaStore.Video.Media.HEIGHT,
            MediaStore.Video.Media.DURATION,
            MediaStore.Video.Media.SIZE,
            MediaStore.Video.Media.MIME_TYPE,
            MediaStore.Video.Media.DATE_ADDED,
            MediaStore.Video.Media.RELATIVE_PATH
        )

        val selection = "${MediaStore.Video.Media.DISPLAY_NAME} LIKE ? OR ${MediaStore.Video.Media.RELATIVE_PATH} LIKE ?"
        val selectionArgs = arrayOf("Lossless_%", "%LosslessRecorder%")
        val sortOrder = "${MediaStore.Video.Media.DATE_ADDED} DESC"

        runCatching {
            context.contentResolver.query(
                collection,
                projection,
                selection,
                selectionArgs,
                sortOrder
            )?.use { cursor ->
                val idCol = cursor.getColumnIndexOrThrow(MediaStore.Video.Media._ID)
                val nameCol = cursor.getColumnIndexOrThrow(MediaStore.Video.Media.DISPLAY_NAME)
                val widthCol = cursor.getColumnIndexOrThrow(MediaStore.Video.Media.WIDTH)
                val heightCol = cursor.getColumnIndexOrThrow(MediaStore.Video.Media.HEIGHT)
                val durationCol = cursor.getColumnIndexOrThrow(MediaStore.Video.Media.DURATION)
                val sizeCol = cursor.getColumnIndexOrThrow(MediaStore.Video.Media.SIZE)
                val dateCol = cursor.getColumnIndexOrThrow(MediaStore.Video.Media.DATE_ADDED)

                while (cursor.moveToNext()) {
                    val id = cursor.getLong(idCol)
                    val contentUri = ContentUris.withAppendedId(collection, id)
                    val displayName = cursor.getString(nameCol) ?: "Lossless_$id.mp4"
                    var width = cursor.getInt(widthCol)
                    var height = cursor.getInt(heightCol)
                    var durationMs = cursor.getLong(durationCol)
                    val sizeBytes = cursor.getLong(sizeCol)
                    val dateAddedSec = cursor.getLong(dateCol)

                    var bitrateMbps = 0f
                    var frameRate = 60
                    var codecLabel = "HEVC / MP4"

                    // Inspect detailed container metadata with MediaMetadataRetriever
                    val retriever = MediaMetadataRetriever()
                    try {
                        retriever.setDataSource(context, contentUri)
                        if (width <= 0) {
                            width = retriever.extractMetadata(
                                MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH
                            )?.toIntOrNull() ?: 0
                        }
                        if (height <= 0) {
                            height = retriever.extractMetadata(
                                MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT
                            )?.toIntOrNull() ?: 0
                        }
                        if (durationMs <= 0L) {
                            durationMs = retriever.extractMetadata(
                                MediaMetadataRetriever.METADATA_KEY_DURATION
                            )?.toLongOrNull() ?: 0L
                        }
                        val bitrateBps = retriever.extractMetadata(
                            MediaMetadataRetriever.METADATA_KEY_BITRATE
                        )?.toLongOrNull() ?: 0L
                        if (bitrateBps > 0L) {
                            bitrateMbps = bitrateBps / 1_000_000f
                        } else if (durationMs > 0L && sizeBytes > 0L) {
                            bitrateMbps = ((sizeBytes * 8f) / (durationMs / 1000f)) / 1_000_000f
                        }

                        val captureFps = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                            retriever.extractMetadata(
                                MediaMetadataRetriever.METADATA_KEY_CAPTURE_FRAMERATE
                            )?.toFloatOrNull()?.roundToInt()
                        } else null

                        val frameCount = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                            retriever.extractMetadata(
                                MediaMetadataRetriever.METADATA_KEY_VIDEO_FRAME_COUNT
                            )?.toIntOrNull()
                        } else null

                        if (captureFps != null && captureFps > 0) {
                            frameRate = captureFps
                        } else if (frameCount != null && durationMs > 0L) {
                            frameRate = ((frameCount * 1000f) / durationMs).roundToInt().coerceIn(24, 144)
                        }

                        codecLabel = if (displayName.contains("AVC", ignoreCase = true)) {
                            "H.264 (AVC)"
                        } else {
                            "H.265 (HEVC)"
                        }
                    } catch (_: Exception) {
                        if (durationMs > 0L && sizeBytes > 0L) {
                            bitrateMbps = ((sizeBytes * 8f) / (durationMs / 1000f)) / 1_000_000f
                        }
                    } finally {
                        runCatching { retriever.release() }
                    }

                    items.add(
                        RecordedVideoItem(
                            id = id,
                            uriString = contentUri.toString(),
                            displayName = displayName,
                            width = width,
                            height = height,
                            durationMillis = durationMs,
                            sizeBytes = sizeBytes,
                            bitrateMbps = bitrateMbps,
                            frameRate = frameRate,
                            codecLabel = codecLabel,
                            dateAddedMillis = dateAddedSec * 1000L
                        )
                    )
                }
            }
        }
        items
    }

    suspend fun deleteRecording(uriString: String): Boolean = withContext(Dispatchers.IO) {
        runCatching {
            val uri = Uri.parse(uriString)
            context.contentResolver.delete(uri, null, null) > 0
        }.getOrDefault(false)
    }
}
