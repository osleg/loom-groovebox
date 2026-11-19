package com.groovebox.ui.views

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.*
import androidx.compose.ui.graphics.Color
import com.groovebox.ui.components.PointerVisualizerOverlay
import com.groovebox.GrooveboxViewModel
import com.groovebox.NativeLib
import com.groovebox.midi.EmpledManager
import com.groovebox.midi.MidiManager
import com.groovebox.EngineType
import com.groovebox.GridMode
import com.groovebox.ui.LocalPlatformInfo
import com.groovebox.ui.PlatformInfo
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Settings
import androidx.compose.ui.input.pointer.pointerInput
import com.groovebox.ui.LocalFocusedValue
import com.groovebox.ui.LocalFocusedSetter
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown

@Composable
fun MainScreen(
    viewModel: GrooveboxViewModel,
    nativeLib: NativeLib,
    empledManager: EmpledManager? = null,
    midiManager: MidiManager? = null
) {
    val state = viewModel.state
    
    BoxWithConstraints(modifier = Modifier.fillMaxSize()) {
        val platform = remember {
            val os = System.getProperty("os.name").lowercase()
            when {
                os.contains("mac") -> "macos"
                os.contains("win") -> "windows"
                os.contains("linux") -> "linux"
                else -> "android" // Default to android as common entry point
            }
        }
        
        val platformInfo = PlatformInfo(
            screenWidthDp = maxWidth.value.toInt(),
            screenHeightDp = maxHeight.value.toInt(),
            isTablet = maxWidth.value >= 600 && maxHeight.value >= 480,
            platform = platform
        )
        
        CompositionLocalProvider(
            LocalPlatformInfo provides platformInfo,
            LocalFocusedValue provides null,
            LocalFocusedSetter provides { newValue -> viewModel.onStateChange(viewModel.state.copy(focusedValue = newValue)) }
        ) {
            // UI Sync Loop (30fps polling)
            LaunchedEffect(Unit) {
                while (true) {
                    // 1. Pull core engine state (CPU, Transport, Parameters)
                    viewModel.pollEngineState() 
                    
                    val selectedTrackIdx = viewModel.state.selectedTrackIndex
                    val currentTrack = viewModel.state.tracks[selectedTrackIdx]
                    
                    // Playhead and Sequencing are handles via polling snapshots in pollEngineState()
                    // 2. Playhead sync is now automatic via currentStep in state
                    
                    // 3. Waveform Updates for Sampler/Granular
                    
                    // 4. Waveform Updates for Sampler/Granular
                    if (currentTrack.engineType == com.groovebox.EngineType.SAMPLER || 
                        currentTrack.engineType == com.groovebox.EngineType.GRANULAR) {
                        viewModel.pullWaveform(selectedTrackIdx)
                    }
                    
                    // Idle: poll slowly so a still screen costs almost nothing.
                    // Fast again as soon as transport runs or levels move.
                    kotlinx.coroutines.delay(
                        when {
                            viewModel.state.performanceProfile >= 2 -> 64L
                            viewModel.isUiActive -> 32L
                            else -> 120L
                        }
                    )
                }
            }
            Surface(color = Color.Black) {
                PointerVisualizerOverlay(
                    state = state,
                    enabled = platformInfo.platform == "macos" && state.isPerformanceMode
                ) {
                    Row(modifier = Modifier.fillMaxSize()) {
                    // Left Edge: Sidebar Mixer
                    MixerView(
                        state = state,
                        onStateChange = viewModel::onStateChange,
                        nativeLib = nativeLib
                    )

                    // Center: Main Content Area
                    Box(modifier = Modifier.weight(1f).fillMaxHeight()) {
                        when (state.selectedTab) {
                            0 -> PlayingScreen(
                                state = state,
                                onStateChange = viewModel::onStateChange,
                                nativeLib = nativeLib,
                                empledManager = empledManager,
                                midiManager = midiManager
                            )
                            1 -> ParametersScreen(
                                viewModel = viewModel,
                                trackIndex = state.selectedTrackIndex,
                                nativeLib = nativeLib
                            )
                            2 -> SequencerView(
                                viewModel = viewModel,
                                nativeLib = nativeLib,
                                empledManager = empledManager
                            )
                            3 -> GlobalEffectsView(
                                state = state,
                                onStateChange = viewModel::onStateChange,
                                nativeLib = nativeLib
                            )
                            4 -> RoutingScreen(
                                state = state,
                                onStateChange = viewModel::onStateChange,
                                nativeLib = nativeLib
                            )
                            5 -> SettingsScreen(
                                state = state,
                                onStateChange = viewModel::onStateChange,
                                nativeLib = nativeLib,
                                midiManager = midiManager,
                                stateForSave = { viewModel.drainRecordedSteps(); viewModel.state }
                            )
                        }

                        // Top Info Bar (CPU & Parameter Display)
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(20.dp)
                                .background(Color.Black.copy(alpha = 0.6f))
                                .padding(horizontal = 8.dp),
                            contentAlignment = Alignment.CenterStart
                        ) {
                            Row(
                                modifier = Modifier.fillMaxSize(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                // Focused Parameter Value
                                Text(
                                    text = state.focusedValue ?: "",
                                    color = Color.Cyan,
                                    fontSize = 11.sp,
                                    fontWeight = androidx.compose.ui.text.font.FontWeight.Medium,
                                    style = MaterialTheme.typography.labelSmall
                                )
                                
                                // CPU Monitor
                                if (state.showCpuMonitor) {
                                    Row(verticalAlignment = Alignment.CenterVertically) {
                                        Text(
                                            text = "CPU",
                                            color = Color.Gray,
                                            fontSize = 9.sp,
                                            modifier = Modifier.padding(end = 4.dp)
                                        )
                                        val cpuPerc = (state.cpuLoad * 100).coerceIn(0f, 100f)
                                        Box(
                                            modifier = Modifier
                                                .width(40.dp)
                                                .height(4.dp)
                                                .background(Color.DarkGray, androidx.compose.foundation.shape.RoundedCornerShape(2.dp))
                                        ) {
                                            Box(
                                                modifier = Modifier
                                                    .fillMaxWidth(cpuPerc / 100f)
                                                    .fillMaxHeight()
                                                    .background(
                                                        if (cpuPerc > 80f) Color.Red else if (cpuPerc > 50f) Color.Yellow else Color.Green,
                                                        androidx.compose.foundation.shape.RoundedCornerShape(2.dp)
                                                    )
                                            )
                                        }
                                        Text(
                                            text = "${cpuPerc.toInt()}%",
                                            color = if (cpuPerc > 80f) Color.Red else Color.White,
                                            fontSize = 9.sp,
                                            modifier = Modifier.padding(start = 4.dp)
                                        )
                                    }
                                }
                            }
                        }
                    }

                    // Right Sidebars
                    Row(
                        modifier = Modifier.fillMaxHeight(),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        // Transport Column
                        Box(modifier = Modifier.width(80.dp).fillMaxHeight().background(Color.Black.copy(alpha = 0.2f))) {
                            TransportControls(viewModel, nativeLib)
                        }
                        
                        // Vertical Navigation Tabs
                        VerticalNavigationTabs(state.selectedTab, state.isRecording) { 
                            viewModel.setSelectedTab(it) 
                        }
                    }
                }
                }
            }
        }
    }
}

@Composable
fun VerticalNavigationTabs(selectedTab: Int, isRecording: Boolean, onTabSelected: (Int) -> Unit) {
    Column(
        modifier = Modifier
            .width(52.dp)
            .fillMaxHeight()
            .background(Color.Black.copy(alpha = 0.5f)),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(0.dp)
    ) {
        val tabItems = listOf("Play", "Param", "Seq", "FX", "Patch", "Settings")
        tabItems.forEachIndexed { index, title ->
            val isRecordTarget = isRecording && (index == 0 || index == 1 || index == 2)
            
            Box(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth()
                    .clickable { onTabSelected(index) }
                    .padding(vertical = 1.dp),
                contentAlignment = Alignment.Center
            ) {
                if (selectedTab == index) {
                    Box(
                        modifier = Modifier
                            .fillMaxSize()
                            .padding(2.dp)
                            .background(Color.Cyan.copy(alpha = 0.1f), androidx.compose.foundation.shape.RoundedCornerShape(4.dp))
                            .border(1.dp, Color.Cyan, androidx.compose.foundation.shape.RoundedCornerShape(4.dp))
                    )
                } else if (isRecordTarget) {
                    Box(
                        modifier = Modifier
                            .fillMaxSize()
                            .padding(2.dp)
                            .border(1.dp, Color.Red, androidx.compose.foundation.shape.RoundedCornerShape(4.dp))
                    )
                }
                
                if (index < 5) {
                    Text(
                        text = title.uppercase(),
                        style = MaterialTheme.typography.labelSmall,
                        color = if (selectedTab == index) Color.Cyan else if (isRecordTarget) Color.Red else Color.White,
                        modifier = Modifier.graphicsLayer { rotationZ = 90f }
                    )
                } else {
                    Icon(
                        androidx.compose.material.icons.Icons.Filled.Settings,
                        contentDescription = "Settings",
                        modifier = Modifier.size(20.dp).graphicsLayer { rotationZ = 90f },
                        tint = if (selectedTab == index) Color.Cyan else Color.White
                    )
                }
            }
        }
    }
}

@Composable
fun TransportControls(viewModel: com.groovebox.GrooveboxViewModel, nativeLib: NativeLib) {
    val state = viewModel.state
    val onStateChange = viewModel::onStateChange
    val platformInfo = LocalPlatformInfo.current
    val isWideScreen = (platformInfo.screenWidthDp.toFloat() / platformInfo.screenHeightDp.toFloat()) > 1.7f
    val spacing = if (isWideScreen) 4.dp else 4.dp

    Column(
        modifier = Modifier.fillMaxSize().padding(horizontal = 2.dp, vertical = spacing),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = if (isWideScreen) Arrangement.spacedBy(4.dp) else Arrangement.spacedBy(4.dp)
    ) {
        // Cluster 1: BPM and Swing
        Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(if (isWideScreen) 4.dp else 2.dp)) {
            com.groovebox.ui.components.Knob("BPM", (state.tempo - 12f) / 228f, -1, state, onStateChange, nativeLib, knobSize = if (isWideScreen) 40.dp else 56.dp, 
                onValueChangeOverride = {
                    val newBpm = 12f + (it * 228f)
                    onStateChange(state.copy(tempo = newBpm))
                    nativeLib.setTempo(newBpm)
                },
                valueFormatter = {
                    val bpm = 12f + (it * 228f)
                    "BPM: ${bpm.toInt()}"
                }
            )
            
            var tapTimes by remember { mutableStateOf(listOf<Long>()) }
            Button(
                onClick = {
                    val now = System.currentTimeMillis()
                    val newTaps = tapTimes.filter { now - it < 2000 } + now
                    if (newTaps.size >= 2) {
                        val intervals = newTaps.zipWithNext { a, b -> b - a }
                        val avgInterval = intervals.average()
                        val newBpm = (60000f / avgInterval.toFloat()).coerceIn(12f, 240f)
                        onStateChange(state.copy(tempo = newBpm))
                        nativeLib.setTempo(newBpm)
                    }
                    tapTimes = newTaps
                },
                modifier = Modifier.height(24.dp).width(50.dp),
                contentPadding = PaddingValues(0.dp),
                colors = ButtonDefaults.buttonColors(containerColor = Color.DarkGray),
                shape = androidx.compose.foundation.shape.RoundedCornerShape(4.dp)
            ) {
                Text("Tap${state.tempo.toInt()}", style = MaterialTheme.typography.labelSmall, fontSize = 9.sp, color = Color.White)
            }
            
            com.groovebox.ui.components.Knob("SWNG", state.swing, -1, state, onStateChange, nativeLib, knobSize = if (isWideScreen) 40.dp else 56.dp, onValueChangeOverride = {
                onStateChange(state.copy(swing = it))
                nativeLib.setSwing(it)
            })
            if (!isWideScreen) Text("${(state.swing * 100).toInt()}%", style = MaterialTheme.typography.labelSmall, color = Color.Cyan)
        }

        Divider(color = Color.White.copy(alpha = 0.1f), modifier = Modifier.padding(horizontal = 8.dp))

        // Cluster 2: Play, Record, Stop
        Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(16.dp)) {
            // Record Button
            Box(
                modifier = Modifier
                    .size(if (isWideScreen) 34.dp else 44.dp)
                    .background(if (state.isRecording) Color.Red else Color.Red.copy(alpha = 0.3f), androidx.compose.foundation.shape.RoundedCornerShape(22.dp))
                    .border(2.dp, Color.Red, androidx.compose.foundation.shape.RoundedCornerShape(22.dp))
                    .clickable {
                        val newRec = !state.isRecording
                        viewModel.setPlaybackState(isPlaying = if (newRec) true else state.isPlaying, isRecording = newRec)
                        nativeLib.setIsRecording(newRec)
                        if (newRec) nativeLib.setPlaying(true)
                    },
                contentAlignment = Alignment.Center
            ) {
                if (state.isRecording) {
                    Box(modifier = Modifier.size(if (isWideScreen) 10.dp else 14.dp).background(Color.White, androidx.compose.foundation.shape.RoundedCornerShape(6.dp)))
                } else {
                    Box(modifier = Modifier.size(if (isWideScreen) 10.dp else 14.dp).background(Color.Red, androidx.compose.foundation.shape.RoundedCornerShape(6.dp)))
                }
            }

            // Play Button
            IconButton(
                onClick = { 
                    val newPlaying = !state.isPlaying
                    nativeLib.setPlaying(newPlaying)
                    if (!newPlaying) viewModel.drainRecordedSteps()
                    viewModel.setPlaybackState(isPlaying = newPlaying,
                        isRecording = newPlaying && viewModel.state.isRecording)
                },
                modifier = Modifier.size(if (isWideScreen) 34.dp else 44.dp).background(if (state.isPlaying) Color.Green.copy(alpha = 0.2f) else Color.DarkGray, androidx.compose.foundation.shape.RoundedCornerShape(22.dp))
            ) {
                Icon(
                    androidx.compose.material.icons.Icons.Filled.PlayArrow, 
                    contentDescription = "Play", 
                    tint = if (state.isPlaying) Color.Green else Color.White,
                    modifier = Modifier.size(if (isWideScreen) 18.dp else 24.dp)
                )
            }
            // Stop Button
            var stopFlash by remember { mutableStateOf(false) }
            val stopColor by androidx.compose.animation.animateColorAsState(if (stopFlash) Color.Red else Color.Gray)
            
            Box(
                modifier = Modifier
                    .size(if (isWideScreen) 34.dp else 44.dp)
                    .background(stopColor, androidx.compose.foundation.shape.RoundedCornerShape(22.dp))
                    .pointerInput(Unit) {
                        awaitEachGesture {
                            awaitFirstDown()
                            stopFlash = true
                            viewModel.setPlaybackState(isPlaying = false, isRecording = false)
                            nativeLib.setPlaying(false)
                            nativeLib.setIsRecording(false)
                            nativeLib.panic()
                            
                            do {
                                val event = awaitPointerEvent()
                            } while (event.changes.any { it.pressed })
                            
                            stopFlash = false
                        }
                    },
                contentAlignment = Alignment.Center
            ) {
                Box(modifier = Modifier.size(if (isWideScreen) 14.dp else 18.dp).background(Color.White, androidx.compose.foundation.shape.RoundedCornerShape(2.dp)))
            }
        }

        Divider(color = Color.White.copy(alpha = 0.1f), modifier = Modifier.padding(horizontal = 8.dp))

        // Cluster 3: Master Volume
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            com.groovebox.ui.components.Knob(if (isWideScreen) "VOL" else "MASTER", state.masterVolume, -1, state, onStateChange, nativeLib, knobSize = if (isWideScreen) 36.dp else 48.dp, onValueChangeOverride = {
                onStateChange(state.copy(masterVolume = it))
                nativeLib.setMasterVolume(it)
            })
            if (!isWideScreen) Text("VOLUME", style = MaterialTheme.typography.labelSmall, color = Color.Gray, fontSize = 8.sp)
        }

        Divider(color = Color.White.copy(alpha = 0.1f), modifier = Modifier.padding(horizontal = 4.dp))

        // Cluster 4: Playback Order
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(2.dp),
            modifier = Modifier.padding(bottom = 2.dp)
        ) {
            val buttonModifier = Modifier.size(if (isWideScreen) 34.dp else 46.dp, 30.dp)
            val buttonShape = androidx.compose.foundation.shape.RoundedCornerShape(8.dp)
            
            val (label, color) = when {
                state.isRandomOrder -> "RND" to Color.Magenta
                state.playbackDirection == 2 -> "PNG" to Color.Cyan
                state.playbackDirection == 1 -> "REV" to Color.Red
                else -> "REG" to Color.Gray
            }
            
            Button(
                onClick = {
                    if (!state.isRandomOrder) {
                        when (state.playbackDirection) {
                            0 -> { // REG -> REV
                                onStateChange(state.copy(playbackDirection = 1))
                                nativeLib.setPlaybackDirection(state.selectedTrackIndex, 1)
                            }
                            1 -> { // REV -> PNG
                                onStateChange(state.copy(playbackDirection = 2))
                                nativeLib.setPlaybackDirection(state.selectedTrackIndex, 2)
                            }
                            2 -> { // PNG -> RND
                                onStateChange(state.copy(playbackDirection = 0, isRandomOrder = true))
                                nativeLib.setPlaybackDirection(state.selectedTrackIndex, 0)
                                nativeLib.setIsRandomOrder(state.selectedTrackIndex, true)
                            }
                        }
                    } else {
                        // RND -> REG
                        onStateChange(state.copy(isRandomOrder = false, playbackDirection = 0))
                        nativeLib.setIsRandomOrder(state.selectedTrackIndex, false)
                        nativeLib.setPlaybackDirection(state.selectedTrackIndex, 0)
                    }
                },
                modifier = buttonModifier,
                contentPadding = PaddingValues(0.dp),
                colors = ButtonDefaults.buttonColors(containerColor = color),
                shape = buttonShape
            ) { 
                Text(label, style = MaterialTheme.typography.labelSmall, fontWeight = androidx.compose.ui.text.font.FontWeight.Bold, color = Color.White, maxLines = 1) 
            }
            
            Text("ORDER", style = MaterialTheme.typography.labelSmall, color = Color.Gray, fontSize = 8.sp)
        }
    }
}
