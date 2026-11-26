package me.srishanth.opentrack360.ui.coordination

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class InboxItemUiTest {
    @Test
    fun exposesSosNavigationOnlyWhenBothContractIdsArePresent() {
        val item = InboxItemUi(
            id = "notification-1",
            kind = "sos",
            title = "Circle SOS",
            body = "A member sent an alert.",
            createdAtLabel = "10:00 AM",
            deliveryState = "received",
            acknowledged = false,
            circleId = "4dd0921f-9a43-46a1-9d4c-2c8100bc0e13",
            sosId = "4c8ecb14-0ef8-46e0-ad4c-e55d5db5c900",
        )

        assertEquals(
            InboxSosTarget("4dd0921f-9a43-46a1-9d4c-2c8100bc0e13", "4c8ecb14-0ef8-46e0-ad4c-e55d5db5c900"),
            item.sosTargetOrNull(),
        )
    }

    @Test
    fun doesNotExposeSosNavigationForIncompleteNotificationIds() {
        val circleId = "4dd0921f-9a43-46a1-9d4c-2c8100bc0e13"
        val sosId = "4c8ecb14-0ef8-46e0-ad4c-e55d5db5c900"
        val withoutSosId = item(circleId = circleId, sosId = null)
        val withoutCircleId = item(circleId = null, sosId = sosId)
        val blankIds = item(circleId = " ", sosId = sosId)
        val malformedIds = item(circleId = "circle-1", sosId = sosId)

        assertNull(withoutSosId.sosTargetOrNull())
        assertNull(withoutCircleId.sosTargetOrNull())
        assertNull(blankIds.sosTargetOrNull())
        assertNull(malformedIds.sosTargetOrNull())
    }

    private fun item(circleId: String?, sosId: String?) = InboxItemUi(
        id = "notification-1",
        kind = "update",
        title = "Update",
        body = "",
        createdAtLabel = "10:00 AM",
        deliveryState = "received",
        acknowledged = false,
        circleId = circleId,
        sosId = sosId,
    )
}
