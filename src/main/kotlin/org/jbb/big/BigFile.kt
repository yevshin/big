package org.jbb.big

import com.google.common.collect.ImmutableList
import com.google.common.primitives.Ints
import com.google.common.primitives.Longs
import com.google.common.primitives.Shorts
import java.io.Closeable
import java.io.IOException
import java.nio.ByteOrder
import java.nio.file.Path
import kotlin.properties.Delegates

/**
 * A common superclass for Big files.
 */
abstract class BigFile<T> throws(IOException::class) protected constructor(path: Path) :
        Closeable, AutoCloseable {

    // XXX maybe we should make it a DataIO instead of separate
    // Input/Output classes?
    val handle: SeekableDataInput = SeekableDataInput.of(path)
    val header: Header = Header.read(handle, getHeaderMagic())

    public val chromosomes: List<String> by Delegates.lazy {
        header.bPlusTree.traverse(handle).map { it.key }.toList()
    }

    /**
     * Queries an R+-tree.
     *
     * @param name human-readable chromosome name, e.g. `"chr9"`.
     * @param startOffset 0-based start offset (inclusive).
     * @param endOffset 0-based end offset (exclusive), if 0 than the whole
     *                  chromosome is used.
     * @return a list of intervals completely contained within the query.
     * @throws IOException if the underlying [SeekableDataInput] does so.
     */
    throws(IOException::class)
    public fun query(name: String, startOffset: Int, endOffset: Int): Sequence<T> {
        val res = header.bPlusTree.find(handle, name)
        return if (res == null) {
            emptySequence()
        } else {
            val (_key, chromIx, size) = res
            queryInternal(Interval.of(
                    chromIx, startOffset, if (endOffset == 0) size else endOffset))
        }
    }

    // TODO: these can be fields.
    public abstract fun getHeaderMagic(): Int

    public fun isCompressed(): Boolean {
        // Compression was introduced in version 3 of the format. See
        // bbiFile.h in UCSC sources.
        return header.version >= 3 && header.uncompressBufSize > 0
    }

    throws(IOException::class)
    protected abstract fun queryInternal(query: ChromosomeInterval): Sequence<T>

    throws(IOException::class)
    override fun close() = handle.close()

    class Header protected constructor(public val order: ByteOrder,
                                       public val version: Short,
                                       public val unzoomedDataOffset: Long,
                                       public val fieldCount: Short,
                                       public val definedFieldCount: Short,
                                       public val asOffset: Long,
                                       public val totalSummaryOffset: Long,
                                       public val uncompressBufSize: Int,
                                       public val zoomLevels: List<ZoomLevel>,
                                       public val bPlusTree: BPlusTree,
                                       public val rTree: RTreeIndex) {
        companion object {
            throws(IOException::class)
            fun read(input: SeekableDataInput, magic: Int): Header = with(input) {
                guess(magic)

                val version = readShort()
                val zoomLevelCount = readShort().toInt()
                val chromTreeOffset = readLong()
                val unzoomedDataOffset = readLong()
                val unzoomedIndexOffset = readLong()
                val fieldCount = readShort()
                val definedFieldCount = readShort()
                val asOffset = readLong()
                val totalSummaryOffset = readLong()
                val uncompressBufSize = readInt()
                val extendedHeaderOffset = readLong()

                val zoomLevels = (0 until zoomLevelCount).asSequence()
                        .map { ZoomLevel.read(input) }.toList()

                // Skip AutoSQL string if any.
                while (asOffset > 0 && readByte() != 0.toByte()) {}

                // Skip total summary block.
                if (totalSummaryOffset > 0) {
                    Summary.read(input)
                }

                // Skip extended header. Ideally, we should issue a warning
                // if extensions are present.
                if (extendedHeaderOffset > 0) {
                    skipBytes(Shorts.BYTES)  // extensionSize.
                    val extraIndexCount = readShort().toInt()
                    skipBytes(Longs.BYTES)   // extraIndexListOffset/
                    skipBytes(48)  // reserved.

                    for (i in 0 until extraIndexCount) {
                        val type = readShort()
                        assert(type == 0.toShort())
                        val extraFieldCount = readShort()
                        skipBytes(Longs.BYTES)      // indexOffset.
                        skipBytes(extraFieldCount *
                                  (Shorts.BYTES +   // fieldId,
                                   Shorts.BYTES))   // reserved.
                    }
                }

                val bpt = BPlusTree.read(input, chromTreeOffset)
                check(bpt.header.order == order)
                val rti = RTreeIndex.read(input, unzoomedIndexOffset)
                check(rti.header.order == order)
                return Header(order, version, unzoomedDataOffset,
                              fieldCount, definedFieldCount, asOffset,
                              totalSummaryOffset, uncompressBufSize,
                              zoomLevels, bpt, rti)
            }
        }
    }
}

data class ZoomLevel(public val reductionLevel: Int,
                     public val dataOffset: Long,
                     public val indexOffset: Long) {
    companion object {
        fun read(input: SeekableDataInput) = with(input) {
            val reductionLevel = readInt()
            val reserved = readInt()
            check(reserved == 0)
            val dataOffset = readLong()
            val indexOffset = readLong()
            ZoomLevel(reductionLevel, dataOffset, indexOffset)
        }
    }
}

data class Summary(public val basesCovered: Long,
                   public val minVal: Double,
                   public val maxVal: Double,
                   public val sumData: Double,
                   public val sumSquared: Double) {
    companion object {
        fun read(input: SeekableDataInput) = with(input) {
            val basesCovered = readLong()
            val minVal = readDouble()
            val maxVal = readDouble()
            val sumData = readDouble()
            val sumSquared = readDouble()
            Summary(basesCovered, minVal, maxVal, sumData, sumSquared)
        }
    }
}