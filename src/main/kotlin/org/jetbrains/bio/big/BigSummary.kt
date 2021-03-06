package org.jetbrains.bio.big

import com.google.common.primitives.Doubles
import com.google.common.primitives.Floats
import com.google.common.primitives.Ints
import com.google.common.primitives.Longs
import org.jetbrains.bio.OrderedDataOutput
import org.jetbrains.bio.RomBuffer
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToLong

data class BigSummary(
        /** An upper bound on the number of (bases) with actual data. */
        var count: Long = 0,
        /** Minimum item value. */
        var minValue: Double = java.lang.Double.POSITIVE_INFINITY,
        /** Maximum item value. */
        var maxValue: Double = java.lang.Double.NEGATIVE_INFINITY,
        /** Sum of values for each base. */
        var sum: Double = 0.0,
        /** Sum of squares for each base. */
        var sumSquares: Double = 0.0) {

    /** Returns `true` if a summary contains no data. */
    internal fun isEmpty() = count == 0L

    internal fun update(value: Double, intersection: Int) {
        count += intersection
        sum += value * intersection
        sumSquares += value * value * intersection
        minValue = min(minValue, value)
        maxValue = max(maxValue, value)
    }

    internal fun update(zoomData: ZoomData, intersection: Int, total: Int) {
        val weight = intersection.toDouble() / total
        count += (zoomData.count * weight).roundToLong()
        sum += zoomData.sum * weight
        sumSquares += zoomData.sumSquares * weight
        minValue = min(minValue, zoomData.minValue.toDouble())
        maxValue = max(maxValue, zoomData.maxValue.toDouble())
    }

    /** Because monoids rock. */
    internal operator fun plus(other: BigSummary) = when {
        isEmpty()       -> other
        other.isEmpty() -> this
        else -> BigSummary(count + other.count,
                           min(minValue, other.minValue),
                           max(maxValue, other.maxValue),
                           sum + other.sum,
                           sumSquares + other.sumSquares)
    }

    internal fun write(output: OrderedDataOutput) = with(output) {
        writeLong(count)
        writeDouble(minValue)
        writeDouble(maxValue)
        writeDouble(sum)
        writeDouble(sumSquares)
    }

    companion object {
        internal val BYTES = Longs.BYTES + Doubles.BYTES * 4

        internal fun read(input: RomBuffer, offset: Long) = with(input) {
            position = offset

            val count = readLong()
            val minValue = readDouble()
            val maxValue = readDouble()
            val sum = readDouble()
            val sumSquares = readDouble()
            BigSummary(count, minValue, maxValue, sum, sumSquares)
        }
    }
}

data internal class ZoomLevel(val reduction: Int,
                              val dataOffset: Long,
                              val indexOffset: Long) {

    internal fun write(output: OrderedDataOutput) = with(output) {
        writeInt(reduction)
        writeInt(0)  // reserved.
        writeLong(dataOffset)
        writeLong(indexOffset)
    }

    companion object {
        internal val BYTES = Ints.BYTES * 2 + Longs.BYTES * 2

        internal fun read(input: RomBuffer) = with(input) {
            val reduction = readInt()
            val reserved = readInt()
            check(reserved == 0)
            val dataOffset = readLong()
            val indexOffset = readLong()
            ZoomLevel(reduction, dataOffset, indexOffset)
        }
    }
}

internal fun List<ZoomLevel>.pick(desiredReduction: Int): ZoomLevel? {
    require(desiredReduction >= 0) { "desired must be >=0" }
    return if (desiredReduction <= 1) {
        null
    } else {
        var acc = Int.MAX_VALUE
        var closest: ZoomLevel? = null
        for (zoomLevel in this) {
            val d = desiredReduction - zoomLevel.reduction
            if (d >= 0 && d < min(desiredReduction, acc)) {
                acc = d
                closest = zoomLevel
            }
        }

        closest
    }
}

internal data class ZoomData(
        /** Chromosome id as defined by B+ tree. */
        val chromIx: Int,
        /** 0-based start offset (inclusive). */
        val startOffset: Int,
        /** 0-based end offset (exclusive). */
        val endOffset: Int,
        /**
         * These are just inlined fields of [BigSummary] downcasted
         * to 4 bytes. Top-notch academic design! */
        val count: Int,
        val minValue: Float,
        val maxValue: Float,
        val sum: Float,
        val sumSquares: Float) {

    internal val interval: ChromosomeInterval get() = Interval(chromIx, startOffset, endOffset)

    internal fun write(output: OrderedDataOutput) = with(output) {
        writeInt(chromIx)
        writeInt(startOffset)
        writeInt(endOffset)
        writeInt(count)
        writeFloat(minValue)
        writeFloat(maxValue)
        writeFloat(sum)
        writeFloat(sumSquares)
    }

    companion object {
        internal val SIZE = Ints.BYTES * 3 + Ints.BYTES + Floats.BYTES * 4

        internal fun read(input: RomBuffer) = with(input) {
            val chromIx = readInt()
            val startOffset = readInt()
            val endOffset = readInt()
            val count = readInt()
            val minValue = readFloat()
            val maxValue = readFloat()
            val sum = readFloat()
            val sumSquares = readFloat()
            ZoomData(chromIx, startOffset, endOffset, count,
                     minValue, maxValue, sum, sumSquares)
        }
    }
}
