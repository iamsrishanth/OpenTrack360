package me.srishanth.opentrack360.core.io

import java.io.ByteArrayOutputStream
import java.io.InputStream

/** Reads no more than [limit] plus one byte, so callers can detect an oversized payload safely. */
fun InputStream.readUpToLimit(limit: Int): ByteArray {
    require(limit in 0 until Int.MAX_VALUE)
    val maxRead = limit + 1
    val buffer = ByteArray(minOf(maxRead, BUFFER_SIZE).coerceAtLeast(1))
    val output = ByteArrayOutputStream(minOf(maxRead, BUFFER_SIZE))

    while (output.size() < maxRead) {
        val requested = minOf(buffer.size, maxRead - output.size())
        val count = read(buffer, 0, requested)
        if (count < 0) break
        if (count == 0) {
            val next = read()
            if (next < 0) break
            output.write(next)
        } else {
            output.write(buffer, 0, count)
        }
    }
    return output.toByteArray()
}

private const val BUFFER_SIZE = 8 * 1024
