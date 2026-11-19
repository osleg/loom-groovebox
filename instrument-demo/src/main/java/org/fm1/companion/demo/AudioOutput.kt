/* SPDX-License-Identifier: GPL-3.0-only */
package org.fm1.companion.demo

import android.media.AudioAttributes
import android.media.AudioDeviceInfo
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioRouting
import android.media.AudioTrack
import android.os.Handler
import android.os.Looper
import android.os.Process
import android.os.SystemClock

/** Each output first proves its selected destination using silence. The session enables music after sync. */
interface InstrumentOutput {
    fun queueDescription(): String
    fun routeMatches(): Boolean
    fun enable()
    fun stop()
}

/** Starts with silent PCM and admits native Skiff audio only after Android reports the selected route. */
class AudioOutput(private val runtime: SkiffRuntime, private val device: AudioDeviceInfo,
                  private val onReady: () -> Unit, private val onFailure: (String) -> Unit,
                  private val onMidiOut: ((LongArray, Long) -> Unit)?) : InstrumentOutput {
    private val main = Handler(Looper.getMainLooper())
    private val stateLock = Any()
    private val track: AudioTrack
    private val routingListener = AudioRouting.OnRoutingChangedListener { checkRoute() }
    @Volatile private var running = true
    @Volatile private var audible = false
    @Volatile private var verified = false
    private var released = false
    private var explicitlyStopped = false
    /** Effective queue for the selected route, recorded once when the key test starts. */
    override fun queueDescription(): String = synchronized(stateLock) {
        check(verified && !released)
        val mode = when (track.performanceMode) {
            AudioTrack.PERFORMANCE_MODE_LOW_LATENCY -> "low-latency"
            AudioTrack.PERFORMANCE_MODE_POWER_SAVING -> "power-saving"
            else -> "standard"
        }
        "${track.bufferSizeInFrames} frames, $mode mode, $RENDER_FRAMES-frame writes"
    }

    init {
        val format = AudioFormat.Builder().setEncoding(AudioFormat.ENCODING_PCM_16BIT)
            .setSampleRate(44100).setChannelMask(AudioFormat.CHANNEL_OUT_STEREO).build()
        val minimum = AudioTrack.getMinBufferSize(44100,
            AudioFormat.CHANNEL_OUT_STEREO, AudioFormat.ENCODING_PCM_16BIT)
        require(minimum > 0) { "Selected output cannot play PCM audio" }
        track = AudioTrack.Builder()
            .setAudioAttributes(AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_GAME)
                .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC).build())
            .setAudioFormat(format)
            .setPerformanceMode(AudioTrack.PERFORMANCE_MODE_LOW_LATENCY)
            .setBufferSizeInBytes(maxOf(minimum, 2 * RENDER_FRAMES * 4))
            .setTransferMode(AudioTrack.MODE_STREAM).build()
        try {
            check(track.state == AudioTrack.STATE_INITIALIZED && track.sampleRate == 44100 &&
                track.channelCount == 2 && track.audioFormat == AudioFormat.ENCODING_PCM_16BIT) {
                "Android cannot open 44.1 kHz stereo PCM16 audio"
            }
            check(track.setPreferredDevice(device)) { "Android cannot select this audio output" }
            track.addOnRoutingChangedListener(routingListener, main)
            track.play()
            Thread({ pump() }, "FM1-demo-audio").start()
        } catch (error: Throwable) {
            synchronized(stateLock) {
                running = false
                released = true
            }
            runCatching { track.removeOnRoutingChangedListener(routingListener) }
            track.release()
            throw error
        }
    }

    override fun routeMatches(): Boolean = synchronized(stateLock) {
        running && verified && !released && track.routedDevice?.id == device.id
    }
    override fun enable() {
        synchronized(stateLock) {
            check(running && verified && !released && track.routedDevice?.id == device.id) {
                "Chosen audio route changed"
            }
            audible = true
        }
    }

    override fun stop() { shutdown(null, true) }

    private fun checkRoute() {
        var ready = false
        var lost = false
        synchronized(stateLock) {
            if (!running || released) return
            val route = track.routedDevice?.id
            if (verified && route != device.id) lost = true
            else if (!verified && route == device.id) {
                // AudioTrack can change capacity on rerouting. Tune the selected sink.
                track.setBufferSizeInFrames(2 * RENDER_FRAMES)
                verified = true
                ready = true
            }
        }
        if (lost) shutdown("Chosen audio output changed", false)
        if (ready) main.post {
            if (routeMatches()) onReady()
        }
    }

    private fun shutdown(message: String?, explicit: Boolean) {
        var report = false
        synchronized(stateLock) {
            if (explicit) explicitlyStopped = true
            if (!running) return
            running = false
            audible = false
            runCatching { track.removeOnRoutingChangedListener(routingListener) }
            // A blocking write never holds this lock; pause and flush discard queued PCM.
            runCatching { track.pause(); track.flush() }
            report = message != null
        }
        if (report) main.post {
            val shouldReport = synchronized(stateLock) { !explicitlyStopped }
            if (shouldReport) onFailure(message!!)
        }
    }

    private fun pump() {
        val silence = ShortArray(RENDER_FRAMES * 2)
        val music = ShortArray(silence.size)
        val stamp = android.media.AudioTimestamp()
        var submittedFrames = 0L
        var writesUntilQueueCheck = 32
        var previousUnderruns = -1
        val started = SystemClock.elapsedRealtime()
        try {
            try {
                Process.setThreadPriority(Process.THREAD_PRIORITY_AUDIO)
            } catch (_: SecurityException) {
                // Some Android builds restrict scheduling priority for application threads.
            }
            while (running) {
                checkRoute()
                if (!running) break
                if (!verified && SystemClock.elapsedRealtime() - started > 1500)
                    error("Android did not route audio to the chosen output")
                val block = synchronized(stateLock) {
                    if (running && audible && verified && !released) {
                        val packets = runtime.render(music)
                        val sendMidi = onMidiOut
                        if (sendMidi != null && packets.isNotEmpty()) {
                            // MIDI timestamps and AudioTrack's default timestamp share CLOCK_MONOTONIC.
                            val now = System.nanoTime()
                            val blockTime = if (track.getTimestamp(stamp)) {
                                stamp.nanoTime + (submittedFrames - stamp.framePosition) * 1_000_000_000L / 44100
                            } else {
                                val played = track.playbackHeadPosition.toLong() and 0xffffffffL
                                now + (submittedFrames - played).coerceAtLeast(0) * 1_000_000_000L / 44100
                            }
                            sendMidi(packets, blockTime)
                        }
                        music
                    } else silence
                }
                // Do not hold the lifecycle lock while Android blocks in write().
                val written = if (running)
                    track.write(block, 0, block.size, AudioTrack.WRITE_BLOCKING) else 0
                if (written > 0) submittedFrames += written / 2
                // Some USB mixers need an entire hardware period per write cycle.
                // Grow only if the selected route actually starves. No telemetry is collected.
                if (verified && running && --writesUntilQueueCheck == 0) {
                    val underruns = track.underrunCount
                    if (previousUnderruns >= 0 && underruns > previousUnderruns) {
                        val current = track.bufferSizeInFrames
                        val capacity = track.bufferCapacityInFrames
                        if (current < capacity)
                            track.setBufferSizeInFrames(minOf(capacity, maxOf(RENDER_FRAMES * 2, current * 2)))
                    }
                    previousUnderruns = underruns
                    writesUntilQueueCheck = 32
                }
                if (running && written != block.size) error("Audio output stopped")
            }
        } catch (error: Throwable) {
            shutdown(error.message ?: "Audio output failed", false)
        } finally {
            synchronized(stateLock) {
                running = false
                audible = false
                runCatching { track.removeOnRoutingChangedListener(routingListener) }
                runCatching { track.stop() }
                track.release()
                released = true
            }
        }
    }

    companion object {
        // One stock Skiff render half at 44.1 kHz.
        private const val RENDER_FRAMES = 128

        fun choices(manager: AudioManager, destination: AudioDestination): List<AudioDeviceInfo> =
            manager.getDevices(AudioManager.GET_DEVICES_OUTPUTS).filter {
                when (destination) {
                    AudioDestination.PHONE -> it.type == AudioDeviceInfo.TYPE_BUILTIN_SPEAKER ||
                        it.type == AudioDeviceInfo.TYPE_WIRED_HEADPHONES ||
                        it.type == AudioDeviceInfo.TYPE_WIRED_HEADSET
                    AudioDestination.FM1 -> it.type == AudioDeviceInfo.TYPE_USB_DEVICE ||
                        it.type == AudioDeviceInfo.TYPE_USB_HEADSET
                    AudioDestination.FM1_DIRECT -> false // The selected MIDI device supplies the direct endpoint.
                }
            }
    }
}
