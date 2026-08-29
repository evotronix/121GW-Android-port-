package com.eevblog.gw121.data

import android.content.Context
import android.content.SharedPreferences
import android.media.AudioManager
import android.media.ToneGenerator
import android.os.Build
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import com.eevblog.gw121.model.PacketV2
import kotlinx.coroutines.flow.MutableStateFlow

class AppSettings(context: Context) {
    private val p: SharedPreferences = context.getSharedPreferences("121gw", Context.MODE_PRIVATE)

    var continuitySoundEnabled = MutableStateFlow(p.getBoolean("cont_sound", true))
    var continuityVibrationEnabled = MutableStateFlow(p.getBoolean("cont_vibration", true))
    var continuityOnThreshold = MutableStateFlow(p.getFloat("cont_on_threshold", 25f).toDouble())
    var continuityOffThreshold = MutableStateFlow(p.getFloat("cont_off_threshold", 400f).toDouble())

    fun setSound(v: Boolean) { continuitySoundEnabled.value = v; p.edit().putBoolean("cont_sound", v).apply() }
    fun setVibration(v: Boolean) { continuityVibrationEnabled.value = v; p.edit().putBoolean("cont_vibration", v).apply() }
    fun setOn(v: Double) { continuityOnThreshold.value = v; p.edit().putFloat("cont_on_threshold", v.toFloat()).apply(); normalize() }
    fun setOff(v: Double) { continuityOffThreshold.value = v; p.edit().putFloat("cont_off_threshold", v.toFloat()).apply(); normalize() }

    fun normalize() {
        var on = continuityOnThreshold.value
        var off = continuityOffThreshold.value
        if (!on.isFinite() || on < 0) on = 0.0
        if (!off.isFinite()) off = on + 1
        if (off < on + 1) off = on + 1
        continuityOnThreshold.value = on
        continuityOffThreshold.value = off
        p.edit()
            .putFloat("cont_on_threshold", on.toFloat())
            .putFloat("cont_off_threshold", off.toFloat())
            .apply()
    }

    companion object {
        fun parseOhms(text: String): Double? =
            text.replace(",", ".").trim().toDoubleOrNull()?.takeIf { it.isFinite() }
    }
}

class ContinuityEngine(private val context: Context, private val settings: AppSettings) {
    var isClosed = MutableStateFlow(false)
    private var lastBeep = 0L
    private val minInterval = 450L
    private val tone by lazy { ToneGenerator(AudioManager.STREAM_ALARM, 80) }
    private val vibrator: Vibrator? = if (Build.VERSION.SDK_INT >= 31) {
        context.getSystemService(VibratorManager::class.java)?.defaultVibrator
    } else {
        @Suppress("DEPRECATION")
        context.getSystemService(Context.VIBRATOR_SERVICE) as? Vibrator
    }

    fun evaluate(packet: PacketV2) {
        when {
            packet.isContinuityMode || packet.isResistanceMode -> evaluateOhms(packet.siValue)
            packet.isDiodeMode -> evaluateDiode(packet)
            else -> if (isClosed.value) isClosed.value = false
        }
    }

    private fun evaluateOhms(ohms: Double) {
        if (!ohms.isFinite() || ohms < 0) {
            if (isClosed.value) isClosed.value = false
            return
        }
        val onT = settings.continuityOnThreshold.value
        val offT = maxOf(settings.continuityOffThreshold.value, onT + 1)
        if (isClosed.value) {
            if (ohms > offT) isClosed.value = false else trigger(false)
        } else if (ohms < onT) {
            isClosed.value = true
            trigger(true)
        }
    }

    private fun evaluateDiode(packet: PacketV2) {
        if (packet.isMainOverflow || !packet.mainValue.isFinite()) {
            if (isClosed.value) isClosed.value = false
            return
        }
        if (!isClosed.value) {
            isClosed.value = true
            trigger(true)
        } else trigger(false)
    }

    fun reset() { isClosed.value = false }

    private fun trigger(force: Boolean) {
        val now = System.currentTimeMillis()
        if (!force && now - lastBeep < minInterval) return
        lastBeep = now
        if (settings.continuitySoundEnabled.value) {
            try { tone.startTone(ToneGenerator.TONE_PROP_BEEP, 80) } catch (_: Exception) {}
        }
        if (settings.continuityVibrationEnabled.value) {
            val ms = if (force) 40L else 20L
            if (Build.VERSION.SDK_INT >= 26) {
                vibrator?.vibrate(VibrationEffect.createOneShot(ms, VibrationEffect.DEFAULT_AMPLITUDE))
            } else {
                @Suppress("DEPRECATION")
                vibrator?.vibrate(ms)
            }
        }
    }
}
