package me.srishanth.opentrack360.ui

import me.srishanth.opentrack360.ui.model.DestructiveAction
import me.srishanth.opentrack360.ui.model.DestructiveActionKind
import me.srishanth.opentrack360.ui.model.DestructiveActionPolicy
import me.srishanth.opentrack360.ui.model.DestructiveConfirmationState
import me.srishanth.opentrack360.ui.model.MemberAccessPolicy
import me.srishanth.opentrack360.ui.model.circleSharingLabel
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class MemberUiPolicyTest {
    @Test
    fun circleMemberSharingLabelUsesBooleanMemberContract() {
        assertEquals("sharing", circleSharingLabel(true))
        assertEquals("paused", circleSharingLabel(false))
        assertEquals("unknown", circleSharingLabel(null))
    }

    @Test
    fun temporaryPasswordRotationBlocksMemberDataUntilFreshLogin() {
        var access = MemberAccessPolicy.afterLogin(
            userId = "member-1",
            username = "aarav",
            mustRotatePassword = true,
        )

        assertFalse(MemberAccessPolicy.canNavigateToMemberData(access))

        access = MemberAccessPolicy.afterPasswordChange()
        assertFalse(MemberAccessPolicy.canNavigateToMemberData(access))

        access = MemberAccessPolicy.afterFreshLogin(
            userId = "member-1",
            username = "aarav",
            mustRotatePassword = false,
        )
        assertTrue(MemberAccessPolicy.canNavigateToMemberData(access))
    }

    @Test
    fun globalStopRequiresVisibleScopeBeforeActionCanReachCallback() {
        val action = DestructiveAction(DestructiveActionKind.STOP_ALL_SHARING)
        val waiting = DestructiveActionPolicy.request(action)

        assertTrue(waiting is DestructiveConfirmationState.Awaiting)
        val confirmation = (waiting as DestructiveConfirmationState.Awaiting).confirmation
        assertTrue(confirmation.scope.contains("every Circle"))
        assertTrue(confirmation.scope.contains("active trip"))
        assertTrue(confirmation.scope.contains("guest link"))
        assertNull(DestructiveActionPolicy.actionReadyForCallback(waiting))

        val confirmed = DestructiveActionPolicy.confirm(waiting)
        assertNotNull(DestructiveActionPolicy.actionReadyForCallback(confirmed))
        assertEquals(action, DestructiveActionPolicy.actionReadyForCallback(confirmed))
    }

    @Test
    fun removeMemberConfirmationNamesCircleHistoryImpact() {
        val state = DestructiveActionPolicy.request(
            DestructiveAction(DestructiveActionKind.REMOVE_CIRCLE_MEMBER, "Mira"),
        ) as DestructiveConfirmationState.Awaiting

        assertTrue(state.confirmation.scope.contains("Mira"))
        assertTrue(state.confirmation.scope.contains("this Circle"))
        assertTrue(state.confirmation.scope.contains("history immediately"))
        assertNull(DestructiveActionPolicy.actionReadyForCallback(state))
    }

    @Test
    fun accountDeletionConfirmationNamesRequestScopeAndPhraseBeforeCallback() {
        val waiting = DestructiveActionPolicy.request(DestructiveAction(DestructiveActionKind.DELETE_ACCOUNT))
        val confirmation = (waiting as DestructiveConfirmationState.Awaiting).confirmation

        assertTrue(confirmation.scope.contains("account and personal data"))
        assertTrue(confirmation.scope.contains("DELETE MY ACCOUNT"))
        assertNull(DestructiveActionPolicy.actionReadyForCallback(waiting))
    }
}
