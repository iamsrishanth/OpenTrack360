package me.srishanth.opentrack360.sharing

import android.os.SystemClock
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import me.srishanth.opentrack360.ui.map.GeoPoint
import me.srishanth.opentrack360.ui.map.MapMarker
import me.srishanth.opentrack360.ui.map.OpenTrackMapSurface
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

/** Signed-out guest map. It uses only the validated link credential and asks for no permissions. */
@Composable
fun GuestShareScreen(
    request: GuestShareRequest,
    gateway: GuestShareGateway,
    modifier: Modifier = Modifier,
) {
    val lifecycleOwner = LocalLifecycleOwner.current
    val machine = remember(request) { GuestShareStateMachine() }
    var resumed by remember {
        mutableStateOf(lifecycleOwner.lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED))
    }
    var display by remember(request) { mutableStateOf(GuestDisplayState()) }

    DisposableEffect(lifecycleOwner, machine) {
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_RESUME -> {
                    resumed = true
                    // Clear before the resumed screen can draw a cached marker; fetch validates it again.
                    display = machine.requireRevalidation()
                }
                Lifecycle.Event.ON_PAUSE -> resumed = false
                else -> Unit
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    LaunchedEffect(request, gateway, resumed) {
        if (!resumed) return@LaunchedEffect
        while (isActive) {
            val requestStarted = SystemClock.elapsedRealtime()
            val outcome = try {
                gateway.fetch(GuestShareHttpRequest.from(request))
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                GuestShareReadOutcome.TransientFailure
            }
            val received = SystemClock.elapsedRealtime()
            display = machine.accept(outcome, requestStarted, received)
            if (display.phase == GuestDisplayPhase.INVALID_LINK || display.phase == GuestDisplayPhase.LINK_ENDED) {
                break
            }

            val nextPollAt = received + POLL_INTERVAL_MILLIS
            while (isActive) {
                val now = SystemClock.elapsedRealtime()
                display = machine.snapshot(now)
                if (now >= nextPollAt) break
                delay(minOf(STATE_TICK_MILLIS, nextPollAt - now))
            }
        }
    }

    val fix = display.latest
    val markers = if (fix == null) {
        emptyList()
    } else {
        listOf(
            MapMarker(
                point = GeoPoint(fix.latitude, fix.longitude),
                stale = display.phase == GuestDisplayPhase.STALE,
            ),
        )
    }

    Column(modifier = modifier.fillMaxSize()) {
        Column(modifier = Modifier.padding(horizontal = 20.dp, vertical = 16.dp)) {
            Text("Live trip", style = MaterialTheme.typography.headlineSmall)
            Text(
                "Signed-out view of the sender’s latest location only.",
                style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier.padding(top = 4.dp),
            )
        }
        OpenTrackMapSurface(
            modifier = Modifier.weight(1f).fillMaxWidth(),
            markers = markers,
        )
        GuestStatusCard(display = display, modifier = Modifier.fillMaxWidth())
    }
}

@Composable
private fun GuestStatusCard(display: GuestDisplayState, modifier: Modifier = Modifier) {
    Card(modifier = modifier.padding(16.dp)) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Text(phaseMessage(display.phase), style = MaterialTheme.typography.titleMedium)
            display.lastUpdate?.let {
                Text(
                    text = "Last update ${LOCAL_TIME_FORMAT.format(it)}${if (display.phase == GuestDisplayPhase.STALE) " · stale" else ""}",
                    style = MaterialTheme.typography.bodyMedium,
                )
            }
            if (display.latest != null) {
                Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                    Text("Accuracy ±${display.latest.accuracyMeters.toInt()} m")
                    when (val speed = display.speed) {
                        is GuestSpeedDisplay.Available -> Text("${speed.kilometersPerHour} km/h")
                        GuestSpeedDisplay.Unavailable -> Text("Speed unavailable")
                    }
                }
            }
        }
    }
}

private fun phaseMessage(phase: GuestDisplayPhase): String = when (phase) {
    GuestDisplayPhase.LOADING -> "Checking this share…"
    GuestDisplayPhase.WAITING -> "Waiting for the sender’s first location."
    GuestDisplayPhase.ACTIVE -> "Location is live."
    GuestDisplayPhase.STALE -> "Showing a stale last location; speed is hidden."
    GuestDisplayPhase.SIGNAL_LOST -> "Sender signal lost. The last location is hidden."
    GuestDisplayPhase.DISPLAY_EXPIRED -> "Location hidden while the share is revalidated."
    GuestDisplayPhase.INVALID_LINK -> "This link is invalid or unavailable."
    GuestDisplayPhase.LINK_ENDED -> "This link has ended."
    GuestDisplayPhase.CONNECTION_PROBLEM -> "Could not reach the share. Retrying…"
}

private val LOCAL_TIME_FORMAT: DateTimeFormatter =
    DateTimeFormatter.ofPattern("d MMM, h:mm a", Locale.getDefault()).withZone(ZoneId.systemDefault())

private const val POLL_INTERVAL_MILLIS = 10_000L
private const val STATE_TICK_MILLIS = 250L
