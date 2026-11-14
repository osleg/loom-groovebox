/* SPDX-License-Identifier: GPL-3.0-only */
package com.groovebox.cafa

import android.media.midi.MidiDeviceInfo
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

@Composable
fun CafaPanel(connection: CafaConnection) {
    var show by remember { mutableStateOf(false) }
    Surface(color = MaterialTheme.colorScheme.surfaceVariant) {
        Column {
            Row(Modifier.fillMaxWidth().padding(horizontal = 8.dp), horizontalArrangement = Arrangement.SpaceBetween) {
                Text(connection.status, modifier = Modifier.weight(1f).padding(top = 10.dp), fontSize = 12.sp)
                TextButton(onClick = { show = true }) { Text("FM1 LINK") }
                if (connection.connected) TextButton(onClick = { connection.disconnect() }) { Text("DISCONNECT") }
            }
            if (connection.notice.isNotEmpty()) Text(connection.notice, Modifier.padding(horizontal = 8.dp), fontSize = 11.sp)
        }
    }
    if (show) {
        var refresh by remember { mutableStateOf(0) }
        val devices = remember(refresh) { connection.midiDevices() }
        val outputs = remember(refresh) { connection.audioDevices() }
        var midiId by remember { mutableStateOf<Int?>(null) }
        var audioId by remember { mutableStateOf<Int?>(null) }
        AlertDialog(onDismissRequest = { show = false }, title = { Text("Loom + FM1 · v5 controls") },
            text = {
                Column(Modifier.verticalScroll(rememberScrollState())) {
                    Text("Choose the FM1 USB MIDI and audio outputs. Requires matching CAFA v5 firmware. All eight Loom tracks are selectable; patterns remain 4–64 steps. Install this app alongside the older CAFA app; projects are not imported automatically.")
                    Spacer(Modifier.height(8.dp))
                    Text("USB MIDI")
                    devices.forEach { info ->
                        TextButton(onClick = { midiId = info.id }) {
                            Text((if (midiId == info.id) "✓ " else "") + (info.properties.getString(MidiDeviceInfo.PROPERTY_NAME) ?: "USB MIDI ${info.id}"))
                        }
                    }
                    if (devices.isEmpty()) Text("No bidirectional USB MIDI device detected.")
                    Text("USB AUDIO OUTPUT")
                    outputs.forEach { info ->
                        TextButton(onClick = { audioId = info.id }) {
                            Text((if (audioId == info.id) "✓ " else "") + "${info.productName} (${info.id})")
                        }
                    }
                    if (outputs.isEmpty()) Text("No USB audio output detected.")
                    TextButton(onClick = { refresh++ }) { Text("REFRESH DEVICES") }
                    Text("ALGORITHM selects tracks 1–8. Page buttons select SOUND, ENV, FX, LFO, ARP, MIX or SEQ. SELECT stays within that family; GLO + SELECT changes tempo. PRESETS scrolls sequence banks. Tap a white SEQ key to toggle a step; hold it and turn knobs for pitch, velocity, gate and probability. REC is unavailable. Preset loading, sampling and advanced arrangement remain on the phone.")
                    if (connection.notice.isNotEmpty()) Text(connection.notice)
                    if (connection.routeHeld) TextButton(onClick = { connection.restoreNormalAudio(); show = false }) { Text("RESTORE NORMAL AUDIO") }
                }
            },
            confirmButton = {
                TextButton(enabled = midiId != null && audioId != null, onClick = {
                    val m = devices.firstOrNull { it.id == midiId }; val a = outputs.firstOrNull { it.id == audioId }
                    if (m != null && a != null) { connection.connect(m, a); show = false }
                }) { Text("CONNECT") }
            }, dismissButton = { TextButton(onClick = { show = false }) { Text("CLOSE") } })
    }
}
