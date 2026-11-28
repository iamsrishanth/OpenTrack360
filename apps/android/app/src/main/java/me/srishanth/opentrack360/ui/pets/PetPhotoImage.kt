package me.srishanth.opentrack360.ui.pets

import android.graphics.BitmapFactory
import androidx.compose.foundation.Image
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import me.srishanth.opentrack360.core.io.readUpToLimit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.net.HttpURLConnection
import java.net.URL

/** Shows a short-lived signed photo URL without attaching or persisting a member credential. */
@Composable
fun PetPhotoImage(
    url: String?,
    contentDescription: String,
    modifier: Modifier = Modifier,
) {
    var photo by remember(url) { mutableStateOf<androidx.compose.ui.graphics.ImageBitmap?>(null) }
    var failed by remember(url) { mutableStateOf(false) }
    LaunchedEffect(url) {
        photo = null
        failed = false
        if (!url.isNullOrBlank()) {
            photo = withContext(Dispatchers.IO) { downloadSignedPetPhoto(url) }
            failed = photo == null
        }
    }
    when {
        photo != null -> Image(
            bitmap = photo!!,
            contentDescription = contentDescription,
            contentScale = ContentScale.Crop,
            modifier = modifier,
        )
        failed -> Text("Photo could not be loaded. Refresh the pet profile and try again.")
        !url.isNullOrBlank() -> Text("Loading pet photo…")
    }
}

private fun downloadSignedPetPhoto(rawUrl: String): androidx.compose.ui.graphics.ImageBitmap? = runCatching {
    val url = URL(rawUrl)
    val host = url.host
    val allowedHost = host.equals(API_HOST, ignoreCase = true) || host.endsWith(SUPABASE_HOST_SUFFIX, ignoreCase = true)
    require(url.protocol.equals("https", ignoreCase = true) && allowedHost && url.userInfo == null)
    val connection = (url.openConnection() as HttpURLConnection).apply {
        requestMethod = "GET"
        connectTimeout = TIMEOUT_MILLIS
        readTimeout = TIMEOUT_MILLIS
        instanceFollowRedirects = false
        useCaches = false
        setRequestProperty("Accept", "image/jpeg,image/png,image/webp")
    }
    try {
        if (connection.responseCode !in 200..299) return null
        val contentType = connection.contentType?.substringBefore(';')?.trim()?.lowercase()
        require(contentType in IMAGE_CONTENT_TYPES)
        val bytes = connection.inputStream.use { it.readUpToLimit(MAX_IMAGE_BYTES) }
        require(bytes.isNotEmpty() && bytes.size <= MAX_IMAGE_BYTES)

        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
        require(bounds.outWidth in 1..MAX_DIMENSION && bounds.outHeight in 1..MAX_DIMENSION)
        var sampleSize = 1
        while (maxOf(bounds.outWidth, bounds.outHeight) / sampleSize > MAX_PREVIEW_DIMENSION) sampleSize *= 2
        val bitmap = BitmapFactory.decodeByteArray(
            bytes,
            0,
            bytes.size,
            BitmapFactory.Options().apply { inSampleSize = sampleSize },
        ) ?: return null
        bitmap.asImageBitmap()
    } finally {
        connection.disconnect()
    }
}.getOrNull()

private const val API_HOST = "track.srishanth.me"
private const val SUPABASE_HOST_SUFFIX = ".supabase.co"
private const val TIMEOUT_MILLIS = 10_000
private const val MAX_IMAGE_BYTES = 5 * 1024 * 1024
private const val MAX_DIMENSION = 4_096
private const val MAX_PREVIEW_DIMENSION = 1_200
private val IMAGE_CONTENT_TYPES = setOf("image/jpeg", "image/png", "image/webp")
