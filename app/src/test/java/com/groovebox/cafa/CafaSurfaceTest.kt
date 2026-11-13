/* SPDX-License-Identifier: GPL-3.0-only */
package com.groovebox.cafa

import com.groovebox.*
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
        val lfos = mutableListOf<Pair<Int, LfoState>>()
        val arps = mutableListOf<Pair<Int, ArpConfig>>()
        val bypass = mutableListOf<Pair<Int, Boolean>>()
        val lanes = mutableListOf<Pair<Int, Int>>()
        val swings = mutableListOf<Float>()
        val humanize = mutableListOf<Pair<Int, Float>>()
        override fun noteOn(track: Int, note: Int) { on += track to note }
        override fun noteOff(track: Int, note: Int) { off += track to note }
        override fun playing(value: Boolean) { running = value }
        override fun recording(value: Boolean) {}
        override fun parameter(track: Int, id: Int, value: Float) { parameters += Triple(track, id, value) }
        override fun volume(track: Int, value: Float) {}
        override fun pan(track: Int, value: Float) {}
        override fun mute(track: Int, value: Boolean) {}
        override fun tempo(value: Float) {}
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
        val surface = CafaSurface({state}, {state=it}, engine, {state=state.copy(selectedTrackIndex=it)}, {saved=true}, {notice=it})
        var serial = 0
        fun input(buttons: Int = 0, keys: Long = 0, encoder: Int = -1, delta: Int = 0): Boolean {
            val p = ByteArray(20); Protocol.put14(p,0,buttons); Protocol.put28(p,2,keys)
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
        val data=ByteArray(20); for(i in 0..6) Protocol.put14(data,6+i*2,8192)
        Protocol.put14(data,8,8193)
        assertTrue(r.surface.input(r.serial,data)); assertEquals(1,r.state.selectedTrackIndex)
        assertFalse(r.surface.input(r.serial+2,data)); assertEquals(1,r.state.selectedTrackIndex)
        for(i in 0 until 17000) assertTrue(r.input())
    }
    @Test fun togglingComplexStepPreservesAllExistingDetails() {
        val r=Rig(); val detail=StepState(active=true,notes=listOf(60,64,67),velocity=.81f,ratchet=3,punch=true,probability=.72f,gate=2.5f,isSkipped=true,subStepOffset=.08f,noteOffsets=listOf(.01f,.02f,.03f),noteVelocities=listOf(.4f,.7f,.9f))
        r.state=r.state.copy(tracks=r.state.tracks.mapIndexed { i,t -> if(i==0)t.copy(steps=t.steps.mapIndexed { j,s -> if(j==0)detail else s }) else t })
        r.baseline(); r.surface.choosePage(CafaSurface.SEQ); r.input(keys=1); r.input()
        assertEquals(detail.copy(active=false),r.engine.steps.last().third)
        r.input(keys=1); r.input(); assertEquals(detail,r.engine.steps.last().third)
        assertEquals(detail,r.state.tracks[0].steps[0])
    }
    @Test fun lockedStepsAreRefusedRatherThanOverwritten() {
        val r=Rig(); val step=StepState(active=true,notes=listOf(60),parameterLocks=mapOf(112 to .5f))
        r.state=r.state.copy(tracks=r.state.tracks.mapIndexed { i,t -> if(i==0)t.copy(steps=t.steps.mapIndexed { j,s -> if(j==0)step else s }) else t })
        r.baseline(); r.surface.choosePage(CafaSurface.SEQ); r.input(keys=1); r.input()
        assertTrue(r.engine.steps.isEmpty()); assertEquals(step,r.state.tracks[0].steps[0]); assertTrue(r.notice.contains("P-locked"))
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
        val r=Rig(); r.baseline(); r.surface.choosePage(CafaSurface.SEQ); r.surface.knob(1,100)
        assertEquals(64,r.state.tracks[0].patternLength); r.input(encoder=2,delta=3)
        assertEquals(48,Protocol.u14(r.surface.snapshot(),5)); r.input(keys=1L shl 26); r.input()
        assertEquals(63,r.engine.steps.last().second)
        r.surface.knob(1,-100); assertEquals(4,r.state.tracks[0].patternLength)
        assertEquals(0,Protocol.u14(r.surface.snapshot(),5))
    }
    @Test fun transportAndResetAreSafe() {
        val r=Rig(); r.baseline(); r.input(buttons=1 shl 10)
        assertTrue(r.engine.running); assertTrue(r.state.isPlaying)
        r.input(keys=1); r.surface.stop()
        assertFalse(r.engine.running); assertFalse(r.state.isPlaying); assertEquals(0 to 53,r.engine.off.last())
    }
    @Test fun tempoMatchesTouchscreenAndPreservesExternalClockRange() {
        val r = Rig(); r.surface.choosePage(CafaSurface.MIX)
        r.surface.knob(3,-999); assertEquals(12f,r.state.tempo,0f)
        assertEquals(12,Protocol.u14(r.surface.snapshot(),9))
        r.surface.knob(3,999); assertEquals(240f,r.state.tempo,0f)
        r.state=r.state.copy(tempo=300f); assertEquals(300,Protocol.u14(r.surface.snapshot(),9))
        r.state=r.state.copy(tempo=11f)
        try { r.surface.snapshot(); fail("Unrepresentable tempo must not be silently clamped") }
        catch (_: IllegalArgumentException) { }
    }
    @Test fun heldStepEditsPreserveChordsAndDoNotToggleOnRelease() {
        val r=Rig()
        val original=StepState(active=true, notes=listOf(60,64,67), velocity=.7f,
            noteVelocities=listOf(.4f,.6f,.8f), noteOffsets=listOf(.01f,.03f,.06f),
            gate=2f, ratchet=3, punch=true, subStepOffset=.12f)
        r.state=r.state.copy(tracks=r.state.tracks.mapIndexed { i,t -> if(i==0)t.copy(steps=t.steps.mapIndexed { j,v -> if(j==0)original else v }) else t })
        r.baseline(); r.surface.choosePage(CafaSurface.SEQ)
        r.input(keys=1,encoder=3,delta=2) // A hold and turn may arrive in one snapshot.
        assertEquals(listOf(62,66,69),r.state.tracks[0].steps[0].notes)
        r.input(keys=1,encoder=4,delta=10)
        r.input(keys=1,encoder=5,delta=5)
        r.input(keys=1,encoder=6,delta=-20)
        val changed=r.state.tracks[0].steps[0]
        assertEquals(.8f,changed.velocity,.0001f); assertEquals(.5f,changed.noteVelocities[0],.0001f)
        assertEquals(2.5f,changed.gate,0f); assertEquals(.8f,changed.probability,.0001f)
        assertEquals(original.noteOffsets,changed.noteOffsets); assertEquals(3,changed.ratchet); assertTrue(changed.punch)
        r.input(); assertEquals(changed,r.state.tracks[0].steps[0]); assertTrue(changed.active)
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
            assertEquals(page,r.surface.page); assertEquals(page,r.surface.snapshot()[1].toInt())
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
            assertEquals(track, payload[0].toInt()); assertEquals(page,payload[1].toInt())
            assertEquals(64,Protocol.u14(payload,3)); assertEquals(48,Protocol.u14(payload,5))
            assertTrue(payload.all { it.toInt() >= 0 })
            java.io.File(directory,"t${track+1}-p$page.syx").writeBytes(Protocol.frame(Protocol.STATE,57,++revision,payload))
        }
        for (tempo in listOf(12f,240f,300f)) {
            r.state=r.state.copy(tempo=tempo)
            java.io.File(directory,"tempo${tempo.toInt()}.syx").writeBytes(Protocol.frame(Protocol.STATE,57,++revision,r.surface.snapshot()))
        }
        java.io.File(directory,"bye.syx").writeBytes(Protocol.frame(Protocol.BYE,57,revision,ByteArray(0)))
    }
}
