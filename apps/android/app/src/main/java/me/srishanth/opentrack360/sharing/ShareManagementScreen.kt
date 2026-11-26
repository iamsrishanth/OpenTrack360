package me.srishanth.opentrack360.sharing

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Intent
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch
import me.srishanth.opentrack360.ui.map.GeoPoint
import me.srishanth.opentrack360.ui.map.MapCircle
import me.srishanth.opentrack360.ui.map.OpenTrackMapSurface
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

/** Sender UI for creating, listing, and revoking read-only guest trip links. */
@Composable
@OptIn(ExperimentalLayoutApi::class)
fun ShareManagementScreen(
    gateway: ShareGateway,
    onStartLocationSharing: () -> Unit,
    modifier: Modifier = Modifier,
    isLocationSharing: Boolean = false,
    hasCurrentLocationFix: Boolean = false,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var selectedMode by remember { mutableStateOf(ShareMode.TIME) }
    var durationMinutes by remember { mutableStateOf("60") }
    var destination by remember { mutableStateOf<GeoPoint?>(null) }
    var radiusMeters by remember { mutableStateOf("150") }
    var pendingReview by remember { mutableStateOf<CreateShareRequest?>(null) }
    var createdShare by remember { mutableStateOf<CreatedShare?>(null) }
    var shares by remember { mutableStateOf<List<SenderShare>>(emptyList()) }
    var revokeTarget by remember { mutableStateOf<SenderShare?>(null) }
    var loadingShares by remember { mutableStateOf(false) }
    var creating by remember { mutableStateOf(false) }
    var actionError by remember { mutableStateOf<String?>(null) }
    var copied by remember { mutableStateOf(false) }

    fun refreshShares() {
        scope.launch {
            loadingShares = true
            try {
                shares = gateway.list().data
                actionError = null
            } catch (_: Exception) {
                actionError = "Could not load your share links. Try again."
            } finally {
                loadingShares = false
            }
        }
    }

    fun createShare(request: CreateShareRequest) {
        scope.launch {
            creating = true
            actionError = null
            createdShare = null
            copied = false
            try {
                val created = gateway.create(request)
                val link = ShareSheetPayload.fromCreatedLink(created.url)
                val parsed = GuestShareLinkParser.parse(created.url) as? GuestLinkParseResult.Valid
                if (link == null || parsed == null || !parsed.request.shareId.equals(created.id, ignoreCase = true)) {
                    actionError = "The server returned a link that does not match the expected share format."
                } else {
                    // Kept in ordinary in-memory Compose state only; never saved to Bundle or disk.
                    createdShare = created
                    try {
                        shares = gateway.list().data
                    } catch (_: Exception) {
                        actionError = "The link was created. Your link list could not refresh."
                    }
                }
            } catch (_: Exception) {
                actionError = "Could not create the share. Check your connection and try again."
            } finally {
                creating = false
            }
        }
    }

    fun confirmRevoke(share: SenderShare) {
        scope.launch {
            try {
                gateway.revoke(share.id)
                try {
                    shares = gateway.list().data
                    actionError = null
                } catch (_: Exception) {
                    actionError = "The link was revoked. Your link list could not refresh."
                }
            } catch (_: Exception) {
                actionError = "Could not revoke this link. Try again."
            } finally {
                revokeTarget = null
            }
        }
    }

    LaunchedEffect(gateway) { refreshShares() }

    Column(
        modifier = modifier.verticalScroll(rememberScrollState()).padding(20.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        Text("Share a live trip", style = MaterialTheme.typography.headlineSmall)
        Text("Choose when this read-only link should end.", style = MaterialTheme.typography.bodyMedium)

        if (!isLocationSharing) {
            Card(modifier = Modifier.fillMaxWidth()) {
                Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("Location sharing is off", style = MaterialTheme.typography.titleMedium)
                    Text("Creating a link does not start tracking. Tap below to request the needed location access and start phone sharing.")
                    Button(onClick = onStartLocationSharing) { Text("Start sharing location") }
                }
            }
        } else if (!hasCurrentLocationFix) {
            Text("Location sharing has started. New links will wait for the first location fix.")
        }

        FlowRow(
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            FilterChip(
                selected = selectedMode == ShareMode.TIME,
                onClick = { selectedMode = ShareMode.TIME },
                label = { Text("Until a time") },
            )
            FilterChip(
                selected = selectedMode == ShareMode.DESTINATION,
                onClick = { selectedMode = ShareMode.DESTINATION },
                label = { Text("At a destination") },
            )
            FilterChip(
                selected = selectedMode == ShareMode.DRIVE_END,
                onClick = { selectedMode = ShareMode.DRIVE_END },
                label = { Text("When I stop driving") },
            )
        }

        when (selectedMode) {
            ShareMode.TIME -> {
                OutlinedTextField(
                    value = durationMinutes,
                    onValueChange = { durationMinutes = it.filter(Char::isDigit).take(8) },
                    modifier = Modifier.fillMaxWidth(),
                    label = { Text("Duration in minutes") },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    singleLine = true,
                )
                val previewExpiry = durationMinutes.toLongOrNull()?.takeIf { it > 0 }
                    ?.let { runCatching { Instant.now().plusSeconds(it * 60) }.getOrNull() }
                Text(
                    text = previewExpiry?.let { "Link ends ${LOCAL_FORMAT.format(it)}" }
                        ?: "Enter a positive number of minutes.",
                    style = MaterialTheme.typography.bodyMedium,
                )
            }
            ShareMode.DESTINATION -> {
                Text("Tap the map to choose the destination. Arrival needs two fresh, accurate fixes at least 60 seconds apart inside the selected radius.")
                OpenTrackMapSurface(
                    modifier = Modifier.fillMaxWidth().height(280.dp),
                    circles = listOfNotNull(
                        destination?.let {
                            radiusMeters.toDoubleOrNull()?.let { radius ->
                                if (radius in 50.0..1000.0) MapCircle(it, radius) else null
                            }
                        },
                    ),
                    selectedPoint = destination,
                    onPointSelected = { destination = it },
                )
                destination?.let {
                    Text("Destination: ${"%.5f".format(Locale.US, it.latitude)}, ${"%.5f".format(Locale.US, it.longitude)}")
                } ?: Text("No destination selected yet.")
                OutlinedTextField(
                    value = radiusMeters,
                    onValueChange = { radiusMeters = it.filter(Char::isDigit).take(4) },
                    modifier = Modifier.fillMaxWidth(),
                    label = { Text("Arrival radius in metres (50–1000)") },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    singleLine = true,
                )
            }
            ShareMode.DRIVE_END -> Text(
                "This link ends only after a confirmed drive followed by five continuous minutes of credible stationary evidence.",
                style = MaterialTheme.typography.bodyMedium,
            )
        }

        Card(modifier = Modifier.fillMaxWidth()) {
            Column(
                modifier = Modifier.padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                Text("What the recipient can see", style = MaterialTheme.typography.titleMedium)
                Text("The recipient sees this link’s waiting, active, or signal-lost status and, when available, your latest location, accuracy, update time, and speed only when the fix is recent and reliable. Circle roster, trip history, destination, and contact details stay private.")
                Text("Anyone holding a forwarded active link can view this trip.", color = MaterialTheme.colorScheme.error)
            }
        }

        val requestForReview = selectedRequest(selectedMode, durationMinutes, destination, radiusMeters)
        Button(
            onClick = { pendingReview = requestForReview },
            enabled = requestForReview != null && !creating,
            modifier = Modifier.fillMaxWidth(),
        ) {
            Text(if (creating) "Creating link…" else "Review share")
        }

        actionError?.let { Text(it, color = MaterialTheme.colorScheme.error) }

        createdShare?.let { created ->
            Card(modifier = Modifier.fillMaxWidth()) {
                Column(
                    modifier = Modifier.padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Text("Live trip link created", style = MaterialTheme.typography.titleMedium)
                    if (!hasCurrentLocationFix) {
                        Text("This link is active and waiting for the first location. Start location sharing above; the recipient will see no marker until a fix is available.")
                    }
                    Text("Ends: ${created.endCondition}")
                    created.expiresAt?.let { Text("Resolved local expiry: ${LOCAL_FORMAT.format(it)}") }
                    Text(created.url, style = MaterialTheme.typography.bodySmall)
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Button(onClick = {
                            val payload = ShareSheetPayload.fromCreatedLink(created.url)
                            if (payload == null) {
                                actionError = "The share link is invalid."
                            } else {
                                val clipboard = context.getSystemService(ClipboardManager::class.java)
                                clipboard?.setPrimaryClip(ClipData.newPlainText("OpenTrack360 live trip", payload.text))
                                copied = true
                                actionError = null
                            }
                        }) { Text(if (copied) "Copied" else "Copy link") }
                        Button(onClick = {
                            val payload = ShareSheetPayload.fromCreatedLink(created.url)
                            if (payload == null) {
                                actionError = "The share link is invalid."
                            } else {
                                val sendIntent = Intent(Intent.ACTION_SEND).apply {
                                    type = "text/plain"
                                    putExtra(Intent.EXTRA_TEXT, payload.text)
                                }
                                context.startActivity(Intent.createChooser(sendIntent, "Share live trip"))
                            }
                        }) { Text("Share link") }
                    }
                }
            }
        }

        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text("Your share links", style = MaterialTheme.typography.titleLarge)
            TextButton(onClick = { refreshShares() }, enabled = !loadingShares) {
                Text(if (loadingShares) "Loading…" else "Refresh")
            }
        }
        if (shares.isEmpty() && !loadingShares) {
            Text("No links yet.", style = MaterialTheme.typography.bodyMedium)
        }
        shares.forEach { share ->
            SenderShareCard(share = share, onRevoke = { revokeTarget = share })
        }
    }

    pendingReview?.let { request ->
        AlertDialog(
            onDismissRequest = { pendingReview = null },
            title = { Text("Review live trip link") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(recipientVisibleData)
                    Text("End condition: ${endConditionFor(request)}")
                    Text("Anyone holding a forwarded active link can view it.", color = MaterialTheme.colorScheme.error)
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    pendingReview = null
                    createShare(request)
                }) { Text("Create link") }
            },
            dismissButton = { TextButton(onClick = { pendingReview = null }) { Text("Cancel") } },
        )
    }

    revokeTarget?.let { share ->
        AlertDialog(
            onDismissRequest = { revokeTarget = null },
            title = { Text("Revoke this link?") },
            text = { Text("The recipient will no longer be able to view this trip. Revocation cannot be undone.") },
            confirmButton = {
                TextButton(onClick = { confirmRevoke(share) }) { Text("Revoke link") }
            },
            dismissButton = { TextButton(onClick = { revokeTarget = null }) { Text("Keep link") } },
        )
    }
}

@Composable
private fun SenderShareCard(share: SenderShare, onRevoke: () -> Unit) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(5.dp)) {
            Text("${share.mode.label()} · ${share.status.wireValue}", style = MaterialTheme.typography.titleMedium)
            Text("Started ${LOCAL_FORMAT.format(share.startedAt)}")
            share.expiresAt?.let { Text("Ends ${LOCAL_FORMAT.format(it)}") }
            share.endedAt?.let { Text("Ended ${LOCAL_FORMAT.format(it)}") }
            Text("${share.endCondition}", style = MaterialTheme.typography.bodyMedium)
            if (share.status == SenderShareStatus.ACTIVE) {
                TextButton(onClick = onRevoke) { Text("Revoke") }
            }
        }
    }
}

private fun selectedRequest(
    mode: ShareMode,
    durationMinutes: String,
    destination: GeoPoint?,
    radiusMeters: String,
): CreateShareRequest? {
    return when (mode) {
        ShareMode.TIME -> durationMinutes.toLongOrNull()?.takeIf { it > 0 }
            ?.let { minutes -> runCatching { CreateShareRequest.UntilTime(Instant.now().plusSeconds(minutes * 60)) }.getOrNull() }
        ShareMode.DESTINATION -> {
            val point = destination ?: return null
            val radius = radiusMeters.toIntOrNull()?.takeIf { it in 50..1000 } ?: return null
            CreateShareRequest.UntilDestination(point.latitude, point.longitude, radius)
        }
        ShareMode.DRIVE_END -> CreateShareRequest.UntilDriveEnd
    }
}

private fun endConditionFor(request: CreateShareRequest): String = when (request) {
    is CreateShareRequest.UntilTime -> "At ${LOCAL_FORMAT.format(request.expiresAt)} local time."
    is CreateShareRequest.UntilDestination -> "After two fresh, accurate fixes at least 60 seconds apart inside the selected ${request.radiusMeters} m radius."
    CreateShareRequest.UntilDriveEnd -> "After a confirmed drive and five continuous minutes of credible stationary evidence."
}

private fun ShareMode.label(): String = when (this) {
    ShareMode.TIME -> "Until a time"
    ShareMode.DESTINATION -> "At destination"
    ShareMode.DRIVE_END -> "When I stop driving"
}

private val LOCAL_FORMAT: DateTimeFormatter =
    DateTimeFormatter.ofPattern("EEE, d MMM yyyy 'at' h:mm a", Locale.getDefault()).withZone(ZoneId.systemDefault())

private const val recipientVisibleData =
    "The recipient sees this link’s waiting, active, or signal-lost status and, when available, your latest location, accuracy, update time, and speed only when the fix is recent and reliable. Circle roster, trip history, destination, and contact details are not shown."
