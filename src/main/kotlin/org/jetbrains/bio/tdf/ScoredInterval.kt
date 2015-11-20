package org.jetbrains.bio.tdf

data class ScoredInterval(val start: Int, val end: Int, val score: Float) {
    override fun toString(): String = "$score@[$start; $end)"
}