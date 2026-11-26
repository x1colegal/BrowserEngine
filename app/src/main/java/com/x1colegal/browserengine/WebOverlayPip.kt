package com.x1colegal.browserengine

import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.PixelFormat
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.util.Rational
import android.view.ContextThemeWrapper
import android.view.Gravity
import android.view.MotionEvent
import android.view.ScaleGestureDetector
import android.view.View
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.ImageButton
import android.widget.ImageView
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.core.view.doOnAttach
import org.mozilla.geckoview.GeckoSession
import org.mozilla.geckoview.GeckoView
import kotlin.math.abs
import kotlin.math.roundToInt

internal object WebOverlayPip {
    private var container: FrameLayout? = null
    private var geckoView: GeckoView? = null
    private var windowManager: WindowManager? = null
    private var layoutParams: WindowManager.LayoutParams? = null
    private var playPauseBtn: ImageButton? = null
    private val mainHandler = Handler(Looper.getMainLooper())
    private var hideRunnable: Runnable? = null

    var activeSession by mutableStateOf<GeckoSession?>(null)
        private set
    private var isPlaying: Boolean = true
    private var onActionCallback: ((Boolean) -> Unit)? = null
    private var onClosedCallback: ((Boolean) -> Unit)? = null

    fun isActiveFor(session: GeckoSession): Boolean = activeSession === session

    @SuppressLint("ClickableViewAccessibility")
    fun show(
        context: Context,
        session: GeckoSession,
        initialPlaying: Boolean,
        aspect: Rational,
        onAction: (Boolean) -> Unit,
        onReleaseFromHost: () -> Unit = {},
        onClosed: (Boolean) -> Unit
    ): Boolean {
        val appCtx = context.applicationContext
        if (Build.VERSION.SDK_INT >= 23 && !android.provider.Settings.canDrawOverlays(appCtx)) {
            return false
        }
        if (container != null) {
            if (activeSession === session) return true
            close(pause = true)
        }

        val wm = appCtx.getSystemService(Context.WINDOW_SERVICE) as? WindowManager ?: return false
        val dm = appCtx.resources.displayMetrics
        val density = dm.density
        val minW = (110 * density).roundToInt()
        val shortSide = minOf(dm.widthPixels, dm.heightPixels)
        val longSide = maxOf(dm.widthPixels, dm.heightPixels)
        val maxW = (shortSide * 0.92f).roundToInt().coerceAtLeast(minW)
        val minH = (62 * density).roundToInt()
        val maxH = (longSide * 0.55f).roundToInt()
        val ratio = if (aspect.denominator != 0) aspect.toFloat().coerceIn(0.5f, 2.4f) else (16f / 9f)
        var curW = (shortSide * 0.62f).roundToInt().coerceIn(minW, maxW)
        var curH = (curW / ratio).roundToInt().coerceIn(minH, maxH)

        val windowType = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
        } else {
            @Suppress("DEPRECATION")
            WindowManager.LayoutParams.TYPE_PHONE
        }

        val params = WindowManager.LayoutParams(
            curW,
            curH,
            windowType,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS or
                WindowManager.LayoutParams.FLAG_HARDWARE_ACCELERATED,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = dm.widthPixels - curW - (12 * density).roundToInt()
            y = dm.heightPixels - curH - (96 * density).roundToInt()
        }

        val themedCtx = ContextThemeWrapper(appCtx, R.style.BrowserTheme)
        val root = FrameLayout(themedCtx).apply {
            background = GradientDrawable().apply {
                setColor(Color.BLACK)
                cornerRadius = 14f * density
                setStroke((1 * density).roundToInt().coerceAtLeast(1), 0x33FFFFFF)
            }
            clipToOutline = true
            elevation = 12f * density
        }

        val gv = GeckoView(themedCtx)
        root.addView(
            gv,
            FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT
            )
        )

        val controlsOverlay = FrameLayout(themedCtx).apply {
            setBackgroundColor(Color.TRANSPARENT)
            visibility = View.VISIBLE
        }

        fun scheduleAutoHide() {
            hideRunnable?.let { mainHandler.removeCallbacks(it) }
            val r = Runnable { controlsOverlay.visibility = View.GONE }
            hideRunnable = r
            mainHandler.postDelayed(r, 2600L)
        }

        fun makeIconBtn(iconRes: Int, desc: String, sizeDp: Int, padDp: Int, onClick: () -> Unit): ImageButton {
            val size = (sizeDp * density).roundToInt()
            val pad = (padDp * density).roundToInt()
            return ImageButton(themedCtx).apply {
                setImageResource(iconRes)
                scaleType = ImageView.ScaleType.FIT_CENTER
                setColorFilter(Color.WHITE)
                background = GradientDrawable().apply {
                    shape = GradientDrawable.OVAL
                    setColor(0x6E1E1E22)
                    setStroke((1 * density).roundToInt().coerceAtLeast(1), 0x4DFFFFFF)
                }
                elevation = 4f * density
                contentDescription = desc
                setPadding(pad, pad, pad, pad)
                setOnClickListener {
                    scheduleAutoHide()
                    onClick()
                }
                layoutParams = FrameLayout.LayoutParams(size, size)
            }
        }

        isPlaying = initialPlaying
        val playBtn = makeIconBtn(
            if (isPlaying) R.drawable.ic_pip_pause else R.drawable.ic_pip_play,
            "Play or pause",
            38,
            6
        ) {
            val next = !isPlaying
            isPlaying = next
            playPauseBtn?.setImageResource(if (next) R.drawable.ic_pip_pause else R.drawable.ic_pip_play)
            onActionCallback?.invoke(next)
        }.apply {
            (layoutParams as FrameLayout.LayoutParams).gravity = Gravity.CENTER
        }
        playPauseBtn = playBtn

        val expandBtn = makeIconBtn(R.drawable.ic_pip_expand, "Expand", 30, 5) {
            close(pause = false)
            val intent = Intent(appCtx, MainActivity::class.java).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_REORDER_TO_FRONT or Intent.FLAG_ACTIVITY_SINGLE_TOP)
            }
            runCatching { appCtx.startActivity(intent) }
        }.apply {
            (layoutParams as FrameLayout.LayoutParams).apply {
                gravity = Gravity.TOP or Gravity.START
                val m = (4 * density).roundToInt()
                setMargins(m, m, m, m)
            }
        }

        val closeBtn = makeIconBtn(R.drawable.ic_pip_close, "Close", 30, 5) {
            close(pause = true)
        }.apply {
            (layoutParams as FrameLayout.LayoutParams).apply {
                gravity = Gravity.TOP or Gravity.END
                val m = (4 * density).roundToInt()
                setMargins(m, m, m, m)
            }
        }

        controlsOverlay.addView(expandBtn)
        controlsOverlay.addView(closeBtn)
        controlsOverlay.addView(playBtn)

        val resizeBars = object : View(themedCtx) {
            private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = 0xCCFFFFFF.toInt()
                strokeWidth = 2f * density
                strokeCap = Paint.Cap.ROUND
            }
            override fun onDraw(canvas: Canvas) {
                super.onDraw(canvas)
                val w = width.toFloat()
                val h = height.toFloat()
                canvas.drawLine(w * 0.35f, h - 2f * density, w - 2f * density, h * 0.35f, paint)
                canvas.drawLine(w * 0.68f, h - 2f * density, w - 2f * density, h * 0.68f, paint)
            }
        }.apply {
            val s = (18 * density).roundToInt()
            layoutParams = FrameLayout.LayoutParams(s, s).apply {
                gravity = Gravity.BOTTOM or Gravity.END
                val m = (5 * density).roundToInt()
                setMargins(m, m, m, m)
            }
        }
        controlsOverlay.addView(resizeBars)

        var startX = 0
        var startY = 0
        var startW = 0
        var startH = 0
        var touchX = 0f
        var touchY = 0f
        var moved = false
        var resizing = false
        var scaling = false

        val scaleDetector = ScaleGestureDetector(
            themedCtx,
            object : ScaleGestureDetector.SimpleOnScaleGestureListener() {
                override fun onScaleBegin(detector: ScaleGestureDetector): Boolean {
                    scaling = true
                    return true
                }

                override fun onScale(detector: ScaleGestureDetector): Boolean {
                    val newW = (curW * detector.scaleFactor).roundToInt().coerceIn(minW, maxW)
                    val newH = (newW / ratio).roundToInt().coerceIn(minH, maxH)
                    curW = newW
                    curH = newH
                    params.width = curW
                    params.height = curH
                    params.x = params.x.coerceIn(0, (dm.widthPixels - curW).coerceAtLeast(0))
                    params.y = params.y.coerceIn(0, (dm.heightPixels - curH).coerceAtLeast(0))
                    runCatching { wm.updateViewLayout(root, params) }
                    return true
                }
            }
        )

        val touchLayer = View(themedCtx).apply {
            setBackgroundColor(Color.TRANSPARENT)
            setOnTouchListener { _, event ->
                scaleDetector.onTouchEvent(event)
                when (event.actionMasked) {
                    MotionEvent.ACTION_DOWN -> {
                        startX = params.x
                        startY = params.y
                        startW = curW
                        startH = curH
                        touchX = event.rawX
                        touchY = event.rawY
                        moved = false
                        scaling = false
                        val gripZone = (40 * density)
                        resizing = event.x >= (curW - gripZone) && event.y >= (curH - gripZone)
                        true
                    }
                    MotionEvent.ACTION_MOVE -> {
                        if (scaling || event.pointerCount > 1) return@setOnTouchListener true
                        val dx = (event.rawX - touchX).roundToInt()
                        val dy = (event.rawY - touchY).roundToInt()
                        if (abs(dx) > 6 || abs(dy) > 6) moved = true
                        if (moved) {
                            if (resizing) {
                                curW = (startW + dx).coerceIn(minW, maxW)
                                curH = (curW / ratio).roundToInt().coerceIn(minH, maxH)
                                params.width = curW
                                params.height = curH
                            } else {
                                params.x = (startX + dx).coerceIn(0, (dm.widthPixels - curW).coerceAtLeast(0))
                                params.y = (startY + dy).coerceIn(0, (dm.heightPixels - curH).coerceAtLeast(0))
                            }
                            runCatching { wm.updateViewLayout(root, params) }
                        }
                        true
                    }
                    MotionEvent.ACTION_UP -> {
                        if (!moved && !scaling) {
                            if (controlsOverlay.visibility == View.VISIBLE) {
                                hideRunnable?.let { mainHandler.removeCallbacks(it) }
                                controlsOverlay.visibility = View.GONE
                            } else {
                                controlsOverlay.visibility = View.VISIBLE
                                scheduleAutoHide()
                            }
                        }
                        resizing = false
                        scaling = false
                        true
                    }
                    else -> false
                }
            }
        }

        root.addView(
            touchLayer,
            FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT
            )
        )
        root.addView(
            controlsOverlay,
            FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT
            )
        )

        activeSession = session
        onActionCallback = onAction
        onClosedCallback = onClosed
        windowManager = wm
        layoutParams = params
        container = root
        geckoView = gv

        onReleaseFromHost()
        root.alpha = 0f
        wm.addView(root, params)
        root.animate().alpha(1f).setDuration(220L).start()
        scheduleAutoHide()
        gv.doOnAttach {
            gv.post {
                if (container === root && activeSession === session) {
                    gv.setSession(session)
                    session.setActive(true)
                    session.setFocused(true)
                    session.setPriorityHint(GeckoSession.PRIORITY_HIGH)
                }
            }
        }
        return true
    }

    fun close(pause: Boolean) {
        hideRunnable?.let { mainHandler.removeCallbacks(it) }
        hideRunnable = null
        val session = activeSession
        val onClosed = onClosedCallback
        val gv = geckoView
        val root = container
        val wm = windowManager

        activeSession = null
        onActionCallback = null
        onClosedCallback = null
        playPauseBtn = null
        geckoView = null
        container = null
        windowManager = null
        layoutParams = null

        if (session != null) {
            session.setPriorityHint(GeckoSession.PRIORITY_DEFAULT)
        }
        runCatching { gv?.releaseSession() }
        if (root != null && wm != null) {
            runCatching { wm.removeViewImmediate(root) }
        }
        onClosed?.invoke(pause)
    }
}
