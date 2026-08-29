package com.eevblog.gw121.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material3.Button
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.eevblog.gw121.ble.BleManager
import com.eevblog.gw121.data.AppSettings

@Composable
fun ConnectScreen(ble: BleManager, settings: AppSettings) {
    val connected by ble.isConnected.collectAsState()
    val scanning by ble.isScanning.collectAsState()
    val devices by ble.discovered.collectAsState()
    val auto by ble.autoReconnect.collectAsState()
    val sound by settings.continuitySoundEnabled.collectAsState()
    val vibe by settings.continuityVibrationEnabled.collectAsState()
    val onT by settings.continuityOnThreshold.collectAsState()
    val offT by settings.continuityOffThreshold.collectAsState()
    var onText by remember { mutableStateOf(fmtOhm(onT)) }
    var offText by remember { mutableStateOf(fmtOhm(offT)) }
    LaunchedEffect(onT) { onText = fmtOhm(onT) }
    LaunchedEffect(offT) { offText = fmtOhm(offT) }

    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp)) {
        Text("Connect", style = MaterialTheme.typography.titleMedium)
        Row(Modifier.padding(vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            BluetoothGlyph(connected)
            Text(if (connected) "  Connected" else "  Disconnected", style = MaterialTheme.typography.titleSmall)
        }
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text("Auto-reconnect", modifier = Modifier.weight(1f))
            Switch(checked = auto, onCheckedChange = { ble.setAutoReconnect(it) })
        }
        HorizontalDivider(Modifier.padding(vertical = 8.dp))
        Text("Devices", style = MaterialTheme.typography.labelLarge)
        if (devices.isEmpty()) {
            Text("No 121GW found yet. Hold 1ms PEAK on the meter to enable BT.", color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        devices.forEach { d ->
            Row(
                Modifier.fillMaxWidth().clickable { ble.connect(d.address) }.padding(vertical = 10.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(d.name.ifBlank { "121GW" }, modifier = Modifier.weight(1f))
                if (connected && ble.connectedAddress.value == d.address) {
                    Icon(Icons.Default.CheckCircle, null, tint = Color(0xFF2E7D32))
                }
            }
        }
        Button(
            onClick = { if (scanning) ble.stopScan() else ble.startScan() },
            enabled = !connected,
            modifier = Modifier.fillMaxWidth().padding(top = 8.dp)
        ) { Text(if (scanning) "Stop Scan" else "Scan for 121GW") }
        OutlinedButton(
            onClick = { ble.disconnect() },
            enabled = connected,
            modifier = Modifier.fillMaxWidth().padding(top = 8.dp)
        ) { Text("Disconnect") }

        HorizontalDivider(Modifier.padding(vertical = 16.dp))
        Text("Continuity Feedback", style = MaterialTheme.typography.labelLarge)
        Text(
            "Beep & vibrate in Continuity/Ω when below ON, stop above OFF. Also beeps in Diode mode while a junction is conducting. Defaults: ON < 25 Ω, OFF > 400 Ω.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text("Sound (beep)", modifier = Modifier.weight(1f))
            Switch(checked = sound, onCheckedChange = { settings.setSound(it) })
        }
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text("Vibration", modifier = Modifier.weight(1f))
            Switch(checked = vibe, onCheckedChange = { settings.setVibration(it) })
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("ON below", modifier = Modifier.weight(1f))
            OutlinedTextField(
                value = onText,
                onValueChange = {
                    onText = it
                    AppSettings.parseOhms(it)?.let { v -> settings.setOn(v) }
                },
                modifier = Modifier.fillMaxWidth(0.4f),
                singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                suffix = { Text("Ω") }
            )
        }
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(top = 8.dp)) {
            Text("OFF above", modifier = Modifier.weight(1f))
            OutlinedTextField(
                value = offText,
                onValueChange = {
                    offText = it
                    AppSettings.parseOhms(it)?.let { v -> settings.setOff(v) }
                },
                modifier = Modifier.fillMaxWidth(0.4f),
                singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                suffix = { Text("Ω") }
            )
        }
        Text(
            "Turn the meter’s Bluetooth on by holding the 1ms PEAK key until the BT icon appears. Long-press a meter button for the hold-key function.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(top = 16.dp)
        )
        Spacer(Modifier.padding(24.dp))
    }
}

private fun fmtOhm(v: Double) = if (v == kotlin.math.round(v)) "%.0f".format(v) else "%g".format(v)
