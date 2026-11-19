package com.groovebox.persistence

import com.groovebox.StepState
import java.io.ByteArrayInputStream
import java.io.ObjectInputStream
import java.util.Base64
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class LegacyStepStateSerializationTest {
    @Test fun legacySerializedStepMissingNoteGatesCanBeEdited() {
        // Java ObjectOutputStream fixture from com.groovebox.StepState, serialVersionUID=1L,
        // with the pre-noteGates fields and no noteGates field in its class descriptor.
        val bytes = Base64.getDecoder().decode(LEGACY_STEP)
        val loaded = ObjectInputStream(ByteArrayInputStream(bytes)).use { it.readObject() as StepState }
        val edited = loaded.copy(gate = .75f)

        assertEquals(listOf(60, 64), edited.notes)
        assertEquals(listOf(.01f, .02f), edited.noteOffsets)
        assertEquals(listOf(.6f, .7f), edited.noteVelocities)
        assertEquals(mapOf(112 to .3f), edited.parameterLocks)
        assertEquals(.75f, edited.gate)
        assertTrue(edited.noteGates.isEmpty())
    }

    private companion object {
        const val LEGACY_STEP = "rO0ABXNyABdjb20uZ3Jvb3ZlYm94LlN0ZXBTdGF0ZQAAAAAAAAABAgAMWgAGYWN0aXZlRgAEZ2F0ZVoACWlzU2tpcHBlZEYAC3Byb2JhYmlsaXR5WgAFcHVuY2hJAAdyYXRjaGV0RgANc3ViU3RlcE9mZnNldEYACHZlbG9jaXR5TAALbm90ZU9mZnNldHN0ABBMamF2YS91dGlsL0xpc3Q7TAAObm90ZVZlbG9jaXRpZXNxAH4AAUwABW5vdGVzcQB+AAFMAA5wYXJhbWV0ZXJMb2Nrc3QAD0xqYXZhL3V0aWwvTWFwO3hwAT8AAAAAP4AAAAAAAAABAAAAAD9MzM1zcgATamF2YS51dGlsLkFycmF5TGlzdHiB0h2Zx2GdAwABSQAEc2l6ZXhwAAAAAncEAAAAAnNyAA9qYXZhLmxhbmcuRmxvYXTa7cmi2zzw7AIAAUYABXZhbHVleHIAEGphdmEubGFuZy5OdW1iZXKGrJUdC5TgiwIAAHhwPCPXCnNxAH4ABjyj1wp4c3EAfgAEAAAAAncEAAAAAnNxAH4ABj8ZmZpzcQB+AAY/MzMzeHNxAH4ABAAAAAJ3BAAAAAJzcgARamF2YS5sYW5nLkludGVnZXIS4qCk94GHOAIAAUkABXZhbHVleHEAfgAHAAAAPHNxAH4ADgAAAEB4c3IAEWphdmEudXRpbC5IYXNoTWFwBQfawcMWYNEDAAJGAApsb2FkRmFjdG9ySQAJdGhyZXNob2xkeHA/QAAAAAAAAXcIAAAAAgAAAAFzcQB+AA4AAABwc3EAfgAGPpmZmng="
    }
}
