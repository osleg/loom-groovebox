package com.groovebox.persistence

import com.groovebox.EngineType
import com.groovebox.Fm1KnobBinding
import com.groovebox.StepState
import com.groovebox.TrackState
import com.groovebox.presets.PresetCodec
import com.groovebox.presets.PresetEngine
import java.io.File
import java.nio.file.Files
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class PresetBindingsPersistenceTest {
    private lateinit var temporaryHome: File
    private var previousHome: String? = null

    @BeforeTest fun prepareHome() {
        val directory = File("build/persistence-tests").apply { mkdirs() }
        temporaryHome = Files.createTempDirectory(directory.toPath(), "bindings-").toFile()
        previousHome = System.getProperty("user.home")
        System.setProperty("user.home", temporaryHome.absolutePath)
    }

    @AfterTest fun restoreHome() {
        previousHome?.let { System.setProperty("user.home", it) } ?: System.clearProperty("user.home")
        temporaryHome.deleteRecursively()
    }

    @Test fun trackPresetRetainsFourBindingsAndStepLocks() {
        val bindings = listOf(
            Fm1KnobBinding(151, "Filter", .5f), null,
            Fm1KnobBinding(157, "Bright", .3f), null
        )
        val stepBindings = listOf(null, bindings[0], bindings[0], null)
        val track = TrackState(id = 2, engineType = EngineType.FM,
            fm1KnobBindings = bindings,
            fm1StepKnobBindings = stepBindings,
            steps = List(64) { index -> if (index == 0)
                StepState(active = true, parameterLocks = mapOf(151 to .7f)) else StepState() })

        PersistenceManager.saveTrackPreset(track, "bound")
        val loaded = PersistenceManager.loadTrackPreset(EngineType.FM, "bound")

        assertEquals(bindings, loaded?.fm1KnobBindings)
        assertEquals(stepBindings, loaded?.fm1StepKnobBindings)
        assertEquals(mapOf(151 to .7f), loaded?.steps?.first()?.parameterLocks)
    }

    @Test fun portablePresetRetainsSlotsAndOldFormatDefaultsToLegacyBanks() {
        val bindings = listOf(
            Fm1KnobBinding(1, "Cutoff", .8f), null,
            Fm1KnobBinding(113, "Res", .2f), null
        )
        val stepBindings = listOf(null, bindings[2], bindings[2], null)
        val track = TrackState(id = 0, fm1KnobBindings = bindings, fm1StepKnobBindings = stepBindings)
        val file = File(temporaryHome, "sound.loom")

        assertTrue(PresetCodec.save(file, PresetCodec.fromTrack(track, 0, PresetEngine.SUBTRACTIVE, "Sound")))
        val loaded = PresetCodec.load(file)
        assertEquals(bindings, loaded?.fm1KnobBindings?.map { it?.toTrackBinding() })
        assertEquals(stepBindings, loaded?.fm1StepKnobBindings?.map { it?.toTrackBinding() })

        file.writeText("""{"schema":1,"name":"Old","engine":"subtractive","parameters":{"1":0.5}}""")
        assertNull(PresetCodec.load(file)?.fm1KnobBindings)
        assertNull(PresetCodec.load(file)?.fm1StepKnobBindings)
    }
}
