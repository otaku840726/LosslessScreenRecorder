package com.lossless.screenrecorder.ui.screens

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Analytics
import androidx.compose.material.icons.filled.DeleteOutline
import androidx.compose.material.icons.filled.PlayCircleOutline
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Share
import androidx.compose.material.icons.filled.VideoFile
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.lossless.screenrecorder.R
import com.lossless.screenrecorder.model.RecordedVideoItem
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@Composable
fun RecordingsLibraryScreen(
    recordings: List<RecordedVideoItem>,
    selectedInspectionVideo: RecordedVideoItem?,
    onRefreshClick: () -> Unit,
    onPlayVideoClick: (RecordedVideoItem) -> Unit,
    onInspectVideoClick: (RecordedVideoItem?) -> Unit,
    onShareVideoClick: (RecordedVideoItem) -> Unit,
    onDeleteVideoClick: (RecordedVideoItem) -> Unit,
    modifier: Modifier = Modifier
) {
    if (selectedInspectionVideo != null) {
        BackHandler {
            onInspectVideoClick(null)
        }
        VideoSpecInspectionDialog(
            video = selectedInspectionVideo,
            onDismiss = { onInspectVideoClick(null) },
            onPlayClick = {
                onInspectVideoClick(null)
                onPlayVideoClick(selectedInspectionVideo)
            }
        )
    }

    Column(
        modifier = modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = stringResource(R.string.library_title),
                style = MaterialTheme.typography.titleLarge,
                color = MaterialTheme.colorScheme.onSurface
            )
            OutlinedButton(
                onClick = onRefreshClick,
                modifier = Modifier.testTag("refresh_recordings_button")
            ) {
                Icon(
                    imageVector = Icons.Default.Refresh,
                    contentDescription = stringResource(R.string.library_refresh),
                    modifier = Modifier.size(16.dp)
                )
                Spacer(modifier = Modifier.width(6.dp))
                Text(
                    text = stringResource(R.string.library_refresh),
                    style = MaterialTheme.typography.labelMedium
                )
            }
        }

        if (recordings.isEmpty()) {
            Card(
                modifier = Modifier
                    .fillMaxWidth()
                    .testTag("empty_recordings_card"),
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.surface
                ),
                border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant)
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(28.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    Icon(
                        imageVector = Icons.Default.VideoFile,
                        contentDescription = null,
                        modifier = Modifier.size(48.dp),
                        tint = MaterialTheme.colorScheme.primary
                    )
                    Text(
                        text = stringResource(R.string.library_empty_title),
                        style = MaterialTheme.typography.titleMedium,
                        color = MaterialTheme.colorScheme.onSurface,
                        fontWeight = FontWeight.Bold
                    )
                    Text(
                        text = stringResource(R.string.library_empty_subtitle),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        textAlign = TextAlign.Center
                    )
                }
            }
        } else {
            recordings.forEach { video ->
                RecordedVideoCard(
                    video = video,
                    onPlayClick = { onPlayVideoClick(video) },
                    onInspectClick = { onInspectVideoClick(video) },
                    onShareClick = { onShareVideoClick(video) },
                    onDeleteClick = { onDeleteVideoClick(video) }
                )
            }
        }
    }
}

@Composable
private fun RecordedVideoCard(
    video: RecordedVideoItem,
    onPlayClick: () -> Unit,
    onInspectClick: () -> Unit,
    onShareClick: () -> Unit,
    onDeleteClick: () -> Unit
) {
    val sizeMb = String.format(Locale.US, "%.1f MB", video.sizeBytes / (1024f * 1024f))
    val bitrateStr = String.format(Locale.US, "%.1f Mbps", video.bitrateMbps)
    val dateStr = SimpleDateFormat("yyyy/MM/dd HH:mm", Locale.getDefault())
        .format(Date(video.dateAddedMillis))

    Card(
        modifier = Modifier
            .fillMaxWidth()
            .testTag("recording_item_${video.id}"),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surface
        ),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
        shape = MaterialTheme.shapes.medium
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(14.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = video.displayName,
                        style = MaterialTheme.typography.titleMedium,
                        color = MaterialTheme.colorScheme.onSurface,
                        fontWeight = FontWeight.Bold,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    Text(
                        text = "$dateStr · 時長 ${formatDurationMillis(video.durationMillis)}",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }

                Surface(
                    color = MaterialTheme.colorScheme.primaryContainer,
                    shape = MaterialTheme.shapes.extraSmall
                ) {
                    Text(
                        text = video.codecLabel,
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp)
                    )
                }
            }

            // Hardware Spec Telemetry Badges
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                SpecPillBadge(text = "${video.width} × ${video.height}")
                SpecPillBadge(text = "${video.frameRate} FPS")
                SpecPillBadge(text = bitrateStr)
                SpecPillBadge(text = sizeMb)
            }

            // Action Buttons
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    FilledTonalButton(
                        onClick = onPlayClick,
                        modifier = Modifier.testTag("play_video_${video.id}")
                    ) {
                        Icon(
                            imageVector = Icons.Default.PlayCircleOutline,
                            contentDescription = stringResource(R.string.video_action_play),
                            modifier = Modifier.size(18.dp)
                        )
                        Spacer(modifier = Modifier.width(6.dp))
                        Text(
                            text = stringResource(R.string.video_action_play),
                            style = MaterialTheme.typography.labelLarge
                        )
                    }

                    OutlinedButton(
                        onClick = onInspectClick,
                        modifier = Modifier.testTag("inspect_video_${video.id}")
                    ) {
                        Icon(
                            imageVector = Icons.Default.Analytics,
                            contentDescription = stringResource(R.string.video_action_inspect),
                            modifier = Modifier.size(18.dp)
                        )
                        Spacer(modifier = Modifier.width(6.dp))
                        Text(
                            text = stringResource(R.string.video_action_inspect),
                            style = MaterialTheme.typography.labelLarge
                        )
                    }
                }

                Row {
                    IconButton(
                        onClick = onShareClick,
                        modifier = Modifier.testTag("share_video_${video.id}")
                    ) {
                        Icon(
                            imageVector = Icons.Default.Share,
                            contentDescription = stringResource(R.string.video_action_share),
                            tint = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    IconButton(
                        onClick = onDeleteClick,
                        modifier = Modifier.testTag("delete_video_${video.id}")
                    ) {
                        Icon(
                            imageVector = Icons.Default.DeleteOutline,
                            contentDescription = stringResource(R.string.video_action_delete),
                            tint = MaterialTheme.colorScheme.secondary
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun SpecPillBadge(text: String) {
    Surface(
        color = MaterialTheme.colorScheme.surfaceVariant,
        shape = MaterialTheme.shapes.extraSmall
    ) {
        Text(
            text = text,
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurface,
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp)
        )
    }
}

@Composable
private fun VideoSpecInspectionDialog(
    video: RecordedVideoItem,
    onDismiss: () -> Unit,
    onPlayClick: () -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Text(
                text = "無損封裝規格檢驗報告",
                style = MaterialTheme.typography.titleLarge,
                color = MaterialTheme.colorScheme.primary
            )
        },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                InspectionDetailRow("檔案名稱", video.displayName)
                InspectionDetailRow("實際輸出物理像素", "${video.width} × ${video.height} px")
                InspectionDetailRow("視訊編碼器", video.codecLabel)
                InspectionDetailRow(
                    "實測平均碼率",
                    String.format(Locale.US, "%.2f Mbps", video.bitrateMbps)
                )
                InspectionDetailRow("封裝幀率", "${video.frameRate} FPS")
                InspectionDetailRow("影片時長", formatDurationMillis(video.durationMillis))
                InspectionDetailRow(
                    "檔案大小",
                    String.format(
                        Locale.US,
                        "%.2f MB (%,d bytes)",
                        video.sizeBytes / (1024f * 1024f),
                        video.sizeBytes
                    )
                )
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    text = "儲存路徑：Movies/LosslessRecorder/${video.displayName}",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        },
        confirmButton = {
            FilledTonalButton(
                onClick = onPlayClick,
                modifier = Modifier.testTag("dialog_play_button")
            ) {
                Text("開啟外部播放器預覽")
            }
        },
        dismissButton = {
            TextButton(
                onClick = onDismiss,
                modifier = Modifier.testTag("dialog_close_button")
            ) {
                Text("關閉")
            }
        }
    )
}

@Composable
private fun InspectionDetailRow(label: String, value: String) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Text(
            text = value,
            style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.onSurface
        )
    }
}
