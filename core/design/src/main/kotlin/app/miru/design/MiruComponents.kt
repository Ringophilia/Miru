// Copyright (c) 2026 Ringophilia and xiaomao
// SPDX-License-Identifier: MPL-2.0

package app.miru.design

import android.net.Uri
import android.graphics.Bitmap
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/** Site host without "www.", or the raw value when it is not a URL. */
fun siteHost(url: String): String =
    runCatching { Uri.parse(url).host }.getOrNull()?.removePrefix("www.")?.takeIf { it.isNotBlank() } ?: url

val LocalSiteIconLoader = staticCompositionLocalOf<(suspend (String) -> Bitmap?)?> { null }

/** Real site icon, or a first letter on a stable domain color when unavailable. */
@Composable
fun SiteIcon(url: String, title: String, modifier: Modifier = Modifier, size: Dp = 40.dp,
             bitmap: Bitmap? = null, isPrivate: Boolean = false) {
    val loader = LocalSiteIconLoader.current
    val loaded by produceState<Bitmap?>(null, url, loader, isPrivate, bitmap) {
        value = null
        if (bitmap == null && !isPrivate) value = loader?.invoke(url)
    }
    val image = bitmap ?: loaded
    if (image != null && !image.isRecycled) {
        Box(modifier.testTag("site-icon:$url").size(size)
            .background(MaterialTheme.colorScheme.surfaceContainer, if (size >= 40.dp) CircleShape else RoundedCornerShape(4.dp)),
            contentAlignment = Alignment.Center) {
            Image(image.asImageBitmap(), null, Modifier.size(if (size >= 40.dp) 28.dp else size)
                .clip(RoundedCornerShape(4.dp)), contentScale = ContentScale.Fit)
        }
        return
    }
    val host = siteHost(url)
    val letter = (title.firstOrNull { it.isLetterOrDigit() } ?: host.firstOrNull { it.isLetterOrDigit() } ?: '?').uppercase()
    val hue = (host.hashCode().toLong() and 0xffffffffL) % 360
    val dark = MaterialTheme.colorScheme.surface.luminance() < 0.5f
    // Muted and pulled toward the paper surface so the tiles sit in the warm palette.
    val background = lerp(Color.hsl(hue.toFloat(), 0.35f, if (dark) 0.30f else 0.88f), MaterialTheme.colorScheme.surfaceContainer, 0.4f)
    val foreground = Color.hsl(hue.toFloat(), 0.45f, if (dark) 0.82f else 0.32f)
    Box(modifier.size(size).background(background, CircleShape).clearAndSetSemantics {}, contentAlignment = Alignment.Center) {
        Text(letter, color = foreground, fontSize = (size.value * 0.42f).sp, fontWeight = FontWeight.Medium)
    }
}

/** Pill search field used by full-screen lists (history, bookmarks). */
@Composable
fun MiruSearchField(
    value: String,
    onValueChange: (String) -> Unit,
    placeholder: String,
    clearLabel: String,
    modifier: Modifier = Modifier,
    focusRequester: FocusRequester? = null,
) {
    Surface(modifier.fillMaxWidth().height(48.dp), shape = RoundedCornerShape(50), color = MaterialTheme.colorScheme.surfaceContainer) {
        Row(Modifier.padding(start = 16.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Outlined.Search, null, Modifier.size(20.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
            Spacer(Modifier.width(12.dp))
            BasicTextField(
                value = value,
                onValueChange = onValueChange,
                modifier = Modifier.weight(1f).then(if (focusRequester != null) Modifier.focusRequester(focusRequester) else Modifier).semantics { contentDescription = placeholder },
                singleLine = true,
                textStyle = MaterialTheme.typography.bodyLarge.copy(color = MaterialTheme.colorScheme.onSurface),
                cursorBrush = SolidColor(MaterialTheme.colorScheme.primary),
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                keyboardActions = KeyboardActions(onSearch = {}),
                decorationBox = { inner ->
                    Box(contentAlignment = Alignment.CenterStart) {
                        if (value.isEmpty()) Text(placeholder, style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        inner()
                    }
                },
            )
            if (value.isNotEmpty()) {
                IconButton(onClick = { onValueChange("") }) { Icon(Icons.Outlined.Close, clearLabel, Modifier.size(20.dp)) }
            } else {
                Spacer(Modifier.width(16.dp))
            }
        }
    }
}

/**
 * Shows an undo snackbar for 6 seconds (spec 6.6) and reports whether undo was chosen.
 * Any snackbar already showing is replaced, so undo always refers to the latest action.
 */
suspend fun SnackbarHostState.showUndo(message: String, undoLabel: String): Boolean =
    replaceSnackbar(message, undoLabel, SnackbarDuration.Long) == SnackbarResult.ActionPerformed

/** Shows an ordinary four-second message with an action. */
suspend fun SnackbarHostState.showWithAction(message: String, actionLabel: String): Boolean =
    replaceSnackbar(message, actionLabel, SnackbarDuration.Short) == SnackbarResult.ActionPerformed

/** Shows a plain message for the standard 4 seconds, replacing any current one. */
suspend fun SnackbarHostState.showMessage(message: String) {
    replaceSnackbar(message, null, SnackbarDuration.Short)
}

private suspend fun SnackbarHostState.replaceSnackbar(
    message: String,
    actionLabel: String?,
    duration: SnackbarDuration,
): SnackbarResult {
    currentSnackbarData?.dismiss()
    return showSnackbar(message, actionLabel, duration = duration)
}
