package com.lossless.screenrecorder.service

import android.annotation.SuppressLint
import android.app.PendingIntent
import android.app.StatusBarManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.graphics.drawable.Icon
import android.os.Build
import android.service.quicksettings.Tile
import android.service.quicksettings.TileService
import com.lossless.screenrecorder.R
import com.lossless.screenrecorder.model.RecordingSessionState
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

class QuickRecordTileService : TileService() {

    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var stateJob: Job? = null

    override fun onStartListening() {
        super.onStartListening()
        stateJob?.cancel()
        stateJob = serviceScope.launch {
            RecordingStateHolder.sessionState.collect { state ->
                syncTileWithState(state)
            }
        }
    }

    override fun onStopListening() {
        stateJob?.cancel()
        stateJob = null
        super.onStopListening()
    }

    @SuppressLint("StartActivityAndCollapseDeprecated")
    override fun onClick() {
        super.onClick()
        val currentState = RecordingStateHolder.sessionState.value
        if (currentState is RecordingSessionState.Recording ||
            currentState is RecordingSessionState.Countdown
        ) {
            ScreenRecordService.sendStopIntent(this)
        } else {
            val intent = Intent(this, RecordingPermissionActivity::class.java).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                val pendingIntent = PendingIntent.getActivity(
                    this,
                    2001,
                    intent,
                    PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
                )
                startActivityAndCollapse(pendingIntent)
            } else {
                @Suppress("DEPRECATION")
                startActivityAndCollapse(intent)
            }
        }
    }

    private fun syncTileWithState(state: RecordingSessionState) {
        val tile = qsTile ?: return
        when (state) {
            is RecordingSessionState.Recording -> {
                tile.state = Tile.STATE_ACTIVE
                tile.label = getString(R.string.qs_tile_label)
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    tile.subtitle = "${state.resolutionLabel} · ${state.bitrateMbps}Mbps"
                }
            }
            is RecordingSessionState.Countdown -> {
                tile.state = Tile.STATE_ACTIVE
                tile.label = "倒數 ${state.remainingSeconds} 秒..."
            }
            else -> {
                tile.state = Tile.STATE_INACTIVE
                tile.label = getString(R.string.qs_tile_label)
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    tile.subtitle = getString(R.string.qs_tile_subtitle_idle)
                }
            }
        }
        tile.updateTile()
    }

    override fun onDestroy() {
        serviceScope.cancel()
        super.onDestroy()
    }

    companion object {
        fun requestAddTile(context: Context, onResult: (String) -> Unit) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                val statusBarManager = context.getSystemService(StatusBarManager::class.java)
                if (statusBarManager != null) {
                    statusBarManager.requestAddTileService(
                        ComponentName(context, QuickRecordTileService::class.java),
                        context.getString(R.string.qs_tile_label),
                        Icon.createWithResource(context, R.drawable.ic_qs_tile_record),
                        context.mainExecutor
                    ) { resultCode ->
                        val msg = when (resultCode) {
                            StatusBarManager.TILE_ADD_REQUEST_RESULT_TILE_ADDED ->
                                "已成功將「無損螢幕錄影」磁貼加入快速設定控制中心！"
                            StatusBarManager.TILE_ADD_REQUEST_RESULT_TILE_ALREADY_ADDED ->
                                "「無損螢幕錄影」磁貼已存在於您的下拉控制中心。"
                            else ->
                                "請從螢幕頂部下拉控制中心，點擊編輯即可拖入「無損螢幕錄影」磁貼。"
                        }
                        onResult(msg)
                    }
                    return
                }
            }
            onResult("請從螢幕頂部下拉控制中心，點擊編輯按鈕將「無損螢幕錄影」磁貼加入面板。")
        }
    }
}
