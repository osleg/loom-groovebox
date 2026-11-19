/* SPDX-License-Identifier: GPL-3.0-only */
package org.fm1.companion.control

import kotlin.math.roundToInt

data class ParameterDescriptor(
    val id: String,
    val label: String,
    val fallback: Float = 0f,
    val minimum: Float = 0f,
    val maximum: Float = 1f,
    val step: Float = .01f,
    val choices: List<String> = emptyList(),
    val unit: String = "%"
) {
    init {
        require(id.isNotBlank())
        require(minimum.isFinite() && maximum.isFinite() && minimum < maximum)
        require(step.isFinite() && step > 0f)
    }

    fun value(raw: Float?): Float = (raw ?: fallback).takeIf { it.isFinite() }
        ?.coerceIn(minimum, maximum) ?: minimum

    fun normalized(raw: Float?): Int = (fraction(raw) * 16383.0).roundToInt()

    fun display(raw: Float?): String {
        val value = value(raw)
        val fraction = fraction(raw)
        return when {
            choices.isNotEmpty() -> choices[(fraction * (choices.size - 1)).roundToInt()]
            unit == "%" -> "${(fraction * 100.0).roundToInt()}%"
            else -> "$value$unit"
        }
    }

    fun adjusted(raw: Float?, ticks: Int): Float {
        val next = value(raw).toDouble() + ticks.toDouble() * step.toDouble()
        return next.coerceIn(minimum.toDouble(), maximum.toDouble()).toFloat()
    }

    private fun fraction(raw: Float?): Double =
        (value(raw).toDouble() - minimum.toDouble()) / (maximum.toDouble() - minimum.toDouble())
}
