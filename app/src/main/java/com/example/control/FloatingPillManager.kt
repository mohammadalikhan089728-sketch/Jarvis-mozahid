package com.example.control

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
import android.view.View
import android.view.WindowManager
import android.widget.LinearLayout
import android.widget.TextView

/**
 * Displays an ultra-thin obsidian floating pill at the top of the screen whenever JARVIS
 * is actively controlling the device:
 *   ● JARVIS is using phone   [ Stop ]
 */
class FloatingPillManager(private val context: Context) {

    private val mainHandler = Handler(Looper.getMainLooper())
    private val windowManager = context.getSystemService(Context.WINDOW_SERVICE) as WindowManager
    private var pillView: View? = null

    fun canDrawOverlay(): Boolean = Settings.canDrawOverlays(context)

    fun show(statusText: String = "JARVIS is using phone", onStopClick: () -> Unit = {}) {
        if (!canDrawOverlay()) return
        mainHandler.post {
            if (pillView != null) return@post
            try {
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
                    setPadding(dp(12), dp(5), dp(6), dp(5))
                    background = GradientDrawable().apply {
                        shape = GradientDrawable.RECTANGLE
                        cornerRadius = dp(999).toFloat()
                        setColor(Color.parseColor("#EC0A0E14"))
                        setStroke(dp(1), Color.parseColor("#4D00E5FF"))
                    }
                    elevation = dp(8).toFloat()
                }

                // Glowing cyan indicator dot
                val dot = View(context).apply {
                    background = GradientDrawable().apply {
                        shape = GradientDrawable.OVAL
                        setColor(Color.parseColor("#00E5FF"))
                    }
                    layoutParams = LinearLayout.LayoutParams(dp(8), dp(8)).apply {
                        marginEnd = dp(8)
                    }
                }

                val label = TextView(context).apply {
                    text = statusText
                    setTextColor(Color.parseColor("#F1F5F9"))
                    setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
                    typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
                    layoutParams = LinearLayout.LayoutParams(
                        LinearLayout.LayoutParams.WRAP_CONTENT,
                        LinearLayout.LayoutParams.WRAP_CONTENT
                    ).apply {
                        marginEnd = dp(10)
                    }
                }

                val stopBtn = TextView(context).apply {
                    text = "Stop"
                    setTextColor(Color.WHITE)
                    setTextSize(TypedValue.COMPLEX_UNIT_SP, 11.5f)
                    typeface = Typeface.create("sans-serif-medium", Typeface.BOLD)
                    setPadding(dp(10), dp(4), dp(10), dp(4))
                    background = GradientDrawable().apply {
                        shape = GradientDrawable.RECTANGLE
                        cornerRadius = dp(999).toFloat()
                        setColor(Color.parseColor("#EF4444"))
                    }
                    setOnClickListener {
                        ExecutionController.requestStop()
                        onStopClick()
                        hide()
                    }
                }

                container.addView(dot)
                container.addView(label)
                container.addView(stopBtn)

                val layoutType = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
                } else {
                    @Suppress("DEPRECATION")
                    WindowManager.LayoutParams.TYPE_PHONE
                }

                val params = WindowManager.LayoutParams(
                    WindowManager.LayoutParams.WRAP_CONTENT,
                    WindowManager.LayoutParams.WRAP_CONTENT,
                    layoutType,
                    WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                        WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
                    PixelFormat.TRANSLUCENT
                ).apply {
                    gravity = Gravity.TOP or Gravity.CENTER_HORIZONTAL
                    y = dp(6)
                }

                windowManager.addView(container, params)
                pillView = container
            } catch (_: Exception) {
                pillView = null
            }
        }
    }

    fun hide() {
        mainHandler.post {
            val view = pillView ?: return@post
            try {
                windowManager.removeView(view)
            } catch (_: Exception) {
            }
            pillView = null
        }
    }
}
