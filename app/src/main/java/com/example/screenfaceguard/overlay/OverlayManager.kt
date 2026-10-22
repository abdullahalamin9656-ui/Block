package com.example.screenfaceguard.overlay

import android.content.Context
import android.graphics.PixelFormat
import android.graphics.Point
import android.graphics.RectF
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.util.DisplayMetrics
import android.util.Log
import android.view.Gravity
import android.view.WindowManager
import com.example.screenfaceguard.model.OverlayStyle

/**
 * Owns the full-screen, non-touchable overlay window.
 * show / hide / updateSize must be called on the main thread.
 * setCovers may be called from any thread.
 */
class OverlayManager(context: Context) {

    private val appContext = context.applicationContext
    private val windowManager = appContext.getSystemService(Context.WINDOW_SERVICE) as WindowManager
    private val mainHandler = Handler(Looper.getMainLooper())

    private var view: FaceCoverView? = null
    private var params: WindowManager.LayoutParams? = null

    @Volatile private var latestRects: List<RectF> = emptyList()
    @Volatile private var latestStyle = OverlayStyle.OVAL
    private val applyRunnable = Runnable { view?.setCovers(latestRects, latestStyle) }

    val densityDpi: Int
        get() = appContext.resources.configuration.densityDpi

    /** Real display size in the current rotation, in pixels. */
    fun screenSize(): Point {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            val b = windowManager.maximumWindowMetrics.bounds
            Point(b.width(), b.height())
        } else {
            val dm = DisplayMetrics()
            @Suppress("DEPRECATION")
            windowManager.defaultDisplay.getRealMetrics(dm)
            Point(dm.widthPixels, dm.heightPixels)
        }
    }

    fun show(): Boolean {
        if (view != null) return true
        return try {
            val size = screenSize()
            val lp = WindowManager.LayoutParams(
                size.x,
                size.y,
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                    WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
                    WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                    WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
                PixelFormat.TRANSLUCENT
            ).apply {
                gravity = Gravity.TOP or Gravity.START
                x = 0
                y = 0
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                    layoutInDisplayCutoutMode =
                        WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS
                } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                    layoutInDisplayCutoutMode =
                        WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
                }
            }
            val v = FaceCoverView(appContext)
            windowManager.addView(v, lp)
            view = v
            params = lp
            true
        } catch (e: Exception) {
            Log.e("OverlayManager", "Could not add overlay", e)
            view = null
            params = null
            false
        }
    }

    /** Call after rotation. */
    fun updateSize() {
        val v = view ?: return
        val lp = params ?: return
        val size = screenSize()
        lp.width = size.x
        lp.height = size.y
        try {
            windowManager.updateViewLayout(v, lp)
        } catch (e: Exception) {
            Log.w("OverlayManager", "updateViewLayout failed", e)
        }
    }

    fun setCovers(rects: List<RectF>, style: OverlayStyle) {
        latestRects = rects
        latestStyle = style
        mainHandler.post(applyRunnable)
    }

    fun hide() {
        mainHandler.removeCallbacks(applyRunnable)
        val v = view ?: return
        try {
            windowManager.removeViewImmediate(v)
        } catch (e: Exception) {
            Log.w("OverlayManager", "removeView failed", e)
        }
        view = null
        params = null
    }
}
