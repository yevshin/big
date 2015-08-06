package org.jetbrains.bio.big

import java.io.IOException
import java.nio.file.Files
import java.nio.file.Path
import kotlin.platform.platformStatic

class BedFile(private val path: Path) : Iterable<BedEntry> {
    override fun iterator(): Iterator<BedEntry> = Files.lines(path).map { line ->
        val chunks = line.split('\t', limit = 4)
        BedEntry(chunks[0], chunks[1].toInt(), chunks[2].toInt(),
                 if (chunks.size() == 3) "" else chunks[3])
    }.iterator()

    companion object {
        throws(IOException::class)
        public platformStatic fun read(path: Path): BedFile = BedFile(path)
    }
}

/**
 * A minimal representation of a BED file entry.
 */
public data class BedEntry(
        /** Chromosome name, e.g. `"chr9"`. */
        public val chrom: String,
        /** 0-based start offset (inclusive). */
        public val start: Int,
        /** 0-based end offset (exclusive). */
        public val end: Int,
        /** Name of feature. */
        public val name: String,
        /** A number from [0, 1000] that controls shading of item. */
        public val score: Short,
        /** + or – or . for unknown. */
        public val strand: Char,
        /** Comma-separated string of additional BED values. */
        public val rest: String = "") {

    init {
        require(score >= 0 && score <= 1000)
        require(strand == '+' || strand == '-' || strand == '.')
    }

    companion object {
        public fun invoke(chrom: String, start: Int, end: Int,
                          rest: String = ""): BedEntry {
            val it = rest.split(',', limit = 4).iterator()
            return BedEntry(
                chrom, start, end,
                name = if (it.hasNext()) it.next() else "",
                score = if (it.hasNext()) it.next().toShort() else 0,
                strand = if (it.hasNext()) it.next().first() else '.',
                rest = if (it.hasNext()) it.next() else ""
            )
        }
    }
}