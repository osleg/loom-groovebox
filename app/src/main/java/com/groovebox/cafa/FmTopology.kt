/* SPDX-License-Identifier: GPL-3.0-only */
package com.groovebox.cafa

/** Modulation inputs transcribed from FmEngine.h switch(mAlgorithm), cases 0..31. */
internal object FmTopology {
    private val inputEdges = arrayOf(
        intArrayOf(5,4,4,3,3,2,2,1,1,0),
        intArrayOf(5,4,4,3,2,1,3,1,1,0),
        intArrayOf(5,4,3,2,2,1,1,0,4,0),
        intArrayOf(5,4,3,2,2,1,4,1,1,0),
        intArrayOf(5,4,3,2,1,0),
        intArrayOf(5,4,3,2,1,0),
        intArrayOf(5,4,4,3,3,2,1,0),
        intArrayOf(5,4,4,3,3,2,1,0),
        intArrayOf(5,4,4,3,2,1,1,0),
        intArrayOf(5,4,4,3,2,1,1,0),
        intArrayOf(5,4,4,3,2,1,1,0),
        intArrayOf(5,4,3,2,2,1,1,0),
        intArrayOf(5,4,3,2,2,1,1,0),
        intArrayOf(5,4,4,3,3,2,1,0,2,0),
        intArrayOf(5,4,4,3,3,2,1,0,2,0),
        intArrayOf(5,4,4,3,3,2,2,1,1,0,4,0),
        intArrayOf(5,4,4,3,2,1,1,0,3,0),
        intArrayOf(5,4,4,3,3,2,2,1),
        intArrayOf(5,4,5,3,5,2,4,1),
        intArrayOf(5,4,5,3,2,1,1,0),
        intArrayOf(5,4,5,3,5,2,1,0),
        intArrayOf(5,4,5,3,5,2,5,1,5,0),
        intArrayOf(5,4,5,3,2,1,1,0),
        intArrayOf(5,4,4,3,4,2,1,0),
        intArrayOf(5,4,4,3,4,2,4,1),
        intArrayOf(5,4,4,3,4,2,1,0),
        intArrayOf(5,4,4,3,2,1,1,0),
        intArrayOf(5,4,4,3,2,1),
        intArrayOf(5,4,3,2,1,0),
        intArrayOf(5,4,3,2),
        intArrayOf(5,4),
        intArrayOf(),
    )
    fun edges(algorithm: Int): List<NativeUiView.Edge> =
        inputEdges[algorithm.coerceIn(0,31)].toList().chunked(2).map {
            NativeUiView.Edge(it[0],it[1],16383)
        }
}
