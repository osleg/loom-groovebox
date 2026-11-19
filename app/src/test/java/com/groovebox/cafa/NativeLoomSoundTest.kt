package com.groovebox.cafa

import com.groovebox.*
import org.fm1.companion.protocol.Wire6
import org.junit.Assert.*
import org.junit.Test

class NativeLoomSoundTest {
    private class Fixture(type: EngineType, track: TrackState? = null) {
        var state=GrooveboxState().let { initial -> initial.copy(tracks=initial.tracks.mapIndexed { i,t ->
            if(i==0) track ?: t.copy(engineType=type) else t }) }
        val engine=NativeLoomSurfaceTest.Engine()
        val surface=NativeLoomSurface({state},{state=it},engine,
            {state=state.copy(selectedTrackIndex=it)},{true},{state=it},{},
            storage=NativeLoomSurfaceTest.EmptyStorage)
        var serial=0
        var view=surface.snapshot()
        init { surface.capabilitiesAccepted(setOf(1,2,3,9)); view=surface.snapshot(); surface.acknowledged(Wire6.u28(view,2)); send() }
        fun send(buttons: Int=0,select: Int=0,knob: Int=0,presets: Int=0): ByteArray {
            val scan=ByteArray(24)
            Wire6.put14(scan,0,buttons)
            for(i in 0..6) Wire6.put14(scan,6+i*2,8192+when(i) {
                0 -> select; 2 -> presets; 3 -> knob; else -> 0 })
            Wire6.put28(scan,20,Wire6.u28(view,2))
            assertTrue(surface.input(serial++,scan))
            view=surface.snapshot(); surface.acknowledged(Wire6.u28(view,2))
            return view
        }
        fun edit(page: Int=15) {
            send(buttons=1 shl 4); send()
            if(page==16) send(select=1)
            assertEquals(page,view[6].toInt())
        }
        fun lfo() { send(buttons=1 shl 3); send(); assertEquals(2,view[6].toInt()) }
        fun control(index: Int) = String(view,112+36*index,12).trim()
        fun curve(): List<Int> {
            var at=256
            while(at<view.size) {
                val tag=view[at].toInt(); val size=Wire6.u14(view,at+1)
                if(tag==3) {
                    val count=Wire6.u14(view,at+3)
                    return List(count) { Wire6.u14(view,at+5+it*4+2) }
                }
                at+=3+size
            }
            return emptyList()
        }
    }
    @Test fun editBanksUseParametersOfSelectedEngineAndPreserveAssignedSoundKnobs() {
        val examples=listOf(EngineType.SUBTRACTIVE to 104,EngineType.FM to 150,
            EngineType.SAMPLER to 300,EngineType.GRANULAR to 400,
            EngineType.WAVETABLE to 450,EngineType.AUDIO_IN to 121)
        for((type,id) in examples) {
            val f=Fixture(type); f.edit(); assertFalse("$type",f.control(0).isBlank())
            f.send(knob=1)
            assertEquals("$type",id,f.engine.parameters.last().second)
            assertTrue(f.state.tracks[0].parameters.containsKey(id))
        }
        val custom=TrackState(0,engineType=EngineType.FM,
            fm1KnobBindings=listOf(Fm1KnobBinding(159,"DRIVE",0f),null,null,null))
        val f=Fixture(EngineType.FM,custom); f.edit()
        assertEquals("DRIVE",f.control(0)); f.send(knob=1)
        assertEquals(159,f.engine.parameters.last().second)
    }
    @Test fun drumEditFollowsSelectedLaneAndSecondBankEditsThatLane() {
        for((type,base) in listOf(EngineType.FM_DRUM to 200,EngineType.ANALOG_DRUM to 600)) {
            val f=Fixture(type); f.edit()
            f.send(presets=2)
            assertEquals(2,f.state.tracks[0].selectedFmDrumInstrument)
            f.send(knob=1)
            assertEquals(base+20,f.engine.parameters.last().second)
            f.send(select=1)
            assertEquals(16,f.view[6].toInt())
            assertFalse(f.control(0).isBlank())
            f.send(knob=1)
            assertEquals(base+(if(type==EngineType.FM_DRUM) 24 else 23),
                f.engine.parameters.last().second)
        }
    }
    @Test fun repeatedSoundKnobTurnsKeepDrawnTargetContextAndApplyBothDeltas() {
        val f=Fixture(EngineType.FM)
        f.edit()
        val drawn=Wire6.u28(f.view,2)
        f.send(knob=1)
        assertEquals(drawn,Wire6.u28(f.view,2))
        val first=f.engine.parameters.last().third
        f.send(knob=1)
        assertEquals(drawn,Wire6.u28(f.view,2))
        assertEquals(2,f.engine.parameters.size)
        assertTrue(f.engine.parameters.last().third>first)
    }
    @Test fun lfoPreviewTracksDepthRateSyncAndNativeShapeOrder() {
        val f=Fixture(EngineType.SUBTRACTIVE)
        f.state=f.state.copy(lfos=f.state.lfos.mapIndexed { i,l -> if(i==0) l.copy(
            rate=.5f,depth=0f,shape=2,sync=false) else l })
        f.lfo()
        assertTrue(f.curve().isNotEmpty())
        assertTrue(f.curve().all { kotlin.math.abs(it-8192)<=1 })
        f.state=f.state.copy(lfos=f.state.lfos.mapIndexed { i,l -> if(i==0) l.copy(depth=1f) else l })
        f.view=f.surface.snapshot()
        val square=f.curve()
        assertTrue(square.maxOrNull()!!>15000)
        assertTrue(square.minOrNull()!!<1000)
        f.state=f.state.copy(lfos=f.state.lfos.mapIndexed { i,l -> if(i==0) l.copy(shape=3) else l })
        f.view=f.surface.snapshot()
        val saw=f.curve()
        assertTrue(saw.take(5).zipWithNext().any { it.second>it.first })
        f.state=f.state.copy(lfos=f.state.lfos.mapIndexed { i,l -> if(i==0) l.copy(shape=0,rate=.1f) else l })
        f.view=f.surface.snapshot(); val slow=f.curve()
        f.state=f.state.copy(lfos=f.state.lfos.mapIndexed { i,l -> if(i==0) l.copy(rate=.8f) else l })
        f.view=f.surface.snapshot(); assertNotEquals(slow,f.curve())
        f.state=f.state.copy(lfos=f.state.lfos.mapIndexed { i,l -> if(i==0) l.copy(sync=true) else l })
        f.view=f.surface.snapshot(); val synced=f.curve()
        f.state=f.state.copy(tempo=200f)
        f.view=f.surface.snapshot(); assertNotEquals(synced,f.curve())
        f.state=f.state.copy(lfos=f.state.lfos.mapIndexed { i,l -> if(i==0) l.copy(shape=4) else l })
        f.view=f.surface.snapshot(); assertEquals(f.curve(),f.surface.snapshot().let { f.view=it; f.curve() })
    }
}
