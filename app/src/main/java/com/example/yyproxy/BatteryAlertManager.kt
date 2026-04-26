package com.example.yyproxy

import android.content.Context
import android.media.MediaPlayer
import android.os.Build
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import android.util.Log

/**
 * 负责低电量时的音频播放和振动提示。
 */
class BatteryAlertManager(private val context: Context) {

    private var mediaPlayer: MediaPlayer? = null

    /**
     * 播放提示音并触发振动。
     */
    fun triggerAlert() {
        Log.i("BatteryAlertManager", "Triggering looping low battery alert")
        playLowPowerSoundLooping()
        vibrateRepeating()
    }

    private fun playLowPowerSoundLooping() {
        try {
            mediaPlayer?.stop()
            mediaPlayer?.release()
            
            mediaPlayer = MediaPlayer.create(context, R.raw.lowpower).apply {
                isLooping = true
                start()
            }
        } catch (e: Exception) {
            Log.e("BatteryAlertManager", "Failed to play looping low power sound", e)
        }
    }

    private fun vibrateRepeating() {
        try {
            val vibrator = getVibrator()
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                // Looping pattern: 1s vibrate, 1s silent
                val pattern = longArrayOf(0, 1000, 1000)
                val effect = VibrationEffect.createWaveform(pattern, 0) // 0 means loop from index 0
                vibrator.vibrate(effect)
            } else {
                @Suppress("DEPRECATION")
                val pattern = longArrayOf(0, 1000, 1000)
                vibrator.vibrate(pattern, 0)
            }
        } catch (e: Exception) {
            Log.e("BatteryAlertManager", "Failed to trigger repeating vibration", e)
        }
    }

    private fun getVibrator(): Vibrator {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            val vibratorManager = context.getSystemService(Context.VIBRATOR_MANAGER_SERVICE) as VibratorManager
            vibratorManager.defaultVibrator
        } else {
            @Suppress("DEPRECATION")
            context.getSystemService(Context.VIBRATOR_SERVICE) as Vibrator
        }
    }

    fun stopAlert() {
        Log.i("BatteryAlertManager", "Stopping low battery alert")
        try {
            mediaPlayer?.stop()
            mediaPlayer?.release()
            mediaPlayer = null
            getVibrator().cancel()
        } catch (e: Exception) {
            Log.e("BatteryAlertManager", "Error stopping alert", e)
        }
    }

    /**
     * 释放资源。
     */
    fun release() {
        mediaPlayer?.release()
        mediaPlayer = null
    }
}
