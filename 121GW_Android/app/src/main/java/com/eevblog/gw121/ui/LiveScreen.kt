package com.eevblog.gw121.ui

import android.view.MotionEvent
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Battery0Bar
import androidx.compose.material.icons.filled.Battery1Bar
import androidx.compose.material.icons.filled.BatteryFull
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.pointerInteropFilter
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.eevblog.gw121.ble.BleManager
import com.eevblog.gw121.ble.MeterKey
import com.eevblog.gw121.data.DataPoint
import com.eevblog.gw121.data.SessionStore
import com.eevblog.gw121.model.Coupling
import com.eevblog.gw121.model.PacketV2
import com.eevblog.gw121.model.setupDescriptor
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

private val LcdBg = Color(0xFFB8C7B2)
private val LcdInk = Color(0xFF1F241F)
private val LcdDim = LcdInk.copy(alpha = 0.28f)

@Composable
fun LiveScreen(ble: BleManager, store: SessionStore, onAskName: () -> Unit) {
    val packet by ble.latestPacket.collectAsState()
    val display by ble.liveDisplay.collectAsState()
    val status by ble.statusText.collectAsState()
    val lost by ble.communicationLost.collectAsState()
    val connected by ble.isConnected.collectAsState()
    val rssi by ble.rssi.collectAsState()
    val batV by ble.batteryVoltage.collectAsState()
    val meterTime by ble.meterTime.collectAsState()
    val inSetup by ble.inSetupMenu.collectAsState()
    val logging by store.isLogging.collectAsState()
    val points by store.currentPoints.collectAsState()
    val liveMin by store.liveMin.collectAsState()
    val liveMax by store.liveMax.collectAsState()
    var showTemp by remember { mutableStateOf(true) }
    var landscape by remember { mutableStateOf(false) }

    Column(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.surfaceVariant)) {
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            SignalRow(rssi, connected)
            Spacer(Modifier.weight(1f))
            Text("121GW", fontWeight = FontWeight.SemiBold)
            Spacer(Modifier.weight(1f))
            BatteryRow(batV, ble.batteryLow)
            if (meterTime != null) Text("  $meterTime", fontSize = 11.sp, fontFamily = FontFamily.Monospace)
        }

        Column(Modifier.verticalScroll(rememberScrollState()).padding(bottom = 16.dp)) {
            when {
                lost -> Banner("121GW communication lost", Color(0xFFFFCDD2), Color(0xFFB71C1C))
                ble.batteryNearPowerOff -> Banner(
                    batV?.let { "Meter battery low: %.2f V — near power-off".format(it) } ?: "Meter battery low — near power-off",
                    Color(0xFFFFCDD2), Color(0xFFB71C1C)
                )
                ble.batteryLow -> Banner(
                    batV?.let { "Meter battery low: %.2f V".format(it) } ?: "Meter battery low",
                    Color(0xFFFFE0B2), Color(0xFFE65100)
                )
            }

            MeterLcd(packet, display, status, inSetup)
            MeterPad(ble)
            Surface(Modifier.padding(16.dp), shape = RoundedCornerShape(14.dp), tonalElevation = 1.dp) {
                Column(Modifier.padding(12.dp)) {
                    Row {
                        Text("Trend", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        Spacer(Modifier.weight(1f))
                        if (logging && liveMin != null && liveMax != null) {
                            Text(
                                "min %.4g  max %.4g %s".format(liveMin, liveMax, points.lastOrNull()?.plotUnit ?: ble.liveUnit.value),
                                fontSize = 11.sp, fontFamily = FontFamily.Monospace
                            )
                        }
                    }
                    if (packet?.subIsTemperature == true) {
                        TextButton(onClick = { showTemp = !showTemp }) {
                            Text(
                                if (showTemp) "Temperature monitoring: ${packet!!.subUnit}"
                                else "Temperature monitoring: hidden",
                                color = if (showTemp) Color(0xFFE65100) else MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                    ZoomableTrend(
                        points = points.takeLast(25_000),
                        unit = points.lastOrNull()?.unit ?: ble.liveUnit.value,
                        showTemperature = showTemp,
                        onTap = { landscape = true },
                        modifier = Modifier.fillMaxWidth().height(200.dp)
                    )
                }
            }
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.Center) {
                Button(
                    onClick = {
                        if (logging) {
                            store.stopLogging()
                            if (store.currentPoints.value.isNotEmpty()) onAskName()
                        } else store.startLogging()
                    },
                    enabled = connected || logging,
                    colors = ButtonDefaults.buttonColors(
                        containerColor = if (logging) Color(0xFFC62828) else MaterialTheme.colorScheme.primary
                    )
                ) { Text(if (logging) "Stop & Save" else "Start Log") }
            }
            if (logging) {
                Text("${points.size} samples", modifier = Modifier.align(Alignment.CenterHorizontally), fontSize = 12.sp)
            }
        }
    }

    if (landscape) {
        androidx.compose.ui.window.Dialog(onDismissRequest = { landscape = false }) {
            Surface(Modifier.fillMaxSize()) {
                Column(Modifier.fillMaxSize().padding(8.dp)) {
                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                        val vals = points.map { it.plotValue }.filter { it.isFinite() }
                        Metric("NOW", points.lastOrNull()?.plotValue)
                        Metric("MIN", vals.minOrNull())
                        Metric("MAX", vals.maxOrNull())
                        Metric("AVG", vals.takeIf { it.isNotEmpty() }?.average())
                        Spacer(Modifier.weight(1f))
                        TextButton(onClick = { landscape = false }) { Text("Close") }
                    }
                    ZoomableTrend(
                        points = points.takeLast(25_000),
                        unit = points.lastOrNull()?.unit ?: ble.liveUnit.value,
                        showTemperature = showTemp,
                        modifier = Modifier.fillMaxSize()
                    )
                }
            }
        }
    }
}

@Composable
private fun Metric(label: String, v: Double?) {
    Column(Modifier.padding(end = 12.dp)) {
        Text(label, fontSize = 10.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(v?.let { "%.6g".format(it) } ?: "—", fontFamily = FontFamily.Monospace, fontWeight = FontWeight.Medium)
    }
}

@Composable
private fun Banner(text: String, bg: Color, fg: Color) {
    Row(
        Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 6.dp)
            .clip(RoundedCornerShape(10.dp)).background(bg).padding(10.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(Icons.Default.Warning, null, tint = fg)
        Text(text, color = fg, fontWeight = FontWeight.SemiBold, modifier = Modifier.padding(start = 8.dp))
    }
}

@Composable
fun MeterLcd(packet: PacketV2?, fallback: String, status: String, inSetup: Boolean) {
    val mainDigits = when {
        packet == null -> fallback.ifEmpty { "---.---" }
        packet.formattedMainValue == "OL" -> "  OL  "
        else -> packet.formattedMainValue
    }
    val sign = if (packet?.isMainNegative == true && packet.isMainOverflow.not()) "-" else " "
    val subTitle = when {
        inSetup && packet?.setupDescriptor() != null -> "SETUP · ${packet.setupDescriptor()!!.title}"
        packet?.iconTEST == true -> "Calibration"
        packet?.setupSubPresentation() != null -> packet.setupSubPresentation()!!.label
        else -> packet?.subUnit ?: ""
    }
    val subReading = if (inSetup && packet?.setupDescriptor() != null) packet.setupDescriptor()!!.reading
    else packet?.formattedSubValue ?: ""

    Column(
        Modifier.fillMaxWidth().padding(horizontal = 16.dp)
            .clip(RoundedCornerShape(12.dp)).background(LcdBg).padding(12.dp)
    ) {
        Row {
            LcdIcon("BT", packet?.iconBT == true)
            LcdIcon("AUTO", packet?.iconAUTO == true)
            LcdIcon("1kHz", packet?.icon1kHz == true)
            LcdIcon("1ms", packet?.icon1ms == true)
            LcdIcon("LowZ", packet?.iconLowZ == true)
            Spacer(Modifier.weight(1f))
            LcdIcon("BAT", packet?.iconBAT == true)
            LcdIcon("APO", packet?.iconAPO == true)
        }
        Row(verticalAlignment = Alignment.Bottom) {
            Text(sign, fontSize = 32.sp, fontFamily = FontFamily.Monospace, color = if (sign == "-") LcdInk else LcdDim)
            Text(mainDigits, fontSize = 42.sp, fontFamily = FontFamily.Monospace, fontWeight = FontWeight.SemiBold, color = LcdInk, maxLines = 1)
            Text(packet?.mainUnit ?: "", fontSize = 24.sp, fontFamily = FontFamily.Monospace, fontWeight = FontWeight.Bold, color = LcdInk)
        }
        Row(Modifier.padding(top = 4.dp)) {
            ModeTag("DC", packet?.coupling == Coupling.DC)
            ModeTag("AC", packet?.coupling == Coupling.AC)
            ModeTag("DC+AC", packet?.coupling == Coupling.ACDC)
            Spacer(Modifier.weight(1f))
            ModeTag("DIODE", packet?.isDiodeMode == true)
            ModeTag("CONT", packet?.iconCONT == true)
        }
        Row(Modifier.padding(vertical = 8.dp), verticalAlignment = Alignment.Bottom) {
            Text(subTitle, fontSize = 11.sp, fontFamily = FontFamily.Monospace, color = LcdInk.copy(alpha = 0.75f), modifier = Modifier.weight(1f))
            Text(subReading, fontSize = 20.sp, fontFamily = FontFamily.Monospace, fontWeight = FontWeight.SemiBold, color = LcdInk)
        }
        Row {
            LcdIcon("REL", packet?.iconREL == true)
            LcdIcon("HOLD", packet?.iconHOLD == true)
            LcdIcon("MIN/MAX", packet?.iconMinMax == true)
            LcdIcon("MEM", packet?.iconMEM == true)
            LcdIcon("dBm", packet?.iconDBM == true)
            LcdIcon("TEST", packet?.iconTEST == true)
            Spacer(Modifier.weight(1f))
            Text(status, fontSize = 10.sp, fontFamily = FontFamily.Monospace, color = LcdInk.copy(alpha = 0.55f))
        }
    }
}

@Composable
private fun LcdIcon(t: String, on: Boolean) {
    Text(t, fontSize = 11.sp, fontFamily = FontFamily.Monospace, fontWeight = FontWeight.Bold,
        color = if (on) LcdInk else LcdDim, modifier = Modifier.padding(end = 8.dp))
}

@Composable
private fun ModeTag(t: String, on: Boolean) {
    Text(
        t, fontSize = 10.sp, fontFamily = FontFamily.Monospace, fontWeight = FontWeight.Bold,
        color = if (on) LcdBg else LcdDim,
        modifier = Modifier.padding(end = 8.dp)
            .clip(RoundedCornerShape(3.dp))
            .background(if (on) LcdInk else Color.Transparent)
            .padding(horizontal = 4.dp, vertical = 1.dp)
    )
}

@Composable
fun MeterPad(ble: BleManager) {
    val connected by ble.isConnected.collectAsState()
    val rows = listOf(
        listOf(MeterKey.HOLD, MeterKey.MODE, MeterKey.REL, MeterKey.RANGE),
        listOf(MeterKey.MIN_MAX, MeterKey.MEM, MeterKey.PEAK, MeterKey.SETUP)
    )
    Column(
        Modifier.fillMaxWidth().padding(16.dp)
            .clip(RoundedCornerShape(12.dp))
            .background(MaterialTheme.colorScheme.surface)
            .padding(10.dp)
    ) {
        Text("Meter Controls", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        rows.forEach { row ->
            Row(Modifier.fillMaxWidth().padding(top = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                row.forEach { key ->
                    KeyButton(key, connected, Modifier.weight(1f),
                        onShort = { ble.sendKey(key, false) },
                        onLong = { ble.sendKey(key, true) })
                }
            }
        }
    }
}

@OptIn(ExperimentalComposeUiApi::class)
@Composable
private fun KeyButton(key: MeterKey, enabled: Boolean, modifier: Modifier, onShort: () -> Unit, onLong: () -> Unit) {
    var down by remember { mutableStateOf(false) }
    var longFired by remember { mutableStateOf(false) }
    var downAt by remember { mutableStateOf(0L) }
    Surface(
        modifier = modifier
            .pointerInteropFilter { ev ->
                if (!enabled) return@pointerInteropFilter false
                when (ev.action) {
                    MotionEvent.ACTION_DOWN -> {
                        down = true; longFired = false; downAt = System.currentTimeMillis(); true
                    }
                    MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                        val held = System.currentTimeMillis() - downAt
                        if (!longFired) {
                            if (held >= key.longMs) { longFired = true; onLong() } else onShort()
                        }
                        down = false
                        true
                    }
                    else -> false
                }
            },
        shape = RoundedCornerShape(8.dp),
        tonalElevation = if (down) 0.dp else 1.dp,
        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = if (enabled) 1f else 0.45f)
    ) {
        Column(Modifier.padding(vertical = if (key.setupArrow != null) 6.dp else 8.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            Text(key.title, fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
            if (key.setupArrow != null) Text(key.setupArrow, fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
fun SignalRow(rssi: Int, connected: Boolean) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        BluetoothGlyph(connected)
        if (connected && rssi != 0) Text(" $rssi dBm", fontSize = 11.sp, fontFamily = FontFamily.Monospace)
    }
}

@Composable
fun BatteryRow(voltage: Double?, low: Boolean) {
    val icon = when {
        voltage == null -> Icons.Default.BatteryFull
        voltage <= 4.2 -> Icons.Default.Battery0Bar
        voltage <= 4.5 -> Icons.Default.Battery1Bar
        else -> Icons.Default.BatteryFull
    }
    Row(verticalAlignment = Alignment.CenterVertically) {
        Icon(icon, null, tint = if (low) Color.Red else MaterialTheme.colorScheme.onSurface)
        if (voltage != null) Text("%.2fV".format(voltage), fontSize = 11.sp, fontFamily = FontFamily.Monospace)
    }
}

@Composable
fun BluetoothGlyph(on: Boolean) {
    Canvas(Modifier.height(18.dp).padding(end = 2.dp).then(Modifier.fillMaxWidth(0f))) { }
    Canvas(Modifier.height(18.dp).padding(horizontal = 2.dp)) {
        val w = 16.dp.toPx(); val h = size.height
        val p = Path().apply {
            moveTo(w * 0.50f, h * 0.08f)
            lineTo(w * 0.78f, h * 0.30f)
            lineTo(w * 0.50f, h * 0.50f)
            lineTo(w * 0.78f, h * 0.70f)
            lineTo(w * 0.50f, h * 0.92f)
            moveTo(w * 0.22f, h * 0.30f)
            lineTo(w * 0.50f, h * 0.50f)
            lineTo(w * 0.22f, h * 0.70f)
        }
        drawPath(p, if (on) Color(0xFF1565C0) else Color(0xFFEF6C00), style = Stroke(2.2f, cap = StrokeCap.Round, join = StrokeJoin.Round))
    }
}

@Composable
fun ZoomableTrend(
    points: List<DataPoint>,
    unit: String,
    showTemperature: Boolean,
    modifier: Modifier = Modifier,
    onTap: (() -> Unit)? = null
) {
    if (points.isEmpty()) {
        Box(modifier, contentAlignment = Alignment.Center) {
            Text("No samples yet", color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        return
    }
    val tf = remember { SimpleDateFormat("HH:mm:ss", Locale.US) }
    val line = MaterialTheme.colorScheme.primary
    Column(modifier.pointerInput(Unit) { detectTapGestures(onTap = { onTap?.invoke() }, onDoubleTap = {}) }) {
        Row(Modifier.fillMaxWidth()) {
            Text(tf.format(Date(points.first().timestamp)), fontSize = 10.sp, fontFamily = FontFamily.Monospace)
            Spacer(Modifier.weight(1f))
            Text("samples 1–${points.size} / ${points.size}", fontSize = 10.sp)
            Spacer(Modifier.weight(1f))
            Text(tf.format(Date(points.last().timestamp)), fontSize = 10.sp, fontFamily = FontFamily.Monospace)
        }
        Canvas(Modifier.fillMaxWidth().weight(1f, fill = false).height(160.dp)) {
            val finite = points.map { it.plotValue }.filter { it.isFinite() }
            val scaleMax = points.map { it.plotFullScale }.filter { it.isFinite() && it > 0 }.maxOrNull()
            val scaleMin = points.map { it.plotLowerBound }.filter { it.isFinite() }.minOrNull()
            var yLo = scaleMin ?: (finite.minOrNull() ?: 0.0)
            var yHi = scaleMax ?: (finite.maxOrNull() ?: 1.0)
            if (scaleMax == null && finite.isNotEmpty()) {
                val dMin = finite.min(); val dMax = finite.max()
                val pad = if (dMin == dMax) maxOf(kotlin.math.abs(dMin) * 0.05, 1e-9) else (dMax - dMin) * 0.05
                yLo = dMin - pad; yHi = dMax + pad
            }
            if (!yLo.isFinite() || !yHi.isFinite() || yHi <= yLo) {
                yLo = finite.minOrNull() ?: 0.0
                yHi = maxOf(yLo + 1, finite.maxOrNull() ?: 1.0)
            }
            val padL = 64f
            val plotW = size.width - padL
            val plotH = size.height - 8f
            for (i in 0..4) {
                val y = plotH * i / 4f
                drawLine(Color.Gray.copy(alpha = 0.22f), Offset(padL, y), Offset(size.width, y))
            }
            if (points.size > 1) {
                val pts = points.mapIndexed { i, dp ->
                    val value = if (dp.plotValue.isFinite()) dp.plotValue else yLo
                    val t = ((value - yLo) / (yHi - yLo)).coerceIn(0.0, 1.0)
                    Offset(padL + i.toFloat() / (points.size - 1) * plotW, (plotH - t.toFloat() * plotH * 0.92f - plotH * 0.04f).toFloat())
                }
                val fill = Path().apply {
                    moveTo(pts.first().x, plotH)
                    pts.forEach { lineTo(it.x, it.y) }
                    lineTo(pts.last().x, plotH)
                    close()
                }
                drawPath(fill, line.copy(alpha = 0.14f))
                val stroke = Path().apply {
                    moveTo(pts.first().x, pts.first().y)
                    pts.drop(1).forEach { lineTo(it.x, it.y) }
                }
                drawPath(stroke, line, style = Stroke(width = 3f, join = StrokeJoin.Round))
            }
            if (showTemperature) {
                val temps = points.mapNotNull { it.temperatureValue?.takeIf { v -> v.isFinite() } }
                if (temps.size > 1) {
                    val tLo = minOf(-20.0, temps.min())
                    val tHi = maxOf(100.0, temps.max())
                    val tp = Path()
                    var started = false
                    points.forEachIndexed { i, dp ->
                        val v = dp.temperatureValue ?: return@forEachIndexed
                        if (!v.isFinite()) return@forEachIndexed
                        val norm = ((v - tLo) / (tHi - tLo)).coerceIn(0.0, 1.0)
                        val x = padL + i.toFloat() / (points.size - 1).coerceAtLeast(1) * plotW
                        val y = plotH - norm.toFloat() * plotH * 0.92f - plotH * 0.04f
                        if (!started) { tp.moveTo(x, y); started = true } else tp.lineTo(x, y)
                    }
                    drawPath(tp, Color(0xFFE65100), style = Stroke(width = 2.4f))
                }
            }
        }
        Text("Y axis follows meter mode/range · tap graph for landscape", fontSize = 10.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}
