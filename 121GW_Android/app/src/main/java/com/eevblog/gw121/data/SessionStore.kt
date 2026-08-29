package com.eevblog.gw121.data

import android.content.Context
import com.eevblog.gw121.model.PacketV2
import com.eevblog.gw121.model.graphFullScale
import com.eevblog.gw121.model.graphLowerBound
import com.eevblog.gw121.model.graphUnit
import com.eevblog.gw121.model.graphValue
import com.google.gson.Gson
import com.google.gson.GsonBuilder
import com.google.gson.reflect.TypeToken
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.UUID

data class DataPoint(
    val id: String = UUID.randomUUID().toString(),
    val timestamp: Long = System.currentTimeMillis(),
    val value: Double,
    val unit: String,
    val display: String,
    val plotValue: Double,
    val plotUnit: String,
    val plotFullScale: Double,
    val plotLowerBound: Double,
    val modeRaw: Int,
    val rangeIndex: Int,
    val temperatureValue: Double? = null,
    val temperatureUnit: String? = null
)

data class MeasurementSession(
    val id: String = UUID.randomUUID().toString(),
    var name: String,
    val createdAt: Long = System.currentTimeMillis(),
    var points: List<DataPoint> = emptyList(),
    var notes: String = ""
) {
    val durationMs: Long
        get() = if (points.size < 2) 0 else points.last().timestamp - points.first().timestamp

    val finiteValues: List<Double> get() = points.map { it.plotValue }.filter { it.isFinite() }
    val graphUnit: String get() = points.lastOrNull { it.plotUnit.isNotEmpty() }?.plotUnit
        ?: points.lastOrNull()?.unit ?: ""
    val minValue: Double? get() = finiteValues.minOrNull()
    val maxValue: Double? get() = finiteValues.maxOrNull()
    val average: Double? get() = finiteValues.takeIf { it.isNotEmpty() }?.average()
    val peakToPeak: Double?
        get() {
            val mn = minValue ?: return null
            val mx = maxValue ?: return null
            return mx - mn
        }

    fun csvString(): String {
        val df = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSSXXX", Locale.US)
        val lines = mutableListOf("timestamp,value,unit,display,plot_value,plot_unit,plot_full_scale,plot_lower_bound,mode,range")
        for (p in points) {
            val display = p.display.replace("\"", "\"\"")
            lines += "${df.format(Date(p.timestamp))},${p.value},${p.unit},\"$display\",${p.plotValue},${p.plotUnit},${p.plotFullScale},${p.plotLowerBound},${p.modeRaw},${p.rangeIndex}"
        }
        return lines.joinToString("\n")
    }
}

class SessionStore(private val context: Context) {
    private val gson: Gson = GsonBuilder().create()
    private val saveFile = File(context.filesDir, "sessions.json")

    private val _sessions = MutableStateFlow<List<MeasurementSession>>(emptyList())
    val sessions = _sessions.asStateFlow()

    private val _currentPoints = MutableStateFlow<List<DataPoint>>(emptyList())
    val currentPoints = _currentPoints.asStateFlow()

    var isLogging = MutableStateFlow(false)
    var liveMin = MutableStateFlow<Double?>(null)
    var liveMax = MutableStateFlow<Double?>(null)
    private var logFingerprint: String? = null

    init { load() }

    fun startLogging() {
        _currentPoints.value = emptyList()
        liveMin.value = null
        liveMax.value = null
        logFingerprint = null
        isLogging.value = true
    }

    fun stopLogging() { isLogging.value = false }

    fun discardCurrent() {
        _currentPoints.value = emptyList()
        liveMin.value = null
        liveMax.value = null
        logFingerprint = null
        isLogging.value = false
    }

    /** @return false if function changed and logging was paused */
    fun append(packet: PacketV2): Boolean {
        val v = packet.mainValue
        if (!isLogging.value || !v.isFinite()) return true
        val fp = packet.measureFingerprint
        if (logFingerprint != null && logFingerprint != fp && _currentPoints.value.isNotEmpty()) {
            isLogging.value = false
            return false
        }
        if (logFingerprint == null) logFingerprint = fp
        val temp = if (packet.subIsTemperature && packet.subValue.isFinite()) packet.subValue else null
        val pt = DataPoint(
            value = v,
            unit = packet.mainUnit,
            display = packet.displayString,
            plotValue = packet.graphValue,
            plotUnit = packet.graphUnit,
            plotFullScale = packet.graphFullScale,
            plotLowerBound = packet.graphLowerBound,
            modeRaw = packet.modeRaw,
            rangeIndex = packet.mainRangeIndex,
            temperatureValue = temp,
            temperatureUnit = if (packet.subIsTemperature) packet.subUnit else null
        )
        _currentPoints.value = _currentPoints.value + pt
        if (pt.plotValue.isFinite()) {
            liveMin.value = minOf(liveMin.value ?: pt.plotValue, pt.plotValue)
            liveMax.value = maxOf(liveMax.value ?: pt.plotValue, pt.plotValue)
        }
        return true
    }

    fun saveCurrentSession(name: String, notes: String = "") {
        val pts = _currentPoints.value
        if (pts.isEmpty()) return
        val session = MeasurementSession(name = name, points = pts, notes = notes)
        _sessions.value = listOf(session) + _sessions.value
        persist()
        discardCurrent()
    }

    fun delete(session: MeasurementSession) {
        _sessions.value = _sessions.value.filter { it.id != session.id }
        persist()
    }

    fun updateNotes(session: MeasurementSession, notes: String) {
        _sessions.value = _sessions.value.map {
            if (it.id == session.id) it.copy(notes = notes) else it
        }
        persist()
    }

    fun clearAll() {
        _sessions.value = emptyList()
        persist()
    }

    fun exportCsvFile(session: MeasurementSession): File {
        val safe = session.name.replace(" ", "_").replace("/", "-")
        val f = File(context.cacheDir, "$safe.csv")
        f.writeText(session.csvString())
        return f
    }

    private fun persist() {
        saveFile.writeText(gson.toJson(_sessions.value))
    }

    private fun load() {
        if (!saveFile.exists()) return
        try {
            val type = object : TypeToken<List<MeasurementSession>>() {}.type
            _sessions.value = gson.fromJson(saveFile.readText(), type) ?: emptyList()
        } catch (_: Exception) { }
    }
}
