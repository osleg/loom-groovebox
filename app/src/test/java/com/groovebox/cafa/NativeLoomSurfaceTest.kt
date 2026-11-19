/* SPDX-License-Identifier: GPL-3.0-only */
package com.groovebox.cafa

import com.groovebox.*
import org.fm1.companion.protocol.Wire6
import org.junit.Assert.*
import org.junit.Test

class NativeLoomSurfaceTest {
    internal object EmptyStorage : NativeLoomStorage {
        override fun projects() = emptyList<String>()
        override fun loadProject(name: String): GrooveboxState? = null
        override fun saveProject(state: GrooveboxState, name: String) = false
        override fun deleteProject(name: String) = false
        override fun renameProject(from: String, to: String) = false
        override fun presets(engine: EngineType) = emptyList<String>()
        override fun loadPreset(engine: EngineType, name: String): TrackState? = null
        override fun savePreset(track: TrackState, name: String) {}
        override fun deletePreset(engine: EngineType, name: String) = false
        override fun renamePreset(engine: EngineType, from: String, to: String) = false
        override fun sequences() = emptyList<String>()
        override fun loadSequence(track: TrackState, name: String): TrackState? = null
        override fun saveSequence(track: TrackState, name: String) {}
        override fun deleteSequence(name: String) = false
        override fun renameSequence(from: String, to: String) = false
    }
    internal open class Engine : CafaSurface.Engine {
        val on = mutableListOf<Pair<Int,Int>>()
        val off = mutableListOf<Pair<Int,Int>>()
        val volumes = mutableListOf<Pair<Int,Float>>()
        val parameters = mutableListOf<Triple<Int,Int,Float>>()
        val masterVolumes = mutableListOf<Float>()
        override fun noteOn(track: Int, note: Int) { on += track to note }
        override fun noteOff(track: Int, note: Int) { off += track to note }
        override fun playing(value: Boolean) {}
        override fun recording(value: Boolean) {}
        override fun parameter(track: Int, id: Int, value: Float) { parameters += Triple(track,id,value) }
        override fun volume(track: Int, value: Float) { volumes += track to value }
        override fun pan(track: Int, value: Float) {}
        override fun mute(track: Int, value: Boolean) {}
        override fun solo(track: Int, value: Boolean) {}
        override fun tempo(value: Float) {}
        override fun length(track: Int, value: Int) {}
        override fun step(track: Int, index: Int, value: StepState) {}
        override fun playhead(track: Int, lane: Int) = 0
        override fun lfo(index: Int, value: LfoState) {}
        override fun arp(track: Int, value: ArpConfig) {}
        override fun fxEnabled(index: Int, enabled: Boolean) {}
        override fun swing(value: Float) {}
        override fun humanize(track: Int, value: Float) {}
        override fun lane(track: Int, value: Int) {}
        override fun masterVolume(value: Float) { masterVolumes += value }
    }
    @Test fun oldDrawnContextCannotEditNewTrackAfterTouchscreenSwitch() {
        var state=GrooveboxState()
        val engine=Engine()
        val surface=NativeLoomSurface({state},{state=it},engine,
            {state=state.copy(selectedTrackIndex=it)},{true},{state=it},{})
        surface.capabilitiesAccepted(setOf(1,2,3,9))
        val drawn=Wire6.u28(surface.snapshot(),2)
        surface.acknowledged(drawn)
        fun scan(serial: Int, keys: Int = 0, knob: Int = 0): Boolean {
            val bytes=ByteArray(24)
            Wire6.put14(bytes,0,0)
            Wire6.put28(bytes,2,keys)
            for (i in 0..6) Wire6.put14(bytes,6+i*2,8192+if(i==3) knob else 0)
            Wire6.put28(bytes,20,drawn)
            return surface.input(serial,bytes)
        }
        assertTrue(scan(0))
        assertTrue(scan(1,keys=1))
        assertEquals(listOf(0 to 53),engine.on)
        state=state.copy(selectedTrackIndex=1)
        val original=state.tracks[1].volume
        assertTrue(scan(2,keys=0,knob=30))
        assertEquals(original,state.tracks[1].volume,0.0001f)
        assertTrue(engine.volumes.isEmpty())
        assertEquals(listOf(0 to 53),engine.off)
    }
    @Test fun selectNavigationAndKnobTurnInOneScanCannotEditUndrawnPage() {
        var state=GrooveboxState()
        val engine=Engine()
        val surface=NativeLoomSurface({state},{state=it},engine,
            {state=state.copy(selectedTrackIndex=it)},{true},{state=it},{})
        surface.capabilitiesAccepted(setOf(1,2,3,9))
        var drawn=Wire6.u28(surface.snapshot(),2)
        surface.acknowledged(drawn)
        fun scan(serial: Int, buttons: Int = 0, select: Int = 0, k2: Int = 0) {
            val bytes=ByteArray(24)
            Wire6.put14(bytes,0,buttons)
            for(i in 0..6) Wire6.put14(bytes,6+i*2,8192+when(i) {
                0 -> select; 4 -> k2; else -> 0 })
            Wire6.put28(bytes,20,drawn)
            assertTrue(surface.input(serial,bytes))
        }
        scan(0)
        scan(1,buttons=1)
        drawn=Wire6.u28(surface.snapshot(),2)
        surface.acknowledged(drawn)
        scan(2)
        scan(3,select=1,k2=30)
        assertEquals(0f,state.tracks[0].fxSends[0],0.0001f)
        assertTrue(engine.parameters.isEmpty())
    }
    @Test fun physicalFamilyButtonsPublishTheirNativeScreensAndTransportActsWithoutPlayPage() {
        var state=GrooveboxState()
        val engine=Engine()
        val surface=NativeLoomSurface({state},{state=it},engine,
            {state=state.copy(selectedTrackIndex=it)},{true},{state=it},{})
        surface.capabilitiesAccepted(setOf(1,2,3,9))
        var serial=0
        var view=surface.snapshot()
        var drawn=Wire6.u28(view,2)
        surface.acknowledged(drawn)
        fun send(buttons: Int) {
            val bytes=ByteArray(24)
            Wire6.put14(bytes,0,buttons)
            for(i in 0..6) Wire6.put14(bytes,6+i*2,8192)
            Wire6.put28(bytes,20,drawn)
            assertTrue(surface.input(serial++,bytes))
            view=surface.snapshot()
            drawn=Wire6.u28(view,2)
            surface.acknowledged(drawn)
        }
        send(0)
        for ((button,screen) in listOf(0 to 5,1 to 12,2 to 0,3 to 2,
            4 to 15,5 to 31,6 to 47,8 to 40,9 to 42)) {
            send(1 shl button)
            if(button!=6) assertEquals("button $button",screen,view[6].toInt())
            assertEquals(7,view[0].toInt())
            val expectedTab = when(button) { 0 -> 3; 1,2,4 -> 1; 3 -> 4; 9 -> 2; else -> 0 }
            assertEquals("phone tab for button $button",expectedTab,state.selectedTab)
            send(0)
            if(button==6) assertEquals(screen,view[6].toInt())
        }
        send(1 shl 10)
        assertTrue(state.isPlaying)
        assertEquals(42,view[6].toInt())
        send(0)
        send(1 shl 11)
        assertFalse(state.isRecording)
        send(0)
        assertTrue(state.isRecording)
    }
    @Test fun soundingTrackDoesNotRetireKnobsWhilePlaying() {
        var state=GrooveboxState().copy(isPlaying=true)
        val engine=Engine()
        val surface=NativeLoomSurface({state},{state=it},engine,{},{true},{state=it},{},storage=EmptyStorage)
        surface.capabilitiesAccepted(setOf(1,2,3,9))
        var drawn=Wire6.u28(surface.snapshot(),2)
        surface.acknowledged(drawn)
        fun scan(serial: Int,buttons: Int,k1: Int=0) {
            val b=ByteArray(24)
            Wire6.put14(b,0,buttons)
            for(i in 0..6)Wire6.put14(b,6+2*i,8192+if(i==3)k1 else 0)
            Wire6.put28(b,20,drawn);assertTrue(surface.input(serial,b))
        }
        scan(0,0);scan(1,1 shl 2)
        drawn=Wire6.u28(surface.snapshot(),2);surface.acknowledged(drawn);scan(2,0)
        state=state.copy(tracks=state.tracks.mapIndexed { i,t -> if(i==0)t.copy(isAudiblyActive=true) else t })
        scan(3,0,1);scan(4,0,1)
        assertEquals("both relative turns apply to the displayed envelope while notes sound",2,engine.parameters.size)
    }
    @Test fun holdingRecPublishesValidSettingsFromTheDrumGridAndReleaseReturnsToNotes() {
        var state=GrooveboxState()
        state=state.copy(tracks=state.tracks.mapIndexed { i,t -> if(i==0)t.copy(engineType=EngineType.FM_DRUM) else t })
        var now=1_000_000_000L
        val surface=NativeLoomSurface({state},{state=it},Engine(),{},{true},{state=it},{},
            storage=EmptyStorage,nowNanos={now})
        surface.capabilitiesAccepted(setOf(1,2,3,9,11))
        var serial=0
        fun send(buttons: Int) {
            val context=Wire6.u28(surface.snapshot(),2)
            surface.acknowledged(context)
            val bytes=ByteArray(24)
            Wire6.put14(bytes,0,buttons)
            for(i in 0..6) Wire6.put14(bytes,6+i*2,8192)
            Wire6.put28(bytes,20,context)
            assertTrue(surface.input(serial++,bytes))
        }
        send(0); send(1 shl 9); send(0); send(1 shl 11)
        now+=2_000_000_000L
        val held=surface.snapshot()
        assertEquals(54,held[6].toInt())
        assertEquals(11,held[8].toInt())
        send(0)
        assertEquals(42,surface.snapshot()[6].toInt())
        assertFalse(state.isRecording)
    }
    @Test fun everyNativePagePublishesBoundedValidAppContentForEveryEngine() {
        val families=listOf(
            0 to (5..11).toList(), 1 to (12..14).toList(), 2 to (0..1).toList(),
            3 to (2..4).toList(), 4 to (15..30).toList(), 5 to (31..35).toList(),
            8 to (40..41).toList(), 9 to (42..46).toList())
        for(engineType in EngineType.values()) {
            var state=GrooveboxState().let { initial ->
                initial.copy(tracks=initial.tracks.mapIndexed { i,t ->
                    if(i==0) t.copy(engineType=engineType) else t }) }
            val surface=NativeLoomSurface({state},{state=it},Engine(),
                {state=state.copy(selectedTrackIndex=it)},{true},{state=it},{},
                storage=EmptyStorage)
            surface.capabilitiesAccepted(setOf(1,2,3,9))
            var view=surface.snapshot()
            var drawn=Wire6.u28(view,2)
            surface.acknowledged(drawn)
            var serial=0
            fun send(buttons: Int=0, select: Int=0) {
                val scan=ByteArray(24)
                Wire6.put14(scan,0,buttons)
                for(i in 0..6) Wire6.put14(scan,6+i*2,8192+if(i==0) select else 0)
                Wire6.put28(scan,20,drawn)
                assertTrue(surface.input(serial++,scan))
                view=surface.snapshot()
                assertTrue(view.size<=3072)
                drawn=Wire6.u28(view,2)
                surface.acknowledged(drawn)
            }
            send()
            for((button,expected) in families) {
                send(buttons=1 shl button)
                assertEquals("${engineType.name} button $button",expected.first(),view[6].toInt())
                send()
                for(id in expected.drop(1)) {
                    send(select=1)
                    assertEquals("${engineType.name} page $id",id,view[6].toInt())
                }
            }
            send(buttons=1 shl 7); send()
            assertEquals(37,view[6].toInt())
            send(select=-1); assertEquals(36,view[6].toInt())
            for(id in 37..39) { send(select=1); assertEquals(id,view[6].toInt()) }
            send(buttons=1 shl 6); send()
            assertEquals(47,view[6].toInt())
            send(select=1); assertEquals(48,view[6].toInt())
        }
    }
    @Test fun nativeNameAndConfirmUseKeysAndOctaveButtonsToRenameThenDeleteProject() {
        var state=GrooveboxState()
        val names=mutableListOf("OLD.gbx")
        var renamed=""
        var deleted=""
        val store=object : NativeLoomStorage by EmptyStorage {
            override fun projects() = names.toList()
            override fun renameProject(from: String, to: String): Boolean {
                renamed="$from:$to"; names.clear(); names += "$to.gbx"; return true
            }
            override fun deleteProject(name: String): Boolean {
                deleted=name; names.remove(name); return true
            }
        }
        val surface=NativeLoomSurface({state},{state=it},Engine(),
            {state=state.copy(selectedTrackIndex=it)},{true},{state=it},{},storage=store)
        surface.capabilitiesAccepted(setOf(1,2,3,9))
        var view=surface.snapshot()
        var drawn=Wire6.u28(view,2)
        surface.acknowledged(drawn)
        var serial=0
        fun send(buttons: Int=0,keys: Int=0,select: Int=0,k3: Int=0) {
            val scan=ByteArray(24)
            Wire6.put14(scan,0,buttons); Wire6.put28(scan,2,keys)
            for(i in 0..6) Wire6.put14(scan,6+i*2,8192+when(i) {
                0 -> select; 5 -> k3; else -> 0 })
            Wire6.put28(scan,20,drawn)
            assertTrue(surface.input(serial++,scan))
            view=surface.snapshot(); drawn=Wire6.u28(view,2); surface.acknowledged(drawn)
        }
        send(); send(buttons=1 shl 7); send()
        assertEquals(37,view[6].toInt())
        send(select=2); assertEquals(39,view[6].toInt())
        send(k3=-1); assertEquals(64,view[6].toInt())
        send(keys=1); send(); send(keys=1); send()
        send(buttons=1 shl 13)
        assertEquals("OLD.gbx:OLDB",renamed)
        send()
        send(k3=1); assertEquals(63,view[6].toInt())
        send(buttons=1 shl 12); assertEquals("",deleted)
        send(); send(k3=1); assertEquals(63,view[6].toInt())
        send(buttons=1 shl 13); assertEquals("OLDB.gbx",deleted)
    }
    @Test fun stoppedRecArmsNativeStepEntryAndHomeShiftExtendsWithoutLeavingNotes() {
        var state=GrooveboxState()
        val engine=Engine()
        val surface=NativeLoomSurface({state},{state=it},engine,
            {state=state.copy(selectedTrackIndex=it)},{true},{state=it},{})
        surface.capabilitiesAccepted(setOf(1,2,3,9))
        var serial=0; var view=surface.snapshot(); var drawn=Wire6.u28(view,2)
        surface.acknowledged(drawn)
        fun send(buttons: Int=0,keys: Int=0,presets: Int=0) {
            val scan=ByteArray(24)
            Wire6.put14(scan,0,buttons); Wire6.put28(scan,2,keys)
            for(i in 0..6) Wire6.put14(scan,6+i*2,8192+if(i==2) presets else 0)
            Wire6.put28(scan,20,drawn)
            assertTrue(surface.input(serial++,scan))
            view=surface.snapshot(); drawn=Wire6.u28(view,2); surface.acknowledged(drawn)
        }
        send(); send(buttons=1 shl 9); send()
        assertEquals(42,view[6].toInt())
        send(buttons=1 shl 11)
        send()
        assertEquals(4,view[32].toInt() and 4)
        assertFalse(state.isRecording)
        send(keys=1)
        assertTrue(state.tracks[0].steps[0].active)
        send()
        send(buttons=1 shl 6)
        send(buttons=1 shl 6,presets=1)
        // Step entry advanced the cursor to 1; SHIFT + PRESETS extends it to 2.
        assertEquals(2,Wire6.u14(view,20))
        send()
        assertEquals(42,view[6].toInt())
    }
    @Test fun playStopsEvenWhenDeviceStillReportsOldScreenContext() {
        var state=GrooveboxState().copy(isPlaying=true)
        val engine=Engine()
        val surface=NativeLoomSurface({state},{state=it},engine,
            {state=state.copy(selectedTrackIndex=it)},{true},{state=it},{})
        surface.capabilitiesAccepted(setOf(1,2,3,9))
        val drawn=Wire6.u28(surface.snapshot(),2)
        surface.acknowledged(drawn)
        fun scan(serial: Int,buttons: Int) {
            val bytes=ByteArray(24); Wire6.put14(bytes,0,buttons)
            for(i in 0..6) Wire6.put14(bytes,6+i*2,8192)
            Wire6.put28(bytes,20,drawn)
            assertTrue(surface.input(serial,bytes))
        }
        scan(0,0)
        state=state.copy(selectedTrackIndex=1)
        scan(1,1 shl 10)
        assertFalse(state.isPlaying)
    }
    @Test fun globalMasterKnobChangesAudioEngineAndModelTogether() {
        var state=GrooveboxState()
        val engine=Engine()
        val surface=NativeLoomSurface({state},{state=it},engine,
            {state=state.copy(selectedTrackIndex=it)},{true},{state=it},{})
        surface.capabilitiesAccepted(setOf(1,2,3,9))
        var serial=0; var view=surface.snapshot(); var drawn=Wire6.u28(view,2)
        surface.acknowledged(drawn)
        fun send(buttons: Int=0,select: Int=0,k1: Int=0) {
            val scan=ByteArray(24)
            Wire6.put14(scan,0,buttons)
            for(i in 0..6) Wire6.put14(scan,6+i*2,8192+when(i) {
                0 -> select; 3 -> k1; else -> 0 })
            Wire6.put28(scan,20,drawn)
            assertTrue(surface.input(serial++,scan))
            view=surface.snapshot(); drawn=Wire6.u28(view,2); surface.acknowledged(drawn)
        }
        send(); send(buttons=1 shl 5); send()
        send(select=4)
        assertEquals(35,view[6].toInt())
        send(k1=-10)
        assertEquals(.7f,state.masterVolume,.0001f)
        assertEquals(listOf(.7f),engine.masterVolumes)
    }
    @Test fun delayedDisplayAckCannotAuthorizeEditAfterPhoneSwitchesTarget() {
        var state=GrooveboxState()
        val engine=Engine()
        val surface=NativeLoomSurface({state},{state=it},engine,
            {state=state.copy(selectedTrackIndex=it)},{true},{state=it},{})
        surface.capabilitiesAccepted(setOf(1,2,3,9))
        val old=Wire6.u28(surface.snapshot(),2)
        surface.acknowledged(old)
        fun scan(serial: Int,context: Int,buttons: Int=0,k1: Int=0) {
            val bytes=ByteArray(24)
            Wire6.put14(bytes,0,buttons)
            for(i in 0..6) Wire6.put14(bytes,6+i*2,8192+if(i==3) k1 else 0)
            Wire6.put28(bytes,20,context)
            assertTrue(surface.input(serial,bytes))
        }
        scan(0,old)
        scan(1,old,buttons=1 shl 5)
        val pending=Wire6.u28(surface.snapshot(),2)
        state=state.copy(selectedTrackIndex=1)
        scan(2,old)
        surface.acknowledged(pending)
        scan(3,pending,k1=30)
        assertEquals(.25f,state.tracks[1].volume,.0001f)
        assertTrue(engine.volumes.isEmpty())
    }

    @Test fun rapidDrumKeysSurviveRecordedStepReadbackButPhoneTargetChangesRetireThem() {
        var state=GrooveboxState().let { initial -> initial.copy(isRecording=true,isPlaying=true,
            tracks=initial.tracks.mapIndexed { i,t -> if(i==0) t.copy(engineType=EngineType.FM_DRUM) else t }) }
        val engine=Engine()
        val surface=NativeLoomSurface({state},{state=it},engine,
            {state=state.copy(selectedTrackIndex=it)},{true},{state=it},{})
        surface.capabilitiesAccepted(setOf(1,2,3,9))
        var serial=0
        var drawn=Wire6.u28(surface.snapshot(),2)
        surface.acknowledged(drawn)
        fun send(keys: Int=0, buttons: Int=0) {
            val scan=ByteArray(24)
            Wire6.put14(scan,0,buttons)
            Wire6.put28(scan,2,keys)
            for(i in 0..6) Wire6.put14(scan,6+i*2,8192)
            Wire6.put28(scan,20,drawn)
            assertTrue(surface.input(serial++,scan))
        }
        send(); send(buttons=1 shl 9)
        drawn=Wire6.u28(surface.snapshot(),2); surface.acknowledged(drawn)
        send()
        drawn=Wire6.u28(surface.snapshot(),2); surface.acknowledged(drawn)
        assertEquals(42,surface.snapshot()[6].toInt())

        send(keys=1 shl 1) // Black key: lane 0 and its audition.
        state=state.copy(tracks=state.tracks.mapIndexed { i,t -> if(i==0)
            t.copy(drumSteps=t.drumSteps.mapIndexed { lane,steps -> if(lane==0)
                steps.mapIndexed { step,value -> if(step==0) value.copy(active=true,notes=listOf(60)) else value }
                else steps }) else t })
        surface.snapshot() // Native readback arrived; display has not acknowledged it.
        send(keys=(1 shl 1) or (1 shl 3)) // Next black key selects lane 1.
        assertEquals(listOf(0 to 60,0 to 61),engine.on)
        assertTrue(engine.off.isEmpty())

        state=state.copy(selectedTrackIndex=1) // Unrelated phone target must still retire old scans.
        send(keys=(1 shl 3) or (1 shl 5))
        assertEquals(2,engine.on.size)
        assertTrue(engine.off.contains(0 to 60) && engine.off.contains(0 to 61))

        state=state.copy(selectedTrackIndex=0,isRecording=false,isPlaying=true,
            tracks=state.tracks.mapIndexed { i,t -> if(i==0)
                t.copy(selectedFmDrumInstrument=0) else t })
        drawn=Wire6.u28(surface.snapshot(),2); surface.acknowledged(drawn)
        send(keys=0)
        drawn=Wire6.u28(surface.snapshot(),2); surface.acknowledged(drawn)
        send(keys=1 shl 2) // White key toggles a step during playback with REC off.
        send(keys=0) // Release before the new display is acknowledged.
        assertTrue(state.tracks[0].drumSteps[0][1].active)
    }
}
