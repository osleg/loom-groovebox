/* SPDX-License-Identifier: GPL-3.0-only */
package com.groovebox.cafa

import com.groovebox.*
import org.fm1.companion.legacy.Protocol
import org.junit.Assert.*
import org.junit.Test

class CafaSurfaceTest {
    private class Engine : CafaSurface.Engine {
        val on = mutableListOf<Pair<Int, Int>>()
        val off = mutableListOf<Pair<Int, Int>>()
        val parameters = mutableListOf<Triple<Int, Int, Float>>()
        val steps = mutableListOf<Triple<Int, Int, StepState>>()
        val lengths = mutableListOf<Pair<Int, Int>>()
        var running = false
        val playback = mutableListOf<Boolean>()
        val recordingCalls = mutableListOf<Boolean>()
        val tempos = mutableListOf<Float>()
        val lfos = mutableListOf<Pair<Int, LfoState>>()
        val arps = mutableListOf<Pair<Int, ArpConfig>>()
        val bypass = mutableListOf<Pair<Int, Boolean>>()
        val lanes = mutableListOf<Pair<Int, Int>>()
        val swings = mutableListOf<Float>()
        val humanize = mutableListOf<Pair<Int, Float>>()
        val levels = mutableListOf<Pair<Int, Float>>()
        val pans = mutableListOf<Pair<Int, Float>>()
        val mutes = mutableListOf<Pair<Int, Boolean>>()
        val solos = mutableListOf<Pair<Int, Boolean>>()
        override fun noteOn(track: Int, note: Int) { on += track to note }
        override fun noteOff(track: Int, note: Int) { off += track to note }
        override fun playing(value: Boolean) { running = value; playback += value }
        override fun recording(value: Boolean) { recordingCalls += value }
        override fun parameter(track: Int, id: Int, value: Float) { parameters += Triple(track, id, value) }
        override fun volume(track: Int, value: Float) { levels += track to value }
        override fun pan(track: Int, value: Float) { pans += track to value }
        override fun mute(track: Int, value: Boolean) { mutes += track to value }
        override fun solo(track: Int, value: Boolean) { solos += track to value }
        override fun tempo(value: Float) { tempos += value }
        override fun length(track: Int, value: Int) { lengths += track to value }
        override fun step(track: Int, index: Int, value: StepState) { steps += Triple(track, index, value) }
        override fun playhead(track: Int, lane: Int) = 63
        override fun lfo(index: Int, value: LfoState) { lfos += index to value }
        override fun arp(track: Int, value: ArpConfig) { arps += track to value }
        override fun fxEnabled(index: Int, enabled: Boolean) { bypass += index to enabled }
        override fun swing(value: Float) { swings += value }
        override fun humanize(track: Int, value: Float) { humanize += track to value }
        override fun lane(track: Int, value: Int) { lanes += track to value }
    }
    private class Rig {
        var state = GrooveboxState()
        val engine = Engine()
        var notice = ""
        var saved = false
        var saveResult = true
        val surface = CafaSurface({state}, {state=it}, engine, {state=state.copy(selectedTrackIndex=it)}, {saved=true; saveResult}, {notice=it})
        var serial = 0
        fun input(buttons: Int = 0, keys: Long = 0, encoder: Int = -1, delta: Int = 0, epoch: Int? = null): Boolean {
            val p = ByteArray(22); Protocol.put14(p,20,epoch ?: Protocol.u14(surface.snapshot(),152)); Protocol.put14(p,0,buttons); Protocol.put28(p,2,keys)
            for(i in 0..6) Protocol.put14(p,6+i*2,8192 + if(i==encoder) delta else 0)
            serial=(serial+1) and 16383
            return surface.input(serial,p)
        }
        fun baseline() { assertTrue(input()) }
    }
    @Test fun heldKeysAreNotReplayedAndReleaseUsesOriginalTrack() {
        val r=Rig(); assertTrue(r.input(keys=1)); assertTrue(r.engine.on.isEmpty())
        r.input(); r.input(keys=1); assertEquals(0 to 53,r.engine.on.last())
        r.state=r.state.copy(selectedTrackIndex=7); r.input(buttons=1 shl 13, keys=1)
        r.input(); assertEquals(0 to 53,r.engine.off.last())
    }
    @Test fun mixBanksRouteSoloAndKeepLegacyTempoAndLevelRange() {
        val r = Rig(); r.baseline(); r.surface.choosePage(CafaSurface.MIX)
        r.surface.knob(0, 300)
        assertEquals(1f, r.state.tracks[0].volume)
        r.input(encoder = 0, delta = 1)
        assertTrue(String(r.surface.snapshot(), 76, 8).contains("SOLO"))
        r.surface.knob(3, 1)
        assertTrue(r.state.tracks[0].isSoloed)
        assertEquals(listOf(0 to true), r.engine.solos)
        r.surface.knob(3, -1)
        assertFalse(r.state.tracks[0].isSoloed)
        r.input(encoder = 0, delta = -1)
        r.surface.knob(3, 1)
        assertEquals(121f, r.state.tempo)
    }
    @Test fun phoneMixChangesRetireDrawnHomeAndBothMixBanks() {
        for (case in 0..2) {
            val r = Rig(); r.baseline()
            if (case != 0) r.surface.choosePage(CafaSurface.MIX)
            if (case == 2) r.input(encoder = 0, delta = 1)
            val slot = when (case) { 0 -> 3; 1 -> 5; else -> 6 }
            val oldEpoch = Protocol.u14(r.surface.snapshot(), 152)
            val old = r.state.tracks[0]
            r.state = r.state.copy(tracks = r.state.tracks.mapIndexed { index, track ->
                if (index != 0) track else when (case) {
                    0 -> track.copy(volume = .5f)
                    1 -> track.copy(isMuted = true)
                    else -> track.copy(isSoloed = true)
                }
            })
            val delta = if (case == 0) 1 else -1
            r.input(encoder = slot, delta = delta, epoch = oldEpoch)
            assertEquals("stale scan case $case", if (case == 0) .5f else old.volume, r.state.tracks[0].volume)
            assertEquals("stale mute case $case", case == 1, r.state.tracks[0].isMuted)
            assertEquals("stale solo case $case", case == 2, r.state.tracks[0].isSoloed)
            assertTrue("stale scan reached native case $case", r.engine.levels.isEmpty() && r.engine.mutes.isEmpty() && r.engine.solos.isEmpty())
            val freshEpoch = Protocol.u14(r.surface.snapshot(), 152)
            assertNotEquals("mix change needs new display context case $case", oldEpoch, freshEpoch)
            r.input(encoder = slot, delta = delta, epoch = freshEpoch)
            when (case) {
                0 -> assertEquals(.51f, r.state.tracks[0].volume, .0001f)
                1 -> assertFalse(r.state.tracks[0].isMuted)
                else -> assertFalse(r.state.tracks[0].isSoloed)
            }
        }
    }
    @Test fun consecutiveAcceptedMixTurnsAdvanceTheirContext() {
        val r = Rig(); r.baseline(); r.surface.choosePage(CafaSurface.MIX)
        val epoch = Protocol.u14(r.surface.snapshot(), 152)
        r.input(encoder = 3, delta = 1, epoch = epoch)
        r.input(encoder = 3, delta = 1, epoch = epoch)
        assertEquals(.27f, r.state.tracks[0].volume, .0001f)
        assertEquals(2, r.engine.levels.size)
    }
    @Test fun replacingSelectedPartIdentityRetiresOldHomeScanAndReleasesOriginalVoice() {
        val r = Rig(); r.baseline(); r.input(keys = 1)
        val oldEpoch = Protocol.u14(r.surface.snapshot(), 152)
        r.state = r.state.copy(tracks = r.state.tracks.mapIndexed { index, track ->
            if (index == 0) track.copy(id = 100) else track
        })
        r.input(keys = 1, encoder = 3, delta = 1, epoch = oldEpoch)
        assertEquals(listOf(0 to 53), r.engine.off)
        assertEquals(.25f, r.state.tracks[0].volume)
        assertTrue(r.engine.levels.isEmpty())
        assertNotEquals(oldEpoch, Protocol.u14(r.surface.snapshot(), 152))
    }
    @Test fun partNavigationReleasesOriginalVoiceBeforeNewTrackSelection() {
        val r = Rig(); r.baseline(); r.input(keys = 1)
        r.input(keys = 1, encoder = 1, delta = 1)
        assertEquals(listOf(0 to 53), r.engine.off)
        assertEquals(1, r.state.selectedTrackIndex)
        r.input()
        assertEquals(listOf(0 to 53), r.engine.off)
    }
    @Test fun allEightTracksAndPhoneEditsReachTheSnapshot() {
        val r=Rig(); r.baseline()
        r.input(encoder=1,delta=7); assertEquals(7,r.state.selectedTrackIndex)
        r.surface.choosePage(CafaSurface.MIX); r.surface.knob(0,5)
        assertEquals(.3f,r.state.tracks[7].volume,.0001f)
        var b=r.surface.snapshot(); assertEquals(7,b[0].toInt()); assertEquals(30,Protocol.u14(b,84))
        r.state=r.state.copy(tracks=r.state.tracks.mapIndexed { i,t -> if(i==7)t.copy(volume=.73f) else t })
        b=r.surface.snapshot(); assertEquals(73,Protocol.u14(b,84))
        r.state=r.state.copy(selectedTab=2); assertEquals(CafaSurface.SEQ,r.surface.snapshot()[1].toInt())
    }
    @Test fun duplicateAndGapInputsDoNotChangeTheModel() {
        val r=Rig(); r.baseline(); r.input(encoder=1,delta=1)
        val data=ByteArray(22); for(i in 0..6) Protocol.put14(data,6+i*2,8192)
        Protocol.put14(data,8,8193)
        assertTrue(r.surface.input(r.serial,data)); assertEquals(1,r.state.selectedTrackIndex)
        assertFalse(r.surface.input(r.serial+2,data)); assertEquals(1,r.state.selectedTrackIndex)
        for(i in 0 until 17000) assertTrue(r.input())
    }
    @Test fun melodicKeySelectionDoesNotToggleComplexStep() {
        val r=Rig(); val detail=StepState(active=true,notes=listOf(60,64,67),velocity=.81f,ratchet=3,punch=true,probability=.72f,gate=2.5f,isSkipped=true,subStepOffset=.08f,noteOffsets=listOf(.01f,.02f,.03f),noteVelocities=listOf(.4f,.7f,.9f))
        r.state=r.state.copy(tracks=r.state.tracks.mapIndexed { i,t -> if(i==0)t.copy(steps=t.steps.mapIndexed { j,s -> if(j==0)detail else s }) else t })
        r.baseline(); r.surface.choosePage(CafaSurface.SEQ); r.input(keys=1); r.input()
        assertTrue(r.engine.steps.isEmpty())
        assertEquals(0 to 53,r.engine.off.last())
        assertEquals(detail,r.state.tracks[0].steps[0])
    }
    @Test fun melodicKeySelectionLeavesLockedStepIntact() {
        val r=Rig(); val step=StepState(active=true,notes=listOf(60),parameterLocks=mapOf(112 to .5f))
        r.state=r.state.copy(tracks=r.state.tracks.mapIndexed { i,t -> if(i==0)t.copy(steps=t.steps.mapIndexed { j,s -> if(j==0)step else s }) else t })
        r.baseline(); r.surface.choosePage(CafaSurface.SEQ); r.input(keys=1); r.input()
        assertTrue(r.engine.steps.isEmpty()); assertEquals(step,r.state.tracks[0].steps[0])
    }
    @Test fun drumAndSliceRowsStayAddressedWhenEmptyOrDisabled() {
        for(type in listOf(EngineType.FM_DRUM,EngineType.ANALOG_DRUM,EngineType.SAMPLER)) {
            val r=Rig(); r.state=r.state.copy(tracks=r.state.tracks.mapIndexed { i,t -> if(i==0)t.copy(engineType=type,selectedFmDrumInstrument=5,parameters=mapOf(320 to .5f)) else t })
            r.baseline(); r.surface.choosePage(CafaSurface.SEQ); r.input(keys=1); r.input()
            assertEquals(listOf(65),r.engine.steps.last().third.notes); assertTrue(r.state.tracks[0].drumSteps[5][0].active)
            r.input(keys=1); r.input(); assertEquals(listOf(65),r.engine.steps.last().third.notes)
            assertFalse(r.state.tracks[0].drumSteps[5][0].active); assertFalse(r.state.tracks[0].drumSteps[0][0].active)
        }
    }
    @Test fun envelopeMappingIsEngineAwareAndExplicitlyPercentage() {
        val maps=mapOf(EngineType.SUBTRACTIVE to 100,EngineType.FM to 100,EngineType.SAMPLER to 310,EngineType.GRANULAR to 425,EngineType.WAVETABLE to 454,EngineType.AUDIO_IN to 100,EngineType.SOUNDFONT to 100)
        for((type,base) in maps) {
            val r=Rig(); r.state=r.state.copy(tracks=r.state.tracks.mapIndexed { i,t -> if(i==0)t.copy(engineType=type,parameters=(0..3).associate {base+it to .5f}) else t })
            r.surface.choosePage(CafaSurface.ENV)
            for(i in 0..3) { r.surface.knob(i,1); assertEquals(base+i,r.engine.parameters.last().second) }
            val b=r.surface.snapshot(); assertEquals(51,Protocol.u14(b,84)); assertTrue(String(b,52,8).contains("%"))
        }
        val r=Rig(); r.state=r.state.copy(tracks=r.state.tracks.mapIndexed { i,t -> if(i==0)t.copy(engineType=EngineType.MIDI) else t })
        r.surface.choosePage(CafaSurface.ENV); r.surface.knob(0,20)
        assertTrue(r.engine.parameters.isEmpty()); assertTrue(String(r.surface.snapshot(),52,8).contains("NO ADSR"))
    }
    @Test fun patternBoundsAndBankRemainConsistent() {
        val r=Rig(); r.baseline(); r.surface.choosePage(CafaSurface.PATTERN); r.surface.knob(0,100)
        assertEquals(64,r.state.tracks[0].patternLength); r.surface.choosePage(CafaSurface.SEQ)
        r.input(encoder=2,delta=63)
        assertEquals(48,Protocol.u14(r.surface.snapshot(),5))
        assertEquals("STEP 64/64",String(r.surface.snapshot(),36,16).trim())
        r.surface.choosePage(CafaSurface.PATTERN); r.surface.knob(0,-100)
        assertEquals(4,r.state.tracks[0].patternLength)
        assertEquals(0,Protocol.u14(r.surface.snapshot(),5))
    }
    @Test fun transportAndResetAreSafe() {
        val r=Rig(); r.baseline(); r.input(buttons=1 shl 10)
        assertTrue(r.engine.running); assertTrue(r.state.isPlaying); assertEquals(listOf(true),r.engine.playback)
        r.input(keys=1); r.surface.stop()
        assertFalse(r.engine.running); assertFalse(r.state.isPlaying); assertEquals(0 to 53,r.engine.off.last())
        assertEquals(listOf(true,false),r.engine.playback); assertEquals(listOf(false),r.engine.recordingCalls)
    }
    @Test fun teardownStopsAndReleasesHeldNotesWithUnreportableTempo() {
        val r=Rig(); r.baseline(); r.input(keys=1)
        r.state=r.state.copy(isPlaying=true,tempo=Float.NaN)
        r.surface.stop()
        assertFalse(r.engine.running); assertFalse(r.state.isPlaying)
        assertEquals(listOf(false),r.engine.playback)
        assertEquals(listOf(false),r.engine.recordingCalls)
        assertEquals(0 to 53,r.engine.off.last())
        assertTrue(r.state.heldNotes.isEmpty())
    }
    @Test fun tempoMatchesTouchscreenAndPreservesExternalClockRange() {
        val r = Rig(); r.surface.choosePage(CafaSurface.MIX)
        r.surface.knob(3,-999); assertEquals(12f,r.state.tempo,0f)
        assertEquals(12,Protocol.u14(r.surface.snapshot(),9))
        r.surface.knob(3,999); assertEquals(240f,r.state.tempo,0f)
        assertEquals(listOf(12f,240f),r.engine.tempos)
        r.state=r.state.copy(tempo=300f); assertEquals(300,Protocol.u14(r.surface.snapshot(),9))
        r.state=r.state.copy(tempo=11f)
        try { r.surface.snapshot(); fail("Unrepresentable tempo must not be silently clamped") }
        catch (_: IllegalArgumentException) { }
    }
    @Test fun sequenceCursorEditsOneChordNoteAndPreservesTheOtherNotes() {
        val r=Rig()
        val original=StepState(active=true, notes=listOf(60,64,67), velocity=.7f,
            noteVelocities=listOf(.4f,.6f,.8f), noteOffsets=listOf(.01f,.03f,.06f),
            gate=2f, ratchet=3, punch=true, subStepOffset=.12f)
        r.state=r.state.copy(tracks=r.state.tracks.mapIndexed { i,t -> if(i==0)t.copy(steps=t.steps.mapIndexed { j,v -> if(j==0)original else v }) else t })
        r.baseline(); r.surface.choosePage(CafaSurface.SEQ)
        r.input(keys=1L shl 11) // Select the existing middle note (pitch 64).
        r.input()
        r.input(encoder=3,delta=1)
        val changed=r.state.tracks[0].steps[0]
        assertEquals(listOf(60,65,67),changed.notes)
        assertEquals(original.noteVelocities,changed.noteVelocities)
        assertEquals(original.noteOffsets,changed.noteOffsets)
        assertEquals(3,changed.ratchet); assertTrue(changed.punch)
    }
    @Test fun stoppedSequenceEntryAddsNotesWithoutStartingTransportRecording() {
        val r=Rig(); r.baseline(); r.surface.choosePage(CafaSurface.SEQ)
        r.input(buttons=1 shl 11)
        assertFalse(r.state.isRecording)
        assertTrue(r.engine.recordingCalls.isEmpty())
        r.input(); r.input(keys=1); r.input()
        assertEquals(listOf(53),r.state.tracks[0].steps[0].notes)
        assertEquals(0 to 53,r.engine.off.last())
        assertEquals("STEP 2/16",String(r.surface.snapshot(),36,16).trim())
    }
    @Test fun stoppedStepEntryRemainsArmedAcrossBankBoundary() {
        val r=Rig()
        r.state=r.state.copy(tracks=r.state.tracks.mapIndexed { i,t ->
            if(i==0)t.copy(patternLength=32) else t })
        r.baseline(); r.surface.choosePage(CafaSurface.SEQ)
        r.input(encoder=2,delta=15)
        r.input(buttons=1 shl 11); r.input()
        r.input(keys=1); r.input()
        assertEquals(listOf(53),r.state.tracks[0].steps[15].notes)
        assertEquals(16,Protocol.u14(r.surface.snapshot(),5))
        assertEquals("STEP 17/32",String(r.surface.snapshot(),36,16).trim())
        assertTrue((Protocol.u14(r.surface.snapshot(),14) and (1 shl 11)) != 0)
        r.input(keys=1L shl 2); r.input()
        assertEquals(listOf(55),r.state.tracks[0].steps[16].notes)
        assertFalse(r.state.isRecording)
    }
    @Test fun heldStepBindingWritesLockAndLeavesBaseSoundUntouched() {
        val r=Rig()
        r.state=r.state.copy(tracks=r.state.tracks.mapIndexed { i,t -> if(i==0)t.copy(
            engineType=EngineType.FM_DRUM, parameters=t.parameters + (200 to .3f),
            fm1StepKnobBindings=listOf(Fm1KnobBinding(200,"PITCH",.5f),null,null,null)) else t })
        r.baseline(); r.surface.choosePage(CafaSurface.SEQ)
        r.input(keys=1)
        r.input(keys=1,encoder=3,delta=10)
        r.input()
        assertEquals(.3f,r.state.tracks[0].parameters[200]!!,0f)
        assertEquals(.4f,r.state.tracks[0].drumSteps[0][0].parameterLocks[200]!!,.0001f)
        assertFalse(r.state.tracks[0].drumSteps[0][0].active)
        assertEquals(listOf(60),r.engine.steps.last().third.notes)
    }
    @Test fun phoneBindingChangeRejectsHeldStepTurnAndStillReleasesVoice() {
        val r=Rig()
        r.state=r.state.copy(tracks=r.state.tracks.mapIndexed { i,t -> if(i==0)t.copy(
            fm1StepKnobBindings=listOf(Fm1KnobBinding(112,"OLD"),null,null,null)) else t })
        r.baseline(); r.surface.choosePage(CafaSurface.SEQ)
        r.input(keys=1)
        val oldEpoch=Protocol.u14(r.surface.snapshot(),152)
        r.state=r.state.copy(tracks=r.state.tracks.mapIndexed { i,t -> if(i==0)t.copy(
            fm1StepKnobBindings=listOf(Fm1KnobBinding(113,"NEW"),null,null,null)) else t })
        val p=ByteArray(22)
        for(i in 0..6) Protocol.put14(p,6+i*2,8192)
        Protocol.put14(p,12,8193); Protocol.put14(p,20,oldEpoch)
        assertTrue(r.surface.input(++r.serial,p))
        assertTrue(r.engine.steps.isEmpty())
        assertEquals(0 to 53,r.engine.off.last())
        assertTrue(r.state.tracks[0].steps[0].parameterLocks.isEmpty())
    }
    @Test fun sameTrackPhoneSoundBindingChangeRetiresHeldDefaultBank() {
        val r=Rig()
        r.state=r.state.copy(tracks=r.state.tracks.mapIndexed { i,t -> if(i==0)t.copy(
            engineType=EngineType.FM_DRUM,
            fm1KnobBindings=listOf(Fm1KnobBinding(200,"OLD"),null,null,null)) else t })
        r.baseline(); r.surface.choosePage(CafaSurface.SOUND); r.surface.choosePage(CafaSurface.SEQ)
        r.input(keys=1)
        val oldEpoch=Protocol.u14(r.surface.snapshot(),152)
        r.state=r.state.copy(tracks=r.state.tracks.mapIndexed { i,t -> if(i==0)t.copy(
            fm1KnobBindings=listOf(Fm1KnobBinding(201,"NEW"),null,null,null)) else t })
        val oldTurn=ByteArray(22)
        for(i in 0..6) Protocol.put14(oldTurn,6+i*2,8192)
        Protocol.put14(oldTurn,2,1); Protocol.put14(oldTurn,12,8202); Protocol.put14(oldTurn,20,oldEpoch)
        assertTrue(r.surface.input(++r.serial,oldTurn))
        assertTrue(r.engine.steps.isEmpty())
        r.input()
        r.input(keys=1); r.input(keys=1,encoder=3,delta=10); r.input()
        assertEquals(.6f,r.state.tracks[0].drumSteps[0][0].parameterLocks[201]!!,.0001f)
        assertFalse(r.state.tracks[0].drumSteps[0][0].parameterLocks.containsKey(200))
    }
    @Test fun heldGestureCancelsOnTrackPageBankAndDisconnect() {
        for(action in 0..4) {
            val r=Rig(); r.baseline(); r.surface.choosePage(CafaSurface.SEQ); r.input(keys=1)
            when(action) {
                0 -> r.state=r.state.copy(selectedTrackIndex=7)
                1 -> r.surface.choosePage(CafaSurface.ENV)
                2 -> r.input(keys=1,encoder=2,delta=1)
                3 -> r.surface.reset()
                else -> r.state=r.state.copy(currentSequencerBank=1,tracks=r.state.tracks.map { it.copy(patternLength=64) })
            }
            r.input(); assertTrue(r.engine.steps.isEmpty())
        }
    }
    @Test fun simultaneousNavigationAndReleaseCancelsRatherThanToggles() {
        for(action in 0..2) {
            val r=Rig(); r.baseline(); r.surface.choosePage(CafaSurface.SEQ); r.input(keys=1)
            when(action) {
                0 -> r.input(buttons=1 shl 2)
                1 -> r.input(encoder=1,delta=1)
                else -> r.input(encoder=2,delta=1)
            }
            assertTrue(r.engine.steps.isEmpty())
        }
    }
    @Test fun allLfosPreserveRoutingAndReflectPhoneEdits() {
        val r=Rig(); r.baseline(); r.input(buttons=1 shl 3)
        assertEquals(CafaSurface.LFO,r.surface.page)
        r.input(encoder=0,delta=5)
        r.state=r.state.copy(lfos=r.state.lfos.mapIndexed { i,v -> if(i==5)v.copy(targetId=112,targetType=1,targetLabel="Cutoff",intensity=.37f) else v })
        for(i in 0..3) r.surface.knob(i,1)
        val l=r.state.lfos[5]
        assertEquals(112,l.targetId); assertEquals(.37f,l.intensity,0f); assertEquals(1,l.shape); assertTrue(l.sync)
        assertEquals(5,r.engine.lfos.last().first)
        r.state=r.state.copy(lfos=r.state.lfos.mapIndexed { i,v -> if(i==5)v.copy(rate=.83f) else v })
        assertEquals(83,Protocol.u14(r.surface.snapshot(),84))
    }
    @Test fun fxEditsRealBusWithoutChangingOtherTracksOrBuses() {
        val r=Rig(); r.baseline(); r.input(buttons=1)
        r.surface.knob(0,17); r.surface.knob(1,25); r.surface.knob(2,60); r.surface.knob(3,-1)
        assertEquals(17,r.surface.fxIndex)
        assertEquals(Triple(0,2170,.25f),r.engine.parameters.last())
        assertEquals(.25f,r.state.tracks[0].fxSends[17],0f); assertEquals(0f,r.state.tracks[0].fxMix[17],0f)
        assertEquals(0f,r.state.tracks[1].fxSends[17],0f); assertEquals(0f,r.state.tracks[0].fxSends[0],0f)
        assertFalse(r.state.fxEnabled[17]); assertEquals(17 to false,r.engine.bypass.last())
        r.state=r.state.copy(tracks=r.state.tracks.mapIndexed { i,t -> if(i==0)t.copy(fxMix=t.fxMix.mapIndexed { n,v -> if(n==17).42f else v }) else t })
        assertEquals(100,Protocol.u14(r.surface.snapshot(),88)); assertTrue(r.notice.contains("unity"))
    }
    @Test fun arpPagesPreserveRhythmsAndUseNativeRateValues() {
        val r=Rig(); r.baseline(); r.input(buttons=1 shl 8)
        val before=r.state.tracks[0].arpConfig
        r.surface.knob(0,1); r.surface.knob(1,-3); r.surface.knob(2,1); r.surface.knob(3,2)
        val a=r.state.tracks[0].arpConfig
        assertEquals(ArpMode.UP,a.mode); assertEquals(-3,a.octaves); assertTrue(a.isLatched); assertEquals(4f,a.arpRate,0f)
        assertEquals(before.rhythms,a.rhythms); assertEquals(before.gateLengths,a.gateLengths)
        r.input(encoder=0,delta=1); r.surface.knob(0,-2); r.surface.knob(1,25); r.surface.knob(2,50); r.surface.knob(3,10)
        val b=r.state.tracks[0].arpConfig
        assertEquals(-2,b.inversion); assertEquals(.25f,b.probability,0f); assertEquals(.5f,b.weird,0f); assertEquals(.1f,b.strum,0f)
        assertEquals(b,r.engine.arps.last().second)
    }
    @Test fun physicalFamiliesAndGloTempoStaySynchronized() {
        val r=Rig(); r.baseline()
        for((button,page) in listOf(0 to CafaSurface.FX,1 to CafaSurface.SOUND,2 to CafaSurface.ENV,
                3 to CafaSurface.LFO,4 to CafaSurface.SOUND,5 to CafaSurface.MIX,6 to CafaSurface.HOME,
                8 to CafaSurface.ARP,9 to CafaSurface.SEQ)) {
            r.input(); r.input(buttons=1 shl button)
            assertEquals(page,r.surface.page); assertEquals(if(page == CafaSurface.SOUND) 9 else page,r.surface.snapshot()[1].toInt())
        }
        r.input(); r.input(buttons=1 shl 9); assertEquals(CafaSurface.PATTERN,r.surface.page)
        r.surface.knob(2,-100); assertEquals(-.23f,r.state.swing,0f)
        r.surface.knob(3,20); assertEquals(.2f,r.state.tracks[0].humanize,0f)
        r.input(buttons=1 shl 5,encoder=0,delta=3); assertEquals(123f,r.state.tempo,0f)
        assertEquals(CafaSurface.MIX,r.surface.page)
        r.input(buttons=1 shl 13); assertEquals(32,r.surface.snapshot()[2].toInt() and 56)
    }
    @Test fun firstSnapshotDoesNotReplayEncoderAndUnsupportedRecDoesNotLie() {
        val r=Rig(); r.input(encoder=1,delta=7); assertEquals(0,r.state.selectedTrackIndex)
        r.input(buttons=1 shl 11); assertFalse(r.state.isRecording); assertTrue(r.notice.contains("synchronization"))
    }
    @Test fun exportActualModelWireFixtures() {
        val directory = java.io.File("build/cafa-model-vectors").apply { mkdirs() }
        val r = Rig()
        r.state = r.state.copy(isPlaying = true, tracks = List(8) { i ->
            TrackState(id = i, engineType = EngineType.SUBTRACTIVE, patternLength = 64,
                parameters = mapOf(100 to 0f, 101 to .33f, 102 to .66f, 103 to .99f))
        }, currentSequencerBank = 3)
        java.io.File(directory,"hello.syx").writeBytes(Protocol.frame(Protocol.HELLO,57,0,ByteArray(0)))
        var revision = 0
        for (track in 0..7) for (page in 0..8) {
            r.state = r.state.copy(selectedTrackIndex = track)
            r.surface.choosePage(page)
            val payload = r.surface.snapshot()
            assertEquals(track, payload[0].toInt()); assertEquals(if(page == CafaSurface.SOUND) 9 else page,payload[1].toInt())
            assertEquals(64,Protocol.u14(payload,3)); assertEquals(if(page == CafaSurface.SEQ) 0 else 48,Protocol.u14(payload,5))
            assertTrue(payload.all { it.toInt() >= 0 })
            java.io.File(directory,"t${track+1}-p$page.syx").writeBytes(Protocol.frame(Protocol.STATE,57,++revision,payload))
        }
        for (tempo in listOf(12f,240f,300f)) {
            r.state=r.state.copy(tempo=tempo)
            java.io.File(directory,"tempo${tempo.toInt()}.syx").writeBytes(Protocol.frame(Protocol.STATE,57,++revision,r.surface.snapshot()))
        }
        java.io.File(directory,"bye.syx").writeBytes(Protocol.frame(Protocol.BYE,57,revision,ByteArray(0)))
    }
    @Test fun runtimeAdapterBrowsesAndPagesWithoutFirmwareEngineIds() {
        val r=Rig(); r.baseline(); r.surface.choosePage(CafaSurface.SOUND)
        var activated = -1
        r.surface.catalog.register(CafaCatalog.Entry("user.orbit", "ORBIT CUSTOM", EngineType.SUBTRACTIVE,
            { _, _ -> List(9) { CafaCatalog.Control(900+it,"ORB$it",.25f) } }, { activated=it }))
        r.input(encoder=2,delta=100)
        assertEquals(0,activated)
        var b=r.surface.snapshot(); assertEquals(9,b[1].toInt())
        assertEquals(11,Protocol.u14(b,98)); assertEquals(10,Protocol.u14(b,96))
        assertEquals("ORBIT CUSTOM",String(b,20,16).trim())
        assertEquals(3,Protocol.u14(b,94))
        r.input(encoder=0,delta=2); b=r.surface.snapshot(); assertEquals(2,Protocol.u14(b,92))
        r.input(encoder=3,delta=10)
        assertEquals(908,r.engine.parameters.last().second)
        assertEquals(.35f,r.engine.parameters.last().third,.0001f)
        assertEquals(0,r.surface.snapshot()[149].toInt())
    }
    @Test fun staleContextReleasesNotesButCannotEditDifferentEngine() {
        val r=Rig(); r.baseline(); r.surface.choosePage(CafaSurface.SOUND)
        r.input(keys=1); val old=Protocol.u14(r.surface.snapshot(),152)
        r.input(keys=1,encoder=2,delta=1)
        val p=ByteArray(22); for(i in 0..6) Protocol.put14(p,6+i*2,8192)
        Protocol.put14(p,12,8292); Protocol.put14(p,20,old)
        assertTrue(r.surface.input(++r.serial,p))
        assertTrue(r.engine.parameters.isEmpty()); assertTrue(r.engine.off.isNotEmpty())
    }
    @Test fun navigationAndKnobInOneScanCannotEditNewBank() {
        val r=Rig(); r.baseline(); r.surface.choosePage(CafaSurface.SOUND)
        val p=ByteArray(22); Protocol.put14(p,20,Protocol.u14(r.surface.snapshot(),152))
        for(i in 0..6) Protocol.put14(p,6+i*2,8192)
        Protocol.put14(p,10,8193); Protocol.put14(p,12,8292)
        assertTrue(r.surface.input(++r.serial,p)); assertTrue(r.engine.parameters.isEmpty())
    }

    @Test(expected = IllegalArgumentException::class) fun oversizedBankCatalogIsRejectedInsteadOfTruncated() {
        val r=Rig(); r.surface.catalog.register(CafaCatalog.Entry("SUBTRACTIVE","TOO LARGE",EngineType.SUBTRACTIVE,
            { _, _ -> List(65533) { CafaCatalog.Control(it,"X") } }))
        r.surface.snapshot()
    }
    @Test fun catalogMetadataChangesInvalidateOldInputToken() {
        val r=Rig(); r.baseline(); r.surface.choosePage(CafaSurface.SOUND)
        val old=Protocol.u14(r.surface.snapshot(),152)
        r.surface.catalog.register(CafaCatalog.Entry("SUBTRACTIVE","NEW",EngineType.SUBTRACTIVE,
            { _, _ -> listOf(CafaCatalog.Control(999,"NEW",.5f)) }))
        val p=ByteArray(22); for(i in 0..6) Protocol.put14(p,6+i*2,8192)
        Protocol.put14(p,12,8193); Protocol.put14(p,20,old)
        assertTrue(r.surface.input(++r.serial,p)); assertTrue(r.engine.parameters.isEmpty())
        assertNotEquals(old,Protocol.u14(r.surface.snapshot(),152))
    }

    @Test fun customAdapterIdentityBelongsToEachTrack() {
        val r=Rig(); r.state=r.state.copy(tracks=r.state.tracks.map { it.copy(engineType=EngineType.SUBTRACTIVE) })
        r.baseline(); r.surface.choosePage(CafaSurface.SOUND)
        r.surface.catalog.register(CafaCatalog.Entry("custom","CUSTOM",EngineType.SUBTRACTIVE,{_,_->emptyList()}))
        r.input(encoder=2,delta=100)
        assertEquals("CUSTOM",String(r.surface.snapshot(),20,16).trim())
        r.input(encoder=1,delta=1)
        assertEquals("SUBTRACTIVE",String(r.surface.snapshot(),20,16).trim())
        r.input(encoder=1,delta=-1)
        assertEquals("CUSTOM",String(r.surface.snapshot(),20,16).trim())
    }

    @Test fun customAdapterSelectionSurvivesProjectSerializationAndSurfaceRecreation() {
        fun register(r: Rig) {
            r.surface.catalog.register(CafaCatalog.Entry("user.alpha","ALPHA",EngineType.SUBTRACTIVE,
                {_,_->listOf(CafaCatalog.Control(901,"ALPHA",.2f))}))
            r.surface.catalog.register(CafaCatalog.Entry("user.beta","BETA",EngineType.SUBTRACTIVE,
                {_,_->listOf(CafaCatalog.Control(902,"BETA",.4f))}))
        }
        val original=Rig(); register(original); original.baseline(); original.surface.choosePage(CafaSurface.SOUND)
        original.input(encoder=2,delta=10)
        assertEquals("ALPHA",String(original.surface.snapshot(),20,16).trim())
        original.input(encoder=1,delta=1)
        original.input(encoder=2,delta=100)
        assertEquals("BETA",String(original.surface.snapshot(),20,16).trim())

        val bytes=java.io.ByteArrayOutputStream().also { output ->
            java.io.ObjectOutputStream(output).use { it.writeObject(original.state) }
        }.toByteArray()
        val restored=java.io.ObjectInputStream(java.io.ByteArrayInputStream(bytes)).use {
            it.readObject() as GrooveboxState
        }
        val fresh=Rig(); fresh.state=restored; register(fresh); fresh.baseline(); fresh.surface.choosePage(CafaSurface.SOUND)
        assertEquals("BETA",String(fresh.surface.snapshot(),20,16).trim())
        fresh.input(encoder=3,delta=1)
        assertEquals(1,fresh.engine.parameters.last().first)
        assertEquals(902,fresh.engine.parameters.last().second)
        assertEquals(.41f,fresh.engine.parameters.last().third,.0001f)
        fresh.input(encoder=1,delta=-1)
        assertEquals("ALPHA",String(fresh.surface.snapshot(),20,16).trim())
        fresh.input(encoder=3,delta=1)
        assertEquals(0,fresh.engine.parameters.last().first)
        assertEquals(901,fresh.engine.parameters.last().second)
        assertEquals(.21f,fresh.engine.parameters.last().third,.0001f)
    }

    @Test fun legacyAndMissingCatalogEntriesDoNotSelectAnotherSound() {
        val r=Rig(); r.state=r.state.copy(tracks=r.state.tracks.mapIndexed { i,t ->
            if (i == 0) t.copy(engineType=EngineType.SUBTRACTIVE, catalogEntryId=null)
            else if (i == 1) t.copy(engineType=EngineType.SUBTRACTIVE, catalogEntryId="user.absent") else t
        })
        r.surface.catalog.register(CafaCatalog.Entry("user.other","OTHER",EngineType.SUBTRACTIVE,
            {_,_->listOf(CafaCatalog.Control(999,"WRONG",.5f))}))
        r.baseline(); r.surface.choosePage(CafaSurface.SOUND)
        assertEquals("SUBTRACTIVE",String(r.surface.snapshot(),20,16).trim())
        r.input(encoder=1,delta=1)
        val missing=r.surface.snapshot()
        assertEquals("MISSING ENTRY",String(missing,20,16).trim())
        assertTrue(String(missing,36,16).contains("user.absent"))
        assertTrue(Protocol.u14(missing,96) < Protocol.u14(missing,98))
        assertEquals(0,missing[148].toInt())
        r.input(encoder=3,delta=1)
        assertTrue(r.engine.parameters.isEmpty())
        assertEquals("user.absent",r.state.tracks[1].catalogEntryId)
        r.input(encoder=2,delta=100)
        assertEquals("OTHER",String(r.surface.snapshot(),20,16).trim())
        r.input(encoder=3,delta=1)
        assertEquals(999,r.engine.parameters.last().second)
        assertEquals("user.other",r.state.tracks[1].catalogEntryId)
    }

    @Test fun presetKnobSlotsKeepHolesAndLeaveAutomationAndDefaultBanksIntact() {
        val r=Rig()
        val locked=StepState(active=true,notes=listOf(60),parameterLocks=mapOf(1 to .8f))
        r.state=r.state.copy(tracks=r.state.tracks.mapIndexed { i,t -> if(i==0)t.copy(
            parameters=t.parameters + (1 to .4f) + (2 to .2f),
            steps=t.steps.mapIndexed { j,s -> if(j==0)locked else s },
            fm1KnobBindings=listOf(null,Fm1KnobBinding(2,"Res"),Fm1KnobBinding(1,"Cut"),Fm1KnobBinding(1,"Cut"))) else t })
        r.baseline(); r.surface.choosePage(CafaSurface.SOUND)
        val b=r.surface.snapshot()
        assertEquals("PRESET KNOBS",String(b,36,16).trim())
        assertEquals(0,b[148].toInt())
        assertEquals("Res",String(b,60,8).trim())
        assertEquals("Cut",String(b,68,8).trim())
        assertEquals("Cut",String(b,76,8).trim())
        r.input(encoder=3,delta=10); assertTrue(r.engine.parameters.isEmpty())
        r.input(encoder=4,delta=10)
        assertEquals(Triple(0,2,.3f),r.engine.parameters.last())
        r.input(encoder=5,delta=10)
        assertEquals(Triple(0,1,.5f),r.engine.parameters.last())
        assertEquals(locked,r.state.tracks[0].steps[0])
        r.input(encoder=0,delta=1)
        assertEquals("CONTROLS 1",String(r.surface.snapshot(),36,16).trim())
        r.input(encoder=2,delta=1)
        assertNull(r.state.tracks[0].fm1KnobBindings)
    }

    @Test fun replacingPresetBindingsRejectsOldKnobInputButStillReleasesHeldNotes() {
        val r=Rig()
        r.state=r.state.copy(tracks=r.state.tracks.mapIndexed { i,t -> if(i==0)t.copy(
            fm1KnobBindings=listOf(Fm1KnobBinding(1,"Old"),null,null,null)) else t })
        r.baseline(); r.surface.choosePage(CafaSurface.SOUND); r.input(keys=1)
        val old=Protocol.u14(r.surface.snapshot(),152)
        r.state=r.state.copy(tracks=r.state.tracks.mapIndexed { i,t -> if(i==0)t.copy(
            fm1KnobBindings=listOf(Fm1KnobBinding(2,"New"),null,null,null)) else t })
        val p=ByteArray(22)
        for(i in 0..6) Protocol.put14(p,6+i*2,8192)
        Protocol.put14(p,12,8193); Protocol.put14(p,20,old)
        assertTrue(r.surface.input(++r.serial,p))
        assertTrue(r.engine.parameters.isEmpty())
        assertEquals(0 to 53,r.engine.off.last())
        assertEquals("New",String(r.surface.snapshot(),52,8).trim())
        r.input(encoder=3,delta=1)
        assertEquals(2,r.engine.parameters.last().second)
        r.state=r.state.copy(tracks=r.state.tracks.mapIndexed { i,t -> if(i==0)t.copy(fm1KnobBindings=null) else t })
        assertEquals("CONTROLS 1",String(r.surface.snapshot(),36,16).trim())
    }

    @Test fun saveButtonShowsTheActualPersistenceResultOnTheSurface() {
        for (success in listOf(true,false)) {
            val r=Rig(); r.saveResult=success; r.baseline(); r.surface.choosePage(CafaSurface.SOUND)
            r.input(buttons=1 shl 7)
            val expected=if (success) "SAVED" else "SAVE FAILED"
            assertTrue(r.saved)
            assertEquals(expected,r.notice)
            assertEquals(expected,String(r.surface.snapshot(),36,16).trim())
        }
    }

    @Test fun phoneValueChangesRejectOldKnobInputButStillReleaseNotes() {
        val r=Rig(); r.baseline(); r.surface.choosePage(CafaSurface.SOUND); r.input(keys=1)
        val old=Protocol.u14(r.surface.snapshot(),152)
        r.state=r.state.copy(tracks=r.state.tracks.mapIndexed { i,t ->
            if(i==0)t.copy(parameters=t.parameters+(112 to .8f)) else t })
        val p=ByteArray(22)
        for(i in 0..6) Protocol.put14(p,6+i*2,8192)
        Protocol.put14(p,12,8193); Protocol.put14(p,20,old)
        assertTrue(r.surface.input(++r.serial,p))
        assertTrue(r.engine.parameters.isEmpty())
        assertEquals(.8f,r.state.tracks[0].parameters[112]!!,.0001f)
        assertEquals(0 to 53,r.engine.off.last())
        r.input(encoder=3,delta=1)
        assertEquals(.81f,r.state.tracks[0].parameters[112]!!,.0001f)
    }

    @Test fun simultaneousAndConsecutiveKnobTurnsKeepEveryAcceptedDelta() {
        val r=Rig()
        r.state=r.state.copy(tracks=r.state.tracks.mapIndexed { i,t ->
            if(i==0)t.copy(parameters=t.parameters+(112 to .2f)+(113 to .3f)) else t })
        r.baseline(); r.surface.choosePage(CafaSurface.SOUND)
        val p=ByteArray(22)
        for(i in 0..6) Protocol.put14(p,6+i*2,8192)
        Protocol.put14(p,12,8193); Protocol.put14(p,14,8194)
        Protocol.put14(p,20,Protocol.u14(r.surface.snapshot(),152))
        assertTrue(r.surface.input(++r.serial,p))
        // No intervening display snapshot: repeated relative turns remain one valid gesture context.
        assertTrue(r.surface.input(++r.serial,p))
        assertEquals(listOf(112,113,112,113),r.engine.parameters.map { it.second })
        assertEquals(.22f,r.state.tracks[0].parameters[112]!!,.0001f)
        assertEquals(.34f,r.state.tracks[0].parameters[113]!!,.0001f)
    }

}
