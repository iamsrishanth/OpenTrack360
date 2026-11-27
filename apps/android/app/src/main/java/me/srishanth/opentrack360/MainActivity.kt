package me.srishanth.opentrack360

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import me.srishanth.opentrack360.sharing.DeepLinkRouter
import me.srishanth.opentrack360.sharing.CircleInviteLinkParser
import me.srishanth.opentrack360.sharing.CircleInviteRequest
import me.srishanth.opentrack360.sharing.GuestShareRequest
import me.srishanth.opentrack360.sharing.PetRecoveryLinkParser
import me.srishanth.opentrack360.sharing.PetRecoveryRequest
import me.srishanth.opentrack360.ui.theme.OpenTrackTheme

class MainActivity : ComponentActivity() {
    private var guestRequest by mutableStateOf<GuestShareRequest?>(null)
    private var petRecoveryRequest by mutableStateOf<PetRecoveryRequest?>(null)
    private var circleInviteRequest by mutableStateOf<CircleInviteRequest?>(null)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        routeIncomingIntent(intent)
        setContent {
            OpenTrackTheme {
                OpenTrackAppRoot(
                    activity = this,
                    runtime = (application as OpenTrackApplication).runtime,
                    guestRequest = guestRequest,
                    petRecoveryRequest = petRecoveryRequest,
                    circleInviteRequest = circleInviteRequest,
                    onCloseGuest = { guestRequest = null },
                    onClosePetRecovery = { petRecoveryRequest = null },
                    onCloseCircleInvite = { circleInviteRequest = null },
                )
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        routeIncomingIntent(intent)
    }

    private fun routeIncomingIntent(incoming: Intent?) {
        guestRequest = DeepLinkRouter.fromColdStart(incoming)
        petRecoveryRequest = PetRecoveryLinkParser.fromIntent(incoming)
        circleInviteRequest = CircleInviteLinkParser.fromIntent(incoming)

        // Keep only the validated transient request in Compose state. The Activity's retained
        // launch Intent must not keep a share, invite, or recovery secret after parsing.
        setIntent(
            if (incoming?.action == Intent.ACTION_VIEW) {
                Intent(this, MainActivity::class.java).setAction(Intent.ACTION_MAIN)
            } else {
                incoming ?: Intent(this, MainActivity::class.java).setAction(Intent.ACTION_MAIN)
            },
        )
    }
}
