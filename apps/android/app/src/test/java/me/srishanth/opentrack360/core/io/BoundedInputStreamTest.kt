package me.srishanth.opentrack360.core.io

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertThrows
import org.junit.Test
import java.io.ByteArrayInputStream

class BoundedInputStreamTest {
    @Test
    fun readsAtMostOneByteBeyondLimitToDetectOversizePayloads() {
        val input = ByteArrayInputStream(byteArrayOf(1, 2, 3, 4, 5))

        assertArrayEquals(byteArrayOf(1, 2, 3, 4), input.readUpToLimit(3))
    }

    @Test
    fun returnsShorterPayloadWhenStreamEndsWithinLimit() {
        val input = ByteArrayInputStream(byteArrayOf(1, 2))

        assertArrayEquals(byteArrayOf(1, 2), input.readUpToLimit(3))
    }

    @Test
    fun rejectsInvalidLimits() {
        assertThrows(IllegalArgumentException::class.java) {
            ByteArrayInputStream(byteArrayOf()).readUpToLimit(-1)
        }
    }
}
