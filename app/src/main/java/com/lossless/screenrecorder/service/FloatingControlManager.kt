package com.lossless.screenrecorder.service

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.util.TypedValue
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import com.lossless.screenrecorder.model.OverlayControlStyle
import com.lossless.screenrecorder.model.RecordingSessionState
import com.lossless.screenrecorder.model.SidebarTapBehavior
import java.util.Locale
import kotlin.math.abs

class FloatingControlManager(private val context: Context) {

    private val windowManager = context.getSystemService(Context.WINDOW_SERVICE) as WindowManager
    private val mainHandler = Handler(Looper.getMainLooper())

    private var rootContainer: LinearLayout? = null
    private var edgeHandleContainer: FrameLayout? = null
    private var edgeVisualBar: View? = null
    private var edgeCountdownLabel: TextView? = null
    private var floatingBallOrb: TextView? = null

    private var expandedDrawer: LinearLayout? = null
    private var drawerTimerText: TextView? = null
    private var actionPrimaryBtn: TextView? = null
    private var actionPauseBtn: TextView? = null
    private var layoutParams: WindowManager.LayoutParams? = null

    private var isExpanded = false
    private var isDockedRight = true
    private var currentStyle: OverlayControlStyle = OverlayControlStyle.EDGE_SIDEBAR
    private var currentTapBehavior: SidebarTapBehavior = SidebarTapBehavior.EXPAND_PANEL
    private var hideWhileRecording = false

    private val autoCollapseRunnable = Runnable {
        collapseMenu()
    }

    fun canDrawOverlays(): Boolean = Settings.canDrawOverlays(context)

    private fun dp(value: Int): Int {
        return TypedValue.applyDimension(
            TypedValue.COMPLEX_UNIT_DIP,
            value.toFloat(),
            context.resources.displayMetrics
        ).toInt()
    }

    @SuppressLint("ClickableViewAccessibility")
    fun showOverlay(
        style: OverlayControlStyle = currentStyle,
        tapBehavior: SidebarTapBehavior = currentTapBehavior,
        hideOnRecord: Boolean = hideWhileRecording
    ) {
        currentStyle = style
        currentTapBehavior = tapBehavior
        hideWhileRecording = hideOnRecord

        if (!canDrawOverlays()) {
            RecordingStateHolder.setFloatingBallShowing(false)
            return
        }

        if (rootContainer != null) {
            updateForState(
                state = RecordingStateHolder.sessionState.value,
                style = currentStyle,
                tapBehavior = currentTapBehavior,
                hideOnRecord = hideWhileRecording
            )
            return
        }

        val container = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }

        // 1. Edge Sidebar Handle (Ultra-thin non-intrusive edge tab)
        val handleFrame = FrameLayout(context).apply {
            layoutParams = LinearLayout.LayoutParams(dp(20), dp(88))
        }

        val visualBar = View(context).apply {
            layoutParams = FrameLayout.LayoutParams(dp(6), dp(76)).apply {
                gravity = Gravity.CENTER_VERTICAL or Gravity.END
            }
            background = buildEdgeBarDrawable(
                fillColor = Color.parseColor("#CC00E5FF"),
                strokeColor = Color.parseColor("#80FFFFFF"),
                dockedRight = true
            )
        }

        val countdownBadge = TextView(context).apply {
            layoutParams = FrameLayout.LayoutParams(dp(28), dp(28)).apply {
                gravity = Gravity.CENTER
            }
            visibility = View.GONE
            gravity = Gravity.CENTER
            typeface = Typeface.MONOSPACE
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
            setTextColor(Color.parseColor("#FFB300"))
            background = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor(Color.parseColor("#F00A0D12"))
                setStroke(dp(2), Color.parseColor("#FFB300"))
            }
        }

        handleFrame.addView(visualBar)
        handleFrame.addView(countdownBadge)

        // 2. Classic Floating Ball Orb (Available when FLOATING_BALL style is selected)
        val orb = TextView(context).apply {
            text = "REC"
            setTextColor(Color.parseColor("#00E5FF"))
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 11f)
            typeface = Typeface.MONOSPACE
            gravity = Gravity.CENTER
            width = dp(46)
            height = dp(46)
            background = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor(Color.parseColor("#E60A0D12"))
                setStroke(dp(2), Color.parseColor("#00E5FF"))
            }
        }

        // 3. Slide-out Sidebar Control Drawer (Quick Stop / Pause / Timer)
        val drawer = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            visibility = View.GONE
            setPadding(dp(12), dp(8), dp(12), dp(8))
            background = GradientDrawable().apply {
                shape = GradientDrawable.RECTANGLE
                cornerRadius = dp(22).toFloat()
                setColor(Color.parseColor("#F2101520"))
                setStroke(dp(1), Color.parseColor("#5500E5FF"))
            }
        }

        val timerLabel = TextView(context).apply {
            text = "STANDBY"
            setTextColor(Color.parseColor("#00E5FF"))
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 11f)
            typeface = Typeface.MONOSPACE
            setPadding(0, 0, dp(10), 0)
        }

        val startStopBtn = TextView(context).apply {
            text = "● 開錄"
            setTextColor(Color.WHITE)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
            typeface = Typeface.DEFAULT_BOLD
            setPadding(dp(14), dp(7), dp(14), dp(7))
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
            text = "II 暫停"
            setTextColor(Color.parseColor("#00E5FF"))
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
            setPadding(dp(12), dp(7), dp(10), dp(7))
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

        val collapseBtn = TextView(context).apply {
            text = "收起"
            setTextColor(Color.parseColor("#94A3B8"))
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 11f)
            setPadding(dp(8), dp(7), dp(4), dp(7))
            setOnClickListener {
                collapseMenu()
            }
        }

        drawer.addView(timerLabel)
        drawer.addView(startStopBtn)
        drawer.addView(pauseResumeBtn)
        drawer.addView(collapseBtn)

        val screenW = context.resources.displayMetrics.widthPixels
        val screenH = context.resources.displayMetrics.heightPixels

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
            x = screenW - dp(20)
            y = (screenH * 0.35f).toInt()
        }

        var initialX = 0
        var initialY = 0
        var initialTouchX = 0f
        var initialTouchY = 0f
        var isDragging = false

        val touchListener = View.OnTouchListener { _, event ->
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
                    if (abs(dx) > dp(8) || abs(dy) > dp(8)) {
                        isDragging = true
                        params.x = initialX + dx
                        params.y = (initialY + dy).coerceIn(dp(48), screenH - dp(120))
                        runCatching { windowManager.updateViewLayout(container, params) }
                    }
                    true
                }
                MotionEvent.ACTION_UP -> {
                    val currentScreenW = context.resources.displayMetrics.widthPixels
                    if (!isDragging) {
                        handleTapOrSwipeTrigger()
                    } else {
                        isDockedRight = event.rawX >= currentScreenW / 2f
                        rebuildContainerOrder()
                        snapToNearestEdge()
                    }
                    true
                }
                else -> false
            }
        }

        handleFrame.setOnTouchListener(touchListener)
        orb.setOnTouchListener(touchListener)

        rootContainer = container
        edgeHandleContainer = handleFrame
        edgeVisualBar = visualBar
        edgeCountdownLabel = countdownBadge
        floatingBallOrb = orb
        expandedDrawer = drawer
        drawerTimerText = timerLabel
        actionPrimaryBtn = startStopBtn
        actionPauseBtn = pauseResumeBtn
        layoutParams = params

        rebuildContainerOrder()

        runCatching {
            windowManager.addView(container, params)
            RecordingStateHolder.setFloatingBallShowing(true)
            updateForState(
                state = RecordingStateHolder.sessionState.value,
                style = currentStyle,
                tapBehavior = currentTapBehavior,
                hideOnRecord = hideWhileRecording
            )
            snapToNearestEdge()
        }
    }

    private fun handleTapOrSwipeTrigger() {
        val state = RecordingStateHolder.sessionState.value
        if (currentStyle == OverlayControlStyle.EDGE_SIDEBAR &&
            currentTapBehavior == SidebarTapBehavior.INSTANT_STOP &&
            state is RecordingSessionState.Recording
        ) {
            // Instant 1-tap stop without opening any menu over the screen!
            ScreenRecordService.sendStopIntent(context)
            return
        }
        toggleMenu()
    }

    private fun rebuildContainerOrder() {
        val container = rootContainer ?: return
        val handle = edgeHandleContainer ?: return
        val bar = edgeVisualBar ?: return
        val orb = floatingBallOrb ?: return
        val drawer = expandedDrawer ?: return

        container.removeAllViews()
        val activeTriggerView = if (currentStyle == OverlayControlStyle.EDGE_SIDEBAR) handle else orb

        val barLp = (bar.layoutParams as? FrameLayout.LayoutParams)
            ?: FrameLayout.LayoutParams(dp(6), dp(76))
        barLp.gravity = Gravity.CENTER_VERTICAL or (if (isDockedRight) Gravity.END else Gravity.START)
        bar.layoutParams = barLp

        if (isDockedRight) {
            container.addView(drawer)
            container.addView(activeTriggerView)
        } else {
            container.addView(activeTriggerView)
            container.addView(drawer)
        }
    }

    private fun snapToNearestEdge() {
        val container = rootContainer ?: return
        val params = layoutParams ?: return
        val screenW = context.resources.displayMetrics.widthPixels
        params.x = if (isDockedRight) {
            screenW
        } else {
            0
        }
        runCatching { windowManager.updateViewLayout(container, params) }
    }

    private fun toggleMenu() {
        isExpanded = !isExpanded
        expandedDrawer?.visibility = if (isExpanded) View.VISIBLE else View.GONE
        mainHandler.removeCallbacks(autoCollapseRunnable)
        if (isExpanded) {
            // Auto collapse back to thin edge sidebar after 3.5 seconds of inactivity
            mainHandler.postDelayed(autoCollapseRunnable, 3500L)
        }
        snapToNearestEdge()
    }

    private fun collapseMenu() {
        mainHandler.removeCallbacks(autoCollapseRunnable)
        if (!isExpanded) return
        isExpanded = false
        expandedDrawer?.visibility = View.GONE
        snapToNearestEdge()
    }

    private fun buildEdgeBarDrawable(
        fillColor: Int,
        strokeColor: Int,
        dockedRight: Boolean
    ): GradientDrawable {
        val r = dp(6).toFloat()
        return GradientDrawable().apply {
            shape = GradientDrawable.RECTANGLE
            // Rounded on the inner side facing the screen, flat against the screen bezel
            cornerRadii = if (dockedRight) {
                floatArrayOf(r, r, 0f, 0f, 0f, 0f, r, r)
            } else {
                floatArrayOf(0f, 0f, r, r, r, r, 0f, 0f)
            }
            setColor(fillColor)
            setStroke(dp(1), strokeColor)
        }
    }

    fun updateForState(
        state: RecordingSessionState,
        style: OverlayControlStyle = currentStyle,
        tapBehavior: SidebarTapBehavior = currentTapBehavior,
        hideOnRecord: Boolean = hideWhileRecording
    ) {
        val styleChanged = currentStyle != style
        currentStyle = style
        currentTapBehavior = tapBehavior
        hideWhileRecording = hideOnRecord

        val container = rootContainer ?: return
        val bar = edgeVisualBar ?: return
        val cdBadge = edgeCountdownLabel ?: return
        val orb = floatingBallOrb ?: return
        val timerLabel = drawerTimerText ?: return
        val primaryBtn = actionPrimaryBtn ?: return
        val pauseBtn = actionPauseBtn ?: return

        if (styleChanged) {
            rebuildContainerOrder()
        }

        when (state) {
            is RecordingSessionState.Countdown -> {
                container.visibility = View.VISIBLE
                collapseMenu()
                cdBadge.visibility = View.VISIBLE
                cdBadge.text = "${state.remainingSeconds}"
                bar.alpha = 0.9f
                bar.background = buildEdgeBarDrawable(
                    fillColor = Color.parseColor("#EEFFB300"),
                    strokeColor = Color.parseColor("#FFFFFF"),
                    dockedRight = isDockedRight
                )

                orb.alpha = 1.0f
                orb.text = "${state.remainingSeconds}"
                orb.setTextColor(Color.parseColor("#FFB300"))
                timerLabel.text = "倒數 ${state.remainingSeconds}s"
            }
            is RecordingSessionState.Recording -> {
                cdBadge.visibility = View.GONE
                if (hideWhileRecording && !state.isPaused) {
                    collapseMenu()
                    container.visibility = View.GONE
                } else {
                    container.visibility = View.VISIBLE
                    // Ultra-subtle edge bar during active recording so it does not obstruct the screen
                    bar.alpha = if (isExpanded || state.isPaused) 0.9f else 0.30f
                    bar.background = buildEdgeBarDrawable(
                        fillColor = if (state.isPaused) {
                            Color.parseColor("#DDFFB300")
                        } else {
                            Color.parseColor("#DDFF2A54")
                        },
                        strokeColor = Color.parseColor("#66FFFFFF"),
                        dockedRight = isDockedRight
                    )

                    orb.alpha = if (state.isPaused) 0.9f else 0.32f
                    orb.text = if (state.isPaused) "II" else "●"
                    orb.setTextColor(Color.parseColor("#FF2A54"))

                    val mins = (state.elapsedMillis / 1000L) / 60L
                    val secs = (state.elapsedMillis / 1000L) % 60L
                    val timeText = String.format(Locale.US, "%02d:%02d", mins, secs)
                    timerLabel.text = if (state.isPaused) "PAUSED $timeText" else "REC $timeText"
                    timerLabel.setTextColor(
                        if (state.isPaused) Color.parseColor("#FFB300")
                        else Color.parseColor("#FF2A54")
                    )

                    primaryBtn.text = "■ 停止存檔"
                    pauseBtn.visibility = View.VISIBLE
                    pauseBtn.text = if (state.isPaused) "▶ 繼續" else "II 暫停"
                }
            }
            else -> {
                cdBadge.visibility = View.GONE
                container.visibility = View.VISIBLE
                bar.alpha = 0.85f
                bar.background = buildEdgeBarDrawable(
                    fillColor = Color.parseColor("#CC00E5FF"),
                    strokeColor = Color.parseColor("#80FFFFFF"),
                    dockedRight = isDockedRight
                )

                orb.alpha = 0.95f
                orb.text = "REC"
                orb.setTextColor(Color.parseColor("#00E5FF"))

                timerLabel.text = "待命就緒"
                timerLabel.setTextColor(Color.parseColor("#00E5FF"))
                primaryBtn.text = "● 開始錄影"
                pauseBtn.visibility = View.GONE
            }
        }
        snapToNearestEdge()
    }

    fun hideOverlay() {
        mainHandler.removeCallbacks(autoCollapseRunnable)
        val view = rootContainer ?: return
        runCatching { windowManager.removeView(view) }
        rootContainer = null
        edgeHandleContainer = null
        edgeVisualBar = null
        edgeCountdownLabel = null
        floatingBallOrb = null
        expandedDrawer = null
        drawerTimerText = null
        actionPrimaryBtn = null
        actionPauseBtn = null
        layoutParams = null
        RecordingStateHolder.setFloatingBallShowing(false)
    }
}
