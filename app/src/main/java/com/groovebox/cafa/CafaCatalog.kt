/* SPDX-License-Identifier: GPL-3.0-only */
package com.groovebox.cafa

import com.groovebox.*
import org.fm1.companion.control.ParameterDescriptor

/** Phone-side registry. IDs, ranges, formatting and backend adapters never enter firmware. */
class CafaCatalog {
    data class Control(val id: Int, val label: String, val fallback: Float = 0f,
        val minimum: Float = 0f, val maximum: Float = 1f, val step: Float = .01f,
        val choices: List<String> = emptyList(), val unit: String = "%") {
        val descriptor = ParameterDescriptor(id.toString(), label, fallback, minimum, maximum, step, choices, unit)
    }
    data class Entry(val id: String, val name: String, val backend: EngineType,
        val controls: (GrooveboxState, TrackState) -> List<Control>,
        /** Optional sound/plugin activation, called after the existing backend is selected. */
        val activate: (Int) -> Unit = {})
    private val entries = linkedMapOf<String, Entry>()
    var generation = 1; private set
    fun register(entry: Entry) {
        require(entry.id.isNotBlank() && entry.name.isNotBlank())
        require(entries.containsKey(entry.id) || entries.size < 16383)
        entries[entry.id] = entry; generation = (generation + 1) and 16383
    }
    fun list(): List<Entry> = entries.values.toList()
}
