package me.srishanth.opentrack360.ui.pets

import me.srishanth.opentrack360.OwnerScopedLostPetReportIdStore
import me.srishanth.opentrack360.tracking.InMemoryTrackingBlobStorage
import me.srishanth.opentrack360.tracking.TrackingQueueCipher
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class OwnerScopedLostPetReportIdStoreTest {
    private val ownerA = "00000000-0000-0000-0000-000000000001"
    private val ownerB = "00000000-0000-0000-0000-000000000002"
    private val petA = "10000000-0000-0000-0000-000000000001"
    private val petB = "10000000-0000-0000-0000-000000000002"
    private val reportA = "20000000-0000-0000-0000-000000000001"
    private val reportB = "20000000-0000-0000-0000-000000000002"

    @Test
    fun idsSurviveStoreRecreationAndRemainScopedToTheOwner() {
        val storage = InMemoryTrackingBlobStorage()
        val firstStore = OwnerScopedLostPetReportIdStore(storage, TestCipher())
        assertTrue(firstStore.remember(ownerA, petA, reportA))
        assertTrue(firstStore.remember(ownerB, petA, reportB))

        val restartedStore = OwnerScopedLostPetReportIdStore(storage, TestCipher())
        assertEquals(mapOf(petA to reportA), restartedStore.restore(ownerA).reportIds)
        assertEquals(mapOf(petA to reportB), restartedStore.restore(ownerB).reportIds)
        assertTrue(restartedStore.restore(ownerA).available)
    }

    @Test
    fun removalIsLimitedToTheMatchingOwnerAndPet() {
        val storage = InMemoryTrackingBlobStorage()
        val store = OwnerScopedLostPetReportIdStore(storage, TestCipher())
        assertTrue(store.remember(ownerA, petA, reportA))
        assertTrue(store.remember(ownerA, petB, reportB))
        assertTrue(store.remember(ownerB, petA, reportB))

        assertTrue(store.remove(ownerA, petA))
        assertEquals(mapOf(petB to reportB), store.restore(ownerA).reportIds)
        assertEquals(mapOf(petA to reportB), store.restore(ownerB).reportIds)
    }

    @Test
    fun encryptedBytesDoNotContainStoredIdentifiersInPlainText() {
        val storage = InMemoryTrackingBlobStorage()
        val store = OwnerScopedLostPetReportIdStore(storage, TestCipher())
        assertTrue(store.remember(ownerA, petA, reportA))

        val stored = requireNotNull(storage.read()).toString(Charsets.ISO_8859_1)
        assertFalse(stored.contains(ownerA))
        assertFalse(stored.contains(petA))
        assertFalse(stored.contains(reportA))
    }

    @Test
    fun unreadableCiphertextIsReportedAndNotOverwritten() {
        val storage = InMemoryTrackingBlobStorage()
        val unreadable = byteArrayOf(0x01, 0x02, 0x03)
        assertTrue(storage.write(unreadable))
        val store = OwnerScopedLostPetReportIdStore(storage, TestCipher())

        val restored = store.restore(ownerA)
        assertFalse(restored.available)
        assertTrue(restored.reportIds.isEmpty())
        assertFalse(store.remember(ownerA, petA, reportA))
        assertEquals(unreadable.toList(), requireNotNull(storage.read()).toList())
    }

    @Test
    fun invalidIdentifiersAreRejectedWithoutCreatingARecord() {
        val storage = InMemoryTrackingBlobStorage()
        val store = OwnerScopedLostPetReportIdStore(storage, TestCipher())

        assertFalse(store.remember(ownerA, "bad-pet-id", reportA))
        assertFalse(store.restore("bad-owner-id").available)
        assertNull(store.restore(ownerA).reportIds[petA])
        assertTrue(store.restore(ownerA).reportIds.isEmpty())
    }

    private class TestCipher : TrackingQueueCipher {
        override fun encrypt(plaintext: ByteArray): ByteArray = ByteArray(plaintext.size + 1).apply {
            this[0] = CIPHER_MARKER
            plaintext.forEachIndexed { index, byte -> this[index + 1] = (byte.toInt() xor XOR_MASK).toByte() }
        }

        override fun decrypt(ciphertext: ByteArray): ByteArray {
            require(ciphertext.isNotEmpty() && ciphertext[0] == CIPHER_MARKER)
            return ByteArray(ciphertext.size - 1) { index ->
                (ciphertext[index + 1].toInt() xor XOR_MASK).toByte()
            }
        }

        private companion object {
            const val CIPHER_MARKER: Byte = 0x53
            const val XOR_MASK = 0x5A
        }
    }
}
