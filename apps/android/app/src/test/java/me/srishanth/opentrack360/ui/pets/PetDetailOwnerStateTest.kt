package me.srishanth.opentrack360.ui.pets

import me.srishanth.opentrack360.ui.model.PetUi
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PetDetailOwnerStateTest {
    @Test
    fun ownerControlsDefaultToHiddenUntilOwnerDetailIsAuthorized() {
        val sharedPet = PetDetailUiState(pet = PetUi(id = "pet-id", name = "Milo", speciesLabel = "Dog"))
        val ownerPet = PetDetailUiState(pet = sharedPet.pet, isOwner = true)

        assertFalse(sharedPet.isOwner)
        assertTrue(ownerPet.isOwner)
    }
}
