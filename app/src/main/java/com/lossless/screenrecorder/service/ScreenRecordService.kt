package com.lossless.screenrecorder.service

import android.app.Activity
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.ServiceInfo
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.IBinder
import android.os.SystemClock
import androidx.core.app.NotificationCompat
import com.lossless.screenrecorder.MainActivity
import com.lossless.screenrecorder.R
import com.lossless.screenrecorder.data.RecorderPreferencesRepository
import com.lossless.screenrecorder.domain.DeviceCapabilityDetector
import com.lossless.screenrecorder.engine.AudioCaptureMixerEngine
import com.lossless.screenrecorder.engine.MediaMuxerWrapper
import com.lossless.screenrecorder.engine.VideoEncoderCore
import com.lossless.screenrecorder.model.AudioSourceMode
import com.lossless.screenrecorder.model.OverlayControlStyle
import com.lossless.screenrecorder.model.RecorderConfig
import com.lossless.screenrecorder.model.RecordingSessionState
import com.lossless.screenrecorder.model.SidebarTapBehavior
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.Locale

class ScreenRecordService : Service() {

    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    private lateinit var preferencesRepository: RecorderPreferencesRepository
    private lateinit var capabilityDetector: DeviceCapabilityDetector
    private lateinit var floatingControlManager: FloatingControlManager
    private lateinit var notificationManager: NotificationManager

    private var mediaProjection: MediaProjection? = null
    private var virtualDisplay: VirtualDisplay? = null
    private var videoEncoder: VideoEncoderCore? = null
    private var audioEngine: AudioCaptureMixerEngine? = null
    private var muxerWrapper: MediaMuxerWrapper? = null

    private var countdownJob: Job? = null
    private var tickerJob: Job? = null
    private var currentConfig: RecorderConfig = RecorderConfig()

    private var recordingStartRealtimeMs: Long = 0L
    private var pausedAccumulatedMs: Long = 0L
    private var pauseStartedAtMs: Long = 0L
    private var isPaused: Boolean = false
    private var activeResolutionLabel: String = ""
    private var isStopping = false

    private val screenOffReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (intent?.action == Intent.ACTION_SCREEN_OFF) {
                if (currentConfig.autoStopOnScreenOff &&
                    RecordingStateHolder.sessionState.value is RecordingSessionState.Recording
                ) {
                    stopRecordingSession()
                }
            }
        }
    }

    override fun onCreate() {
        super.onCreate()
        preferencesRepository = RecorderPreferencesRepository(applicationContext)
        capabilityDetector = DeviceCapabilityDetector(applicationContext)
        floatingControlManager = FloatingControlManager(applicationContext)
        notificationManager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager

        createNotificationChannel()
        registerReceiver(screenOffReceiver, IntentFilter(Intent.ACTION_SCREEN_OFF))

        RecordingStateHolder.onLiveGainChanged = { internalGain, micGain ->
            audioEngine?.internalGain = internalGain
            audioEngine?.micGain = micGain
        }

        serviceScope.launch {
            RecordingStateHolder.sessionState.collect { state ->
                floatingControlManager.updateForState(
                    state = state,
                    style = currentConfig.overlayControlStyle,
                    tapBehavior = currentConfig.sidebarTapBehavior,
                    hideOnRecord = currentConfig.hideFloatingBallWhileRecording
                )
            }
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_START_WITH_PROJECTION -> {
                val resultCode = intent.getIntExtra(EXTRA_RESULT_CODE, Activity.RESULT_CANCELED)
                val resultData: Intent? = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    intent.getParcelableExtra(EXTRA_RESULT_DATA, Intent::class.java)
                } else {
                    @Suppress("DEPRECATION")
                    intent.getParcelableExtra(EXTRA_RESULT_DATA)
                }
                if (resultCode == Activity.RESULT_OK && resultData != null) {
                    promoteToForeground(isMediaProjectionReady = true)
                    beginCountdownAndRecord(resultCode, resultData)
                }
            }
            ACTION_PAUSE_RECORDING -> {
                pauseRecordingSession()
            }
            ACTION_RESUME_RECORDING -> {
                resumeRecordingSession()
            }
            ACTION_STOP_RECORDING -> {
                stopRecordingSession()
            }
            ACTION_TOGGLE_FLOATING_BALL -> {
                val enable = intent.getBooleanExtra(EXTRA_ENABLE_FLOATING_BALL, true)
                val styleName = intent.getStringExtra(EXTRA_OVERLAY_STYLE)
                val tapName = intent.getStringExtra(EXTRA_SIDEBAR_TAP_BEHAVIOR)
                val hideOnRecord = intent.getBooleanExtra(EXTRA_HIDE_BALL_ON_RECORD, false)

                val style = styleName?.let {
                    runCatching { OverlayControlStyle.valueOf(it) }.getOrNull()
                } ?: currentConfig.overlayControlStyle

                val tapBehavior = tapName?.let {
                    runCatching { SidebarTapBehavior.valueOf(it) }.getOrNull()
                } ?: currentConfig.sidebarTapBehavior

                currentConfig = currentConfig.copy(
                    enableFloatingBall = enable,
                    overlayControlStyle = style,
                    sidebarTapBehavior = tapBehavior,
                    hideFloatingBallWhileRecording = hideOnRecord
                )

                if (enable && floatingControlManager.canDrawOverlays()) {
                    floatingControlManager.showOverlay(
                        style = style,
                        tapBehavior = tapBehavior,
                        hideOnRecord = hideOnRecord
                    )
                } else {
                    floatingControlManager.hideOverlay()
                }
            }
        }
        return START_NOT_STICKY
    }

    private fun promoteToForeground(isMediaProjectionReady: Boolean) {
        val notification = buildControllerNotification(RecordingStateHolder.sessionState.value)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q && isMediaProjectionReady) {
            startForeground(
                NOTIFICATION_ID,
                notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION
            )
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
    }

    private fun beginCountdownAndRecord(resultCode: Int, resultData: Intent) {
        countdownJob?.cancel()
        countdownJob = serviceScope.launch {
            currentConfig = preferencesRepository.configFlow.first()
            if (currentConfig.enableFloatingBall && floatingControlManager.canDrawOverlays()) {
                floatingControlManager.showOverlay(
                    style = currentConfig.overlayControlStyle,
                    tapBehavior = currentConfig.sidebarTapBehavior,
                    hideOnRecord = currentConfig.hideFloatingBallWhileRecording
                )
            }

            // 300ms delay so system notification panel / permission dialog finishes closing cleanly
            delay(300L)

            val countdownSecs = currentConfig.countdownSeconds.coerceIn(0, 10)
            for (remaining in countdownSecs downTo 1) {
                val cdState = RecordingSessionState.Countdown(remaining)
                RecordingStateHolder.updateState(cdState)
                updateNotification(cdState)
                delay(1000L)
            }

            startActualHardwarePipeline(resultCode, resultData)
        }
    }

    private suspend fun startActualHardwarePipeline(resultCode: Int, resultData: Intent) {
        isStopping = false
        val capabilities = capabilityDetector.detectCapabilities()
        val alignedRes = capabilityDetector.calculateAlignedResolution(capabilities, currentConfig)
        activeResolutionLabel = alignedRes.displayText

        if (currentConfig.hideFloatingBallWhileRecording) {
            floatingControlManager.updateForState(
                state = RecordingSessionState.Recording(
                    elapsedMillis = 0L,
                    bytesWritten = 0L,
                    isPaused = false,
                    resolutionLabel = activeResolutionLabel,
                    codecLabel = currentConfig.codecType.shortTag,
                    bitrateMbps = currentConfig.bitrateMbps
                ),
                style = currentConfig.overlayControlStyle,
                tapBehavior = currentConfig.sidebarTapBehavior,
                hideOnRecord = true
            )
            delay(60L)
        }

        try {
            val projectionManager =
                getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
            val projection = projectionManager.getMediaProjection(resultCode, resultData)
                ?: throw IllegalStateException("無法建立 MediaProjection 實例")

            projection.registerCallback(object : MediaProjection.Callback() {
                override fun onStop() {
                    if (!isStopping) {
                        stopRecordingSession()
                    }
                }
            }, null)

            val muxer = withContext(Dispatchers.IO) {
                MediaMuxerWrapper(
                    context = applicationContext,
                    expectAudioTrack = currentConfig.audioSourceMode != AudioSourceMode.MUTE,
                    codecShortTag = currentConfig.codecType.shortTag
                )
            }

            val vEncoder = withContext(Dispatchers.IO) {
                VideoEncoderCore(
                    config = currentConfig,
                    alignedResolution = alignedRes,
                    muxerWrapper = muxer
                )
            }

            val inputSurface = withContext(Dispatchers.IO) {
                vEncoder.prepare()
            }

            val aEngine = AudioCaptureMixerEngine(
                config = currentConfig,
                mediaProjection = projection,
                muxerWrapper = muxer
            )
            withContext(Dispatchers.IO) {
                aEngine.start()
            }

            val vDisplay = projection.createVirtualDisplay(
                "LosslessNativeDisplay",
                alignedRes.alignedWidth,
                alignedRes.alignedHeight,
                capabilities.densityDpi,
                DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
                inputSurface,
                null,
                null
            )

            mediaProjection = projection
            muxerWrapper = muxer
            videoEncoder = vEncoder
            virtualDisplay = vDisplay
            audioEngine = aEngine

            recordingStartRealtimeMs = SystemClock.elapsedRealtime()
            pausedAccumulatedMs = 0L
            isPaused = false

            startTelemetryTicker()
        } catch (e: Exception) {
            val errState = RecordingSessionState.Error(
                "啟動無損硬體編碼失敗: ${e.localizedMessage ?: "未知硬體錯誤"}"
            )
            RecordingStateHolder.updateState(errState)
            cleanupResourcesAsync()
        }
    }

    private fun startTelemetryTicker() {
        tickerJob?.cancel()
        tickerJob = serviceScope.launch {
            while (isActive) {
                val now = SystemClock.elapsedRealtime()
                val elapsed = if (isPaused) {
                    (pauseStartedAtMs - recordingStartRealtimeMs - pausedAccumulatedMs).coerceAtLeast(0L)
                } else {
                    (now - recordingStartRealtimeMs - pausedAccumulatedMs).coerceAtLeast(0L)
                }
                val bytes = muxerWrapper?.getBytesWritten() ?: 0L
                val recState = RecordingSessionState.Recording(
                    elapsedMillis = elapsed,
                    bytesWritten = bytes,
                    isPaused = isPaused,
                    resolutionLabel = activeResolutionLabel,
                    codecLabel = currentConfig.codecType.shortTag,
                    bitrateMbps = currentConfig.bitrateMbps
                )
                RecordingStateHolder.updateState(recState)
                updateNotification(recState)
                delay(500L)
            }
        }
    }

    private fun pauseRecordingSession() {
        if (isPaused || RecordingStateHolder.sessionState.value !is RecordingSessionState.Recording) return
        isPaused = true
        pauseStartedAtMs = SystemClock.elapsedRealtime()
        muxerWrapper?.setPaused(true)
    }

    private fun resumeRecordingSession() {
        if (!isPaused || RecordingStateHolder.sessionState.value !is RecordingSessionState.Recording) return
        val now = SystemClock.elapsedRealtime()
        pausedAccumulatedMs += (now - pauseStartedAtMs).coerceAtLeast(0L)
        isPaused = false
        muxerWrapper?.setPaused(false)
        videoEncoder?.requestSyncFrame()
    }

    private fun stopRecordingSession() {
        if (isStopping) return
        isStopping = true
        countdownJob?.cancel()
        tickerJob?.cancel()

        val savedFileName = muxerWrapper?.outputFileName ?: ""
        val savedUriStr = muxerWrapper?.outputContentUri?.toString() ?: ""

        RecordingStateHolder.updateState(RecordingSessionState.Finalizing)

        serviceScope.launch {
            cleanupResourcesAsync()
            if (savedFileName.isNotEmpty()) {
                RecordingStateHolder.updateState(
                    RecordingSessionState.Completed(
                        fileName = savedFileName,
                        contentUriString = savedUriStr
                    )
                )
                RecordingStateHolder.notifyRecordingSaved()
                delay(2000L)
            }
            RecordingStateHolder.updateState(RecordingSessionState.Idle)
            stopForeground(STOP_FOREGROUND_REMOVE)
        }
    }

    private suspend fun cleanupResourcesAsync() = withContext(Dispatchers.IO) {
        runCatching { virtualDisplay?.release() }
        virtualDisplay = null

        runCatching { videoEncoder?.stopAndRelease() }
        videoEncoder = null

        runCatching { audioEngine?.stopAndRelease() }
        audioEngine = null

        runCatching { muxerWrapper?.stopAndRelease() }
        muxerWrapper = null

        runCatching { mediaProjection?.stop() }
        mediaProjection = null
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                getString(R.string.notif_channel_name),
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = getString(R.string.notif_channel_desc)
                setShowBadge(false)
            }
            notificationManager.createNotificationChannel(channel)
        }
    }

    private fun updateNotification(state: RecordingSessionState) {
        if (!currentConfig.enableNotificationController) return
        runCatching {
            notificationManager.notify(NOTIFICATION_ID, buildControllerNotification(state))
        }
    }

    private fun buildControllerNotification(state: RecordingSessionState): Notification {
        val openAppIntent = PendingIntent.getActivity(
            this,
            100,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val stopIntent = PendingIntent.getService(
            this,
            101,
            Intent(this, ScreenRecordService::class.java).apply { action = ACTION_STOP_RECORDING },
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val pauseIntent = PendingIntent.getService(
            this,
            102,
            Intent(this, ScreenRecordService::class.java).apply { action = ACTION_PAUSE_RECORDING },
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val resumeIntent = PendingIntent.getService(
            this,
            103,
            Intent(this, ScreenRecordService::class.java).apply { action = ACTION_RESUME_RECORDING },
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val builder = NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_qs_tile_record)
            .setContentIntent(openAppIntent)
            .setOngoing(true)
            .setOnlyAlertOnce(true)

        when (state) {
            is RecordingSessionState.Recording -> {
                val timeStr = formatElapsed(state.elapsedMillis)
                val sizeMb = String.format(Locale.US, "%.1f MB", state.bytesWritten / (1024f * 1024f))
                builder.setContentTitle(
                    if (state.isPaused) getString(R.string.notif_title_paused, timeStr)
                    else getString(R.string.notif_title_recording, timeStr)
                )
                builder.setContentText("${state.resolutionLabel} · ${state.codecLabel} ${state.bitrateMbps}Mbps · $sizeMb")
                if (state.isPaused) {
                    builder.addAction(0, getString(R.string.btn_resume_recording), resumeIntent)
                } else {
                    builder.addAction(0, getString(R.string.btn_pause_recording), pauseIntent)
                }
                builder.addAction(0, getString(R.string.btn_stop_recording), stopIntent)
            }
            is RecordingSessionState.Countdown -> {
                builder.setContentTitle("倒數 ${state.remainingSeconds} 秒後啟動無損錄製...")
                builder.setContentText("點擊停止可取消本次錄影")
                builder.addAction(0, "取消錄影", stopIntent)
            }
            else -> {
                builder.setContentTitle(getString(R.string.notif_title_standby))
                builder.setContentText("硬體零拷貝管線已就緒")
            }
        }

        return builder.build()
    }

    private fun formatElapsed(millis: Long): String {
        val totalSecs = millis / 1000L
        val mins = totalSecs / 60L
        val secs = totalSecs % 60L
        return String.format(Locale.US, "%02d:%02d", mins, secs)
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        runCatching { unregisterReceiver(screenOffReceiver) }
        floatingControlManager.hideOverlay()
        RecordingStateHolder.onLiveGainChanged = null
        serviceScope.cancel()
        super.onDestroy()
    }

    companion object {
        private const val CHANNEL_ID = "lossless_screen_recorder_channel"
        private const val NOTIFICATION_ID = 4096

        const val ACTION_START_WITH_PROJECTION = "com.lossless.screenrecorder.action.START_WITH_PROJECTION"
        const val ACTION_PAUSE_RECORDING = "com.lossless.screenrecorder.action.PAUSE_RECORDING"
        const val ACTION_RESUME_RECORDING = "com.lossless.screenrecorder.action.RESUME_RECORDING"
        const val ACTION_STOP_RECORDING = "com.lossless.screenrecorder.action.STOP_RECORDING"
        const val ACTION_TOGGLE_FLOATING_BALL = "com.lossless.screenrecorder.action.TOGGLE_FLOATING_BALL"

        private const val EXTRA_RESULT_CODE = "extra_result_code"
        private const val EXTRA_RESULT_DATA = "extra_result_data"
        private const val EXTRA_ENABLE_FLOATING_BALL = "extra_enable_floating_ball"
        private const val EXTRA_OVERLAY_STYLE = "extra_overlay_style"
        private const val EXTRA_SIDEBAR_TAP_BEHAVIOR = "extra_sidebar_tap_behavior"
        private const val EXTRA_HIDE_BALL_ON_RECORD = "extra_hide_ball_on_record"

        fun startRecordingWithProjection(context: Context, resultCode: Int, resultData: Intent) {
            val intent = Intent(context, ScreenRecordService::class.java).apply {
                action = ACTION_START_WITH_PROJECTION
                putExtra(EXTRA_RESULT_CODE, resultCode)
                putExtra(EXTRA_RESULT_DATA, resultData)
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(intent)
            } else {
                context.startService(intent)
            }
        }

        fun sendPauseIntent(context: Context) {
            context.startService(
                Intent(context, ScreenRecordService::class.java).apply {
                    action = ACTION_PAUSE_RECORDING
                }
            )
        }

        fun sendResumeIntent(context: Context) {
            context.startService(
                Intent(context, ScreenRecordService::class.java).apply {
                    action = ACTION_RESUME_RECORDING
                }
            )
        }

        fun sendStopIntent(context: Context) {
            context.startService(
                Intent(context, ScreenRecordService::class.java).apply {
                    action = ACTION_STOP_RECORDING
                }
            )
        }

        fun syncFloatingOverlay(
            context: Context,
            enable: Boolean,
            style: OverlayControlStyle,
            tapBehavior: SidebarTapBehavior,
            hideOnRecord: Boolean
        ) {
            context.startService(
                Intent(context, ScreenRecordService::class.java).apply {
                    action = ACTION_TOGGLE_FLOATING_BALL
                    putExtra(EXTRA_ENABLE_FLOATING_BALL, enable)
                    putExtra(EXTRA_OVERLAY_STYLE, style.name)
                    putExtra(EXTRA_SIDEBAR_TAP_BEHAVIOR, tapBehavior.name)
                    putExtra(EXTRA_HIDE_BALL_ON_RECORD, hideOnRecord)
                }
            )
        }
    }
}
