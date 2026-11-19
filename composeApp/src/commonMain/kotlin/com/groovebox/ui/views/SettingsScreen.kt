package com.groovebox.ui.views

import com.groovebox.ui.platformShowMessage
import com.groovebox.ui.LocalPlatformInfo
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Menu
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Share
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.material3.MaterialTheme
import com.groovebox.GrooveboxState
import com.groovebox.LfoState
import com.groovebox.MacroState
import com.groovebox.NativeLib
import com.groovebox.ui.views.SoundFontCreditsDialog
import com.groovebox.StripRouting
import com.groovebox.midi.MidiManager
import com.groovebox.persistence.PersistenceManager
import com.groovebox.utils.sanitizeGrooveboxState
import com.groovebox.utils.syncNativeState
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

@OptIn(ExperimentalMaterial3Api::class, androidx.compose.foundation.ExperimentalFoundationApi::class, androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
@Composable
fun SettingsScreen(state: GrooveboxState, onStateChange: (GrooveboxState) -> Unit, nativeLib: NativeLib, midiManager: MidiManager? = null, stateForSave: () -> GrooveboxState = { state }) {
    // val context = LocalContext.current // Removed
    var mappingStripIndex by remember { mutableStateOf(-1) }
    Column(modifier = Modifier.fillMaxSize().padding(16.dp).verticalScroll(rememberScrollState())) {
        
        // ====== 1. PROJECT SAVE/LOAD ======
        // Project Management Cluster
        Text("Project", style = MaterialTheme.typography.titleMedium, color = Color.Gray)
        val scope = rememberCoroutineScope()
        
        var showSaveDialog by remember { mutableStateOf(false) }
        var showLoadDialog by remember { mutableStateOf(false) }
        var showExportDialog by remember { mutableStateOf(false) }

        if (showSaveDialog) {
            var fileName by remember { mutableStateOf("") }
            val existingProjects = remember { PersistenceManager.listProjects().sorted() }
            
            AlertDialog(
                onDismissRequest = { showSaveDialog = false },
                title = { Text("Save Project") },
                text = {
                    Column {
                        OutlinedTextField(
                            value = fileName,
                            onValueChange = { fileName = it },
                            label = { Text("Project Name") },
                            singleLine = true,
                            modifier = Modifier.fillMaxWidth()
                        )
                        Spacer(modifier = Modifier.height(16.dp))
                        Text("Existing Projects:", style = MaterialTheme.typography.labelSmall, color = Color.Gray)
                        Box(modifier = Modifier.heightIn(max = 200.dp).fillMaxWidth().border(1.dp, Color.Gray.copy(alpha=0.3f), RoundedCornerShape(4.dp))) {
                             LazyColumn(modifier = Modifier.padding(8.dp)) {
                                 items(existingProjects) { name ->
                                     Text(
                                         name, 
                                         modifier = Modifier
                                            .fillMaxWidth()
                                            .clickable { fileName = name.removeSuffix(".gbx") } // Auto-fill name without extension
                                            .padding(vertical = 8.dp),
                                         style = MaterialTheme.typography.bodySmall,
                                         color = Color.LightGray
                                     )
                                     Divider(color = Color.DarkGray.copy(alpha = 0.5f))
                                 }
                             }
                        }
                    }
                },
                confirmButton = {
                    Button(onClick = {
                        if (fileName.isNotBlank()) {
                            val projectState = stateForSave()
                            scope.launch(Dispatchers.IO) {
                                try {
                                    val saved = PersistenceManager.saveProject(projectState, fileName)
                                    withContext(Dispatchers.Main) {
                                        platformShowMessage(if (saved) "Project Saved: $fileName" else "Save Failed")
                                        if (saved) showSaveDialog = false
                                    }
                                } catch (e: Exception) {
                                    withContext(Dispatchers.Main) {
                                        platformShowMessage("Save Failed")
                                    }
                                }
                            }
                        }
                    }) { Text("SAVE") }
                },
                dismissButton = { TextButton(onClick = { showSaveDialog = false }) { Text("CANCEL") } }
            )
        }

        if (showLoadDialog) {
            val projects = remember { PersistenceManager.listProjects().sorted() }
            AlertDialog(
                onDismissRequest = { showLoadDialog = false },
                title = { Text("Load Project") },
                text = {
                    if (projects.isEmpty()) {
                        Text("No projects found in Projects folder.")
                    } else {
                        Box(modifier = Modifier.heightIn(max = 300.dp)) {
                            val listState = rememberLazyListState()
                            
                            LazyColumn(state = listState, modifier = Modifier.fillMaxWidth()) {
                                items(projects) { name ->
                                    var showMenu by remember { mutableStateOf(false) }
                                    
                                    Box(modifier = Modifier.fillMaxWidth()) {
                                        Row(
                                            modifier = Modifier
                                                .fillMaxWidth()
                                                .combinedClickable(
                                                    onClick = {
                                                        scope.launch(Dispatchers.IO) {
                                                            val loaded = PersistenceManager.loadProject(name)
                                                            withContext(Dispatchers.Main) {
                                                                if (loaded != null) {
                                                                    nativeLib.stop()
                                                                    nativeLib.init()
                                                                    // nativeLib.setAppDataDir(...)
                                                                    val sanitized = sanitizeGrooveboxState(loaded)
                                                                    onStateChange(sanitized)
                                                                    // Sync to native IMMEDIATELY
                                                                    syncNativeState(sanitized, nativeLib)
                                                                    nativeLib.start()
                                                                    platformShowMessage("Loaded: $name")
                                                                    showLoadDialog = false
                                                                }
                                                            }
                                                        }
                                                    },
                                                    onLongClick = { showMenu = true }
                                                )
                                                .padding(16.dp),
                                            verticalAlignment = Alignment.CenterVertically
                                        ) {
                                            Icon(Icons.Default.Menu, contentDescription = null, tint = Color.Gray, modifier = Modifier.size(16.dp))
                                            Spacer(modifier = Modifier.width(16.dp))
                                            Text(name, color = Color.White)
                                        }
                                        Divider(color = Color.DarkGray, modifier = Modifier.align(Alignment.BottomCenter))

                                        DropdownMenu(
                                            expanded = showMenu,
                                            onDismissRequest = { showMenu = false }
                                        ) {
                                            DropdownMenuItem(
                                                text = { Text("Rename") },
                                                onClick = {
                                                    // Rename logic
                                                    showMenu = false
                                                    // Trigger Rename Dialog (Nested state needed or simple prompt)
                                                    // For simplicity in this iteration, we'll implement Copy/Delete first as requested priority
                                                }
                                            )
                                            DropdownMenuItem(
                                                text = { Text("Copy") },
                                                onClick = {
                                                    scope.launch(Dispatchers.IO) {
                                                        PersistenceManager.copyProject(name)
                                                        withContext(Dispatchers.Main) {
                                                            platformShowMessage("Copied to ${name.removeSuffix(".gbx")}_copy.gbx")
                                                            showMenu = false
                                                            showLoadDialog = false // Force refresh on re-open
                                                        }
                                                    }
                                                }
                                            )
                                            DropdownMenuItem(
                                                text = { Text("Delete", color = Color.Red) },
                                                onClick = {
                                                    scope.launch(Dispatchers.IO) {
                                                        PersistenceManager.deleteProject(name)
                                                        withContext(Dispatchers.Main) {
                                                            platformShowMessage("Deleted $name")
                                                            showMenu = false
                                                            showLoadDialog = false // Force refresh
                                                        }
                                                    }
                                                }
                                            )
                                        }
                                    }
                                }
                            }
                            
                            // Scrollbar hint (Simple Visual Indicator)
                            if (listState.canScrollForward || listState.canScrollBackward) {
                                Box(
                                    modifier = Modifier
                                        .align(Alignment.CenterEnd)
                                        .fillMaxHeight()
                                        .width(4.dp)
                                        .background(Color.White.copy(alpha = 0.1f))
                                ) {
                                    val totalItems = projects.size
                                    val visibleItems = listState.layoutInfo.visibleItemsInfo.size
                                    if (totalItems > 0 && totalItems > visibleItems) {
                                         val scrollRatio = listState.firstVisibleItemIndex.toFloat() / totalItems
                                         val heightRatio = visibleItems.toFloat() / totalItems
                                         
                                         // Dynamic Scroll Handle
                                         Box(
                                             modifier = Modifier
                                                 .fillMaxWidth()
                                                 .fillMaxHeight(fraction = heightRatio.coerceIn(0.1f, 1f))
                                                 .align(Alignment.TopCenter)
                                                 // NOTE: Exact positioning in Compose requires constraints or custom layout. 
                                                 // For a reliable "discreet" indicator without math hell, we use a simple alignment trick:
                                                 // Since we can't easily offset by percentage without `BiasAlignment`, let's just show a static bar 
                                                 // to indicate "Scrolling is possible" or use a weighted column.
                                         )
                                         // Better: Standard Scrollbar logic is hard to inline. 
                                         // User asked for "discreet scroll indicator".
                                    }
                                }
                                // Simple Top/Bottom shadow indicators
                                if (listState.canScrollBackward) {
                                    Box(
                                        modifier = Modifier.fillMaxWidth().height(16.dp).align(Alignment.TopCenter).background(
                                            brush = Brush.verticalGradient(colors = listOf(Color.Black, Color.Transparent))
                                        )
                                    )
                                }
                                if (listState.canScrollForward) {
                                    Box(
                                        modifier = Modifier.fillMaxWidth().height(16.dp).align(Alignment.BottomCenter).background(
                                            brush = Brush.verticalGradient(colors = listOf(Color.Transparent, Color.Black))
                                        )
                                    )
                                }
                            }
                        }
                    }
                },
                confirmButton = {},
                dismissButton = { TextButton(onClick = { showLoadDialog = false }) { Text("CANCEL") } }
            )
        }

        if (showExportDialog) {
            var loops by remember { mutableStateOf("1") }
            AlertDialog(
                onDismissRequest = { showExportDialog = false },
                title = { Text("Export Audio") },
                text = {
                    Column {
                        Text("Number of sequence repeats (loops):")
                        OutlinedTextField(
                            value = loops,
                            onValueChange = { loops = it.filter { c -> c.isDigit() } },
                            modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
                            singleLine = true
                        )
                    }
                },
                confirmButton = {
                    Button(onClick = {
                        val numLoops = loops.toIntOrNull() ?: 1
                        scope.launch(Dispatchers.IO) {
                            try {
                                val exportPath = "export.wav" // Placeholder, handle via file picker
                                platformShowMessage("Exported $numLoops loops to: $exportPath")
                                showExportDialog = false
                            } catch (e: Exception) {
                                withContext(Dispatchers.Main) {
                                    platformShowMessage("Assignments Cleared")
                                }
                            }
                        }
                    }) { Text("EXPORT") }
                },
                dismissButton = { TextButton(onClick = { showExportDialog = false }) { Text("CANCEL") } }
            )
        }

        Row(
            modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Button(
                onClick = { 
                    // NEW PROJECT
                    val newState = sanitizeGrooveboxState(GrooveboxState())
                    onStateChange(newState)
                    scope.launch(Dispatchers.IO) {
                        syncNativeState(newState, nativeLib)
                    }
                    platformShowMessage("New Project Created (Init)")
                },
                modifier = Modifier.weight(0.8f),
                colors = ButtonDefaults.buttonColors(
                    containerColor = Color(0xFFEEEEEE),
                    contentColor = Color.Black
                ),
                contentPadding = PaddingValues(horizontal = 4.dp)
            ) {
                Text("NEW", fontSize = 10.sp, fontWeight = FontWeight.Bold)
            }
            Button(
                onClick = { showSaveDialog = true },
                modifier = Modifier.weight(1f),
                colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF2E7D32)),
                contentPadding = PaddingValues(horizontal = 4.dp)
            ) {
                Icon(Icons.Default.Check, contentDescription = null, modifier = Modifier.size(16.dp))
                Spacer(Modifier.width(4.dp))
                Text("SAVE", fontSize = 10.sp)
            }
            Button(
                onClick = { showLoadDialog = true },
                modifier = Modifier.weight(1f),
                colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF1565C0)),
                contentPadding = PaddingValues(horizontal = 4.dp)
            ) {
                Icon(Icons.Default.Refresh, contentDescription = null, modifier = Modifier.size(16.dp))
                Spacer(Modifier.width(4.dp))
                Text("LOAD", fontSize = 10.sp)
            }
            Button(
                onClick = { showExportDialog = true },
                modifier = Modifier.weight(1f),
                colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF6A1B9A)),
                contentPadding = PaddingValues(horizontal = 4.dp)
            ) {
                Icon(Icons.Default.Share, contentDescription = null, modifier = Modifier.size(16.dp))
                Spacer(Modifier.width(4.dp))
                Text("EXPORT", fontSize = 10.sp)
            }
        }
        
        Spacer(modifier = Modifier.height(16.dp))
        Button(
            onClick = {
                PersistenceManager.clearAssignments()
                val newState = state.copy(
                    engineTypeStripAssignments = emptyMap(), 
                    engineTypeKnobAssignments = emptyMap(),
                    // Immediately reset current routings to default
                    stripRoutings = List(4) { i -> StripRouting(stripIndex = i) },
                    knobRoutings = List(4) { i -> StripRouting(stripIndex = i + 4, parameterName = "KNOB ${i + 1}") },
                    lfos = List(6) { LfoState() },
                    macros = List(6) { i -> MacroState(label="Macro ${i+1}") },
                    routingConnections = emptyList(),
                    fxChainSlots = List(5) { -1 }
                )
                onStateChange(newState)
                // Sync native state
                scope.launch(Dispatchers.IO) {
                    syncNativeState(newState, nativeLib)
                    // Explicitly stop all FX if slots are cleared
                    (0 until 5).forEach { slot -> nativeLib.setFxChain(slot, -1) }
                }
            },
            colors = ButtonDefaults.buttonColors(containerColor = Color.Red.copy(alpha = 0.6f))
        ) {
            Text("RESET ALL PATCHING & MIDI")
        }
        Text("Clears all MIDI assignments, LFOs, Macros, Routings and Pedal arrangements.", style = MaterialTheme.typography.bodySmall, color = Color.Gray)

        // ====== 2. UI / INTERFACE ======

        // Interface Settings
        Text("Interface", style = MaterialTheme.typography.titleMedium, color = Color.Gray)
        Spacer(modifier = Modifier.height(8.dp))
        
        Card(
            colors = CardDefaults.cardColors(containerColor = Color.DarkGray.copy(alpha = 0.3f)),
            modifier = Modifier.fillMaxWidth().padding(bottom = 16.dp),
            border = BorderStroke(1.dp, Color.Gray.copy(alpha = 0.3f))
        ) {
            Column(modifier = Modifier.padding(12.dp)) {
                Text("UI Layout Mode", style = MaterialTheme.typography.labelMedium, color = Color.White)
                Spacer(modifier = Modifier.height(8.dp))
                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    val modes = listOf("Auto", "Phone", "Tablet")
                    modes.forEachIndexed { index, label ->
                        val isSelected = state.uiLayoutMode == index
                        OutlinedButton(
                            onClick = { onStateChange(state.copy(uiLayoutMode = index)) },
                            modifier = Modifier.weight(1f),
                            colors = ButtonDefaults.outlinedButtonColors(
                                containerColor = if (isSelected) Color.Cyan.copy(alpha = 0.2f) else Color.Transparent,
                                contentColor = if (isSelected) Color.Cyan else Color.Gray
                            ),
                            border = BorderStroke(1.dp, if (isSelected) Color.Cyan else Color.Gray)
                        ) {
                            Text(label, fontSize = 12.sp, maxLines = 1)
                        }
                    }
                }
                
                Spacer(modifier = Modifier.height(16.dp))
                Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween) {
                    Text("Show CPU Monitor", style = MaterialTheme.typography.labelMedium, color = Color.White)
                    Switch(
                        checked = state.showCpuMonitor,
                        onCheckedChange = { onStateChange(state.copy(showCpuMonitor = it)) }
                    )
                }

                if (LocalPlatformInfo.current.platform == "macos") {
                    Spacer(modifier = Modifier.height(16.dp))
                    Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween) {
                        Column {
                            Text("Performance Mode", style = MaterialTheme.typography.labelMedium, color = Color.White)
                            Text("Enables visual cues for pointer interactions", style = MaterialTheme.typography.labelSmall, color = Color.Gray)
                        }
                        Switch(
                            checked = state.isPerformanceMode,
                            onCheckedChange = { onStateChange(state.copy(isPerformanceMode = it)) }
                        )
                    }
                }

                if (LocalPlatformInfo.current.platform == "android") {
                    Spacer(modifier = Modifier.height(16.dp))
                    Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween) {
                        Column {
                            Text("Keyboard Mode", style = MaterialTheme.typography.labelMedium, color = Color.White)
                            Text("Use physical keyboard for notes & octaves", style = MaterialTheme.typography.labelSmall, color = Color.Gray)
                        }
                        Switch(
                            checked = state.isKeyboardModeEnabled,
                            onCheckedChange = { 
                                val newState = state.copy(isKeyboardModeEnabled = it)
                                if (!it && state.gridMode == com.groovebox.GridMode.MAC_KEYS) {
                                    onStateChange(newState.copy(gridMode = com.groovebox.GridMode.GRID_4X4))
                                } else {
                                    onStateChange(newState)
                                }
                            }
                        )
                    }
                }

                Spacer(modifier = Modifier.height(16.dp))
                Text("MIDI Pad Gestures", style = MaterialTheme.typography.labelMedium, color = Color.White)
                Spacer(modifier = Modifier.height(8.dp))
                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                    Column(modifier = Modifier.weight(1f)) {
                        val xAttn = state.padXAttenuation ?: 1.0f
                        Text("X-Axis (Pitch) Attn: ${(xAttn * 100).toInt()}%", color = Color.Gray, fontSize = 12.sp)
                        Slider(
                            value = xAttn,
                            onValueChange = { onStateChange(state.copy(padXAttenuation = it)) },
                            valueRange = 0f..1f
                        )
                    }
                    Column(modifier = Modifier.weight(1f)) {
                        val yAttn = state.padYAttenuation ?: 1.0f
                        Text("Y-Axis (Mod) Attn: ${(yAttn * 100).toInt()}%", color = Color.Gray, fontSize = 12.sp)
                        Slider(
                            value = yAttn,
                            onValueChange = { onStateChange(state.copy(padYAttenuation = it)) },
                            valueRange = 0f..1f
                        )
                    }
                }

                Spacer(modifier = Modifier.height(16.dp))
                Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween) {
                    Column {
                        Text("Invert MIDI Pad Y-axis", style = MaterialTheme.typography.labelMedium, color = Color.White)
                        Text("Flips the vertical modulation mapping", style = MaterialTheme.typography.labelSmall, color = Color.Gray)
                    }
                    Switch(
                        checked = state.invertPadY,
                        onCheckedChange = { onStateChange(state.copy(invertPadY = it)) }
                    )
                }
            }
        }

        // ====== 2b. PERFORMANCE / CPU ======

        Text("Performance", style = MaterialTheme.typography.titleMedium, color = Color.Gray)
        Spacer(modifier = Modifier.height(8.dp))

        val fxBusNames = listOf(
            "Overdrive", "Bitcrush", "Chorus", "Phaser", "Wobble", "Delay",
            "Reverb", "Slicer", "Compressor", "HP LFO", "LP LFO", "Flanger",
            "Filter 1", "Tape Echo", "Octaver", "Filter 2", "Filter 3", "5-Band EQ"
        )
        val fxEnabledCount = (0 until 18).count { state.fxEnabled.getOrElse(it) { true } }

        Card(
            colors = CardDefaults.cardColors(containerColor = Color.DarkGray.copy(alpha = 0.3f)),
            modifier = Modifier.fillMaxWidth().padding(bottom = 16.dp),
            border = BorderStroke(1.dp, Color.Gray.copy(alpha = 0.3f))
        ) {
            Column(modifier = Modifier.padding(12.dp)) {
                Text("Performance Profile", style = MaterialTheme.typography.labelMedium, color = Color.White)
                Spacer(modifier = Modifier.height(6.dp))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    listOf("HIGH", "BALANCED", "LOW").forEachIndexed { idx, label ->
                        val selected = state.performanceProfile == idx
                        OutlinedButton(
                            onClick = {
                                nativeLib.setPerformanceProfile(idx)
                                onStateChange(state.copy(performanceProfile = idx))
                            },
                            modifier = Modifier.weight(1f),
                            contentPadding = PaddingValues(horizontal = 4.dp),
                            colors = ButtonDefaults.outlinedButtonColors(
                                containerColor = if (selected) Color(0xFF4CAF50).copy(alpha = 0.25f) else Color.Transparent,
                                contentColor = if (selected) Color(0xFF4CAF50) else Color.Gray
                            ),
                            border = BorderStroke(1.dp, if (selected) Color(0xFF4CAF50) else Color.Gray.copy(alpha = 0.5f))
                        ) {
                            Text(label, fontSize = 11.sp, maxLines = 1)
                        }
                    }
                }
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    when (state.performanceProfile) {
                        1 -> "8 voices per track; FX processing pauses after ~4 s of silence."
                        2 -> "4 voices per track, modulation every other block, idle FX pause, slower UI polling."
                        else -> "Full 16-voice polyphony, per-block modulation, FX always processed."
                    },
                    style = MaterialTheme.typography.labelSmall,
                    color = Color.Gray
                )

                Spacer(modifier = Modifier.height(16.dp))
                Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text("Active FX Buses", style = MaterialTheme.typography.labelMedium, color = Color.White)
                        Text("Bypass pedals you do not use to free CPU", style = MaterialTheme.typography.labelSmall, color = Color.Gray)
                    }
                    Text("$fxEnabledCount/18", color = if (fxEnabledCount < 18) Color(0xFF4CAF50) else Color.Gray, fontWeight = FontWeight.Bold)
                }
                Spacer(modifier = Modifier.height(8.dp))
                FlowRow(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    fxBusNames.forEachIndexed { idx, name ->
                        val enabled = state.fxEnabled.getOrElse(idx) { true }
                        FilterChip(
                            selected = enabled,
                            onClick = {
                                val newEnabled = !enabled
                                val newMask = state.fxEnabled.toMutableList()
                                while (newMask.size < 18) newMask.add(true)
                                newMask[idx] = newEnabled
                                nativeLib.setFxEnabled(idx, newEnabled)
                                onStateChange(state.copy(fxEnabled = newMask))
                            },
                            label = { Text(name, fontSize = 10.sp) },
                            colors = FilterChipDefaults.filterChipColors(
                                selectedContainerColor = Color(0xFF00E5FF).copy(alpha = 0.25f),
                                selectedLabelColor = Color(0xFF00E5FF),
                                labelColor = Color.Gray
                            )
                        )
                    }
                }

                Spacer(modifier = Modifier.height(8.dp))
                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(
                        onClick = {
                            // Conservative rule: an FX is unused only if no track
                            // sends to it and no other FX routes into it.
                            val chainDests = state.fxChainSlots.filter { it != -1 }.drop(1).toSet()
                            val newMask = (0 until 18).map { idx ->
                                val hasSend = state.tracks.any { (it.fxSends.getOrNull(idx) ?: 0f) > 0.001f }
                                val receivesChain = chainDests.contains(idx)
                                if (hasSend || receivesChain) true else false
                            }
                            newMask.forEachIndexed { idx, enabled -> nativeLib.setFxEnabled(idx, enabled) }
                            onStateChange(state.copy(fxEnabled = newMask))
                        },
                        modifier = Modifier.weight(1f),
                        contentPadding = PaddingValues(horizontal = 8.dp)
                    ) {
                        Text("BYPASS UNUSED", fontSize = 11.sp, maxLines = 1)
                    }
                    OutlinedButton(
                        onClick = {
                            val newMask = List(18) { true }
                            newMask.forEachIndexed { idx, enabled -> nativeLib.setFxEnabled(idx, enabled) }
                            onStateChange(state.copy(fxEnabled = newMask))
                        },
                        modifier = Modifier.weight(1f),
                        contentPadding = PaddingValues(horizontal = 8.dp)
                    ) {
                        Text("ENABLE ALL", fontSize = 11.sp, maxLines = 1)
                    }
                }

                Spacer(modifier = Modifier.height(16.dp))
                Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text("Active Tracks", style = MaterialTheme.typography.labelMedium, color = Color.White)
                        Text("Disabled tracks skip sequencing, DSP and modulation", style = MaterialTheme.typography.labelSmall, color = Color.Gray)
                    }
                    Text("${state.tracks.count { !it.isDisabled }}/8", color = Color.Gray, fontWeight = FontWeight.Bold)
                }
                Spacer(modifier = Modifier.height(8.dp))
                FlowRow(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    state.tracks.forEachIndexed { i, track ->
                        val enabled = !track.isDisabled
                        FilterChip(
                            selected = enabled,
                            onClick = {
                                val nowDisabled = enabled
                                val newTracks = state.tracks.toMutableList()
                                newTracks[i] = newTracks[i].copy(isDisabled = nowDisabled)
                                nativeLib.setTrackDisabled(i, nowDisabled)
                                onStateChange(state.copy(tracks = newTracks))
                            },
                            label = { Text("T${i + 1}", fontSize = 10.sp) },
                            colors = FilterChipDefaults.filterChipColors(
                                selectedContainerColor = Color(0xFF4CAF50).copy(alpha = 0.25f),
                                selectedLabelColor = Color(0xFF4CAF50),
                                labelColor = Color.Gray
                            )
                        )
                    }
                }
            }
        }

        // ====== 2d. MIDI DEVICES ======
        if (midiManager != null) {
            val devices = midiManager.devices.value
            val activity = midiManager.deviceActivity.value
            val midiSettings = com.groovebox.midi.MidiConfigStore.settings
            var showSavePreset by remember { mutableStateOf(false) }
            var presetName by remember { mutableStateOf("") }
            var showPresetMenu by remember { mutableStateOf(false) }

            Text("MIDI Devices", style = MaterialTheme.typography.titleMedium, color = Color.Gray)
            Spacer(modifier = Modifier.height(8.dp))

            Card(
                colors = CardDefaults.cardColors(containerColor = Color.DarkGray.copy(alpha = 0.3f)),
                modifier = Modifier.fillMaxWidth().padding(bottom = 16.dp),
                border = BorderStroke(1.dp, Color.Gray.copy(alpha = 0.3f))
            ) {
                Column(modifier = Modifier.padding(12.dp)) {
                    Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text("${devices.size} connected", style = MaterialTheme.typography.labelMedium, color = Color.White)
                            Text(
                                "Per-device routing; saved globally for this machine",
                                style = MaterialTheme.typography.labelSmall,
                                color = Color.Gray
                            )
                        }
                        TextButton(onClick = { midiManager.refreshDevices() }) {
                            Text("REFRESH", fontSize = 11.sp)
                        }
                    }
                    Spacer(modifier = Modifier.height(8.dp))

                    if (devices.isEmpty()) {
                        Text(
                            "No MIDI devices detected. Connect a controller and tap Refresh.",
                            style = MaterialTheme.typography.bodySmall,
                            color = Color.Gray
                        )
                    } else {
                        devices.forEachIndexed { deviceIdx, device ->
                            val config = midiSettings.configs.firstOrNull { it.deviceId == device.id }
                                ?: com.groovebox.midi.MidiDeviceConfig(device.id)
                            MidiDeviceRow(
                                device = device,
                                config = config,
                                lastActivity = activity[device.id],
                                onChange = { com.groovebox.midi.MidiConfigStore.updateDevice(it) }
                            )
                            if (deviceIdx < devices.lastIndex) {
                                Divider(color = Color.Gray.copy(alpha = 0.15f), modifier = Modifier.padding(vertical = 2.dp))
                            }
                        }
                    }

                    Spacer(modifier = Modifier.height(12.dp))
                    Text("HARDWARE PRESETS", style = MaterialTheme.typography.labelMedium, color = Color.White)
                    Text(
                        "Save the current device list and channels as a named setup (Home, Live, ...)",
                        style = MaterialTheme.typography.labelSmall,
                        color = Color.Gray
                    )
                    Spacer(modifier = Modifier.height(6.dp))
                    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Box(modifier = Modifier.weight(1f)) {
                            OutlinedButton(
                                onClick = { showPresetMenu = true },
                                modifier = Modifier.fillMaxWidth(),
                                contentPadding = PaddingValues(horizontal = 8.dp)
                            ) {
                                Text(
                                    midiSettings.activePreset ?: "LOAD PRESET",
                                    fontSize = 11.sp,
                                    maxLines = 1
                                )
                            }
                            DropdownMenu(expanded = showPresetMenu, onDismissRequest = { showPresetMenu = false }) {
                                if (midiSettings.presets.isEmpty()) {
                                    DropdownMenuItem(
                                        text = { Text("No presets saved") },
                                        onClick = { showPresetMenu = false }
                                    )
                                } else {
                                    midiSettings.presets.forEach { preset ->
                                        DropdownMenuItem(
                                            text = { Text(preset.name) },
                                            onClick = {
                                                com.groovebox.midi.MidiConfigStore.loadPreset(preset.name)
                                                showPresetMenu = false
                                            },
                                            trailingIcon = {
                                                IconButton(
                                                    onClick = {
                                                        com.groovebox.midi.MidiConfigStore.deletePreset(preset.name)
                                                    }
                                                ) {
                                                    Icon(Icons.Default.Delete, contentDescription = "Delete preset", tint = Color.Gray)
                                                }
                                            }
                                        )
                                    }
                                }
                            }
                        }
                        OutlinedButton(
                            onClick = { showSavePreset = true },
                            modifier = Modifier.weight(1f),
                            contentPadding = PaddingValues(horizontal = 8.dp)
                        ) {
                            Text("SAVE AS", fontSize = 11.sp, maxLines = 1)
                        }
                    }
                }
            }

            if (showSavePreset) {
                AlertDialog(
                    onDismissRequest = { showSavePreset = false },
                    title = { Text("Save MIDI Preset") },
                    text = {
                        OutlinedTextField(
                            value = presetName,
                            onValueChange = { presetName = it },
                            label = { Text("Name (e.g. Home, Live)") },
                            singleLine = true
                        )
                    },
                    confirmButton = {
                        TextButton(
                            onClick = {
                                if (presetName.isNotBlank()) {
                                    com.groovebox.midi.MidiConfigStore.savePreset(presetName)
                                }
                                presetName = ""
                                showSavePreset = false
                            }
                        ) { Text("Save") }
                    },
                    dismissButton = {
                        TextButton(onClick = { showSavePreset = false }) { Text("Cancel") }
                    }
                )
            }
        }

        // ====== 2e. MIDI CLOCK & EXPRESSION ======
        if (midiManager != null) {
            val midiSettings = com.groovebox.midi.MidiConfigStore.settings
            val clockInputs = midiManager.devices.value.filter { it.isInput }
            var showClockInMenu by remember { mutableStateOf(false) }
            val clockInName = midiSettings.clockInDeviceId
                ?.let { id -> clockInputs.firstOrNull { it.id == id }?.name }
                ?: "Any input"

            Text("MIDI Clock & Expression", style = MaterialTheme.typography.titleMedium, color = Color.Gray)
            Spacer(modifier = Modifier.height(8.dp))

            Card(
                colors = CardDefaults.cardColors(containerColor = Color.DarkGray.copy(alpha = 0.3f)),
                modifier = Modifier.fillMaxWidth().padding(bottom = 16.dp),
                border = BorderStroke(1.dp, Color.Gray.copy(alpha = 0.3f))
            ) {
                Column(modifier = Modifier.padding(12.dp)) {
                    MidiOptionSwitch(
                        title = "Clock In (external sync)",
                        subtitle = "Follow an incoming 24 PPQN clock and its tempo",
                        checked = midiSettings.clockInEnabled,
                        onChange = { com.groovebox.midi.MidiConfigStore.setClockInEnabled(it) }
                    )
                    if (midiSettings.clockInEnabled) {
                        Spacer(modifier = Modifier.height(6.dp))
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                "Source",
                                style = MaterialTheme.typography.labelSmall,
                                color = Color.Gray,
                                modifier = Modifier.weight(1f)
                            )
                            Box {
                                OutlinedButton(
                                    onClick = { showClockInMenu = true },
                                    contentPadding = PaddingValues(horizontal = 8.dp)
                                ) {
                                    Text(clockInName, fontSize = 11.sp, maxLines = 1)
                                }
                                DropdownMenu(
                                    expanded = showClockInMenu,
                                    onDismissRequest = { showClockInMenu = false }
                                ) {
                                    DropdownMenuItem(
                                        text = { Text("Any input") },
                                        onClick = {
                                            com.groovebox.midi.MidiConfigStore.setClockInDevice(null)
                                            showClockInMenu = false
                                        }
                                    )
                                    clockInputs.forEach { device ->
                                        DropdownMenuItem(
                                            text = { Text(device.name) },
                                            onClick = {
                                                com.groovebox.midi.MidiConfigStore.setClockInDevice(device.id)
                                                showClockInMenu = false
                                            }
                                        )
                                    }
                                }
                            }
                        }
                        Text(
                            "Tip: enable Clock In on one device only. Tempo follows the master.",
                            style = MaterialTheme.typography.labelSmall,
                            color = Color.Gray
                        )
                    }

                    Divider(color = Color.Gray.copy(alpha = 0.15f), modifier = Modifier.padding(vertical = 10.dp))

                    MidiOptionSwitch(
                        title = "Clock Out",
                        subtitle = "Send 24 PPQN to every enabled output",
                        checked = midiSettings.clockOutEnabled,
                        onChange = { com.groovebox.midi.MidiConfigStore.setClockOutEnabled(it) }
                    )
                    if (midiSettings.clockOutEnabled) {
                        MidiOptionSwitch(
                            title = "Send Start / Stop",
                            subtitle = "Transport messages (0xFA / 0xFC) with the clock",
                            checked = midiSettings.clockOutStartStop,
                            onChange = { com.groovebox.midi.MidiConfigStore.setClockOutStartStop(it) }
                        )
                    }

                    Divider(color = Color.Gray.copy(alpha = 0.15f), modifier = Modifier.padding(vertical = 10.dp))

                    MidiOptionSwitch(
                        title = "Aftertouch → Pad Mod",
                        subtitle = "Channel pressure drives the track's mod destination",
                        checked = midiSettings.aftertouchEnabled,
                        onChange = { com.groovebox.midi.MidiConfigStore.setAftertouchEnabled(it) }
                    )
                    if (midiSettings.aftertouchEnabled) {
                        MidiOptionSwitch(
                            title = "Poly aftertouch (per voice)",
                            subtitle = "Per-note pressure modulates that note only",
                            checked = midiSettings.polyAftertouchEnabled,
                            onChange = { com.groovebox.midi.MidiConfigStore.setPolyAftertouchEnabled(it) }
                        )
                    }

                    Spacer(modifier = Modifier.height(10.dp))
                    Text(
                        "Pitch-bend range",
                        style = MaterialTheme.typography.labelMedium,
                        color = Color.White
                    )
                    Text(
                        "Semitones at full wheel travel for incoming 0xE0",
                        style = MaterialTheme.typography.labelSmall,
                        color = Color.Gray
                    )
                    Spacer(modifier = Modifier.height(6.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        listOf(2, 12, 24).forEach { range ->
                            FilterChip(
                                selected = midiSettings.pitchBendRange == range,
                                onClick = { com.groovebox.midi.MidiConfigStore.setPitchBendRange(range) },
                                label = { Text("±$range", fontSize = 11.sp) },
                                colors = FilterChipDefaults.filterChipColors(
                                    selectedContainerColor = Color(0xFF4CAF50).copy(alpha = 0.25f),
                                    selectedLabelColor = Color(0xFF4CAF50),
                                    labelColor = Color.Gray
                                )
                            )
                        }
                    }
                }
            }
        }

        // ====== 3. MIDI INPUT ======

        // MIDI Input Channels
        Text("MIDI Input Config", style = MaterialTheme.typography.titleMedium, color = Color.Gray)
        Spacer(modifier = Modifier.height(8.dp))
        
        Card(
            colors = CardDefaults.cardColors(containerColor = Color.DarkGray.copy(alpha = 0.3f)),
            modifier = Modifier.fillMaxWidth().padding(bottom = 16.dp),
            border = BorderStroke(1.dp, Color.Gray.copy(alpha = 0.3f))
        ) {
            Column(modifier = Modifier.padding(12.dp)) {
                Text("Assign specific channels for background playback", style = MaterialTheme.typography.labelSmall, color = Color.Gray)
                Spacer(modifier = Modifier.height(12.dp))
                
                state.tracks.forEachIndexed { i, track ->
                    Row(
                        modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text("T${i+1}: ${track.engineType.name.replace("_", " ")}", modifier = Modifier.weight(1.5f), color = Color.White, style = MaterialTheme.typography.bodySmall)
                        
                        var expanded by remember { mutableStateOf(false) }
                        Box(modifier = Modifier.weight(1f)) {
                            Surface(
                                onClick = { expanded = true },
                                color = Color.DarkGray,
                                shape = RoundedCornerShape(4.dp),
                                border = BorderStroke(1.dp, Color.Gray.copy(alpha = 0.5f))
                            ) {
                                Text(
                                    text = if (track.midiInChannel == 17) "OMNI (All)" else "CH ${track.midiInChannel}",
                                    modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp).fillMaxWidth(),
                                    style = MaterialTheme.typography.bodySmall,
                                    color = if (track.midiInChannel == 17) Color.Cyan else Color.Yellow
                                )
                            }
                            
                            DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
                                DropdownMenuItem(
                                    text = { Text("OMNI (All)") },
                                    onClick = {
                                        onStateChange(state.copy(tracks = state.tracks.mapIndexed { idx, t -> 
                                            if (idx == i) t.copy(midiInChannel = 17) else t 
                                        }))
                                        expanded = false
                                    }
                                )
                                (1..16).forEach { ch ->
                                    DropdownMenuItem(
                                        text = { Text("Channel $ch") },
                                        onClick = {
                                            onStateChange(state.copy(tracks = state.tracks.mapIndexed { idx, t -> 
                                                if (idx == i) t.copy(midiInChannel = ch) else t 
                                            }))
                                            expanded = false
                                        }
                                    )
                                }
                            }
                        }
                    }

                    // Per-track source / destination device filters
                    if (midiManager != null) {
                        val inputDevices = midiManager.devices.value.filter { it.isInput }
                        val outputDevices = midiManager.devices.value.filter { it.isOutput }
                        Row(
                            modifier = Modifier.fillMaxWidth().padding(bottom = 4.dp),
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text("IN", color = Color.Gray, fontSize = 10.sp)
                            TrackMidiDevicePicker(
                                devices = inputDevices,
                                selectedId = track.midiInDeviceId,
                                anyLabel = "Any input",
                                modifier = Modifier.weight(1f)
                            ) { id ->
                                onStateChange(
                                    state.copy(tracks = state.tracks.mapIndexed { idx, t ->
                                        if (idx == i) t.copy(midiInDeviceId = id) else t
                                    })
                                )
                            }
                            if (track.engineType == com.groovebox.EngineType.MIDI) {
                                Text("OUT", color = Color.Gray, fontSize = 10.sp)
                                TrackMidiDevicePicker(
                                    devices = outputDevices,
                                    selectedId = track.midiOutDeviceId,
                                    anyLabel = "All outputs",
                                    modifier = Modifier.weight(1f)
                                ) { id ->
                                    onStateChange(
                                        state.copy(tracks = state.tracks.mapIndexed { idx, t ->
                                            if (idx == i) t.copy(midiOutDeviceId = id) else t
                                        })
                                    )
                                }
                            }
                        }
                    }
                    if (i < 7) Divider(color = Color.Gray.copy(alpha = 0.1f), modifier = Modifier.padding(vertical = 2.dp))
                }
            }
        }

        // ====== 4. MIDI MONITOR ======
        // MIDI STATUS & LINKS OVERLAY
        if (midiManager != null) {
            Row(
                modifier = Modifier.fillMaxWidth().padding(bottom = 16.dp).height(intrinsicSize = IntrinsicSize.Min),
                horizontalArrangement = Arrangement.spacedBy(16.dp)
            ) {
                // Left Half: MIDI Monitor
                Card(
                    colors = CardDefaults.cardColors(containerColor = Color.Black.copy(alpha = 0.6f)),
                    modifier = Modifier.weight(1f).fillMaxHeight(),
                    border = BorderStroke(1.dp, Color.DarkGray)
                ) {
                    Column(modifier = Modifier.padding(12.dp)) {
                        Text("MIDI MONITOR", style = MaterialTheme.typography.titleSmall, color = Color.Gray)
                        Spacer(modifier = Modifier.height(4.dp))
                        Text(
                            text = "Device: ${midiManager?.deviceName?.value}",
                            color = Color.Yellow,
                            style = MaterialTheme.typography.labelSmall
                        )
                        Spacer(modifier = Modifier.height(4.dp))
                        Text(
                            text = midiManager?.midiLog?.value ?: "".takeLast(500),
                            color = Color.Red,
                            style = MaterialTheme.typography.bodySmall,
                            fontFamily = FontFamily.Monospace,
                            softWrap = true,
                            modifier = Modifier
                                .fillMaxWidth()
                                .background(Color.Black)
                                .padding(4.dp)
                                .height(80.dp)
                                .verticalScroll(rememberScrollState())
                        )
                    }
                }

                // Right Half: Social Links
                Card(
                    colors = CardDefaults.cardColors(containerColor = Color.DarkGray.copy(alpha = 0.2f)),
                    modifier = Modifier.weight(1f).fillMaxHeight(),
                    border = BorderStroke(1.dp, Color.Gray.copy(alpha = 0.3f))
                ) {
                    Column(
                        modifier = Modifier.padding(12.dp).fillMaxSize(),
                        verticalArrangement = Arrangement.Center,
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        Text("RESOURCES", style = MaterialTheme.typography.titleSmall, color = Color.Gray)
                        Spacer(modifier = Modifier.height(4.dp))
                        Text("v${com.groovebox.utils.Version.APP_VERSION}", style = MaterialTheme.typography.labelSmall, color = Color.Cyan.copy(alpha = 0.7f))
                        Spacer(modifier = Modifier.height(8.dp))
                        
                        OutlinedButton(
                            onClick = {
                                // platformOpenUrl("https://www.youtube.com/@LoomGroovebox")
                            },
                            modifier = Modifier.fillMaxWidth(),
                            colors = ButtonDefaults.outlinedButtonColors(contentColor = Color.Red),
                            border = BorderStroke(1.dp, Color.Red.copy(alpha = 0.5f))
                        ) {
                            Text("YouTube", fontWeight = FontWeight.Bold)
                        }
                        
                        Spacer(modifier = Modifier.height(8.dp))
                        
                        OutlinedButton(
                            onClick = {
                                // platformOpenUrl("https://github.com/Co-oscillator/loom-groovebox")
                            },
                            modifier = Modifier.fillMaxWidth(),
                            colors = ButtonDefaults.outlinedButtonColors(contentColor = Color.White),
                            border = BorderStroke(1.dp, Color.White.copy(alpha = 0.5f))
                        ) {
                            Text("GitHub", fontWeight = FontWeight.Bold)
                        }
                    }
                }
            }
        }
        // ====== 5. ASSIGNABLE CONTROLS ======
        Text("Assignable Controls (MIDI Learn)", style = MaterialTheme.typography.titleMedium, color = Color.Gray)
        Spacer(modifier = Modifier.height(8.dp))
        
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            // Touch Strips Cluster
            Column(
                modifier = Modifier
                    .weight(1f)
                    .border(1.dp, Color.White.copy(alpha = 0.1f), RoundedCornerShape(8.dp))
                    .padding(8.dp)
            ) {
                Text("TOUCH STRIPS", style = MaterialTheme.typography.labelSmall, color = Color.Cyan)
                Spacer(modifier = Modifier.height(4.dp))
                state.stripRoutings.forEachIndexed { index, routing ->
                    Row(
                        modifier = Modifier.fillMaxWidth().padding(vertical = 2.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text("S${index + 1}", style = MaterialTheme.typography.bodySmall, fontWeight = FontWeight.Bold, color = Color.LightGray)
                            Text(routing.parameterName.take(12), style = MaterialTheme.typography.labelSmall, color = Color.Gray, maxLines = 1)
                        }
                        Box(
                            modifier = Modifier
                                .size(32.dp)
                                .background(if (mappingStripIndex == index) Color.Yellow else Color.DarkGray, RoundedCornerShape(4.dp))
                                .clickable { mappingStripIndex = if (mappingStripIndex == index) -1 else index },
                            contentAlignment = Alignment.Center
                        ) {
                            Text(if (mappingStripIndex == index) "..." else "SET", color = if (mappingStripIndex == index) Color.Black else Color.White, style = MaterialTheme.typography.labelSmall)
                        }
                    }
                }
            }

            // Knobs Cluster
            Column(
                modifier = Modifier
                    .weight(1f)
                    .border(1.dp, Color.White.copy(alpha = 0.1f), RoundedCornerShape(8.dp))
                    .padding(8.dp)
            ) {
                Text("MACRO KNOBS", style = MaterialTheme.typography.labelSmall, color = Color.Magenta)
                Spacer(modifier = Modifier.height(4.dp))
                state.knobRoutings.forEachIndexed { index, routing ->
                    val globalIndex = index + 4
                    Row(
                        modifier = Modifier.fillMaxWidth().padding(vertical = 2.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text("K${index + 1}", style = MaterialTheme.typography.bodySmall, fontWeight = FontWeight.Bold, color = Color.LightGray)
                            Text(routing.parameterName.take(12), style = MaterialTheme.typography.labelSmall, color = Color.Gray, maxLines = 1)
                        }
                        Box(
                            modifier = Modifier
                                .size(32.dp)
                                .background(if (mappingStripIndex == globalIndex) Color.Yellow else Color.DarkGray, RoundedCornerShape(4.dp))
                                .clickable { mappingStripIndex = if (mappingStripIndex == globalIndex) -1 else globalIndex },
                            contentAlignment = Alignment.Center
                        ) {
                            Text(if (mappingStripIndex == globalIndex) "..." else "SET", color = if (mappingStripIndex == globalIndex) Color.Black else Color.White, style = MaterialTheme.typography.labelSmall)
                        }
                    }
                }
            }
        }
        
        if (mappingStripIndex != -1) {
            Spacer(modifier = Modifier.height(16.dp))
            Text("Simulate Parameter Selection (Click one):", style = MaterialTheme.typography.labelMedium)
            val params = listOf("Volume T1", "Volume T2", "Filter Cutoff T1", "Reverb Wet", "Delay Feedback")
            params.forEach { param ->
                OutlinedButton(
                    onClick = {
                        if (mappingStripIndex < 4) {
                            val newRoutings = state.stripRoutings.toMutableList()
                            newRoutings[mappingStripIndex] = newRoutings[mappingStripIndex].copy(parameterName = param, targetType = 1, targetId = 100) // Mock ID
                            onStateChange(state.copy(stripRoutings = newRoutings))
                        } else {
                            val knobIdx = mappingStripIndex - 4
                            val newRoutings = state.knobRoutings.toMutableList()
                            newRoutings[knobIdx] = newRoutings[knobIdx].copy(parameterName = param, targetType = 1, targetId = 100) // Mock ID
                            onStateChange(state.copy(knobRoutings = newRoutings))
                        }
                        mappingStripIndex = -1
                    },
                    modifier = Modifier.fillMaxWidth().padding(vertical = 2.dp)
                ) {
                    Text(param)
                }
            }
        }

        Spacer(modifier = Modifier.height(16.dp))
        // ====== 6. PANIC / RESET ======
        Button(
            onClick = {
                // PANIC / RESET AUDIO ENGINE
                nativeLib.stop()
                nativeLib.init() 
                // nativeLib.setAppDataDir(...) // Managed by engine/platform
                syncNativeState(state, nativeLib)
                nativeLib.start()
                platformShowMessage("Audio Engine Reset")
            },
            modifier = Modifier.fillMaxWidth().padding(bottom = 16.dp),
            colors = ButtonDefaults.buttonColors(containerColor = Color.Red.copy(alpha=0.7f))
        ) {
            Text("RESET AUDIO ENGINE (PANIC)", color = Color.White, fontWeight = FontWeight.Bold)
        }
        // ====== 7. CREDITS & PRIVACY ======

        var showCreditsDialog by remember { mutableStateOf(false) }
        Button(
            onClick = { showCreditsDialog = true },
            modifier = Modifier.fillMaxWidth().padding(bottom = 16.dp),
            colors = ButtonDefaults.buttonColors(containerColor = Color.Magenta.copy(alpha=0.3f)),
            border = BorderStroke(1.dp, Color.Magenta.copy(alpha=0.5f))
        ) {
            Icon(Icons.Default.Info, contentDescription = null, modifier = Modifier.size(20.dp), tint = Color.Magenta)
            Spacer(modifier = Modifier.width(8.dp))
            Text("Credits & Privacy Policy", color = Color.Magenta, fontWeight = FontWeight.SemiBold)
        }

        if (showCreditsDialog) {
            SoundFontCreditsDialog(onDismiss = { showCreditsDialog = false })
        }
    }
}

@Composable
private fun MidiBadge(text: String, color: Color) {
    Text(
        text = text,
        color = color,
        fontSize = 9.sp,
        fontWeight = FontWeight.Bold,
        modifier = Modifier
            .background(color.copy(alpha = 0.15f), RoundedCornerShape(3.dp))
            .border(1.dp, color.copy(alpha = 0.6f), RoundedCornerShape(3.dp))
            .padding(horizontal = 4.dp, vertical = 1.dp)
    )
}

@Composable
private fun MidiChannelChip(label: String, value: Int, onChange: (Int) -> Unit) {
    var expanded by remember { mutableStateOf(false) }
    val valueText = when (value) {
        -1 -> "ALL"
        0 -> "OFF"
        else -> "CH $value"
    }
    Box {
        OutlinedButton(
            onClick = { expanded = true },
            modifier = Modifier.height(30.dp),
            contentPadding = PaddingValues(horizontal = 8.dp)
        ) {
            Text("$label $valueText", fontSize = 10.sp, maxLines = 1)
        }
        DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            DropdownMenuItem(
                text = { Text("All channels") },
                onClick = { onChange(-1); expanded = false }
            )
            DropdownMenuItem(
                text = { Text("Off") },
                onClick = { onChange(0); expanded = false }
            )
            (1..16).forEach { ch ->
                DropdownMenuItem(
                    text = { Text("Channel $ch") },
                    onClick = { onChange(ch); expanded = false }
                )
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun MidiToggleChip(label: String, selected: Boolean, onToggle: (Boolean) -> Unit) {
    FilterChip(
        selected = selected,
        onClick = { onToggle(!selected) },
        label = { Text(label, fontSize = 10.sp) },
        colors = FilterChipDefaults.filterChipColors(
            selectedContainerColor = Color(0xFFFF7043).copy(alpha = 0.25f),
            selectedLabelColor = Color(0xFFFF7043),
            labelColor = Color.Gray
        )
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun MidiOptionSwitch(
    title: String,
    subtitle: String,
    checked: Boolean,
    onChange: (Boolean) -> Unit
) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.labelMedium, color = Color.White)
            Text(subtitle, style = MaterialTheme.typography.labelSmall, color = Color.Gray)
        }
        Spacer(modifier = Modifier.width(8.dp))
        Switch(checked = checked, onCheckedChange = onChange)
    }
}

@Composable
private fun MidiDeviceRow(
    device: com.groovebox.midi.MidiDeviceInfo,
    config: com.groovebox.midi.MidiDeviceConfig,
    lastActivity: Long?,
    onChange: (com.groovebox.midi.MidiDeviceConfig) -> Unit
) {
    val isActive = lastActivity != null && System.currentTimeMillis() - lastActivity < 1500

    Column(modifier = Modifier.fillMaxWidth().padding(vertical = 6.dp)) {
        Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Box(
                modifier = Modifier
                    .size(8.dp)
                    .background(if (isActive) Color.Green else Color.DarkGray, CircleShape)
            )
            Spacer(modifier = Modifier.width(8.dp))
            Text(
                text = device.name,
                color = if (config.enabled) Color.White else Color.Gray,
                fontWeight = FontWeight.SemiBold,
                fontSize = 12.sp,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f)
            )
            if (device.isInput) {
                MidiBadge("IN", Color(0xFF00BCD4))
                Spacer(modifier = Modifier.width(4.dp))
            }
            if (device.isOutput) {
                MidiBadge("OUT", Color(0xFF8BC34A))
                Spacer(modifier = Modifier.width(4.dp))
            }
            Switch(
                checked = config.enabled,
                onCheckedChange = { onChange(config.copy(enabled = it)) }
            )
        }

        if (config.enabled) {
            Spacer(modifier = Modifier.height(4.dp))
            Row(
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                if (device.isOutput) {
                    MidiChannelChip("SEND", config.sendChannel) {
                        onChange(config.copy(sendChannel = it))
                    }
                }
                if (device.isInput) {
                    MidiChannelChip("RECV", config.receiveChannel) {
                        onChange(config.copy(receiveChannel = it))
                    }
                }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                if (device.isOutput) {
                    MidiToggleChip("Mute Out", config.muteOutgoing) {
                        onChange(config.copy(muteOutgoing = it))
                    }
                }
                if (device.isInput) {
                    MidiToggleChip("Ignore In", config.ignoreIncoming) {
                        onChange(config.copy(ignoreIncoming = it))
                    }
                }
            }
        }
    }
}

@Composable
private fun TrackMidiDevicePicker(
    devices: List<com.groovebox.midi.MidiDeviceInfo>,
    selectedId: String?,
    anyLabel: String,
    modifier: Modifier = Modifier,
    onSelect: (String?) -> Unit
) {
    var expanded by remember { mutableStateOf(false) }
    val selectedName = devices.firstOrNull { it.id == selectedId }?.name
    val label = when {
        selectedId == null -> anyLabel
        selectedName == null -> "Missing device"
        selectedName.length > 14 -> selectedName.take(13) + "…"
        else -> selectedName
    }

    Box(modifier = modifier) {
        Surface(
            onClick = { expanded = true },
            color = Color.DarkGray,
            shape = RoundedCornerShape(4.dp),
            border = BorderStroke(1.dp, Color.Gray.copy(alpha = 0.5f))
        ) {
            Text(
                text = label,
                modifier = Modifier.padding(horizontal = 6.dp, vertical = 3.dp).fillMaxWidth(),
                style = MaterialTheme.typography.labelSmall,
                color = if (selectedId == null) Color.Gray else Color.Cyan,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }
        DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            DropdownMenuItem(
                text = { Text(anyLabel) },
                onClick = { onSelect(null); expanded = false }
            )
            devices.forEach { device ->
                DropdownMenuItem(
                    text = { Text(device.name, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                    onClick = { onSelect(device.id); expanded = false }
                )
            }
        }
    }
}
