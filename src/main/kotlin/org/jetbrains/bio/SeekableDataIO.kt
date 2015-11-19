package org.jetbrains.bio

import com.google.common.io.ByteStreams
import com.google.common.primitives.Ints
import com.google.common.primitives.Longs
import com.google.common.primitives.Shorts
import java.io.*
import java.nio.ByteOrder
import java.nio.channels.Channels
import java.nio.file.Path
import java.util.zip.Deflater
import java.util.zip.DeflaterOutputStream
import java.util.zip.Inflater
import kotlin.LazyThreadSafetyMode.NONE

/**
 * A stripped-down byte order-aware complement to [java.io.DataInputStream].
 */
internal interface OrderedDataInput {
    var order: ByteOrder

    fun readFully(b: ByteArray, off: Int = 0, len: Int = b.size)

    fun readBoolean() = readUnsignedByte() != 0

    fun readByte() = readUnsignedByte().toByte()

    fun readUnsignedByte(): Int

    fun readCString(): String {
        val sb = StringBuilder()
        do {
            val ch = readUnsignedByte()
            if (ch == 0) {
                break
            }

            sb.append(ch.toChar())
        } while (true)

        return sb.toString()
    }

    fun readShort(): Short {
        val b1 = readByte()
        val b2 = readByte()
        return if (order == ByteOrder.BIG_ENDIAN) {
            Shorts.fromBytes(b1, b2)
        } else {
            Shorts.fromBytes(b2, b1)
        }
    }

    fun readUnsignedShort(): Int {
        val b1 = readByte()
        val b2 = readByte()
        return if (order == ByteOrder.BIG_ENDIAN) {
            Ints.fromBytes(0, 0, b1, b2)
        } else {
            Ints.fromBytes(0, 0, b2, b1)
        }
    }

    fun readInt(): Int {
        val b1 = readByte()
        val b2 = readByte()
        val b3 = readByte()
        val b4 = readByte()
        return if (order == ByteOrder.BIG_ENDIAN) {
            Ints.fromBytes(b1, b2, b3, b4)
        } else {
            Ints.fromBytes(b4, b3, b2, b1)
        }
    }

    fun readLong(): Long {
        val b1 = readByte()
        val b2 = readByte()
        val b3 = readByte()
        val b4 = readByte()
        val b5 = readByte()
        val b6 = readByte()
        val b7 = readByte()
        val b8 = readByte()
        return if (order == ByteOrder.BIG_ENDIAN) {
            Longs.fromBytes(b1, b2, b3, b4, b5, b6, b7, b8)
        } else {
            Longs.fromBytes(b8, b7, b6, b5, b4, b3, b2, b1)
        }
    }

    fun readFloat() = java.lang.Float.intBitsToFloat(readInt())

    fun readDouble() = java.lang.Double.longBitsToDouble(readLong())
}

class SeekableDataInput private constructor(
        private val path: Path,
        override var order: ByteOrder)
:
        OrderedDataInput, Closeable, AutoCloseable {

    private val file = RandomAccessFile(path.toFile(), "r")

    // This is important to keep lazy, otherwise the GC will be trashed
    // by a zillion of pending finalizers.
    private val inf by lazy(NONE) { Inflater() }

    // For performance reasons we use fixed-size buffers for both
    // compressed and uncompressed inputs. Unfortunately this makes
    // the class non-thread safe.
    private var compressedBuf = ByteArray(1024)
    private var uncompressedBuf = ByteArray(4096)

    /**
     * Executes a `block` on a fixed-size possibly compressed input.
     *
     * This of this method as a way to get buffered input locally.
     * See for example [RTreeIndex.findOverlappingBlocks].
     */
    internal fun <T> with(offset: Long, size: Long,
                          compressed: Boolean = false,
                          block: CountingOrderedDataInput.() -> T): T {
        seek(offset)
        val input = if (compressed) {
            compressedBuf = compressedBuf.ensureCapacity(size.toInt())
            readFully(compressedBuf, 0, size.toInt())

            // Decompression step is (unfortunately) mandatory, since
            // we need to know the *exact* length of the data before
            // passing it to `block`.
            inf.reset()
            inf.setInput(compressedBuf, 0, size.toInt())
            var uncompressedSize = 0
            var step = size.toInt()
            while (!inf.finished()) {
                uncompressedBuf = uncompressedBuf.ensureCapacity(uncompressedSize + step)
                val actual = inf.inflate(uncompressedBuf, uncompressedSize, step)
                uncompressedSize += actual
            }

            CountingDataInput(ByteArrayInputStream(uncompressedBuf, 0, uncompressedSize),
                    uncompressedSize.toLong(), order)
        } else {
            uncompressedBuf = uncompressedBuf.ensureCapacity(size.toInt())
            readFully(uncompressedBuf, 0, size.toInt())
            CountingDataInput(ByteArrayInputStream(uncompressedBuf, 0, size.toInt()),
                    size, order)
        }
        return with(input, block)
    }

    /** Guess byte order from a given big-endian `magic`. */
    fun guess(magic: Int) {
        val b = ByteArray(4)
        readFully(b)
        val bigMagic = Ints.fromBytes(b[0], b[1], b[2], b[3])
        order = if (bigMagic != magic) {
            val littleMagic = Ints.fromBytes(b[3], b[2], b[1], b[0])
            check(littleMagic == magic) { "bad signature in $path" }
            ByteOrder.LITTLE_ENDIAN
        } else {
            ByteOrder.BIG_ENDIAN
        }
    }

    fun seek(pos: Long): Unit = file.seek(pos)

    fun tell(): Long = file.filePointer

    override fun readFully(b: ByteArray, off: Int, len: Int) {
        file.readFully(b, off, len)
    }

    override fun readUnsignedByte(): Int = file.readUnsignedByte()

    override fun close() = file.close()

    companion object {
        fun of(path: Path, order: ByteOrder = ByteOrder.nativeOrder()): SeekableDataInput {
            return SeekableDataInput(path, order)
        }
    }
}

private fun ByteArray.ensureCapacity(requested: Int): ByteArray {
    return if (size < requested) {
        copyOf((requested + requested shr 1).toInt())  // 1.5x
    } else {
        this
    }
}

internal interface CountingOrderedDataInput : OrderedDataInput {
    /**
     * Returns `true` if the input doesn't contain any more data and
     * `false` otherwise.
     * */
    val finished: Boolean
}

private class CountingDataInput(private val input: InputStream,
                                private val size: Long,
                                override var order: ByteOrder)
:
        CountingOrderedDataInput {

    private var read = 0L

    override fun readFully(b: ByteArray, off: Int, len: Int) {
        check(!finished) { "no data" }
        val available = Math.min(len, (size - read).toInt())
        ByteStreams.readFully(input, b, off, available)
        read += available
    }

    override fun readUnsignedByte(): Int {
        check(!finished) { "no data" }
        val b = input.read()
        if (b < 0) {
            throw EOFException()
        }

        read++
        return b
    }

    override val finished: Boolean get() = read >= size
}

/**
 * A stripped-down byte order-aware complement to [java.io.DataOutputStream].
 */
interface OrderedDataOutput {
    val order: ByteOrder

    fun skipBytes(count: Int) {
        assert(count >= 0) { "count must be >=0" }
        for (i in 0..count - 1) {
            writeByte(0)
        }
    }

    fun writeCString(s: String)

    fun writeCString(s: String, length: Int) {
        assert(length >= s.length + 1)
        writeCString(s)
        skipBytes(length - (s.length + 1))
    }

    fun writeBoolean(v: Boolean) = writeByte(if (v) 1 else 0)

    fun writeByte(v: Int)

    fun writeShort(v: Int) {
        if (order == ByteOrder.BIG_ENDIAN) {
            writeByte((v ushr 8) and 0xff)
            writeByte((v ushr 0) and 0xff)
        } else {
            writeByte((v ushr 0) and 0xff)
            writeByte((v ushr 8) and 0xff)
        }
    }

    fun writeInt(v: Int) {
        if (order == ByteOrder.BIG_ENDIAN) {
            writeByte((v ushr 24) and 0xff)
            writeByte((v ushr 16) and 0xff)
            writeByte((v ushr  8) and 0xff)
            writeByte((v ushr  0) and 0xff)
        } else {
            writeByte((v ushr  0) and 0xff)
            writeByte((v ushr  8) and 0xff)
            writeByte((v ushr 16) and 0xff)
            writeByte((v ushr 24) and 0xff)
        }
    }

    fun writeLong(v: Long) {
        if (order == ByteOrder.BIG_ENDIAN) {
            writeByte((v ushr 56).toInt() and 0xff)
            writeByte((v ushr 48).toInt() and 0xff)
            writeByte((v ushr 40).toInt() and 0xff)
            writeByte((v ushr 32).toInt() and 0xff)
            writeByte((v ushr 24).toInt() and 0xff)
            writeByte((v ushr 16).toInt() and 0xff)
            writeByte((v ushr  8).toInt() and 0xff)
            writeByte((v ushr  0).toInt() and 0xff)
        } else {
            writeByte((v ushr  0).toInt() and 0xff)
            writeByte((v ushr  8).toInt() and 0xff)
            writeByte((v ushr 16).toInt() and 0xff)
            writeByte((v ushr 24).toInt() and 0xff)
            writeByte((v ushr 32).toInt() and 0xff)
            writeByte((v ushr 40).toInt() and 0xff)
            writeByte((v ushr 48).toInt() and 0xff)
            writeByte((v ushr 56).toInt() and 0xff)
        }
    }

    fun writeFloat(v: Float) = writeInt(java.lang.Float.floatToIntBits(v))

    fun writeDouble(v: Double) = writeLong(java.lang.Double.doubleToLongBits(v))
}

open class CountingDataOutput(private val output: OutputStream,
                              private val offset: Long,
                              override val order: ByteOrder)
:
        OrderedDataOutput, Closeable, AutoCloseable {

    /** Total number of bytes written. */
    private var written = 0L

    // This is important to keep lazy, otherwise the GC will be trashed
    // by a zillion of pending finalizers.
    private val def by lazy(NONE) { Deflater() }

    private fun ack(size: Int) {
        written += size
    }

    /**
     * Executes a `block` (compressing the output) and returns the
     * total number of *uncompressed* bytes written.
     */
    fun with(compressed: Boolean, block: OrderedDataOutput.() -> Unit): Int {
        return if (compressed) {
            // This is slightly involved. We stack deflater on top of
            // our input stream and report the number of uncompressed
            // bytes fed into the deflater.
            def.reset()
            val inner = DeflaterOutputStream(output, def)
            with(CountingDataOutput(inner, offset, order), block)
            inner.finish()
            ack(def.bytesWritten.toInt())
            def.bytesRead
        } else {
            val snapshot = written
            with(this, block)
            written - snapshot
        }.toInt()
    }

    override fun writeCString(s: String) {
        for (ch in s) {
            output.write(ch.toInt())
        }

        output.write(0)  // null-terminated.
        ack(s.length + 1)
    }

    override fun writeByte(v: Int) {
        output.write(v)
        ack(1)
    }

    fun tell(): Long = offset + written

    override fun close() = output.close()

    companion object {
        fun of(path: Path, order: ByteOrder = ByteOrder.nativeOrder(),
               offset: Long = 0): CountingDataOutput {
            val file = RandomAccessFile(path.toFile(), "rw")
            file.seek(offset)
            val output = Channels.newOutputStream(file.channel).buffered()
            return CountingDataOutput(output, offset, order)
        }
    }
}