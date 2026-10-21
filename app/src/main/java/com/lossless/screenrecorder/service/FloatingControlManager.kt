package com.lossless.screenrecorder.service

import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.provider.Settings
import android.util.TypedValue
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.widget.LinearLayout
import android.widget.TextView
import com.lossless.screenrecorder.model.RecordingSessionState
import kotlin.math.abs

class FloatingControlManager(private val context: Context) {

    private val windowManager = context.getSystemService(Context.WINDOW_SERVICE) as WindowManager
    private var rootContainer: LinearLayout? = null
    private var badgeView: TextView? = null
    private var expandedPanel: LinearLayout? = null
    private var actionPrimaryBtn: TextView? = null
    private var actionPauseBtn: TextView? = null
    private var layoutParams: WindowManager.LayoutParams? = null

    private var isExpanded = false
    private var hideWhileRecording = true

    fun canDrawOverlays(): Boolean = Settings.canDrawOverlays(context)

    @SuppressLint("ClickableViewAccessibility")
    fun showOverlay(hideOnRecord: Boolean) {
        hideWhileRecording = hideOnRecord
        if (!canDrawOverlays()) {
            RecordingStateHolder.setFloatingBallShowing(false)
            return
        }
        if (rootContainer != null) {
            updateForState(RecordingStateHolder.sessionState.value, hideWhileRecording)
            return
        }

        val dp = { value: Int ->
            TypedValue.applyDimension(
                TypedValue.COMPLEX_UNIT_DIP,
                value.toFloat(),
                context.resources.displayMetrics
            ).toInt()
        }

        val container = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(4), dp(4), dp(4), dp(4))
        }

        val orb = TextView(context).apply {
            text = "REC"
            setTextColor(Color.parseColor("#00E5FF"))
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 11f)
            typeface = Typeface.MONOSPACE
            gravity = Gravity.CENTER
            width = dp(48)
            height = dp(48)
            background = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor(Color.parseColor("#E60A0D12"))
                setStroke(dp(2), Color.parseColor("#00E5FF"))
            }
        }

        val panel = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            visibility = View.GONE
            setPadding(dp(8), dp(6), dp(8), dp(6))
            background = GradientDrawable().apply {
                shape = GradientDrawable.RECTANGLE
                cornerRadius = dp(24).toFloat()
                setColor(Color.parseColor("#F2121721"))
                setStroke(dp(1), Color.parseColor("#3300E5FF"))
            }
        }

        val startStopBtn = TextView(context).apply {
            text = "開錄"
            setTextColor(Color.WHITE)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
            setPadding(dp(12), dp(6), dp(12), dp(6))
            background = GradientDrawable().apply {
                cornerRadius = dp(16).toFloat()
                setColor(Color.parseColor("#FF2A54"))
            }
            setOnClickListener {
                collapseMenu()
                val currentState = RecordingStateHolder.sessionState.value
                if (currentState is RecordingSessionState.Recording ||
                    currentState is RecordingSessionState.Countdown
                ) {
                    ScreenRecordService.sendStopIntent(context)
                } else {
                    RecordingPermissionActivity.launch(context)
                }
            }
        }

        val pauseResumeBtn = TextView(context).apply {
            text = "暫停"
            setTextColor(Color.parseColor("#00E5FF"))
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
            setPadding(dp(10), dp(6), dp(10), dp(6))
            visibility = View.GONE
            setOnClickListener {
                val currentState = RecordingStateHolder.sessionState.value
                if (currentState is RecordingSessionState.Recording) {
                    if (currentState.isPaused) {
                        ScreenRecordService.sendResumeIntent(context)
                    } else {
                        ScreenRecordService.sendPauseIntent(context)
                    }
                }
                collapseMenu()
            }
        }

        val closeBallBtn = TextView(context).apply {
            text = "✕"
            setTextColor(Color.parseColor("#94A3B8"))
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
            setPadding(dp(10), dp(6), dp(8), dp(6))
            setOnClickListener {
                hideOverlay()
            }
        }

        panel.addView(startStopBtn)
        panel.addView(pauseResumeBtn)
        panel.addView(closeBallBtn)

        container.addView(orb)
        container.addView(panel)

        val overlayType = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
        } else {
            @Suppress("DEPRECATION")
            WindowManager.LayoutParams.TYPE_PHONE
        }

        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            overlayType,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = dp(16)
            y = dp(180)
        }

        var initialX = 0
        var initialY = 0
        var initialTouchX = 0f
        var initialTouchY = 0f
        var isDragging = false

        orb.setOnTouchListener { _, event ->
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    initialX = params.x
                    initialY = params.y
                    initialTouchX = event.rawX
                    initialTouchY = event.rawY
                    isDragging = false
                    true
                }
                MotionEvent.ACTION_MOVE -> {
                    val dx = (event.rawX - initialTouchX).toInt()
                    val dy = (event.rawY - initialTouchY).toInt()
                    if (abs(dx) > dp(6) || abs(dy) > dp(6)) {
                        isDragging = true
                        params.x = initialX + dx
                        params.y = initialY + dy
                        runCatching { windowManager.updateViewLayout(container, params) }
                    }
                    true
                }
                MotionEvent.ACTION_UP -> {
                    if (!isDragging) {
                        toggleMenu()
                    } else {
                        // Snap to nearest horizontal screen edge
                        val screenW = context.resources.displayMetrics.widthPixels
                        params.x = if (params.x + container.width / 2 < screenW / 2) {
                            dp(8)
                        } else {
                            screenW - container.width - dp(8)
                        }
                        runCatching { windowManager.updateViewLayout(container, params) }
                    }
                    true
                }
                else -> false
            }
        }

        runCatching {
            windowManager.addView(container, params)
            rootContainer = container
            badgeView = orb
            expandedPanel = panel
            actionPrimaryBtn = startStopBtn
            actionPauseBtn = pauseResumeBtn
            layoutParams = params
            RecordingStateHolder.setFloatingBallShowing(true)
            updateForState(RecordingStateHolder.sessionState.value, hideWhileRecording)
        }
    }

    private fun toggleMenu() {
        isExpanded = !isExpanded
        expandedPanel?.visibility = if (isExpanded) View.VISIBLE else View.GONE
    }

    private fun collapseMenu() {
        isExpanded = false
        expandedPanel?.visibility = View.GONE
    }

    fun updateForState(state: RecordingSessionState, hideOnRecord: Boolean) {
        hideWhileRecording = hideOnRecord
        val container = rootContainer ?: return
        val orb = badgeView ?: return
        val primaryBtn = actionPrimaryBtn ?: return
        val pauseBtn = actionPauseBtn ?: return

        val dp = { value: Int ->
            TypedValue.applyDimension(
                TypedValue.COMPLEX_UNIT_DIP,
                value.toFloat(),
                context.resources.displayMetrics
            ).toInt()
        }

        when (state) {
            is RecordingSessionState.Countdown -> {
                container.visibility = View.VISIBLE
                collapseMenu()
                orb.alpha = 1.0f
                orb.text = "${state.remainingSeconds}"
                orb.setTextColor(Color.parseColor("#FFB300"))
                orb.background = GradientDrawable().apply {
                    shape = GradientDrawable.OVAL
                    setColor(Color.parseColor("#E60A0D12"))
                    setStroke(dp(2), Color.parseColor("#FFB300"))
                }
            }
            is RecordingSessionState.Recording -> {
                if (hideWhileRecording && !state.isPaused) {
                    // Completely hide floating ball during active recording so zero overlay pixels enter the video
                    collapseMenu()
                    container.visibility = View.GONE
                } else {
                    container.visibility = View.VISIBLE
                    orb.alpha = if (state.isPaused) 0.9f else 0.35f
                    orb.text = if (state.isPaused) "II" else "●"
                    orb.setTextColor(Color.parseColor("#FF2A54"))
                    orb.background = GradientDrawable().apply {
                        shape = GradientDrawable.OVAL
                        setColor(Color.parseColor("#CC0A0D12"))
                        setStroke(dp(2), Color.parseColor("#FF2A54"))
                    }
                    primaryBtn.text = "停止"
                    pauseBtn.visibility = View.VISIBLE
                    pauseBtn.text = if (state.isPaused) "繼續" else "暫停"
                }
            }
            else -> {
                container.visibility = View.VISIBLE
                orb.alpha = 0.95f
                orb.text = "REC"
                orb.setTextColor(Color.parseColor("#00E5FF"))
                orb.background = GradientDrawable().apply {
                    shape = GradientDrawable.OVAL
                    setColor(Color.parseColor("#E60A0D12"))
                    setStroke(dp(2), Color.parseColor("#00E5FF"))
                }
                primaryBtn.text = "開錄"
                pauseBtn.visibility = View.GONE
            }
        }
    }

    fun hideOverlay() {
        val view = rootContainer ?: return
        runCatching { windowManager.removeView(view) }
        rootContainer = null
        badgeView = null
        expandedPanel = null
        actionPrimaryBtn = null
        actionPauseBtn = null
        layoutParams = null
        RecordingStateHolder.setFloatingBallShowing(false)
    }
}
