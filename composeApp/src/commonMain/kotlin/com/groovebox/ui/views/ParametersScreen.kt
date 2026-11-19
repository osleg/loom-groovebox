package com.groovebox.ui.views


import com.groovebox.ui.LocalPlatformInfo
import com.groovebox.ui.platformShowMessage
import com.groovebox.ui.openUrl
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.*
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.material.icons.filled.Share
import com.groovebox.utils.SysexParser
import com.groovebox.utils.DX7Voice
import com.groovebox.GrooveboxState
import com.groovebox.NativeLib
import com.groovebox.EngineType
import com.groovebox.ui.components.Knob
import com.groovebox.ui.components.CompactParameterBox
import com.groovebox.ui.components.VerticalSlider
import com.groovebox.ui.components.EngineIcon
import com.groovebox.ui.theme.getEngineColor
import com.groovebox.ui.components.VerticalSlider
import com.groovebox.ui.LocalPlatformInfo
import androidx.compose.ui.text.font.FontWeight
import com.groovebox.ToggleIcon
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Menu
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material.icons.filled.List
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.outlined.Lock
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.res.painterResource
import java.io.File
import com.groovebox.persistence.PersistenceManager
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import com.groovebox.ui.components.NativeFileDialog
import com.groovebox.utils.AudioExporter
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.withContext
import androidx.compose.animation.core.*
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.waitForUpOrCancellation
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.ui.input.pointer.util.VelocityTracker
import androidx.compose.ui.input.pointer.positionChange
import androidx.compose.ui.input.pointer.changedToDown
import androidx.compose.ui.input.pointer.changedToUp
import com.groovebox.ui.components.CompactParameterBox
import com.groovebox.ui.components.CompactKnobRow
import com.groovebox.TrackState



@OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class, androidx.compose.material3.ExperimentalMaterial3Api::class)
@Composable
fun ParametersScreen(viewModel: com.groovebox.GrooveboxViewModel, trackIndex: Int, nativeLib: NativeLib, onRecordingSourceChange: (Int) -> Unit = {}) {
    val state = viewModel.state
    val onStateChange = viewModel::onStateChange
    if (trackIndex < 0 || trackIndex >= state.tracks.size) return
    val track = state.tracks[trackIndex]
    
    var showPresetBrowser by remember { mutableStateOf(false) }

    // Auto-refresh mechanism for visualizers (like Grain playheads)
    var refreshTicker by remember { mutableStateOf(0) }
    LaunchedEffect(Unit) {
        while(true) {
            delay(50)
            refreshTicker++
        }
    }

    Column(modifier = Modifier.fillMaxSize().padding(16.dp).verticalScroll(rememberScrollState())) {
        // Premium Title Bar with Centered Action Buttons
        val engineColor = getEngineColor(track.engineType)
        Row(
            modifier = Modifier.fillMaxWidth().padding(bottom = 12.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            // Left: Engine Name Label
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.weight(1f)
            ) {
                EngineIcon(type = track.engineType, modifier = Modifier.size(24.dp), color = engineColor)
                Spacer(modifier = Modifier.width(8.dp))
                val displayName = track.engineType.name.replace("_", "\n")
                Text(
                    displayName, 
                    color = engineColor, 
                    fontSize = if (displayName.length > 8) 12.sp else 18.sp, 
                    fontWeight = FontWeight.ExtraBold,
                    letterSpacing = 1.sp,
                    lineHeight = 12.sp
                )
            }
            
             // Center: Test Note, Default Patch (as pair)
            Row(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                val buttonWidth = 70.dp
                val buttonHeight = 42.dp
                val buttonShape = RoundedCornerShape(4.dp)
                
                // Test Note Button (with proper press detection)
                var isPressed by remember { mutableStateOf(false) }
                Box(
                    modifier = Modifier
                        .size(width = 60.dp, height = buttonHeight)
                        .background(if (isPressed) Color.Cyan.copy(alpha = 0.3f) else Color.DarkGray, buttonShape)
                        .pointerInput(trackIndex) {
                            detectTapGestures(
                                onPress = {
                                    try {
                                        isPressed = true
                                        nativeLib.triggerNote(trackIndex, 60, 100)
                                        awaitRelease()
                                    } finally {
                                        isPressed = false
                                        nativeLib.releaseNote(trackIndex, 60)
                                    }
                                }
                            )
                        },
                    contentAlignment = Alignment.Center
                ) {
                    Text("♪ ♫", fontSize = 16.sp, color = Color.Cyan, fontWeight = FontWeight.Bold)
                }
                
                // Default Patch Button (LOAD)
                Button(
                    onClick = { 
                         nativeLib.restoreTrackPreset(trackIndex)
                         // Refresh State from Engine
                         val allParams = nativeLib.getAllTrackParameters(trackIndex)
                         val paramMap = if (allParams.isNotEmpty())
                             allParams.mapIndexed { idx, value -> idx to value }.toMap() else track.parameters
                         onStateChange(state.copy(tracks = state.tracks.mapIndexed { i, t ->
                             if (i == trackIndex) t.copy(parameters = paramMap, fm1KnobBindings = null, fm1StepKnobBindings = null) else t
                         }))
                    },
                    modifier = Modifier.size(width = buttonWidth, height = buttonHeight),
                    contentPadding = PaddingValues(0.dp),
                    colors = ButtonDefaults.buttonColors(containerColor = Color.DarkGray, contentColor = Color.White),
                    shape = buttonShape
                ) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Text("RESET", fontSize = 8.sp, fontWeight = FontWeight.Bold, lineHeight = 9.sp)
                        Text("DEFAULT", fontSize = 8.sp, fontWeight = FontWeight.Bold, lineHeight = 9.sp)
                    }
                }
                Spacer(modifier = Modifier.width(4.dp))
                RandomizeButton(trackIndex, state, onStateChange, nativeLib)
            }
            
            // Right: MIDI Learn Toggle & Preset/Seq Management
            Row(
                modifier = Modifier.weight(1.5f),
                horizontalArrangement = Arrangement.End,
                verticalAlignment = Alignment.CenterVertically
            ) {
                // Preset Management
                var showPresetLoad by remember { mutableStateOf(false) }
                var showPresetSave by remember { mutableStateOf(false) }
                
                if (showPresetSave) {
                     val defaultDir = File(File(PersistenceManager.getLoomFolder(), "Presets"), track.engineType.name).apply { if(!exists()) mkdirs() }
                     NativeFileDialog(
                        directory = defaultDir,
                        onDismiss = { showPresetSave = false },
                        state = state,
                        onFileSelected = { path ->
                            // Fix: Strip extensions to avoid double-appending (e.g. .wav.gbp)
                            var name = File(path).name
                            if (name.endsWith(".gbp")) name = name.removeSuffix(".gbp")
                            if (name.endsWith(".wav")) name = name.removeSuffix(".wav")
                            
                            PersistenceManager.saveTrackPreset(track, name)
                        },
                        isSave = true,
                        trackIndex = trackIndex,
                        extensions = listOf("gbp"),
                        title = "SAVE PRESET"
                     )
                }
                
                if (showPresetLoad) {
                     val defaultDir = File(File(PersistenceManager.getLoomFolder(), "Presets"), track.engineType.name).apply { if(!exists()) mkdirs() }
                     NativeFileDialog(
                        directory = defaultDir,
                        onDismiss = { showPresetLoad = false },
                        state = state,
                        onFileSelected = { path ->
                            val name = File(path).name.removeSuffix(".gbp")
                            val loadedState = PersistenceManager.loadTrackPreset(track.engineType, name)
                            if (loadedState != null) {
                                // 1. SYNC SOUND: Push all parameters to Native Engine immediately
                                loadedState.parameters.forEach { (id, value) ->
                                    nativeLib.setParameter(trackIndex, id, value)
                                }
                                
                                // 2. RELOAD SAMPLE: If preset includes a sample path, reload it
                                val samplePath = loadedState.lastSamplePath
                                if (!samplePath.isNullOrEmpty()) {
                                    val sampleFile = java.io.File(samplePath)
                                    if (sampleFile.exists()) {
                                        when (track.engineType) {
                                            EngineType.WAVETABLE -> nativeLib.loadWavetable(trackIndex, samplePath)
                                            else -> nativeLib.loadSample(trackIndex, samplePath)
                                        }
                                    } else {
                                        platformShowMessage("Sample not found: ${sampleFile.name}\nUse LOAD to browse for it.")
                                    }
                                }
                                
                                // 3. SYNC UI: Update Kotlin State
                                onStateChange(state.copy(tracks = state.tracks.mapIndexed { i, t -> 
                                    if (i == trackIndex) loadedState.copy(id = t.id) else t 
                                }))
                            }
                        },
                        isSave = false,
                        trackIndex = trackIndex,
                        extensions = listOf("gbp"),
                        title = "LOAD PRESET"
                     )
                }

                // Buttons
                Column(horizontalAlignment = Alignment.End) {
                    Row(horizontalArrangement = Arrangement.spacedBy(4.dp), verticalAlignment = Alignment.CenterVertically) {
                         Button(onClick = { showPresetLoad = true }, 
                            contentPadding = PaddingValues(0.dp), modifier = Modifier.size(52.dp, 42.dp), 
                            colors = ButtonDefaults.buttonColors(containerColor = Color.DarkGray, contentColor = Color.White),
                            shape = RoundedCornerShape(4.dp)) { Text("LOAD", fontSize = 9.sp, fontWeight = FontWeight.Bold) }
                            
                         Button(onClick = { showPresetSave = true }, 
                            contentPadding = PaddingValues(0.dp), modifier = Modifier.size(52.dp, 42.dp),
                            colors = ButtonDefaults.buttonColors(containerColor = Color.DarkGray, contentColor = Color.White),
                            shape = RoundedCornerShape(4.dp)) { Text("SAVE", fontSize = 9.sp, fontWeight = FontWeight.Bold) }

                         Button(onClick = { showPresetBrowser = true },
                            contentPadding = PaddingValues(0.dp), modifier = Modifier.size(68.dp, 42.dp),
                            colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF1B5E20), contentColor = Color(0xFFB9F6CA)),
                            shape = RoundedCornerShape(4.dp)) { Text("PRESETS", fontSize = 9.sp, fontWeight = FontWeight.Bold) }
                    }
                }

                Spacer(modifier = Modifier.width(8.dp))
                
                Button(
                    onClick = { 
                        val newActive = !state.midiLearnActive
                        onStateChange(state.copy(
                            midiLearnActive = newActive,
                            midiLearnStep = if (newActive) 1 else 0,
                            midiLearnSelectedStrip = null
                        )) 
                    },
                    modifier = Modifier.height(36.dp),
                    contentPadding = PaddingValues(horizontal = 12.dp),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = if (state.midiLearnActive) Color.Red else Color.DarkGray,
                        contentColor = Color.White
                    ),
                    shape = RoundedCornerShape(4.dp)
                ) {
                    Text(if (state.midiLearnActive) "MIDI LRN" else "MIDI LRN", fontSize = 10.sp, fontWeight = FontWeight.Bold)
                }
            }
        }
        
        Spacer(modifier = Modifier.height(8.dp))
        Spacer(modifier = Modifier.height(8.dp))
        
        when(track.engineType) {
            EngineType.WAVETABLE -> WavetableParameters(state, trackIndex, onStateChange, nativeLib, onRefresh = { /* Trigger refresh */ })
            EngineType.ANALOG_DRUM -> AnalogDrumParameters(state, trackIndex, onStateChange, nativeLib)
            EngineType.SUBTRACTIVE -> SubtractiveParameters(state, trackIndex, onStateChange, nativeLib)
            EngineType.FM -> FmParameters(state, trackIndex, onStateChange, nativeLib, onRefresh = { 
                // Refresh State from Engine
                val allParams = nativeLib.getAllTrackParameters(trackIndex)
                if (allParams.isNotEmpty()) {
                    val paramMap = allParams.mapIndexed { idx, value -> idx to value }.toMap()
                    viewModel.updateState { s ->
                        s.copy(tracks = s.tracks.mapIndexed { i, t -> 
                            if (i == trackIndex) t.copy(parameters = paramMap) else t 
                        })
                    }
                }
            })
            EngineType.FM_DRUM -> FmDrumParameters(viewModel, trackIndex, nativeLib)
            EngineType.SAMPLER -> SamplerParameters(state, trackIndex, onStateChange, nativeLib, onRecordingSourceChange)
            EngineType.GRANULAR -> GranularParameters(state, trackIndex, onStateChange, nativeLib, onRecordingSourceChange)
            EngineType.AUDIO_IN -> AudioInParameters(state, trackIndex, onStateChange, nativeLib)
            EngineType.SOUNDFONT -> SoundFontParameters(state, trackIndex, onStateChange, nativeLib, onRefresh = {})
            EngineType.MIDI -> MidiEngineParameters(state, trackIndex, onStateChange, nativeLib)
            else -> Text("No parameters for this engine type yet.", color = Color.LightGray)
        }
        
        Spacer(modifier = Modifier.height(24.dp))
        Divider(color = Color.LightGray.copy(alpha = 0.3f))
        Spacer(modifier = Modifier.height(24.dp))


        Spacer(modifier = Modifier.height(24.dp))
        Divider(color = Color.LightGray.copy(alpha = 0.3f))
        Spacer(modifier = Modifier.height(24.dp))

        // Global FX Sends (Dynamic based on Chain)
        val track = state.tracks[trackIndex]
        val isSliceLock = track.engineType == EngineType.SAMPLER && (track.parameters[342] ?: 0f) > 0.5f
        val selectedSlice = track.selectedFmDrumInstrument % 16
        
        val fxMapper: ((Int) -> Int)? = if (isSliceLock) {
            { slotIdx -> 1000 + selectedSlice * 20 + slotIdx }
        } else null

        GlobalActiveSends(state, trackIndex, onStateChange, nativeLib, paramIdMapper = fxMapper)
         
        Spacer(modifier = Modifier.height(24.dp))
        Divider(color = Color.LightGray.copy(alpha = 0.3f))
        Spacer(modifier = Modifier.height(24.dp))

        // Global FX Sends (Dynamic based on Chain)
        GlobalFxSends(state, trackIndex, onStateChange, nativeLib, paramIdMapper = fxMapper)
        
        if (state.midiLearnActive) {
            Spacer(modifier = Modifier.height(16.dp))
            MidiParameters(state, trackIndex, onStateChange, nativeLib)
        }

        if (showPresetBrowser) {
            PresetBrowserDialog(
                state = state,
                trackIndex = trackIndex,
                nativeLib = nativeLib,
                onStateChange = onStateChange,
                onDismiss = { showPresetBrowser = false }
            )
        }
    }
}

@Composable
fun ParameterGroup(title: String, modifier: Modifier = Modifier, titleSize: Int = 10, content: @Composable () -> Unit) {
    Column(modifier = modifier.padding(vertical = 4.dp)) {
        Text(title.uppercase(), style = MaterialTheme.typography.labelSmall, color = Color.White, fontSize = titleSize.sp, fontWeight = FontWeight.Bold)
        Spacer(modifier = Modifier.height(4.dp))
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .background(Color(0xFF222222), RoundedCornerShape(4.dp))
                .padding(4.dp)
        ) {
            content()
        }
    }
}

@Composable
fun TestTriggerButton(trackIndex: Int, track: TrackState, nativeLib: NativeLib, modifier: Modifier = Modifier) {
    var isPressed by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    
    // Calculate the target note: default 60, but if Sampler is in a Chop mode, 
    // trigger the specific slice based on the Select param (331)
    val targetNote = remember(track.engineType, track.parameters) {
        if (track.engineType == EngineType.SAMPLER) {
            val modeId = track.parameters[320] ?: 0f
            if (modeId >= 0.7f && modeId <= 0.95f) { // Chop, One Chop, Loop Chop
                val selectVal = track.parameters[331] ?: 0f
                val sliceIndex = (selectVal * 15f).toInt().coerceIn(0, 15)
                60 + sliceIndex
            } else {
                60
            }
        } else {
            60
        }
    }

    Box(
        modifier = modifier
            .size(width = 80.dp, height = 32.dp)
            .background(if (isPressed) Color.White.copy(alpha = 0.3f) else Color.White.copy(alpha = 0.1f), RoundedCornerShape(4.dp))
            .border(1.dp, if (isPressed) Color.White else Color.White.copy(alpha = 0.3f), RoundedCornerShape(4.dp))
            .pointerInput(targetNote) {
                detectTapGestures(
                    onPress = {
                        try {
                            isPressed = true
                            nativeLib.triggerNote(trackIndex, targetNote, 100)
                            awaitRelease()
                        } finally {
                            isPressed = false
                            nativeLib.releaseNote(trackIndex, targetNote)
                        }
                    }
                )
            },
        contentAlignment = Alignment.Center
    ) {
        Text("TEST NOTE", color = Color.White, fontSize = 10.sp, fontWeight = FontWeight.Bold)
    }
}

@Composable
fun RandomizeButton(trackIndex: Int, state: GrooveboxState, onStateChange: (GrooveboxState) -> Unit, nativeLib: NativeLib) {
    Button(
        onClick = { randomizeTrackParameters(trackIndex, state, onStateChange, nativeLib) },
        modifier = Modifier.size(width = 70.dp, height = 42.dp),
        contentPadding = PaddingValues(0.dp),
        colors = ButtonDefaults.buttonColors(containerColor = Color.DarkGray, contentColor = Color.White),
        shape = RoundedCornerShape(4.dp)
    ) {
        DiceIcon(modifier = Modifier.size(24.dp), color = Color.White)
    }
}

@Composable
fun DiceIcon(modifier: Modifier = Modifier, color: Color = Color.White) {
    Canvas(modifier = modifier) {
        // Fill background black for maximum visibility
        drawRoundRect(
            color = Color.Black,
            size = size,
            cornerRadius = CornerRadius(4f, 4f)
        )
        // Draw border
        drawRoundRect(
            color = color,
            size = size,
            cornerRadius = CornerRadius(4f, 4f),
            style = androidx.compose.ui.graphics.drawscope.Stroke(width = 2f)
        )
        // Draw dots
        drawCircle(color = color, radius = 2f, center = center)
        drawCircle(color = color, radius = 2f, center = Offset(size.width * 0.25f, size.height * 0.25f))
        drawCircle(color = color, radius = 2f, center = Offset(size.width * 0.75f, size.height * 0.75f))
    }
}

@Composable
fun DoubleDiceIcon(modifier: Modifier = Modifier, color: Color = Color.White) {
    Canvas(modifier = modifier) {
        // Make dice square using height as basis
        val diceSize = size.height * 0.9f  // Square dice
        val totalDiceWidth = diceSize * 2
        val gap = (size.width - totalDiceWidth) / 3  // Distribute remaining space
        val dotRadius = diceSize * 0.08f
        val cornerRadius = diceSize * 0.15f
        
        // Left dice (showing 3 - diagonal line)
        val leftOffset = Offset(gap, (size.height - diceSize) / 2)
        drawRoundRect(
            color = Color.Black,
            topLeft = leftOffset,
            size = Size(diceSize, diceSize),
            cornerRadius = CornerRadius(cornerRadius, cornerRadius)
        )
        drawRoundRect(
            color = color,
            topLeft = leftOffset,
            size = Size(diceSize, diceSize),
            cornerRadius = CornerRadius(cornerRadius, cornerRadius),
            style = androidx.compose.ui.graphics.drawscope.Stroke(width = 1.5f)
        )
        // Dots for 3: center + 2 corners
        drawCircle(color = color, radius = dotRadius, center = Offset(leftOffset.x + diceSize / 2, leftOffset.y + diceSize / 2))
        drawCircle(color = color, radius = dotRadius, center = Offset(leftOffset.x + diceSize * 0.25f, leftOffset.y + diceSize * 0.25f))
        drawCircle(color = color, radius = dotRadius, center = Offset(leftOffset.x + diceSize * 0.75f, leftOffset.y + diceSize * 0.75f))
        
        // Right dice (showing 5 - 4 corners + center)
        val rightOffset = Offset(gap * 2 + diceSize, (size.height - diceSize) / 2)
        drawRoundRect(
            color = Color.Black,
            topLeft = rightOffset,
            size = Size(diceSize, diceSize),
            cornerRadius = CornerRadius(cornerRadius, cornerRadius)
        )
        drawRoundRect(
            color = color,
            topLeft = rightOffset,
            size = Size(diceSize, diceSize),
            cornerRadius = CornerRadius(cornerRadius, cornerRadius),
            style = androidx.compose.ui.graphics.drawscope.Stroke(width = 1.5f)
        )
        // Dots for 5: center + 4 corners
        drawCircle(color = color, radius = dotRadius, center = Offset(rightOffset.x + diceSize / 2, rightOffset.y + diceSize / 2))
        drawCircle(color = color, radius = dotRadius, center = Offset(rightOffset.x + diceSize * 0.25f, rightOffset.y + diceSize * 0.25f))
        drawCircle(color = color, radius = dotRadius, center = Offset(rightOffset.x + diceSize * 0.75f, rightOffset.y + diceSize * 0.25f))
        drawCircle(color = color, radius = dotRadius, center = Offset(rightOffset.x + diceSize * 0.25f, rightOffset.y + diceSize * 0.75f))
        drawCircle(color = color, radius = dotRadius, center = Offset(rightOffset.x + diceSize * 0.75f, rightOffset.y + diceSize * 0.75f))
    }
}

fun randomizeTrackParameters(trackIndex: Int, state: GrooveboxState, onStateChange: (GrooveboxState) -> Unit, nativeLib: NativeLib) {
    val track = state.tracks[trackIndex]
    val newParams = track.parameters.toMutableMap()
    
    val idsToRandomize = when(track.engineType) {
        EngineType.SUBTRACTIVE -> (100..120).toList() + (150..191).toList()
        EngineType.FM -> (0..241).toList()
        EngineType.GRANULAR -> (400..429).toList()
        EngineType.WAVETABLE -> (450..476).toList()
        EngineType.SAMPLER -> (300..304).toList() + (310..314).toList() + listOf(320, 330, 331, 355)
        EngineType.FM_DRUM -> (0..55).map { 200 + (it/7)*10 + (it%7) } // 8 Drums * 7 Params
        EngineType.ANALOG_DRUM -> (0..47).map { 600 + (it/6)*10 + (it%6) }
        EngineType.SOUNDFONT -> listOf(1, 2, 100, 103, 112, 113, 150, 151, 152, 355)
        EngineType.AUDIO_IN -> (100..123).toList()
        else -> listOf()
    }
    
    idsToRandomize.forEach { id ->
        val newVal = if (track.engineType == EngineType.FM && (id == 153 || id == 155)) {
            // Special handling handled below the loop for better logic
            0f 
        } else {
            Math.random().toFloat()
        }
        newParams[id] = newVal
        nativeLib.setParameter(trackIndex, id, newVal)
    }

    // Special Handling for FM Masks (v1.11.3)
    var updatedActiveMask = track.fmActiveMask
    var updatedCarrierMask = track.fmCarrierMask

    if (track.engineType == EngineType.FM) {
        updatedCarrierMask = 0
        updatedActiveMask = 0
        for (op in 0 until 6) {
            val r = Math.random()
            if (r < 0.33) {
                // OFF: Do nothing
            } else if (r < 0.66) {
                // MOD: Active but not Carrier
                updatedActiveMask = updatedActiveMask or (1 shl op)
            } else {
                // CAR: Active and Carrier
                updatedActiveMask = updatedActiveMask or (1 shl op)
                updatedCarrierMask = updatedCarrierMask or (1 shl op)
            }
        }
        newParams[153] = updatedCarrierMask.toFloat()
        newParams[155] = updatedActiveMask.toFloat()
        nativeLib.setParameter(trackIndex, 153, updatedCarrierMask.toFloat())
        nativeLib.setParameter(trackIndex, 155, updatedActiveMask.toFloat())
    }
    
    onStateChange(state.copy(tracks = state.tracks.mapIndexed { i, t -> 
        if (i == trackIndex) t.copy(
            parameters = newParams,
            fmActiveMask = updatedActiveMask,
            fmCarrierMask = updatedCarrierMask
        ) else t 
    }))
}

@Composable
fun InputSourceSelector(state: GrooveboxState, onStateChange: (GrooveboxState) -> Unit, nativeLib: NativeLib) {
    Column(modifier = Modifier.padding(vertical = 4.dp).clickable {
        val nextSource = (state.recordingSource + 1) % 3
        nativeLib.setRecordingSource(nextSource)
        onStateChange(state.copy(recordingSource = nextSource))
    }) {
        Text("INPUT SELECTOR", color = Color.Gray, fontSize = 10.sp)
        Text(
            when(state.recordingSource) {
                0 -> "INTERNAL MICROPHONE"
                1 -> "RESAMPLING (MIX)"
                2 -> "SYSTEM AUDIO (LOOPBACK)"
                else -> "INTERNAL MICROPHONE"
            }, 
            style = MaterialTheme.typography.bodySmall, 
            color = when(state.recordingSource) {
                0 -> Color.White
                1 -> Color.Cyan
                2 -> Color(0xFFFFA500)
                else -> Color.White
            },
            fontWeight = FontWeight.Bold
        )
    }
}


@Composable
fun RecordingStrip(
    trackIndex: Int, 
    isRecording: Boolean,
    isResampling: Boolean, 
    waveform: FloatArray?,
    track: TrackState, 
    onStartRecording: () -> Unit,
    onStopRecording: () -> Unit,
    state: GrooveboxState,
    onStateChange: (GrooveboxState) -> Unit,
    onWaveformRefresh: () -> Unit = {},
    trimStart: Float? = null,
    trimEnd: Float? = null,
    slices: Int? = null,
    granularPlayheads: FloatArray? = null,
    grainSize: Float? = null,
    nativeLib: NativeLib? = null,
    onRecordingSourceChange: (Int) -> Unit = {},
    selectedSlice: Int? = null,
    extraControls: (@Composable () -> Unit)? = null 
) {
    val engineColor = getEngineColor(track.engineType)
    val scope = rememberCoroutineScope()
    var previewJob by remember { mutableStateOf<Job?>(null) }
    var showSaveDialog by remember { mutableStateOf(false) }
    var showLoadDialog by remember { mutableStateOf(false) }
    
    // For Sampler, fetch real slice points with local state for smooth dragging
    var slicePoints by remember { mutableStateOf<FloatArray?>(null) }
    
    // Fetch initial or refreshed points
    LaunchedEffect(trackIndex, track.parameters[340], isRecording) {
        if (track.engineType == EngineType.SAMPLER && nativeLib != null) {
            slicePoints = nativeLib.getSlicePoints(trackIndex)
        } else {
            slicePoints = null
        }
    }
    
    // Drag State
    var draggingSliceIndex by remember { mutableIntStateOf(-1) }

    // Persistent and Instant Trim lines (No Animation for instant feedback)
    val effectiveTrimStart = trimStart ?: 0f
    val effectiveTrimEnd = trimEnd ?: 1f
    // Using raw values directly for instant response
    val animTrimStart = effectiveTrimStart
    val animTrimEnd = effectiveTrimEnd

    // Scrub Mode Logic
    val isScrubMode = track.engineType == EngineType.SAMPLER && (track.parameters[320] ?: 0f) >= 0.95f
    val scrubPosition = track.parameters[360] ?: 0f

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(Color.Black.copy(alpha = 0.4f), RoundedCornerShape(12.dp))
            .border(1.dp, Color.White.copy(alpha = 0.1f), RoundedCornerShape(12.dp))
            .padding(12.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(modifier = Modifier.clickable { 
                val nextSource = (state.recordingSource + 1) % 3
                nativeLib?.setRecordingSource(nextSource)
                onStateChange(state.copy(recordingSource = nextSource))
                onRecordingSourceChange(nextSource)
            }) {
                Text("RECORDING SOURCE", style = MaterialTheme.typography.labelSmall, color = Color.LightGray)
                Text(
                    when(state.recordingSource) {
                        0 -> "INTERNAL MICROPHONE"
                        1 -> "RESAMPLING (MIX)"
                        2 -> "SYSTEM AUDIO (LOOPBACK)"
                        else -> "INTERNAL MICROPHONE"
                    }, 
                    style = MaterialTheme.typography.bodySmall, 
                    color = when(state.recordingSource) {
                        0 -> Color.White
                        1 -> Color.Cyan
                        2 -> Color(0xFFFFA500) // Orange for System Audio
                        else -> Color.White
                    },
                    fontWeight = FontWeight.Bold
                )
            }
            
            // Central Control Cluster (Save/Load/Trim)
            Row(modifier = Modifier.weight(1f), horizontalArrangement = Arrangement.Center, verticalAlignment = Alignment.CenterVertically) {
                // val context = LocalContext.current // Removed
                // Controls removed from here to top scope

                if (showSaveDialog) {
                    val folderName = when(track.engineType) {
                        EngineType.GRANULAR -> "granular"
                        EngineType.SAMPLER -> "samples"
                        EngineType.AUDIO_IN -> "recordings"
                        else -> "samples"
                    }
                    val defaultDir = File(PersistenceManager.getLoomFolder(), folderName).apply { if (!exists()) mkdirs() }
                    
                    NativeFileDialog(
                        directory = defaultDir, 
                        onDismiss = { showSaveDialog = false }, 
                        state = state, 
                        onFileSelected = { path ->
                             nativeLib?.saveSample(trackIndex, path)
                             onStateChange(state.copy(tracks = state.tracks.mapIndexed { idx, t ->
                                 if (idx == trackIndex) t.copy(lastSamplePath = path) else t
                             }))
                        }, 
                        isSave = true,
                        trackIndex = trackIndex,
                        title = "SAVE SAMPLE"
                    )
                }
                if (showLoadDialog) {
                    val defaultDir = when(track.engineType) {
                        EngineType.GRANULAR -> File(PersistenceManager.getLoomFolder(), "granular").apply { if (!exists()) mkdirs() }
                        EngineType.SAMPLER -> File(PersistenceManager.getLoomFolder(), "samples").apply { if (!exists()) mkdirs() }
                        else -> PersistenceManager.getLoomFolder()
                    }

                    NativeFileDialog(
                        directory = defaultDir, 
                        onDismiss = { showLoadDialog = false }, 
                        state = state, 
                        onFileSelected = { path ->
                             nativeLib?.loadSample(trackIndex, path)
                             onStateChange(state.copy(tracks = state.tracks.mapIndexed { idx, t ->
                                 if (idx == trackIndex) t.copy(lastSamplePath = path) else t
                             }))
                             onWaveformRefresh()
                             // Refresh slices after load
                             if (nativeLib != null) {
                                 slicePoints = nativeLib.getSlicePoints(trackIndex)
                             }
                        }, 
                        isSave = false,
                        trackIndex = trackIndex,
                        extensions = listOf("wav"),
                        title = "LOAD SAMPLE"
                    )
                }

                Button(onClick = { showSaveDialog = true }, modifier = Modifier.size(60.dp, 30.dp), 
                    colors = ButtonDefaults.buttonColors(containerColor = Color.DarkGray, contentColor = Color.White),
                    contentPadding = PaddingValues(0.dp)) { Text("SAVE", fontSize = 10.sp) }
                Spacer(modifier = Modifier.width(8.dp))
                Button(onClick = { showLoadDialog = true }, modifier = Modifier.size(60.dp, 30.dp), 
                    colors = ButtonDefaults.buttonColors(containerColor = Color.DarkGray, contentColor = Color.White),
                    contentPadding = PaddingValues(0.dp)) { Text("LOAD", fontSize = 10.sp) }
                Spacer(modifier = Modifier.width(8.dp))
                Button(onClick = { 
                    nativeLib?.trimSample(trackIndex)
                    onStateChange(state.copy(tracks = state.tracks.mapIndexed { idx, t -> 
                        if (idx == trackIndex) t.copy(parameters = t.parameters + (330 to 0.0f) + (331 to 1.0f)) else t 
                    }))
                    onWaveformRefresh()
                }, modifier = Modifier.size(60.dp, 30.dp),  
                    colors = ButtonDefaults.buttonColors(containerColor = Color.DarkGray, contentColor = Color.White),
                    contentPadding = PaddingValues(0.dp)) { Text("TRIM", fontSize = 10.sp) }
                
                // BPM CONTROLS (Moved here per user request)
                // Layout: [Sugg BPM | SET] then [REPEATS Knob]
                // Located right of TRIM and left of LOCK
                Spacer(modifier = Modifier.width(16.dp))
                
                val sampleLength = remember(trackIndex, waveform) { nativeLib?.getSampleLength(trackIndex) ?: 0L }
                // BPM CONTROLS
                Spacer(modifier = Modifier.width(16.dp))
                
                // sampleLength already defined above
                
                // Observe all factors
                val seqSteps = state.patternLength
                
                val repeatsParam = track.parameters[365] ?: 0.0f
                val repeats = (repeatsParam * 15f + 1f).toInt() // 1 to 16 repeats
                
                val tStart = track.parameters[330] ?: 0.0f
                val tEnd = track.parameters[331] ?: 1.0f
                
                // SPEED (302): 0.5 = 1x. Range 0.0 -> 2.0? 
                // In SamplerEngine: mSpeed = value * 2.0f;
                // So 0.5 * 2.0 = 1.0x. 
                val speedParam = track.parameters[302] ?: 0.5f 
                val speedFactor = (speedParam * 2.0f).coerceAtLeast(0.01f)
                
                // STRETCH (301): mStretch = value * 4.0f;
                // Default 0.25 = 1.0x? No, 1.0 is neutral stretch usually.
                // SamplerEngine: mStretch = value * 4.0f.  
                // If user hasn't touched it, what's default? 1.0 (value 0.25)?
                // Let's assume neutral is 1.0. 
                val stretchParam = track.parameters[301] ?: 0.25f
                val stretchFactor = (stretchParam * 4.0f).coerceAtLeast(0.01f)

                val suggestedBpm = remember(sampleLength, seqSteps, repeats, tStart, tEnd, speedParam, stretchParam, state.patternLength, track.id, track.parameters[342], track.parameters[341], track.parameters[320]) {
                    if (sampleLength > 0L) {
                         val isSliceLock = (track.parameters[342] ?: 0.0f) > 0.5f
                         val samplerMode = track.parameters[320] ?: 0f
                         val isChops = samplerMode >= 0.49f && samplerMode < 0.95f
                         
                         var effectiveSamples = if (isSliceLock && isChops) {
                             val slicePoints = nativeLib?.getSlicePoints(trackIndex) ?: floatArrayOf()
                             if (slicePoints.isNotEmpty()) {
                                 val selParam = track.parameters[341] ?: 0.0f
                                 val selIdx = (selParam * (slicePoints.size - 0.001f)).toInt().coerceIn(0, slicePoints.size - 1)
                                 val sStart = slicePoints[selIdx]
                                 val sEnd = if (selIdx < slicePoints.size - 1) slicePoints[selIdx + 1] else 1.0f
                                 (sEnd - sStart).coerceAtLeast(0.001f) * sampleLength
                             } else {
                                 (tEnd - tStart).coerceAtLeast(0.01f) * sampleLength
                             }
                         } else {
                              (tEnd - tStart).coerceAtLeast(0.01f) * sampleLength
                         }

                         // Actual Duration = (Samples / 48000) / Speed * Stretch
                         val actualDur = ((effectiveSamples / 48000f) / speedFactor) * stretchFactor
                         
                         if (actualDur > 0.001f) {
                             // BPM = (15 * Steps) / (Duration * Repeats)
                             val bpm = (seqSteps * 15f) / (actualDur * repeats)
                             bpm.toInt().coerceIn(20, 999)
                         } else null
                    } else null
                }

                if (suggestedBpm != null) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Text("Sugg.", style = MaterialTheme.typography.labelSmall, color = Color.LightGray, fontSize = 9.sp)
                        Text("${suggestedBpm}bpm", color = engineColor, fontWeight = FontWeight.Bold, fontSize = 11.sp)
                         Button(
                            onClick = {
                                nativeLib?.setTempo(suggestedBpm.toFloat())
                                onStateChange(state.copy(tempo = suggestedBpm.toFloat()))
                            },
                            modifier = Modifier.height(20.dp),
                            contentPadding = PaddingValues(horizontal = 4.dp, vertical = 0.dp),
                            colors = ButtonDefaults.buttonColors(containerColor = engineColor.copy(alpha = 0.3f)),
                            border = BorderStroke(1.dp, engineColor),
                            shape = RoundedCornerShape(4.dp)
                        ) {
                            Text("SET", fontSize = 9.sp, color = engineColor)
                        }
                    }
                }
                
                Spacer(modifier = Modifier.width(8.dp))
                Knob("REPEATS", 0.0f, 365, state, onStateChange, nativeLib ?: return, knobSize = 32.dp, valueFormatter = { v -> "${(v * 15 + 1).toInt()}" })
                Spacer(modifier = Modifier.width(16.dp))
                
                // LOCK
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Text("LOCK", style = MaterialTheme.typography.labelSmall, color = Color.LightGray, fontSize = 8.sp)
                    androidx.compose.material3.Switch(
                        checked = state.isRecordingLocked,
                        onCheckedChange = { locked ->
                            onStateChange(state.copy(isRecordingLocked = locked))
                            nativeLib?.setRecordingLocked(locked)
                        },
                        modifier = Modifier.graphicsLayer { scaleX = 0.6f; scaleY = 0.6f }.height(24.dp)
                    )
                }
            } // End of Central Control Loop
            
            // Record Button (Stable Position)
            Row(verticalAlignment = Alignment.CenterVertically) {
                Spacer(modifier = Modifier.width(12.dp))
                
                // Only animate while recording: an always-running infinite
                // transition invalidates the screen every frame forever, which
                // is a large idle cost on low-power devices.
                val animScale = if (isRecording) {
                    val infiniteTransition = rememberInfiniteTransition()
                    val scale by infiniteTransition.animateFloat(
                        initialValue = 1f, targetValue = 1.15f,
                        animationSpec = infiniteRepeatable(tween(400), RepeatMode.Reverse)
                    )
                    scale
                } else 1f

                val latestIsRecording by rememberUpdatedState(isRecording)
                val latestOnStart by rememberUpdatedState(onStartRecording)
                val latestOnStop by rememberUpdatedState(onStopRecording)

                Box(
                    modifier = Modifier
                        .size(52.dp)
                        .graphicsLayer { if (isRecording) { scaleX = animScale; scaleY = animScale } }
                        .background(if (isRecording) Color.Red else Color.Red.copy(alpha = 0.3f), CircleShape)
                        .border(2.dp, Color.Red, CircleShape)
                        .pointerInput(trackIndex, state.isRecordingLocked) {
                            while (true) {
                                awaitPointerEventScope {
                                    val down = awaitFirstDown(requireUnconsumed = false)
                                    
                                    if (state.isRecordingLocked) {
                                        // Toggle Mode
                                        if (latestIsRecording) {
                                            onStateChange(state.copy(isRecordingLocked = false))
                                            nativeLib?.setRecordingLocked(false)
                                            latestOnStop()
                                        } else {
                                            latestOnStart()
                                        }
                                        down.consume()
                                    } else {
                                        // Hold Mode
                                        latestOnStart()
                                        down.consume()
                                        
                                        // Wait for release
                                        waitForUpOrCancellation()
                                        latestOnStop()
                                    }
                                }
                                // Small "cool-off" to prevent immediate re-trigger on some touchscreens
                                // Allowed here because we are in the pointerInput CoroutineScope, not the restricted awaitPointerEventScope
                                delay(150)
                            }
                        },
                    contentAlignment = Alignment.Center
                ) {
                   if (isRecording) {
                       Box(Modifier.size(20.dp).background(Color.White, RoundedCornerShape(4.dp)))
                   } else {
                       Box(Modifier.size(20.dp).background(Color.Red, CircleShape))
                   }
                }
            }
        }
        
        Spacer(modifier = Modifier.height(12.dp))
        
        val scrubMidiLearnable = isScrubMode && (state.midiLearnActive && state.midiLearnStep == 2)
        val scrubLearnActive = isScrubMode && (state.lfoLearnActive || state.macroLearnActive)

        // Local state for smooth scrubbing without expensive global re-compositions
        var localScrubPos by remember { mutableStateOf<Float?>(null) }
        var localScrubGate by remember { mutableStateOf(false) }

        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(80.dp)
                .background(Color.Black, RoundedCornerShape(8.dp))
                .border(
                    if (scrubMidiLearnable || scrubLearnActive) 2.dp else 1.dp,
                    if (scrubMidiLearnable) Color.Yellow
                    else if (scrubLearnActive) Color.Cyan
                    else Color.LightGray.copy(alpha = 0.2f),
                    RoundedCornerShape(8.dp)
                )
                .padding(4.dp)
                .pointerInput(trackIndex, isScrubMode) {
                    awaitEachGesture {
                        val down = awaitFirstDown()
                        val width = size.width.toFloat()
                        val clickPos = down.position.x / width
                        
                        // Use updated values from outside the block to avoid restarts
                        val animTrimStart = trimStart ?: 0f
                        val animTrimEnd = trimEnd ?: 1f
                        val currentSlicePoints = slicePoints
                        val currentSlices = slices ?: 0

                        if (isScrubMode && state.midiLearnActive && state.midiLearnStep == 2) {
                            // MIDI LEARN: Assign scrub position (param 360) to selected strip/knob
                            val stripIdx = state.midiLearnSelectedStrip ?: return@awaitEachGesture
                            val newState = if (stripIdx < 4) {
                                val newRoutings = state.stripRoutings.map {
                                    if (it.stripIndex == stripIdx) it.copy(targetType = 1, targetId = 360, parameterName = "Scrub Pos")
                                    else it
                                }
                                state.copy(stripRoutings = newRoutings, midiLearnActive = false, midiLearnStep = 0, midiLearnSelectedStrip = null, selectedTab = 0)
                            } else {
                                val knobIdx = stripIdx - 4
                                val newRoutings = state.knobRoutings.mapIndexed { idx, item ->
                                    if (idx == knobIdx) item.copy(targetType = 1, targetId = 360, parameterName = "Scrub Pos")
                                else item
                                }
                                state.copy(knobRoutings = newRoutings, midiLearnActive = false, midiLearnStep = 0, midiLearnSelectedStrip = null, selectedTab = 0)
                            }
                            onStateChange(newState)
                            return@awaitEachGesture
                        } else if (isScrubMode && state.lfoLearnActive) {
                            // LFO LEARN: Assign scrub position as LFO target
                            val lfoIdx = state.lfoLearnLfoIndex
                            if (lfoIdx != -1) {
                                nativeLib?.let { nl -> nl.setRouting(trackIndex, -1, 2 + lfoIdx, 5, 1.0f, 360) }
                                val newLfos = state.lfos.toMutableList()
                                newLfos[lfoIdx] = newLfos[lfoIdx].copy(targetType = 1, targetId = 360, targetLabel = "Scrub Pos")
                                onStateChange(state.copy(lfos = newLfos, lfoLearnActive = false))
                            }
                            return@awaitEachGesture
                        } else if (isScrubMode && state.macroLearnActive) {
                            // MACRO LEARN: Assign scrub position as macro target
                            val macroIdx = state.macroLearnMacroIndex
                            val tIdx = state.macroLearnTargetIndex
                            if (macroIdx != -1 && tIdx != -1) {
                                nativeLib?.let { nl -> nl.setRouting(trackIndex, -1, 10 + macroIdx, 5, 1.0f, 360) }
                                val newMacros = state.macros.toMutableList()
                                val currentTargets = newMacros[macroIdx].targets.toMutableList()
                                if (tIdx < currentTargets.size) {
                                    currentTargets[tIdx] = currentTargets[tIdx].copy(targetId = 360, targetLabel = "Scrub Pos", enabled = true)
                                    newMacros[macroIdx] = newMacros[macroIdx].copy(targets = currentTargets)
                                    onStateChange(state.copy(macros = newMacros, macroLearnActive = false))
                                }
                            }
                            return@awaitEachGesture
                        }

                        // 1. Check Slices first (Trim Markers are now Knob-only per user request)
                        var draggingIdx = -1
                        val touchThreshold = 20.dp.toPx() 
                        
                        if (!isScrubMode) {
                            // Find closest slice point if not scrubbing - using a pixel-based threshold for consistency
                            val threshold = 15.dp.toPx() / width 
                            slicePoints?.let { points ->
                                var minDist = threshold 
                                points.forEachIndexed { i, p ->
                                    val dist = kotlin.math.abs(p - clickPos)
                                    if (dist < minDist) {
                                        minDist = dist
                                        draggingIdx = i
                                    }
                                }
                            }
                        }

                        if (draggingIdx != -1) {
                            // Slice Dragging Logic
                            draggingSliceIndex = draggingIdx
                            down.consume()
                            
                            var dragChange = down
                            do {
                                val event = awaitPointerEvent()
                                val change = event.changes.firstOrNull { it.id == dragChange.id }
                                if (change != null && change.pressed) {
                                    if (change.position != dragChange.position) {
                                        val newPos = (change.position.x / width).coerceIn(0f, 1f)
                                        
                                        // Use the latest slicePoints to avoid stale data during rapid updates
                                        slicePoints?.let { pts ->
                                            val newPts = pts.copyOf()
                                            if (draggingIdx < newPts.size) {
                                                newPts[draggingIdx] = newPos
                                                slicePoints = newPts
                                                // Update engine in real-time
                                                nativeLib?.setSlicePosition(trackIndex, draggingIdx, newPos)
                                            }
                                        }
                                        
                                        change.consume()
                                    }
                                    dragChange = change
                                } else {
                                    break
                                }
                            } while (dragChange.pressed)
                            
                            onWaveformRefresh()
                            draggingSliceIndex = -1
                        } else if (isScrubMode) {
                            // 2. SCRUB: Optimized with local state to avoid UI lag
                            down.consume()
                            val initialPos = clickPos.coerceIn(0f, 1f)
                            localScrubPos = initialPos
                            localScrubGate = true
                            
                            nativeLib?.setParameter(trackIndex, 360, initialPos)
                            nativeLib?.setParameter(trackIndex, 361, 1.0f)
                            
                            var dragChange = down
                            do {
                                val event = awaitPointerEvent()
                                val change = event.changes.firstOrNull { it.id == dragChange.id }
                                if (change != null && change.pressed) {
                                    if (change.position != dragChange.position) {
                                        val newPos = (change.position.x / width).coerceIn(0f, 1f)
                                        localScrubPos = newPos
                                        nativeLib?.setParameter(trackIndex, 360, newPos)
                                        change.consume()
                                    }
                                    dragChange = change
                                } else {
                                    break 
                                }
                            } while (dragChange.pressed)
                            
                            // SCRUB: Release = Gate OFF + Final State Sync
                            localScrubGate = false
                            val finalPos = localScrubPos ?: initialPos
                            nativeLib?.setParameter(trackIndex, 361, 0.0f)
                            
                            onStateChange(state.copy(tracks = state.tracks.mapIndexed { idx, t -> 
                                if (idx == trackIndex) t.copy(parameters = t.parameters + (360 to finalPos) + (361 to 0.0f)) else t 
                            }))
                            localScrubPos = null
                        } else {
                            // 3. Fallback for Click-to-Note or slice selection
                            // (Old Logic if needed)
                        }
                    }
                }
        ) {
            androidx.compose.foundation.Canvas(modifier = Modifier.fillMaxSize()) {
                val wave = waveform
                if (wave != null && wave.isNotEmpty()) {
                    val step = size.width / wave.size
                    wave.forEachIndexed { i, amp ->
                        val x = i * step
                        val h = Math.abs(amp) * size.height * 0.95f
                        drawLine(engineColor, Offset(x, size.height/2 - h/2), Offset(x, size.height/2 + h/2), strokeWidth = 1.5.dp.toPx())
                    }
                } else if (isRecording) {
                    drawLine(Color.Red.copy(alpha = 0.3f), Offset(0f, size.height/2), Offset(size.width, size.height/2), strokeWidth = 1.dp.toPx())
                }
                
                val currentPoints = slicePoints

                // Granular/Scrub Playheads
                if (isScrubMode) {
                    val physPos = if (localScrubPos != null) localScrubPos!! 
                                 else if (granularPlayheads != null && granularPlayheads.size >= 2 && granularPlayheads[0] >= 0) granularPlayheads[0] 
                                 else scrubPosition
                    val x = physPos * size.width
                    val fuschia = Color(0xFFFF00FF)
                    drawLine(fuschia, Offset(x, 0f), Offset(x, size.height), strokeWidth = 4.dp.toPx())
                    drawCircle(fuschia, radius = 15.dp.toPx(), center = Offset(x, size.height - 15.dp.toPx()))
                } 
                
                // SLICE LINES (Restored)
                if (track.engineType == EngineType.SAMPLER && currentPoints != null) {
                    currentPoints.forEachIndexed { index, point ->
                        val x = point * size.width
                        drawLine(Color.Magenta, Offset(x, 0f), Offset(x, size.height), strokeWidth = 1.dp.toPx())
                    }
                } else if (slices != null && slices > 1) {
                    val sliceStep = size.width / slices
                    for (i in 1 until slices) {
                         val x = i * sliceStep
                         drawLine(Color.Magenta, Offset(x, 0f), Offset(x, size.height), strokeWidth = 1.dp.toPx())
                    }
                }

                // Granular Playheads (Only if NOT in Scrub Mode)
                if (!isScrubMode && granularPlayheads != null && granularPlayheads.isNotEmpty()) {
                    val gSizeVal = grainSize ?: 0.1f 
                    val widthPx = gSizeVal * size.width 
                    
                    for (i in 0 until granularPlayheads.size step 2) {
                        val pos = granularPlayheads[i]
                        val vol = granularPlayheads[i + 1]
                        if (pos >= 0f && vol > 0.01f) {
                             val x = pos * size.width
                             val alpha = (vol * 0.9f).coerceIn(0.2f, 1f)
                             
                             drawLine(Color.Yellow, Offset(x, 0f), Offset(x, size.height), strokeWidth = 2.dp.toPx())
                             
                             if (widthPx > 2f) {
                                 drawRect(
                                     Color.Yellow.copy(alpha = (alpha * 0.3f).coerceIn(0.0f, 1.0f)),
                                     topLeft = Offset(x, 0f),
                                     size = Size(widthPx, size.height)
                                 )
                             }
                        }
                    }
                }

                // Trim Lines (Always Visible)
                if (track.engineType == EngineType.SAMPLER) {
                    // DRAW SELECTED SLICE HIGHLIGHT
                    selectedSlice?.let { selIdx ->
                        val startPos: Float
                        val endPos: Float
                        val pts = currentPoints
                        if (pts != null && pts.isNotEmpty()) {
                             startPos = if (selIdx == 0) animTrimStart else pts.getOrNull(selIdx - 1) ?: animTrimStart
                             endPos = pts.getOrNull(selIdx) ?: animTrimEnd
                        } else {
                            val sCount = slices ?: 1
                            val sliceStep = (animTrimEnd - animTrimStart) / sCount
                            startPos = animTrimStart + selIdx * sliceStep
                            endPos = animTrimStart + (selIdx + 1) * sliceStep
                        }
                        
                        drawRect(
                            color = engineColor.copy(alpha = 0.25f),
                            topLeft = Offset(startPos * size.width, 0f),
                            size = Size((endPos - startPos) * size.width, size.height)
                        )
                    }

                    val xStart = animTrimStart * size.width
                    drawLine(Color.Green, Offset(xStart, 0f), Offset(xStart, size.height), strokeWidth = 1.5.dp.toPx())
                    
                    val xEnd = animTrimEnd * size.width
                    drawLine(Color.Red, Offset(xEnd, 0f), Offset(xEnd, size.height), strokeWidth = 1.5.dp.toPx())
                }
            }
            
            if (isRecording) {
                Text("REC", modifier = Modifier.padding(4.dp).align(Alignment.TopEnd), color = Color.Red, style = MaterialTheme.typography.labelSmall)
            }
            }
        }
    }

@Suppress("UNCHECKED_CAST")
fun applyImportedFmVoice(voiceMap: Map<String, Any>, trackIndex: Int, nativeLib: NativeLib) {
    val algorithm = voiceMap["algorithm"] as? Int ?: 0
    val feedback = voiceMap["feedback"] as? Int ?: 0
    val carrierMask = voiceMap["carrierMask"] as? Int ?: 1
    val opLevels = voiceMap["opLevels"] as? List<Float> ?: List(6) { 0.5f }
    val opRatios = voiceMap["opRatios"] as? List<Float> ?: List(6) { 1f / 16f }
    val opAttack = voiceMap["opAttack"] as? List<Float> ?: List(6) { 0.01f }
    val opDecay = voiceMap["opDecay"] as? List<Float> ?: List(6) { 0.5f }
    val opSustain = voiceMap["opSustain"] as? List<Float> ?: List(6) { 0.8f }
    val opRelease = voiceMap["opRelease"] as? List<Float> ?: List(6) { 0.4f }
    
    nativeLib.setParameter(trackIndex, 150, algorithm.toFloat() / 31f)
    nativeLib.setParameter(trackIndex, 154, feedback.toFloat() / 7f)
    nativeLib.setParameter(trackIndex, 153, carrierMask.toFloat())
    for (op in 0 until 6) {
        val baseId = 160 + (op * 6)
        nativeLib.setParameter(trackIndex, baseId + 0, opLevels[op])
        nativeLib.setParameter(trackIndex, baseId + 1, opAttack[op])
        nativeLib.setParameter(trackIndex, baseId + 2, opDecay[op])
        nativeLib.setParameter(trackIndex, baseId + 3, opSustain[op])
        nativeLib.setParameter(trackIndex, baseId + 4, opRelease[op])
        nativeLib.setParameter(trackIndex, baseId + 5, opRatios[op])
    }
}

data class FmPreset(val id: Int, val name: String, val category: String)

val fmPresets = listOf(
    FmPreset(0, "Brass", "Brass"),
    FmPreset(1, "Strings", "Strings"),
    FmPreset(2, "Orchestra", "Ens"),
    FmPreset(3, "Piano", "Keys"),
    FmPreset(4, "E. Piano", "Keys"),
    FmPreset(5, "Guitar", "Pluck"),
    FmPreset(6, "Bass", "Bass"),
    FmPreset(7, "Organ", "Keys"),
    FmPreset(8, "Pipes", "Wind"),
    FmPreset(9, "Harpsichord", "Keys"),
    FmPreset(10, "Clav", "Keys"),
    FmPreset(11, "Vibe", "Mallet"),
    FmPreset(12, "Marimba", "Mallet"),
    FmPreset(13, "Koto", "Pluck"),
    FmPreset(14, "Flute", "Wind"),
    FmPreset(15, "Tubular Bells", "Bell"),
    FmPreset(16, "Voice", "Vox"),
    FmPreset(17, "Choir", "Vox"),
    FmPreset(18, "Calliope", "Wind"),
    FmPreset(19, "Oboe", "Wind"),
    FmPreset(20, "Bassoon", "Wind"),
    FmPreset(21, "Xylophone", "Mallet"),
    FmPreset(22, "Church Bells", "Bell"),
    FmPreset(23, "Synth Lead", "Lead"),
    FmPreset(24, "Recorders", "Wind"),
    FmPreset(25, "Shimmer", "Pad"),
    FmPreset(26, "Filter Sweep", "FX"),
    FmPreset(27, "Funky Rise", "FX"),
    FmPreset(28, "Ref's Whistle", "FX"),
    FmPreset(29, "Steel Drum", "Mallet"),
    FmPreset(30, "Harmonica", "Wind"),
    FmPreset(31, "Accordion", "Keys"),
    FmPreset(32, "Sitar", "Pluck"),
    FmPreset(33, "Lute", "Pluck"),
    FmPreset(34, "Banjo", "Pluck"),
    FmPreset(35, "Harp 1", "Pluck"),
    FmPreset(36, "Harp 2", "Pluck"),
    FmPreset(37, "Syn-Vox", "Vox"),
    FmPreset(38, "Syn-Orchestra", "Ens")
)

fun getFmPresetIconColor(category: String): Color {
    return when(category) {
        "Brass" -> Color(0xFFFFD700)   // Gold
        "Strings" -> Color(0xFF9370DB) // Medium Purple
        "Keys" -> Color(0xFF00BFFF)    // Deep Sky Blue
        "Pad" -> Color(0xFF20B2AA)     // Light Sea Green
        "Bass" -> Color(0xFFFF4500)    // Orange Red
        "Ens" -> Color(0xFFFF69B4)     // Hot Pink
        "Pluck" -> Color(0xFF32CD32)   // Lime Green
        "Wind" -> Color(0xFF00FA9A)    // Medium Spring Green
        "Mallet" -> Color(0xFFFF6347)  // Tomato
        "Bell" -> Color(0xFFE0FFFF)    // Light Cyan (Bright)
        "Vox" -> Color(0xFFF08080)     // Light Coral
        "Lead" -> Color(0xFFFF00FF)    // Magenta
        "FX" -> Color(0xFFFFA500)      // Orange
        else -> Color(0xFF7FFFD4)      // Aquamarine
    }
}

fun getFmPresetIconVector(category: String): androidx.compose.ui.graphics.vector.ImageVector {
    return when(category) {
        else -> Icons.Default.Lock
    }
}

@Composable
fun AutoPannerParameters(state: GrooveboxState, onStateChange: (GrooveboxState) -> Unit, nativeLib: NativeLib) {
    Column(modifier = Modifier.fillMaxWidth().padding(8.dp)) {
        Text("FILTER 1 FX", style = MaterialTheme.typography.titleMedium, color = Color.Yellow)
        Spacer(modifier = Modifier.height(16.dp))
        
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Knob(label = "PAN", initialValue = 0.5f, parameterId = 2100, state = state, onStateChange = onStateChange, nativeLib = nativeLib)
            Knob(label = "RATE", initialValue = 0.5f, parameterId = 2101, state = state, onStateChange = onStateChange, nativeLib = nativeLib)
            Knob(label = "DEPTH", initialValue = 0.5f, parameterId = 2102, state = state, onStateChange = onStateChange, nativeLib = nativeLib)
            Knob(label = "MIX", initialValue = 0.5f, parameterId = 2104, state = state, onStateChange = onStateChange, nativeLib = nativeLib)
        }
        
        Spacer(modifier = Modifier.height(16.dp))
        
        val shapes = listOf("SINE", "TRI", "SQR")
        val currentShapeValue = state.globalParameters[2103] ?: 0.0f
        val currentShapeIdx = (currentShapeValue * 2.0f + 0.5f).toInt().coerceIn(0, 2)
        
        Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text("LFO SHAPE", style = MaterialTheme.typography.labelSmall, color = Color.LightGray, modifier = Modifier.width(80.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                shapes.forEachIndexed { i, label ->
                    Button(
                        onClick = {
                            val newVal = i / 2.0f
                            nativeLib.setParameter(-1, 2103, newVal) // -1 for global
                            onStateChange(state.copy(globalParameters = state.globalParameters + (2103 to newVal)))
                        },
                        modifier = Modifier.height(32.dp),
                        contentPadding = PaddingValues(horizontal = 8.dp),
                        colors = ButtonDefaults.buttonColors(containerColor = if (currentShapeIdx == i) Color.Yellow else Color.DarkGray)
                    ) {
                        Text(label, fontSize = 10.sp, color = if (currentShapeIdx == i) Color.Black else Color.White)
                    }
                }
            }
        }
    }
}

/**
 * A Loom Pi oscillator macro: the knob's own parameter id (290-292) drives the
 * native fan-out, while the three per-oscillator values are mirrored into the
 * UI state so their knobs and saved projects stay in sync.
 */
@Composable
private fun OscMacroKnob(
    label: String,
    parameterId: Int,
    targets: List<Int>,
    state: GrooveboxState,
    trackIndex: Int,
    onStateChange: (GrooveboxState) -> Unit,
    nativeLib: NativeLib
) {
    Knob(
        label = label,
        initialValue = 0.0f,
        parameterId = parameterId,
        state = state,
        onStateChange = { newState ->
            val value = newState.tracks[trackIndex].parameters[parameterId] ?: 0.0f
            onStateChange(
                newState.copy(
                    tracks = newState.tracks.mapIndexed { idx, track ->
                        if (idx != trackIndex) track
                        else track.copy(parameters = track.parameters + targets.associateWith { value })
                    }
                )
            )
        },
        nativeLib = nativeLib,
        knobSize = 32.dp,
        labelFontSize = 8.sp
    )
}

@Composable
fun SubtractiveParameters(state: GrooveboxState, trackIndex: Int, onStateChange: (GrooveboxState) -> Unit, nativeLib: NativeLib) {
    val themeColor = Color(0xFF00E5FF) // Cyan for Subtractive
    val track = state.tracks[trackIndex]
    
    // Waveform Formatter
    val waveFormatter: (Float) -> String = { v ->
        when {
            v < 0.05f -> "Sine"
            v in 0.28f..0.38f -> "Tri"
            v in 0.61f..0.71f -> "Saw"
            v > 0.95f -> "Sqr"
            v < 0.33f -> "S-T"
            v < 0.66f -> "T-Sw"
            else -> "Sw-Sq"
        }
    }

    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        // TOP ROW: OSC 1, OSC 2, SUB OSC, TUNE (4 Boxes)
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            // OSC 1 (5 Knobs)
            CompactParameterBox(title = "OSC 1", startColor = themeColor, modifier = Modifier.weight(1f)) {
                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly) {
                    Knob("SHAPE", 0.0f, 104, state, onStateChange, nativeLib, knobSize = 32.dp, valueFormatter = waveFormatter)
                    Knob("LVL", 0.7f, 107, state, onStateChange, nativeLib, knobSize = 32.dp)
                }
                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly) {
                    Knob("PITCH", 0.5f, 160, state, onStateChange, nativeLib, knobSize = 32.dp, detentValue = 0.5f)
                    Knob("FOLD", 0.0f, 180, state, onStateChange, nativeLib, knobSize = 32.dp)
                    Knob("DRIVE", 0.0f, 170, state, onStateChange, nativeLib, knobSize = 32.dp)
                }
            }

            // OSC 2 (5 Knobs)
            CompactParameterBox(title = "OSC 2", startColor = themeColor, modifier = Modifier.weight(1f)) {
                 Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly) {
                    Knob("SHAPE", 0.5f, 105, state, onStateChange, nativeLib, knobSize = 32.dp, valueFormatter = waveFormatter)
                    Knob("LVL", 0.4f, 108, state, onStateChange, nativeLib, knobSize = 32.dp)
                }
                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly) {
                    Knob("PITCH", 0.5f, 161, state, onStateChange, nativeLib, knobSize = 32.dp, detentValue = 0.5f)
                    Knob("FOLD", 0.0f, 181, state, onStateChange, nativeLib, knobSize = 32.dp)
                    Knob("DRIVE", 0.0f, 171, state, onStateChange, nativeLib, knobSize = 32.dp)
                }
            }
            
            // SUB OSC (5 Knobs)
            CompactParameterBox(title = "SUB OSC", startColor = themeColor, modifier = Modifier.weight(1f)) {
                 Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly) {
                    Knob("SHAPE", 0.0f, 155, state, onStateChange, nativeLib, knobSize = 32.dp, valueFormatter = waveFormatter)
                    Knob("VOL", 0.4f, 109, state, onStateChange, nativeLib, knobSize = 32.dp)
                }
                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly) {
                    Knob("PITCH", 0.5f, 162, state, onStateChange, nativeLib, knobSize = 32.dp, detentValue = 0.5f)
                    Knob("FOLD", 0.0f, 182, state, onStateChange, nativeLib, knobSize = 32.dp)
                    Knob("DRIVE", 0.0f, 172, state, onStateChange, nativeLib, knobSize = 32.dp)
                }
            }
            
            // TUNE (3 Knobs)
            CompactParameterBox(title = "TUNE", startColor = themeColor, modifier = Modifier.weight(0.7f)) { // Slightly smaller weight
                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly) {
                    Knob("NOISE", 0.0f, 110, state, onStateChange, nativeLib, knobSize = 32.dp)
                    Knob("DETUN", 0.1f, 106, state, onStateChange, nativeLib, knobSize = 32.dp)
                    Knob("GLIDE", 0.0f, 355, state, onStateChange, nativeLib, knobSize = 32.dp)
                }
            }
        }

        // MACROS: one knob drives the same stage on all three oscillators
        // (ported from Loom Pi's subtractive MACROS card).
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            CompactParameterBox(title = "MACROS", startColor = themeColor, modifier = Modifier.weight(1f)) {
                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly) {
                    OscMacroKnob(
                        label = "MORPHX3",
                        parameterId = 290,
                        targets = listOf(104, 105, 155),
                        state = state,
                        trackIndex = trackIndex,
                        onStateChange = onStateChange,
                        nativeLib = nativeLib
                    )
                    OscMacroKnob(
                        label = "FOLDX3",
                        parameterId = 291,
                        targets = listOf(180, 181, 182),
                        state = state,
                        trackIndex = trackIndex,
                        onStateChange = onStateChange,
                        nativeLib = nativeLib
                    )
                    OscMacroKnob(
                        label = "DRIVEX3",
                        parameterId = 292,
                        targets = listOf(170, 171, 172),
                        state = state,
                        trackIndex = trackIndex,
                        onStateChange = onStateChange,
                        nativeLib = nativeLib
                    )
                }
            }
        }

        // MIDDLE ROW: FILTER, AMP ENV, FILTER ENV
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            // FILTER
            CompactParameterBox(title = "FILTER", startColor = themeColor, modifier = Modifier.weight(1f)) {
                 Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly) {
                    Knob("CUTOFF", 0.8f, 1, state, onStateChange, nativeLib, knobSize = 34.dp)
                    Knob("RES", 0.2f, 113, state, onStateChange, nativeLib, knobSize = 34.dp)
                }
                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly, verticalAlignment = Alignment.CenterVertically) {
                    Knob("ENV", 0.5f, 118, state, onStateChange, nativeLib, knobSize = 34.dp)
                    Spacer(modifier = Modifier.width(34.dp))
                    
                    // Mode Button (Small)
                    Button(
                        onClick = {
                            val newMode = (track.filterMode + 1) % 3
                            val newTracks = state.tracks.toMutableList()
                            newTracks[trackIndex] = track.copy(filterMode = newMode)
                            onStateChange(state.copy(tracks = newTracks))
                            nativeLib.setFilterMode(trackIndex, newMode)
                        },
                        modifier = Modifier.size(24.dp), // Check size
                        contentPadding = PaddingValues(0.dp),
                        colors = ButtonDefaults.buttonColors(containerColor = Color.DarkGray),
                        border = BorderStroke(1.dp, themeColor)
                    ) { 
                        Text(when(track.filterMode) { 0 -> "LP"; 1 -> "HP"; else -> "BP" }, fontSize = 8.sp, color = Color.White) 
                    }
                }
            }

            // AMP ENV
            CompactParameterBox(title = "AMP ENV", startColor = themeColor, modifier = Modifier.weight(1f)) {
                 Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly) {
                     Knob("Atk", 0.01f, 100, state, onStateChange, nativeLib, knobSize = 34.dp)
                     Knob("Dec", 0.2f, 101, state, onStateChange, nativeLib, knobSize = 34.dp)
                 }
                 Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly) {
                     Knob("Sus", 0.8f, 102, state, onStateChange, nativeLib, knobSize = 34.dp)
                     Knob("Rel", 0.3f, 103, state, onStateChange, nativeLib, knobSize = 34.dp)
                 }
            }

            // FILTER ENV
            CompactParameterBox(title = "FILTER ENV", startColor = themeColor, modifier = Modifier.weight(1f)) {
                 Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly) {
                     Knob("Atk", 0.01f, 114, state, onStateChange, nativeLib, knobSize = 34.dp)
                     Knob("Dec", 0.2f, 115, state, onStateChange, nativeLib, knobSize = 34.dp)
                 }
                 Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly) {
                     Knob("Sus", 0.5f, 116, state, onStateChange, nativeLib, knobSize = 34.dp)
                     Knob("Rel", 0.3f, 117, state, onStateChange, nativeLib, knobSize = 34.dp)
                 }
            }
        }
    }
}


@Composable
fun WavetableParameters(state: GrooveboxState, trackIndex: Int, onStateChange: (GrooveboxState) -> Unit, nativeLib: NativeLib, onRefresh: () -> Unit) {
    var showLoadDialog by remember { mutableStateOf(false) }
    // val context = LocalContext.current // Removed
    val track = state.tracks[trackIndex]
    val wtColor = Color(0xFF2979FF) // Blue for Wavetable

    val wavetablesDir = File(PersistenceManager.getLoomFolder(), "wavetables")
    if (!wavetablesDir.exists()) wavetablesDir.mkdirs()

    // Launcher commented out for CMP

    if (showLoadDialog) {
        NativeFileDialog(
            directory = wavetablesDir,
            onDismiss = { showLoadDialog = false },
            state = state,
            onFileSelected = { path ->
                if (path == "DEFAULT") {
                    nativeLib.loadDefaultWavetable(trackIndex)
                } else if (path == "IMPORT") {
                    // launcher.launch("audio/*")
                    platformShowMessage("Import not supported on this platform yet.")
                } else {
                    nativeLib.loadWavetable(trackIndex, path)
                    val filename = File(path).name
                    val displayName = filename.removeSuffix(".WAV").removeSuffix(".wav")
                    val newTracks = state.tracks.mapIndexed { i, t ->
                        if (i == trackIndex) t.copy(activeWavetableName = displayName) else t
                    }
                    onStateChange(state.copy(tracks = newTracks))
                }
                onRefresh()
            },
            isSave = false, // Restored isSave
                trackIndex = trackIndex,
                extensions = listOf("wav")
            )
    }

    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        
        // Header with Load Button
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 4.dp), 
            verticalAlignment = Alignment.CenterVertically, 
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
             Text("WAVETABLE SOURCE", style = MaterialTheme.typography.labelLarge, color = Color.White, fontWeight = FontWeight.Bold)
             
             Button(
                onClick = { showLoadDialog = true },
                colors = ButtonDefaults.buttonColors(containerColor = Color.White.copy(alpha=0.1f)),
                border = BorderStroke(1.dp, Color.Cyan.copy(alpha=0.5f)),
                contentPadding = PaddingValues(horizontal = 12.dp, vertical = 8.dp),
                shape = RoundedCornerShape(8.dp)
             ) {
                 Icon(Icons.Default.List, contentDescription = null, modifier = Modifier.size(16.dp), tint = Color.Cyan)
                 Spacer(modifier = Modifier.width(8.dp))
                 Text(track.activeWavetableName ?: "Basic", color = Color.Cyan, style = MaterialTheme.typography.labelLarge)
             }
         }

        // ROW 1: CHARACTER (0.6), UNISON/LOFI (0.7), AMP ENV (0.7)
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            // CHARACTER (2x2)
            CompactParameterBox(title = "CHARACTER", startColor = wtColor, modifier = Modifier.weight(0.6f)) {
                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly) {
                    Knob("Morph", 0.0f, 450, state, onStateChange, nativeLib, knobSize = 34.dp)
                    Knob("Warp", 0.0f, 465, state, onStateChange, nativeLib, knobSize = 34.dp)
                }
                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly) {
                    Knob("Crush", 0.0f, 466, state, onStateChange, nativeLib, knobSize = 34.dp)
                    Knob("Drive", 0.0f, 467, state, onStateChange, nativeLib, knobSize = 34.dp)
                }
            }

            // UNISON & LO-FI (2x2)
             CompactParameterBox(title = "UNISON & LO-FI", startColor = wtColor, modifier = Modifier.weight(0.7f)) {
                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly) {
                    Knob("Detune", 0.0f, 451, state, onStateChange, nativeLib, knobSize = 34.dp)
                    Knob("Glide", 0.0f, 355, state, onStateChange, nativeLib, knobSize = 34.dp)
                }
                 Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly) {
                    Knob("Bits", 0.0f, 475, state, onStateChange, nativeLib, knobSize = 34.dp)
                    Knob("Srate", 0.0f, 476, state, onStateChange, nativeLib, knobSize = 34.dp)
                }
            }

            // AMP ENVELOPE (2x2)
            CompactParameterBox(title = "AMP ENV", startColor = wtColor, modifier = Modifier.weight(0.7f)) {
                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly) {
                    Knob("Atk", 0.01f, 454, state, onStateChange, nativeLib, knobSize = 34.dp)
                    Knob("Dcy", 0.1f, 455, state, onStateChange, nativeLib, knobSize = 34.dp)
                }
                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly) {
                    Knob("Sus", 0.8f, 456, state, onStateChange, nativeLib, knobSize = 34.dp)
                    Knob("Rel", 0.5f, 457, state, onStateChange, nativeLib, knobSize = 34.dp)
                }
            }
        }

        // ROW 2: FILTER (1.0), FILTER ENV (1.0)
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            // FILTER (2x2 with Mode Button)
            CompactParameterBox(title = "FILTER", startColor = wtColor, modifier = Modifier.weight(1f)) {
                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly) {
                    Knob("Cutoff", 0.5f, 458, state, onStateChange, nativeLib, knobSize = 34.dp)
                    Knob("Reso", 0.0f, 459, state, onStateChange, nativeLib, knobSize = 34.dp)
                }
                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly, verticalAlignment = Alignment.CenterVertically) {
                    Knob("F.Amt", 0.0f, 464, state, onStateChange, nativeLib, knobSize = 34.dp)
                    
                    // Mode Button (Small)
                    Button(
                        onClick = {
                            val currentMode = (track.parameters[470] ?: 0.0f).toInt().coerceIn(0, 3)
                            val nextMode = (currentMode + 1) % 4
                            val newVal = nextMode.toFloat()
                            nativeLib.setParameter(trackIndex, 470, newVal)
                            onStateChange(state.copy(tracks = state.tracks.mapIndexed { i, t -> if (i == trackIndex) t.copy(parameters = t.parameters + (470 to newVal)) else t }))
                        },
                        modifier = Modifier.size(34.dp),
                        contentPadding = PaddingValues(0.dp),
                        colors = ButtonDefaults.buttonColors(containerColor = Color.DarkGray),
                        border = BorderStroke(1.dp, wtColor)
                    ) {
                        val currentMode = (track.parameters[470] ?: 0.0f).toInt().coerceIn(0, 3)
                         Text(when(currentMode) { 0 -> "LP"; 1 -> "HP"; 2 -> "BP"; else -> "NT" }, fontSize = 9.sp, color = Color.White) 
                    }
                }
            }

            // FILTER ENVELOPE (2x2)
            CompactParameterBox(title = "FILTER ENV", startColor = wtColor, modifier = Modifier.weight(1f)) {
                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly) {
                    Knob("Atk", 0.01f, 471, state, onStateChange, nativeLib, knobSize = 34.dp)
                    Knob("Dcy", 0.1f, 461, state, onStateChange, nativeLib, knobSize = 34.dp)
                }
                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly) {
                    Knob("Sus", 0.0f, 473, state, onStateChange, nativeLib, knobSize = 34.dp)
                    Knob("Rel", 0.5f, 474, state, onStateChange, nativeLib, knobSize = 34.dp)
                }
            }
        }
    }
}

@Composable
fun AnalogDrumParameters(state: GrooveboxState, trackIndex: Int, onStateChange: (GrooveboxState) -> Unit, nativeLib: NativeLib) {
    val drumColor = getEngineColor(EngineType.ANALOG_DRUM)
    val knobSize = 38.dp
    val hotPink = Color(0xFFFF69B4)

    Row(
        modifier = Modifier
            .fillMaxSize()
            .padding(8.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        // Kick
        Column(
            modifier = Modifier
                .weight(1f)
                .fillMaxHeight()
                .border(1.dp, Color.White.copy(alpha = 0.1f), RoundedCornerShape(12.dp))
                .padding(12.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.SpaceEvenly
        ) {
            val drumIndex = 0
            val note = 60
            val isSelectedForSidechain = state.isSelectingSidechain && state.sidechainSourceTrack == trackIndex && state.sidechainSourceDrumIdx == drumIndex
            var isPressed by remember { mutableStateOf(false) }
            val currentColor = if (isPressed) Color.White else drumColor

            Column(
                modifier = Modifier
                    .clip(RoundedCornerShape(8.dp))
                    .background(if (isPressed) drumColor.copy(alpha=0.5f) else Color.Transparent)
                    .then(if (state.isSelectingSidechain) Modifier.border(2.dp, hotPink, CircleShape).padding(4.dp) else Modifier)
                    .pointerInput(state.isSelectingSidechain) {
                        detectTapGestures(
                            onPress = {
                                if (!state.isSelectingSidechain) {
                                    try {
                                        isPressed = true
                                        if (note in 0..127) {
                                            nativeLib.triggerNote(trackIndex, note, 100)
                                        }
                                        awaitRelease()
                                    } finally {
                                        isPressed = false
                                        if (note in 0..127) {
                                            nativeLib.releaseNote(trackIndex, note)
                                        }
                                    }
                                }
                            },
                            onTap = {
                                if (state.isSelectingSidechain) {
                                    nativeLib.setParameter(0, 585, trackIndex.toFloat())
                                    nativeLib.setParameter(0, 586, drumIndex.toFloat())
                                    onStateChange(state.copy(isSelectingSidechain = false, sidechainSourceTrack = trackIndex, sidechainSourceDrumIdx = drumIndex, selectedTab = 3))
                                }
                            }
                        )
                    }
                    .padding(8.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Canvas(Modifier.size(32.dp)) { drawCircle(color = if (isSelectedForSidechain) hotPink else currentColor) }
                Text("KICK", style = MaterialTheme.typography.labelMedium, color = if (isSelectedForSidechain) hotPink else currentColor, maxLines = 1, fontWeight = FontWeight.Bold)
            }
            Knob("Dcy", 0.5f, 600, state, onStateChange, nativeLib, knobSize = knobSize)
            Knob("Tone", 0.8f, 601, state, onStateChange, nativeLib, knobSize = knobSize)
            Knob("Tune", 0.25f, 602, state, onStateChange, nativeLib, knobSize = knobSize)
            Knob("Gain", 0.8f, 605, state, onStateChange, nativeLib, knobSize = knobSize)
        }
        
        // Snare
        Column(
            modifier = Modifier
                .weight(1f)
                .fillMaxHeight()
                .border(1.dp, Color.White.copy(alpha = 0.1f), RoundedCornerShape(12.dp))
                .padding(12.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.SpaceEvenly
        ) {
            val drumIndex = 1
            val note = 61
            val isSelectedForSidechain = state.isSelectingSidechain && state.sidechainSourceTrack == trackIndex && state.sidechainSourceDrumIdx == drumIndex
            var isPressed by remember { mutableStateOf(false) }
            val currentColor = if (isPressed) Color.White else drumColor

            Column(
                modifier = Modifier
                    .clip(RoundedCornerShape(8.dp))
                    .background(if (isPressed) drumColor.copy(alpha=0.5f) else Color.Transparent)
                    .then(if (state.isSelectingSidechain) Modifier.border(2.dp, hotPink, CircleShape).padding(4.dp) else Modifier)
                    .pointerInput(state.isSelectingSidechain) {
                        detectTapGestures(
                            onPress = {
                                if (!state.isSelectingSidechain) {
                                    try {
                                        isPressed = true
                                        if (note in 0..127) {
                                            nativeLib.triggerNote(trackIndex, note, 100)
                                        }
                                        awaitRelease()
                                    } finally {
                                        isPressed = false
                                        if (note in 0..127) {
                                            nativeLib.releaseNote(trackIndex, note)
                                        }
                                    }
                                }
                            },
                            onTap = {
                                if (state.isSelectingSidechain) {
                                    nativeLib.setParameter(0, 585, trackIndex.toFloat())
                                    nativeLib.setParameter(0, 586, drumIndex.toFloat())
                                    onStateChange(state.copy(isSelectingSidechain = false, sidechainSourceTrack = trackIndex, sidechainSourceDrumIdx = drumIndex, selectedTab = 3))
                                }
                            }
                        )
                    }
                    .padding(8.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Canvas(Modifier.size(32.dp)) { drawRect(color = if (isSelectedForSidechain) hotPink else currentColor) }
                Text("SNARE", style = MaterialTheme.typography.labelMedium, color = if (isSelectedForSidechain) hotPink else currentColor, maxLines = 1, fontWeight = FontWeight.Bold)
            }
            Knob("Dcy", 0.2f, 610, state, onStateChange, nativeLib, knobSize = knobSize)
            Knob("Snap", 0.4f, 613, state, onStateChange, nativeLib, knobSize = knobSize)
            Knob("Tune", 0.33f, 612, state, onStateChange, nativeLib, knobSize = knobSize)
            Knob("Gain", 0.8f, 615, state, onStateChange, nativeLib, knobSize = knobSize)
        }
        
        // Rim
        Column(
            modifier = Modifier
                .weight(1f)
                .fillMaxHeight()
                .border(1.dp, Color.White.copy(alpha = 0.1f), RoundedCornerShape(12.dp))
                .padding(12.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.SpaceEvenly
        ) {
            val drumIndex = 2
            val note = 62
            val isSelectedForSidechain = state.isSelectingSidechain && state.sidechainSourceTrack == trackIndex && state.sidechainSourceDrumIdx == drumIndex
            var isPressed by remember { mutableStateOf(false) }
            val currentColor = if (isPressed) Color.White else drumColor

            Column(
                modifier = Modifier
                    .clip(RoundedCornerShape(8.dp))
                    .background(if (isPressed) drumColor.copy(alpha=0.5f) else Color.Transparent)
                    .then(if (state.isSelectingSidechain) Modifier.border(2.dp, hotPink, CircleShape).padding(4.dp) else Modifier)
                    .pointerInput(state.isSelectingSidechain) {
                        detectTapGestures(
                            onPress = {
                                if (!state.isSelectingSidechain) {
                                    try {
                                        isPressed = true
                                        if (note in 0..127) {
                                            nativeLib.triggerNote(trackIndex, note, 100)
                                        }
                                        awaitRelease()
                                    } finally {
                                        isPressed = false
                                        if (note in 0..127) {
                                            nativeLib.releaseNote(trackIndex, note)
                                        }
                                    }
                                }
                            },
                            onTap = {
                                if (state.isSelectingSidechain) {
                                    nativeLib.setParameter(0, 585, trackIndex.toFloat())
                                    nativeLib.setParameter(0, 586, drumIndex.toFloat())
                                    onStateChange(state.copy(isSelectingSidechain = false, sidechainSourceTrack = trackIndex, sidechainSourceDrumIdx = drumIndex, selectedTab = 3))
                                }
                            }
                        )
                    }
                    .padding(8.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Canvas(Modifier.size(32.dp)) {
                    drawRect(
                        color = if (isSelectedForSidechain) hotPink else currentColor,
                        topLeft = androidx.compose.ui.geometry.Offset(0f, size.height * 0.4f),
                        size = androidx.compose.ui.geometry.Size(size.width, size.height * 0.2f)
                    )
                }
                Text("RIM", style = MaterialTheme.typography.labelMedium, color = if (isSelectedForSidechain) hotPink else currentColor, maxLines = 1, fontWeight = FontWeight.Bold)
            }
            Knob("Dcy", 0.3f, 620, state, onStateChange, nativeLib, knobSize = knobSize)
            Knob("Col", 0.0f, 621, state, onStateChange, nativeLib, knobSize = knobSize)
            Knob("Tune", 0.5f, 622, state, onStateChange, nativeLib, knobSize = knobSize)
            Knob("Gain", 0.8f, 625, state, onStateChange, nativeLib, knobSize = knobSize)
        }
        
        // Hat Closed
        Column(
            modifier = Modifier
                .weight(1f)
                .fillMaxHeight()
                .border(1.dp, Color.White.copy(alpha = 0.1f), RoundedCornerShape(12.dp))
                .padding(12.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.SpaceEvenly
        ) {
            val drumIndex = 3
            val note = 63
            val isSelectedForSidechain = state.isSelectingSidechain && state.sidechainSourceTrack == trackIndex && state.sidechainSourceDrumIdx == drumIndex
            var isPressed by remember { mutableStateOf(false) }
            val currentColor = if (isPressed) Color.White else drumColor

            Column(
                modifier = Modifier
                    .clip(RoundedCornerShape(8.dp))
                    .background(if (isPressed) drumColor.copy(alpha=0.5f) else Color.Transparent)
                    .then(if (state.isSelectingSidechain) Modifier.border(2.dp, hotPink, CircleShape).padding(4.dp) else Modifier)
                    .pointerInput(state.isSelectingSidechain) {
                        detectTapGestures(
                            onPress = {
                                if (!state.isSelectingSidechain) {
                                    try {
                                        isPressed = true
                                        if (note in 0..127) {
                                            nativeLib.triggerNote(trackIndex, note, 100)
                                        }
                                        awaitRelease()
                                    } finally {
                                        isPressed = false
                                        if (note in 0..127) {
                                            nativeLib.releaseNote(trackIndex, note)
                                        }
                                    }
                                }
                            },
                            onTap = {
                                if (state.isSelectingSidechain) {
                                    nativeLib.setParameter(0, 585, trackIndex.toFloat())
                                    nativeLib.setParameter(0, 586, drumIndex.toFloat())
                                    onStateChange(state.copy(isSelectingSidechain = false, sidechainSourceTrack = trackIndex, sidechainSourceDrumIdx = drumIndex, selectedTab = 3))
                                }
                            }
                        )
                    }
                    .padding(8.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Canvas(Modifier.size(32.dp)) {
                    drawLine(if (isSelectedForSidechain) hotPink else currentColor, start = androidx.compose.ui.geometry.Offset(0f, size.height/2), end = androidx.compose.ui.geometry.Offset(size.width, size.height/2), strokeWidth = 5f)
                    drawLine(if (isSelectedForSidechain) hotPink else currentColor, start = androidx.compose.ui.geometry.Offset(size.width/2, 0f), end = androidx.compose.ui.geometry.Offset(size.width/2, size.height), strokeWidth = 5f)
                }
                Text("HAT C", style = MaterialTheme.typography.labelMedium, color = if (isSelectedForSidechain) hotPink else currentColor, maxLines = 1, fontWeight = FontWeight.Bold)
            }
            Knob("Dcy", 0.1f, 630, state, onStateChange, nativeLib, knobSize = knobSize)
            Knob("Col", 0.8f, 631, state, onStateChange, nativeLib, knobSize = knobSize)
            Knob("Gain", 0.8f, 635, state, onStateChange, nativeLib, knobSize = knobSize)
        }
        
        // Hat Open
        Column(
            modifier = Modifier
                .weight(1f)
                .fillMaxHeight()
                .border(1.dp, Color.White.copy(alpha = 0.1f), RoundedCornerShape(12.dp))
                .padding(12.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.SpaceEvenly
        ) {
            val drumIndex = 4
            val note = 64
            val isSelectedForSidechain = state.isSelectingSidechain && state.sidechainSourceTrack == trackIndex && state.sidechainSourceDrumIdx == drumIndex
            var isPressed by remember { mutableStateOf(false) }
            val currentColor = if (isPressed) Color.White else drumColor

            Column(
                modifier = Modifier
                    .clip(RoundedCornerShape(8.dp))
                    .background(if (isPressed) drumColor.copy(alpha=0.5f) else Color.Transparent)
                    .then(if (state.isSelectingSidechain) Modifier.border(2.dp, hotPink, CircleShape).padding(4.dp) else Modifier)
                    .pointerInput(state.isSelectingSidechain) {
                        detectTapGestures(
                            onPress = {
                                if (!state.isSelectingSidechain) {
                                    try {
                                        isPressed = true
                                        if (note in 0..127) {
                                            nativeLib.triggerNote(trackIndex, note, 100)
                                        }
                                        awaitRelease()
                                    } finally {
                                        isPressed = false
                                        if (note in 0..127) {
                                            nativeLib.releaseNote(trackIndex, note)
                                        }
                                    }
                                }
                            },
                            onTap = {
                                if (state.isSelectingSidechain) {
                                    nativeLib.setParameter(0, 585, trackIndex.toFloat())
                                    nativeLib.setParameter(0, 586, drumIndex.toFloat())
                                    onStateChange(state.copy(isSelectingSidechain = false, sidechainSourceTrack = trackIndex, sidechainSourceDrumIdx = drumIndex, selectedTab = 3))
                                }
                            }
                        )
                    }
                    .padding(8.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Canvas(Modifier.size(32.dp)) {
                    drawCircle(if (isSelectedForSidechain) hotPink else currentColor, style = androidx.compose.ui.graphics.drawscope.Stroke(width = 5f))
                }
                Text("HAT O", style = MaterialTheme.typography.labelMedium, color = if (isSelectedForSidechain) hotPink else currentColor, maxLines = 1, fontWeight = FontWeight.Bold)
            }
            Knob("Dcy", 0.4f, 640, state, onStateChange, nativeLib, knobSize = knobSize)
            Knob("Col", 0.8f, 641, state, onStateChange, nativeLib, knobSize = knobSize)
            Knob("Gain", 0.8f, 645, state, onStateChange, nativeLib, knobSize = knobSize)
        }
        
        // Cymbal
        Column(
            modifier = Modifier
                .weight(1f)
                .fillMaxHeight()
                .border(1.dp, Color.White.copy(alpha = 0.1f), RoundedCornerShape(12.dp))
                .padding(12.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.SpaceEvenly
        ) {
            val drumIndex = 5
            val note = 65
            val isSelectedForSidechain = state.isSelectingSidechain && state.sidechainSourceTrack == trackIndex && state.sidechainSourceDrumIdx == drumIndex
            var isPressed by remember { mutableStateOf(false) }
            val currentColor = if (isPressed) Color.White else drumColor

            Column(
                modifier = Modifier
                    .clip(RoundedCornerShape(8.dp))
                    .background(if (isPressed) drumColor.copy(alpha=0.5f) else Color.Transparent)
                    .then(if (state.isSelectingSidechain) Modifier.border(2.dp, hotPink, CircleShape).padding(4.dp) else Modifier)
                    .pointerInput(state.isSelectingSidechain) {
                        detectTapGestures(
                            onPress = {
                                if (!state.isSelectingSidechain) {
                                    try {
                                        isPressed = true
                                        if (note in 0..127) {
                                            nativeLib.triggerNote(trackIndex, note, 100)
                                        }
                                        awaitRelease()
                                    } finally {
                                        isPressed = false
                                        if (note in 0..127) {
                                            nativeLib.releaseNote(trackIndex, note)
                                        }
                                    }
                                }
                            },
                            onTap = {
                                if (state.isSelectingSidechain) {
                                    nativeLib.setParameter(0, 585, trackIndex.toFloat())
                                    nativeLib.setParameter(0, 586, drumIndex.toFloat())
                                    onStateChange(state.copy(isSelectingSidechain = false, sidechainSourceTrack = trackIndex, sidechainSourceDrumIdx = drumIndex, selectedTab = 3))
                                }
                            }
                        )
                    }
                    .padding(8.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Canvas(Modifier.size(32.dp)) {
                    val path = androidx.compose.ui.graphics.Path().apply {
                        moveTo(size.width/2, 0f)
                        lineTo(size.width, size.height)
                        lineTo(0f, size.height)
                        close()
                    }
                    drawPath(path, if (isSelectedForSidechain) hotPink else currentColor)
                }
                Text("CYMBAL", style = MaterialTheme.typography.labelMedium, color = if (isSelectedForSidechain) hotPink else currentColor, maxLines = 1, fontWeight = FontWeight.Bold)
            }
            Knob("Atk", 0.0f, 653, state, onStateChange, nativeLib, knobSize = knobSize)
            Knob("Dcy", 0.8f, 650, state, onStateChange, nativeLib, knobSize = knobSize)
            Knob("Col", 0.7f, 651, state, onStateChange, nativeLib, knobSize = knobSize)
            Knob("Gain", 0.6f, 655, state, onStateChange, nativeLib, knobSize = knobSize)
        }
    }
}



@Composable
fun FmDrumParameters(viewModel: com.groovebox.GrooveboxViewModel, trackIndex: Int, nativeLib: NativeLib) {
    val state = viewModel.state
    val onStateChange = viewModel::onStateChange
    val instruments = listOf("KICK", "SNARE", "TOM", "HIHAT", "OHH", "CYMB", "PERC", "NOISE")
    
    val platformInfo = LocalPlatformInfo.current
    val isTablet = kotlin.math.min(platformInfo.screenWidthDp, platformInfo.screenHeightDp) >= 600

    val containerModifier = if (isTablet) Modifier.fillMaxWidth().fillMaxHeight().padding(vertical = 8.dp)
                            else Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(vertical = 4.dp)
    
    Row(
        modifier = containerModifier,
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        instruments.forEachIndexed { i, name ->
            val baseId = 200 + i * 10
            Column(
                modifier = (if (isTablet) Modifier.weight(1f) else Modifier.width(72.dp))
                    .border(1.dp, Color.White.copy(alpha = 0.1f), RoundedCornerShape(4.dp))
                    .padding(2.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(2.dp)
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable {
                            nativeLib.setSelectedFmDrumInstrument(trackIndex, i)
                            viewModel.setSelectedFmDrumInstrument(trackIndex, i)
                            nativeLib.triggerNote(trackIndex, 60 + i, 100)
                        }
                        .padding(vertical = 4.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    Text(name, style = MaterialTheme.typography.labelMedium, color = Color(0xFFEEEEEE), maxLines = 1)
                    
                    EngineIcon(
                        type = EngineType.FM_DRUM,
                        modifier = Modifier.size(32.dp),
                        color = getEngineColor(EngineType.FM_DRUM)
                    )
                }
                
                Divider(color = Color.White.copy(alpha = 0.05f))

                Knob("PITCH", 0.5f, baseId, state, onStateChange, nativeLib, knobSize = 36.dp)
                Knob("SNAP", 0.1f, baseId + 1, state, onStateChange, nativeLib, knobSize = 36.dp)
                Knob("DECAY", 0.3f, baseId + 2, state, onStateChange, nativeLib, knobSize = 36.dp)
                Knob("LVL", 0.8f, baseId + 5, state, onStateChange, nativeLib, knobSize = 36.dp)
            }
        }
    }
}
@Composable
fun FmParameters(state: GrooveboxState, trackIndex: Int, onStateChange: (GrooveboxState) -> Unit, nativeLib: NativeLib, onRefresh: () -> Unit) {
    val track = state.tracks[trackIndex]
    var showPresetDrawer by remember { mutableStateOf(false) }
    var showLoadDialog by remember { mutableStateOf(false) }
    val themeColor = Color(0xFF00FF00) // Green for FM

    if (showPresetDrawer) {
        Dialog(
            onDismissRequest = { showPresetDrawer = false },
            properties = DialogProperties(usePlatformDefaultWidth = false)
        ) {
            Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Surface(
                    shape = RoundedCornerShape(16.dp),
                    color = Color(0xFF222222),
                    modifier = Modifier.fillMaxWidth(0.8f).fillMaxHeight(0.9f)
                ) {
                    Column(modifier = Modifier.padding(16.dp)) {
                        Text("Select FM Preset", style = MaterialTheme.typography.headlineSmall, color = Color.White)
                        Spacer(modifier = Modifier.height(16.dp))
                    
                        LazyVerticalGrid(
                            columns = GridCells.Fixed(6),
                            horizontalArrangement = Arrangement.spacedBy(4.dp),
                            verticalArrangement = Arrangement.spacedBy(8.dp),
                            modifier = Modifier.weight(1f)
                        ) {
                            items(fmPresets) { preset ->
                                Box(
                                    modifier = Modifier
                                        .clip(RoundedCornerShape(8.dp))
                                        .background(if (track.selectedFmPreset == preset.id) Color.DarkGray else Color(0xFF333333))
                                        .border(
                                            width = if (track.selectedFmPreset == preset.id) 2.dp else 0.dp,
                                            color = if (track.selectedFmPreset == preset.id) Color.White else Color.Transparent,
                                            shape = RoundedCornerShape(8.dp)
                                        )
                                        .clickable {
                                            nativeLib.loadFmPreset(trackIndex, preset.id)
                                            val allParams = nativeLib.getAllTrackParameters(trackIndex)
                                            val paramMap = if (allParams.isNotEmpty())
                                                allParams.mapIndexed { idx, value -> idx to value }.toMap() else track.parameters
                                            val newState = state.copy(tracks = state.tracks.mapIndexed { idx, t ->
                                                if (idx == trackIndex) t.copy(selectedFmPreset = preset.id, parameters = paramMap, fm1KnobBindings = null, fm1StepKnobBindings = null) else t
                                            })
                                            onStateChange(newState)
                                            showPresetDrawer = false
                                        }
                                        .padding(4.dp),
                                    contentAlignment = Alignment.Center
                                ) {
                                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                        val iconColor = getFmPresetIconColor(preset.category)
                                        val iconVector = getFmPresetIconVector(preset.category)
                                        
                                        Icon(
                                            imageVector = iconVector,
                                            contentDescription = preset.category,
                                            modifier = Modifier.size(32.dp),
                                            tint = iconColor
                                        )
                                        Spacer(modifier = Modifier.height(6.dp))
                                        Text(preset.name, style = MaterialTheme.typography.labelSmall, fontSize = 8.sp, color = Color.White, textAlign = TextAlign.Center, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                    }
                                }
                            }
                            // Imported DX7 Presets
                            val importedColors = listOf(
                                Color(0xFFE91E63), Color(0xFF9C27B0), Color(0xFF3F51B5),
                                Color(0xFF00BCD4), Color(0xFF4CAF50), Color(0xFFFFC107),
                                Color(0xFFFF5722), Color(0xFF607D8B)
                            )
                            val importedIcons = listOf(
                                Icons.Default.Lock, Icons.Default.Info,
                                Icons.Default.List, Icons.Default.Refresh
                            )
                            items(state.importedFmPresets.size) { importIdx ->
                                val voiceMap = state.importedFmPresets[importIdx]
                                val name = voiceMap["name"] as? String ?: "DX7 ${importIdx + 1}"
                                val presetId = 1000 + importIdx
                                Box(
                                    modifier = Modifier
                                        .clip(RoundedCornerShape(8.dp))
                                        .background(if (track.selectedFmPreset == presetId) Color.DarkGray else Color(0xFF333333))
                                        .border(
                                            width = if (track.selectedFmPreset == presetId) 2.dp else 0.dp,
                                            color = if (track.selectedFmPreset == presetId) Color.White else Color.Transparent,
                                            shape = RoundedCornerShape(8.dp)
                                        )
                                        .pointerInput(importIdx) {
                                            detectTapGestures(
                                                onTap = {
                                                    applyImportedFmVoice(voiceMap, trackIndex, nativeLib)
                                                    val allParams = nativeLib.getAllTrackParameters(trackIndex)
                                                    val paramMap = if (allParams.isNotEmpty()) allParams.mapIndexed { idx, value -> idx to value }.toMap() else state.tracks[trackIndex].parameters
                                                    onStateChange(state.copy(tracks = state.tracks.mapIndexed { idx, t -> 
                                                        if (idx == trackIndex) t.copy(selectedFmPreset = presetId, parameters = paramMap, fm1KnobBindings = null, fm1StepKnobBindings = null) else t
                                                    }))
                                                    showPresetDrawer = false
                                                },
                                                onLongPress = {
                                                    val updated = state.importedFmPresets.toMutableList().apply { removeAt(importIdx) }
                                                    onStateChange(state.copy(
                                                        importedFmPresets = updated,
                                                        focusedValue = "Deleted: $name"
                                                    ))
                                                }
                                            )
                                        }
                                        .padding(4.dp),
                                    contentAlignment = Alignment.Center
                                ) {
                                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                        Icon(
                                            imageVector = importedIcons[importIdx % importedIcons.size],
                                            contentDescription = "Imported",
                                            modifier = Modifier.size(32.dp),
                                            tint = importedColors[importIdx % importedColors.size]
                                        )
                                        Spacer(modifier = Modifier.height(6.dp))
                                        Text(name, style = MaterialTheme.typography.labelSmall, fontSize = 8.sp, color = Color.White, textAlign = TextAlign.Center, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                    }
                                }
                            }
                        }
                        Spacer(modifier = Modifier.height(16.dp))
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            // Import DX7 Sysex Button
                            Button(
                                onClick = {
                                    // Use NativeFileDialog to find .syx files
                                    showLoadDialog = true 
                                },
                                colors = ButtonDefaults.buttonColors(containerColor = themeColor.copy(alpha=0.3f)),
                                border = BorderStroke(1.dp, themeColor)
                            ) {
                                Icon(Icons.Default.Share, contentDescription = null, modifier = Modifier.size(20.dp), tint = themeColor)
                                Spacer(modifier = Modifier.width(8.dp))
                                Text("IMPORT SYSEX", color = themeColor)
                            }
                            
                            Button(onClick = { showPresetDrawer = false }) {
                                Text("Close")
                            }
                        }
                    }
                }
            }
        }
    }
    
    if (showLoadDialog) {
        com.groovebox.ui.components.NativeFileDialog(
            directory = java.io.File(nativeLib.getAppDataDir()),
            onDismiss = { showLoadDialog = false },
            state = state,
            title = "Import DX7 Sysex (.syx)",
            extensions = listOf("syx"),
            onFileSelected = { path ->
                if (path != null) {
                    try {
                        val data = java.io.File(path).readBytes()
                        val voices = SysexParser.parse(data)
                        if (voices.isNotEmpty()) {
                            val newPresets = voices.map { v ->
                                mapOf(
                                    "name" to v.name,
                                    "algorithm" to v.algorithm,
                                    "feedback" to v.feedback,
                                    "opLevels" to v.opLevels.toList(),
                                    "opRatios" to v.opRatios.toList(),
                                    "opAttack" to v.opAttack.toList(),
                                    "opDecay" to v.opDecay.toList(),
                                    "opSustain" to v.opSustain.toList(),
                                    "opRelease" to v.opRelease.toList(),
                                    "carrierMask" to v.carrierMask
                                )
                            }
                            onStateChange(state.copy(importedFmPresets = state.importedFmPresets + newPresets))
                            com.groovebox.ui.platformShowMessage("Imported ${voices.size} DX7 voices")
                        } else {
                            com.groovebox.ui.platformShowMessage("No valid DX7 voices found")
                        }
                    } catch (e: Exception) {
                        com.groovebox.ui.platformShowMessage("Import Error: ${e.message}")
                    }
                }
                showLoadDialog = false
            }
        )
    }

    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        // themeColor moved to top

        // Helper contents
        val routingContent: @Composable ColumnScope.() -> Unit = {
             Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly) {
                Knob("ALGO", 0.0f, 150, state, onStateChange, nativeLib, knobSize = 34.dp,
                    valueFormatter = { v -> "${((v * 31.99f).toInt() + 1)}" })
                Knob("FBK", 0.0f, 154, state, onStateChange, nativeLib, knobSize = 34.dp)
                Knob("DRIVE", 0.0f, 159, state, onStateChange, nativeLib, knobSize = 34.dp)
             }
             Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly) {
                Knob("BRT", 0.5f, 157, state, onStateChange, nativeLib, knobSize = 34.dp)
                Knob("GLIDE", 0.0f, 355, state, onStateChange, nativeLib, knobSize = 34.dp)
                Spacer(modifier = Modifier.width(34.dp)) // Placeholder for balance
             }
        }

        val filterContent: @Composable ColumnScope.() -> Unit = {
             val currentTrack = state.tracks[trackIndex]
             Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly) {
                Knob("FILT", 1.0f, 151, state, onStateChange, nativeLib, knobSize = 34.dp)
                Knob("RES", 0.0f, 152, state, onStateChange, nativeLib, knobSize = 34.dp)
             }
             Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly, verticalAlignment = Alignment.CenterVertically) {
                Knob("DET", 0.0f, 158, state, onStateChange, nativeLib, knobSize = 34.dp)
                
                // Mode Button (Small)
                Button(
                    onClick = {
                        val newMode = (currentTrack.filterMode + 1) % 3
                        val newTracks = state.tracks.toMutableList()
                        newTracks[trackIndex] = currentTrack.copy(filterMode = newMode)
                        onStateChange(state.copy(tracks = newTracks))
                        nativeLib.setFilterMode(trackIndex, newMode)
                    },
                    modifier = Modifier.size(34.dp),
                    contentPadding = PaddingValues(0.dp),
                    colors = ButtonDefaults.buttonColors(containerColor = Color.DarkGray),
                    border = BorderStroke(1.dp, themeColor)
                ) { Text(when(currentTrack.filterMode) { 0 -> "LP"; 1 -> "HP"; else -> "BP" }, fontSize = 9.sp, color = Color.White) }
             }
        }

        val ampContent: @Composable ColumnScope.() -> Unit = {
             Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly) {
                  Knob("Atk", 0.01f, 100, state, onStateChange, nativeLib, knobSize = 34.dp)
                  Knob("Dec", 0.1f, 101, state, onStateChange, nativeLib, knobSize = 34.dp)
             }
             Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly) {
                  Knob("Sus", 0.8f, 102, state, onStateChange, nativeLib, knobSize = 34.dp)
                  Knob("Rel", 0.5f, 103, state, onStateChange, nativeLib, knobSize = 34.dp)
             }
        }

        // 3 Control Boxes (Narrow) + Browse Button
        Row(modifier = Modifier.fillMaxWidth().height(IntrinsicSize.Min), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
             CompactParameterBox("ROUTING", themeColor, modifier = Modifier.weight(0.75f), content = routingContent)
             CompactParameterBox("FILTER & UNISON", themeColor, modifier = Modifier.weight(0.75f), content = filterContent)
             CompactParameterBox("AMP ENVELOPE", themeColor, modifier = Modifier.weight(0.75f), content = ampContent)
             
             // Browse Button (Large Square-ish)
             Button(
                onClick = { showPresetDrawer = true },
                colors = ButtonDefaults.buttonColors(containerColor = themeColor.copy(alpha=0.2f)),
                shape = RoundedCornerShape(8.dp),
                border = BorderStroke(1.dp, themeColor),
                modifier = Modifier.weight(1.0f).fillMaxHeight()
            ) {
                Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center, modifier = Modifier.fillMaxSize()) {
                    Text("PRESET", color = themeColor, fontWeight = FontWeight.Bold, style = MaterialTheme.typography.labelSmall, textAlign = TextAlign.Center, lineHeight = 10.sp, fontSize = 10.sp)
                    Spacer(modifier = Modifier.height(6.dp))
                    val activePreset = fmPresets.find { it.id == track.selectedFmPreset }
                    if (activePreset != null) {
                        Icon(getFmPresetIconVector(activePreset.category), contentDescription = null, tint = getFmPresetIconColor(activePreset.category), modifier = Modifier.size(20.dp))
                        Spacer(modifier = Modifier.height(2.dp))
                        Text(activePreset.name, color = Color.White, fontWeight = FontWeight.SemiBold, style = MaterialTheme.typography.labelSmall, textAlign = TextAlign.Center, lineHeight = 10.sp, fontSize = 8.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    } else {
                        Spacer(modifier = Modifier.height(30.dp)) // Leave empty space if none loaded yet
                    }
                }
            }
        }

        // 6-Operator Grid (Restored)
        Row(
            modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(4.dp),
            verticalAlignment = Alignment.Top
        ) {
            repeat(6) { opIdx ->
                val baseId = 160 + opIdx * 6
                Column(
                    modifier = Modifier
                        .width(120.dp) 
                        .border(1.dp, Color.White.copy(alpha = 0.1f), RoundedCornerShape(8.dp))
                        .padding(10.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    val isActive = (track.fmActiveMask and (1 shl opIdx)) != 0
                    val isCarrier = (track.fmCarrierMask and (1 shl opIdx)) != 0
                    
                    val (buttonColor, textColor, stateLabel) = when {
                        !isActive -> Triple(Color.Black, Color.White, "OFF")
                        isCarrier -> Triple(Color(0xFF00FF00), Color.Black, "CAR")
                        else -> Triple(Color(0xFFFF00FF), Color.White, "MOD")
                    }

                    Button(
                        onClick = { 
                            var nextActive = isActive
                            var nextCarrier = isCarrier
                            
                            // Cycle: MOD (Active=1, Carr=0) -> CAR (Active=1, Carr=1) -> OFF (Active=0, Carr=0) -> MOD...
                            if (isActive && !isCarrier) { // MOD -> CAR
                                nextCarrier = true
                            } else if (isActive && isCarrier) { // CAR -> OFF
                                nextActive = false
                                nextCarrier = false
                            } else { // OFF -> MOD
                                nextActive = true
                                nextCarrier = false
                            }

                            onStateChange(state.copy(tracks = state.tracks.mapIndexed { idx, t ->
                                if (idx == trackIndex) t.copy(fmActiveMask = if (nextActive) t.fmActiveMask or (1 shl opIdx) else t.fmActiveMask and (1 shl opIdx).inv(),
                                                            fmCarrierMask = if (nextCarrier) t.fmCarrierMask or (1 shl opIdx) else t.fmCarrierMask and (1 shl opIdx).inv()) else t
                            }))
                            nativeLib.setParameter(trackIndex, 153, (if (nextCarrier) track.fmCarrierMask or (1 shl opIdx) else track.fmCarrierMask and (1 shl opIdx).inv()).toFloat())
                            nativeLib.setParameter(trackIndex, 155, (if (nextActive) track.fmActiveMask or (1 shl opIdx) else track.fmActiveMask and (1 shl opIdx).inv()).toFloat())
                        },

                        modifier = Modifier.size(width = 56.dp, height = 36.dp),
                        shape = RoundedCornerShape(8.dp),
                        contentPadding = PaddingValues(0.dp),
                        colors = ButtonDefaults.buttonColors(containerColor = buttonColor),
                        border = if (!isActive) BorderStroke(1.dp, Color.LightGray) else null
                    ) {
                        Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
                            Text("${opIdx + 1}", color = textColor, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                            Text(stateLabel, color = textColor, style = MaterialTheme.typography.labelSmall, fontSize = 8.sp)
                        }
                    }

                    Divider(color = Color.White.copy(alpha = 0.1f))

                    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly) {
                        Knob("LVL", 0.0f, baseId, state, onStateChange, nativeLib, knobSize = 24.dp)
                        Knob("RAT", 1.0f, baseId + 1, state, onStateChange, nativeLib, knobSize = 24.dp, valueFormatter = { v -> String.format("%.1f", v) })
                    }
                    
                    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly) {
                         Knob("Atk", 0.0f, baseId + 2, state, onStateChange, nativeLib, knobSize = 24.dp)
                         Knob("Dec", 0.0f, baseId + 3, state, onStateChange, nativeLib, knobSize = 24.dp)
                    }
                    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly) {
                         Knob("Sus", 1.0f, baseId + 4, state, onStateChange, nativeLib, knobSize = 24.dp)
                         Knob("Rel", 0.0f, baseId + 5, state, onStateChange, nativeLib, knobSize = 24.dp)
            }
        }
    }
}
}
}



@Composable
fun GranularParameters(state: GrooveboxState, trackIndex: Int, onStateChange: (GrooveboxState) -> Unit, nativeLib: NativeLib, onRecordingSourceChange: (Int) -> Unit = {}) {
    var waveform by remember { mutableStateOf<FloatArray?>(null) }
    var playheads by remember { mutableStateOf(floatArrayOf()) }
    val themeColor = Color(0xFFFF00FF)
    // Animation loop for playheads and waveform
    val isRecordingSample = state.isRecordingSample && state.recordingTrackIndex == trackIndex
    
    LaunchedEffect(trackIndex, state.isRecordingSample) {
        waveform = nativeLib.getWaveform(trackIndex)
        while (true) {
            playheads = nativeLib.getGranularPlayheads(trackIndex)
            if (isRecordingSample) {
                waveform = nativeLib.getWaveform(trackIndex)
            }
            delay(33) // 30fps
        }
    }

    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        RecordingStrip(
            trackIndex = trackIndex,
            isRecording = isRecordingSample,
            isResampling = state.isResampling,
            waveform = waveform,
            track = state.tracks[trackIndex],
            onStartRecording = { 
                nativeLib.startRecordingSample(trackIndex) 
                onStateChange(state.copy(isRecordingSample = true, recordingTrackIndex = trackIndex))
            },
            onStopRecording = { 
                nativeLib.stopRecordingSample(trackIndex)
                onStateChange(state.copy(isRecordingSample = false, recordingTrackIndex = -1))
                waveform = nativeLib.getWaveform(trackIndex)
            },
            onRecordingSourceChange = onRecordingSourceChange,
            onWaveformRefresh = { waveform = nativeLib.getWaveform(trackIndex) },
            granularPlayheads = playheads,
            grainSize = state.tracks[trackIndex].parameters[406],
            nativeLib = nativeLib,
            state = state,
            onStateChange = onStateChange
        )
        
    // Single Row Layout: Cloud, Motion, Envelope, Advanced
    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
        // CLOUD (2x2)
        CompactParameterBox(title = "CLOUD", startColor = themeColor, modifier = Modifier.weight(1f)) {
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly) {
                Knob("POS", 0.0f, 400, state, onStateChange, nativeLib, knobSize = 34.dp)
                Knob("SIZE", 0.2f, 406, state, onStateChange, nativeLib, knobSize = 34.dp)
            }
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly) {
                Knob("DENS", 0.5f, 407, state, onStateChange, nativeLib, knobSize = 34.dp)
                Knob("SPRAY", 0.0f, 415, state, onStateChange, nativeLib, knobSize = 34.dp)
            }
        }

        // MOTION (2x3 -> 2x3 with REV restored)
        CompactParameterBox(title = "MOTION", startColor = themeColor, modifier = Modifier.weight(1f)) {
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly) {
                Knob("SPEED", 0.5f, 401, state, onStateChange, nativeLib, knobSize = 34.dp)
                Knob("JITTER", 0.0f, 411, state, onStateChange, nativeLib, knobSize = 34.dp)
                Knob("REV", 0.0f, 420, state, onStateChange, nativeLib, knobSize = 34.dp) // Restored REV
            }
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly) {
                Knob("PITCH", 0.5f, 410, state, onStateChange, nativeLib, knobSize = 34.dp)
                Knob("GLIDE", 0.0f, 355, state, onStateChange, nativeLib, knobSize = 34.dp)
                Spacer(modifier = Modifier.width(34.dp)) // Balance
            }
        }

        // ENVELOPE (2x2)
        CompactParameterBox(title = "ENVELOPE", startColor = themeColor, modifier = Modifier.weight(1f)) {
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly) {
                Knob("ATK", 0.01f, 425, state, onStateChange, nativeLib, knobSize = 34.dp)
                Knob("DEC", 0.1f, 426, state, onStateChange, nativeLib, knobSize = 34.dp)
            }
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly) {
                Knob("SUS", 1.0f, 427, state, onStateChange, nativeLib, knobSize = 34.dp)
                Knob("REL", 0.5f, 428, state, onStateChange, nativeLib, knobSize = 34.dp)
            }
        }
        
        // ADVANCED (2x2)
         CompactParameterBox(title = "ADVANCED", startColor = themeColor, modifier = Modifier.weight(1f)) {
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly) {
                Knob("DETUN", 0.0f, 416, state, onStateChange, nativeLib, knobSize = 34.dp)
                Knob("RAND", 0.0f, 417, state, onStateChange, nativeLib, knobSize = 34.dp)
            }
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly) {
                Knob("COUNT", 0.2f, 418, state, onStateChange, nativeLib, knobSize = 34.dp)
                Knob("WIDTH", 0.5f, 419, state, onStateChange, nativeLib, knobSize = 34.dp)
            }
        }
    }
    }
}


@Composable
fun SoundFontParameters(state: GrooveboxState, trackIndex: Int, onStateChange: (GrooveboxState) -> Unit, nativeLib: NativeLib, onRefresh: () -> Unit) {
    val track = state.tracks[trackIndex]
    // val context = LocalContext.current // Removed
    var showLoadDialog by remember { mutableStateOf(false) }
    var showPresetMenu by remember { mutableStateOf(false) }

    if (showLoadDialog) {
        val soundFontsDir = File(PersistenceManager.getLoomFolder(), "soundfonts")
        if (!soundFontsDir.exists()) soundFontsDir.mkdirs()
        
        NativeFileDialog(
            directory = soundFontsDir,
            onDismiss = { showLoadDialog = false },
            onFileSelected = { path ->
                try {
                    nativeLib.loadSoundFont(trackIndex, path)
                    val fileName = File(path).name.removeSuffix(".sf2").removeSuffix(".sf3").removeSuffix(".SF2").removeSuffix(".SF3")
                    onStateChange(state.copy(tracks = state.tracks.mapIndexed { idx, t ->
                        if (idx == trackIndex) t.copy(soundFontPath = path, soundFontPresetName = fileName, soundFontPresetIndex = 0, fm1KnobBindings = null, fm1StepKnobBindings = null) else t
                    }))
                    onRefresh()
                } catch (e: Exception) {
                    platformShowMessage("Error: ${e.message}")
                }
                showLoadDialog = false
            },
            state = state, isSave = false,
            extensions = listOf("sf2", "sf3", "SF2", "SF3")
        )
    }

    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        // Source Selection Row
        Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween) {
            Column(modifier = Modifier.weight(1f)) {
                Text("GET SOUNDFONTS", style = MaterialTheme.typography.labelSmall, color = Color(0xFFEEEEEE), fontWeight = FontWeight.Bold)
                // val context = LocalContext.current // Removed
                Text(
                    text = "Musical Artifacts",
                    style = MaterialTheme.typography.bodyMedium,
                    color = Color.Cyan,
                    modifier = Modifier.clickable {
                        openUrl("https://musical-artifacts.com")
                    },
                    textDecoration = TextDecoration.Underline 
                )
            }
            Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                Button(onClick = { showLoadDialog = true }, modifier = Modifier.height(28.dp), contentPadding = PaddingValues(horizontal = 8.dp), shape = RoundedCornerShape(4.dp), colors = ButtonDefaults.buttonColors(containerColor = Color.White.copy(alpha=0.1f)), border = BorderStroke(1.dp, Color.Cyan.copy(alpha=0.5f))) {
                    Text("SELECT", fontSize = 10.sp, color = Color.Cyan)
                }
                
                
                val presetCount = nativeLib.getSoundFontPresetCount(trackIndex)
                // Always show preset button if SoundFont is loaded (presetCount > 0)
                if (presetCount > 0) {
                    Button(onClick = { showPresetMenu = true }, modifier = Modifier.height(28.dp), contentPadding = PaddingValues(horizontal = 8.dp), shape = RoundedCornerShape(4.dp), colors = ButtonDefaults.buttonColors(containerColor = Color.White.copy(alpha=0.1f)), border = BorderStroke(1.dp, Color.Magenta.copy(alpha=0.5f))) {
                        Text("PRESET", fontSize = 10.sp, color = Color.Magenta)
                    }

                    // v3.0: Prev/Next preset navigation buttons
                    Button(
                        onClick = {
                            val currentIdx = track.soundFontPresetIndex
                            val newIdx = if (currentIdx <= 0) presetCount - 1 else currentIdx - 1
                            nativeLib.setSoundFontPreset(trackIndex, newIdx)
                            val name = nativeLib.getSoundFontPresetName(trackIndex, newIdx)
                            onStateChange(state.copy(tracks = state.tracks.mapIndexed { idx, t -> if (idx == trackIndex) t.copy(soundFontPresetIndex = newIdx, soundFontPresetName = name, fm1KnobBindings = null, fm1StepKnobBindings = null) else t }))
                        },
                        modifier = Modifier.height(28.dp).width(28.dp),
                        contentPadding = PaddingValues(0.dp),
                        shape = RoundedCornerShape(4.dp),
                        colors = ButtonDefaults.buttonColors(containerColor = Color.White.copy(alpha=0.1f)),
                        border = BorderStroke(1.dp, Color.Magenta.copy(alpha=0.3f))
                    ) {
                        Text("◀", fontSize = 12.sp, color = Color.Magenta)
                    }

                    Button(
                        onClick = {
                            val currentIdx = track.soundFontPresetIndex
                            val newIdx = if (currentIdx >= presetCount - 1) 0 else currentIdx + 1
                            nativeLib.setSoundFontPreset(trackIndex, newIdx)
                            val name = nativeLib.getSoundFontPresetName(trackIndex, newIdx)
                            onStateChange(state.copy(tracks = state.tracks.mapIndexed { idx, t -> if (idx == trackIndex) t.copy(soundFontPresetIndex = newIdx, soundFontPresetName = name, fm1KnobBindings = null, fm1StepKnobBindings = null) else t }))
                        },
                        modifier = Modifier.height(28.dp).width(28.dp),
                        contentPadding = PaddingValues(0.dp),
                        shape = RoundedCornerShape(4.dp),
                        colors = ButtonDefaults.buttonColors(containerColor = Color.White.copy(alpha=0.1f)),
                        border = BorderStroke(1.dp, Color.Magenta.copy(alpha=0.3f))
                    ) {
                        Text("▶", fontSize = 12.sp, color = Color.Magenta)
                    }
                }
            }
        }

        if (showPresetMenu) {
            Dialog(onDismissRequest = { showPresetMenu = false }) {
                Surface(shape = RoundedCornerShape(8.dp), color = Color(0xFF222222), modifier = Modifier.fillMaxWidth().padding(16.dp)) {
                    val presetCount = nativeLib.getSoundFontPresetCount(trackIndex)
                    LazyVerticalGrid(
                        columns = GridCells.Fixed(3),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                        modifier = Modifier.heightIn(max = 400.dp).padding(8.dp)
                    ) {
                        items(presetCount) { i ->
                            val name = nativeLib.getSoundFontPresetName(trackIndex, i)
                            val isSelected = i == track.soundFontPresetIndex
                            
                            Box(
                                modifier = Modifier
                                    .clip(RoundedCornerShape(4.dp))
                                    .background(if (isSelected) Color.Magenta.copy(alpha=0.3f) else Color.DarkGray)
                                    .border(1.dp, if (isSelected) Color.Magenta else Color.LightGray.copy(alpha=0.3f), RoundedCornerShape(4.dp))
                                    .clickable {
                                        nativeLib.setSoundFontPreset(trackIndex, i)
                                        onStateChange(state.copy(tracks = state.tracks.mapIndexed { idx, t -> if (idx == trackIndex) t.copy(soundFontPresetIndex = i, soundFontPresetName = name, fm1KnobBindings = null, fm1StepKnobBindings = null) else t }))
                                        showPresetMenu = false
                                    }
                                    .padding(8.dp),
                                contentAlignment = Alignment.Center
                            ) {
                                Text(name, style = MaterialTheme.typography.labelSmall, color = if (isSelected) Color.Magenta else Color.White, textAlign = TextAlign.Center, maxLines = 2, overflow = TextOverflow.Ellipsis)
                            }
                        }
                    }
                }
            }
        }

        // Performance Macros
        ParameterGroup("Performance Macros") {
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly) {
                Knob("LVL", 0.8f, 0, state, onStateChange, nativeLib, knobSize = 34.dp)
                Knob("CUT", 0.5f, 1, state, onStateChange, nativeLib, knobSize = 34.dp)
                Knob("RES", 0.0f, 2, state, onStateChange, nativeLib, knobSize = 34.dp)
                Knob("ENV", 0.0f, 3, state, onStateChange, nativeLib, knobSize = 34.dp)
                Knob("DET", 0.0f, 6, state, onStateChange, nativeLib, knobSize = 34.dp)
            }
        }

        // ADSR & Modulation
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            ParameterGroup("Amp Envelope", modifier = Modifier.weight(1.5f)) {
                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly) {
                    Knob("Atk", 0.01f, 100, state, onStateChange, nativeLib, knobSize = 32.dp)
                    Knob("Dec", 0.1f, 101, state, onStateChange, nativeLib, knobSize = 32.dp)
                    Knob("Sus", 0.8f, 102, state, onStateChange, nativeLib, knobSize = 32.dp)
                    Knob("Rel", 0.5f, 103, state, onStateChange, nativeLib, knobSize = 32.dp)
                }
            }
            ParameterGroup("Mod", modifier = Modifier.weight(1f)) {
                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly) {
                    Knob("GLIDE", 0.0f, 355, state, onStateChange, nativeLib, knobSize = 32.dp)
                    Knob("RAT", 0.0f, 7, state, onStateChange, nativeLib, knobSize = 32.dp)
                    Knob("DEP", 0.0f, 8, state, onStateChange, nativeLib, knobSize = 32.dp)
                }
            }
        }
    }
}


@Composable
fun GeneratorMappingRow(label: String, knobId: Int, currentGenId: Int, trackIndex: Int, nativeLib: NativeLib, onStateChange: (GrooveboxState) -> Unit, state: GrooveboxState) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(label, style = MaterialTheme.typography.labelMedium, color = Color.White)
        
        // This would be better as a dropdown, but for simplicity we'll use a numeric selector or a common set of generators
        val generators = listOf(
            "None" to 0,
            "Filter Cutoff" to 8,
            "Filter Q" to 9,
            "Mod LFO -> Pitch" to 5,
            "Vib LFO -> Pitch" to 7,
            "Mod Env -> Pitch" to 11,
            "Pan" to 17
        )
        
        var showMenu by remember { mutableStateOf(false) }
        Box {
            TextButton(onClick = { showMenu = true }) {
                Text(generators.find { it.second == currentGenId }?.first ?: "Gen $currentGenId", color = Color.Cyan)
            }
            DropdownMenu(expanded = showMenu, onDismissRequest = { showMenu = false }) {
                generators.forEach { (name, id) ->
                    DropdownMenuItem(
                        text = { Text(name) },
                        onClick = {
                            nativeLib.setSoundFontMapping(trackIndex, knobId, id)
                            val newMapping = (state.tracks[trackIndex].soundFontMapping ?: emptyMap()).toMutableMap()
                            newMapping[knobId] = id
                            val newTracks = state.tracks.mapIndexed { i, t ->
                                if (i == trackIndex) t.copy(soundFontMapping = newMapping) else t
                            }
                            onStateChange(state.copy(tracks = newTracks))
                            showMenu = false
                        }
                    )
                }
            }
        }
    }
}

@Composable
fun SamplerParameters(state: GrooveboxState, trackIndex: Int, onStateChange: (GrooveboxState) -> Unit, nativeLib: NativeLib, onRecordingSourceChange: (Int) -> Unit = {}) {
    val track = state.tracks[trackIndex]
    val themeColor = getEngineColor(track.engineType)
    val samplerMode = track.parameters[320] ?: 0f
    val isChops = samplerMode >= 0.49f
    val isSliceLock = (track.parameters[342] ?: 0.0f) > 0.5f
    val selectedSlice = track.selectedFmDrumInstrument
    
    fun getPId(globalId: Int, sliceSubId: Int): Int = 
        if (isSliceLock && isChops) 700 + selectedSlice * 10 + sliceSubId else globalId

    var waveform by remember { mutableStateOf<FloatArray?>(null) }
    var granularPlayheads by remember { mutableStateOf<FloatArray?>(null) }
    
    // Poll waveform
    val isRecordingSample = state.isRecordingSample && state.recordingTrackIndex == trackIndex

    LaunchedEffect(trackIndex, track.engineType, isRecordingSample) {
        waveform = nativeLib.getWaveform(trackIndex)
        while(true) {
            if (isRecordingSample) {
                waveform = nativeLib.getWaveform(trackIndex)
            }
            granularPlayheads = nativeLib.getGranularPlayheads(trackIndex)
            delay(33) 
        }
    }
    
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        // TestTriggerButton removed (redundant)

        RecordingStrip(
            trackIndex = trackIndex,
            isRecording = isRecordingSample,
            isResampling = state.isResampling, 
            waveform = waveform,
            track = track,
            onStartRecording = { nativeLib.startRecordingSample(trackIndex); onStateChange(state.copy(isRecordingSample = true, recordingTrackIndex = trackIndex)) },
            onStopRecording = { 
                nativeLib.stopRecordingSample(trackIndex)
                onStateChange(state.copy(isRecordingSample = false, recordingTrackIndex = -1))
                waveform = nativeLib.getWaveform(trackIndex)
            },
            onRecordingSourceChange = onRecordingSourceChange,
            onWaveformRefresh = { waveform = nativeLib.getWaveform(trackIndex) },
            trimStart = track.parameters[330],
            trimEnd = track.parameters[331],
            slices = ((track.parameters[340] ?: 0f) * 15f).toInt() + 1,
            granularPlayheads = granularPlayheads,
            grainSize = track.parameters[406],
            nativeLib = nativeLib,
            state = state,
            onStateChange = onStateChange,
            selectedSlice = if (isChops) selectedSlice else null
        )

        // Testing row removed (redundant)

        val themeColor = Color(0xFFFFD700) // Gold for Sampler

            // ONE ROW: SAMPLE EDITS, SYNTHESIS, ENVELOPE
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                // SAMPLE EDITS (Compact 2x2)
                CompactParameterBox(title = "SAMPLE EDITS", startColor = themeColor, modifier = Modifier.weight(0.8f)) {
                     Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly, verticalAlignment = Alignment.CenterVertically) {
                        Knob("START", 0.0f, 330, state, onStateChange, nativeLib, knobSize = 32.dp, onValueChangeOverride = { newStart ->
                            val currentEnd = track.parameters[331] ?: 1.0f
                            val clampedStart = newStart.coerceAtMost(currentEnd - 0.001f)
                            nativeLib.setParameter(trackIndex, 330, clampedStart)
                            onStateChange(state.copy(tracks = state.tracks.mapIndexed { idx, t -> 
                                if (idx == trackIndex) t.copy(parameters = t.parameters + (330 to clampedStart)) else t 
                            }))
                        })
                        Knob("END", 1.0f, 331, state, onStateChange, nativeLib, knobSize = 32.dp, onValueChangeOverride = { newEnd ->
                            val currentStart = track.parameters[330] ?: 0.0f
                            val clampedEnd = newEnd.coerceAtLeast(currentStart + 0.001f)
                            nativeLib.setParameter(trackIndex, 331, clampedEnd)
                            onStateChange(state.copy(tracks = state.tracks.mapIndexed { idx, t -> 
                                if (idx == trackIndex) t.copy(parameters = t.parameters + (331 to clampedEnd)) else t 
                            }))
                        })

                        // LOCK Button
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            Text("LOCK", style = MaterialTheme.typography.labelSmall, color = Color.White.copy(alpha=0.6f))
                            Spacer(Modifier.height(4.dp))
                            Box(
                                modifier = Modifier
                                    .size(32.dp)
                                    .clip(RoundedCornerShape(4.dp))
                                    .background(if (isSliceLock) themeColor else Color.White.copy(alpha = 0.1f))
                                    .clickable {
                                        val newVal = if (isSliceLock) 0.0f else 1.0f
                                        nativeLib.setParameter(trackIndex, 342, newVal)
                                        onStateChange(state.copy(tracks = state.tracks.mapIndexed { idx, t -> if (idx == trackIndex) t.copy(parameters = t.parameters + (342 to newVal)) else t }))
                                    },
                                contentAlignment = Alignment.Center
                            ) {
                                Icon(
                                    imageVector = if (isSliceLock) Icons.Filled.Lock else Icons.Filled.Lock,
                                    contentDescription = null,
                                    tint = if (isSliceLock) Color.Black else Color.White,
                                    modifier = Modifier.size(16.dp)
                                )
                            }
                        }
                     }
                     Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly) {
                        Knob("SLICE", 0.0f, 340, state, onStateChange, nativeLib, knobSize = 32.dp, valueFormatter = { v -> "${(v * 15f).toInt() + 1}" })
                        Knob("SEL", 0.0f, 341, state, onStateChange, nativeLib, knobSize = 32.dp, 
                            valueFormatter = { v -> if (v < 0) "OFF" else "${(v * 15.99f).toInt() + 1}" },
                            onValueChangeOverride = { v ->
                                val selIdx = (v * 15.99f).toInt().coerceIn(0, 15)
                                nativeLib.setParameter(trackIndex, 341, v)
                                onStateChange(state.copy(tracks = state.tracks.mapIndexed { idx, t -> 
                                    if (idx == trackIndex) t.copy(
                                        parameters = t.parameters + (341 to v),
                                        selectedFmDrumInstrument = selIdx
                                    ) else t 
                                }))
                            }
                        )
                        Knob("MODE", 0.0f, 320, state, onStateChange, nativeLib, knobSize = 32.dp, valueFormatter = { v ->
                            when(v) {
                                in 0.0f..0.16f -> "ONE"
                                in 0.161f..0.32f -> "SUS"
                                in 0.321f..0.49f -> "LOOP"
                                in 0.491f..0.65f -> "CHOP" // Gate Chop
                                in 0.651f..0.82f -> "1-CP" // One-Shot Chop
                                in 0.821f..0.94f -> "L-CP" // Loop Chop
                                else -> "SCRUB"
                            }
                        })
                     }
                }

                // SYNTHESIS (Wide)
                CompactParameterBox(title = "SYNTHESIS", startColor = themeColor, modifier = Modifier.weight(1.2f)) {
                     Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly) {
                        Knob("PITCH", 0.5f, getPId(300, 0), state, onStateChange, nativeLib, knobSize = 32.dp, detentValue = 0.5f)
                        Knob("SPEED", 0.5f, 302, state, onStateChange, nativeLib, knobSize = 32.dp, detentValue = 0.5f)
                        Knob("STRCH", 0.25f, 301, state, onStateChange, nativeLib, knobSize = 32.dp, detentValue = 0.25f)
                    }
                    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly, verticalAlignment = Alignment.CenterVertically) {
                        Knob("FILT", 1.0f, getPId(1, 1), state, onStateChange, nativeLib, knobSize = 32.dp)
                        Knob("RESO", 0.0f, getPId(2, 2), state, onStateChange, nativeLib, knobSize = 32.dp)
                        
                        // REVERSE Button
                        val revPid = getPId(351, 3)
                        val isReverse = (track.parameters[revPid] ?: 0.0f) > 0.5f
                        Button(
                            onClick = {
                                val newVal = if (isReverse) 0.0f else 1.0f
                                nativeLib.setParameter(trackIndex, revPid, newVal)
                                onStateChange(state.copy(tracks = state.tracks.mapIndexed { idx, t -> if (idx == trackIndex) t.copy(parameters = t.parameters + (revPid to newVal)) else t }))
                            },
                            modifier = Modifier.size(width = 32.dp, height = 32.dp),
                            contentPadding = PaddingValues(0.dp),
                            colors = ButtonDefaults.buttonColors(containerColor = if (isReverse) themeColor else Color.DarkGray),
                            shape = CircleShape,
                            border = BorderStroke(1.dp, Color.White.copy(alpha = 0.5f))
                        ) {
                            Text("REV", fontSize = 8.sp, color = if (isReverse) Color.Black else Color.White, fontWeight = FontWeight.Bold)
                        }
                        
                        Knob("GLIDE", 0.0f, 355, state, onStateChange, nativeLib, knobSize = 32.dp)
                    }
                }
                
                // ENVELOPE (Compact 2x2)
                 CompactParameterBox(title = "ENVELOPE", startColor = themeColor, modifier = Modifier.weight(0.8f)) {
                      Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly) {
                          Knob("Atk", 0.01f, getPId(310, 4), state, onStateChange, nativeLib, knobSize = 32.dp)
                          Knob("Dec", 0.2f, getPId(311, 5), state, onStateChange, nativeLib, knobSize = 32.dp)
                      }
                      Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly) {
                          Knob("Sus", 1.0f, getPId(312, 6), state, onStateChange, nativeLib, knobSize = 32.dp)
                          Knob("Rel", 0.2f, getPId(313, 7), state, onStateChange, nativeLib, knobSize = 32.dp)
                          Knob("AMT", 0.5f, 314, state, onStateChange, nativeLib, knobSize = 32.dp, detentValue = 0.5f)
                      }
                 }
            }
    }
}


@Composable
fun MidiParameters(state: GrooveboxState, trackIndex: Int, onStateChange: (GrooveboxState) -> Unit, nativeLib: NativeLib) {
    val track = state.tracks[trackIndex]
    
    Column(modifier = Modifier.fillMaxWidth().padding(8.dp)) {
        ParameterGroup("MIDI Configuration") {
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                // MIDI IN Selector
                Knob(
                    label = "MIDI IN",
                    initialValue = track.midiInChannel.toFloat() / 17f,
                    parameterId = 800,
                    state = state,
                    onStateChange = { newState ->
                        val newVal = newState.tracks[trackIndex].midiInChannel
                        nativeLib.setParameter(trackIndex, 800, newVal.toFloat())
                        onStateChange(newState)
                    },
                    nativeLib = nativeLib,
                    onValueChangeOverride = { v ->
                        val chan = (v * 17.1f).toInt().coerceIn(0, 17)
                        val newTracks = state.tracks.mapIndexed { i, t ->
                            if (i == trackIndex) t.copy(midiInChannel = chan) else t
                        }
                        nativeLib.setParameter(trackIndex, 800, chan.toFloat())
                        onStateChange(state.copy(tracks = newTracks, focusedValue = if (chan == 0) "IN: NONE" else if (chan == 17) "IN: ALL" else "IN: CH $chan"))
                    },
                    overrideValue = track.midiInChannel.toFloat() / 17f,
                    valueFormatter = { v ->
                        val chan = (v * 17.1f).toInt().coerceIn(0, 17)
                        if (chan == 0) "IN: NONE" else if (chan == 17) "IN: ALL" else "IN: CH $chan"
                    }
                )

                // MIDI OUT Selector
                Knob(
                    label = "MIDI OUT",
                    initialValue = (track.midiOutChannel - 1).toFloat() / 15f,
                    parameterId = 801,
                    state = state,
                    onStateChange = { newState ->
                        val newVal = newState.tracks[trackIndex].midiOutChannel
                        nativeLib.setParameter(trackIndex, 801, newVal.toFloat())
                        onStateChange(newState)
                    },
                    nativeLib = nativeLib,
                    onValueChangeOverride = { v ->
                        val chan = (v * 15.1f).toInt().coerceIn(0, 15) + 1
                        val newTracks = state.tracks.mapIndexed { i, t ->
                            if (i == trackIndex) t.copy(midiOutChannel = chan) else t
                        }
                        nativeLib.setParameter(trackIndex, 801, chan.toFloat())
                        onStateChange(state.copy(tracks = newTracks, focusedValue = "OUT: CH $chan"))
                    },
                    overrideValue = (track.midiOutChannel - 1).toFloat() / 15f,
                    valueFormatter = { v ->
                         val chan = (v * 15.1f).toInt().coerceIn(0, 15) + 1
                         "OUT: CH $chan"
                    }
                )
            }
        }
    }
}


@Composable
fun AudioInParameters(state: GrooveboxState, trackIndex: Int, onStateChange: (GrooveboxState) -> Unit, nativeLib: NativeLib) {
    val track = state.tracks[trackIndex]
    
    Column(modifier = Modifier.fillMaxWidth().padding(8.dp)) {
        Text("AUDIO IN ENGINE", style = MaterialTheme.typography.titleMedium, color = getEngineColor(EngineType.AUDIO_IN))
        Spacer(modifier = Modifier.height(8.dp))
        
        // Input Source Selection
        InputSourceSelector(state = state, onStateChange = onStateChange, nativeLib = nativeLib)
        Spacer(modifier = Modifier.height(16.dp))

        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Knob(label = "GAIN", initialValue = 0.8f, parameterId = 121, state = state, onStateChange = onStateChange, nativeLib = nativeLib)
            Knob(label = "FOLD", initialValue = 0.0f, parameterId = 122, state = state, onStateChange = onStateChange, nativeLib = nativeLib)
            
            // Filter Mode Toggle
            val currentMode = (track.parameters[123] ?: 0.0f).toInt().coerceIn(0, 2)
            Column(modifier = Modifier.weight(1f), horizontalAlignment = Alignment.CenterHorizontally) {
                Text("FILTER", style = MaterialTheme.typography.labelSmall, color = Color(0xFFEEEEEE))
                Spacer(modifier = Modifier.height(4.dp))
                Button(
                    onClick = { 
                        val nextMode = (currentMode + 1) % 3
                        val newVal = nextMode.toFloat()
                        nativeLib.setParameter(trackIndex, 123, newVal)
                        onStateChange(state.copy(tracks = state.tracks.mapIndexed { i, t -> if (i == trackIndex) t.copy(parameters = t.parameters + (123 to newVal)) else t }))
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = Color.DarkGray),
                    modifier = Modifier.height(36.dp).fillMaxWidth(),
                    contentPadding = PaddingValues(0.dp)
                ) {
                    Text(when(currentMode) { 0 -> "LP"; 1 -> "HP"; else -> "BP" }, fontSize = 10.sp, color = Color.White)
                }
            }

            // TOGGLE: GATED vs OPEN
            val isGated = (track.parameters[120] ?: 0.0f) > 0.5f
            Column(modifier = Modifier.weight(1f), horizontalAlignment = Alignment.CenterHorizontally) {
                Text("MODE", style = MaterialTheme.typography.labelSmall, color = Color(0xFFEEEEEE))
                Spacer(modifier = Modifier.height(4.dp))
                Button(
                    onClick = { 
                        val newVal = if (isGated) 0.0f else 1.0f
                        nativeLib.setParameter(trackIndex, 120, newVal)
                        onStateChange(state.copy(tracks = state.tracks.mapIndexed { i, t -> if (i == trackIndex) t.copy(parameters = t.parameters + (120 to newVal)) else t }))
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = if (isGated) getEngineColor(EngineType.AUDIO_IN) else Color.DarkGray),
                    modifier = Modifier.height(36.dp).fillMaxWidth()
                ) {
                    Text(if (isGated) "OPEN" else "GATED", fontSize = 10.sp, color = if (isGated) Color.Black else Color.White)
                }
            }
        }

        Spacer(modifier = Modifier.height(16.dp))
        Text("CHARACTER EQ", style = MaterialTheme.typography.labelMedium, color = Color(0xFFEEEEEE))
        Spacer(modifier = Modifier.height(8.dp))
        Row(
            modifier = Modifier.fillMaxWidth().height(100.dp),
            horizontalArrangement = Arrangement.SpaceEvenly,
            verticalAlignment = Alignment.CenterVertically
        ) {
            val eqBands = listOf("LOW", "L-MID", "MID", "H-MID", "HIGH")
            eqBands.forEachIndexed { i, label ->
                val paramId = 1530 + i
                val value = track.parameters[paramId] ?: 0.5f
                VerticalSlider(
                    label = label,
                    value = value,
                    color = getEngineColor(EngineType.AUDIO_IN),
                    height = 70.dp,
                    width = 28.dp,
                    onValueChange = { newVal: Float ->
                        nativeLib.setParameter(trackIndex, paramId, newVal)
                        onStateChange(state.copy(tracks = state.tracks.mapIndexed { idx, t -> 
                            if (idx == trackIndex) t.copy(parameters = t.parameters + (paramId to newVal)) else t 
                        }))
                    }
                )
            }
        }
        
        Spacer(modifier = Modifier.height(16.dp))
        
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Knob(label = "CUTOFF", initialValue = 0.5f, parameterId = 112, state = state, onStateChange = onStateChange, nativeLib = nativeLib)
            Knob(label = "RESON", initialValue = 0.0f, parameterId = 113, state = state, onStateChange = onStateChange, nativeLib = nativeLib)
            Knob(label = "ENV AMT", initialValue = 0.0f, parameterId = 118, state = state, onStateChange = onStateChange, nativeLib = nativeLib)
            Spacer(modifier = Modifier.weight(1f))
        }

        Spacer(modifier = Modifier.height(16.dp))
        Text("AMP ENVELOPE", style = MaterialTheme.typography.labelMedium, color = Color(0xFFEEEEEE))
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Knob(label = "Atk", initialValue = 0.1f, parameterId = 100, state = state, onStateChange = onStateChange, nativeLib = nativeLib)
            Knob(label = "Dec", initialValue = 0.5f, parameterId = 101, state = state, onStateChange = onStateChange, nativeLib = nativeLib)
            Knob(label = "Sus", initialValue = 0.8f, parameterId = 102, state = state, onStateChange = onStateChange, nativeLib = nativeLib)
            Knob(label = "Rel", initialValue = 0.2f, parameterId = 103, state = state, onStateChange = onStateChange, nativeLib = nativeLib)
        }

        Spacer(modifier = Modifier.height(16.dp))
        Text("FILTER ENVELOPE", style = MaterialTheme.typography.labelMedium, color = Color(0xFFEEEEEE))
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Knob(label = "Atk", initialValue = 0.01f, parameterId = 114, state = state, onStateChange = onStateChange, nativeLib = nativeLib)
            Knob(label = "Dec", initialValue = 0.1f, parameterId = 115, state = state, onStateChange = onStateChange, nativeLib = nativeLib)
            Knob(label = "Sus", initialValue = 0.0f, parameterId = 116, state = state, onStateChange = onStateChange, nativeLib = nativeLib)
            Knob(label = "Rel", initialValue = 0.5f, parameterId = 117, state = state, onStateChange = onStateChange, nativeLib = nativeLib)
        }
    }
}

@Composable
fun GlobalFxSends(state: GrooveboxState, trackIndex: Int, onStateChange: (GrooveboxState) -> Unit, nativeLib: NativeLib, paramIdMapper: ((Int) -> Int)? = null) {
    val activeSlots = state.fxChainSlots.mapIndexedNotNull { idx, fxId -> if (fxId != -1) idx to fxId else null }
    val fxNames = mapOf(
        0 to "ODRV", 1 to "BIT", 2 to "CHOR", 3 to "PHAS", 4 to "WOB",
        5 to "DLY", 6 to "REV", 7 to "SLIC", 8 to "CMP",
        9 to "HP", 10 to "LP", 11 to "FLG", 12 to "FLT1", 13 to "TAPE", 14 to "OCT",
        15 to "FLT2", 16 to "FLT3"
    )

    if (activeSlots.isNotEmpty()) {
        ParameterGroup("FX SENDS (CHAIN)") {
            Row(modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                activeSlots.forEach { (slotIdx, fxId) ->
                    val paramId = paramIdMapper?.invoke(slotIdx) ?: (2000 + (slotIdx * 10))
                    val name = fxNames[fxId] ?: "FX$fxId"
                    Knob(name, 0.0f, paramId, state, onStateChange, nativeLib, knobSize = 40.dp)
                }
            }
        }
    } else {
        Text("No Active FX in Chain", color = Color(0xFFEEEEEE), fontSize = 10.sp, modifier = Modifier.padding(8.dp))
    }
}

@Composable
fun GlobalActiveSends(state: GrooveboxState, trackIndex: Int, onStateChange: (GrooveboxState) -> Unit, nativeLib: NativeLib, paramIdMapper: ((Int) -> Int)? = null) {
    val fxSends = nativeLib.getFxSends(trackIndex) // Should be 17 floats
    val fxNames = mapOf(
        0 to "ODRV", 1 to "BIT", 2 to "CHOR", 3 to "PHAS", 4 to "WOB",
        5 to "DLY", 6 to "REV", 7 to "SLIC", 8 to "CMP",
        9 to "HP", 10 to "LP", 11 to "FLG", 12 to "FLT1", 13 to "TAPE", 14 to "OCT",
        15 to "FLT2", 16 to "FLT3"
    )

    // Filter for Active Sends (> 0.01f)
    // Filter for Active Sends (> 0.01f)
    val activeIndices = fxSends.indices.filter { i -> fxSends[i] > 0.01f }

    if (activeIndices.isNotEmpty()) {
        ParameterGroup("ACTIVE FX SENDS (ANY)") {
            Row(modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                activeIndices.forEach { fxId ->
                    val name = fxNames[fxId] ?: "FX\$fxId"
                    val slotIdx = state.fxChainSlots.indexOf(fxId)
                    
                    if (slotIdx != -1) {
                         val paramId = paramIdMapper?.invoke(slotIdx) ?: (2000 + (slotIdx * 10))
                         Knob(name, 0.0f, paramId, state, onStateChange, nativeLib, knobSize = 40.dp, overrideValue = fxSends[fxId])
                    } else {
                         // Read-only display of send level if not in slot
                         Knob(
                            label = name, 
                            initialValue = 0f, 
                            parameterId = -1, 
                            state = state, 
                            onStateChange = onStateChange, 
                            nativeLib = nativeLib, 
                            knobSize = 40.dp,
                            overrideValue = fxSends[fxId],
                            overrideColor = Color(0xFFEEEEEE)
                        )
                    }
                }
            }
        }
    } else {
        // Optional: Hide or show "No Active Sends"
    }
}







