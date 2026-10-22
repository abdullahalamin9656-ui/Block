package com.example.screenfaceguard.service

import android.app.Activity
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.content.res.Configuration
import android.media.projection.MediaProjectionManager
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import androidx.core.content.IntentCompat
import com.example.screenfaceguard.R
import com.example.screenfaceguard.capture.ScreenCaptureManager
import com.example.screenfaceguard.classifier.TfliteGenderClassifier
import com.example.screenfaceguard.detection.FaceDetectorManager
import com.example.screenfaceguard.model.ProtectionSettings
import com.example.screenfaceguard.overlay.OverlayManager
import com.example.screenfaceguard.permissions.PermissionHelper
import com.example.screenfaceguard.tracking.FaceTracker
import com.example.screenfaceguard.ui.MainActivity
import com.example.screenfaceguard.utils.CoordinateMapper
import com.example.screenfaceguard.utils.FrameProcessor
import com.example.screenfaceguard.utils.SettingsStore

/** Foreground service that owns capture, ML, tracking and the overlay. */
class ProtectionService : Service() {

    companion object {
        const val ACTION_START = "com.example.screenfaceguard.START"
        const val ACTION_STOP = "com.example.screenfaceguard.STOP"
        const val EXTRA_RESULT_CODE = "result_code"
        const val EXTRA_RESULT_DATA = "result_data"
        private const val CHANNEL_ID = "protection"
        private const val NOTIFICATION_ID = 1
        private const val TAG = "ProtectionService"

        fun start(context: Context, resultCode: Int, data: Intent) {
            val i = Intent(context, ProtectionService::class.java)
                .setAction(ACTION_START)
                .putExtra(EXTRA_RESULT_CODE, resultCode)
                .putExtra(EXTRA_RESULT_DATA, data)
            ContextCompat.startForegroundService(context, i)
        }

        fun stop(context: Context) {
            context.stopService(Intent(context, ProtectionService::class.java))
        }
    }

    private val mainHandler = Handler(Looper.getMainLooper())

    private var detector: FaceDetectorManager? = null
    private var classifier: TfliteGenderClassifier? = null
    private var overlay: OverlayManager? = null
    private var capture: ScreenCaptureManager? = null
    private val tracker = FaceTracker()

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_START -> startProtection(intent)
            ACTION_STOP -> shutdown()
            else -> shutdown()
        }
        return START_NOT_STICKY
    }

    private fun startProtection(intent: Intent) {
        // Must be called first: the service was started with startForegroundService().
        goForeground("Starting protection…")
        if (ProtectionState.running.value) return

        val resultCode = intent.getIntExtra(EXTRA_RESULT_CODE, Activity.RESULT_CANCELED)
        val data = IntentCompat.getParcelableExtra(intent, EXTRA_RESULT_DATA, Intent::class.java)
        if (resultCode != Activity.RESULT_OK || data == null) {
            fail("Screen-capture permission was not granted")
            return
        }
        if (!PermissionHelper.canDrawOverlays(this)) {
            fail("Overlay permission is missing")
            return
        }

        try {
            val settings: ProtectionSettings = SettingsStore(this).load()

            val det = FaceDetectorManager()
            val cls = TfliteGenderClassifier(this)
            val ovl = OverlayManager(this)
            detector = det
            classifier = cls
            overlay = ovl

            if (!ovl.show()) {
                fail("Could not create the overlay")
                return
            }

            val projection = getSystemService(MediaProjectionManager::class.java)
                .getMediaProjection(resultCode, data)
            if (projection == null) {
                fail("Could not start screen capture")
                return
            }

            val processor = FrameProcessor(det, cls, tracker, CoordinateMapper(), ovl) { settings }
            val cap = ScreenCaptureManager(
                projection = projection,
                onFrame = { frame, w, h -> processor.process(frame, w, h) },
                onProjectionStopped = { mainHandler.post { shutdown() } }
            )
            capture = cap

            val size = ovl.screenSize()
            cap.start(size.x, size.y, ovl.densityDpi, settings.captureScale, settings.fps)

            ProtectionState.running.value = true
            ProtectionState.message.value = cls.statusMessage
            updateNotification(
                if (cls.isReady) "Protection ON – covering faces"
                else "Model not ready (${cls.statusMessage}) – nothing is blocked"
            )
        } catch (t: Throwable) {
            Log.e(TAG, "Start failed", t)
            fail("Could not start: ${t.message}")
        }
    }

    // Rotation / resolution change.
    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        val ovl = overlay ?: return
        if (!ProtectionState.running.value) return
        ovl.updateSize()
        val size = ovl.screenSize()
        capture?.updateSize(size.x, size.y, ovl.densityDpi)
        tracker.clear()
        ovl.setCovers(emptyList(), com.example.screenfaceguard.model.OverlayStyle.OVAL)
    }

    override fun onDestroy() {
        cleanup()
        super.onDestroy()
    }

    private fun fail(message: String) {
        ProtectionState.message.value = message
        shutdown()
    }

    private fun shutdown() {
        cleanup()
        ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    /** Stops capture, removes the overlay and releases all ML resources. */
    private fun cleanup() {
        capture?.stop()
        capture = null
        overlay?.hide()
        overlay = null
        tracker.clear()
        detector?.close()
        detector = null
        classifier?.close()
        classifier = null
        ProtectionState.running.value = false
    }

    private fun goForeground(text: String) {
        ServiceCompat.startForeground(
            this,
            NOTIFICATION_ID,
            buildNotification(text),
            ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION
        )
    }

    private fun updateNotification(text: String) {
        getSystemService(NotificationManager::class.java)
            .notify(NOTIFICATION_ID, buildNotification(text))
    }

    private fun buildNotification(text: String): Notification {
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(
            NotificationChannel(CHANNEL_ID, "Protection status", NotificationManager.IMPORTANCE_LOW)
        )
        val flags = PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        val openApp = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java), flags
        )
        val stopIntent = PendingIntent.getService(
            this, 1,
            Intent(this, ProtectionService::class.java).setAction(ACTION_STOP), flags
        )
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle("Screen Face Guard")
            .setContentText(text)
            .setStyle(NotificationCompat.BigTextStyle().bigText(text))
            .setOngoing(true)
            .setContentIntent(openApp)
            .addAction(0, "STOP PROTECTION", stopIntent)
            .build()
    }
}
