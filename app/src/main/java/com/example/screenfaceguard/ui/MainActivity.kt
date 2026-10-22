package com.example.screenfaceguard.ui

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.media.projection.MediaProjectionManager
import android.os.Bundle
import android.widget.SeekBar
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.example.screenfaceguard.classifier.TfliteGenderClassifier
import com.example.screenfaceguard.databinding.ActivityMainBinding
import com.example.screenfaceguard.model.OverlayStyle
import com.example.screenfaceguard.model.PerformanceMode
import com.example.screenfaceguard.model.ProtectionSettings
import com.example.screenfaceguard.permissions.PermissionHelper
import com.example.screenfaceguard.service.ProtectionService
import com.example.screenfaceguard.service.ProtectionState
import com.example.screenfaceguard.utils.SettingsStore
import kotlinx.coroutines.launch

class MainActivity : AppCompatActivity() {

    private lateinit var b: ActivityMainBinding
    private lateinit var store: SettingsStore
    private lateinit var projectionManager: MediaProjectionManager
    private var askedNotification = false

    private val overlayLauncher =
        registerForActivityResult(ActivityResultContracts.StartActivityForResult()) {
            if (PermissionHelper.canDrawOverlays(this)) {
                continueStart()
            } else {
                toast("Overlay permission is required to draw the black covers")
            }
        }

    private val notificationLauncher =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) {
            // Protection works even if notifications are denied, so continue.
            continueStart()
        }

    private val projectionLauncher =
        registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
            val data = result.data
            if (result.resultCode == Activity.RESULT_OK && data != null) {
                ProtectionService.start(this, result.resultCode, data)
            } else {
                toast("Screen-capture permission denied")
            }
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        b = ActivityMainBinding.inflate(layoutInflater)
        setContentView(b.root)

        store = SettingsStore(this)
        projectionManager = getSystemService(MediaProjectionManager::class.java)

        applySettingsToViews(store.load())

        b.seekThreshold.setOnSeekBarChangeListener(simpleSeek { updateLabels() })
        b.seekPadding.setOnSeekBarChangeListener(simpleSeek { updateLabels() })

        b.btnToggle.setOnClickListener {
            if (ProtectionState.running.value) {
                ProtectionService.stop(this)
            } else {
                store.save(readSettings())
                askedNotification = false
                continueStart()
            }
        }

        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                launch { ProtectionState.running.collect { updateUi(it) } }
                launch { ProtectionState.message.collect { if (it.isNotEmpty()) updateModelText() } }
            }
        }
    }

    override fun onResume() {
        super.onResume()
        updateModelText()
    }

    override fun onPause() {
        super.onPause()
        if (!ProtectionState.running.value) store.save(readSettings())
    }

    private fun continueStart() {
        if (!PermissionHelper.canDrawOverlays(this)) {
            toast("Please allow 'Display over other apps' for Screen Face Guard")
            overlayLauncher.launch(PermissionHelper.overlaySettingsIntent(this))
            return
        }
        if (PermissionHelper.needsNotificationPermission() &&
            !PermissionHelper.hasNotificationPermission(this) &&
            !askedNotification
        ) {
            askedNotification = true
            notificationLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
            return
        }
        projectionLauncher.launch(projectionManager.createScreenCaptureIntent())
    }

    private fun updateUi(running: Boolean) {
        b.txtStatus.text = if (running) "Protection ON" else "Protection OFF"
        b.btnToggle.text = if (running) "STOP PROTECTION" else "START PROTECTION"
        val enabled = !running
        b.seekThreshold.isEnabled = enabled
        b.seekPadding.isEnabled = enabled
        b.switchUncertain.isEnabled = enabled
        for (g in listOf(b.rgStyle, b.rgFps, b.rgPerf)) {
            for (i in 0 until g.childCount) g.getChildAt(i).isEnabled = enabled
        }
    }

    private fun updateModelText() {
        val files = assets.list("")?.toList().orEmpty()
        val ok = TfliteGenderClassifier.MODEL_FILE in files &&
            TfliteGenderClassifier.LABELS_FILE in files
        b.txtModelStatus.text = if (ok) {
            "Classifier model: found"
        } else {
            "Classifier model missing: add ${TfliteGenderClassifier.MODEL_FILE} and " +
                "${TfliteGenderClassifier.LABELS_FILE} to assets. Until then nothing is blocked."
        }
    }

    private fun applySettingsToViews(s: ProtectionSettings) {
        b.seekThreshold.progress = ((s.femaleThreshold * 100).toInt() - 50).coerceIn(0, 49)
        b.seekPadding.progress = s.paddingPercent.coerceIn(0, 40)
        b.switchUncertain.isChecked = s.blockUncertain
        b.rgStyle.check(
            when (s.overlayStyle) {
                OverlayStyle.RECTANGLE -> b.rbRect.id
                OverlayStyle.ROUNDED -> b.rbRounded.id
                OverlayStyle.OVAL -> b.rbOval.id
            }
        )
        b.rgFps.check(
            when (s.fps) {
                5 -> b.rbFps5.id
                15 -> b.rbFps15.id
                else -> b.rbFps10.id
            }
        )
        b.rgPerf.check(
            when (s.performanceMode) {
                PerformanceMode.BATTERY_SAVER -> b.rbBattery.id
                PerformanceMode.BALANCED -> b.rbBalanced.id
                PerformanceMode.FAST -> b.rbFast.id
            }
        )
        updateLabels()
    }

    private fun readSettings(): ProtectionSettings {
        val threshold = (50 + b.seekThreshold.progress) / 100f
        return store.load().copy(
            femaleThreshold = threshold,
            maleThreshold = threshold,
            blockUncertain = b.switchUncertain.isChecked,
            overlayStyle = when (b.rgStyle.checkedRadioButtonId) {
                b.rbRect.id -> OverlayStyle.RECTANGLE
                b.rbRounded.id -> OverlayStyle.ROUNDED
                else -> OverlayStyle.OVAL
            },
            paddingPercent = b.seekPadding.progress,
            fps = when (b.rgFps.checkedRadioButtonId) {
                b.rbFps5.id -> 5
                b.rbFps15.id -> 15
                else -> 10
            },
            performanceMode = when (b.rgPerf.checkedRadioButtonId) {
                b.rbBattery.id -> PerformanceMode.BATTERY_SAVER
                b.rbFast.id -> PerformanceMode.FAST
                else -> PerformanceMode.BALANCED
            }
        )
    }

    private fun updateLabels() {
        b.txtThreshold.text = "Female detection confidence: ${50 + b.seekThreshold.progress}%"
        b.txtPadding.text = "Face-cover padding: ${b.seekPadding.progress}%"
    }

    private fun simpleSeek(onChange: () -> Unit) = object : SeekBar.OnSeekBarChangeListener {
        override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) = onChange()
        override fun onStartTrackingTouch(seekBar: SeekBar?) {}
        override fun onStopTrackingTouch(seekBar: SeekBar?) {}
    }

    private fun toast(msg: String) = Toast.makeText(this, msg, Toast.LENGTH_LONG).show()
}
