package com.example.screenfaceguard.utils

import android.graphics.RectF

/**
 * Converts coordinates: captured frame -> screen -> overlay window.
 * The detector works on the scaled capture frame; the overlay lives in
 * real screen pixels, so we scale and subtract the overlay origin
 * (in case the overlay window is not at screen 0,0).
 */
class CoordinateMapper {

    private var scaleX = 1f
    private var scaleY = 1f
    private var originX = 0f
    private var originY = 0f
    private var overlayW = 1f
    private var overlayH = 1f

    /**
     * @param frameW/frameH size of the captured (scaled) frame
     * @param screenW/screenH real display size in the current rotation
     * @param overlayOriginX/Y screen position of the overlay's top-left corner
     */
    fun update(
        frameW: Int, frameH: Int,
        screenW: Int, screenH: Int,
        overlayOriginX: Int = 0, overlayOriginY: Int = 0
    ) {
        scaleX = screenW.toFloat() / frameW.coerceAtLeast(1)
        scaleY = screenH.toFloat() / frameH.coerceAtLeast(1)
        originX = overlayOriginX.toFloat()
        originY = overlayOriginY.toFloat()
        overlayW = screenW.toFloat()
        overlayH = screenH.toFloat()
    }

    fun frameToOverlay(src: RectF, out: RectF = RectF()): RectF {
        out.set(
            src.left * scaleX - originX,
            src.top * scaleY - originY,
            src.right * scaleX - originX,
            src.bottom * scaleY - originY
        )
        return out
    }

    /** Enlarges the box by [paddingPercent] of its size on every side and clamps it to the overlay. */
    fun padAndClamp(r: RectF, paddingPercent: Int, out: RectF = RectF()): RectF {
        val padX = r.width() * paddingPercent / 100f
        val padY = r.height() * paddingPercent / 100f
        out.set(
            (r.left - padX).coerceAtLeast(0f),
            (r.top - padY).coerceAtLeast(0f),
            (r.right + padX).coerceAtMost(overlayW),
            (r.bottom + padY).coerceAtMost(overlayH)
        )
        return out
    }
}
