package com.groovebox.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.forEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.material3.AlertDialog
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Text
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.TextButton
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.PointerEvent
import com.groovebox.ui.LocalPlatformInfo
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.groovebox.ui.platformShowMessage
import androidx.compose.ui.text.style.TextOverflow
import com.groovebox.GrooveboxState
import com.groovebox.NativeLib
import com.groovebox.EngineType
import com.groovebox.Fm1KnobBinding
import com.groovebox.TrackState
import com.groovebox.ui.LocalFocusedValue
import com.groovebox.ui.LocalFocusedSetter
import com.groovebox.ui.theme.getEngineColor
import kotlinx.coroutines.withTimeoutOrNull

private class KnobAssignmentContext(val trackIndex: Int, track: TrackState) {
    private val engineType = track.engineType
    private val selectedFmPreset = track.selectedFmPreset
    private val catalogEntryId = track.catalogEntryId
    private val selectedFmDrumInstrument = track.selectedFmDrumInstrument
    private val soundFontPath = track.soundFontPath
    private val soundFontPresetIndex = track.soundFontPresetIndex
    private val soundFontPresetName = track.soundFontPresetName
    private val lastSamplePath = track.lastSamplePath
    private val activeWavetableName = track.activeWavetableName
    private val parameters = track.parameters
    private val fm1KnobBindings = track.fm1KnobBindings
    private val fm1StepKnobBindings = track.fm1StepKnobBindings

    fun matches(state: GrooveboxState): Boolean {
        val current = state.tracks.getOrNull(trackIndex) ?: return false
        return state.selectedTrackIndex == trackIndex && current.engineType == engineType &&
            current.selectedFmPreset == selectedFmPreset && current.catalogEntryId == catalogEntryId &&
            current.selectedFmDrumInstrument == selectedFmDrumInstrument &&
            current.soundFontPath == soundFontPath && current.soundFontPresetIndex == soundFontPresetIndex &&
            current.soundFontPresetName == soundFontPresetName && current.lastSamplePath == lastSamplePath &&
            current.activeWavetableName == activeWavetableName && current.parameters == parameters &&
            current.fm1KnobBindings == fm1KnobBindings &&
            current.fm1StepKnobBindings == fm1StepKnobBindings
    }
}

@Composable
fun Knob(
    label: String, 
    initialValue: Float, 
    parameterId: Int, 
    state: GrooveboxState, 
    onStateChange: (GrooveboxState) -> Unit, 
    nativeLib: NativeLib,
    knobSize: Dp = 50.dp,
    onValueChangeOverride: ((Float) -> Unit)? = null,
    overrideValue: Float? = null,
    overrideColor: Color? = null,
    valueFormatter: ((Float) -> String)? = null,
    isBold: Boolean = false,
    showValue: Boolean = true,
    onLocalValueChange: (String?) -> Unit = {}, // New callback for performance
    fullLabel: String? = null,
    detentValue: Float? = null,
    labelFontSize: androidx.compose.ui.unit.TextUnit? = null
) {
    val latestState by rememberUpdatedState(state)
    val latestOnStateChange by rememberUpdatedState(onStateChange)
    val latestOnValueChangeOverride by rememberUpdatedState(onValueChangeOverride)
    val latestValueFormatter by rememberUpdatedState(valueFormatter)
    val trackIndex = state.selectedTrackIndex
    val selectedTrack = state.tracks[trackIndex]
    val platformInfo = LocalPlatformInfo.current
    val canAssignToFm1 = platformInfo.platform == "android" &&
        state.selectedTab == 1 && parameterId >= 0 && overrideValue == null &&
        onValueChangeOverride == null && initialValue.isFinite() && initialValue in 0f..1f &&
        !state.isParameterLocking && !state.isRecording && !state.midiLearnActive &&
        !state.lfoLearnActive && !state.macroLearnActive && !state.macroSourceLearnActive &&
        !state.isPadModLearnActive
    var assignmentContext by remember { mutableStateOf<KnobAssignmentContext?>(null) }
    var assignWhileHoldingStep by remember { mutableStateOf<Boolean?>(null) }
    // val context = LocalContext.current // Removed
    
    val localFocusedSetter = LocalFocusedSetter.current
    // Derived value logic
    val trackValue = if (parameterId != -1) (selectedTrack.parameters[parameterId] ?: initialValue) else initialValue
    val effectiveValue = overrideValue ?: trackValue

    val currentAssignment = assignmentContext
    val assignmentStillCurrent = canAssignToFm1 && currentAssignment?.matches(state) == true
    LaunchedEffect(assignmentContext, assignmentStillCurrent) {
        if (assignmentContext != null && !assignmentStillCurrent) {
            platformShowMessage("Sound changed. Hold the control again to assign it.")
            assignmentContext = null
        }
    }
    
    // local value state: 
    // - reset when parameterId changes
    // - reset when trackIndex changes (unless it's a global/shared override)
    // - reset when effectiveValue changes IF NOT HELD (to avoid fighting UI vs global state)
    var value by remember(parameterId, trackIndex, effectiveValue) { mutableStateOf(effectiveValue) }
    var isHeld by remember { mutableStateOf(false) }

    val isModTarget = latestState.tracks[trackIndex].padModTargetId == parameterId

    val engineColor = overrideColor ?: getEngineColor(state.tracks[state.selectedTrackIndex].engineType)
    
    // Check if this parameter is locked on the current track (any step)
    val hasLocks = remember(trackIndex, parameterId, state.tracks[trackIndex].steps, state.tracks[trackIndex].drumSteps) {
        val t = state.tracks[trackIndex]
        if (t.engineType == EngineType.FM_DRUM) {
            t.drumSteps.any { steps -> steps.any { it.parameterLocks.containsKey(parameterId) } }
        } else {
            t.steps.any { it.parameterLocks.containsKey(parameterId) }
        }
    }

    // Check if specifically locked on currently targeted step (if in p-lock mode)
    val isLockedOnTarget = remember(state.isParameterLocking, state.lockingTarget, trackIndex, parameterId, state.tracks) {
        if (!state.isParameterLocking || state.lockingTarget == null) false
        else if (state.tracks[trackIndex].engineType == EngineType.FM_DRUM) {
            val drumInstrumentIndex = state.lockingTarget!!.first 
            val stepIdx = state.lockingTarget!!.second 
            state.tracks[trackIndex].drumSteps.getOrNull(drumInstrumentIndex)?.getOrNull(stepIdx)?.parameterLocks?.containsKey(parameterId) ?: false
        } else {
             val stepIdx = state.lockingTarget!!.second 
             state.tracks[trackIndex].steps.getOrNull(stepIdx)?.parameterLocks?.containsKey(parameterId) ?: false
        }
    }

    LaunchedEffect(effectiveValue) {
        if (!isHeld) {
            value = effectiveValue
        }
    }
    
    Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.padding(4.dp)) {
        Box(
            modifier = Modifier
                .size(knobSize)
                .background(
                    color = if (isHeld) engineColor.copy(alpha = 0.4f) 
                            else if (state.isParameterLocking) Color.Magenta.copy(alpha = 0.3f) 
                            else if (state.midiLearnActive && state.midiLearnStep == 2 && parameterId >= 0) Color.Yellow.copy(alpha = 0.2f)
                            else if ((state.lfoLearnActive || state.macroLearnActive) && parameterId >= 0) Color.Green.copy(alpha = 0.1f)
                            else if (isBold) Color.Transparent
                            else Color.DarkGray, 
                    shape = RoundedCornerShape(knobSize / 2)
                )
                .border(
                    width = if ((isHeld || state.isParameterLocking || (state.midiLearnActive && state.midiLearnStep == 2 && parameterId >= 0)) && !isBold) 2.dp else 0.dp,
                    color = if (isHeld) engineColor 
                            else if (state.isParameterLocking) Color.Magenta 
                            else if (state.midiLearnActive && state.midiLearnStep == 2 && parameterId >= 0) Color.Yellow
                            else if ((state.lfoLearnActive || state.macroLearnActive || state.macroSourceLearnActive) && parameterId >= 0) Color.Green
                            else Color.Transparent,
                    shape = RoundedCornerShape(knobSize / 2)
                )
                .pointerInput(
                    parameterId, state.isParameterLocking, state.midiLearnActive, state.midiLearnStep,
                    state.lfoLearnActive, state.macroLearnActive, state.macroSourceLearnActive,
                    trackIndex, selectedTrack.engineType, selectedTrack.catalogEntryId,
                    selectedTrack.selectedFmPreset, selectedTrack.selectedFmDrumInstrument,
                    selectedTrack.fm1KnobBindings, selectedTrack.fm1StepKnobBindings, selectedTrack.soundFontPath,
                    selectedTrack.soundFontPresetIndex, selectedTrack.soundFontPresetName,
                    selectedTrack.lastSamplePath, selectedTrack.activeWavetableName, canAssignToFm1
                ) {
                    if (state.macroSourceLearnActive) {
                        detectTapGestures {
                            val macroIdx = latestState.macroSourceLearnIndex
                            if (macroIdx != -1) {
                                // SourceType 4 = Envelope, sourceIndex = -1 (not used for env), sourceTrackIndex = current track
                                nativeLib.setMacroSource(macroIdx, 4, -1, latestState.selectedTrackIndex)
                                val newMacros = latestState.macros.toMutableList()
                                newMacros[macroIdx] = newMacros[macroIdx].copy(
                                    sourceType = 4,
                                    sourceIndex = -1,
                                    sourceTrackIndex = latestState.selectedTrackIndex,
                                    sourceLabel = "TRACK ${latestState.selectedTrackIndex + 1} ENV"
                                )
                                latestOnStateChange(latestState.copy(macros = newMacros, macroSourceLearnActive = false))
                            }
                        }
                    } else if (state.midiLearnActive && state.midiLearnStep == 2) {
                        detectTapGestures {
                            if (parameterId >= 0) {
                                val stripIdx = latestState.midiLearnSelectedStrip ?: return@detectTapGestures
                                val newState = if (stripIdx < 4) {
                                    val newRoutings = latestState.stripRoutings.map {
                                        if (it.stripIndex == stripIdx) it.copy(targetType = 1, targetId = parameterId, parameterName = label)
                                        else it
                                    }
                                    latestState.copy(stripRoutings = newRoutings, midiLearnActive = false, midiLearnStep = 0, midiLearnSelectedStrip = null, selectedTab = 0)
                                } else {
                                    val knobIdx = stripIdx - 4
                                    val newRoutings = latestState.knobRoutings.mapIndexed { idx, item ->
                                        if (idx == knobIdx) item.copy(targetType = 1, targetId = parameterId, parameterName = label)
                                        else item
                                    }
                                    latestState.copy(knobRoutings = newRoutings, midiLearnActive = false, midiLearnStep = 0, midiLearnSelectedStrip = null, selectedTab = 0)
                                }
                                platformShowMessage("Bound $label to MIDI Strip ${stripIdx + 1}")
                                latestOnStateChange(newState)
                            }
                        }
                    } else if (state.lfoLearnActive || state.macroLearnActive) {
                        detectTapGestures {
                            if (parameterId >= 0) {
                                if (state.lfoLearnActive) {
                                    val lfoIdx = state.lfoLearnLfoIndex
                                    if (lfoIdx != -1) {
                                        val oldTargetId = state.lfos[lfoIdx].targetId
                                        if (oldTargetId != -1 && oldTargetId != parameterId) {
                                            nativeLib.setRouting(latestState.selectedTrackIndex, -1, 2 + lfoIdx, 5, 0.0f, oldTargetId)
                                        }
                                        nativeLib.setRouting(latestState.selectedTrackIndex, -1, 2 + lfoIdx, 5, 1.0f, parameterId)
                                        val newLfos = state.lfos.toMutableList()
                                        newLfos[lfoIdx] = newLfos[lfoIdx].copy(targetType = 1, targetId = parameterId, targetLabel = label)
                                        latestOnStateChange(latestState.copy(lfos = newLfos, lfoLearnActive = false))
                                    }
                                } else if (state.macroLearnActive) {
                                    val macroIdx = state.macroLearnMacroIndex
                                    val tIdx = state.macroLearnTargetIndex
                                    if (macroIdx != -1 && tIdx != -1) {
                                         val currentMacro = state.macros[macroIdx]
                                         val currentTargets = currentMacro.targets.toMutableList()
                                         if (tIdx < currentTargets.size) {
                                             val oldTargetId = currentTargets[tIdx].targetId
                                             if (oldTargetId != -1 && oldTargetId != parameterId) {
                                                 nativeLib.setRouting(latestState.selectedTrackIndex, -1, 10 + macroIdx, 5, 0.0f, oldTargetId)
                                             }
                                             nativeLib.setRouting(latestState.selectedTrackIndex, -1, 10 + macroIdx, 5, 1.0f, parameterId)
                                             currentTargets[tIdx] = currentTargets[tIdx].copy(targetId = parameterId, targetLabel = label)
                                             val newMacros = state.macros.toMutableList()
                                             newMacros[macroIdx] = newMacros[macroIdx].copy(targets = currentTargets)
                                             latestOnStateChange(latestState.copy(macros = newMacros, macroLearnActive = false))
                                         }
                                    }
                                }
                            }
                        }
                    } else {
                        forEachGesture {
                            try {
                            awaitPointerEventScope {
                                val down = awaitFirstDown()
                                
                                // PAD MOD LEARN LOGIC
                                if (latestState.isPadModLearnActive && parameterId != -1) {
                                    val learnBank = latestState.padModLearnBank
                                    latestOnStateChange(latestState.copy(
                                        isPadModLearnActive = false,
                                        padModLearnBank = -1,
                                        tracks = if (learnBank < 0) {
                                            latestState.tracks.mapIndexed { idx, t ->
                                                if (idx == latestState.selectedTrackIndex) t.copy(padModTargetId = parameterId)
                                                else t
                                            }
                                        } else latestState.tracks,
                                        splitLeft = if (learnBank == 0)
                                            latestState.splitLeft.copy(modTargetId = parameterId)
                                        else latestState.splitLeft,
                                        splitRight = if (learnBank == 1)
                                            latestState.splitRight.copy(modTargetId = parameterId)
                                        else latestState.splitRight
                                    ))
                                    return@awaitPointerEventScope
                                }
                                
                                isHeld = true
                                var lastY = down.position.y
                                
                                val getValStr: (Float) -> String = { v ->
                                    val formatter = latestValueFormatter
                                    if (formatter != null) {
                                        formatter(v)
                                    } else if (parameterId != -1 && (parameterId in 100..103 || parameterId in 150..152)) { 
                                        "%.2f".format(v)
                                    } else if (label == "MODE" && (parameterId == 320 || parameterId == 1531)) {
                                        val modeIdx = (v * 2.9f).toInt()
                                        when(modeIdx) {
                                            0 -> "1 SHOT"
                                            1 -> "SUSTAIN"
                                            2 -> "CHOP"
                                            else -> "REV"
                                        }
                                    } else {
                                        val upperLabel = label.uppercase()
                                        val displayLabel = (fullLabel ?: label).uppercase()
                                        when {
                                            parameterId == -1 || (parameterId == 0 && upperLabel == "VOL") -> String.format("%.2f", v)
                                            upperLabel == "MODE" -> {
                                                val modeIdx = (v * 11.9f).toInt()
                                                when(modeIdx) {
                                                    0 -> "UP"; 1 -> "UP+"; 2 -> "DOWN"; 3 -> "SUB+"; 4 -> "DUAL"
                                                    5 -> "5TH"; 6 -> "MAJ"; 7 -> "MIN"; 8 -> "DIM"; 9 -> "AUG"
                                                    10 -> "SUS"; else -> "ALL"
                                                }
                                            }
                                            upperLabel == "SHAPE" || upperLabel == "SHP" -> {
                                                 if (v < 0.2f) "SINE" else if (v < 0.4f) "TRI" else if (v < 0.6f) "SAW" else if (v < 0.8f) "SQR" else "RND"
                                            }
                                            upperLabel == "BPM" -> String.format("BPM: %.1f", 12f + (v * 228f))
                                            upperLabel == "ALGO" || upperLabel == "ROUTING" -> {
                                               val algo = (v * 3f).toInt() + 1
                                               val name = when(algo) { 1 -> "PIANO"; 2 -> "ORGAN"; 3 -> "BRASS"; else -> "SERIAL" }
                                               "ALGO: $name"
                                            }
                                            upperLabel == "SLICES" -> "SLICES: ${(v * 14f).toInt() + 2}"
                                            upperLabel == "WAVE" || upperLabel == "WAVETABLE" || upperLabel == "MORPH" -> {
                                               if (v < 0.25f) "SINE" else if (v < 0.5f) "TRI" else if (v < 0.75f) "SQU" else "SAW"
                                            }
                                            else -> String.format("%s: %.2f", displayLabel, v)
                                        }
                                    }
                                }
                                
                                localFocusedSetter.invoke(getValStr(value))

                                var pendingDragEvent: PointerEvent? = null
                                if (canAssignToFm1) {
                                    val slop = viewConfiguration.touchSlop
                                    var moved = false
                                    var released = false
                                    val longPressed = withTimeoutOrNull(viewConfiguration.longPressTimeoutMillis) {
                                        while (true) {
                                            val event = awaitPointerEvent()
                                            val change = event.changes.firstOrNull { it.id == down.id }
                                            if (change == null || !change.pressed || change.isConsumed) {
                                                released = true
                                                break
                                            }
                                            if ((change.position - down.position).getDistance() > slop) {
                                                moved = true
                                                pendingDragEvent = event
                                                break
                                            }
                                        }
                                        false
                                    } == null
                                    if (longPressed && !moved && !released) {
                                        localFocusedSetter.invoke(null)
                                        isHeld = false
                                        val current = latestState
                                        val target = current.tracks.getOrNull(trackIndex)
                                        if (target != null && current.selectedTrackIndex == trackIndex) {
                                            assignmentContext = KnobAssignmentContext(trackIndex, target)
                                            assignWhileHoldingStep = null
                                        }
                                        do {
                                            val event = awaitPointerEvent()
                                            event.changes.forEach { it.consume() }
                                        } while (event.changes.any { it.pressed })
                                        return@awaitPointerEventScope
                                    }
                                    if (released) {
                                        localFocusedSetter.invoke(null)
                                        isHeld = false
                                        return@awaitPointerEventScope
                                    }
                                }

                                do {
                                    val event = pendingDragEvent ?: awaitPointerEvent()
                                    pendingDragEvent = null
                                    val change = event.changes.first()
                                    if (change.pressed) {
                                        val dragAmount = lastY - change.position.y
                                        lastY = change.position.y
                                        
                                        val absDrag = Math.abs(dragAmount)
                                        val sensitivity = when {
                                            absDrag < 1.0f -> 0.0005f
                                            absDrag < 3.0f -> 0.0015f
                                            else -> 0.005f
                                        }
                                        
                                        var rawNextValue = (value + dragAmount * sensitivity)
                                        
                                        val upperLabel = label.uppercase()
                                        val effectiveDetent = detentValue ?: if (upperLabel == "PAN" || upperLabel == "P" || upperLabel == "BALANCE" || upperLabel == "BAL") 0.5f else null
                                        
                                        val nextValue = if (effectiveDetent != null) {
                                            if (rawNextValue in (effectiveDetent - 0.02f)..(effectiveDetent + 0.02f)) effectiveDetent else rawNextValue.coerceIn(0f, 1f)
                                        } else {
                                            rawNextValue.coerceIn(0f, 1f)
                                        }
                                        
                                        if (nextValue != value) {
                                            value = nextValue
                                            val valStr = getValStr(value)
                                            localFocusedSetter.invoke(valStr)

                                            if (latestOnValueChangeOverride != null) {
                                                latestOnValueChangeOverride!!.invoke(nextValue)
                                            } else if (parameterId != -1) {
                                                val isAutoLocking = latestState.isRecording && latestState.isPlaying
                                                val isLockingMode = latestState.isParameterLocking || isAutoLocking
                                                if (isLockingMode) {
                                                    nativeLib.setParameterPreview(trackIndex, parameterId, nextValue)
                                                    
                                                    val targetIdx = if (isAutoLocking) latestState.currentStep else latestState.lockingTarget?.second
                                                    if (targetIdx != null) {
                                                        nativeLib.setParameterLock(trackIndex, targetIdx, parameterId, nextValue)
                                                        val newState = latestState.copy(
                                                            focusedValue = valStr,
                                                            tracks = latestState.tracks.mapIndexed { tIdx, t ->
                                                                if (tIdx == trackIndex) {
                                                                    val sIdx = targetIdx
                                                                    if (t.engineType == EngineType.FM_DRUM) {
                                                                        val inst = t.selectedFmDrumInstrument
                                                                        t.copy(drumSteps = t.drumSteps.mapIndexed { di, ds ->
                                                                            if (di == inst) ds.mapIndexed { si, s -> if (si == sIdx) s.copy(parameterLocks = s.parameterLocks + (parameterId to nextValue)) else s }
                                                                            else ds
                                                                        })
                                                                    } else {
                                                                        t.copy(steps = t.steps.mapIndexed { si, s -> if (si == sIdx) s.copy(parameterLocks = s.parameterLocks + (parameterId to nextValue)) else s })
                                                                    }
                                                                } else t
                                                            }
                                                        )
                                                        latestOnStateChange(newState)
                                                    }
                                                } else {
                                                    nativeLib.setParameter(trackIndex, parameterId, nextValue)
                                                }
                                            }
                                        }
                                    }
                                    event.changes.forEach { it.consume() }
                                } while (event.changes.any { it.pressed })
                                
                                localFocusedSetter.invoke(null)
                                
                                if (parameterId != -1) {
                                    val isAutoLocking = latestState.isRecording && latestState.isPlaying
                                    val isLockingMode = latestState.isParameterLocking || isAutoLocking
                                    
                                    if (!isLockingMode) {
                                        val finalValue = value
                                        latestOnStateChange(latestState.copy(
                                            focusedValue = null,
                                            tracks = latestState.tracks.mapIndexed { idx, t ->
                                                if (idx == trackIndex) t.copy(parameters = t.parameters + (parameterId to finalValue))
                                                else t
                                            }
                                        ))
                                    } else {
                                        latestOnStateChange(latestState.copy(focusedValue = null))
                                    }
                                } else {
                                    latestOnStateChange(latestState.copy(focusedValue = null))
                                }
                                isHeld = false
                            }
                            } finally {
                                if (isHeld) {
                                    localFocusedSetter.invoke(null)
                                    isHeld = false
                                }
                            }
                        }
                    }
                },
            contentAlignment = Alignment.Center
        ) {
            // Knob Visuals
            androidx.compose.foundation.Canvas(modifier = Modifier.fillMaxSize().padding(6.dp)) {
                val radius = knobSize.toPx() / 2 - 8.dp.toPx()
                val center = Offset(size.width / 2, size.height / 2)
                
                if (isBold) {
                    drawCircle(color = engineColor, radius = radius + 4.dp.toPx(), center = center)
                    val angle = 135f + (value * 270f)
                    val angleRad = Math.toRadians(angle.toDouble())
                    val indicatorLen = radius + 2.dp.toPx()
                    val indicatorX = center.x + Math.cos(angleRad).toFloat() * indicatorLen
                    val indicatorY = center.y + Math.sin(angleRad).toFloat() * indicatorLen
                    drawLine(color = Color.White, start = center, end = Offset(indicatorX, indicatorY), strokeWidth = 3.dp.toPx(), cap = StrokeCap.Round)
                    drawCircle(color = Color.White.copy(alpha = 0.2f), radius = radius + 4.dp.toPx(), center = center, style = androidx.compose.ui.graphics.drawscope.Stroke(width = 1.dp.toPx()))
                } else {
                    drawCircle(color = Color.Black.copy(alpha = 0.3f), radius = radius, style = androidx.compose.ui.graphics.drawscope.Stroke(width = 4.dp.toPx()))
                    val upperLabel = label.uppercase()
                    val effectiveDetent = detentValue ?: if (upperLabel == "PAN" || upperLabel == "P" || upperLabel == "BALANCE" || upperLabel == "BAL") 0.5f else null
                    if (effectiveDetent != null) {
                        val detentLen = 6.dp.toPx()
                        val detentAngle = 135f + (effectiveDetent * 270f)
                        val detentRad = Math.toRadians(detentAngle.toDouble())
                        if (effectiveDetent == 0.5f) {
                             drawLine(color = Color.White.copy(alpha = 0.4f), start = Offset(center.x, center.y - radius - 2.dp.toPx()), end = Offset(center.x, center.y - radius + detentLen), strokeWidth = 2.dp.toPx())
                        } else {
                            val outer = radius + 4.dp.toPx()
                            val inner = radius - 2.dp.toPx()
                            drawLine(color = Color.White.copy(alpha = 0.4f), start = Offset(center.x + Math.cos(detentRad).toFloat() * inner, center.y + Math.sin(detentRad).toFloat() * inner), end = Offset(center.x + Math.cos(detentRad).toFloat() * outer, center.y + Math.sin(detentRad).toFloat() * outer), strokeWidth = 2.dp.toPx())
                        }
                    }
                    drawArc(color = engineColor, startAngle = 135f, sweepAngle = value * 270f, useCenter = false, topLeft = Offset(center.x - radius, center.y - radius), size = Size(radius * 2, radius * 2), style = androidx.compose.ui.graphics.drawscope.Stroke(width = 4.dp.toPx(), cap = StrokeCap.Round))
                }
                if (isModTarget && latestState.isPadModLearnActive) {
                    drawCircle(color = Color.Cyan, radius = radius + 8.dp.toPx(), center = center, style = androidx.compose.ui.graphics.drawscope.Stroke(width = 1.5.dp.toPx()))
                }
                if (isLockedOnTarget) drawCircle(color = Color.Magenta, radius = 4.dp.toPx(), center = center)
            }
            if (hasLocks) {
                Box(modifier = Modifier.align(Alignment.TopEnd).padding(2.dp).size(6.dp).background(Color.Magenta, CircleShape))
            }
            val formatter = latestValueFormatter
            val displayText = if (formatter != null) formatter(value) else when {
                label.uppercase() == "MODE" -> if (parameterId == 320) { val modeIdx = (value * 2.9f).toInt(); when(modeIdx) { 0 -> "1 SHOT"; 1 -> "SUSTAIN"; 2 -> "CHOP"; else -> "REV" } } else { val modeIdx = (value * 11.9f).toInt(); when(modeIdx) { 0 -> "UP"; 1 -> "UP+"; 2 -> "DOWN"; 3 -> "SUB+"; 4 -> "DUAL"; 5 -> "PWR"; 6 -> "MAJ"; 7 -> "MIN"; 8 -> "DIM"; 9 -> "AUG"; 10 -> "SUS"; else -> "ALL" } }
                label.uppercase().startsWith("SHP") || label.uppercase().contains("SHAPE") -> { if (value < 0.2f) "SINE" else if (value < 0.4f) "TRI" else if (value < 0.6f) "SAW" else if (value < 0.8f) "SQR" else "RND" }
                label.uppercase().startsWith("MIDI") || label.uppercase().contains("CHANNEL") || label.uppercase() == "CH" -> { val ch = (value * 17.0f).toInt(); when(ch) { 0 -> "NONE"; 17 -> "ALL"; else -> "$ch" } }
                label.uppercase() == "BPM" -> "${(12f + (value * 228f)).toInt()}"
                label.uppercase() == "ALGO" || label.uppercase() == "ROUTING" -> { val algo = (value * 3f).toInt() + 1; when(algo) { 1 -> "PIANO"; 2 -> "ORGAN"; 3 -> "BRASS"; else -> "SERIAL" } }
                label.uppercase() == "SLICES" -> "${(value * 14f).toInt() + 2}"
                label.uppercase() == "WAVE" || label.uppercase() == "WAVETABLE" || label.uppercase() == "MORPH" -> { if (value < 0.25f) "SINE" else if (value < 0.5f) "TRI" else if (value < 0.75f) "SQU" else "SAW" }
                else -> "${(value * 100).toInt()}"
            }
            if (showValue) {
                Text(text = displayText, style = MaterialTheme.typography.labelSmall, fontSize = if (displayText.length > 4) 8.sp else 10.sp, maxLines = 1, softWrap = false, color = if (isHeld) Color.White else Color(0xFFEEEEEE).copy(alpha = 0.6f))
            }
        }
        val isTablet = LocalPlatformInfo.current.isTablet
        val baseFontSize = labelFontSize ?: (if (isTablet) 8.sp else 10.sp)
        Text(label.uppercase(), style = MaterialTheme.typography.labelSmall, color = if (isHeld) Color.White else Color(0xFFEEEEEE), fontSize = baseFontSize.let { base -> if (label.length > 5) (base.value - 1).sp else base }, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
    if (assignmentStillCurrent && currentAssignment != null) {
        val bindings = if (assignWhileHoldingStep == true)
            state.tracks[currentAssignment.trackIndex].fm1StepKnobBindings
        else state.tracks[currentAssignment.trackIndex].fm1KnobBindings
        AlertDialog(
            onDismissRequest = { assignmentContext = null },
            title = { Text(if (assignWhileHoldingStep == null) "Choose FM-1 controls" else if (assignWhileHoldingStep == true) "While holding a step" else "Sound knobs") },
            text = {
                Column {
                    if (assignWhileHoldingStep == null) {
                        Text("Assign ${fullLabel ?: label} to:")
                        TextButton(onClick = { assignWhileHoldingStep = false }) { Text("Sound knobs") }
                        TextButton(onClick = { assignWhileHoldingStep = true }) { Text("While holding a step") }
                    } else {
                        Text("${fullLabel ?: label} · FM-1 displays this control as 0–100%")
                        Text("Save the sound preset to keep these assignments.")
                        repeat(4) { slot ->
                            val currentLabel = bindings?.getOrNull(slot)?.label ?: if (bindings == null) {
                                if (assignWhileHoldingStep == true) "Last visited" else "Automatic"
                            } else "Empty"
                            TextButton(onClick = {
                                val current = latestState
                                val target = current.tracks.getOrNull(currentAssignment.trackIndex)
                                if (target != null && currentAssignment.matches(current)) {
                                    assignmentContext = null
                                    val updated = List(4) { index ->
                                        if (index == slot) Fm1KnobBinding(parameterId, fullLabel ?: label, initialValue)
                                        else bindings?.getOrNull(index)
                                    }
                                    latestOnStateChange(current.copy(tracks = current.tracks.mapIndexed { index, track ->
                                        if (index == currentAssignment.trackIndex) {
                                            if (assignWhileHoldingStep == true) track.copy(fm1StepKnobBindings = updated)
                                            else track.copy(fm1KnobBindings = updated)
                                        } else track
                                    }))
                                } else {
                                    platformShowMessage("Sound changed. Hold the control again to assign it.")
                                    assignmentContext = null
                                }
                            }) { Text("Knob ${slot + 1} · $currentLabel") }
                        }
                    }
                }
            },
            confirmButton = {
                if (assignWhileHoldingStep != null) TextButton(onClick = {
                    val current = latestState
                    val target = current.tracks.getOrNull(currentAssignment.trackIndex)
                    if (target != null && currentAssignment.matches(current)) {
                        assignmentContext = null
                        latestOnStateChange(current.copy(tracks = current.tracks.mapIndexed { index, track ->
                            if (index == currentAssignment.trackIndex) {
                                if (assignWhileHoldingStep == true) track.copy(fm1StepKnobBindings = null)
                                else track.copy(fm1KnobBindings = null)
                            } else track
                        }))
                    } else {
                        platformShowMessage("Sound changed. Hold the control again to assign it.")
                        assignmentContext = null
                    }
                }) { Text("Reset to default controls") }
            },
            dismissButton = { TextButton(onClick = { assignmentContext = null }) { Text("Cancel") } }
        )
    }
}
