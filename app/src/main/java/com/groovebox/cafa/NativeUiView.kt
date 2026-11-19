/* SPDX-License-Identifier: GPL-3.0-only */
package com.groovebox.cafa

import org.fm1.companion.protocol.NativeUi

/** App-owned content for one immutable Skiff native frame. */
internal class NativeUiView(
    val screen: Int, val family: Int, val title: String, val subtitle: String = "",
    val hint: String = "", val context: Int, val part: Int, val partCount: Int,
    val page: Int = 0, val pageCount: Int = 1, val flags: Int = 0,
    val variant: Int = 0, val tempo: Int = 0, val transport: Int = 0,
    val firstStep: Int = 0, val visibleSteps: Int = 0, val cursor: Int = 16383,
    val playhead: Int = 16383, val selectedRow: Int = 16383, val firstRow: Int = 0,
    val totalRows: Int = 0, val controls: List<Control> = emptyList(),
    val rows: List<Row> = emptyList(), val cells: List<Cell> = emptyList(),
    val notes: List<Note> = emptyList(), val curve: List<Point> = emptyList(),
    val markers: List<Marker> = emptyList(), val keyboard: Set<Int>? = null,
    val root: Int = 60, val lines: List<String> = emptyList(),
    val series: List<Series> = emptyList(), val edges: List<Edge> = emptyList()
) {
    data class Control(val label: String, val value: String, val normalized: Int,
                       val unit: String = "", val kind: Int = 1, val flags: Int = 1,
                       val icon: Int = 0)
    data class Row(val label: String, val value: String = "", val detail: String = "",
                   val flags: Int = 0, val a: Int = 0, val b: Int = 0, val c: Int = 0)
    data class Cell(val value: Int, val flags: Int = 0, val label: String = "")
    data class Note(val pitch: Int, val start: Int, val length: Int, val flags: Int = 0,
                    val velocity: Int = 100)
    data class Point(val x: Int, val y: Int)
    data class Marker(val position: Int, val value: Int, val flags: Int = 0,
                      val label: String = "")
    data class Series(val id: Int, val flags: Int, val points: List<Triple<Int, Int, Int>>)
    data class Edge(val from: Int, val to: Int, val strength: Int, val enabled: Boolean = true)

    fun encode(): ByteArray {
        val out = ArrayList<Byte>(256)
        repeat(256) { out.add(0) }
        fun put(at: Int, value: Int) { out[at] = value.toByte() }
        fun put14(at: Int, value: Int) {
            val n = value.coerceIn(0, 16383)
            put(at, n and 127); put(at + 1, n shr 7)
        }
        fun put28(at: Int, value: Int) {
            put14(at, value and 16383); put14(at + 2, value ushr 14)
        }
        fun text(at: Int, size: Int, value: String) {
            for (i in 0 until size) put(at + i,
                value.getOrNull(i)?.code?.takeIf { it in 32..126 } ?: 32)
        }
        put(0, 7); put(1, flags); put28(2, context)
        put(6, screen); put(7, variant); put(8, family)
        put14(9, page); put14(11, pageCount.coerceAtLeast(1))
        put14(13, part); put14(15, partCount.coerceAtLeast(1))
        put14(17, firstStep); put(19, visibleSteps)
        put14(20, cursor); put14(22, playhead); put14(24, selectedRow)
        put14(26, firstRow); put14(28, totalRows); put14(30, tempo)
        put(32, transport); text(33, 24, title); text(57, 24, subtitle)
        text(81, 31, hint)
        controls.take(4).forEachIndexed { index, control ->
            val at = 112 + 36 * index
            text(at, 12, control.label); text(at + 12, 14, control.value)
            text(at + 26, 4, control.unit); put14(at + 30, control.normalized)
            put(at + 32, control.kind); put(at + 33, control.flags)
            put(at + 34, control.icon)
        }
        for (index in controls.size.coerceAtMost(4) until 4) {
            text(112 + 36 * index, 30, "")
        }
        fun section(tag: Int, payload: List<Byte>) {
            require(payload.size <= 16383 && out.size + payload.size + 3 <= 3072)
            out.add(tag.toByte()); out.add((payload.size and 127).toByte())
            out.add((payload.size shr 7).toByte()); out.addAll(payload)
        }
        fun payload(block: MutableList<Byte>.() -> Unit): List<Byte> = mutableListOf<Byte>().apply(block)
        fun MutableList<Byte>.u(value: Int) { add(value.coerceIn(0, 127).toByte()) }
        fun MutableList<Byte>.u14(value: Int) {
            val n = value.coerceIn(0, 16383); u(n and 127); u(n shr 7)
        }
        fun MutableList<Byte>.ascii(size: Int, value: String) {
            for (i in 0 until size) u(value.getOrNull(i)?.code?.takeIf { it in 32..126 } ?: 32)
        }
        if (rows.isNotEmpty()) section(1, payload {
            require(rows.size <= 16); u(rows.size)
            rows.forEach { row ->
                ascii(16, row.label); ascii(16, row.value); ascii(8, row.detail)
                u(row.flags); u14(row.a); u14(row.b); u14(row.c); u(0)
            }
        })
        if (notes.isNotEmpty()) section(2, payload {
            require(notes.size <= 256); u14(notes.size)
            notes.forEach { note -> u(note.pitch); u14(note.start); u14(note.length.coerceAtLeast(1))
                u(note.flags); u(note.velocity) }
        })
        if (curve.isNotEmpty()) section(3, payload {
            require(curve.size <= 256); u14(curve.size)
            curve.forEach { u14(it.x); u14(it.y) }
        })
        if (markers.isNotEmpty()) section(4, payload {
            require(markers.size <= 32); u(markers.size)
            markers.forEach { u14(it.position); u14(it.value); u(it.flags); ascii(8, it.label) }
        })
        if (cells.isNotEmpty()) section(5, payload {
            require(cells.size <= 256); u14(cells.size)
            cells.forEach { u14(it.value); u(it.flags); ascii(4, it.label) }
        })
        keyboard?.let { notes -> section(6, payload {
            val bits = ByteArray(19)
            for (note in notes) if (note in 0..127)
                bits[note / 7] = (bits[note / 7].toInt() or (1 shl (note % 7))).toByte()
            addAll(bits.toList()); u(root)
        }) }
        if (lines.isNotEmpty()) section(7, payload {
            require(lines.size <= 16); u(lines.size); lines.forEach { ascii(24, it) }
        })
        if (series.isNotEmpty()) section(8, payload {
            require(series.size <= 6); u(series.size)
            series.forEach { item ->
                require(item.points.size <= 127); u(item.id); u(item.flags); u(item.points.size)
                item.points.forEach { (x,y,heat) -> u14(x); u14(y); u14(heat) }
            }
        })
        if (edges.isNotEmpty()) section(9, payload {
            require(edges.size <= 36); u(edges.size)
            edges.forEach { edge -> u(edge.from); u(edge.to); u(if(edge.enabled) 1 else 0)
                u14(edge.strength) }
        })
        return out.toByteArray().also { NativeUi.validate(it) }
    }
}
