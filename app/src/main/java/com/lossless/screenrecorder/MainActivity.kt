package com.lossless.screenrecorder

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.media.projection.MediaProjectionManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.GraphicEq
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material.icons.filled.VideoLibrary
import androidx.compose.material3.Badge
import androidx.compose.material3.BadgedBox
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationRail
import androidx.compose.material3.NavigationRailItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.lossless.screenrecorder.service.QuickRecordTileService
import com.lossless.screenrecorder.service.ScreenRecordService
import com.lossless.screenrecorder.ui.RecorderUiState
import com.lossless.screenrecorder.ui.RecorderViewModel
import com.lossless.screenrecorder.ui.screens.AudioAndControlsScreen
import com.lossless.screenrecorder.ui.screens.EncoderConfigScreen
import com.lossless.screenrecorder.ui.screens.RecordingsLibraryScreen
import com.lossless.screenrecorder.ui.screens.TelemetryHeaderCard
import com.lossless.screenrecorder.ui.theme.LosslessRecorderTheme

enum class RecorderDestination(
    val routeId: String,
    val labelRes: Int,
    val icon: ImageVector
) {
    ENCODER("encoder_config", R.string.nav_encoder, Icons.Default.Tune),
    AUDIO_CONTROLS("audio_controls", R.string.nav_audio_controls, Icons.Default.GraphicEq),
    RECORDINGS("recordings_library", R.string.nav_recordings, Icons.Default.VideoLibrary)
}

class MainActivity : ComponentActivity() {

    private val viewModel: RecorderViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        setContent {
            LosslessRecorderTheme {
                val uiState by viewModel.uiState.collectAsStateWithLifecycle()
                val inspectionVideo by viewModel.selectedInspectionVideo.collectAsStateWithLifecycle()
                val context = LocalContext.current

                // MediaProjection Capture Launcher
                val mediaProjectionLauncher = rememberLauncherForActivityResult(
                    contract = ActivityResultContracts.StartActivityForResult()
                ) { result ->
                    if (result.resultCode == Activity.RESULT_OK && result.data != null) {
                        ScreenRecordService.startRecordingWithProjection(
                            context = context,
                            resultCode = result.resultCode,
                            resultData = result.data!!
                        )
                    } else {
                        viewModel.showBannerMessage("已取消螢幕擷取授權")
                    }
                }

                // Runtime Permissions (RECORD_AUDIO + POST_NOTIFICATIONS) Launcher
                val runtimePermissionsLauncher = rememberLauncherForActivityResult(
                    contract = ActivityResultContracts.RequestMultiplePermissions()
                ) {
                    val projectionManager =
                        context.getSystemService(MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
                    mediaProjectionLauncher.launch(projectionManager.createScreenCaptureIntent())
                }

                // Overlay Permission Launcher for Floating Control Ball
                val overlayPermissionLauncher = rememberLauncherForActivityResult(
                    contract = ActivityResultContracts.StartActivityForResult()
                ) {
                    viewModel.refreshOverlayPermissionState()
                    if (Settings.canDrawOverlays(context)) {
                        viewModel.setEnableFloatingBall(true)
                        viewModel.showBannerMessage("桌面懸浮球已啟用！您可隨時拖曳或點擊開錄。")
                    } else {
                        viewModel.showBannerMessage("未授予「顯示於其他應用程式上層」權限，無法啟用懸浮球。")
                    }
                }

                val triggerStartRecording = {
                    val missingPermissions = mutableListOf<String>()
                    if (uiState.config.audioSourceMode.needsMic &&
                        ContextCompat.checkSelfPermission(
                            context,
                            Manifest.permission.RECORD_AUDIO
                        ) != PackageManager.PERMISSION_GRANTED
                    ) {
                        missingPermissions.add(Manifest.permission.RECORD_AUDIO)
                    }
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
                        ContextCompat.checkSelfPermission(
                            context,
                            Manifest.permission.POST_NOTIFICATIONS
                        ) != PackageManager.PERMISSION_GRANTED
                    ) {
                        missingPermissions.add(Manifest.permission.POST_NOTIFICATIONS)
                    }

                    if (missingPermissions.isNotEmpty()) {
                        runtimePermissionsLauncher.launch(missingPermissions.toTypedArray())
                    } else {
                        val projectionManager =
                            context.getSystemService(MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
                        mediaProjectionLauncher.launch(projectionManager.createScreenCaptureIntent())
                    }
                }

                LosslessRecorderApp(
                    uiState = uiState,
                    selectedInspectionVideo = inspectionVideo,
                    viewModel = viewModel,
                    onStartRecording = triggerStartRecording,
                    onPauseRecording = { ScreenRecordService.sendPauseIntent(context) },
                    onResumeRecording = { ScreenRecordService.sendResumeIntent(context) },
                    onStopRecording = { ScreenRecordService.sendStopIntent(context) },
                    onRequestOverlayPermission = {
                        val intent = Intent(
                            Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                            Uri.parse("package:${context.packageName}")
                        )
                        overlayPermissionLauncher.launch(intent)
                    },
                    onAddQuickSettingsTile = {
                        QuickRecordTileService.requestAddTile(context) { message ->
                            viewModel.showBannerMessage(message)
                        }
                    },
                    onPlayVideo = { item ->
                        runCatching {
                            val playIntent = Intent(Intent.ACTION_VIEW).apply {
                                setDataAndType(Uri.parse(item.uriString), "video/mp4")
                                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                            }
                            context.startActivity(playIntent)
                        }.onFailure {
                            viewModel.showBannerMessage("無法開啟外部播放器，請先安裝支援 HEVC/MP4 的影片播放器。")
                        }
                    },
                    onShareVideo = { item ->
                        runCatching {
                            val shareIntent = Intent(Intent.ACTION_SEND).apply {
                                type = "video/mp4"
                                putExtra(Intent.EXTRA_STREAM, Uri.parse(item.uriString))
                                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                            }
                            context.startActivity(
                                Intent.createChooser(shareIntent, item.displayName)
                            )
                        }
                    }
                )
            }
        }
    }

    override fun onResume() {
        super.onResume()
        viewModel.refreshOverlayPermissionState()
        viewModel.refreshRecordings()
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LosslessRecorderApp(
    uiState: RecorderUiState,
    selectedInspectionVideo: com.lossless.screenrecorder.model.RecordedVideoItem?,
    viewModel: RecorderViewModel,
    onStartRecording: () -> Unit,
    onPauseRecording: () -> Unit,
    onResumeRecording: () -> Unit,
    onStopRecording: () -> Unit,
    onRequestOverlayPermission: () -> Unit,
    onAddQuickSettingsTile: () -> Unit,
    onPlayVideo: (com.lossless.screenrecorder.model.RecordedVideoItem) -> Unit,
    onShareVideo: (com.lossless.screenrecorder.model.RecordedVideoItem) -> Unit
) {
    var currentTab by rememberSaveable { mutableStateOf(RecorderDestination.ENCODER) }
    val snackbarHostState = remember { SnackbarHostState() }

    if (currentTab != RecorderDestination.ENCODER) {
        BackHandler {
            currentTab = RecorderDestination.ENCODER
        }
    }

    LaunchedEffect(uiState.statusBannerMessage) {
        val msg = uiState.statusBannerMessage
        if (!msg.isNullOrEmpty()) {
            snackbarHostState.showSnackbar(msg)
            viewModel.showBannerMessage(null)
        }
    }

    BoxWithConstraints(modifier = Modifier.fillMaxSize()) {
        val isExpandedScreen = maxWidth >= 720.dp

        Scaffold(
            modifier = Modifier.fillMaxSize(),
            contentWindowInsets = WindowInsets.safeDrawing,
            topBar = {
                TopAppBar(
                    title = {
                        Column {
                            Text(
                                text = stringResource(R.string.app_name),
                                style = MaterialTheme.typography.titleLarge,
                                color = MaterialTheme.colorScheme.onBackground,
                                fontWeight = FontWeight.Bold
                            )
                            Text(
                                text = stringResource(R.string.app_subtitle),
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.primary
                            )
                        }
                    },
                    actions = {
                        Surface(
                            color = MaterialTheme.colorScheme.primaryContainer,
                            shape = MaterialTheme.shapes.extraSmall,
                            modifier = Modifier.padding(end = 12.dp)
                        ) {
                            Text(
                                text = "NATIVE ${uiState.alignedResolution.displayText}",
                                style = MaterialTheme.typography.labelMedium,
                                color = MaterialTheme.colorScheme.primary,
                                modifier = Modifier.padding(horizontal = 10.dp, vertical = 5.dp)
                            )
                        }
                    },
                    colors = TopAppBarDefaults.topAppBarColors(
                        containerColor = MaterialTheme.colorScheme.background
                    )
                )
            },
            snackbarHost = { SnackbarHost(snackbarHostState) },
            bottomBar = {
                if (!isExpandedScreen) {
                    NavigationBar(
                        containerColor = MaterialTheme.colorScheme.surface
                    ) {
                        RecorderDestination.entries.forEach { dest ->
                            NavigationBarItem(
                                selected = currentTab == dest,
                                onClick = { currentTab = dest },
                                icon = {
                                    if (dest == RecorderDestination.RECORDINGS && uiState.recordings.isNotEmpty()) {
                                        BadgedBox(
                                            badge = {
                                                Badge {
                                                    Text("${uiState.recordings.size}")
                                                }
                                            }
                                        ) {
                                            Icon(
                                                imageVector = dest.icon,
                                                contentDescription = stringResource(dest.labelRes)
                                            )
                                        }
                                    } else {
                                        Icon(
                                            imageVector = dest.icon,
                                            contentDescription = stringResource(dest.labelRes)
                                        )
                                    }
                                },
                                label = {
                                    Text(
                                        text = stringResource(dest.labelRes),
                                        style = MaterialTheme.typography.labelMedium
                                    )
                                },
                                modifier = Modifier.testTag("nav_tab_${dest.routeId}")
                            )
                        }
                    }
                }
            }
        ) { innerPadding ->
            if (isExpandedScreen) {
                // Canonical Tablet / Landscape Layout: NavigationRail + Side-by-Side Telemetry & Settings
                Row(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(innerPadding)
                ) {
                    NavigationRail(
                        containerColor = MaterialTheme.colorScheme.surface
                    ) {
                        RecorderDestination.entries.forEach { dest ->
                            NavigationRailItem(
                                selected = currentTab == dest,
                                onClick = { currentTab = dest },
                                icon = {
                                    Icon(
                                        imageVector = dest.icon,
                                        contentDescription = stringResource(dest.labelRes)
                                    )
                                },
                                label = {
                                    Text(
                                        text = stringResource(dest.labelRes),
                                        style = MaterialTheme.typography.labelSmall
                                    )
                                },
                                modifier = Modifier.testTag("nav_rail_${dest.routeId}")
                            )
                        }
                    }

                    Row(
                        modifier = Modifier
                            .fillMaxSize()
                            .padding(horizontal = 16.dp, vertical = 8.dp),
                        horizontalArrangement = Arrangement.spacedBy(16.dp)
                    ) {
                        Column(
                            modifier = Modifier
                                .weight(0.42f)
                                .fillMaxHeight()
                                .verticalScroll(rememberScrollState())
                        ) {
                            TelemetryHeaderCard(
                                capabilities = uiState.capabilities,
                                config = uiState.config,
                                alignedResolution = uiState.alignedResolution,
                                sessionState = uiState.sessionState,
                                onStartRecordingClick = onStartRecording,
                                onPauseRecordingClick = onPauseRecording,
                                onResumeRecordingClick = onResumeRecording,
                                onStopRecordingClick = onStopRecording
                            )
                        }

                        Column(
                            modifier = Modifier
                                .weight(0.58f)
                                .fillMaxHeight()
                                .verticalScroll(rememberScrollState())
                        ) {
                            ActiveDestinationContent(
                                currentTab = currentTab,
                                uiState = uiState,
                                selectedInspectionVideo = selectedInspectionVideo,
                                viewModel = viewModel,
                                onRequestOverlayPermission = onRequestOverlayPermission,
                                onAddQuickSettingsTile = onAddQuickSettingsTile,
                                onPlayVideo = onPlayVideo,
                                onShareVideo = onShareVideo
                            )
                            Spacer(modifier = Modifier.height(24.dp))
                        }
                    }
                }
            } else {
                // Compact Mobile Layout: Scrollable Column with Telemetry Header + Active Tab Content
                Column(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(innerPadding)
                        .verticalScroll(rememberScrollState())
                        .padding(horizontal = 16.dp, vertical = 8.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(16.dp)
                ) {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .widthIn(max = 600.dp),
                        verticalArrangement = Arrangement.spacedBy(16.dp)
                    ) {
                        TelemetryHeaderCard(
                            capabilities = uiState.capabilities,
                            config = uiState.config,
                            alignedResolution = uiState.alignedResolution,
                            sessionState = uiState.sessionState,
                            onStartRecordingClick = onStartRecording,
                            onPauseRecordingClick = onPauseRecording,
                            onResumeRecordingClick = onResumeRecording,
                            onStopRecordingClick = onStopRecording
                        )

                        ActiveDestinationContent(
                            currentTab = currentTab,
                            uiState = uiState,
                            selectedInspectionVideo = selectedInspectionVideo,
                            viewModel = viewModel,
                            onRequestOverlayPermission = onRequestOverlayPermission,
                            onAddQuickSettingsTile = onAddQuickSettingsTile,
                            onPlayVideo = onPlayVideo,
                            onShareVideo = onShareVideo
                        )

                        Spacer(modifier = Modifier.height(16.dp))
                    }
                }
            }
        }
    }
}

@Composable
private fun ActiveDestinationContent(
    currentTab: RecorderDestination,
    uiState: RecorderUiState,
    selectedInspectionVideo: com.lossless.screenrecorder.model.RecordedVideoItem?,
    viewModel: RecorderViewModel,
    onRequestOverlayPermission: () -> Unit,
    onAddQuickSettingsTile: () -> Unit,
    onPlayVideo: (com.lossless.screenrecorder.model.RecordedVideoItem) -> Unit,
    onShareVideo: (com.lossless.screenrecorder.model.RecordedVideoItem) -> Unit
) {
    when (currentTab) {
        RecorderDestination.ENCODER -> {
            EncoderConfigScreen(
                capabilities = uiState.capabilities,
                config = uiState.config,
                alignedResolution = uiState.alignedResolution,
                onResolutionModeChange = viewModel::setResolutionMode,
                onCodecTypeChange = viewModel::setCodecType,
                onBitrateChange = viewModel::setBitrateMbps,
                onFrameRateChange = viewModel::setFrameRate,
                onBitrateControlModeChange = viewModel::setBitrateControlMode,
                onKeyFrameIntervalModeChange = viewModel::setKeyFrameIntervalMode,
                onEnable10BitChange = viewModel::setEnable10BitColor
            )
        }
        RecorderDestination.AUDIO_CONTROLS -> {
            AudioAndControlsScreen(
                config = uiState.config,
                canDrawOverlays = uiState.canDrawOverlays,
                onAudioSourceModeChange = viewModel::setAudioSourceMode,
                onInternalGainChange = viewModel::setInternalAudioGain,
                onMicGainChange = viewModel::setMicAudioGain,
                onEnableNotificationChange = viewModel::setEnableNotificationController,
                onEnableFloatingBallChange = viewModel::setEnableFloatingBall,
                onHideFloatingBallOnRecordChange = viewModel::setHideFloatingBallWhileRecording,
                onRequestOverlayPermission = onRequestOverlayPermission,
                onAddQuickSettingsTileClick = onAddQuickSettingsTile,
                onCountdownSecondsChange = viewModel::setCountdownSeconds,
                onAutoStopScreenOffChange = viewModel::setAutoStopOnScreenOff
            )
        }
        RecorderDestination.RECORDINGS -> {
            RecordingsLibraryScreen(
                recordings = uiState.recordings,
                selectedInspectionVideo = selectedInspectionVideo,
                onRefreshClick = viewModel::refreshRecordings,
                onPlayVideoClick = onPlayVideo,
                onInspectVideoClick = viewModel::selectVideoForInspection,
                onShareVideoClick = onShareVideo,
                onDeleteVideoClick = viewModel::deleteVideo
            )
        }
    }
}
