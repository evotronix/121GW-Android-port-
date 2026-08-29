package com.eevblog.gw121.ui

import android.content.Intent
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.outlined.Inbox
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
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
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.core.content.FileProvider
import com.eevblog.gw121.data.MeasurementSession
import com.eevblog.gw121.data.SessionStore
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@Composable
fun SessionsScreen(store: SessionStore) {
    val sessions by store.sessions.collectAsState()
    var selected by remember { mutableStateOf<MeasurementSession?>(null) }
    var confirmClear by remember { mutableStateOf(false) }

    if (selected != null) {
        SessionDetail(selected!!, store) { selected = null }
        return
    }

    Column(Modifier.fillMaxSize()) {
        Row(Modifier.fillMaxWidth().padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
            Text("Sessions", style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
            if (sessions.isNotEmpty()) {
                IconButton(onClick = { confirmClear = true }) {
                    Icon(Icons.Default.Delete, contentDescription = "Delete all", tint = MaterialTheme.colorScheme.error)
                }
            }
        }
        if (sessions.isEmpty()) {
            Column(Modifier.fillMaxSize(), verticalArrangement = Arrangement.Center, horizontalAlignment = Alignment.CenterHorizontally) {
                Icon(Icons.Outlined.Inbox, null, modifier = Modifier.height(48.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
                Text("No sessions yet", style = MaterialTheme.typography.titleMedium)
                Text("Start a log on the Live tab.", color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        } else {
            LazyColumn {
                items(sessions, key = { it.id }) { s ->
                    Column(Modifier.fillMaxWidth().clickable { selected = s }.padding(16.dp)) {
                        Text(s.name, style = MaterialTheme.typography.titleMedium)
                        Text(sessionSubtitle(s), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }
        }
    }
    if (confirmClear) {
        AlertDialog(
            onDismissRequest = { confirmClear = false },
            title = { Text("Delete all sessions?") },
            confirmButton = {
                TextButton(onClick = { store.clearAll(); confirmClear = false }) { Text("Delete All") }
            },
            dismissButton = { TextButton(onClick = { confirmClear = false }) { Text("Cancel") } }
        )
    }
}

private fun sessionSubtitle(s: MeasurementSession): String {
    val df = SimpleDateFormat("M/d/yy, h:mm a", Locale.getDefault())
    val parts = mutableListOf("${s.points.size} pts", df.format(Date(s.createdAt)))
    s.average?.let { parts += "avg %.4g %s".format(it, s.graphUnit).trim() }
    return parts.joinToString(" · ")
}

@Composable
private fun SessionDetail(session: MeasurementSession, store: SessionStore, onBack: () -> Unit) {
    val ctx = LocalContext.current
    var notes by remember { mutableStateOf(session.notes) }
    var editing by remember { mutableStateOf(false) }
    var showTemp by remember { mutableStateOf(true) }
    val u = session.graphUnit

    fun shareFile(f: File, mime: String) {
        val uri = try {
            FileProvider.getUriForFile(ctx, ctx.packageName + ".files", f)
        } catch (_: Exception) {
            androidx.core.content.FileProvider.getUriForFile(ctx, ctx.packageName + ".files", f)
        }
        val i = Intent(Intent.ACTION_SEND).apply {
            type = mime
            putExtra(Intent.EXTRA_STREAM, uri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        ctx.startActivity(Intent.createChooser(i, "Share"))
    }

    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(12.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back") }
            Text(session.name, style = MaterialTheme.typography.titleMedium)
        }
        if (session.points.any { it.temperatureValue?.isFinite() == true }) {
            TextButton(onClick = { showTemp = !showTemp }) {
                Text(if (showTemp) "Temperature monitoring: ${session.points.mapNotNull { it.temperatureUnit }.firstOrNull() ?: "°C"}" else "Temperature monitoring: hidden")
            }
        }
        ZoomableTrend(session.points, session.graphUnit, showTemp, Modifier.fillMaxWidth().height(260.dp))
        Spacer(Modifier.height(12.dp))
        Text("Statistics", style = MaterialTheme.typography.titleMedium)
        StatGrid(
            listOfNotNull(
                "Samples" to "${session.points.size}",
                "Duration" to formatDur(session.durationMs),
                session.minValue?.let { "Min" to "%.5g %s".format(it, u).trim() },
                session.maxValue?.let { "Max" to "%.5g %s".format(it, u).trim() },
                session.average?.let { "Average" to "%.5g %s".format(it, u).trim() },
                session.peakToPeak?.let { "Peak-Peak" to "%.5g %s".format(it, u).trim() }
            )
        )
        Spacer(Modifier.height(12.dp))
        Row {
            Text("Notes", style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
            TextButton(onClick = {
                if (editing) store.updateNotes(session, notes)
                editing = !editing
            }) { Text(if (editing) "Done" else "Edit") }
        }
        if (editing) OutlinedTextField(notes, { notes = it }, modifier = Modifier.fillMaxWidth().height(100.dp))
        else Text(if (session.notes.isBlank()) "No notes" else session.notes, color = if (session.notes.isBlank()) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.onSurface)

        Row(Modifier.padding(top = 16.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            OutlinedButton(onClick = { shareFile(store.exportCsvFile(session), "text/csv") }) { Text("CSV") }
            OutlinedButton(onClick = {
                val f = File(ctx.cacheDir, session.name.replace(" ", "_") + ".txt")
                f.writeText("${session.name}\n${session.csvString()}")
                shareFile(f, "text/plain")
            }) { Text("Graph data") }
        }
        Spacer(Modifier.height(16.dp))
        Button(onClick = { store.delete(session); onBack() }) { Text("Delete session") }
    }
}

@Composable
private fun StatGrid(rows: List<Pair<String, String>>) {
    Column {
        rows.chunked(2).forEach { chunk ->
            Row(Modifier.fillMaxWidth()) {
                chunk.forEach { (t, v) ->
                    Surface(Modifier.weight(1f).padding(4.dp), tonalElevation = 1.dp) {
                        Column(Modifier.padding(10.dp)) {
                            Text(t, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            Text(v, fontFamily = FontFamily.Monospace)
                        }
                    }
                }
                if (chunk.size == 1) Spacer(Modifier.weight(1f))
            }
        }
    }
}

private fun formatDur(ms: Long): String {
    val t = ms / 1000.0
    return when {
        t < 60 -> "%.1fs".format(t)
        t < 3600 -> "%.1f min".format(t / 60)
        else -> "%.1f h".format(t / 3600)
    }
}
