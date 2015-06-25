package org.jbb.big

import org.junit.Test
import java.nio.file.Files
import java.util.Random
import java.util.stream.Collectors
import kotlin.properties.Delegates
import kotlin.test.assertEquals
import kotlin.test.assertTrue

public class BigBedFileTest {
    Test fun testQuerySmall() {
        val items = exampleItems
        exampleFile.use { bbf ->
            for (i in 0..99) {
                testQuery(bbf, items[RANDOM.nextInt(items.size())])
            }
        }
    }

    Test fun testQueryLarge() {
        val items = exampleItems
        exampleFile.use { bbf ->
            for (i in 0..9) {
                val a = items[RANDOM.nextInt(items.size())]
                val b = items[RANDOM.nextInt(items.size())]
                testQuery(bbf, BedData(a.name, Math.min(a.start, b.start),
                                       Math.max(a.end, b.end)))
            }
        }
    }

    private fun testQuery(bbf: BigBedFile, query: BedData) {
        val actual = bbf.query(query.name, query.start, query.end)
        for (item in actual) {
            assertTrue(item.start >= query.start && item.end <= query.end)
        }

        val expected = exampleItems.asSequence()
                .filter { it.start >= query.start && it.end <= query.end }
                .toList()

        assertEquals(expected.size(), actual.size());
        assertEquals(expected, actual, message = query.toString())
    }

    private val exampleFile: BigBedFile get() {
        return BigBedFile.read(Examples.get("example1.bb"))
    }

    private val exampleItems: List<BedData> by Delegates.lazy {
        BedFile.read(Examples.get("example1.bed")).toList()
    }

    companion object {
        private val RANDOM = Random()
    }
}
