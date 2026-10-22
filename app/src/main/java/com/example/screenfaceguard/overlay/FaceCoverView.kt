package com.example.screenfaceguard.overlay

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.view.View
import com.example.screenfaceguard.model.OverlayStyle
import kotlin.math.min

/** Full-screen transparent view that draws fully opaque black shapes. */
class FaceCoverView(context: Context) : View(context) {

    private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.BLACK
        alpha = 255
        style = Paint.Style.FILL
    }
    private val covers = ArrayList<RectF>()
    private var coverStyle = OverlayStyle.OVAL

    fun setCovers(rects: List<RectF>, style: OverlayStyle) {
        covers.clear()
        covers.addAll(rects)
        coverStyle = style
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        for (r in covers) {
            when (coverStyle) {
                OverlayStyle.RECTANGLE -> canvas.drawRect(r, paint)
                OverlayStyle.ROUNDED -> {
                    val radius = min(r.width(), r.height()) * 0.25f
                    canvas.drawRoundRect(r, radius, radius, paint)
                }
                OverlayStyle.OVAL -> canvas.drawOval(r, paint)
            }
        }
    }
}
