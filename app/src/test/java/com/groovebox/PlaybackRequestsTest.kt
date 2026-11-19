package com.groovebox

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PlaybackRequestsTest {
    @Test fun uiReservationSurvivesUntilServiceOwnsItAndReleasesOnce() {
        val requests = PlaybackRequests()
        val start = requests.submitted()

        assertFalse(requests.idle().first)
        assertFalse(requests.idle().second)
        assertTrue(requests.take(start))
        requests.claim(start)
        assertFalse(requests.submissionFailed(start))
        assertTrue(requests.serviceStopped())
        assertFalse(requests.serviceStopped())
    }

    @Test fun replacementKeepsPinAndRejectsStaleConnectAndStop() {
        val requests = PlaybackRequests()
        val first = requests.submitted()
        assertTrue(requests.take(first))
        requests.claim(first)

        val replacement = requests.submitted()
        assertFalse(requests.serviceStopped())
        assertFalse(requests.acceptsStop(first))
        assertTrue(requests.take(replacement))
        requests.claim(replacement)
        assertFalse(requests.acceptsStop(first))
        assertTrue(requests.acceptsStop(replacement))
        assertTrue(requests.serviceStopped())

        val stale = requests.submitted()
        val latest = requests.submitted()
        assertFalse(requests.take(stale))
        assertFalse(requests.idle().first)
        assertTrue(requests.take(latest))
        requests.claim(latest)
        assertTrue(requests.serviceStopped())
    }

    @Test fun failedSubmissionReleasesOnlyTheLastUnusedReservation() {
        val requests = PlaybackRequests()
        val first = requests.submitted()
        val second = requests.submitted()

        assertFalse(requests.submissionFailed(second))
        assertTrue(requests.submissionFailed(first))
        assertFalse(requests.submissionFailed(first))
    }
}
