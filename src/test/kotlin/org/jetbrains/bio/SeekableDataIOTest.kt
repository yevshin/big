package org.jetbrains.bio

import org.junit.Test
import org.junit.runner.RunWith
import org.junit.runners.Parameterized
import org.junit.runners.Parameterized.Parameters
import java.nio.ByteOrder
import java.nio.file.Files
import java.nio.file.Path
import java.util.*
import kotlin.test.assertEquals

@RunWith(Parameterized::class)
class SeekableDataIOTest(private val order: ByteOrder,
                         private val compression: CompressionType) {
    @Test fun testWriteReadIntegral() = withTempFileRandomized() { path, r ->
        val b = r.nextByte()
        val s = r.nextInt(Short.MAX_VALUE.toInt())
        val i = r.nextInt()
        val l = r.nextLong()
        OrderedDataOutput.of(path, order).use {
            it.writeByte(b.toInt())
            it.writeShort(s)
            it.writeInt(i)
            it.writeLong(l)
        }
        RomBuffer(path, order).let {
            assertEquals(b, it.get())
            assertEquals(s.toShort(), it.getShort())
            assertEquals(i, it.getInt())
            assertEquals(l, it.getLong())
        }
    }

    @Test fun testWriteReadFloating() = withTempFileRandomized() { path, r ->
        val f = r.nextFloat()
        val d = r.nextDouble()
        OrderedDataOutput.of(path, order).use {
            it.writeFloat(f)
            it.writeDouble(d)
        }
        RomBuffer(path, order).let {
            assertEquals(f, it.getFloat())
            assertEquals(d, it.getDouble())
        }
    }

    @Test fun testWriteReadChars() = withTempFileRandomized() { path, r ->
        val s = (0..r.nextInt(100)).map { (r.nextInt(64) + 32).toString() }.joinToString("")
        OrderedDataOutput.of(path, order).use {
            it.writeCString(s)
            it.writeCString(s, s.length + 8)
            it.skipBytes(16)
        }
        RomBuffer(path, order).let {
            assertEquals(s, it.getCString())
            var b = ByteArray(s.length + 8)
            it.get(b)
            assertEquals(s, String(b).trimZeros())

            for (i in 0 until 16) {
                assertEquals(0.toByte(), it.get())
            }
        }
    }

    init {
        RANDOM.setSeed(42)
    }

    @Test fun testCompression() = withTempFileRandomized { path, r ->
        val b = (0..r.nextInt(100)).map { r.nextByte() }.toByteArray()
        OrderedDataOutput.of(path, order).use {
            it.with(compression) {
                b.forEach { writeByte(it.toInt()) }
            }
        }

        RomBuffer(path, order).let {
            it.with(0, Files.size(path), compression) {
                for (i in 0 until b.size) {
                    assertEquals(b[i], get())
                }
            }
        }
    }

    private inline fun withTempFileRandomized(block: (Path, Random) -> Unit) {
        withTempFile(order.toString(), ".bb") { path ->
            val attempts = RANDOM.nextInt(100) + 1
            for (i in 0 until attempts) {
                block(path, RANDOM)
            }
        }
    }

    private fun Random.nextByte(): Byte {
        val b = nextInt(Byte.MAX_VALUE - Byte.MIN_VALUE)
        return (b + Byte.MIN_VALUE).toByte()
    }

    companion object {
        private val RANDOM = Random()

        @Parameters(name = "{0}:{1}")
        @JvmStatic fun data(): Iterable<Array<Any>> {
            return listOf(arrayOf(ByteOrder.BIG_ENDIAN, CompressionType.NO_COMPRESSION),
                          arrayOf(ByteOrder.BIG_ENDIAN, CompressionType.DEFLATE),
                          arrayOf(ByteOrder.BIG_ENDIAN, CompressionType.SNAPPY),
                          arrayOf(ByteOrder.LITTLE_ENDIAN, CompressionType.NO_COMPRESSION),
                          arrayOf(ByteOrder.LITTLE_ENDIAN, CompressionType.DEFLATE),
                          arrayOf(ByteOrder.LITTLE_ENDIAN, CompressionType.SNAPPY))
        }
    }
}
