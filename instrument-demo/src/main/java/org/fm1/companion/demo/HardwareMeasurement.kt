/* SPDX-License-Identifier: GPL-3.0-only */
package org.fm1.companion.demo

import android.os.Build
import android.util.AtomicFile
import java.io.File
import java.util.Locale
import kotlin.math.ceil

data class ProbeStats(val keyDowns: Long, val quietRejects: Long,
                      val markersEmitted: Long, val masterGainQ12: Int)
data class PhoneTiming(val serial: Int, val renderDeltaUs: Long?, val onsetFrame: Int?)

/** The FM-1 times both endpoints on its own clock. Only completed pairs enter the distribution. */
internal class HardwareMeasurement(
    directory: File, private val midiName: String, private val audioName: String
) {
    private val file = AtomicFile(File(directory, "fm1-measurement-${System.currentTimeMillis()}.txt"))
    private val rows = ArrayList<String>()
    private val latencies = ArrayList<Int>()
    private val phoneLatencies = ArrayList<Long>()
    private val scanToEnqueue = ArrayList<Int>()
    private val enqueueToRecognition = ArrayList<Int>()
    private val recognitionToDac = ArrayList<Int>()
    private var extended = false
    private var phoneMissingReceipt = 0
    private var phoneMissingOnset = 0
    private var phoneUnpairedPolls = 0
    private var firstProbe: ProbeStats? = null
    private var currentProbe: ProbeStats? = null
    private var last = IntArray(9)
    private var missedByPolling = 0
    private var lastMissMs = -3000
    private var queue = ""
    var finished = false
        private set

    fun start() = save("INCOMPLETE — waiting for FM-1 key timing", "")
    fun elapsedMs() = last[0]
    fun progress(): String {
        val gain = currentProbe?.masterGainQ12 ?: 0
        val hint = when {
            gain < 64 -> " • raise MASTER for a clear test note"
            elapsedMs() - lastMissMs < 2100 -> " • pause for two seconds before the next tap"
            else -> ""
        }
        return "${elapsedMs() / 1000}/60 s • ${last[5]} key presses • ${last[1]} timed$hint"
    }

    fun sample(op: Int, values: IntArray, probe: ProbeStats, queueAtStart: String? = null,
               phoneTiming: ((Int) -> PhoneTiming?)? = null) {
        require((values.size == 9 || values.size == 11) && values.all { it >= 0 }) {
            "Expected the key-timing firmware"
        }
        var timing: PhoneTiming? = null
        var stages: IntArray? = null
        if (op == 1) {
            check(firstProbe == null)
            extended = values.size == 11
            firstProbe = probe
            queue = requireNotNull(queueAtStart)
        } else {
            check(firstProbe != null && (values.size == 11) == extended &&
                values[0] >= elapsedMs() && values[1] >= last[1])
            if (values[4] > last[4]) lastMissMs = values[0]
            val newTimings = values[1] - last[1]
            if (newTimings > 0) {
                latencies.add(values[2])
                missedByPolling += newTimings - 1
                phoneUnpairedPolls += newTimings - 1
                timing = phoneTiming?.invoke(values[3])
                val phoneDelta = timing?.renderDeltaUs
                when {
                    timing == null -> phoneMissingReceipt++
                    phoneDelta == null -> phoneMissingOnset++
                    phoneDelta >= 0 && phoneLatencies.size < 256 ->
                        phoneLatencies.add(phoneDelta)
                    phoneDelta < 0 -> phoneMissingOnset++
                }
                if (extended &&
                    values[9] > 0 && values[10] >= values[9] && values[2] >= values[10]) {
                    stages = intArrayOf(values[9], values[10] - values[9], values[2] - values[10])
                    if (scanToEnqueue.size < 256) {
                        scanToEnqueue.add(stages[0]); enqueueToRecognition.add(stages[1]);
                        recognitionToDac.add(stages[2])
                    }
                }
            }
        }
        currentProbe = probe
        last = values.copyOf()
        rows.add("$op\t${values.take(9).joinToString("\t")}\t${probe.keyDowns}\t${probe.quietRejects}\t${probe.markersEmitted}\t${probe.masterGainQ12}" +
            "\t${timing?.renderDeltaUs ?: ""}\t${timing?.onsetFrame ?: ""}" +
            "\t${stages?.get(0) ?: ""}\t${stages?.get(1) ?: ""}\t${stages?.get(2) ?: ""}")
    }

    fun finish(reason: String?): String {
        check(!finished)
        val a = firstProbe
        val b = currentProbe
        val summary = buildString {
            appendLine(when {
                reason != null -> "INCOMPLETE — $reason"
                elapsedMs() < 60_000 -> "INCOMPLETE — key test ended early"
                last[5] == 0 -> "60-second test ended without a physical key press"
                last[1] == 0 -> "60-second test ended without a matched note"
                else -> "60-second key-to-audio test: ${last[1]} of ${last[5]} presses timed"
            })
            if (a != null && b != null) {
                appendLine("FM-1: ${last[5]} physical key presses, ${last[6]} forwarded.")
                appendLine("Phone: ${b.keyDowns - a.keyDowns} presses received, " +
                    "${b.markersEmitted - a.markersEmitted} note onsets marked, " +
                    "${b.quietRejects - a.quietRejects} presses began muted or during a previous note.")
                appendLine("USB return: ${last[7]} markers detected, ${last[8]} missed at the DAC.")
                appendLine("Key press to digital audio output: ${distribution()}.")
                appendLine("Phone panel receipt to rendered PCM block: ${phoneDistribution()}. " +
                    "Missing receipt: $phoneMissingReceipt; missing marked onset: $phoneMissingOnset; " +
                    "additional timings between polls without a serial: $phoneUnpairedPolls.")
                if (extended) appendLine("Matched FM-1 stages: scan to input enqueue ${stageDistribution(scanToEnqueue)}; " +
                    "input enqueue to full returned cue recognition ${stageDistribution(enqueueToRecognition)}; " +
                    "full cue recognition to DAC ${stageDistribution(recognitionToDac)}. " +
                    "These use only the FM-1 clock; phone timing is separate.")
                appendLine("Unmatched presses: ${last[4]}. Additional timings between polls: $missedByPolling.")
                appendLine("Playback queue at start: $queue.")
            }
        }.trimEnd()
        save(summary, rows.joinToString("\n"))
        finished = true
        return summary
    }

    private fun distribution(): String {
        if (latencies.isEmpty()) return "no matched samples"
        val sorted = latencies.sorted()
        fun ms(us: Int) = String.format(Locale.US, "%.2f ms", us / 1000.0)
        if (sorted.size == 1) return "${ms(sorted[0])} (one sample)"
        fun rank(p: Double) = ms(sorted[ceil(p * sorted.size).toInt() - 1])
        return "median ${rank(.50)}, p95 ${rank(.95)}, max ${ms(sorted.last())} (n=${sorted.size})"
    }

    private fun phoneDistribution(): String {
        if (phoneLatencies.isEmpty()) return "no matched samples"
        val sorted = phoneLatencies.sorted()
        fun ms(us: Long) = String.format(Locale.US, "%.2f ms", us / 1000.0)
        if (sorted.size == 1) return "${ms(sorted[0])} (one sample)"
        fun rank(p: Double) = ms(sorted[ceil(p * sorted.size).toInt() - 1])
        return "median ${rank(.50)}, p95 ${rank(.95)}, max ${ms(sorted.last())} (n=${sorted.size}, capped at 256)"
    }

    private fun stageDistribution(samples: List<Int>): String {
        if (samples.isEmpty()) return "no matched samples"
        val sorted = samples.sorted()
        fun ms(us: Int) = String.format(Locale.US, "%.2f ms", us / 1000.0)
        return "median ${ms(sorted[ceil(.50 * sorted.size).toInt() - 1])}, " +
            "p95 ${ms(sorted[ceil(.95 * sorted.size).toInt() - 1])}, " +
            "max ${ms(sorted.last())} (n=${sorted.size})"
    }

    private fun save(summary: String, data: String) {
        val header = ("op fmWallMs keyTimedCount lastKeyUs lastKeyInputSerial unmatched physicalDownscans " +
            "forwardedKeyReports detectedMarkers dacMisses phoneKeyDowns quietRejects markersEmitted masterGainQ12 phoneRenderDeltaUs phoneOnsetFrame fmScanToEnqueueUs fmEnqueueToCueRecognizedUs fmCueRecognizedToDacUs").replace(' ', '\t')
        val body = "$summary\n\nPhone: ${Build.MANUFACTURER} ${Build.MODEL}, Android ${Build.VERSION.RELEASE}\nMIDI: $midiName\nAudio: $audioName, 44100 Hz stereo USB return\n\n$header\n$data\n"
        val stream = file.startWrite()
        try {
            stream.write(body.toByteArray(Charsets.UTF_8))
            file.finishWrite(stream)
        } catch (error: Exception) {
            file.failWrite(stream)
            throw error
        }
    }
    companion object {
        fun lastSummary(directory: File): String? {
            val latest = directory.listFiles()?.filter {
                it.name.startsWith("fm1-measurement-") && it.name.endsWith(".txt")
            }?.maxByOrNull { it.name } ?: return null
            return try {
                AtomicFile(latest).openRead().bufferedReader().useLines { lines ->
                    lines.takeWhile { it.isNotEmpty() }.joinToString("\n")
                }
            } catch (_: java.io.IOException) { null }
        }
    }
}
