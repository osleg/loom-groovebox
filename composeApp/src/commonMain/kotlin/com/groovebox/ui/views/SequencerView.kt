package com.groovebox.ui.views

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.combinedClickable
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.material3.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.runtime.*
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.input.pointer.pointerInput

import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.window.Popup
import kotlinx.coroutines.delay
import kotlinx.coroutines.withTimeoutOrNull
import androidx.compose.foundation.gestures.awaitFirstDown

import com.groovebox.GrooveboxState
import com.groovebox.NativeLib
import com.groovebox.EngineType
import com.groovebox.StepState
import com.groovebox.midi.EmpledManager
import com.groovebox.ui.components.Knob
import com.groovebox.ui.components.NativeFileDialog
import com.groovebox.ui.LocalPlatformInfo
import com.groovebox.persistence.PersistenceManager
import java.io.File
import com.groovebox.ui.theme.getEngineColor

private fun syncTrackToEngine(
    selectedTrackIndex: Int,
    newTrackState: com.groovebox.TrackState,
    nativeLib: com.groovebox.NativeLib,
    latestState: com.groovebox.GrooveboxState
) {
    val engineType = newTrackState.engineType

    // Set Config First
    nativeLib.clearSequencer(selectedTrackIndex)
    nativeLib.setSequencerConfig(selectedTrackIndex, newTrackState.numPages, newTrackState.stepsPerPage)
    if (selectedTrackIndex == latestState.selectedTrackIndex) {
        nativeLib.setPatternLength(selectedTrackIndex, newTrackState.numPages * newTrackState.stepsPerPage)
    }

    if (engineType == EngineType.FM_DRUM || engineType == EngineType.ANALOG_DRUM) {
        for (instIdx in 0 until 16) {
            val voiceSteps = newTrackState.drumSteps.getOrNull(instIdx) ?: emptyList()
            voiceSteps.forEachIndexed { stepIdx, s ->
                nativeLib.setStep(
                    selectedTrackIndex, stepIdx, s.active, intArrayOf(60 + instIdx),
                    s.velocity, s.ratchet, s.punch, s.probability, s.gate, s.isSkipped,
                    s.subStepOffset, s.noteOffsets.toFloatArray(), s.noteVelocities.toFloatArray(),
                    ((s.noteGates as Any?) as? List<Float> ?: emptyList()).toFloatArray(),
                    s.parameterLocks.keys.toIntArray(), s.parameterLocks.values.toFloatArray()
                )
            }
        }
    } else {
        newTrackState.steps.forEachIndexed { stepIdx, s ->
            val isActiveWithNotes = s.active && s.notes.isNotEmpty()
            nativeLib.setStep(
                selectedTrackIndex, stepIdx, isActiveWithNotes, s.notes.toIntArray(),
                s.velocity, s.ratchet, s.punch, s.probability, s.gate, s.isSkipped,
                s.subStepOffset, s.noteOffsets.toFloatArray(), s.noteVelocities.toFloatArray(),
                    ((s.noteGates as Any?) as? List<Float> ?: emptyList()).toFloatArray(),
                    s.parameterLocks.keys.toIntArray(), s.parameterLocks.values.toFloatArray()
            )
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun SequencerView(viewModel: com.groovebox.GrooveboxViewModel, nativeLib: NativeLib, empledManager: EmpledManager? = null) {

    val state = viewModel.state
    val onStateChange = viewModel::onStateChange
    val latestState = state 
    val latestOnStateChange = onStateChange
    
    // Ensure we have a valid selection
    val selectedTrackIndex = state.selectedTrackIndex.coerceIn(0, state.tracks.lastIndex)
    val track = state.tracks[selectedTrackIndex]
    
    // Calculations for layout
    val platformInfo = LocalPlatformInfo.current
    val screenRatio = platformInfo.screenWidthDp.toFloat() / platformInfo.screenHeightDp.toFloat()
    val isWideScreen = screenRatio > 1.8f && platformInfo.screenHeightDp < 500
    
    // Renaming Logic
    var renamingSliceIndex by remember { mutableStateOf<Int?>(null) }
    var renamingCurrentValue by remember { mutableStateOf("") }
    
    if (renamingSliceIndex != null) {
        AlertDialog(
            onDismissRequest = { renamingSliceIndex = null },
            title = { Text("Rename Track") },
            text = {
                Column {
                    OutlinedTextField(
                        value = renamingCurrentValue,
                        onValueChange = { renamingCurrentValue = it },
                        label = { Text("New Name") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth()
                    )
                }
            },
            confirmButton = {
                Button(onClick = {
                    val idx = renamingSliceIndex!!
                    val newSubNames = track.subTrackNames.toMutableMap()
                    if (renamingCurrentValue.isBlank()) {
                        newSubNames.remove(idx)
                    } else {
                        newSubNames[idx] = renamingCurrentValue
                    }
                    val newTracks = latestState.tracks.toMutableList()
                    newTracks[selectedTrackIndex] = track.copy(subTrackNames = newSubNames)
                    latestOnStateChange(latestState.copy(tracks = newTracks))
                    renamingSliceIndex = null
                }) { Text("OK") }
            },
            dismissButton = {
                Button(onClick = { renamingSliceIndex = null }) { Text("Cancel") }
            }
        )
    }

    Column(modifier = Modifier.fillMaxSize()) {
        // Main UI Components
        Row(modifier = Modifier.weight(1f).fillMaxWidth()) {
            // Main Sequencing Area
            Column(modifier = Modifier.weight(1f).fillMaxHeight().padding(8.dp)) {
                 // Header: Clear / Bank / Grid Controls
                 Row(
                     modifier = Modifier.fillMaxWidth().height(40.dp),
                     horizontalArrangement = Arrangement.SpaceBetween,
                     verticalAlignment = Alignment.CenterVertically
                 ) {
                     // Clock Divider Controls (Refined v1.11.3 - Knob)
                     Row(verticalAlignment = Alignment.CenterVertically) {
                         Text("CLK:", color = Color.Gray, fontSize = 10.sp, modifier = Modifier.padding(end = 4.dp))
                         val divisions = listOf(0.333f, 0.5f, 0.6666f, 0.75f, 0.8f, 1.0f, 1.25f, 1.5f, 2.0f)
                         val labels = listOf("1/3", "1/2", "2/3", "3/4", "4/5", "1x", "1.25", "1.5", "2x")
                         
                         // Find current index to set initial value
                         val currentIdx = divisions.mapIndexed { idx, it -> idx to Math.abs(it - track.clockMultiplier) }
                             .minByOrNull { it.second }?.first ?: 5 // Default to 1x (index 5)
                             
                         Knob(
                             label = "",
                             initialValue = currentIdx.toFloat() / (divisions.size - 1),
                             parameterId = -1,
                             state = latestState,
                             onStateChange = latestOnStateChange,
                             nativeLib = nativeLib,
                             knobSize = 32.dp,
                             onValueChangeOverride = { v ->
                                 val idx = (v * (divisions.size - 1) + 0.5f).toInt().coerceIn(0, divisions.size - 1)
                                 val speed = divisions[idx]
                                 val newTracks = latestState.tracks.toMutableList()
                                 newTracks[selectedTrackIndex] = track.copy(clockMultiplier = speed)
                                 latestOnStateChange(latestState.copy(tracks = newTracks))
                                 nativeLib.setClockMultiplier(selectedTrackIndex, speed)
                             },
                             valueFormatter = { v -> 
                                 val idx = (v * (divisions.size - 1) + 0.5f).toInt().coerceIn(0, divisions.size - 1)
                                 labels[idx]
                             }
                         )
                     }
                     
                     // Sequence File Management Logic
                     var showSeqLoad by remember { mutableStateOf(false) }
                     var showSeqSave by remember { mutableStateOf(false) }

                     if (showSeqSave) {
                         val defaultDir = File(PersistenceManager.getLoomFolder(), "Sequences").apply { if(!exists()) mkdirs() }
                         NativeFileDialog(
                            directory = defaultDir,
                            onDismiss = { showSeqSave = false },
                            state = state,
                            onFileSelected = { path ->
                                val rawName = File(path).name.removeSuffix(".gbs")
                                // Strip audio extensions if present
                                val name = rawName.replace(Regex("\\.(wav|mp3|aif|ogg|flac)$", RegexOption.IGNORE_CASE), "")
                                PersistenceManager.saveSequence(track, name)
                            },
                            isSave = true,
                            trackIndex = selectedTrackIndex,
                            extensions = listOf("gbs"),
                            title = "SAVE SEQ"
                         )
                     }

                     if (showSeqLoad) {
                         val defaultDir = File(PersistenceManager.getLoomFolder(), "Sequences").apply { if(!exists()) mkdirs() }
                         NativeFileDialog(
                            directory = defaultDir,
                            onDismiss = { showSeqLoad = false },
                            state = state,
                            onFileSelected = { path ->
                                val file = File(path)
                                val isMidi = path.endsWith(".mid", ignoreCase = true) || path.endsWith(".midi", ignoreCase = true)
                                
                                val newTrackState = if (isMidi) {
                                    PersistenceManager.loadMidiSequence(track, path)
                                } else {
                                    val name = file.name.removeSuffix(".gbs")
                                    PersistenceManager.loadSequence(track, name)
                                }
                                
                                if (newTrackState != null) {
                                    val newTracks = state.tracks.toMutableList()
                                    newTracks[selectedTrackIndex] = newTrackState.copy(id = track.id)
                                    onStateChange(state.copy(tracks = newTracks))
                                    
                                    // SYNC TO ENGINE
                                    syncTrackToEngine(selectedTrackIndex, newTrackState, nativeLib, state)
                                }
                            },
                            isSave = false,
                            trackIndex = selectedTrackIndex,
                            extensions = listOf("gbs", "mid", "midi"),
                            title = "LOAD SEQ"
                         )
                     }


                     // Bank Select (1, 2, 3, 4 + 64)
                     Row(horizontalArrangement = Arrangement.spacedBy(4.dp), verticalAlignment = Alignment.CenterVertically) {
                         (0..3).forEach { bank ->
                             val isSelected = state.currentSequencerBank == bank && !state.is64StepView
                             val hasSteps = if (track.engineType == EngineType.FM_DRUM) {
                                  // Check drum steps for this bank
                                  val drumInst = track.selectedFmDrumInstrument
                                  track.drumSteps.getOrNull(drumInst)?.subList(bank * 16, (bank + 1) * 16)?.any { it.active } ?: false
                             } else {
                                  track.steps.subList(bank * 16, (bank + 1) * 16).any { it.active }
                             }
                             
                             val engineColor = getEngineColor(track.engineType)
                             
                             Button(
                                 onClick = { latestOnStateChange(latestState.copy(currentSequencerBank = bank, is64StepView = false)) },
                                 modifier = Modifier.height(32.dp).width(32.dp),
                                 contentPadding = PaddingValues(0.dp),
                                 colors = ButtonDefaults.buttonColors(containerColor = if (isSelected) engineColor else if (hasSteps) Color.Gray else Color.DarkGray)
                             ) { Text("${bank + 1}", color = if (isSelected) Color.Black else Color.White, fontSize = 10.sp) }
                         }

                         // 64-Step View Toggle (Moved here)
                         Button(
                             onClick = { 
                                 val new64 = !latestState.is64StepView
                                 // Auto-expand sequence length if entering 64-step view and length is short
                                 if (new64) {
                                     // We need to update the engine state. 
                                     nativeLib.setSequencerConfig(selectedTrackIndex, 4, 16)
                                     nativeLib.setPatternLength(selectedTrackIndex, 64) // CRITICAL: Ensure engine progresses through 64 steps
                                     
                                     // Update Kotlin State
                                     val newTrack = track.copy(numPages = 4, stepsPerPage = 16, patternLength = 64)
                                     latestOnStateChange(latestState.copy(is64StepView = true, tracks = latestState.tracks.mapIndexed { i, t -> if (i == selectedTrackIndex) newTrack else t }))
                                 } else {
                                     // Revert to 16 steps
                                     nativeLib.setSequencerConfig(selectedTrackIndex, 1, 16)
                                     nativeLib.setPatternLength(selectedTrackIndex, 16)
                                     val newTrack = track.copy(numPages = 1, stepsPerPage = 16, patternLength = 16)
                                     latestOnStateChange(latestState.copy(is64StepView = false, tracks = latestState.tracks.mapIndexed { i, t -> if (i == selectedTrackIndex) newTrack else t }))
                                 }
                             },
                             modifier = Modifier.height(32.dp).width(32.dp),
                             contentPadding = PaddingValues(0.dp),
                             colors = ButtonDefaults.buttonColors(containerColor = if (latestState.is64StepView) Color.Cyan else Color.DarkGray)
                         ) { Text("64", fontSize = 10.sp, color = if (latestState.is64StepView) Color.Black else Color.White) }
                     }
                     
                     // Sequencer Tools (Copy/Paste / Save/Load)
                     Row(horizontalArrangement = Arrangement.spacedBy(4.dp), verticalAlignment = Alignment.CenterVertically) {
                         Spacer(modifier = Modifier.width(30.dp)) // ADDED BUFFER
                         // COPY
                         Button(
                             onClick = {
                                 if (track.engineType == EngineType.FM_DRUM) {
                                     // Smart Copy for Drums: Trim to used length (rounded up to bar)
                                     // Check all 8 lanes
                                     var maxStep = 0
                                     track.drumSteps.forEach { lane -> 
                                         val last = lane.indexOfLast { it.active } 
                                         if (last > maxStep) maxStep = last
                                     }
                                     val smartLen = ((maxStep / 16) + 1) * 16
                                     val patternLen = (track.numPages * track.stepsPerPage)
                                     val copyLen = smartLen.coerceIn(16, patternLen)

                                     val trimmedDrums = track.drumSteps.map { it.take(copyLen) }
                                     latestOnStateChange(latestState.copy(copiedDrumSteps = trimmedDrums, copiedSteps = null))
                                 } else {
                                     // Smart Copy: Trim to last active step (rounded up to 16)
                                     val lastActive = track.steps.indexOfLast { it.active }
                                     val smartLen = ((lastActive / 16) + 1) * 16
                                     val patternLen = (track.numPages * track.stepsPerPage)
                                     val copyLen = smartLen.coerceIn(16, patternLen)
                                     
                                     latestOnStateChange(latestState.copy(copiedSteps = track.steps.take(copyLen), copiedDrumSteps = null))
                                 }
                             },
                             modifier = Modifier.height(32.dp).width(45.dp),
                             contentPadding = PaddingValues(0.dp),
                             colors = ButtonDefaults.buttonColors(containerColor = Color.DarkGray)
                         ) { Text("CPY", fontSize = 10.sp, color = Color.White) }

                         // PASTE
                         val hasClipboard = latestState.copiedSteps != null || latestState.copiedDrumSteps != null
                         Button(
                             onClick = {
                                 if (hasClipboard) {
                                     if (track.engineType == EngineType.FM_DRUM && latestState.copiedDrumSteps != null) {
                                         val srcDrumSteps = latestState.copiedDrumSteps!!
                                         // Smart Paste for Drums? (Checking 8 dimensions is hard, defaulting to overwrite/merge logic if simple)
                                         // User request specifically mentions "paste data shorter than sequence".
                                         // We'll implement strict overwrite for drums for now unless requested, OR apply same logic per drum lane?
                                         // Let's stick to simple overwrite for complex drum tracks to avoid partial states, OR try to find a global gap?
                                         // Simplification: Direct overwrite for Drums as before (but using trimmed data).
                                         val newDrumSteps = latestState.tracks[selectedTrackIndex].drumSteps.mapIndexed { idx, existingLane ->
                                             if (idx < srcDrumSteps.size) {
                                                 val srcLane = srcDrumSteps[idx]
                                                 if (srcLane.size < existingLane.size) {
                                                     // Smart Append for Drums?
                                                     // Find gap in THIS lane? No, drum tracks should stay aligned.
                                                     // Just pad it? Or repeat?
                                                     // Let's just Paste at 0.
                                                     val padded = existingLane.toMutableList()
                                                     for(i in srcLane.indices) padded[i] = srcLane[i]
                                                     padded
                                                 } else srcLane
                                             } else existingLane
                                         }
                                         
                                         // Update State
                                         latestOnStateChange(latestState.copy(tracks = latestState.tracks.mapIndexed { idx, t -> if (idx == selectedTrackIndex) t.copy(drumSteps = newDrumSteps) else t }))
                                           // Sync Native Engine (Apply Overwrite)
                                           newDrumSteps.forEachIndexed { drumIdx, steps ->
                                                if (drumIdx < 8) {
                                                    steps.forEachIndexed { stepIdx, step -> 
                                                        if (step.active) {
                                                             val drumNote = 60 + drumIdx
                                                             val finalNotes = if (step.notes.isNotEmpty()) step.notes.toIntArray() else intArrayOf(drumNote)
                                                             nativeLib.setStep(
                                                                 selectedTrackIndex, stepIdx, true, finalNotes, 
                                                                 step.velocity, step.ratchet, step.punch, 
                                                                 step.probability, step.gate, step.isSkipped,
                                                                 step.subStepOffset, 
                                                                 step.noteOffsets.toFloatArray(),
                                                                 step.noteVelocities.toFloatArray(),
                                                                 ((step.noteGates as Any?) as? List<Float> ?: emptyList()).toFloatArray(),
                                                                 step.parameterLocks.keys.toIntArray(), step.parameterLocks.values.toFloatArray()
                                                             )
                                                        } else {
                                                             nativeLib.setStep(selectedTrackIndex, stepIdx, false, intArrayOf(), 0f, 1, false, 0f, 0f, false, 0.0f, null, null)
                                                        }
                                                    }
                                                }
                                           }
                                          } else if (track.engineType != EngineType.FM_DRUM && latestState.copiedSteps != null) {
                                              val srcSteps = latestState.copiedSteps!!
                                              val targetSteps = track.steps
                                              val srcLen = srcSteps.size
                                              val targetLen = targetSteps.size
                                              
                                              // Auto-expand: if source is longer, grow target to match
                                              val finalLen = maxOf(srcLen, targetLen)
                                              val newSteps = targetSteps.toMutableList()
                                              while (newSteps.size < finalLen) {
                                                  newSteps.add(com.groovebox.StepState())
                                              }
                                              
                                              // Always paste at step 0 (overwrite mode)
                                              for (i in 0 until srcLen) {
                                                  val srcStep = srcSteps[i]
                                                  if (srcStep.active) {
                                                      newSteps[i] = srcStep
                                                  }
                                              }
                                              
                                              // If we expanded, update native sequencer config to new length
                                              if (finalLen > targetLen) {
                                                  nativeLib.setSequencerConfig(selectedTrackIndex, finalLen, -1)
                                              }
                                              
                                              latestOnStateChange(latestState.copy(tracks = latestState.tracks.mapIndexed { idx, t -> if (idx == selectedTrackIndex) t.copy(steps = newSteps) else t }))
                                              
                                              // Sync Native (Full Sync for consistency after paste)
                                              newSteps.forEachIndexed { stepIdx, step ->
                                                   if (step.active) {
                                                       val finalNotes = if (step.notes.isNotEmpty()) {
                                                           step.notes.toIntArray()
                                                       } else {
                                                           intArrayOf(60)
                                                       }
                                                       
                                                       nativeLib.setStep(
                                                           selectedTrackIndex, stepIdx, true, finalNotes, 
                                                           step.velocity, step.ratchet, step.punch, 
                                                           step.probability, step.gate, step.isSkipped,
                                                           step.subStepOffset,
                                                           step.noteOffsets.toFloatArray(),
                                                           step.noteVelocities.toFloatArray(),
                                                           ((step.noteGates as Any?) as? List<Float> ?: emptyList()).toFloatArray(),
                                                           step.parameterLocks.keys.toIntArray(), step.parameterLocks.values.toFloatArray()
                                                       )
                                                   } else {
                                                       nativeLib.setStep(selectedTrackIndex, stepIdx, false, intArrayOf(), 0f, 1, false, 0f, 0f, false, 0.0f, null, null)
                                                   }
                                              }
                                         }
                                 }
                             },
                             enabled = hasClipboard,
                             modifier = Modifier.height(32.dp).width(45.dp),
                             contentPadding = PaddingValues(0.dp),
                             colors = ButtonDefaults.buttonColors(containerColor = if (hasClipboard) Color.Gray else Color.DarkGray.copy(alpha = 0.5f))
                         ) { Text("PST", fontSize = 10.sp, color = Color.White) }

                         // Sequence Save/Load
                         Button(
                             onClick = { showSeqSave = true },
                             modifier = Modifier.height(32.dp).width(40.dp),
                             contentPadding = PaddingValues(0.dp),
                             colors = ButtonDefaults.buttonColors(containerColor = Color.DarkGray)
                         ) { Text("SAV", fontSize = 10.sp, color = Color.White) }

                         Button(
                             onClick = { showSeqLoad = true },
                             modifier = Modifier.height(32.dp).width(40.dp),
                             contentPadding = PaddingValues(0.dp),
                             colors = ButtonDefaults.buttonColors(containerColor = Color.DarkGray)
                         ) { Text("LOD", fontSize = 10.sp, color = Color.White) }
                     }

                     // Per-Track Humanize Knob
                     Row(verticalAlignment = Alignment.CenterVertically) {
                         Knob(
                             label = "", // External label used
                             initialValue = track.humanize,
                             parameterId = -1,
                             state = latestState,
                             onStateChange = latestOnStateChange,
                             nativeLib = nativeLib,
                             knobSize = 32.dp,
                             onValueChangeOverride = {
                                 val newTracks = latestState.tracks.toMutableList()
                                 newTracks[selectedTrackIndex] = track.copy(humanize = it)
                                 latestOnStateChange(latestState.copy(tracks = newTracks))
                                 nativeLib.setTrackHumanize(selectedTrackIndex, it)
                             }
                         )
                         Text("HUMAN", style = MaterialTheme.typography.labelSmall, modifier = Modifier.padding(start = 4.dp), color = Color.White)
                     }
                     
                     // Right Side: Clear Button with "Hold to Clear" / "Confirm" logic
                     // simplified for extraction: using original logic
                     var showClearAllConfirm by remember { mutableStateOf(false) }
                     
                     if (showClearAllConfirm) {
                          AlertDialog(
                              onDismissRequest = { showClearAllConfirm = false },
                              title = { Text("Clear All Tracks?") },
                              text = { Text("This will clear sequences for ALL tracks. Are you sure?") },
                              confirmButton = {
                                  Button(onClick = {
                                      for (i in 0 until 8) {
                                          nativeLib.clearSequencer(i)
                                      }
                                      val newTracks = latestState.tracks.map { t ->
                                          t.copy(
                                              steps = List(64) { StepState() },
                                              drumSteps = List(16) { List(64) { StepState() } }
                                          )
                                      }
                                      latestOnStateChange(latestState.copy(tracks = newTracks))
                                      showClearAllConfirm = false
                                  }, colors = ButtonDefaults.buttonColors(containerColor = Color.Red)) { Text("CLEAR ALL") }
                              },
                              dismissButton = {
                                  Button(onClick = { showClearAllConfirm = false }) { Text("CANCEL") }
                              }
                          )
                      }

                      var isClearPressed by remember { mutableStateOf(false) }
                      Box(
                          modifier = Modifier
                              .height(32.dp)
                              .width(70.dp)
                              .clip(RoundedCornerShape(4.dp))
                              .background(if (isClearPressed) Color.Red else Color.Red.copy(alpha = 0.3f))
                              .pointerInput(selectedTrackIndex) {
                                  awaitPointerEventScope {
                                      while (true) {
                                          val down = awaitFirstDown()
                                          val startTime = System.currentTimeMillis()
                                          while (true) {
                                              val event = withTimeoutOrNull(40) { awaitPointerEvent() }
                                              val elapsed = System.currentTimeMillis() - startTime
                                              if (elapsed >= 1000) {
                                                  showClearAllConfirm = true
                                                  break
                                              }
                                              if (event != null && event.changes.any { !it.pressed }) {
                                                  nativeLib.clearSequencer(selectedTrackIndex)
                                                  val newTracks = latestState.tracks.mapIndexed { idx, t ->
                                                      if (idx == selectedTrackIndex) {
                                                          t.copy(steps = List(64) { StepState() }, drumSteps = List(16) { List(64) { StepState() } })
                                                      } else t
                                                  }
                                                  latestOnStateChange(latestState.copy(tracks = newTracks))
                                                  break
                                              }
                                          }
                                          isClearPressed = false
                                          while (true) {
                                              val event = withTimeoutOrNull(100) { awaitPointerEvent() }
                                              if (event == null || event.changes.all { !it.pressed }) break
                                          }
                                      }
                                  }
                              },
                          contentAlignment = Alignment.Center
                      ) {
                          Text("CLEAR", style = MaterialTheme.typography.labelSmall, fontWeight = FontWeight.Bold, color = Color.White)
                      }
                 }
                 
                 Spacer(modifier = Modifier.height(8.dp))

                 // Drum Instrument Selectors (for Drum Engines)
                 if (track.engineType == EngineType.FM_DRUM) {
                    Row(modifier = Modifier.fillMaxWidth().height(40.dp), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                        listOf("KICK", "SNARE", "TOM", "HH", "OHH", "CYMB", "PERC", "NOISE").forEachIndexed { i, label ->
                            val customLabel = track.subTrackNames[i] ?: label
                            Box(
                                modifier = Modifier
                                    .weight(1f)
                                    .fillMaxHeight()
                                    .clip(RoundedCornerShape(8.dp))
                                    .background(if (track.selectedFmDrumInstrument == i) getEngineColor(track.engineType) else Color.DarkGray)
                                    .combinedClickable(
                                        onClick = { 
                                            latestOnStateChange(latestState.copy(tracks = latestState.tracks.mapIndexed { idx, t -> 
                                                if (idx == selectedTrackIndex) t.copy(selectedFmDrumInstrument = i) else t 
                                            })) 
                                        },
                                        onLongClick = {
                                            renamingSliceIndex = i
                                            renamingCurrentValue = customLabel
                                        }
                                    ),
                                contentAlignment = Alignment.Center
                            ) { 
                                Text(customLabel, style = MaterialTheme.typography.labelSmall, color = if (track.selectedFmDrumInstrument == i) Color.Black else Color.White, maxLines = 1, overflow = TextOverflow.Ellipsis) 
                            }
                        }
                    }
                    Spacer(modifier = Modifier.height(8.dp))
                } else if (track.engineType == EngineType.ANALOG_DRUM) {
                     Row(modifier = Modifier.fillMaxWidth().height(40.dp), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                        listOf("KICK", "SNARE", "RIM", "HAT C", "HAT O", "CYMBAL").forEachIndexed { i, label ->
                            val customLabel = track.subTrackNames[i] ?: label
                            Box(
                                modifier = Modifier
                                    .weight(1f)
                                    .fillMaxHeight()
                                    .clip(RoundedCornerShape(8.dp))
                                    .background(if (track.selectedFmDrumInstrument == i) getEngineColor(track.engineType) else Color.DarkGray)
                                    .combinedClickable(
                                        onClick = { 
                                            latestOnStateChange(latestState.copy(tracks = latestState.tracks.mapIndexed { idx, t -> 
                                                if (idx == selectedTrackIndex) t.copy(selectedFmDrumInstrument = i) else t 
                                            })) 
                                        },
                                        onLongClick = {
                                            renamingSliceIndex = i
                                            renamingCurrentValue = customLabel
                                        }
                                    ),
                                contentAlignment = Alignment.Center
                            ) { 
                                Text(customLabel, style = MaterialTheme.typography.labelSmall, color = if (track.selectedFmDrumInstrument == i) Color.Black else Color.White, maxLines = 1, overflow = TextOverflow.Ellipsis) 
                            }
                        }
                    }
                    Spacer(modifier = Modifier.height(8.dp))
                } else {
                     // Check for Sampler Chop Mode
                     val samplerMode = track.parameters[320] ?: 0f
                     val isSamplerChops = track.engineType == EngineType.SAMPLER && samplerMode >= 0.49f
                                           if (isSamplerChops) {
                        Column(modifier = Modifier.fillMaxWidth()) {
                             val numSlices = (((track.parameters[340] ?: 0f) * 15f).toInt() + 1).coerceIn(1, 16)
                             
                             // Calculate rows needed
                             val rowCount = (numSlices + 7) / 8
                             for (row in 0 until rowCount) {
                                 Row(modifier = Modifier.fillMaxWidth().height(32.dp), horizontalArrangement = Arrangement.spacedBy(2.dp)) {
                                     val start = row * 8
                                     val end = minOf(start + 8, numSlices)
                                     
                                     for (i in start until end) {
                                         val defaultLabel = "S${i + 1}"
                                         val customLabel = track.subTrackNames[i] ?: defaultLabel
                                         Box(
                                             modifier = Modifier
                                                 .weight(1f)
                                                 .fillMaxHeight()
                                                 .clip(RoundedCornerShape(4.dp))
                                                 .background(if (track.selectedFmDrumInstrument == i) getEngineColor(track.engineType) else Color.DarkGray)
                                                 .combinedClickable(
                                                     onClick = { 
                                                        latestOnStateChange(latestState.copy(tracks = latestState.tracks.mapIndexed { idx, t -> 
                                                            if (idx == selectedTrackIndex) t.copy(selectedFmDrumInstrument = i) else t 
                                                        })) 
                                                     },
                                                     onLongClick = {
                                                         renamingSliceIndex = i
                                                         renamingCurrentValue = customLabel
                                                     }
                                                 ),
                                             contentAlignment = Alignment.Center
                                         ) { 
                                             Text(customLabel, style = MaterialTheme.typography.labelSmall, fontSize = 10.sp, color = if (track.selectedFmDrumInstrument == i) Color.Black else Color.White, maxLines = 1, overflow = TextOverflow.Ellipsis) 
                                         }
                                     }
                                     
                                     // Fill empty space if row is not full
                                     val itemsInRow = end - start
                                     if (itemsInRow < 8) {
                                         Spacer(modifier = Modifier.weight((8 - itemsInRow).toFloat()))
                                     }
                                 }
                                 if (row < rowCount - 1) Spacer(modifier = Modifier.height(4.dp))
                             }
                        }
                        Spacer(modifier = Modifier.height(8.dp))
                    }
                }
                
                // 16 Step Pads or 64 Step Grid (with Sidebar)
                Row(modifier = Modifier.weight(1f).fillMaxWidth()) {
                    // Sidebar: Pattern Length Control
                    // LEN Control moved to BottomLeft

                    Box(modifier = Modifier.weight(1f), contentAlignment = Alignment.Center) {
                        BoxWithConstraints {
                        val is64 = latestState.is64StepView
                        val columns = if (is64) 8 else 4
                        val padCount = if (is64) 64 else 16
                        val spacing = if (is64) 4.dp else 8.dp
                        val padSize = minOf(maxWidth / (columns + 0.2f), maxHeight / (columns + 0.2f))
                        
                        // We need `isMultiTrack` to determine correct step indexing
                        val isSamplerChop = track.engineType == EngineType.SAMPLER && (track.parameters[320] ?: 0f).let { v -> 
                            // 320: 0.491-0.65 is CHOP, 0.651-0.82 is 1-CP, 0.821-0.94 is L-CP
                            v >= 0.49f && v <= 0.94f 
                        }
                        val isMultiTrack = track.engineType == EngineType.FM_DRUM || 
                                         track.engineType == EngineType.ANALOG_DRUM ||
                                         isSamplerChop

                        val selectedInst = track.selectedFmDrumInstrument
                        val defaultLabel = if (isSamplerChop) "SLICE ${selectedInst + 1}" else "INST ${selectedInst + 1}"
                        val customLabel = track.subTrackNames[selectedInst] ?: defaultLabel
                        
                        if (isMultiTrack) {
                            Text(customLabel, style = MaterialTheme.typography.labelSmall, color = Color.White.copy(alpha = 0.6f))
                        }
                        
                        LazyVerticalGrid(
                            columns = GridCells.Fixed(columns),
                            modifier = Modifier.size(padSize * (columns + 0.2f)),
                            verticalArrangement = Arrangement.spacedBy(spacing),
                            horizontalArrangement = Arrangement.spacedBy(spacing)
                        ) {
                            items(padCount, key = { i -> "${state.selectedTrackIndex}_${track.selectedFmDrumInstrument}_${state.currentSequencerBank}_${state.is64StepView}_$i" }) { i ->
                                val stepIndex = if (is64) i else i + (state.currentSequencerBank * 16)
                                if (stepIndex >= 64) return@items

                                val isBeyondLength = stepIndex >= track.patternLength
                                val step = if (isMultiTrack) track.drumSteps[track.selectedFmDrumInstrument][stepIndex] else track.steps[stepIndex]
                                
                                var showStepPopup by remember { mutableStateOf(false) }
                                
                                val engineColor = getEngineColor(track.engineType)
                                
                                // Check for Ghost Notes (active steps on other drum voices)
                                val isGhostActive = if (isMultiTrack && !step.active && !step.isSkipped) {
                                    track.drumSteps.indices.any { idx ->
                                        idx != track.selectedFmDrumInstrument && 
                                        track.drumSteps.getOrNull(idx)?.getOrNull(stepIndex)?.active == true
                                    }
                                } else false

                                Box(
                                    modifier = Modifier
                                        .aspectRatio(1f)
                                        .background(
                                            when {
                                                isBeyondLength -> Color.DarkGray.copy(alpha = 0.1f)
                                                step.isSkipped -> Color.Black
                                                step.active -> engineColor
                                                isGhostActive -> engineColor.copy(alpha = 0.3f) // Ghost Note Highlight
                                                else -> lerp(Color.DarkGray, engineColor, 0.2f)
                                            }, 
                                            RoundedCornerShape(if (is64) 4.dp else 8.dp)
                                        )
                                        .then(
                                            if (latestState.isPlaying && latestState.currentStep == stepIndex && !isBeyondLength) {
                                                Modifier.border(2.dp, Color.White, RoundedCornerShape(if (is64) 4.dp else 8.dp))
                                            } else Modifier
                                        )
                                        .pointerInput(i, state.currentSequencerBank, state.selectedTrackIndex, track.selectedFmDrumInstrument, is64, isBeyondLength) {
                                            if (isBeyondLength) return@pointerInput
                                            detectTapGestures(
                                                onTap = {
                                                    val currentState = viewModel.state
                                                    if (step.isSkipped) return@detectTapGestures
                                                    val currentTrack = currentState.tracks[currentState.selectedTrackIndex]
                                                    val currentStep = if (isMultiTrack) currentTrack.drumSteps[currentTrack.selectedFmDrumInstrument][stepIndex] else currentTrack.steps[stepIndex]
                                                    val newActive = !currentStep.active
                                                    
                                                    if (isMultiTrack) {
                                                        val instIdx = currentTrack.selectedFmDrumInstrument
                                                        val drumNote = 60 + instIdx
                                                        val finalNotes = if (newActive && currentStep.notes.isEmpty()) listOf(drumNote) else currentStep.notes
                                                        val newDrumSteps = currentTrack.drumSteps.mapIndexed { di, dsteps ->
                                                            if (di == instIdx) dsteps.mapIndexed { si, s -> if (si == stepIndex) s.copy(active = newActive, notes = finalNotes) else s }
                                                            else dsteps
                                                        }
                                                        viewModel.onStateChange(currentState.copy(tracks = currentState.tracks.mapIndexed { idx, t -> if (idx == currentState.selectedTrackIndex) t.copy(drumSteps = newDrumSteps) else t }))
                                                        nativeLib.setStep(currentState.selectedTrackIndex, stepIndex, newActive, finalNotes.toIntArray(), currentStep.velocity, currentStep.ratchet, currentStep.punch, currentStep.probability, currentStep.gate, currentStep.isSkipped, currentStep.subStepOffset, currentStep.noteOffsets.toFloatArray(), currentStep.noteVelocities.toFloatArray(), ((currentStep.noteGates as Any?) as? List<Float> ?: emptyList()).toFloatArray(), currentStep.parameterLocks.keys.toIntArray(), currentStep.parameterLocks.values.toFloatArray())
                                                    } else {
                                                        val rootNote = 60
                                                        val finalNotes = if (newActive && currentStep.notes.isEmpty()) listOf(rootNote) else currentStep.notes
                                                        val newSteps = currentTrack.steps.mapIndexed { si, s -> if (si == stepIndex) s.copy(active = newActive, notes = finalNotes) else s }
                                                        viewModel.onStateChange(currentState.copy(tracks = currentState.tracks.mapIndexed { idx, t -> if (idx == currentState.selectedTrackIndex) t.copy(steps = newSteps) else t }))
                                                        val isActiveWithNotes = newActive && finalNotes.isNotEmpty()
                                                        nativeLib.setStep(currentState.selectedTrackIndex, stepIndex, isActiveWithNotes, finalNotes.toIntArray(), currentStep.velocity, currentStep.ratchet, currentStep.punch, currentStep.probability, currentStep.gate, currentStep.isSkipped, currentStep.subStepOffset, currentStep.noteOffsets.toFloatArray(), currentStep.noteVelocities.toFloatArray(), ((currentStep.noteGates as Any?) as? List<Float> ?: emptyList()).toFloatArray(), currentStep.parameterLocks.keys.toIntArray(), currentStep.parameterLocks.values.toFloatArray())
                                                    }
                                                },
                                                onLongPress = { 
                                                    val currentState = viewModel.state
                                                    if (step.isSkipped) {
                                                        // RESTORE STEP
                                                        val currentTrack = currentState.tracks[currentState.selectedTrackIndex]
                                                        if (isMultiTrack) {
                                                            val instIdx = currentTrack.selectedFmDrumInstrument
                                                            val newDrumSteps = currentTrack.drumSteps.mapIndexed { di, dsteps ->
                                                                if (di == instIdx) dsteps.mapIndexed { si, s -> if (si == stepIndex) s.copy(isSkipped = false) else s }
                                                                else dsteps
                                                            }
                                                            viewModel.onStateChange(currentState.copy(tracks = currentState.tracks.mapIndexed { idx, t -> if (idx == currentState.selectedTrackIndex) t.copy(drumSteps = newDrumSteps) else t }))
                                                            val s = newDrumSteps[instIdx][stepIndex]
                                                            nativeLib.setStep(currentState.selectedTrackIndex, stepIndex, s.active, s.notes.toIntArray(), s.velocity, s.ratchet, s.punch, s.probability, s.gate, false, s.subStepOffset, s.noteOffsets.toFloatArray(), s.noteVelocities.toFloatArray(), ((s.noteGates as Any?) as? List<Float> ?: emptyList()).toFloatArray(), s.parameterLocks.keys.toIntArray(), s.parameterLocks.values.toFloatArray())
                                                        } else {
                                                            val newSteps = currentTrack.steps.mapIndexed { si, s -> if (si == stepIndex) s.copy(isSkipped = false) else s }
                                                            viewModel.onStateChange(currentState.copy(tracks = currentState.tracks.mapIndexed { idx, t -> if (idx == currentState.selectedTrackIndex) t.copy(steps = newSteps) else t }))
                                                            val s = newSteps[stepIndex]
                                                            nativeLib.setStep(currentState.selectedTrackIndex, stepIndex, s.active, s.notes.toIntArray(), s.velocity, s.ratchet, s.punch, s.probability, s.gate, false, s.subStepOffset, s.noteOffsets.toFloatArray(), s.noteVelocities.toFloatArray(), ((s.noteGates as Any?) as? List<Float> ?: emptyList()).toFloatArray(), s.parameterLocks.keys.toIntArray(), s.parameterLocks.values.toFloatArray())
                                                        }
                                                    } else {
                                                        showStepPopup = true 
                                                    }
                                                }
                                            )
                                        },
                                    contentAlignment = Alignment.Center
                                ) {
                                    if (!is64 && !step.isSkipped) {
                                        Text("${stepIndex + 1}", color = if (step.active) Color.Black else Color.White)
                                    }
                                    if (showStepPopup) {
                                        // Fix: Fetch LIVE step state to ensure popup shows current values
                                        val liveTrack = latestState.tracks[latestState.selectedTrackIndex]
                                        val liveStep = if (isMultiTrack) liveTrack.drumSteps[liveTrack.selectedFmDrumInstrument][stepIndex] else liveTrack.steps[stepIndex]
                                        
                                        PadOptionPopup(
                                            onDismiss = { showStepPopup = false },
                                            stepState = liveStep,
                                             onApply = { ratchet: Int, punch: Boolean, probability: Float, gate: Float, notes: List<Int>, velocity: Float, isSkipped: Boolean, parameterLocks: Map<Int, Float>, subStepOffset: Float ->
                                                val currentState = viewModel.state
                                                val currentTrack = currentState.tracks[currentState.selectedTrackIndex]
                                                val currentStep = if (isMultiTrack) currentTrack.drumSteps[currentTrack.selectedFmDrumInstrument][stepIndex] else currentTrack.steps[stepIndex]
                                                val oldNoteGates = (currentStep.noteGates as Any?) as? List<Float> ?: emptyList()
                                                val newNoteGates = if (gate != currentStep.gate) List(notes.size) { gate }
                                                    else if (notes == currentStep.notes) oldNoteGates
                                                    else notes.map { note -> currentStep.notes.indexOf(note).let { oldIndex ->
                                                        if (oldIndex >= 0) oldNoteGates.getOrNull(oldIndex) ?: currentStep.gate else currentStep.gate
                                                    } }
                                                val newNoteOffsets = if (notes == currentStep.notes) currentStep.noteOffsets
                                                    else notes.map { note -> currentStep.notes.indexOf(note).let { oldIndex ->
                                                        if (oldIndex >= 0) currentStep.noteOffsets.getOrNull(oldIndex) ?: subStepOffset else subStepOffset
                                                    } }
                                                val newNoteVelocities = if (notes == currentStep.notes) currentStep.noteVelocities
                                                    else notes.map { note -> currentStep.notes.indexOf(note).let { oldIndex ->
                                                        if (oldIndex >= 0) currentStep.noteVelocities.getOrNull(oldIndex) ?: velocity else velocity
                                                    } }
                                                if (isMultiTrack) {
                                                    val instIdx = currentTrack.selectedFmDrumInstrument
                                                    val newDrumSteps = currentTrack.drumSteps.mapIndexed { di, dsteps ->
                                                        if (di == instIdx) dsteps.mapIndexed { si, s -> if (si == stepIndex) s.copy(ratchet = ratchet, punch = punch, probability = probability, gate = gate, velocity = velocity, notes = notes, isSkipped = isSkipped, parameterLocks = parameterLocks, subStepOffset = subStepOffset, noteOffsets = newNoteOffsets, noteVelocities = newNoteVelocities, noteGates = newNoteGates) else s }
                                                        else dsteps
                                                    }
                                                    viewModel.onStateChange(currentState.copy(tracks = currentState.tracks.mapIndexed { idx, t -> if (idx == currentState.selectedTrackIndex) t.copy(drumSteps = newDrumSteps) else t }))
                                                    nativeLib.setStep(currentState.selectedTrackIndex, stepIndex, currentStep.active, notes.toIntArray(), velocity, ratchet, punch, probability, gate, isSkipped, subStepOffset, newNoteOffsets.toFloatArray(), newNoteVelocities.toFloatArray(), newNoteGates.toFloatArray(), parameterLocks.keys.toIntArray(), parameterLocks.values.toFloatArray())
                                                } else {
                                                    val newSteps = currentTrack.steps.mapIndexed { si, s -> if (si == stepIndex) s.copy(ratchet = ratchet, punch = punch, probability = probability, gate = gate, notes = notes, velocity = velocity, isSkipped = isSkipped, parameterLocks = parameterLocks, subStepOffset = subStepOffset, noteOffsets = newNoteOffsets, noteVelocities = newNoteVelocities, noteGates = newNoteGates) else s }
                                                    viewModel.onStateChange(currentState.copy(tracks = currentState.tracks.mapIndexed { idx, t -> if (idx == currentState.selectedTrackIndex) t.copy(steps = newSteps) else t }))
                                                    nativeLib.setStep(currentState.selectedTrackIndex, stepIndex, currentStep.active, notes.toIntArray(), velocity, ratchet, punch, probability, gate, isSkipped, subStepOffset, newNoteOffsets.toFloatArray(), newNoteVelocities.toFloatArray(), newNoteGates.toFloatArray(), parameterLocks.keys.toIntArray(), parameterLocks.values.toFloatArray())
                                                }
                                            },
                                            onParamLock = {
                                                val currentState = viewModel.state
                                                viewModel.onStateChange(currentState.copy(isParameterLocking = true, lockingTarget = currentState.selectedTrackIndex to stepIndex))
                                                showStepPopup = false
                                            }
                                        )
                                    }
                                }
                            }
                        }
                    }

                    // Seq Chain UI (Added v1.12.0)
                    var showChainSelect by remember { mutableStateOf(false) }
                    Column(
                        modifier = Modifier
                            .align(Alignment.BottomEnd)
                            .padding(bottom = 8.dp, end = 8.dp)
                            .offset(x = if (LocalPlatformInfo.current.isTablet || state.uiLayoutMode == 2) 10.dp else (-10).dp) 
                            .background(
                                if (track.isChainEnabled) getEngineColor(track.engineType).copy(alpha = 0.2f) else Color.Black.copy(alpha = 0.6f), 
                                RoundedCornerShape(8.dp)
                            )
                            .border(
                                width = if (track.isChainEnabled) 1.dp else 0.dp,
                                color = if (track.isChainEnabled) getEngineColor(track.engineType).copy(alpha = 0.5f) else Color.Transparent,
                                shape = RoundedCornerShape(8.dp)
                            )
                            .padding(8.dp)
                            .width(100.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        // PROBABILITY KNOB
                        val probValue = track.sequenceProbability
                        val engineColor = getEngineColor(track.engineType)
                        Column(
                            modifier = Modifier
                                .background(Color.Black.copy(alpha = 0.6f), RoundedCornerShape(8.dp))
                                .border(1.dp, Color.White.copy(alpha = 0.1f), RoundedCornerShape(8.dp))
                                .padding(8.dp)
                                .width(100.dp),
                            horizontalAlignment = Alignment.CenterHorizontally
                        ) {
                            Text("PROB", style = MaterialTheme.typography.labelSmall, color = Color.Gray, fontSize = 9.sp)
                            Spacer(Modifier.height(4.dp))
                            Knob(
                                label = "",
                                initialValue = probValue,
                                parameterId = -1,
                                state = latestState,
                                onStateChange = latestOnStateChange,
                                nativeLib = nativeLib,
                                knobSize = 40.dp,
                                showValue = false,
                                overrideValue = probValue,
                                overrideColor = engineColor,
                                onValueChangeOverride = { v ->
                                    val newTracks = latestState.tracks.toMutableList()
                                    newTracks[selectedTrackIndex] = track.copy(sequenceProbability = v)
                                    nativeLib.setTrackProbability(selectedTrackIndex, v)
                                    latestOnStateChange(latestState.copy(tracks = newTracks))
                                },
                                valueFormatter = { v -> "${(v * 100).toInt()}%" }
                            )
                        }

                        // SEQ CHAIN UI
                        Text("SEQ CHAIN", style = MaterialTheme.typography.labelSmall, color = if (track.isChainEnabled) engineColor else Color.Gray, fontSize = 9.sp)
                        
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            // Length Knob
                            Knob(
                                label = "LEN",
                                initialValue = (track.songChainLength - 1) / 15f,
                                parameterId = -1,
                                state = latestState,
                                onStateChange = latestOnStateChange,
                                nativeLib = nativeLib,
                                knobSize = 32.dp,
                                onValueChangeOverride = { v ->
                                    val newLen = (v * 15 + 1).toInt().coerceIn(1, 16)
                                    val newTracks = latestState.tracks.toMutableList()
                                    newTracks[selectedTrackIndex] = track.copy(songChainLength = newLen)
                                    latestOnStateChange(latestState.copy(tracks = newTracks))
                                    nativeLib.setChainLength(selectedTrackIndex, newLen)
                                },
                                valueFormatter = { v -> "${(v * 15 + 1).toInt()}" }
                            )
                            
                            // On/Off Toggle (Small Round Button)
                            Box(
                                modifier = Modifier
                                    .size(24.dp)
                                    .clip(CircleShape)
                                    .background(if (track.isChainEnabled) engineColor else Color.DarkGray)
                                    .clickable {
                                        val newState = !track.isChainEnabled
                                        val newTracks = latestState.tracks.toMutableList()
                                        newTracks[selectedTrackIndex] = track.copy(isChainEnabled = newState)
                                        latestOnStateChange(latestState.copy(tracks = newTracks))
                                        nativeLib.setChainEnabled(selectedTrackIndex, newState)
                                    },
                                contentAlignment = Alignment.Center
                            ) {
                                Box(modifier = Modifier.size(10.dp).clip(CircleShape).background(if (track.isChainEnabled) Color.Black else Color.Gray))
                            }
                        }
                        
                        // SELECT Button (Bar shaped)
                        Button(
                            onClick = { showChainSelect = true },
                            modifier = Modifier.fillMaxWidth().height(32.dp),
                            shape = RoundedCornerShape(4.dp),
                            contentPadding = PaddingValues(0.dp),
                            colors = ButtonDefaults.buttonColors(containerColor = Color.DarkGray)
                        ) {
                            Text("SELECT", fontSize = 10.sp, color = Color.White)
                        }
                    }

                    // Left Side Controls (LEN + Transpose)
                    Column(
                        modifier = Modifier
                            .align(Alignment.BottomStart)
                            .padding(start = 8.dp, bottom = 108.dp), // Moved up 100dp v1.12.x
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(100.dp) // 100dp space between LEN and Transpose
                    ) {
                        // Pattern Length Control (Per-Track)
                        Column(
                            modifier = Modifier
                                .background(Color.Black.copy(alpha = 0.6f), RoundedCornerShape(8.dp))
                                .padding(8.dp),
                            horizontalAlignment = Alignment.CenterHorizontally,
                        ) {
                            Knob(
                                label = "LEN",
                                initialValue = (track.patternLength - 4) / 60f,
                                parameterId = -1,
                                state = latestState,
                                onStateChange = latestOnStateChange,
                                nativeLib = nativeLib,
                                knobSize = 36.dp,
                                onValueChangeOverride = { v ->
                                    val newLen = (v * 60 + 4).toInt().coerceIn(4, 64)
                                    if (newLen != track.patternLength) {
                                        val newTracks = latestState.tracks.toMutableList()
                                        newTracks[selectedTrackIndex] = track.copy(patternLength = newLen)
                                        latestOnStateChange(latestState.copy(tracks = newTracks))
                                        nativeLib.setSequencerConfig(selectedTrackIndex, 1, newLen)
                                    }
                                },
                                valueFormatter = { v -> "${(v * 60 + 4).toInt().coerceIn(4, 64)}" }
                            )
                            Text("STEPS", style = MaterialTheme.typography.labelSmall, color = Color.Gray, fontSize = 9.sp)
                        }

                        // Transpose UI
                        Column(
                            modifier = Modifier
                                .background(
                                    if (track.transpose != 0) getEngineColor(track.engineType).copy(alpha = 0.2f) else Color.Black.copy(alpha = 0.6f), 
                                    RoundedCornerShape(8.dp)
                                )
                                .border(
                                    width = if (track.transpose != 0) 1.dp else 0.dp,
                                    color = if (track.transpose != 0) getEngineColor(track.engineType).copy(alpha = 0.5f) else Color.Transparent,
                                    shape = RoundedCornerShape(8.dp)
                                )
                                .padding(8.dp),
                            horizontalAlignment = Alignment.CenterHorizontally,
                            verticalArrangement = Arrangement.spacedBy(4.dp)
                        ) {
                            val engineColor = getEngineColor(track.engineType)
                            Text("TRANSPOSE", style = MaterialTheme.typography.labelSmall, color = if (track.transpose != 0) engineColor else Color.Gray, fontSize = 9.sp)
                            val st = track.transpose
                        val sign = if (st > 0) "+" else ""
                        Text("$sign$st", style = MaterialTheme.typography.titleMedium, color = if (st != 0) Color.White else engineColor, fontWeight = FontWeight.Bold)
                        
                        Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                            Button(
                                onClick = {
                                    val newVal = (track.transpose - 12).coerceIn(-36, 36)
                                    val newTracks = latestState.tracks.toMutableList()
                                    newTracks[selectedTrackIndex] = track.copy(transpose = newVal)
                                    latestOnStateChange(latestState.copy(tracks = newTracks))
                                    nativeLib.setTrackTranspose(selectedTrackIndex, newVal)
                                },
                                modifier = Modifier.size(36.dp),
                                contentPadding = PaddingValues(0.dp),
                                colors = ButtonDefaults.buttonColors(containerColor = Color.DarkGray)
                            ) { Text("-12", fontSize = 9.sp) }
                            Button(
                                onClick = {
                                    val newVal = (track.transpose + 12).coerceIn(-36, 36)
                                    val newTracks = latestState.tracks.toMutableList()
                                    newTracks[selectedTrackIndex] = track.copy(transpose = newVal)
                                    latestOnStateChange(latestState.copy(tracks = newTracks))
                                    nativeLib.setTrackTranspose(selectedTrackIndex, newVal)
                                },
                                modifier = Modifier.size(36.dp),
                                contentPadding = PaddingValues(0.dp),
                                colors = ButtonDefaults.buttonColors(containerColor = Color.DarkGray)
                            ) { Text("+12", fontSize = 9.sp) }
                        }
                        Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                            Button(
                                onClick = {
                                    val newVal = (track.transpose - 1).coerceIn(-36, 36)
                                    val newTracks = latestState.tracks.toMutableList()
                                    newTracks[selectedTrackIndex] = track.copy(transpose = newVal)
                                    latestOnStateChange(latestState.copy(tracks = newTracks))
                                    nativeLib.setTrackTranspose(selectedTrackIndex, newVal)
                                },
                                modifier = Modifier.size(36.dp),
                                contentPadding = PaddingValues(0.dp),
                                colors = ButtonDefaults.buttonColors(containerColor = Color.DarkGray)
                            ) { Text("-1", fontSize = 10.sp) }
                            Button(
                                onClick = {
                                    val newVal = (track.transpose + 1).coerceIn(-36, 36)
                                    val newTracks = latestState.tracks.toMutableList()
                                    newTracks[selectedTrackIndex] = track.copy(transpose = newVal)
                                    latestOnStateChange(latestState.copy(tracks = newTracks))
                                    nativeLib.setTrackTranspose(selectedTrackIndex, newVal)
                                },
                                modifier = Modifier.size(36.dp),
                                contentPadding = PaddingValues(0.dp),
                                colors = ButtonDefaults.buttonColors(containerColor = Color.DarkGray)
                            ) { Text("+1", fontSize = 10.sp) }
                        }
                    } // Close Transpose UI
                    } // Close Left Side Controls

                    if (showChainSelect) {
                        ChainSlotPopup(
                            onDismiss = { showChainSelect = false },
                            track = track,
                            state = latestState,
                            onStateChange = latestOnStateChange,
                            nativeLib = nativeLib,
                            trackIndex = selectedTrackIndex
                        )
                    }
                }
            }
        }
    }
}
}

@Composable
fun ChainSlotPopup(
    onDismiss: () -> Unit,
    track: com.groovebox.TrackState,
    state: GrooveboxState,
    onStateChange: (GrooveboxState) -> Unit,
    nativeLib: NativeLib,
    trackIndex: Int
) {
    
    var showFilePickerForSlot by remember { mutableStateOf<Int?>(null) }
    
    Popup(onDismissRequest = onDismiss) {
        Card(
            modifier = Modifier.width(320.dp).padding(16.dp),
            colors = CardDefaults.cardColors(containerColor = Color(0xFF1A1A1A)),
            elevation = CardDefaults.cardElevation(defaultElevation = 12.dp),
            shape = RoundedCornerShape(12.dp)
        ) {
            Column(modifier = Modifier.padding(16.dp)) {
                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                    Text("SEQUENCE CHAIN", color = Color.White, style = MaterialTheme.typography.titleSmall)
                    IconButton(onClick = onDismiss, modifier = Modifier.size(24.dp)) {
                        Icon(Icons.Default.Close, contentDescription = "Close", tint = Color.Gray)
                    }
                }
                
                Spacer(modifier = Modifier.height(16.dp))
                
                LazyVerticalGrid(
                    columns = GridCells.Fixed(4),
                    modifier = Modifier.height(240.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    items(track.songChainLength) { i ->
                        val name = track.songChainNames.getOrNull(i)
                        val engineColor = getEngineColor(track.engineType)
                        
                        Box(
                            modifier = Modifier
                                .aspectRatio(1.5f)
                                .clip(RoundedCornerShape(4.dp))
                                .background(if (name != null) engineColor.copy(alpha = 0.8f) else Color.DarkGray)
                                .clickable { showFilePickerForSlot = i },
                            contentAlignment = Alignment.Center
                        ) {
                            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                Text("${i + 1}", fontSize = 9.sp, color = if (name != null) Color.Black else Color.Gray)
                                if (name != null) {
                                    Text(name.take(6), fontSize = 10.sp, color = Color.Black, fontWeight = FontWeight.Bold)
                                } else {
                                    Text("EMPTY", fontSize = 8.sp, color = Color.Gray)
                                }
                            }
                        }
                    }
                }
            }
        }
        
        showFilePickerForSlot?.let { slotIdx ->
            val defaultDir = File(PersistenceManager.getLoomFolder(), "Sequences").apply { if(!exists()) mkdirs() }
            NativeFileDialog(
                directory = defaultDir,
                onDismiss = { showFilePickerForSlot = null },
                state = state,
                onFileSelected = { path ->
                    val name = File(path).name.removeSuffix(".gbs")
                    // Load the sequence to get the steps
                    val loadedState = PersistenceManager.loadSequence(track, name)
                    if (loadedState != null) {
                        val newChainNames = track.songChainNames.toMutableList()
                        newChainNames[slotIdx] = name
                        
                        val newTracks = state.tracks.toMutableList()
                        newTracks[trackIndex] = track.copy(songChainNames = newChainNames)
                        onStateChange(state.copy(tracks = newTracks))
                        
                        // Sync with Engine
                        val engineType = loadedState.engineType
                        if (engineType == EngineType.FM_DRUM || engineType == EngineType.ANALOG_DRUM) {
                            for (lane in 0 until 16) {
                                val laneSteps = loadedState.drumSteps.getOrNull(lane) ?: emptyList()
                                nativeLib.setChainSlot(trackIndex, slotIdx, lane, laneSteps.toTypedArray())
                            }
                        } else {
                            nativeLib.setChainSlot(trackIndex, slotIdx, -1, loadedState.steps.toTypedArray())
                        }
                    }
                    showFilePickerForSlot = null
                },
                isSave = false,
                trackIndex = trackIndex,
                extensions = listOf("gbs"),
                title = "SELECT SEQUENCE"
            )
        }
    }
}

@Composable
fun PadOptionPopup(
    onDismiss: () -> Unit,
    stepState: StepState,
    onApply: (Int, Boolean, Float, Float, List<Int>, Float, Boolean, Map<Int, Float>, Float) -> Unit, // Added Float
    onParamLock: () -> Unit
) {
    Popup(onDismissRequest = onDismiss) {
        Card(
            modifier = Modifier.width(300.dp).wrapContentHeight().padding(8.dp),
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
            elevation = CardDefaults.cardElevation(defaultElevation = 8.dp)
        ) {
            Column(modifier = Modifier.padding(16.dp).verticalScroll(rememberScrollState())) {
                // Header: Step Details
                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                    Text("Step Options", style = MaterialTheme.typography.titleMedium, color = Color.White)
                    IconButton(onClick = onDismiss, modifier = Modifier.size(24.dp)) {
                        Icon(androidx.compose.material.icons.Icons.Filled.Close, contentDescription = "Close", tint = Color.Gray)
                    }
                }
                Divider(color = Color.White.copy(alpha = 0.2f), modifier = Modifier.padding(vertical = 8.dp))

                // Ratchet & Punch
                Text("Ratchet", style = MaterialTheme.typography.labelMedium)
                Row(modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    (1..4).forEach { r ->
                        Box(
                            modifier = Modifier
                                .size(32.dp)
                                .background(if (stepState.ratchet == r) Color.Cyan else Color.DarkGray, CircleShape)
                                .clickable { onApply(r, stepState.punch, stepState.probability, stepState.gate, stepState.notes, stepState.velocity, stepState.isSkipped, stepState.parameterLocks, stepState.subStepOffset) },
                            contentAlignment = Alignment.Center
                        ) {
                            Text("x$r", style = MaterialTheme.typography.labelSmall, color = if (stepState.ratchet == r) Color.Black else Color.White)
                        }
                    }
                }
                
                Spacer(modifier = Modifier.height(12.dp))

                // Piano & Octave Control
                // Determine active octaves for highlighting
                val activeOctaves = stepState.notes.map { it / 12 }.toSet()
                
                // Safe initial octave: Prioritize first note, else default to 5
                var viewingOctave by remember(stepState.notes) { 
                    mutableStateOf(if (stepState.notes.isNotEmpty()) stepState.notes.first() / 12 else 5) 
                }
                
                // Octave Selector with Highlight
                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.Center, verticalAlignment = Alignment.CenterVertically) {
                    Button(
                        onClick = { if (viewingOctave > 0) viewingOctave-- },
                        colors = ButtonDefaults.buttonColors(containerColor = Color.Transparent),
                        contentPadding = PaddingValues(0.dp),
                        modifier = Modifier.width(40.dp)
                    ) { Text("▼", color = if (viewingOctave > 0 && activeOctaves.any { it < viewingOctave }) Color.Cyan else Color.Gray, fontSize = 20.sp) }
                    
                    Text("C$viewingOctave", color = if (activeOctaves.contains(viewingOctave)) Color.Cyan else Color.White, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold, modifier = Modifier.padding(horizontal = 16.dp))
                    
                    Button(
                        onClick = { if (viewingOctave < 9) viewingOctave++ },
                        colors = ButtonDefaults.buttonColors(containerColor = Color.Transparent),
                        contentPadding = PaddingValues(0.dp),
                         modifier = Modifier.width(40.dp)
                    ) { Text("▲", color = if (viewingOctave < 9 && activeOctaves.any { it > viewingOctave }) Color.Cyan else Color.Gray, fontSize = 20.sp) }
                }

                Spacer(modifier = Modifier.height(8.dp))
                
                // 4x3 Note Grid
                val noteLabels = listOf("C", "C#", "D", "D#", "E", "F", "F#", "G", "G#", "A", "A#", "B")
                val blackKeys = setOf(1, 3, 6, 8, 10)
                
                Column(modifier = Modifier.fillMaxWidth()) {
                    // Rows of 4
                    for (row in 0..2) {
                        Row(modifier = Modifier.fillMaxWidth().height(40.dp), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                            for (col in 0..3) {
                                val noteOffset = row * 4 + col
                                val currentNote = viewingOctave * 12 + noteOffset
                                val isBright = stepState.notes.contains(currentNote)
                                // Show "Dim" if this note exists in ANY other octave
                                val isDim = !isBright && stepState.notes.any { it % 12 == noteOffset }
                                val isBlackKey = blackKeys.contains(noteOffset)
                                
                                // Color Logic
                                val bgColor = when {
                                    isBright -> Color.Cyan
                                    isDim -> Color.Cyan.copy(alpha = 0.3f)
                                    isBlackKey -> Color.Black
                                    else -> Color.LightGray
                                }
                                
                                val textColor = when {
                                    isBright -> Color.Black
                                    isDim -> Color.White
                                    isBlackKey -> Color.White
                                    else -> Color.Black
                                }
                                
                                Button(
                                    onClick = {
                                        val newNotes = if (stepState.notes.contains(currentNote)) {
                                            stepState.notes - currentNote
                                        } else {
                                            stepState.notes + currentNote
                                        }
                                        onApply(stepState.ratchet, stepState.punch, stepState.probability, stepState.gate, newNotes.sorted(), stepState.velocity, stepState.isSkipped, stepState.parameterLocks, stepState.subStepOffset)
                                    },
                                    modifier = Modifier.weight(1f).fillMaxHeight(),
                                    colors = ButtonDefaults.buttonColors(containerColor = bgColor),
                                    shape = RoundedCornerShape(4.dp),
                                    contentPadding = PaddingValues(0.dp)
                                ) {
                                    Text(noteLabels[noteOffset], color = textColor, style = MaterialTheme.typography.labelSmall, fontWeight = FontWeight.Bold)
                                }
                            }
                        }
                        Spacer(modifier = Modifier.height(4.dp))
                    }
                }
                
                Spacer(modifier = Modifier.height(12.dp))
                
                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    Button(
                        onClick = { onApply(stepState.ratchet, !stepState.punch, stepState.probability, stepState.gate, stepState.notes, stepState.velocity, stepState.isSkipped, stepState.parameterLocks, stepState.subStepOffset) },
                        colors = ButtonDefaults.buttonColors(containerColor = if (stepState.punch) Color(0xFFFF4500) else Color.DarkGray), // OrangeRed
                        modifier = Modifier.weight(1f)
                    ) { Text("PUNCH", color = Color.White) }
                    
                    Spacer(modifier = Modifier.width(8.dp))
                    
                    Button(onClick = onParamLock, modifier = Modifier.weight(1f)) { Text("P-LOCK") }
                }

                Spacer(modifier = Modifier.height(16.dp))

                // Velocity Slider
                Text("Velocity: ${(stepState.velocity * 100).toInt()}%", style = MaterialTheme.typography.labelMedium)
                Slider(
                    value = stepState.velocity,
                    onValueChange = { onApply(stepState.ratchet, stepState.punch, stepState.probability, stepState.gate, stepState.notes, it, stepState.isSkipped, stepState.parameterLocks, stepState.subStepOffset) },
                    valueRange = 0f..1f,
                    modifier = Modifier.fillMaxWidth()
                )

                Spacer(modifier = Modifier.height(12.dp))

                // Gate Selection
                val currentGate = stepState.gate.coerceIn(0.1f, 8.0f)
                val gateLabel = if (currentGate < 1.0f) "${(currentGate * 100).toInt()}%" else "${currentGate}x"
                Text("Gate Length: $gateLabel", style = MaterialTheme.typography.labelMedium)
                 Slider(
                    value = currentGate,
                    onValueChange = { onApply(stepState.ratchet, stepState.punch, stepState.probability, it, stepState.notes, stepState.velocity, stepState.isSkipped, stepState.parameterLocks, stepState.subStepOffset) },
                    valueRange = 0.1f..8.0f,
                    modifier = Modifier.fillMaxWidth()
                )

                Spacer(modifier = Modifier.height(12.dp))

                // Probability
                Text("Probability: ${(stepState.probability * 100).toInt()}%", style = MaterialTheme.typography.labelMedium)
                Slider(
                    value = stepState.probability,
                    onValueChange = { onApply(stepState.ratchet, stepState.punch, it, stepState.gate, stepState.notes, stepState.velocity, stepState.isSkipped, stepState.parameterLocks, stepState.subStepOffset) },
                    valueRange = 0f..1f,
                    modifier = Modifier.fillMaxWidth()
                )
                
                Spacer(modifier = Modifier.height(12.dp))
                
                // Microtiming
                // Microtiming
                val microtimingPercent = (stepState.subStepOffset * 100).toInt()
                Text("Microtiming: $microtimingPercent%", style = MaterialTheme.typography.labelMedium)
                Slider(
                    value = stepState.subStepOffset,
                    onValueChange = { onApply(stepState.ratchet, stepState.punch, stepState.probability, stepState.gate, stepState.notes, stepState.velocity, stepState.isSkipped, stepState.parameterLocks, it) },
                    valueRange = 0f..1f,
                    modifier = Modifier.fillMaxWidth()
                )
                
                Spacer(modifier = Modifier.height(12.dp))
                
                 Button(
                    onClick = { onApply(stepState.ratchet, stepState.punch, stepState.probability, stepState.gate, stepState.notes, stepState.velocity, !stepState.isSkipped, stepState.parameterLocks, stepState.subStepOffset) },
                    colors = ButtonDefaults.buttonColors(containerColor = if (stepState.isSkipped) Color.Green else Color.DarkGray), 
                    modifier = Modifier.fillMaxWidth()
                ) { Text(if (stepState.isSkipped) "RESTORE STEP (UNSKIP)" else "SKIP STEP", color = Color.White) }
                
                Spacer(modifier = Modifier.height(8.dp))
            }
        }
    }
}
