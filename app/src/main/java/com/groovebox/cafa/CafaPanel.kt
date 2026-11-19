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
import org.fm1.companion.protocol.Wire6

@Composable
fun CafaPanel(connection: CafaConnection, onConnect: (midiId: Int, audioId: Int, audioMode: Int) -> Unit) {
    var show by remember { mutableStateOf(false) }
    Surface(color = MaterialTheme.colorScheme.surfaceVariant) {
        Column {
            Row(Modifier.fillMaxWidth().padding(horizontal = 8.dp), horizontalArrangement = Arrangement.SpaceBetween) {
                Text(connection.status, modifier = Modifier.weight(1f).padding(top = 10.dp), fontSize = 12.sp)
                TextButton(onClick = { show = true }) { Text("FM1 LINK") }
                if (connection.hasSession) TextButton(onClick = { connection.disconnect() }) { Text("DISCONNECT") }
            }
            if (connection.notice.isNotEmpty()) Text(connection.notice, Modifier.padding(horizontal = 8.dp), fontSize = 11.sp)
        }
    }
    if (show) {
        var refresh by remember { mutableStateOf(0) }
        val devices = remember(refresh) { connection.midiDevices() }
        val outputs = remember(refresh) { connection.audioDevices() }
        val phoneOutputs = remember(refresh) { connection.phoneDevices() }
        var midiId by remember { mutableStateOf<Int?>(null) }
        var audioId by remember { mutableStateOf<Int?>(null) }
        var audioMode by remember { mutableIntStateOf(Wire6.PHONE) }
        AlertDialog(onDismissRequest = { show = false }, title = { Text("Loom + FM1") },
            text = {
                Column(Modifier.verticalScroll(rememberScrollState())) {
                    Text("Choose the FM1 USB MIDI device and where Loom audio plays. All eight Loom tracks are selectable; patterns remain 4–64 steps. Projects stay on this phone.")
                    Spacer(Modifier.height(8.dp))
                    Text("USB MIDI")
                    devices.forEach { info ->
                        TextButton(onClick = { midiId = info.id }) {
                            Text((if (midiId == info.id) "✓ " else "") + (info.properties.getString(MidiDeviceInfo.PROPERTY_NAME) ?: "USB MIDI ${info.id}"))
                        }
                    }
                    if (devices.isEmpty()) Text("No bidirectional USB MIDI device detected.")
                    Text("AUDIO OUTPUT")
                    TextButton(onClick = { audioMode = Wire6.PHONE; audioId = null }) {
                        Text((if (audioMode == Wire6.PHONE) "✓ " else "") + "PHONE SPEAKER / WIRED")
                    }
                    TextButton(onClick = { audioMode = Wire6.USB_RETURN; audioId = null }) {
                        Text((if (audioMode == Wire6.USB_RETURN) "✓ " else "") + "FM1 USB RETURN (44.1 kHz)")
                    }
                    val selectedOutputs = if (audioMode == Wire6.PHONE) phoneOutputs else outputs
                    selectedOutputs.forEach { info ->
                        TextButton(onClick = { audioId = info.id }) {
                            Text((if (audioId == info.id) "✓ " else "") + "${info.productName} (${info.id})")
                        }
                    }
                    if (selectedOutputs.isEmpty()) Text("No output detected for this choice.")
                    TextButton(onClick = { refresh++ }) { Text("REFRESH DEVICES") }
                    Text("Once connected, use the FM1 buttons and knobs to edit. Playback continues with the phone screen off. Use Disconnect or the playback notification to stop.")
                    if (connection.notice.isNotEmpty()) Text(connection.notice)
                    if (connection.routeHeld) TextButton(onClick = { connection.restoreNormalAudio(); show = false }) { Text("RESTORE NORMAL AUDIO") }
                }
            },
            confirmButton = {
                TextButton(enabled = midiId != null && audioId != null, onClick = {
                    val m = midiId; val a = audioId
                    if (m != null && a != null) { onConnect(m, a, audioMode); show = false }
                }) { Text("CONNECT") }
            }, dismissButton = { TextButton(onClick = { show = false }) { Text("CLOSE") } })
    }
}
