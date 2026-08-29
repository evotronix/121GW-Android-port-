package com.eevblog.gw121.ui

import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Bluetooth
import androidx.compose.material.icons.filled.List
import androidx.compose.material.icons.filled.Speed
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import com.eevblog.gw121.GwApplication
import kotlinx.coroutines.flow.collectLatest
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@Composable
fun AppRoot() {
    val app = GwApplication.instance
    val ble = app.ble
    val store = app.store
    val settings = app.settings
    val continuity = app.continuity

    var tab by remember { mutableIntStateOf(0) }
    var showName by remember { mutableStateOf(false) }
    var sessionName by remember { mutableStateOf("") }
    var rangeChange by remember { mutableStateOf(false) }
    var resumeAfterSave by remember { mutableStateOf(false) }
    var showThreshold by remember { mutableStateOf(false) }
    var thresholdInfo by remember { mutableStateOf("") }
    var lastSynced by remember { mutableStateOf<Double?>(null) }
    val connected by ble.isConnected.collectAsState()

    LaunchedEffect(Unit) {
        ble.packetFlow.collectLatest { pkt ->
            if (!store.append(pkt)) rangeChange = true
            continuity.evaluate(pkt)
            pkt.continuityBeeperOhms?.let { t ->
                if (lastSynced == t && kotlin.math.abs(settings.continuityOnThreshold.value - t) < 0.5) return@let
                lastSynced = t
                settings.setOn(t)
                if (settings.continuityOffThreshold.value < t + 1) settings.setOff(maxOf(400.0, t + 50))
                thresholdInfo = "ON below set to ${t.toInt()} Ω from the meter’s continuity beeper."
                showThreshold = true
            }
        }
    }
    LaunchedEffect(connected) {
        if (!connected) {
            continuity.reset()
            lastSynced = null
        }
    }

    Scaffold(
        bottomBar = {
            NavigationBar {
                NavigationBarItem(
                    selected = tab == 0,
                    onClick = { tab = 0 },
                    icon = { Icon(Icons.Default.Speed, contentDescription = "Live") },
                    label = { Text("Live") }
                )
                NavigationBarItem(
                    selected = tab == 1,
                    onClick = { tab = 1 },
                    icon = { Icon(Icons.Default.List, contentDescription = "Sessions") },
                    label = { Text("Sessions") }
                )
                NavigationBarItem(
                    selected = tab == 2,
                    onClick = { tab = 2 },
                    icon = { Icon(Icons.Default.Bluetooth, contentDescription = "Connect") },
                    label = { Text("Connect") }
                )
            }
        }
    ) { pad ->
        androidx.compose.foundation.layout.Box(Modifier.padding(pad)) {
            when (tab) {
                0 -> LiveScreen(ble, store, onAskName = { showName = true })
                1 -> SessionsScreen(store)
                else -> ConnectScreen(ble, settings)
            }
        }
    }

    if (showName) {
        NameSessionDialog(
            name = sessionName,
            onName = { sessionName = it },
            onCancel = { showName = false },
            onSave = {
                val finalName = sessionName.ifBlank {
                    "Session " + SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.US).format(Date())
                }
                store.saveCurrentSession(finalName)
                sessionName = ""
                showName = false
                if (resumeAfterSave) {
                    resumeAfterSave = false
                    store.startLogging()
                }
            }
        )
    }
    if (rangeChange) {
        AlertDialog(
            onDismissRequest = { rangeChange = false },
            title = { Text("Measurement function changed") },
            text = { Text("Trend logging stopped because the meter measurement function changed. Range changes are kept in the same log and normalized for the graph.") },
            confirmButton = {
                TextButton(onClick = {
                    rangeChange = false
                    resumeAfterSave = true
                    sessionName = ""
                    showName = true
                }) { Text("Save log") }
            },
            dismissButton = {
                TextButton(onClick = {
                    rangeChange = false
                    store.discardCurrent()
                    store.startLogging()
                }) { Text("Discard") }
            }
        )
    }
    if (showThreshold) {
        AlertDialog(
            onDismissRequest = { showThreshold = false },
            title = { Text("Continuity") },
            text = { Text(thresholdInfo) },
            confirmButton = { TextButton(onClick = { showThreshold = false }) { Text("OK") } }
        )
    }
}

@Composable
fun NameSessionDialog(name: String, onName: (String) -> Unit, onCancel: () -> Unit, onSave: () -> Unit) {
    AlertDialog(
        onDismissRequest = onCancel,
        title = { Text("Save Session") },
        text = {
            androidx.compose.material3.OutlinedTextField(
                value = name,
                onValueChange = onName,
                label = { Text("Session name") },
                singleLine = true
            )
        },
        confirmButton = { TextButton(onClick = onSave) { Text("Save") } },
        dismissButton = { TextButton(onClick = onCancel) { Text("Cancel") } }
    )
}
