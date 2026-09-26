package com.example.budsplitter

import android.Manifest
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.media.AudioManager
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.widget.Button
import android.widget.SeekBar
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.net.toUri
import com.google.android.material.switchmaterial.SwitchMaterial
import java.lang.ref.WeakReference

class MainActivity : AppCompatActivity() {

    private var isRunning = false
    private lateinit var statusText: TextView
    private lateinit var toggleButton: Button
    private lateinit var volumeLabel: TextView
    private lateinit var micGainSlider: SeekBar
    private lateinit var modeSwitch: SwitchMaterial
    private lateinit var btnBalanceSettings: Button

    private lateinit var audioManager: AudioManager

    // 1. Multi-permission launcher for Mic & Android 13+ Notifications
    private val permissionsLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { permissions ->
        val micGranted = permissions[Manifest.permission.RECORD_AUDIO] ?: false
        val notifGranted = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            permissions[Manifest.permission.POST_NOTIFICATIONS] ?: false
        } else {
            true
        }

        if (micGranted && notifGranted) {
            requestMediaProjection()
        } else {
            showRequiredDialog(
                title = "Permissions Required",
                message = "BudSplitter requires Microphone and Notification permissions to run the background audio engine and control volume."
            ) {
                val intent = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
                    data = "package:$packageName".toUri()
                }
                startActivity(intent)
            }
        }
    }

    // 2. MediaProjection screen/audio capture launcher
    private val captureLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == RESULT_OK && result.data != null) {
            launchAudioPipeline(result.resultCode, result.data!!)
        } else {
            showRequiredDialog(
                title = "Audio Capture Required",
                message = "BudSplitter requires audio capture access to isolate YouTube and Spotify to your Left earbud."
            ) {
                requestMediaProjection()
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        activityRef = WeakReference(this)
        audioManager = getSystemService(Context.AUDIO_SERVICE) as AudioManager

        statusText = findViewById(R.id.statusText)
        toggleButton = findViewById(R.id.toggleButton)
        volumeLabel = findViewById(R.id.volumeLabel)
        micGainSlider = findViewById(R.id.micGainSlider)
        modeSwitch = findViewById(R.id.modeSwitch)
        btnBalanceSettings = findViewById(R.id.btnBalanceSettings)

        micGainSlider.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                volumeLabel.text = getString(R.string.ambient_mic_gain_format, progress)
                nativeSetMicGain(progress / 100.0f)
            }
            override fun onStartTrackingTouch(seekBar: SeekBar?) {}
            override fun onStopTrackingTouch(seekBar: SeekBar?) {}
        })

        modeSwitch.setOnCheckedChangeListener { _, isChecked ->
            if (isChecked) {
                modeSwitch.text = getString(R.string.mode_split)
                nativeSetRoutingMode(0)
            } else {
                modeSwitch.text = getString(R.string.mode_dual)
                nativeSetRoutingMode(1)
            }
        }

        btnBalanceSettings.setOnClickListener {
            openSamsungHearingEnhancements()
        }

        toggleButton.setOnClickListener {
            if (isRunning) {
                stopAudioPipeline()
            } else {
                checkAndRequestPermissions()
            }
        }
    }

    private fun checkAndRequestPermissions() {
        val permissionsNeeded = mutableListOf(Manifest.permission.RECORD_AUDIO)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            permissionsNeeded.add(Manifest.permission.POST_NOTIFICATIONS)
        }

        val allGranted = permissionsNeeded.all {
            ContextCompat.checkSelfPermission(this, it) == PackageManager.PERMISSION_GRANTED
        }

        if (!allGranted) {
            permissionsLauncher.launch(permissionsNeeded.toTypedArray())
            return
        }

        requestMediaProjection()
    }

    private fun requestMediaProjection() {
        val projectionManager = getSystemService(MediaProjectionManager::class.java)
        projectionManager?.let {
            captureLauncher.launch(it.createScreenCaptureIntent())
        }
    }

    private fun showRequiredDialog(title: String, message: String, onRetry: () -> Unit) {
        AlertDialog.Builder(this)
            .setTitle(title)
            .setMessage(message)
            .setCancelable(false)
            .setPositiveButton("Grant Access") { _, _ -> onRetry() }
            .setNegativeButton("Exit") { _, _ -> finish() }
            .show()
    }

    private fun openSamsungHearingEnhancements() {
        val intents = listOf(
            Intent().setComponent(ComponentName("com.android.settings", "com.android.settings.Settings\$HearingEnhancementActivity")),
            Intent("com.samsung.accessibility.HEARING_ENHANCEMENT"),
            Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)
        )

        for (targetIntent in intents) {
            try {
                targetIntent.flags = Intent.FLAG_ACTIVITY_NEW_TASK
                startActivity(targetIntent)
                Toast.makeText(this, "Slide balance to CENTER before starting, then slide LEFT once running", Toast.LENGTH_LONG).show()
                return
            } catch (_: Exception) {}
        }
        startActivity(Intent(Settings.ACTION_SETTINGS))
    }

    private fun launchAudioPipeline(resultCode: Int, data: Intent) {
        // Enforce normal high-quality stereo media mode
        try {
            audioManager.mode = AudioManager.MODE_NORMAL
        } catch (_: Exception) {}

        nativeStartAudio()
        nativeSetMicGain(micGainSlider.progress / 100.0f)
        nativeSetRoutingMode(if (modeSwitch.isChecked) 0 else 1)

        val serviceIntent = Intent(this, AudioCaptureService::class.java).apply {
            putExtra(AudioCaptureService.EXTRA_RESULT_CODE, resultCode)
            putExtra(AudioCaptureService.EXTRA_RESULT_DATA, data)
        }
        ContextCompat.startForegroundService(this, serviceIntent)

        isRunning = true
        statusText.text = getString(R.string.status_streaming)
        toggleButton.text = getString(R.string.btn_stop_audio)
    }

    private fun stopAudioPipeline() {
        try {
            audioManager.mode = AudioManager.MODE_NORMAL
        } catch (_: Exception) {}

        val stopIntent = Intent(this, AudioCaptureService::class.java).apply {
            action = AudioCaptureService.ACTION_STOP
        }
        startService(stopIntent)

        nativeStopAudio()
        isRunning = false
        statusText.text = getString(R.string.status_stopped)
        toggleButton.text = getString(R.string.btn_start_audio)
    }

    override fun onDestroy() {
        activityRef = null
        super.onDestroy()
        if (isRunning) {
            stopAudioPipeline()
        }
    }

    private external fun nativeStartAudio()
    private external fun nativeStopAudio()
    private external fun nativeSetMicGain(gain: Float)
    private external fun nativeSetRoutingMode(mode: Int)

    companion object {
        private var activityRef: WeakReference<MainActivity>? = null

        fun updateNativeGain(gain: Float) {
            activityRef?.get()?.let { activity ->
                activity.runOnUiThread {
                    activity.micGainSlider.progress = (gain * 100).toInt()
                    activity.volumeLabel.text = activity.getString(
                        R.string.ambient_mic_gain_format,
                        (gain * 100).toInt()
                    )
                }
                activity.nativeSetMicGain(gain)
            }
        }

        init {
            System.loadLibrary("budsplitter")
        }
    }
}