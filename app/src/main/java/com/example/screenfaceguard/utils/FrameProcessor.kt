package com.example.screenfaceguard.utils

import android.graphics.Bitmap
import android.graphics.RectF
import android.os.SystemClock
import android.util.Log
import com.example.screenfaceguard.classifier.GenderClassifier
import com.example.screenfaceguard.detection.FaceDetectorManager
import com.example.screenfaceguard.model.Gender
import com.example.screenfaceguard.model.ProtectionSettings
import com.example.screenfaceguard.overlay.OverlayManager
import com.example.screenfaceguard.tracking.FaceTracker
import kotlin.math.min

/**
 * One frame: detect -> (classify when needed) -> track -> map -> overlay.
 * Runs on the capture thread, never on the UI thread.
 * If the classifier is not ready (model file missing) nothing is covered.
 */
class FrameProcessor(
    private val detector: FaceDetectorManager,
    private val classifier: GenderClassifier,
    private val tracker: FaceTracker,
    private val mapper: CoordinateMapper,
    private val overlay: OverlayManager,
    private val settingsProvider: () -> ProtectionSettings
) {

    fun process(frame: Bitmap, frameW: Int, frameH: Int) {
        try {
            val settings = settingsProvider()
            if (!classifier.isReady) {
                overlay.setCovers(emptyList(), settings.overlayStyle)
                return
            }

            val faces = detector.detect(frame)
            val now = SystemClock.elapsedRealtime()

            val blocked = tracker.update(
                faces, now, settings.holdMillis, settings.blockUncertain
            ) { box -> classifyFace(frame, frameW, frameH, box, settings) }

            val screen = overlay.screenSize()
            mapper.update(frameW, frameH, screen.x, screen.y)

            val covers = ArrayList<RectF>(blocked.size)
            for (b in blocked) {
                val onScreen = mapper.frameToOverlay(b)
                covers.add(mapper.padAndClamp(onScreen, settings.paddingPercent))
            }
            overlay.setCovers(covers, settings.overlayStyle)
        } catch (e: Exception) {
            Log.w("FrameProcessor", "Frame skipped", e)
        }
    }

    private fun classifyFace(
        frame: Bitmap,
        frameW: Int,
        frameH: Int,
        box: RectF,
        settings: ProtectionSettings
    ): Gender? {
        val maxX = min(frameW, frame.width)
        val maxY = min(frameH, frame.height)
        val mx = box.width() * 0.1f
        val my = box.height() * 0.1f
        val l = (box.left - mx).toInt().coerceIn(0, maxX - 1)
        val t = (box.top - my).toInt().coerceIn(0, maxY - 1)
        val r = (box.right + mx).toInt().coerceIn(l + 1, maxX)
        val b = (box.bottom + my).toInt().coerceIn(t + 1, maxY)
        val w = r - l
        val h = b - t
        if (w < 16 || h < 16) return null

        val crop = Bitmap.createBitmap(frame, l, t, w, h)
        return try {
            classifier.classify(crop)?.classify(settings)
        } finally {
            if (crop !== frame) crop.recycle()
        }
    }
}
