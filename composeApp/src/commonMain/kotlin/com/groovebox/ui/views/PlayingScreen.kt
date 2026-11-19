package com.groovebox.ui.views


import com.groovebox.ui.LocalPlatformInfo
import androidx.compose.animation.core.*
import androidx.compose.foundation.*
import androidx.compose.foundation.gestures.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.*
import androidx.compose.foundation.lazy.grid.*
import androidx.compose.foundation.lazy.staggeredgrid.*
import androidx.compose.foundation.shape.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.text.style.TextOverflow
import com.groovebox.ui.theme.getEngineColor
import com.groovebox.ui.components.Knob
import com.groovebox.ui.components.EngineIcon
import com.groovebox.ui.components.EngineIcon
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.*
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.draw.scale
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.*
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.animation.*
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.input.pointer.pointerInput
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

import com.groovebox.GrooveboxState
import com.groovebox.NativeLib
import com.groovebox.EngineType
import com.groovebox.ArpMode
import com.groovebox.ScaleLogic
import com.groovebox.ScaleType
import com.groovebox.midi.EmpledManager
import com.groovebox.midi.MidiManager
import com.groovebox.ui.components.Knob
import com.groovebox.ui.components.EngineIcon
import com.groovebox.ui.theme.getEngineColor
import com.groovebox.ui.LocalFocusedValue
import com.groovebox.utils.isPerVoiceModDest
import com.groovebox.utils.padRows
import com.groovebox.utils.padCols
import com.groovebox.utils.padCount
import com.groovebox.utils.padNoteFor
import com.groovebox.utils.shortLabel
import com.groovebox.utils.nextGridStep
import com.groovebox.utils.FX_BUS_NAMES
import com.groovebox.*
// IMPORTANT: We need to copy isBlackKey and getNoteLabel here or import them if they remain in MainActivity (which is bad practice if extracted).
// For now I will redefine them here as private or internal to unlink dependency.

fun isBlackKey(midiNote: Int): Boolean {
    val noteInOctave = midiNote % 12
    return noteInOctave == 1 || noteInOctave == 3 || noteInOctave == 6 || noteInOctave == 8 || noteInOctave == 10
}

fun getNoteLabel(midiNote: Int): String {
    val names = listOf("C", "C#", "D", "D#", "E", "F", "F#", "G", "G#", "A", "A#", "B")
    return names[midiNote % 12] + (midiNote / 12 - 1)
}

@Composable
fun PlayingPad(
    padIndex: Int,
    note: Int,
    padSize: Dp,
    padColor: Color,
    isPlaying: Boolean,
    currentStep: Int,
    nativeLib: NativeLib,
    latestState: GrooveboxState,
    onStateChange: (GrooveboxState) -> Unit,
    empledManager: EmpledManager? = null,
    isChopMode: Boolean = false,
    isNoteActive: Boolean = false,
    targetTrackIndex: Int? = null,
    zone: SplitZone? = null,
    playheadEnabled: Boolean = true,
    shape: Shape = RoundedCornerShape(8.dp),
    isSolidStyle: Boolean = false,
    customWidth: Dp? = null,
    customHeight: Dp? = null
) {
                // Fix for stale state capture: Maintain reference to the most recent state
                val currentState by rememberUpdatedState(latestState)
                var isLocallyPressed by remember { mutableStateOf(false) }
                // Visual state follows local press but with release delay
                var isVisuallyPressed by remember { mutableStateOf(false) }
                
                LaunchedEffect(isLocallyPressed) {
                    if (isLocallyPressed) {
                        isVisuallyPressed = true
                    } else {
                        // User requested ~100ms highlight tail
                        delay(100)
                        isVisuallyPressed = false
                    }
                }

                val isHeld = latestState.heldNotes.contains(note)
                val isMidiTriggered = latestState.lastMidiNote == note && latestState.lastMidiVelocity > 0
                val padTrackIndex = targetTrackIndex ?: latestState.selectedTrackIndex
                val isTrackDisabled = latestState.tracks.getOrNull(padTrackIndex)?.isDisabled ?: false
                val isActive = !isTrackDisabled && (isVisuallyPressed || isHeld || isMidiTriggered || isNoteActive) // Combined "Active" state
                
                // LED Sync for EMP16
                LaunchedEffect(isVisuallyPressed, isHeld, isMidiTriggered, isPlaying, currentStep, latestState.currentSequencerBank) {
                    if (playheadEnabled && latestState.currentSequencerBank == 0) {
                        val finalColor = if (isActive) padColor 
                                        else if (isPlaying && (currentStep % 16) == padIndex) androidx.compose.ui.graphics.Color.White
                                        else padColor.copy(alpha = 0.2f)
                        
                        empledManager?.updatePadColorCompose(padIndex, finalColor)
                    }
                }

                val cols = if (latestState.gridMode == GridMode.GRID_6X6) 6 else 4
                val row = padIndex / cols
                val col = padIndex % cols
                val isPlayheadHighlight = playheadEnabled && if (latestState.gridMode == GridMode.GRID_6X6) {
                    // Map playhead to the top-left 4x4 grid of the 6x6 (representing first 16 steps)
                    row < 4 && col < 4 && (currentStep % 16) == (row * 4 + col)
                } else {
                    currentStep % 16 == padIndex
                }

                val backgroundColor = if (isSolidStyle) {
                    if (isActive) Color.Black else padColor.copy(alpha = if (isTrackDisabled) 0.25f else 0.8f)
                } else {
                    if (isActive) padColor.copy(alpha = 0.9f)
                    else if (isPlaying && isPlayheadHighlight) Color.White.copy(alpha = 0.3f)
                    else if (latestState.tracks.getOrNull(padTrackIndex)?.engineType == EngineType.FM_DRUM && 
                             latestState.tracks[padTrackIndex].selectedFmDrumInstrument == (note - 60)) padColor.copy(alpha = 0.2f)
                    else Color.Transparent
                }

                val borderColor = if (isSolidStyle) {
                    if (isActive) padColor else Color.Transparent // Active = Black Body + Color Border
                } else {
                    padColor.copy(alpha = if (isTrackDisabled) 0.25f else 0.6f)
                }
                
                val borderWidth = if (isSolidStyle && isActive) 3.dp else 1.5.dp

                Box(
                    modifier = Modifier
                        .size(width = customWidth ?: padSize, height = customHeight ?: padSize)
                        .background(backgroundColor, shape)
                        .border(borderWidth, borderColor, shape),
                    contentAlignment = Alignment.Center
                ) {
                    // Redundant TopStart label removed as per v1.3.1 request
                    
                    Box(
                        modifier = Modifier
                            .fillMaxSize()
                            .pointerInput(padIndex, note, targetTrackIndex, zone?.polyMod,
                                zone?.modTargetId, zone?.modIntensity, zone?.bendRange) {
                                awaitPointerEventScope {
                                        while (true) {
                                            val down = awaitFirstDown()
                                            isLocallyPressed = true
                                            val startPos = down.position
                                            val pointerId = down.id
                                            
                                            val currentTIdx = targetTrackIndex ?: currentState.selectedTrackIndex
                                            val currentBank = currentState.currentSequencerBank
                                            val triggeredNote = note
                                            
                                            val bankTarget = zone?.modTargetId?.takeIf { it >= 0 }
                                            val modParamId = bankTarget
                                                ?: currentState.tracks[currentTIdx].padModTargetId
                                            val initialModValue = if (modParamId != null) {
                                                if (modParamId >= 2000) {
                                                    currentState.macros.getOrNull(modParamId - 2000)?.value ?: 0.5f
                                                } else {
                                                    currentState.tracks[currentTIdx].parameters[modParamId] ?: 0.5f
                                                }
                                            } else 0.5f

                                            // Per-voice modulation needs a sounding voice; with
                                            // the arp on the pad only feeds the arp, so fall back
                                            // to the v1 global path. Macro/FX/global
                                            // destinations cannot be per-voice either.
                                            val arpOn = currentState.tracks[currentTIdx].arpConfig.mode != ArpMode.OFF
                                            val perVoiceDest = isPerVoiceModDest(
                                                currentState.tracks[currentTIdx].engineType,
                                                modParamId
                                            )
                                            val usePolyMod = (zone?.polyMod ?: currentState.polyPadMod) &&
                                                !arpOn && perVoiceDest
                                            val bendRange = zone?.bendRange
                                                ?: (if (isChopMode) 12f else 2f)
                                            val modIntensity = zone?.modIntensity ?: 1f
                                            var noteTriggered = false

                                            try {
                                                if (currentState.jumpModeWaitingForTap && targetTrackIndex == null) {
                                                    nativeLib.jumpToStep(currentBank * 16 + padIndex)
                                                    onStateChange(currentState.copy(jumpModeWaitingForTap = false))
                                                } else {
                                                    if (triggeredNote in 0..127 &&
                                                        !currentState.tracks[currentTIdx].isDisabled) {
                                                        nativeLib.triggerNote(currentTIdx, triggeredNote, 100)
                                                        noteTriggered = true
                                                        if (usePolyMod) {
                                                            nativeLib.setPadModRouting(
                                                                currentTIdx, 0, bendRange,
                                                                modParamId, modIntensity, true
                                                            )
                                                            nativeLib.beginVoicePadMod(currentTIdx, triggeredNote)
                                                        }
                                                    }

                                                    // GESTURE HANDLING (X: Pitch Bend, Y: Modulation)
                                                    var currentBend = 0.0f
                                                    var currentModDelta = 0.0f

                                                    // Track movement
                                                    while (true) {
                                                        val event = awaitPointerEvent()
                                                        val change = event.changes.find { it.id == pointerId }

                                                        if (change != null && change.pressed) {
                                                            val currentPos = change.position
                                                            val dx = currentPos.x - startPos.x

                                                            // X: full pad width = the configured bend range.
                                                            val normX = (dx / (size.width.toFloat() * 0.5f))
                                                                .coerceIn(-1f, 1f)
                                                            val newBend = normX * bendRange *
                                                                (currentState.padXAttenuation ?: 1.0f)

                                                            // Y: signed delta relative to the gesture
                                                            // start (-1..1), so releasing snaps back.
                                                            val dyRaw = startPos.y - currentPos.y // Standard: Up is positive
                                                            val dy = if (currentState.invertPadY) -dyRaw else dyRaw
                                                            val newModDelta = ((dy / size.height.toFloat()) *
                                                                (currentState.padYAttenuation ?: 1.0f))
                                                                .coerceIn(-1f, 1f)

                                                            if (newBend != currentBend || newModDelta != currentModDelta) {
                                                                val bendChanged = newBend != currentBend
                                                                val modChanged = newModDelta != currentModDelta
                                                                currentBend = newBend
                                                                currentModDelta = newModDelta

                                                                if (usePolyMod) {
                                                                    nativeLib.setVoicePadMod(
                                                                        currentTIdx, triggeredNote,
                                                                        if (bendRange > 0.001f) newBend / bendRange else 0f,
                                                                        newModDelta
                                                                    )
                                                                } else {
                                                                    if (bendChanged) {
                                                                        nativeLib.setPitchBend(currentTIdx, currentBend)

                                                                        // Live record pitch bend as parameter lock
                                                                        if (currentState.isRecording && currentState.isPlaying && bendRange > 0.001f) {
                                                                            val liveStep = nativeLib.getCurrentStep(currentTIdx)
                                                                            val bendNormalized = ((currentBend + bendRange) / (2f * bendRange)).coerceIn(0f, 1f)
                                                                            nativeLib.setParameterLock(currentTIdx, liveStep, 355, bendNormalized)
                                                                        }
                                                                    }

                                                                    if (modChanged && modParamId != null) {
                                                                        val newMod = (initialModValue + currentModDelta * modIntensity).coerceIn(0f, 1f)
                                                                        if (modParamId >= 2000) {
                                                                            nativeLib.setMacroValue(modParamId - 2000, newMod)
                                                                        } else {
                                                                            nativeLib.setParameter(currentTIdx, modParamId, newMod)
                                                                        }

                                                                        // Live record Y modulation as parameter lock
                                                                        if (currentState.isRecording && currentState.isPlaying) {
                                                                            val liveStep = nativeLib.getCurrentStep(currentTIdx)
                                                                            nativeLib.setParameterLock(currentTIdx, liveStep, modParamId, newMod)
                                                                        }
                                                                    }
                                                                    // Always update for Routing Source (v2.2.5 MIDI PADS Source)
                                                                    nativeLib.setPadMod(currentTIdx,
                                                                        (initialModValue + currentModDelta * modIntensity).coerceIn(0f, 1f))
                                                                }
                                                            }

                                                            change.consume()
                                                        } else {
                                                            break
                                                        }
                                                    }
                                                }
                                            } finally {
                                                isLocallyPressed = false
                                                val currentTIdx = targetTrackIndex ?: currentState.selectedTrackIndex

                                                if (usePolyMod) {
                                                    if (noteTriggered) {
                                                        nativeLib.endVoicePadMod(currentTIdx, triggeredNote)
                                                    }
                                                } else {
                                                    nativeLib.setPitchBend(currentTIdx, 0.0f) // Reset bend on release

                                                    // Y-Axis: Reset modulation on release (Snapback)
                                                    if (modParamId != null) {
                                                        if (modParamId >= 2000) {
                                                            nativeLib.setMacroValue(modParamId - 2000, initialModValue)
                                                        } else {
                                                            nativeLib.setParameter(currentTIdx, modParamId, initialModValue)
                                                        }
                                                    }
                                                    nativeLib.setPadMod(currentTIdx, initialModValue)
                                                }

                                                if (!currentState.jumpModeWaitingForTap) {
                                                    if (triggeredNote in 0..127) {
                                                        nativeLib.releaseNote(currentTIdx, triggeredNote)
                                                    }
                                                }
                                            }
                                        }
                                    }
                                }
                            ,
                            contentAlignment = Alignment.Center
                        ) {
                            val label = when (latestState.tracks.getOrNull(padTrackIndex)?.engineType ?: latestState.tracks[latestState.selectedTrackIndex].engineType) {
                                EngineType.FM_DRUM -> {
                                    val names = listOf("Kick", "Snare", "Tom", "HH", "OHH", "CYMB", "PERC", "NOISE")
                                    names.getOrElse(note - 60) { "Pad $padIndex" }
                                }
                                EngineType.ANALOG_DRUM -> {
                                    val drumMap = mapOf(
                                        60 to "Kick",
                                        61 to "Snare",
                                        62 to "Cymb",
                                        63 to "Hat C",
                                        64 to "Hat O"
                                    )
                                    drumMap[note] ?: "Pad $padIndex"
                                }
                                else -> if (isChopMode) "${padIndex + 1}" else getNoteLabel(note)
                            }
                            if (!isChopMode || label.isNotEmpty()) {
                                Text(label, style = MaterialTheme.typography.labelMedium, color = Color.White.copy(alpha = 0.6f))
                            }
                        }
                }
}


    val HexagonShape = androidx.compose.foundation.shape.GenericShape { size, _ ->
        val width = size.width
        val height = size.height
        // Pointy-topped hexagon
        moveTo(width * 0.5f, 0f)
        lineTo(width, height * 0.25f)
        lineTo(width, height * 0.75f)
        lineTo(width * 0.5f, height)
        lineTo(0f, height * 0.75f)
        lineTo(0f, height * 0.25f)
        close()
    }

    @Composable
    fun TonnetzGrid(
        state: GrooveboxState,
        nativeLib: NativeLib,
        engineColor: Color,
        onStateChange: (GrooveboxState) -> Unit,
        empledManager: EmpledManager? = null
    ) {
        BoxWithConstraints(modifier = Modifier.fillMaxSize().clipToBounds()) {
            val density = LocalDensity.current.density
            val widthPx = constraints.maxWidth.toFloat()
            val heightPx = constraints.maxHeight.toFloat()
            
            val platformInfo = LocalPlatformInfo.current
            val isTabletDetected = platformInfo.isTablet
            
            // Layout Mode: 0=Auto, 1=Phone, 2=Tablet
            val isTablet = when(state.uiLayoutMode) {
                1 -> false // Force Phone
                2 -> true  // Force Tablet
                else -> isTabletDetected // Auto
            }
            
            // Target Density: 
            // Tablet: ~10 rows, 10 cols (alternating 10/9)
            // Phone: 9 rows, alternating 8/9 pads
            val targetRows = if (isTablet) 10 else 9
            val targetCols = if (isTablet) 10 else 9 // Max cols

            // Geometry constants (normalized to radius 1)
            // Hex Width (point to point horizontal) = 2 * radius (No, flat top?)
            // Wait, previous shape was Pointy Topped.
            // Pointy Topped:
            //   Width = sqrt(3) * radius
            //   Height = 2 * radius
            //   Horiz spacing = Width
            //   Vert spacing = 3/4 * Height
            // My previous code:
            //   hexHeight = hexRadiusPx * 2f (Height)
            //   colWidth = hexHeight * 0.866f (Width? No. 2 * r * sqrt(3)/2 = r * sqrt(3). Correct.)
            
            // To fit targetRows/Cols:
            // Width needed = targetCols * (sqrt(3) * r) + (0.5 * sqrt(3) * r for offset)
            // Height needed = targetRows * (1.5 * r) + (0.5 * r)
            
            val sqrt3 = 1.732f
            val widthFactor = targetCols * sqrt3 + (0.5f * sqrt3)
            val heightFactor = targetRows * 1.5f + 0.5f
            
            val radiusByWidth = widthPx / widthFactor
            val radiusByHeight = heightPx / heightFactor
            
            // Use the smaller radius to fit within bounds
            val hexRadiusPx = kotlin.math.min(radiusByWidth, radiusByHeight) * 0.95f // 5% margin
            val hexRadius = (hexRadiusPx / density).dp
            
            val hexHeight = hexRadiusPx * 2f
            val rowHeight = hexHeight * 0.75f
            val colWidth = hexHeight * 0.866f // sqrt(3)/2
            
            val rows = targetRows
            val cols = targetCols // We iterate 0..9, but modify checks
            
            // Center the grid content
            val totalGridWidth = (cols) * colWidth + (colWidth * 0.5f)
            val totalGridHeight = rows * rowHeight + (hexHeight * 0.25f) // Correct total height calc
            val startX = (widthPx - totalGridWidth) / 2f
            val startY = (heightPx - totalGridHeight) / 2f
            
            val rootNote = state.rootNote
            
            (0 until rows).forEach { r ->
                // Tablet: Alternating 10, 9, 10, 9...
                // Phone: Alternating 8, 9, 8, 9... (Top row 8)
                val numColsInRow = if (isTablet) {
                    if (r % 2 == 0) 10 else 9
                } else {
                    if (r % 2 == 0) 8 else 9
                }
                
                (0 until numColsInRow).forEach { c ->
                    // Center the shorter rows based on configuration
                    
                    val isShortRow = if (isTablet) (r % 2 != 0) else (r % 2 == 0)
                    val xOffset = if (isShortRow) colWidth * 0.5f else 0f
                    
                    val xPos = startX + c * colWidth + xOffset
                    val yPos = startY + r * rowHeight
                    
                    // Note Mapping
                    // Center (r=rows/2, c=cols/2) to Root.
                    val rCenter = rows / 2
                    val cCenter = if (isTablet) 5 else 4 // Approx center col
                    
                    val rRel = (rows - 1 - r) - rCenter // Invert Y so up is higher pitch
                    val cRel = c - cCenter
                    
                    val noteVal = rootNote + (cRel * 7) + (rRel * 4)
                    
                    if (noteVal in 0..127) {
                        val isBlack = isBlackKey(noteVal)
                        val isRootRel = (noteVal % 12) == (state.rootNote % 12)
                        
                        // New Shading Logic
                        val pColor = if (isRootRel) {
                            engineColor // Root: Full color
                        } else if (isBlack) {
                            // "Black" key: Darker shade of engine color
                            androidx.compose.ui.graphics.lerp(Color.Black, engineColor, 0.6f)
                        } else {
                            // "White" key: Slightly desaturated or standard engine color
                            engineColor.copy(alpha = 0.6f) 
                        }
                        
                        // Spacing: Shrink visual pad within grid cell
                        val spacing = 6.dp
                        // Aspect Ratio Fix: Hexagon is 0.866 wide relative to height
                        // We calculate Visual Height first (vertical constraint)
                        val visualH = (hexRadius * 2 - spacing).coerceAtLeast(10.dp)
                        // Then calculate Visual Width to maintain 0.866 aspect ratio
                        val visualW = (visualH * 0.866f)
                        
                        Box(modifier = Modifier
                            .offset { IntOffset(xPos.toInt(), yPos.toInt()) }
                            .size(hexRadius * 2), // Grid cell size
                            contentAlignment = Alignment.Center
                        ) {
                             PlayingPad(
                                padIndex = -1,
                                note = noteVal,
                                padSize = 0.dp, // Ignore square size, use custom
                                customWidth = visualW,
                                customHeight = visualH,
                                padColor = pColor, // Use the computed shaded color
                                isPlaying = state.isPlaying,
                                currentStep = state.currentStep,
                                nativeLib = nativeLib,
                                latestState = state,
                                onStateChange = onStateChange,
                                empledManager = empledManager,
                                shape = HexagonShape,
                                isSolidStyle = true
                            )
                        }
                    }
                }
            }
        }
    }

@Composable
fun PadGrid(
    rows: Int, cols: Int, padSize: Dp, spacing: Dp, 
    track: com.groovebox.TrackState, state: GrooveboxState, scaleNotes: List<Int>, 
    engineColor: Color, nativeLib: NativeLib, 
    latestState: GrooveboxState, onStateChange: (GrooveboxState) -> Unit,
    latestOnStateChange: (GrooveboxState) -> Unit,
    empledManager: EmpledManager? = null
) {
    if (state.padMode == PadMode.FX) {
        FxPadGrid(
            state = state,
            latestState = latestState,
            nativeLib = nativeLib,
            onStateChange = onStateChange
        )
        return
    }
    if (state.gridMode == GridMode.TONNETZ) {
         TonnetzGrid(
            state = latestState,
            nativeLib = nativeLib,
            engineColor = engineColor,
            onStateChange = onStateChange,
            empledManager = empledManager
        )
    } else if (state.gridMode == GridMode.MAC_KEYS) {
        MacKeysGrid(
             state = latestState,
             nativeLib = nativeLib,
             engineColor = engineColor,
             onStateChange = onStateChange,
             empledManager = empledManager
        )
    } else {
        var activeNoteMask by remember { mutableStateOf(0L) }
        LaunchedEffect(state.selectedTrackIndex, state.isPlaying) {
            while(true) {
                activeNoteMask = nativeLib.getActiveNoteMask(state.selectedTrackIndex)
                kotlinx.coroutines.delay(if (latestState.performanceProfile >= 2) 64L else 32L)
            }
        }

        Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Column(verticalArrangement = Arrangement.spacedBy(spacing), horizontalAlignment = Alignment.CenterHorizontally) {
                repeat(rows) { row ->
                    Row(horizontalArrangement = Arrangement.spacedBy(spacing)) {
                        repeat(cols) { col ->
                            val padIndex = row * cols + col
                            val chopMode = track.engineType == EngineType.SAMPLER &&
                                (track.parameters[320] ?: 0f) >= 0.6f
                            val note = padNoteFor(state.padMode, track, state, padIndex, scaleNotes)
                            val isDrumMapping = track.engineType == EngineType.FM_DRUM ||
                                track.engineType == EngineType.ANALOG_DRUM ||
                                state.padMode == PadMode.FM_DRUM ||
                                state.padMode == PadMode.ANALOG_DRUM ||
                                state.padMode == PadMode.SLICES
                            val isBlack = if (isDrumMapping || chopMode) false else isBlackKey(note)
                            val padColor = if (isBlack) androidx.compose.ui.graphics.lerp(Color.DarkGray, engineColor, 0.4f) else engineColor
                            // Use 0x3FFFFFFFFFFFFFFFL to mask out bits 62-63 (Status flags)
                            val cleanMask = activeNoteMask and 0x3FFFFFFFFFFFFFFFL
                            val isNoteActive = (note >= 48 && note < 112) && ((cleanMask and (1L shl (note - 48))) != 0L)
                            
                            Box(modifier = Modifier.size(padSize)) {
                                if (note != -1) {
                                    key(state.selectedTrackIndex, padIndex) {
                                        PlayingPad(
                                            padIndex = padIndex,
                                            note = note,
                                            padSize = padSize,
                                            padColor = padColor,
                                            isPlaying = state.isPlaying,
                                            currentStep = state.currentStep,
                                            nativeLib = nativeLib,
                                            latestState = latestState,
                                            onStateChange = onStateChange,
                                            empledManager = empledManager,
                                            isChopMode = chopMode,
                                            isNoteActive = isNoteActive
                                        )
                                    }
                                } else {
                                    Spacer(modifier = Modifier.size(padSize))
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

/**
 * Split performance layout: two independent 5x4 banks side by side.
 * Each bank targets its own track and octave; the same track may be used on
 * both banks to get two octave ranges of one sound.
 */
@Composable
fun SplitPadArea(
    state: GrooveboxState,
    latestState: GrooveboxState,
    nativeLib: NativeLib,
    onStateChange: (GrooveboxState) -> Unit,
    empledManager: EmpledManager? = null
) {
    BoxWithConstraints(modifier = Modifier.fillMaxSize()) {
        val gap = 10.dp
        Row(
            modifier = Modifier.fillMaxSize(),
            horizontalArrangement = Arrangement.spacedBy(gap)
        ) {
            SplitBank(
                zone = state.splitLeft,
                label = "L",
                isLeft = true,
                state = state,
                latestState = latestState,
                nativeLib = nativeLib,
                onStateChange = onStateChange,
                empledManager = empledManager,
                modifier = Modifier.weight(1f).fillMaxHeight()
            )
            SplitBank(
                zone = state.splitRight,
                label = "R",
                isLeft = false,
                state = state,
                latestState = latestState,
                nativeLib = nativeLib,
                onStateChange = onStateChange,
                empledManager = empledManager,
                modifier = Modifier.weight(1f).fillMaxHeight()
            )
        }
    }
}

@Composable
fun SplitBank(
    zone: SplitZone,
    label: String,
    isLeft: Boolean,
    state: GrooveboxState,
    latestState: GrooveboxState,
    nativeLib: NativeLib,
    onStateChange: (GrooveboxState) -> Unit,
    empledManager: EmpledManager? = null,
    modifier: Modifier = Modifier
) {
    val track = state.tracks.getOrNull(zone.trackIndex) ?: state.tracks[0]
    val color = getEngineColor(track.engineType)
    var showTrackMenu by remember { mutableStateOf(false) }
    var showModMenu by remember { mutableStateOf(false) }
    val effectiveModTarget = if (zone.modTargetId >= 0) zone.modTargetId else track.padModTargetId

    fun updateZone(transform: (SplitZone) -> SplitZone) {
        val updated = transform(zone)
        onStateChange(
            if (isLeft) state.copy(splitLeft = updated)
            else state.copy(splitRight = updated)
        )
    }

    val isChopMode = track.engineType == EngineType.SAMPLER && (track.parameters[320] ?: 0f) >= 0.6f
    val notes = splitNotesFor(track, zone, state)

    Column(
        modifier = modifier
            .border(1.dp, color.copy(alpha = 0.55f), RoundedCornerShape(10.dp))
            .padding(5.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            Text(label, color = color, fontWeight = FontWeight.Bold, fontSize = 12.sp)

            Box {
                Surface(
                    onClick = { showTrackMenu = true },
                    color = Color.DarkGray,
                    shape = RoundedCornerShape(4.dp),
                    border = BorderStroke(1.dp, color.copy(alpha = 0.6f))
                ) {
                    Text(
                        "T${zone.trackIndex + 1} ${track.engineType.name.take(3)}",
                        modifier = Modifier.padding(horizontal = 5.dp, vertical = 2.dp),
                        color = Color.White,
                        fontSize = 9.sp,
                        maxLines = 1
                    )
                }
                DropdownMenu(expanded = showTrackMenu, onDismissRequest = { showTrackMenu = false }) {
                    state.tracks.forEachIndexed { idx, t ->
                        DropdownMenuItem(
                            text = {
                                Text(
                                    "T${idx + 1} · ${t.engineType.name.replace("_", " ")}",
                                    color = if (t.isDisabled) Color.Gray else Color.White,
                                    fontSize = 12.sp
                                )
                            },
                            onClick = {
                                updateZone { it.copy(trackIndex = idx) }
                                showTrackMenu = false
                            }
                        )
                    }
                }
            }

            Spacer(modifier = Modifier.weight(1f))

            SplitOctButton("-") {
                updateZone { it.copy(octave = (it.octave - 1).coerceAtLeast(-3)) }
            }
            Text(
                "OCT ${if (zone.octave > 0) "+${zone.octave}" else "${zone.octave}"}",
                color = Color.Gray,
                fontSize = 9.sp,
                modifier = Modifier.width(44.dp),
                textAlign = TextAlign.Center
            )
            SplitOctButton("+") {
                updateZone { it.copy(octave = (it.octave + 1).coerceAtMost(3)) }
            }
            Surface(
                onClick = {
                    onStateChange(
                        state.copy(
                            selectedTrackIndex = zone.trackIndex,
                            isPadModLearnActive = true,
                            padModLearnBank = if (isLeft) 0 else 1
                        )
                    )
                },
                color = if (state.isPadModLearnActive) Color.Cyan else Color.DarkGray,
                shape = RoundedCornerShape(4.dp),
                border = BorderStroke(1.dp, Color.Gray.copy(alpha = 0.5f))
            ) {
                Text(
                    "MOD",
                    modifier = Modifier.padding(horizontal = 4.dp, vertical = 2.dp),
                    color = if (state.isPadModLearnActive) Color.Black else Color.White,
                    fontSize = 9.sp,
                    fontWeight = FontWeight.Bold
                )
            }
            Box {
                Surface(
                    onClick = { showModMenu = true },
                    color = Color.DarkGray,
                    shape = RoundedCornerShape(4.dp),
                    border = BorderStroke(1.dp, Color.Gray.copy(alpha = 0.5f))
                ) {
                    Text(
                        "\u22EE",
                        modifier = Modifier.padding(horizontal = 4.dp, vertical = 1.dp),
                        color = Color.White,
                        fontSize = 10.sp,
                        fontWeight = FontWeight.Bold
                    )
                }
                DropdownMenu(expanded = showModMenu, onDismissRequest = { showModMenu = false }) {
                    Text(
                        "BANK ${label} MOD  ·  ${modTargetLabel(effectiveModTarget)}",
                        modifier = Modifier.padding(horizontal = 12.dp, vertical = 4.dp),
                        color = Color.Gray,
                        fontSize = 10.sp
                    )
                    DropdownMenuItem(
                        text = {
                            Text(
                                if (zone.polyMod) "Poly voices: ON" else "Poly voices: OFF",
                                color = Color.White, fontSize = 12.sp
                            )
                        },
                        onClick = {
                            updateZone { it.copy(polyMod = !it.polyMod) }
                            showModMenu = false
                        }
                    )
                    DropdownMenuItem(
                        text = { Text("Bend range ±2 st", color = Color.White, fontSize = 12.sp) },
                        onClick = { updateZone { it.copy(bendRange = 2f) }; showModMenu = false }
                    )
                    DropdownMenuItem(
                        text = { Text("Bend range ±12 st", color = Color.White, fontSize = 12.sp) },
                        onClick = { updateZone { it.copy(bendRange = 12f) }; showModMenu = false }
                    )
                    DropdownMenuItem(
                        text = { Text("Bend range ±24 st", color = Color.White, fontSize = 12.sp) },
                        onClick = { updateZone { it.copy(bendRange = 24f) }; showModMenu = false }
                    )
                    DropdownMenuItem(
                        text = { Text("Intensity 100%", color = Color.White, fontSize = 12.sp) },
                        onClick = { updateZone { it.copy(modIntensity = 1f) }; showModMenu = false }
                    )
                    DropdownMenuItem(
                        text = { Text("Intensity 50%", color = Color.White, fontSize = 12.sp) },
                        onClick = { updateZone { it.copy(modIntensity = 0.5f) }; showModMenu = false }
                    )
                    DropdownMenuItem(
                        text = { Text("Learn destination…", color = Color.Cyan, fontSize = 12.sp) },
                        onClick = {
                            onStateChange(
                                state.copy(
                                    selectedTrackIndex = zone.trackIndex,
                                    isPadModLearnActive = true,
                                    padModLearnBank = if (isLeft) 0 else 1
                                )
                            )
                            showModMenu = false
                        }
                    )
                    DropdownMenuItem(
                        text = { Text("Use track destination", color = Color.White, fontSize = 12.sp) },
                        onClick = { updateZone { it.copy(modTargetId = -1) }; showModMenu = false }
                    )
                }
            }
        }

        Spacer(modifier = Modifier.height(4.dp))

        BoxWithConstraints(
            modifier = Modifier.weight(1f).fillMaxWidth(),
            contentAlignment = Alignment.Center
        ) {
            val spacing = 4.dp
            val padSize = minOf(
                (maxWidth - spacing * 3) / 4,
                (maxHeight - spacing * 4) / 5,
                110.dp
            ).coerceAtLeast(24.dp)

            Column(
                verticalArrangement = Arrangement.spacedBy(spacing),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                repeat(5) { row ->
                    Row(horizontalArrangement = Arrangement.spacedBy(spacing)) {
                        repeat(4) { col ->
                            val padIndex = row * 4 + col
                            val note = notes.getOrElse(padIndex) { -1 }
                            if (note < 0) {
                                Spacer(modifier = Modifier.size(padSize))
                            } else {
                                val isBlack = track.engineType != EngineType.FM_DRUM &&
                                    track.engineType != EngineType.ANALOG_DRUM &&
                                    !isChopMode && isBlackKey(note)
                                val padColor = if (isBlack)
                                    androidx.compose.ui.graphics.lerp(Color.DarkGray, color, 0.4f)
                                else color
                                key(zone.trackIndex, zone.octave, padIndex) {
                                    PlayingPad(
                                        padIndex = padIndex,
                                        note = note,
                                        padSize = padSize,
                                        padColor = padColor,
                                        isPlaying = state.isPlaying,
                                        currentStep = state.currentStep,
                                        nativeLib = nativeLib,
                                        latestState = latestState,
                                        onStateChange = onStateChange,
                                        empledManager = empledManager,
                                        isChopMode = isChopMode,
                                        targetTrackIndex = zone.trackIndex,
                                        zone = zone,
                                        playheadEnabled = false
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun SplitOctButton(text: String, onClick: () -> Unit) {
    Surface(
        onClick = onClick,
        color = Color.DarkGray,
        shape = RoundedCornerShape(4.dp),
        border = BorderStroke(1.dp, Color.Gray.copy(alpha = 0.5f))
    ) {
        Text(
            text,
            modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp),
            color = Color.White,
            fontSize = 11.sp,
            fontWeight = FontWeight.Bold
        )
    }
}

/** Note map for one split bank, honouring the pad mode and drum/chop rules. */
fun splitNotesFor(track: TrackState, zone: SplitZone, state: GrooveboxState): List<Int> {
    val count = 20
    val base = (state.rootNote + zone.octave * 12).coerceIn(0, 115)
    // FX pads are global, so a split bank falls back to the scale mapping.
    val mode = if (state.padMode == PadMode.FX) PadMode.SCALES else state.padMode

    return when (mode) {
        PadMode.AUTO -> when {
            track.engineType == EngineType.FM_DRUM -> List(count) { 60 + (it % 16) }
            track.engineType == EngineType.ANALOG_DRUM -> List(count) {
                if (it % 8 < 6) 60 + (it % 8) else -1
            }
            track.engineType == EngineType.SAMPLER && (track.parameters[320] ?: 0f) >= 0.6f -> {
                val slices = (((track.parameters[340] ?: 0f) * 14f).toInt() + 2).coerceIn(2, 16)
                List(count) { if (it < slices) 60 + it else -1 }
            }
            else -> ScaleLogic.generateScaleNotes(base, state.scaleType, count).map { it.coerceAtMost(127) }
        }
        PadMode.SCALES -> ScaleLogic.generateScaleNotes(base, state.scaleType, count)
            .map { it.coerceAtMost(127) }
        PadMode.KEYBOARD -> List(count) { (base + it).coerceAtMost(127) }
        PadMode.FM_DRUM -> List(count) { 60 + (it % 16) }
        PadMode.ANALOG_DRUM -> List(count) { if (it % 8 < 6) 60 + (it % 8) else -1 }
        PadMode.SLICES -> {
            val slices = (((track.parameters[340] ?: 0f) * 14f).toInt() + 2).coerceIn(2, 16)
            List(count) { if (it < slices) 60 + it else -1 }
        }
        PadMode.FX -> ScaleLogic.generateScaleNotes(base, state.scaleType, count)
            .map { it.coerceAtMost(127) }
    }
}

/**
 * Phase 6: pad grid in FX mode. Each pad toggles one of the 18 global FX
 * buses, reusing the same enable mask as Settings → Performance.
 */
@Composable
fun FxPadGrid(
    state: GrooveboxState,
    latestState: GrooveboxState,
    nativeLib: NativeLib,
    onStateChange: (GrooveboxState) -> Unit
) {
    BoxWithConstraints(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        val spacing = 6.dp
        val padW = ((maxWidth - spacing * 5) / 6).coerceAtMost(120.dp).coerceAtLeast(36.dp)
        val padH = ((maxHeight - spacing * 2) / 3).coerceAtMost(110.dp).coerceAtLeast(36.dp)

        Column(
            verticalArrangement = Arrangement.spacedBy(spacing),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            repeat(3) { row ->
                Row(horizontalArrangement = Arrangement.spacedBy(spacing)) {
                    repeat(6) { col ->
                        val idx = row * 6 + col
                        val enabled = latestState.fxEnabled.getOrElse(idx) { true }
                        val name = FX_BUS_NAMES.getOrElse(idx) { "FX $idx" }
                        Surface(
                            onClick = {
                                val mask = latestState.fxEnabled.toMutableList()
                                while (mask.size < 18) mask.add(true)
                                mask[idx] = !enabled
                                nativeLib.setFxEnabled(idx, !enabled)
                                onStateChange(latestState.copy(fxEnabled = mask))
                            },
                            modifier = Modifier.size(padW, padH),
                            color = if (enabled) Color(0xFF00BCD4).copy(alpha = 0.25f)
                                    else Color.DarkGray.copy(alpha = 0.5f),
                            border = BorderStroke(
                                1.dp,
                                if (enabled) Color(0xFF00BCD4) else Color.Gray.copy(alpha = 0.4f)
                            ),
                            shape = RoundedCornerShape(8.dp)
                        ) {
                            Column(
                                modifier = Modifier.fillMaxSize().padding(4.dp),
                                verticalArrangement = Arrangement.Center,
                                horizontalAlignment = Alignment.CenterHorizontally
                            ) {
                                Text(
                                    name,
                                    fontSize = 9.sp,
                                    color = if (enabled) Color.White else Color.Gray,
                                    maxLines = 2,
                                    textAlign = TextAlign.Center
                                )
                                Text(
                                    if (enabled) "ON" else "BYP",
                                    fontSize = 8.sp,
                                    color = if (enabled) Color(0xFF00BCD4) else Color.Gray
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

/**
 * Phase 6: guided drum-row mapping.
 *
 * Arm a row, tap a pad/key on the MIDI controller, and the incoming note is
 * stored in [TrackState.drumNoteMap]. MidiRouter consumes the learning note
 * instead of triggering it, then applies the map to future notes.
 */
@Composable
private fun DrumRowWizard(
    state: GrooveboxState,
    onStateChange: (GrooveboxState) -> Unit,
    onClose: () -> Unit
) {
    val track = state.tracks.getOrNull(state.selectedTrackIndex) ?: return
    val isAnalog = track.engineType == EngineType.ANALOG_DRUM
    val rowCount = if (isAnalog) 6 else 16
    val names = if (isAnalog) {
        listOf("Kick", "Snare", "Cymbal", "Hat C", "Hat O", "Perc")
    } else {
        listOf("Kick", "Snare", "Tom", "HH", "OHH", "Cymbal", "Perc", "Noise") +
            List(8) { "Row ${it + 9}" }
    }
    val armedRow = state.drumMapLearnRow

    fun updateTrack(transform: (TrackState) -> TrackState) {
        onStateChange(
            state.copy(
                tracks = state.tracks.mapIndexed { i, t ->
                    if (i == state.selectedTrackIndex) transform(t) else t
                }
            )
        )
    }

    fun close() {
        onStateChange(state.copy(drumMapLearnRow = -1, drumMapLearnTrack = -1))
        onClose()
    }

    Dialog(onDismissRequest = { close() }) {
        Card(
            colors = CardDefaults.cardColors(containerColor = Color(0xFF151515)),
            border = BorderStroke(1.dp, Color.Cyan.copy(alpha = 0.5f))
        ) {
            Column(modifier = Modifier.padding(16.dp).widthIn(max = 420.dp)) {
                Text(
                    "DRUM ROW MAP",
                    style = MaterialTheme.typography.titleMedium,
                    color = Color.Cyan
                )
                Text(
                    "Arm a row, then tap a pad or key on your MIDI controller. " +
                        "Notes outside the map keep the default (60 + row).",
                    style = MaterialTheme.typography.labelSmall,
                    color = Color.Gray
                )
                if (armedRow >= 0) {
                    Text(
                        "Waiting for note → row ${armedRow + 1} " +
                            "(${names.getOrElse(armedRow) { "" }})",
                        style = MaterialTheme.typography.labelMedium,
                        color = Color.Yellow,
                        modifier = Modifier.padding(top = 8.dp)
                    )
                }
                Spacer(modifier = Modifier.height(8.dp))
                Column(
                    modifier = Modifier
                        .heightIn(max = 360.dp)
                        .verticalScroll(rememberScrollState())
                ) {
                    for (row in 0 until rowCount) {
                        val mapped = track.drumNoteMap
                            .filterValues { it == row }
                            .keys
                            .sorted()
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    "${row + 1}. ${names.getOrElse(row) { "Row ${row + 1}" }}",
                                    style = MaterialTheme.typography.labelMedium,
                                    color = Color.White
                                )
                                Text(
                                    if (mapped.isEmpty()) "default note ${60 + row}"
                                    else mapped.joinToString(", ") { "note $it" },
                                    style = MaterialTheme.typography.labelSmall,
                                    color = if (mapped.isEmpty()) Color.Gray else Color(0xFF4CAF50)
                                )
                            }
                            TextButton(
                                onClick = {
                                    onStateChange(
                                        state.copy(
                                            drumMapLearnRow = if (armedRow == row) -1 else row,
                                            drumMapLearnTrack = state.selectedTrackIndex
                                        )
                                    )
                                }
                            ) {
                                Text(
                                    if (armedRow == row) "CANCEL" else "LEARN",
                                    fontSize = 10.sp,
                                    color = if (armedRow == row) Color.Yellow else Color.Cyan
                                )
                            }
                            TextButton(
                                onClick = {
                                    updateTrack {
                                        it.copy(
                                            drumNoteMap = it.drumNoteMap.filterValues { v -> v != row }
                                        )
                                    }
                                }
                            ) {
                                Text("CLEAR", fontSize = 10.sp, color = Color.Gray)
                            }
                        }
                        Divider(color = Color.Gray.copy(alpha = 0.15f))
                    }
                }
                Spacer(modifier = Modifier.height(8.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    TextButton(onClick = { updateTrack { it.copy(drumNoteMap = emptyMap()) } }) {
                        Text("RESET ALL", fontSize = 11.sp, color = Color(0xFFFF7043))
                    }
                    Spacer(modifier = Modifier.weight(1f))
                    TextButton(onClick = { close() }) {
                        Text("CLOSE", fontSize = 11.sp, color = Color.White)
                    }
                }
            }
        }
    }
}

@Composable
fun MacKeysGrid(
    state: GrooveboxState,
    nativeLib: NativeLib,
    engineColor: Color,
    onStateChange: (GrooveboxState) -> Unit,
    empledManager: EmpledManager? = null
) {
    val track = state.tracks[state.selectedTrackIndex]
    val scaleNotes = remember(state.rootNote, state.scaleType) {
        ScaleLogic.generateScaleNotes(state.rootNote, state.scaleType, 46)
    }

    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Column(verticalArrangement = Arrangement.spacedBy(12.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            val rows = listOf(12, 13, 11, 10) // Number of keys per row (1-0/-/= , Q-[, A-', Z-/)
            var currentIndex = 0
            rows.forEachIndexed { rowIndex, colCount ->
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    // Indentation logic for keyboard look
                    val rowPadding = when(rowIndex) {
                        1 -> 12.dp
                        2 -> 18.dp
                        3 -> 24.dp
                        else -> 0.dp
                    }
                    if (rowPadding > 0.dp) Spacer(modifier = Modifier.width(rowPadding))

                    repeat(colCount) { col ->
                        val padIndex = currentIndex + col
                        val isOctaveKey = rowIndex == 0 && col >= 10
                        
                        Box(modifier = Modifier.size(38.dp)) {
                            if (isOctaveKey) {
                                // Draw an octave indicator or just a grayed out key
                                Box(
                                    modifier = Modifier
                                        .fillMaxSize()
                                        .background(Color.DarkGray.copy(alpha = 0.3f), RoundedCornerShape(4.dp))
                                        .border(1.dp, Color.Gray.copy(alpha = 0.3f), RoundedCornerShape(4.dp)),
                                    contentAlignment = Alignment.Center
                                ) {
                                    Text(if (col == 10) "-" else "+", color = Color.Gray, style = MaterialTheme.typography.labelSmall)
                                }
                            } else {
                                key(state.selectedTrackIndex, padIndex) {
                                    val note = scaleNotes.getOrElse(padIndex) { state.rootNote + padIndex }
                                    val isBlack = isBlackKey(note)
                                    val padColor = if (isBlack) androidx.compose.ui.graphics.lerp(Color.DarkGray, engineColor, 0.4f) else engineColor
                                    
                                    PlayingPad(
                                        padIndex = padIndex,
                                        note = note,
                                        padSize = 38.dp,
                                        padColor = padColor,
                                        isPlaying = state.isPlaying,
                                        currentStep = state.currentStep,
                                        nativeLib = nativeLib,
                                        latestState = state,
                                        onStateChange = onStateChange,
                                        empledManager = empledManager,
                                        isSolidStyle = false
                                    )
                                }
                            }
                        }
                    }
                    currentIndex += colCount
                }
            }
        }
    }
}

@Composable
fun TouchStripsPanel(state: GrooveboxState, onStateChange: (GrooveboxState) -> Unit, nativeLib: NativeLib, engineColor: Color) {
    val latestState by rememberUpdatedState(state)
    val latestOnStateChange by rememberUpdatedState(onStateChange)
    
    val platformInfo = LocalPlatformInfo.current
    val isWideScreenAuto = (platformInfo.screenWidthDp.toFloat() / platformInfo.screenHeightDp.toFloat()) > 1.7f
    
    val isWideScreen = when(state.uiLayoutMode) {
        1 -> false // Force Phone (Narrower/Taller strip panel)
        2 -> true  // Force Tablet (Wider/Shorter strip panel)
        else -> isWideScreenAuto
    }

    // Sidebar Area (Strips + Transport)
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .fillMaxHeight()
            .padding(vertical = if (isWideScreen) 12.dp else 16.dp, horizontal = 4.dp),
        horizontalArrangement = Arrangement.spacedBy(if (isWideScreen) 4.dp else 12.dp)
    ) {
        // Touch Strips Area (Side-by-side)
        Row(
            modifier = Modifier
                .weight(1.0f)
                .fillMaxHeight(),
            horizontalArrangement = Arrangement.spacedBy(4.dp, Alignment.CenterHorizontally)
        ) {
            state.stripRoutings.forEachIndexed { i, routing ->
                if (i > 0) Spacer(modifier = Modifier.width(5.dp)) // 5dp space between ALL strips
                val stripValue = state.stripValues[i]
                val isSelectedInLearn = state.midiLearnActive && state.midiLearnStep == 2 && state.midiLearnSelectedStrip == i
                val isLearnWait = state.midiLearnActive && state.midiLearnStep == 1
                
                // Check if this strip is driving a Macro
                val assignedMacro = state.macros.find { it.sourceType == 1 && it.sourceIndex == i }
                val displayLabel = if (assignedMacro != null) assignedMacro.label.uppercase()
                                   else if (routing.parameterName == "None") "S${i+1}" 
                                   else routing.parameterName.uppercase()
                
                Column(
                    modifier = Modifier.fillMaxHeight().width(if (isWideScreen) 24.dp else 36.dp),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    Text(displayLabel, style = MaterialTheme.typography.labelSmall, maxLines = 1, fontSize = 8.sp, color = if (assignedMacro != null) Color.Cyan else Color.Gray)
                    Box(
                        modifier = Modifier
                            .weight(1f)
                            .fillMaxWidth()
                            .padding(vertical = 8.dp)
                            .background(
                                if (isSelectedInLearn) Color.Yellow.copy(alpha = 0.5f)
                                else if (isLearnWait) Color.Yellow.copy(alpha = 0.3f)
                                else Color.DarkGray.copy(alpha = 0.5f), 
                                RoundedCornerShape(16.dp)
                            )
                            .border(
                                if (isSelectedInLearn || isLearnWait) 2.dp else 1.dp, 
                                if (isSelectedInLearn || isLearnWait) Color.Yellow else Color.Gray.copy(alpha = 0.3f), 
                                RoundedCornerShape(16.dp)
                            )
                            .pointerInput(i, state.midiLearnActive, state.midiLearnStep, state.lfoLearnActive, state.macroLearnActive) {
                                if (state.lfoLearnActive || state.macroLearnActive) {
                                    detectTapGestures {
                                        if (state.lfoLearnActive) {
                                            val lfoIdx = state.lfoLearnLfoIndex
                                            if (lfoIdx != -1) {
                                                nativeLib.setRouting(latestState.selectedTrackIndex, -1, 2 + lfoIdx, 5, 1.0f, routing.targetId)
                                                val newLfos = state.lfos.toMutableList()
                                                newLfos[lfoIdx] = newLfos[lfoIdx].copy(targetType = 1, targetId = routing.targetId, targetLabel = routing.parameterName)
                                                latestOnStateChange(latestState.copy(lfos = newLfos, lfoLearnActive = false))
                                            }
                                        } else if (state.macroLearnActive) {
                                            val macroIdx = state.macroLearnMacroIndex
                                            val tIdx = state.macroLearnTargetIndex
                                            if (macroIdx != -1 && tIdx != -1) {
                                                 val newMacros = state.macros.toMutableList()
                                                 val currentTargets = newMacros[macroIdx].targets.toMutableList()
                                                 if (tIdx < currentTargets.size) {
                                                      nativeLib.setRouting(latestState.selectedTrackIndex, -1, 10 + macroIdx, 5, 1.0f, routing.targetId)
                                                      currentTargets[tIdx] = currentTargets[tIdx].copy(targetId = routing.targetId, targetLabel = routing.parameterName, enabled = true)
                                                      newMacros[macroIdx] = newMacros[macroIdx].copy(targets = currentTargets)
                                                      latestOnStateChange(latestState.copy(macros = newMacros, macroLearnActive = false))
                                                 }
                                            }
                                        }
                                    }
                                } else if (state.midiLearnActive && state.midiLearnStep == 1) {
                                    detectTapGestures(
                                        onTap = {
                                            latestOnStateChange(latestState.copy(midiLearnStep = 2, midiLearnSelectedStrip = i))
                                        },
                                        onLongPress = {
                                            // UN-ASSIGN: Clear routing for this strip
                                            val newRoutings = latestState.stripRoutings.map {
                                                if (it.stripIndex == i) it.copy(targetType = 0, targetId = -1, parameterName = "None")
                                                else it
                                            }
                                            latestOnStateChange(latestState.copy(stripRoutings = newRoutings))
                                        }
                                    )
                                } else {
                                    detectDragGestures(
                                        onDrag = { change, dragAmount ->
                                            val delta = dragAmount.y / -200f
                                            val currentVal = latestState.stripValues[i]
                                            val newVal = (currentVal + delta).coerceIn(0f, 1f)
                                            val newValues = latestState.stripValues.toMutableList().apply { set(i, newVal) }
                                            
                                            val isAutoLocking = latestState.isRecording && latestState.isPlaying
                                            val isLockingMode = latestState.isParameterLocking || isAutoLocking
                                            
                                            if (routing.targetType == 1) {
                                                if (isLockingMode) {
                                                    nativeLib.setParameterPreview(latestState.selectedTrackIndex, routing.targetId, newVal)
                                                    val targetIdx = if (isAutoLocking) latestState.currentStep else latestState.lockingTarget?.second
                                                    if (targetIdx != null) {
                                                        nativeLib.setParameterLock(latestState.selectedTrackIndex, targetIdx, routing.targetId, newVal)
                                                    }
                                                } else {
                                                    nativeLib.setParameter(latestState.selectedTrackIndex, routing.targetId, newVal)
                                                }
                                            }

                                            latestOnStateChange(latestState.copy(
                                                stripValues = newValues,
                                                focusedParameter = if (routing.targetType == 1) routing.targetId else null,
                                                focusedValue = String.format("%.2f", newVal),
                                                tracks = if (isLockingMode && routing.targetType == 1) {
                                                    val targetIdx = if (isAutoLocking) latestState.currentStep else latestState.lockingTarget?.second
                                                     if (targetIdx != null) {
                                                        latestState.tracks.mapIndexed { tIdx, t ->
                                                            if (tIdx == latestState.selectedTrackIndex) {
                                                                if (t.engineType == EngineType.FM_DRUM) {
                                                                    val inst = t.selectedFmDrumInstrument
                                                                    t.copy(drumSteps = t.drumSteps.mapIndexed { di, ds ->
                                                                        if (di == inst) ds.mapIndexed { si, s ->
                                                                            if (si == targetIdx) s.copy(parameterLocks = s.parameterLocks + (routing.targetId to newVal))
                                                                            else s
                                                                        } else ds
                                                                    })
                                                                } else {
                                                                    t.copy(steps = t.steps.mapIndexed { si, s ->
                                                                        if (si == targetIdx) s.copy(parameterLocks = s.parameterLocks + (routing.targetId to newVal))
                                                                        else s
                                                                    })
                                                                }
                                                            } else t
                                                        }
                                                    } else latestState.tracks
                                                } else latestState.tracks
                                            ))
                                            change.consume()
                                        },
                                        onDragEnd = {
                                            val isAutoLocking = latestState.isRecording && latestState.isPlaying
                                            val isLockingMode = latestState.isParameterLocking || isAutoLocking
                                            if (!isLockingMode && routing.targetType == 1) {
                                                val finalVal = latestState.stripValues[i]
                                                latestOnStateChange(latestState.copy(
                                                    focusedValue = null,
                                                    tracks = latestState.tracks.mapIndexed { idx, t ->
                                                        if (idx == latestState.selectedTrackIndex) t.copy(parameters = t.parameters + (routing.targetId to finalVal))
                                                        else t
                                                    }
                                                ))
                                            } else {
                                                latestOnStateChange(latestState.copy(focusedValue = null))
                                            }
                                        },
                                        onDragCancel = {
                                            latestOnStateChange(latestState.copy(focusedValue = null))
                                        }
                                    )
                                }
                            }
                    ) {
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .fillMaxHeight(stripValue)
                                .align(Alignment.BottomCenter)
                                .background(if (isSelectedInLearn || isLearnWait) Color.Yellow.copy(alpha = 0.6f) else engineColor.copy(alpha = 0.6f), RoundedCornerShape(16.dp))
                        )
                        
                        Text(
                            if (routing.parameterName == "None") "STRIP ${i+1}" else routing.parameterName,
                            modifier = Modifier
                                .graphicsLayer { rotationZ = -90f }
                                .padding(bottom = 12.dp)
                                .align(Alignment.Center),
                            color = if (isSelectedInLearn || isLearnWait) Color.Black else Color.White,
                            style = MaterialTheme.typography.labelSmall,
                            maxLines = 1,
                            overflow = TextOverflow.Visible,
                            softWrap = false
                        )
                    }
                    Text("${(stripValue * 100).toInt()}", style = MaterialTheme.typography.labelSmall, color = if (isSelectedInLearn || isLearnWait) Color.Yellow else Color.Gray)
                }
            }
        }
            // Transport Sidebar (Far Right) Removed for redundancy
    }
}

@Composable
fun AssignableKnobsPanel(state: GrooveboxState, onStateChange: (GrooveboxState) -> Unit, nativeLib: NativeLib, engineColor: Color) {
    val latestState by rememberUpdatedState(state)
    val latestOnStateChange by rememberUpdatedState(onStateChange)
    
    val track = state.tracks[state.selectedTrackIndex]
    val engineType = track.engineType
    val currentRoutings = (state.engineTypeKnobAssignments ?: emptyMap())[engineType] ?: emptyList()
    
    val platformInfo = LocalPlatformInfo.current
    val ratio = platformInfo.screenWidthDp.toFloat() / platformInfo.screenHeightDp.toFloat()
    // STRICTER WideScreen: Must be > 600dp width AND > 480dp height (Tablet)
    val isTabletAuto = platformInfo.screenWidthDp >= 600 && platformInfo.screenHeightDp >= 480 && ratio > 1.3f
    
    val isWideScreen = when(state.uiLayoutMode) {
        1 -> false // Force Phone
        2 -> true  // Force Tablet
        else -> isTabletAuto
    }

    Column(
        modifier = Modifier
            .padding(start = if (isWideScreen) 0.dp else 12.dp) // Reduced from 24dp to 12dp
            .fillMaxWidth()
            .fillMaxHeight()
            .padding(vertical = 8.dp),
        verticalArrangement = Arrangement.SpaceEvenly,
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        val knobCount = 4
        for (i in 0 until knobCount) {
            val routing = currentRoutings.getOrNull(i)
            val knobValue = state.knobValues[i]
            val isSelectedInLearn = state.midiLearnActive && state.midiLearnStep == 2 && state.midiLearnSelectedStrip == i + 4 // Offset 4 for Knobs
            val isLearnWait = state.midiLearnActive && state.midiLearnStep == 1
            
            // Check if this knob is driving a Macro from this slot
            val assignedMacro = state.macros.find { it.sourceType == 2 && it.sourceIndex == i }
            
            val displayLabel = if (assignedMacro != null) assignedMacro.label.uppercase()
                               else if (routing != null && routing.parameterName != "None") routing.parameterName.uppercase()
                               else "VOL"
            
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                // Wrapper to handle Tap for Learning
                Box(
                    modifier = Modifier
                        .border(
                            if (isSelectedInLearn || isLearnWait) 2.dp else 0.dp, 
                            if (isSelectedInLearn || isLearnWait) Color.Yellow else Color.Transparent, 
                            RoundedCornerShape(8.dp)
                        )
                        .padding(2.dp)
                ) {
                    Knob(
                        label = displayLabel,
                        initialValue = 0.5f,
                        parameterId = -1,
                        state = state,
                        onStateChange = onStateChange,
                        nativeLib = nativeLib,
                        knobSize = if (isWideScreen) 56.dp else 45.dp,
                        labelFontSize = if (isWideScreen) null else 8.sp,
                        overrideValue = knobValue,
                        overrideColor = if (assignedMacro != null) Color.Cyan else if (isSelectedInLearn || isLearnWait) Color.Yellow else engineColor,
                        onValueChangeOverride = { newVal ->
                             val newValues = latestState.knobValues.toMutableList().apply { set(i, newVal) }
                             
                                 if (routing != null && routing.targetType == 1) {
                                     val isAutoLocking = latestState.isRecording && latestState.isPlaying
                                     val isLockingMode = latestState.isParameterLocking || isAutoLocking
                                     
                                     if (isLockingMode) {
                                         nativeLib.setParameterPreview(latestState.selectedTrackIndex, routing.targetId, newVal)
                                         // P-Lock
                                         val targetIdx = if (isAutoLocking) latestState.currentStep else latestState.lockingTarget?.second
                                         if (targetIdx != null) {
                                             nativeLib.setParameterLock(latestState.selectedTrackIndex, targetIdx, routing.targetId, newVal)
                                         }
                                         latestOnStateChange(latestState.copy(knobValues = newValues))
                                     } else {
                                         nativeLib.setParameter(latestState.selectedTrackIndex, routing.targetId, newVal)
                                         val updatedTracks = latestState.tracks.mapIndexed { idx, t ->
                                             if (idx == latestState.selectedTrackIndex) {
                                                 val newParams = t.parameters.toMutableMap()
                                                 newParams[routing.targetId] = newVal
                                                 t.copy(parameters = newParams)
                                             } else t
                                         }
                                         latestOnStateChange(latestState.copy(knobValues = newValues, tracks = updatedTracks))
                                     }
                                 } else {
                                     latestOnStateChange(latestState.copy(knobValues = newValues))
                                 }
                        }
                    )
                    
                    if (isLearnWait) {
                        Box(
                            modifier = Modifier
                                .matchParentSize()
                                .background(Color.Transparent)
                                .pointerInput(i) {
                                    detectTapGestures(
                                        onTap = {
                                            latestOnStateChange(latestState.copy(midiLearnStep = 2, midiLearnSelectedStrip = i + 4))
                                        },
                                        onLongPress = {
                                            // UN-ASSIGN: Clear routing for this knob
                                            val newRoutings = latestState.knobRoutings.mapIndexed { idx, item ->
                                                if (idx == i) item.copy(targetType = 0, targetId = -1, parameterName = "None")
                                                else item
                                            }
                                            latestOnStateChange(latestState.copy(knobRoutings = newRoutings))
                                        }
                                    )
                                }
                        )
                    }
                }
                // Value Display below knob (like Strips)
                Text(
                     text = "${(knobValue * 100).toInt()}",
                     style = MaterialTheme.typography.labelSmall,
                     color = if (isSelectedInLearn || isLearnWait) Color.Yellow else Color.Gray,
                     fontSize = if (isWideScreen) 9.sp else 11.sp
                )
            }
        }
    }
}

@Composable
fun EngineSideSheet(
    isOpen: Boolean,
    trackIndex: Int,
    onDismiss: () -> Unit,
    state: GrooveboxState,
    onStateChange: (GrooveboxState) -> Unit,
    nativeLib: NativeLib
) {
    if (!isOpen) return
    val track = state.tracks[trackIndex]

    key(trackIndex) { // Force recomposition when track changes
        Dialog(onDismissRequest = onDismiss) {
            Surface(
                modifier = Modifier
                    .width(320.dp)
                    .fillMaxHeight(0.9f)
                    .clip(RoundedCornerShape(16.dp)),
                color = MaterialTheme.colorScheme.surface,
                tonalElevation = 8.dp
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Text("Track ${trackIndex + 1} Settings", style = MaterialTheme.typography.titleMedium)
                    Spacer(modifier = Modifier.height(16.dp))
                    
                    Text("Engine Type", style = MaterialTheme.typography.labelMedium)
                    Spacer(modifier = Modifier.height(8.dp))
                    
                    val engines = EngineType.values()
                    LazyVerticalGrid(
                        columns = GridCells.Fixed(2),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        items(engines.size) { idx ->
                            val engine = engines[idx]
                            val isSelected = track.engineType == engine
                            
                            Card(
                                modifier = Modifier
                                    .height(80.dp)
                                    .clickable {
                                        if (!isSelected || track.catalogEntryId != null) {
                                            nativeLib.setEngineType(trackIndex, engine.ordinal)
                                            val newTrack = track.copy(engineType = engine, catalogEntryId = null, fm1KnobBindings = null, fm1StepKnobBindings = null)
                                            val newTracks = state.tracks.toMutableList()
                                            newTracks[trackIndex] = newTrack
                                            onStateChange(state.copy(tracks = newTracks))
                                        }
                                    },
                                colors = CardDefaults.cardColors(
                                    containerColor = if (isSelected) getEngineColor(engine) else MaterialTheme.colorScheme.surfaceVariant
                                )
                            ) {
                                Column(
                                    modifier = Modifier.fillMaxSize(),
                                    verticalArrangement = Arrangement.Center,
                                    horizontalAlignment = Alignment.CenterHorizontally
                                ) {
                                    EngineIcon(type = engine, color = if (isSelected) Color.White else MaterialTheme.colorScheme.onSurfaceVariant)
                                    Text(
                                        engine.name.replace("_", " "),
                                        style = MaterialTheme.typography.labelSmall,
                                        modifier = Modifier.padding(top = 4.dp),
                                        color = if (isSelected) Color.White else MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                }
                            }
                        }
                    }
                    
                    Spacer(modifier = Modifier.height(24.dp))
                    
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text("Mute Track")
                        Spacer(modifier = Modifier.weight(1f))
                        Switch(
                            checked = track.isMuted,
                            onCheckedChange = { muted ->
                                nativeLib.setTrackMute(trackIndex, muted)
                                val newTrack = track.copy(isMuted = muted)
                                val newTracks = state.tracks.toMutableList()
                                newTracks[trackIndex] = newTrack
                                onStateChange(state.copy(tracks = newTracks))
                            }
                        )
                    }

                    Spacer(modifier = Modifier.weight(1f))
                    Button(onClick = onDismiss, modifier = Modifier.fillMaxWidth()) {
                        Text("Close")
                    }
                }
            }
        }
    }
}

@Composable
fun ArpSettingsSheet(
    isOpen: Boolean,
    onDismiss: () -> Unit,
    state: GrooveboxState,
    onStateChange: (GrooveboxState) -> Unit,
    nativeLib: NativeLib
) {
    var copyDialogLive by remember { mutableStateOf<Boolean?>(null) }

    Box(modifier = Modifier.fillMaxSize()) {
        if (isOpen) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(Color.Black.copy(alpha = 0.6f))
                    .pointerInput(Unit) { detectTapGestures { onDismiss() } }
            )
        }

        androidx.compose.animation.AnimatedVisibility(
            visible = isOpen,
            enter = slideInVertically(initialOffsetY = { it }),
            exit = slideOutVertically(targetOffsetY = { it }),
            modifier = Modifier.align(Alignment.BottomCenter)
        ) {
            val track = state.tracks[state.selectedTrackIndex]
            val config = track.arpConfig
            
            Surface(
                modifier = Modifier
                    .fillMaxWidth()
                    .fillMaxHeight(0.85f)
                    .clickable(enabled = false) {},
                color = Color(0xFF1E1E1E),
                shape = RoundedCornerShape(topStart = 24.dp, topEnd = 24.dp),
                tonalElevation = 16.dp
            ) {
                Column(modifier = Modifier.fillMaxSize().padding(horizontal = 16.dp, vertical = 12.dp).verticalScroll(rememberScrollState())) {
                    val maxSteps = if (config.isLatched) 16 else 8
                    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                        Text("ARPEGGIATOR", style = MaterialTheme.typography.titleLarge, color = Color.White)
                        IconButton(onClick = onDismiss) { Icon(Icons.Filled.Close, contentDescription = null, tint = Color.White) }
                    }
                    
                    Spacer(modifier = Modifier.height(8.dp))

                    // Copy arp setup to another track.
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        OutlinedButton(
                            onClick = { copyDialogLive = false },
                            modifier = Modifier.weight(1f).height(36.dp),
                            contentPadding = PaddingValues(horizontal = 4.dp)
                        ) {
                            Text("COPY SETTINGS", fontSize = 10.sp, maxLines = 1)
                        }
                        OutlinedButton(
                            onClick = { copyDialogLive = true },
                            modifier = Modifier.weight(1f).height(36.dp),
                            contentPadding = PaddingValues(horizontal = 4.dp),
                            colors = ButtonDefaults.outlinedButtonColors(contentColor = Color(0xFFFFB74D))
                        ) {
                            Text("CLONE LIVE", fontSize = 10.sp, maxLines = 1)
                        }
                    }

                    Spacer(modifier = Modifier.height(8.dp))

                    val allModes = ArpMode.values().filter { it != ArpMode.OFF }
                    val row1 = allModes.take(5)
                    val row2 = allModes.drop(5)
                    
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) { // SpaceBetween for full width
                            row1.forEach { mode ->
                                val isSelected = config.mode == mode
                                Button(
                                    onClick = {
                                        val newConfig = config.copy(mode = mode)
                                        val newTracks = state.tracks.mapIndexed { i, t -> if (i == state.selectedTrackIndex) t.copy(arpConfig = newConfig) else t }
                                        onStateChange(state.copy(tracks = newTracks))
                                        nativeLib.setArpConfig(
                                            state.selectedTrackIndex, 
                                            mode.ordinal, 
                                            newConfig.octaves, 
                                            newConfig.inversion, 
                                            newConfig.isLatched, 
                                            newConfig.isMutated,
                                            newConfig.rhythms.map { it.toBooleanArray() }.toTypedArray(),
                                            newConfig.randomSequence.toIntArray(),
                                            newConfig.gateLengths.toFloatArray(),
                                            newConfig.probability,
                                            newConfig.weird
                                        )
                                    },
                                    contentPadding = PaddingValues(horizontal = 8.dp), // Compact padding
                                    colors = ButtonDefaults.buttonColors(containerColor = if (isSelected) Color.Cyan else Color.DarkGray),
                                    modifier = Modifier.weight(1f).padding(horizontal = 2.dp) // Equal width
                                ) { 
                                    Text(
                                        mode.name.replace("_", " "), 
                                        color = if (isSelected) Color.Black else Color.White,
                                        style = MaterialTheme.typography.labelSmall,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis
                                    ) 
                                }
                            }
                        }
                        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                            row2.forEach { mode ->
                                val isSelected = config.mode == mode
                                Button(
                                    onClick = {
                                        val newConfig = config.copy(mode = mode)
                                        val newTracks = state.tracks.mapIndexed { i, t -> if (i == state.selectedTrackIndex) t.copy(arpConfig = newConfig) else t }
                                        onStateChange(state.copy(tracks = newTracks))
                                        nativeLib.setArpConfig(
                                            state.selectedTrackIndex, 
                                            mode.ordinal, 
                                            newConfig.octaves, 
                                            newConfig.inversion, 
                                            newConfig.isLatched, 
                                            newConfig.isMutated,
                                            newConfig.rhythms.map { it.toBooleanArray() }.toTypedArray(),
                                            newConfig.randomSequence.toIntArray(),
                                            newConfig.gateLengths.toFloatArray(),
                                            newConfig.probability,
                                            newConfig.weird
                                        )
                                    },
                                    contentPadding = PaddingValues(horizontal = 8.dp),
                                    colors = ButtonDefaults.buttonColors(containerColor = if (isSelected) Color.Cyan else Color.DarkGray),
                                    modifier = Modifier.weight(1f).padding(horizontal = 2.dp)
                                ) { 
                                    Text(
                                        mode.name.replace("_", " "), 
                                        color = if (isSelected) Color.Black else Color.White,
                                        style = MaterialTheme.typography.labelSmall,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis
                                    ) 
                                }
                            }
                        }
                    }
                    
                    Spacer(modifier = Modifier.height(24.dp))
                    
                    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(24.dp)) {
                        // Rate & Division
                        Column(modifier = Modifier.weight(1.2f)) {
                            Text("RATE & DIVISION", style = MaterialTheme.typography.labelMedium, color = Color.Gray)
                            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                                // Rate Knob
                                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                    val rates = listOf("1/32", "1/16", "1/8", "1/4", "1/2")
                                    val numericRates = listOf(0.5f, 1.0f, 2.0f, 4.0f, 8.0f)
                                    val currentIndex = numericRates.indexOfFirst { it >= config.arpRate }.coerceAtLeast(0)
                                    val initialV = currentIndex.toFloat() / (numericRates.size - 1)

                                    Knob(
                                        label = "RATE",
                                        initialValue = initialV,
                                        parameterId = -99, // Custom
                                        state = state,
                                        onStateChange = { newState ->
                                            val v = newState.tracks[state.selectedTrackIndex].parameters[-99] ?: initialV
                                            val idx = ((v * 0.99f) * numericRates.size).toInt().coerceIn(0, numericRates.size - 1)
                                            val newRate = numericRates[idx]
                                            val newConfig = config.copy(arpRate = newRate)
                                            val newTracks = state.tracks.mapIndexed { i, t -> if (i == state.selectedTrackIndex) t.copy(arpConfig = newConfig) else t }
                                            onStateChange(state.copy(tracks = newTracks))
                                            nativeLib.setArpRate(state.selectedTrackIndex, newRate, config.arpDivisionMode)
                                        },
                                        nativeLib = nativeLib,
                                        knobSize = 60.dp,
                                        valueFormatter = { v -> rates[((v * 0.99f) * rates.size).toInt().coerceIn(0, rates.size - 1)] }
                                    )
                                }

                                // Division Mode (Reg, Dot, Tri)
                                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                                    listOf("REG", "DOT", "TRI").forEachIndexed { index, label ->
                                        val isSelected = config.arpDivisionMode == index
                                        Button(
                                            onClick = {
                                                val newConfig = config.copy(arpDivisionMode = index)
                                                val newTracks = state.tracks.mapIndexed { i, t -> if (i == state.selectedTrackIndex) t.copy(arpConfig = newConfig) else t }
                                                onStateChange(state.copy(tracks = newTracks))
                                                nativeLib.setArpRate(state.selectedTrackIndex, config.arpRate, index)
                                            },
                                            modifier = Modifier.height(28.dp).width(50.dp),
                                            contentPadding = PaddingValues(0.dp),
                                            colors = ButtonDefaults.buttonColors(containerColor = if (isSelected) Color.Cyan else Color.DarkGray),
                                            shape = RoundedCornerShape(4.dp)
                                        ) {
                                            Text(label, style = MaterialTheme.typography.labelSmall, fontSize = 9.sp, color = if (isSelected) Color.Black else Color.White)
                                        }
                                    }
                                }
                                
                                // Strum Knob (Time Spread)
                                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                    Knob(
                                        label = "STRUM",
                                        initialValue = config.strum,
                                        parameterId = -102, // Custom
                                        state = state,
                                        onStateChange = { newState ->
                                            val v = newState.tracks[state.selectedTrackIndex].parameters[-102] ?: config.strum
                                            val newConfig = config.copy(strum = v)
                                            val newTracks = state.tracks.mapIndexed { i, t -> if (i == state.selectedTrackIndex) t.copy(arpConfig = newConfig) else t }
                                            onStateChange(state.copy(tracks = newTracks))
                                            nativeLib.setArpStrum(state.selectedTrackIndex, v)
                                        },
                                        nativeLib = nativeLib,
                                        knobSize = 60.dp,
                                        valueFormatter = { v -> "${(v * 100).toInt()}%" }
                                    )
                                }
                                
                            }
                        }

                        // Octaves & Inversion Stacked
                        Column(modifier = Modifier.weight(0.8f), verticalArrangement = Arrangement.spacedBy(16.dp)) {
                            // Octaves
                            Column {
                                Text("OCTAVES (+/- 3)", style = MaterialTheme.typography.labelMedium, color = Color.Gray)
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    (-3..3).forEach { oct ->
                                        val isSelected = config.octaves == oct
                                        Box(
                                            modifier = Modifier
                                                .size(28.dp)
                                                .background(if (isSelected) Color.Cyan else Color.Transparent, CircleShape)
                                                .clickable {
                                                    val newConfig = config.copy(octaves = oct)
                                                    val newTracks = state.tracks.mapIndexed { i, t -> if (i == state.selectedTrackIndex) t.copy(arpConfig = newConfig) else t }
                                                    onStateChange(state.copy(tracks = newTracks))
                                                    nativeLib.setArpConfig(state.selectedTrackIndex, config.mode.ordinal, oct, newConfig.inversion, newConfig.isLatched, newConfig.isMutated, newConfig.rhythms.map { it.toBooleanArray() }.toTypedArray(), newConfig.randomSequence.toIntArray(), newConfig.gateLengths.toFloatArray(), newConfig.probability, newConfig.weird)
                                                },
                                            contentAlignment = Alignment.Center
                                        ) { Text("$oct", color = if (isSelected) Color.Black else Color.White, fontSize = 10.sp) }
                                    }
                                }
                            }
                            
                            // Inversion
                            Column {
                                Text("INVERSION", style = MaterialTheme.typography.labelMedium, color = Color.Gray)
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    (-2..2).forEach { inv ->
                                        val isSelected = config.inversion == inv
                                        Box(
                                            modifier = Modifier
                                                .size(28.dp)
                                                .background(if (isSelected) Color.Cyan else Color.Transparent, CircleShape)
                                                .clickable {
                                                    val newConfig = config.copy(inversion = inv)
                                                    val newTracks = state.tracks.mapIndexed { i, t -> if (i == state.selectedTrackIndex) t.copy(arpConfig = newConfig) else t }
                                                    onStateChange(state.copy(tracks = newTracks))
                                                    nativeLib.setArpConfig(state.selectedTrackIndex, config.mode.ordinal, config.octaves, inv, newConfig.isLatched, newConfig.isMutated, newConfig.rhythms.map { it.toBooleanArray() }.toTypedArray(), newConfig.randomSequence.toIntArray(), newConfig.gateLengths.toFloatArray(), newConfig.probability, newConfig.weird)
                                                },
                                            contentAlignment = Alignment.Center
                                        ) { Text("$inv", color = if (isSelected) Color.Black else Color.White, fontSize = 10.sp) }
                                    }
                                }
                            }
                        }

                        // chord Progression Section
                        Column(modifier = Modifier.weight(1.5f)) {
                            Text("CHORD PROGRESSION", style = MaterialTheme.typography.labelMedium, color = Color.Gray)
                            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                                // Toggle
                                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                    Text("ON/OFF", style = MaterialTheme.typography.labelSmall, fontSize = 8.sp, color = Color.Gray)
                                    Switch(
                                        checked = config.isChordProgEnabled,
                                        onCheckedChange = { enabled ->
                                            val newConfig = config.copy(isChordProgEnabled = enabled)
                                            val newTracks = state.tracks.mapIndexed { i, t -> if (i == state.selectedTrackIndex) t.copy(arpConfig = newConfig) else t }
                                            onStateChange(state.copy(tracks = newTracks))
                                            nativeLib.setChordProgConfig(state.selectedTrackIndex, enabled, config.chordProgMood, config.chordProgComplexity)
                                            nativeLib.setScaleConfig(state.rootNote, state.scaleType.intervals.toIntArray())
                                        },
                                        colors = SwitchDefaults.colors(checkedThumbColor = Color.Cyan)
                                    )
                                }

                                // Mood Knob
                                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                    val moods = listOf("Calm", "Happy", "Sad", "Spooky", "Angry", "Excited", "Grand", "Tense")
                                    Knob(
                                        label = "MOOD",
                                        initialValue = config.chordProgMood.toFloat() / 7f,
                                        parameterId = -100, // Custom for UI
                                        state = state,
                                        onStateChange = { newState ->
                                            val v = newState.tracks[state.selectedTrackIndex].parameters[-100] ?: (config.chordProgMood.toFloat() / 7f)
                                            val moodIdx = (v * 7.99f).toInt().coerceIn(0, 7)
                                            val newConfig = config.copy(chordProgMood = moodIdx)
                                            val newTracks = state.tracks.mapIndexed { i, t -> if (i == state.selectedTrackIndex) t.copy(arpConfig = newConfig) else t }
                                            onStateChange(state.copy(tracks = newTracks))
                                            nativeLib.setChordProgConfig(state.selectedTrackIndex, config.isChordProgEnabled, moodIdx, config.chordProgComplexity)
                                        },
                                        nativeLib = nativeLib,
                                        knobSize = 50.dp,
                                        valueFormatter = { v -> moods[(v * 7.99f).toInt().coerceIn(0, 7)] }
                                    )
                                }

                                // Complexity Buttons
                                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                                    listOf("SIMPLE", "COMPLEX", "COLTRANE").forEachIndexed { index, label ->
                                        val isSelected = config.chordProgComplexity == index
                                        Button(
                                            onClick = {
                                                val newConfig = config.copy(chordProgComplexity = index)
                                                val newTracks = state.tracks.mapIndexed { i, t -> if (i == state.selectedTrackIndex) t.copy(arpConfig = newConfig) else t }
                                                onStateChange(state.copy(tracks = newTracks))
                                                nativeLib.setChordProgConfig(state.selectedTrackIndex, config.isChordProgEnabled, config.chordProgMood, index)
                                            },
                                            modifier = Modifier.height(28.dp).width(70.dp),
                                            contentPadding = PaddingValues(0.dp),
                                            colors = ButtonDefaults.buttonColors(containerColor = if (isSelected) Color.Cyan else Color.DarkGray),
                                            shape = RoundedCornerShape(4.dp)
                                        ) {
                                            Text(label, style = MaterialTheme.typography.labelSmall, fontSize = 9.sp, color = if (isSelected) Color.Black else Color.White)
                                        }
                                    }
                                }
                                
                                // Randomize Rhythm (Relocated v1.11.3)
                                Button(
                                    onClick = {
                                        val currentTrack = state.tracks[state.selectedTrackIndex]
                                        val currentConfig = currentTrack.arpConfig
                                        val newRhythms = currentConfig.rhythms.mapIndexed { laneIdx, _ ->
                                            if (laneIdx == 0) List(16) { Math.random() < 0.7 }
                                            else List(16) { false }
                                        }
                                        val updatedConfig = currentConfig.copy(rhythms = newRhythms)
                                        val updatedTracks = state.tracks.mapIndexed { i, t -> if (i == state.selectedTrackIndex) t.copy(arpConfig = updatedConfig) else t }
                                        onStateChange(state.copy(tracks = updatedTracks))
                                        nativeLib.setArpConfig(
                                            state.selectedTrackIndex,
                                            updatedConfig.mode.ordinal,
                                            updatedConfig.octaves,
                                            updatedConfig.inversion,
                                            updatedConfig.isLatched,
                                            updatedConfig.isMutated,
                                            newRhythms.map { it.toBooleanArray() }.toTypedArray(),
                                            updatedConfig.randomSequence.toIntArray(),
                                            updatedConfig.gateLengths.toFloatArray(),
                                            updatedConfig.probability,
                                            updatedConfig.weird
                                        )
                                    },
                                    modifier = Modifier.height(36.dp).widthIn(min = 64.dp).padding(start = 8.dp), // Increased height to 36dp
                                    contentPadding = PaddingValues(horizontal = 4.dp),
                                    colors = ButtonDefaults.buttonColors(containerColor = Color.Magenta),
                                    shape = RoundedCornerShape(4.dp)
                                ) {
                                    Text("RAND\nRHY", style = MaterialTheme.typography.labelSmall, fontSize = 9.sp, color = Color.White, textAlign = androidx.compose.ui.text.style.TextAlign.Center, lineHeight = 10.sp)
                                }
                            }
                        }
                    }
                    
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Column(modifier = Modifier.weight(1f)) {
                                Text("RHYTHM PATTERNS ($maxSteps STEPS)", style = MaterialTheme.typography.labelSmall, color = Color.Gray)
                                Text("(Bottom=Root, Upper=Polyphonic Cycle)", style = MaterialTheme.typography.labelSmall, fontSize = 10.sp, color = Color.DarkGray)
                            }
                            // Probability Knob for Arp
                            if (!config.isLatched) {
                                Knob(
                                    label = "PROB",
                                    initialValue = config.probability,
                                    parameterId = -101, // Custom
                                    state = state,
                                    onStateChange = { newState ->
                                        val v = newState.tracks[state.selectedTrackIndex].parameters[-101] ?: config.probability
                                        val newConfig = config.copy(probability = v)
                                        val newTracks = state.tracks.mapIndexed { i, t -> if (i == state.selectedTrackIndex) t.copy(arpConfig = newConfig) else t }
                                        onStateChange(state.copy(tracks = newTracks))
                                        nativeLib.setArpConfig(
                                            state.selectedTrackIndex,
                                            newConfig.mode.ordinal,
                                            newConfig.octaves,
                                            newConfig.inversion,
                                            newConfig.isLatched,
                                            newConfig.isMutated,
                                            newConfig.rhythms.map { it.toBooleanArray() }.toTypedArray(),
                                            newConfig.randomSequence.toIntArray(),
                                            newConfig.gateLengths.toFloatArray(),
                                            v,
                                            newConfig.weird
                                        )
                                    },
                                    nativeLib = nativeLib,
                                    knobSize = 48.dp,
                                    valueFormatter = { v -> "${(v * 100).toInt()}%" }
                                )
                            }
                            
                            if (!config.isLatched) {
                                Spacer(modifier = Modifier.width(16.dp))

                                // Weird Knob for Arp
                                Knob(
                                    label = "WEIRD",
                                    initialValue = config.weird,
                                    parameterId = -102, // Custom
                                    state = state,
                                    onStateChange = { newState ->
                                        val v = newState.tracks[state.selectedTrackIndex].parameters[-102] ?: config.weird
                                        val newConfig = config.copy(weird = v)
                                        val newTracks = state.tracks.mapIndexed { i, t -> if (i == state.selectedTrackIndex) t.copy(arpConfig = newConfig) else t }
                                        onStateChange(state.copy(tracks = newTracks))
                                        nativeLib.setArpConfig(
                                            state.selectedTrackIndex,
                                            newConfig.mode.ordinal,
                                            newConfig.octaves,
                                            newConfig.inversion,
                                            newConfig.isLatched,
                                            newConfig.isMutated,
                                            newConfig.rhythms.map { it.toBooleanArray() }.toTypedArray(),
                                            newConfig.randomSequence.toIntArray(),
                                            newConfig.gateLengths.toFloatArray(),
                                            newConfig.probability,
                                            v
                                        )
                                    },
                                    nativeLib = nativeLib,
                                    knobSize = 48.dp,
                                    valueFormatter = { v -> "${(v * 100).toInt()}%" }
                                )
                            }
                        }
                        Spacer(modifier = Modifier.height(4.dp))
                        
                        val laneLabels = listOf("ROOT", "UP 1", "UP 2", "UP 3")
                        val laneColors = listOf(Color(0xFF43A047), Color(0xFF1E88E5), Color(0xFF8E24AA), Color(0xFFE91E63))
                        
                        // Step Numbers header row
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier.fillMaxWidth().padding(vertical = 2.dp)
                        ) {
                            Spacer(modifier = Modifier.width(40.dp))
                            Row(modifier = Modifier.weight(1f), horizontalArrangement = Arrangement.spacedBy(2.dp)) {
                                (1..maxSteps).forEach { stepLabel ->
                                    Text(
                                        stepLabel.toString(),
                                        style = MaterialTheme.typography.labelSmall,
                                        color = Color.DarkGray,
                                        fontSize = 8.sp,
                                        textAlign = TextAlign.Center,
                                        modifier = Modifier.weight(1f)
                                    )
                                }
                                if (!config.isLatched) Spacer(modifier = Modifier.weight(1f))
                            }
                        }

                        (3 downTo 0).forEach { laneIdx ->
                             Row(
                                 verticalAlignment = Alignment.CenterVertically, 
                                 modifier = Modifier
                                     .fillMaxWidth()
                                     .padding(vertical = 2.dp)
                             ) {
                               Text(laneLabels[laneIdx], style = MaterialTheme.typography.labelSmall, color = laneColors[laneIdx], modifier = Modifier.width(40.dp))
                               val lanePattern = config.rhythms.getOrElse(laneIdx) { List(maxSteps) { false } }
                               Row(modifier = Modifier.weight(1f), horizontalArrangement = Arrangement.spacedBy(2.dp)) {
                                   val displaySteps = if (lanePattern.size < maxSteps) lanePattern + List(maxSteps - lanePattern.size) { false } else lanePattern
                                   displaySteps.take(maxSteps).forEachIndexed { step, isActive ->
                                       Box(
                                           modifier = Modifier
                                               .weight(1f)
                                               .aspectRatio(1.2f) 
                                               .background(
                                                   if (isActive) laneColors[laneIdx] else Color.DarkGray,
                                                   RoundedCornerShape(2.dp)
                                               )
                                               .clickable {
                                                   val newLane = lanePattern.toMutableList()
                                                   while (newLane.size <= step) newLane.add(false)
                                                   if (step < newLane.size) {
                                                        newLane[step] = !newLane[step]
                                                         val newRhythms = config.rhythms.toMutableList()
                                                         while(newRhythms.size <= laneIdx) newRhythms.add(List(16) { false })
                                                         newRhythms[laneIdx] = newLane.take(16)
                                                        
                                                         val newConfig = config.copy(rhythms = newRhythms)
                                                         val newTracks = state.tracks.mapIndexed { i, t -> if (i == state.selectedTrackIndex) t.copy(arpConfig = newConfig) else t }
                                                         onStateChange(state.copy(tracks = newTracks))
                                                         nativeLib.setArpConfig(
                                                             state.selectedTrackIndex, 
                                                             newConfig.mode.ordinal, 
                                                             newConfig.octaves, 
                                                             newConfig.inversion, 
                                                             newConfig.isLatched, 
                                                             newConfig.isMutated,
                                                             newRhythms.map { it.toBooleanArray() }.toTypedArray(), 
                                                             newConfig.randomSequence.toIntArray(),
                                                             newConfig.gateLengths.toFloatArray(),
                                                             newConfig.probability,
                                                             newConfig.weird
                                                         )
                                                   }
                                               }
                                       )
                                   }
                                   if (!config.isLatched) {
                                       Spacer(modifier = Modifier.weight(1f))
                                   }
                               }
                           }
                        }
                        
                      // GATE Row
                      Row(
                           verticalAlignment = Alignment.CenterVertically, 
                           modifier = Modifier
                               .fillMaxWidth()
                               .padding(vertical = 2.dp)
                      ) {
                           Text("GATE", style = MaterialTheme.typography.labelSmall, color = Color.Gray, modifier = Modifier.width(40.dp))
                           
                           Row(modifier = Modifier.weight(1f), horizontalArrangement = Arrangement.spacedBy(2.dp)) {
                               val gateLengths = config.gateLengths
                               (0 until maxSteps).forEach { step ->
                                   Box(
                                       modifier = Modifier
                                           .weight(1f)
                                           .aspectRatio(1.2f),
                                       contentAlignment = Alignment.Center
                                   ) {
                                       val currentGate = gateLengths.getOrElse(step) { 0.5f }
                                       Knob(
                                           label = "",
                                           overrideValue = currentGate,
                                           initialValue = currentGate,
                                           parameterId = -200 - step,
                                           state = state,
                                           onStateChange = { newState ->
                                               val v = newState.tracks[state.selectedTrackIndex].parameters[-200 - step] ?: currentGate
                                               val newGates = config.gateLengths.toMutableList()
                                               while(newGates.size <= step) newGates.add(0.5f)
                                               newGates[step] = v
                                               val newConfig = config.copy(gateLengths = newGates)
                                               val newTracks = state.tracks.mapIndexed { i, t -> if (i == state.selectedTrackIndex) t.copy(arpConfig = newConfig) else t }
                                               onStateChange(state.copy(tracks = newTracks))
                                               nativeLib.setArpConfig(
                                                   state.selectedTrackIndex, 
                                                   newConfig.mode.ordinal, 
                                                   newConfig.octaves, 
                                                   newConfig.inversion, 
                                                   newConfig.isLatched, 
                                                   newConfig.isMutated,
                                                   newConfig.rhythms.map { it.toBooleanArray() }.toTypedArray(), 
                                                   newConfig.randomSequence.toIntArray(),
                                                   newGates.toFloatArray(),
                                                   newConfig.probability,
                                                   newConfig.weird
                                               )
                                           },
                                           nativeLib = nativeLib,
                                           knobSize = 24.dp,
                                           showValue = false,
                                           detentValue = 0.5f,
                                           valueFormatter = { v -> 
                                               if (v in 0.48f..0.52f) "ENV"
                                               else if (v < 0.5f) {
                                                   val steps = 0.0625f + (v / 0.5f) * (1f - 0.0625f)
                                                   String.format("%.2f", steps)
                                               } else {
                                                   val steps = 1f + ((v - 0.5f) / 0.5f) * 3f
                                                   String.format("%.2f", steps)
                                               }
                                           }
                                       )
                                   }
                               }
                               if (!config.isLatched) {
                                   Spacer(modifier = Modifier.weight(1f))
                               }
                           }
                      }
                        
                        Spacer(modifier = Modifier.height(16.dp))
                        
                    }
                }
            }
        }

        copyDialogLive?.let { live ->
            ArpCopyTargetDialog(
                live = live,
                sourceTrackIndex = state.selectedTrackIndex,
                state = state,
                onDismiss = { copyDialogLive = null },
                onConfirm = { destTrack ->
                    copyDialogLive = null
                    val source = state.tracks[state.selectedTrackIndex]
                    val newTracks = state.tracks.mapIndexed { i, t ->
                        if (i == destTrack) {
                            t.copy(
                                arpConfig = source.arpConfig,
                                mutatedNotes = source.mutatedNotes,
                                clockMultiplier = source.clockMultiplier,
                                isDisabled = if (live) false else t.isDisabled,
                                isActive = if (live) true else t.isActive
                            )
                        } else t
                    }
                    onStateChange(state.copy(tracks = newTracks))
                    // Native copy must run after the state update is dispatched;
                    // onStateChange only stores Kotlin state and never resyncs
                    // the engine, so this cannot be overwritten.
                    nativeLib.copyArpeggiator(state.selectedTrackIndex, destTrack, live)
                    if (live) {
                        nativeLib.setTrackDisabled(destTrack, false)
                        nativeLib.setTrackActive(destTrack, true)
                    }
                }
            )
        }
    }

@Composable
fun ArpCopyTargetDialog(
    live: Boolean,
    sourceTrackIndex: Int,
    state: GrooveboxState,
    onDismiss: () -> Unit,
    onConfirm: (Int) -> Unit
) {
    val engineNames = listOf(
        "SUB", "FM", "SAMP", "GRAN", "WAVE",
        "FM DRM", "AN DRM", "MIDI", "AUDIO", "SFONT"
    )
    Dialog(onDismissRequest = onDismiss) {
        Surface(
            modifier = Modifier
                .width(340.dp)
                .fillMaxHeight(0.8f)
                .clip(RoundedCornerShape(16.dp)),
            color = Color(0xFF1E1E1E),
            tonalElevation = 8.dp
        ) {
            Column(modifier = Modifier.padding(16.dp)) {
                Text(
                    if (live) "CLONE LIVE TO" else "COPY SETTINGS TO",
                    style = MaterialTheme.typography.titleMedium,
                    color = if (live) Color(0xFFFFB74D) else Color.White
                )
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    if (live)
                        "Copies the running arp, including its random seed and held notes. The source keeps playing."
                    else
                        "Copies the arp setup with a fresh random seed. The destination starts on its next gesture.",
                    style = MaterialTheme.typography.bodySmall,
                    color = Color.Gray
                )
                Spacer(modifier = Modifier.height(12.dp))

                Column(
                    modifier = Modifier.weight(1f).verticalScroll(rememberScrollState()),
                    verticalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    state.tracks.forEachIndexed { idx, t ->
                        if (idx == sourceTrackIndex) return@forEachIndexed
                        val name = engineNames.getOrElse(t.engineType.ordinal) { "TRK" }
                        OutlinedButton(
                            onClick = { onConfirm(idx) },
                            modifier = Modifier.fillMaxWidth().height(44.dp),
                            contentPadding = PaddingValues(horizontal = 12.dp)
                        ) {
                            Text(
                                "Track ${idx + 1}",
                                color = Color.White,
                                fontWeight = FontWeight.Bold,
                                modifier = Modifier.weight(1f),
                                textAlign = TextAlign.Start
                            )
                            Text(
                                name + if (t.isDisabled) " (off)" else "",
                                color = if (t.isDisabled) Color.Gray else getEngineColor(t.engineType),
                                fontSize = 11.sp
                            )
                        }
                    }
                }

                Spacer(modifier = Modifier.height(12.dp))
                TextButton(onClick = onDismiss, modifier = Modifier.fillMaxWidth()) {
                    Text("Cancel", color = Color.Gray)
                }
            }
        }
    }
}

@OptIn(androidx.compose.animation.ExperimentalAnimationApi::class, androidx.compose.foundation.ExperimentalFoundationApi::class, androidx.compose.material3.ExperimentalMaterial3Api::class, androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
@Composable
fun PlayingScreen(state: GrooveboxState, onStateChange: (GrooveboxState) -> Unit, nativeLib: NativeLib, empledManager: EmpledManager? = null, midiManager: MidiManager? = null) {
    val latestState by rememberUpdatedState(state)
    val latestOnStateChange by rememberUpdatedState(onStateChange)
    val track = state.tracks[state.selectedTrackIndex]
    val isArpOn = track.arpConfig.mode != ArpMode.OFF
    val isLatched = track.arpConfig.isLatched
    val engineColor = getEngineColor(track.engineType)
    var showTransposeMenu by remember { mutableStateOf(false) }
    var showScaleMenu by remember { mutableStateOf(false) }
    var showArpMenu by remember { mutableStateOf(false) }
    var showDrumWizard by remember { mutableStateOf(false) }
    
    val scaleNotes = remember(state.rootNote, state.scaleType, state.gridMode) {
        ScaleLogic.generateScaleNotes(state.rootNote, state.scaleType, state.gridMode.padCount())
    }

    Row(modifier = Modifier.fillMaxSize()) {
        Box(modifier = Modifier.weight(0.25f).fillMaxHeight()) {
            TouchStripsPanel(state, onStateChange, nativeLib, engineColor)
        }
        Box(modifier = Modifier.weight(0.12f).fillMaxHeight()) {
            AssignableKnobsPanel(state, onStateChange, nativeLib, engineColor)
        }
        
        Box(modifier = Modifier.weight(0.66f).fillMaxHeight()) {
            BoxWithConstraints(modifier = Modifier.fillMaxSize().padding(8.dp)) {
                val platformInfo = LocalPlatformInfo.current
                val screenRatio = platformInfo.screenWidthDp.toFloat() / platformInfo.screenHeightDp.toFloat()
                
                val currentMaxWidth = maxWidth
                val currentMaxHeight = maxHeight
                
                // Identify if Sampler Chop Mode is active
                val samplerMode = track.parameters[320] ?: 0f
                val isChopMode = track.engineType == EngineType.SAMPLER && samplerMode >= 0.6f
                
                val rows = if (isChopMode) 4 else state.gridMode.padRows()
                val cols = if (isChopMode) 4 else state.gridMode.padCols()
                val isWideScreen = screenRatio > 1.8f && platformInfo.screenHeightDp < 500
                val spacing = 6.dp
                
                Column(modifier = Modifier.fillMaxSize()) {
                    // Spacer(modifier = Modifier.height(8.dp))
                    
                    Box(modifier = Modifier.weight(1f)) {
                Column(modifier = Modifier.fillMaxSize()) {
                    // --- Consistent Top Row Controls ---
                    val isTablet = platformInfo.screenWidthDp >= 600
                    val controlHeight = if (isTablet) 45.dp else 38.dp
                    val controlWidth = 52.dp
                    
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(bottom = 8.dp)
                            .horizontalScroll(rememberScrollState()),
                        horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.CenterHorizontally), 
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        // PAD MOD LEARN
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            Button(
                                onClick = {
                                    onStateChange(
                                        state.copy(
                                            isPadModLearnActive = !state.isPadModLearnActive,
                                            // The single grid has no bank: learn into the
                                            // selected track, not the last touched bank.
                                            padModLearnBank = -1
                                        )
                                    )
                                },
                                modifier = Modifier.height(controlHeight).width(controlWidth),
                                colors = ButtonDefaults.buttonColors(
                                    containerColor = if (state.isPadModLearnActive) Color.Cyan else Color.DarkGray
                                ),
                                shape = RoundedCornerShape(8.dp),
                                contentPadding = PaddingValues(0.dp)
                            ) {
                                Text("BND\nLRN", style = MaterialTheme.typography.labelSmall, fontSize = 9.sp, lineHeight = 10.sp, textAlign = TextAlign.Center, color = if (state.isPadModLearnActive) Color.Black else Color.White)
                            }
                            Text("PAD", style = MaterialTheme.typography.labelSmall, fontSize = 8.sp, color = Color.Gray)
                        }

                        // ROOT
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            Button(
                                onClick = { showTransposeMenu = true },
                                modifier = Modifier.height(controlHeight).width(controlWidth),
                                colors = ButtonDefaults.buttonColors(containerColor = Color.DarkGray),
                                shape = RoundedCornerShape(8.dp),
                                contentPadding = PaddingValues(0.dp)
                            ) {
                                Text(getNoteLabel(state.rootNote).filter { !it.isDigit() }, style = MaterialTheme.typography.titleMedium, color = Color.White)
                            }
                            Text("ROOT", style = MaterialTheme.typography.labelSmall, color = Color.Gray)
                        }

                        // SCALE
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            Button(
                                onClick = { showScaleMenu = true },
                                enabled = state.gridMode != GridMode.TONNETZ,
                                modifier = Modifier.height(controlHeight).width(controlWidth),
                                colors = ButtonDefaults.buttonColors(
                                    containerColor = if (state.gridMode == GridMode.TONNETZ) Color.DarkGray.copy(alpha=0.3f) else Color.DarkGray
                                ),
                                shape = RoundedCornerShape(8.dp),
                                contentPadding = PaddingValues(0.dp)
                            ) {
                                Text(state.scaleType.displayName.uppercase(), style = MaterialTheme.typography.labelSmall, maxLines = 1, 
                                     color = if (state.gridMode == GridMode.TONNETZ) Color.Gray else Color.White)
                            }
                            Text("SCALE", style = MaterialTheme.typography.labelSmall, color = Color.Gray)
                        }

                        // OCTAVE
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                                Button(
                                    onClick = { 
                                        val newRoot = (state.rootNote - 12).coerceAtLeast(0)
                                        onStateChange(state.copy(rootNote = newRoot))
                                        nativeLib.setScaleConfig(newRoot, state.scaleType.intervals.toIntArray())
                                    },
                                    modifier = Modifier.size(controlHeight),
                                    colors = ButtonDefaults.buttonColors(containerColor = Color.DarkGray),
                                    shape = RoundedCornerShape(8.dp),
                                    contentPadding = PaddingValues(0.dp)
                                ) { Text("-", style = MaterialTheme.typography.titleMedium, color = Color.White) }
                                Button(
                                    onClick = { 
                                        val newRoot = (state.rootNote + 12).coerceAtMost(110)
                                        onStateChange(state.copy(rootNote = newRoot))
                                        nativeLib.setScaleConfig(newRoot, state.scaleType.intervals.toIntArray())
                                    },
                                    modifier = Modifier.size(controlHeight),
                                    colors = ButtonDefaults.buttonColors(containerColor = Color.DarkGray),
                                    shape = RoundedCornerShape(8.dp),
                                    contentPadding = PaddingValues(0.dp)
                                ) { Text("+", style = MaterialTheme.typography.titleMedium, color = Color.White) }
                            }
                            Text("OCTAVE", style = MaterialTheme.typography.labelSmall, color = Color.Gray)
                        }

                        // GRID (4x4 -> 6x6 -> [Keys] -> Tonnetz -> 20/20 split)
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            val isSplit = state.playLayout == PlayLayout.SPLIT_20_20
                            Button(
                                onClick = {
                                    val includeMacKeys = platformInfo.platform == "macos" || state.isKeyboardModeEnabled
                                    val (nextMode, nextSplit) = nextGridStep(state.gridMode, isSplit, includeMacKeys)
                                    onStateChange(
                                        state.copy(
                                            gridMode = nextMode,
                                            playLayout = if (nextSplit) PlayLayout.SPLIT_20_20 else PlayLayout.GRID
                                        )
                                    )
                                },
                                modifier = Modifier.height(controlHeight).width(controlWidth),
                                colors = ButtonDefaults.buttonColors(
                                    containerColor = if (isSplit || state.gridMode != GridMode.GRID_4X4) Color(0xFF6200EE)
                                                     else Color.DarkGray
                                ),
                                shape = RoundedCornerShape(8.dp),
                                contentPadding = PaddingValues(0.dp)
                            ) {
                                Text(
                                    if (isSplit) "20/20" else state.gridMode.shortLabel(),
                                    style = MaterialTheme.typography.labelSmall,
                                    color = Color.White
                                )
                            }
                            Text("GRID", style = MaterialTheme.typography.labelSmall, color = Color.Gray)
                        }

                        // PAD MODE (Phase 6: AUTO / scales / keyboard / drum kits / slices / FX)
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            val nextMode = PadMode.values()[(state.padMode.ordinal + 1) % PadMode.values().size]
                            Button(
                                onClick = { onStateChange(state.copy(padMode = nextMode)) },
                                modifier = Modifier.height(controlHeight).width(controlWidth),
                                colors = ButtonDefaults.buttonColors(
                                    containerColor = if (state.padMode != PadMode.AUTO) Color(0xFF00BCD4) else Color.DarkGray
                                ),
                                shape = RoundedCornerShape(8.dp),
                                contentPadding = PaddingValues(0.dp)
                            ) {
                                Text(
                                    state.padMode.shortLabel(),
                                    style = MaterialTheme.typography.labelSmall,
                                    color = if (state.padMode != PadMode.AUTO) Color.Black else Color.White,
                                    maxLines = 1
                                )
                            }
                            Text("MODE", style = MaterialTheme.typography.labelSmall, color = Color.Gray)
                        }

                        // DRUM ROW WIZARD (drum tracks only)
                        if (track.engineType == EngineType.FM_DRUM || track.engineType == EngineType.ANALOG_DRUM) {
                            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                Button(
                                    onClick = { showDrumWizard = true },
                                    modifier = Modifier.height(controlHeight).width(controlWidth),
                                    colors = ButtonDefaults.buttonColors(
                                        containerColor = if (state.drumMapLearnRow >= 0) Color.Yellow else Color.DarkGray
                                    ),
                                    shape = RoundedCornerShape(8.dp),
                                    contentPadding = PaddingValues(0.dp)
                                ) {
                                    Text(
                                        if (state.drumMapLearnRow >= 0) "WAIT" else "MAP",
                                        style = MaterialTheme.typography.labelSmall,
                                        color = if (state.drumMapLearnRow >= 0) Color.Black else Color.White
                                    )
                                }
                                Text("DRUM", style = MaterialTheme.typography.labelSmall, color = Color.Gray)
                            }
                        }

                        // ARP
                        val currentState by rememberUpdatedState(latestState)
                        if (track.engineType != EngineType.FM_DRUM && track.engineType != EngineType.ANALOG_DRUM) {
                            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                val isArpOn = track.arpConfig.mode != ArpMode.OFF
                                val isLatched = track.arpConfig.isLatched
                                Box(
                                    modifier = Modifier
                                        .width(controlWidth).height(controlHeight)
                                        .background(if (isLatched) Color.Yellow else if (isArpOn) Color.Yellow.copy(alpha = 0.3f) else Color.DarkGray, RoundedCornerShape(8.dp))
                                        .border(1.dp, if (isArpOn) Color.Yellow else Color.Transparent, RoundedCornerShape(8.dp))
                                        .combinedClickable(
                                            onClick = {
                                                val t = currentState.tracks[currentState.selectedTrackIndex]
                                                val isOn = t.arpConfig.mode != ArpMode.OFF
                                                val isL = t.arpConfig.isLatched
                                                val (newMode, newLatched) = if (!isOn) Pair(ArpMode.UP, false) else if (!isL) Pair(t.arpConfig.mode, true) else Pair(ArpMode.OFF, false)
                                                val newArpConfig = t.arpConfig.copy(mode = newMode, isLatched = newLatched)
                                                val newTracks = currentState.tracks.mapIndexed { idx, tr -> if (idx == currentState.selectedTrackIndex) tr.copy(arpConfig = newArpConfig) else tr }
                                                latestOnStateChange(currentState.copy(tracks = newTracks))
                                                nativeLib.setArpConfig(currentState.selectedTrackIndex, newMode.ordinal, newArpConfig.octaves, newArpConfig.inversion, newLatched, newArpConfig.isMutated, newArpConfig.rhythms.map { it.toBooleanArray() }.toTypedArray(), newArpConfig.randomSequence.toIntArray(), newArpConfig.gateLengths.toFloatArray(), newArpConfig.probability, newArpConfig.weird)
                                            },
                                            onLongClick = { showArpMenu = true }
                                        ),
                                    contentAlignment = Alignment.Center
                                ) {
                                    Text("ARP", color = if (isLatched) Color.Black else if (isArpOn) Color.Yellow else Color.White, style = MaterialTheme.typography.labelSmall, fontWeight = FontWeight.Bold)
                                }
                                Text("ARP", style = MaterialTheme.typography.labelSmall, color = Color.Gray)
                            }
                        }
                    }

                    // --- Pad Grid ---
                    Box(modifier = Modifier.weight(1f), contentAlignment = Alignment.Center) {
                        if (state.playLayout == PlayLayout.SPLIT_20_20) {
                            SplitPadArea(
                                state = state,
                                latestState = latestState,
                                nativeLib = nativeLib,
                                onStateChange = onStateChange,
                                empledManager = empledManager
                            )
                        } else {
                            val padSize = minOf(
                                (currentMaxWidth - (spacing * (cols - 1))) / cols.coerceAtLeast(1),
                                (currentMaxHeight - (spacing * (rows - 1)) - controlHeight - 20.dp) / rows.coerceAtLeast(1),
                                135.dp
                            ).coerceAtLeast(40.dp)

                            PadGrid(rows, cols, padSize, spacing, track, state, scaleNotes, engineColor, nativeLib, latestState, onStateChange, latestOnStateChange, empledManager)
                        }
                    }
                }
            }
        }
    }
}
}

    // Scale Selection Bottom Sheet
    val sheetState = rememberModalBottomSheetState()
    if (showScaleMenu) {
        ModalBottomSheet(
            onDismissRequest = { showScaleMenu = false },
            sheetState = sheetState,
            containerColor = Color(0xFF111111),
            scrimColor = Color.Black.copy(alpha = 0.5f),
            dragHandle = { BottomSheetDefaults.DragHandle(color = Color.Gray) }
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(16.dp)
                    .padding(bottom = 32.dp)
            ) {
                Text(
                    "SELECT SCALE",
                    style = MaterialTheme.typography.titleMedium,
                    color = Color.White,
                    modifier = Modifier.padding(bottom = 16.dp)
                )
                
                LazyVerticalGrid(
                    columns = GridCells.Fixed(3),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    items(ScaleType.values()) { type ->
                        val isSelected = state.scaleType == type
                        Surface(
                            onClick = {
                                onStateChange(state.copy(scaleType = type))
                                nativeLib.setScaleConfig(state.rootNote, type.intervals.toIntArray())
                                showScaleMenu = false
                            },
                            color = if (isSelected) Color.Cyan.copy(alpha = 0.2f) else Color.White.copy(alpha = 0.05f),
                            shape = RoundedCornerShape(8.dp),
                            border = if (isSelected) BorderStroke(1.dp, Color.Cyan) else null,
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Box(
                                modifier = Modifier.padding(12.dp),
                                contentAlignment = Alignment.Center
                            ) {
                                Text(
                                    type.displayName.uppercase(),
                                    style = MaterialTheme.typography.labelMedium,
                                    color = if (isSelected) Color.Cyan else Color.White,
                                    maxLines = 1,
                                    textAlign = TextAlign.Center
                                )
                            }
                        }
                    }
                }
            }
        }
    }

    // Transpose Menu
    if (showTransposeMenu) {
        val sheetState = rememberModalBottomSheetState()
        ModalBottomSheet(
            onDismissRequest = { showTransposeMenu = false },
            sheetState = sheetState,
            containerColor = Color(0xFF111111),
            scrimColor = Color.Black.copy(alpha = 0.5f),
            dragHandle = { BottomSheetDefaults.DragHandle(color = Color.Gray) }
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(16.dp)
                    .padding(bottom = 32.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Text("SELECT ROOT NOTE", style = MaterialTheme.typography.titleMedium, color = Color.Gray, modifier = Modifier.padding(bottom = 16.dp))
                
                val noteNames = listOf("C", "C#", "D", "D#", "E", "F", "F#", "G", "G#", "A", "A#", "B")
                
                // Helper for button rendering
                val renderButton: @Composable (Int, String) -> Unit = { i, name ->
                    val currentOctave = state.rootNote / 12
                    Surface(
                        onClick = {
                            val newRoot = currentOctave * 12 + i
                            onStateChange(state.copy(rootNote = newRoot))
                            nativeLib.setScaleConfig(newRoot, state.scaleType.intervals.toIntArray())
                            showTransposeMenu = false
                        },
                        color = if (state.rootNote % 12 == i) Color.Cyan else Color.DarkGray,
                        shape = RoundedCornerShape(8.dp),
                        modifier = Modifier.size(50.dp)
                    ) {
                        Box(contentAlignment = Alignment.Center) {
                            Text(name, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold, color = if (state.rootNote % 12 == i) Color.Black else Color.White)
                        }
                    }
                }

                // Grid of notes
                FlowRow(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.Center,
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                    maxItemsInEachRow = 6
                ) {
                    noteNames.forEachIndexed { i, name -> 
                        Box(modifier = Modifier.padding(horizontal = 4.dp)) {
                            renderButton(i, name)
                        }
                    }
                }
            }
        }
    }

    ArpSettingsSheet(
        isOpen = showArpMenu,
        onDismiss = { showArpMenu = false },
        state = latestState,
        onStateChange = latestOnStateChange,
        nativeLib = nativeLib
    )

    if (showDrumWizard) {
        DrumRowWizard(
            state = state,
            onStateChange = onStateChange,
            onClose = { showDrumWizard = false }
        )
    }
}

/**
 * Human-readable name for a pad-modulation destination id.
 *
 * The ids are engine parameter ids, so several names collide across engines
 * (e.g. 1 is Subtractive cutoff and 151 is FM cutoff).  The mapping is
 * intentionally lossy: it only needs to tell two banks apart at a glance.
 */
private fun modTargetLabel(id: Int): String = when (id) {
    -1 -> "TRACK"
    0 -> "POSITION"
    1, 112, 151, 458 -> "CUTOFF"
    2, 113, 152, 459 -> "RES"
    3, 118, 464 -> "ENV AMT"
    10 -> "DETUNE"
    15, 465 -> "WARP"
    16, 466 -> "CRUSH"
    17, 467 -> "DRIVE"
    100 -> "ATTACK"
    101 -> "DECAY"
    102 -> "SUSTAIN"
    103 -> "RELEASE"
    107 -> "OSC1"
    108 -> "OSC2"
    109 -> "OSC3"
    110 -> "NOISE"
    154 -> "FEEDBACK"
    159 -> "FM DRIVE"
    300 -> "PITCH"
    302 -> "SPEED"
    303 -> "CUTOFF"
    304 -> "RES"
    314 -> "ENV AMT"
    406 -> "GRAIN"
    450 -> "MORPH"
    451 -> "DETUNE"
    475 -> "BITS"
    476 -> "SRATE"
    in 170..173 -> "OSC${id - 169} DRV"
    in 180..183 -> "OSC${id - 179} FOLD"
    in 160..195 -> "OP ${((id - 160) / 6) + 1} LVL"
    290 -> "MORPHX3"
    291 -> "FOLDX3"
    292 -> "DRIVEX3"
    in 2000..2015 -> "MACRO ${id - 1999}"
    else -> "#$id"
}
