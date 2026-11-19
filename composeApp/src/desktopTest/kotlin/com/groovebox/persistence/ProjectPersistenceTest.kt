package com.groovebox.persistence

import com.groovebox.GrooveboxState
import com.groovebox.Fm1KnobBinding
import com.groovebox.StepState
import com.groovebox.utils.sanitizeGrooveboxState
import java.io.File
import java.nio.file.Files
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ProjectPersistenceTest {
    private lateinit var temporaryHome: File
    private var previousHome: String? = null

    @BeforeTest fun prepareHome() {
        val directory = File("build/persistence-tests").apply { mkdirs() }
        temporaryHome = Files.createTempDirectory(directory.toPath(), "session-").toFile()
        previousHome = System.getProperty("user.home")
        System.setProperty("user.home", temporaryHome.absolutePath)
    }

    @AfterTest fun restoreHome() {
        previousHome?.let { System.setProperty("user.home", it) } ?: System.clearProperty("user.home")
        temporaryHome.deleteRecursively()
    }

    @Test fun successfulSaveReportsSuccessAndRestoresEditedNotes() {
        val original = GrooveboxState()
        val chord = StepState(active = true, notes = listOf(60, 64, 67), velocity = .6f,
            noteVelocities = listOf(.4f, .6f, .8f), noteOffsets = listOf(.01f, .02f, .03f),
            noteGates = listOf(.25f, .5f, .75f),
            parameterLocks = mapOf(112 to .3f))
        val soundBindings = listOf(Fm1KnobBinding(1, "Cutoff", .5f), null, null, null)
        val stepBindings = listOf(null, Fm1KnobBinding(112, "Res", .2f), null, Fm1KnobBinding(112, "Res", .2f))
        val state = original.copy(tempo = 137f, tracks = original.tracks.mapIndexed { index, track ->
            if (index == 0) track.copy(fm1KnobBindings = soundBindings, fm1StepKnobBindings = stepBindings,
                steps = track.steps.mapIndexed { step, value -> if (step == 0) chord else value }) else track
        })

        assertTrue(PersistenceManager.saveProject(state, "last_session"))
        val restored = PersistenceManager.loadProject("last_session.gbx")
        assertEquals(137f, restored?.tempo)
        assertEquals(chord, restored?.tracks?.get(0)?.steps?.get(0))
        assertEquals(soundBindings, restored?.tracks?.get(0)?.fm1KnobBindings)
        assertEquals(stepBindings, restored?.tracks?.get(0)?.fm1StepKnobBindings)
        assertTrue(restored?.tracks?.get(0)?.steps?.get(1)?.noteGates?.isEmpty() == true)
    }

    @Test fun unavailableDestinationReportsFailure() {
        File(PersistenceManager.getLoomFolder(), "Projects/last_session.gbx").mkdirs()
        assertFalse(PersistenceManager.saveProject(GrooveboxState(), "last_session.gbx"))
    }

    @Test fun silentMixSurvivesProjectRestoreAndSanitization() {
        val original = GrooveboxState()
        val edited = original.copy(tracks = original.tracks.mapIndexed { index, track ->
            if (index == 0) track.copy(volume = 0f, pan = .2f, isMuted = true, isSoloed = true,
                steps = track.steps.mapIndexed { step, value ->
                    if (step == 0) value.copy(active = true, notes = listOf(60, 64), parameterLocks = mapOf(112 to .3f)) else value
                }) else track
        })
        assertTrue(PersistenceManager.saveProject(edited, "silent_mix"))
        val restored = sanitizeGrooveboxState(PersistenceManager.loadProject("silent_mix.gbx")!!)
        assertEquals(0f, restored.tracks[0].volume)
        assertEquals(0f, restored.tracks[0].parameters[0])
        assertEquals(.2f, restored.tracks[0].pan)
        assertTrue(restored.tracks[0].isMuted)
        assertTrue(restored.tracks[0].isSoloed)
        assertEquals(edited.tracks[0].steps[0], restored.tracks[0].steps[0])
    }
}
