package com.example.screenfaceguard.capture

import android.graphics.Bitmap
import android.graphics.PixelFormat
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.Image
import android.media.ImageReader
import android.media.projection.MediaProjection
import android.os.Handler
import android.os.HandlerThread
import android.os.SystemClock
import android.util.Log

/**
 * Captures the screen with MediaProjection at a reduced size.
 * Frames are throttled to the chosen FPS; older frames are dropped so the
 * newest one is always processed. The frame Bitmap is REUSED, so [onFrame]
 * must finish with it before returning. Android 14+ allows only one
 * VirtualDisplay per projection, so rotation uses resize() + setSurface().
 */
class ScreenCaptureManager(
    private val projection: MediaProjection,
    private val onFrame: (frame: Bitmap, frameWidth: Int, frameHeight: Int) -> Unit,
    private val onProjectionStopped: () -> Unit
) {
    private companion object {
        const val TAG = "ScreenCapture"
    }

    private var thread: HandlerThread? = null
    private var handler: Handler? = null
    private var reader: ImageReader? = null
    private var display: VirtualDisplay? = null
    private var frameBitmap: Bitmap? = null
    private var pending: Image? = null
    private var scale = 0.5f

    @Volatile private var running = false
    @Volatile private var minIntervalMs = 100L
    private var lastFrameTime = 0L

    private val projectionCallback = object : MediaProjection.Callback() {
        override fun onStop() {
            stop()
            onProjectionStopped()
        }
    }

    private val processPending = Runnable {
        val img = pending
        pending = null
        if (img != null) processImage(img)
    }

    fun setFps(fps: Int) {
        minIntervalMs = 1000L / fps.coerceIn(1, 30)
    }

    @Synchronized
    fun start(screenW: Int, screenH: Int, densityDpi: Int, scale: Float, fps: Int) {
        if (running) return
        running = true
        this.scale = scale
        setFps(fps)

        val t = HandlerThread("ScreenCapture").also { it.start() }
        thread = t
        val h = Handler(t.looper)
        handler = h

        // Required on Android 14+ before creating the VirtualDisplay.
        projection.registerCallback(projectionCallback, h)

        val (w, ht) = scaledSize(screenW, screenH)
        val r = createReader(w, ht)
        reader = r
        display = projection.createVirtualDisplay(
            "ScreenFaceGuard", w, ht, densityDpi,
            DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
            r.surface, null, h
        )
    }

    /** Call after rotation / resolution change. */
    @Synchronized
    fun updateSize(screenW: Int, screenH: Int, densityDpi: Int) {
        val d = display ?: return
        val (w, h) = scaledSize(screenW, screenH)
        val newReader = createReader(w, h)
        d.resize(w, h, densityDpi)
        d.surface = newReader.surface
        reader?.close()
        reader = newReader
        pending = null
        lastFrameTime = 0L
    }

    /** Immediately stops capture and releases everything. Safe to call repeatedly. */
    @Synchronized
    fun stop() {
        if (!running) return
        running = false
        try { projection.unregisterCallback(projectionCallback) } catch (_: Exception) {}
        handler?.removeCallbacksAndMessages(null)
        display?.release()
        display = null
        reader?.setOnImageAvailableListener(null, null)
        reader?.close()
        reader = null
        pending = null
        try { projection.stop() } catch (_: Exception) {}
        frameBitmap = null
        thread?.quitSafely()
        thread = null
        handler = null
    }

    private fun scaledSize(screenW: Int, screenH: Int): Pair<Int, Int> {
        val w = (screenW * scale).toInt().coerceAtLeast(64)
        val h = (screenH * scale).toInt().coerceAtLeast(64)
        return Pair(w, h)
    }

    private fun createReader(w: Int, h: Int): ImageReader {
        val r = ImageReader.newInstance(w, h, PixelFormat.RGBA_8888, 3)
        r.setOnImageAvailableListener({ handleImage(it) }, handler)
        return r
    }

    // Runs on the capture thread.
    private fun handleImage(r: ImageReader) {
        val image = runCatching { r.acquireLatestImage() }.getOrNull() ?: return
        if (!running) {
            image.close()
            return
        }
        pending?.close()
        pending = null

        val wait = minIntervalMs - (SystemClock.elapsedRealtime() - lastFrameTime)
        if (wait > 0) {
            // Keep the newest frame and process it when the interval has passed,
            // so the last frame of a scroll is never lost.
            pending = image
            handler?.removeCallbacks(processPending)
            handler?.postDelayed(processPending, wait)
            return
        }
        processImage(image)
    }

    private fun processImage(image: Image) {
        try {
            if (!running) return
            lastFrameTime = SystemClock.elapsedRealtime()
            val plane = image.planes[0]
            val paddedWidth = plane.rowStride / plane.pixelStride
            var bmp = frameBitmap
            if (bmp == null || bmp.width != paddedWidth || bmp.height != image.height) {
                bmp = Bitmap.createBitmap(paddedWidth, image.height, Bitmap.Config.ARGB_8888)
                frameBitmap = bmp
            }
            plane.buffer.rewind()
            bmp.copyPixelsFromBuffer(plane.buffer)
            onFrame(bmp, image.width, image.height)
        } catch (e: Exception) {
            Log.w(TAG, "Frame processing error", e)
        } finally {
            image.close()
        }
    }
}
