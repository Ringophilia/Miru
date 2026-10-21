package app.miru.design

import android.net.Uri
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
import androidx.compose.runtime.Composable
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
import kotlinx.coroutines.withTimeoutOrNull

/** Site host without "www.", or the raw value when it is not a URL. */
fun siteHost(url: String): String =
    runCatching { Uri.parse(url).host }.getOrNull()?.removePrefix("www.")?.takeIf { it.isNotBlank() } ?: url

/**
 * Fallback site icon (spec 6.5): the first letter on a color derived from the domain,
 * so sites that share an initial are still distinguishable.
 */
@Composable
fun SiteIcon(url: String, title: String, modifier: Modifier = Modifier, size: Dp = 40.dp) {
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
suspend fun SnackbarHostState.showUndo(message: String, undoLabel: String): Boolean = showWithAction(message, undoLabel)

/** Snackbars with an action default to Indefinite in Material 3; this bounds them to 6 seconds like undo. */
suspend fun SnackbarHostState.showWithAction(message: String, actionLabel: String): Boolean {
    currentSnackbarData?.dismiss()
    return withTimeoutOrNull(6_000) { showSnackbar(message, actionLabel, duration = SnackbarDuration.Indefinite) } == SnackbarResult.ActionPerformed
}

/** Shows a plain message for the standard 4 seconds, replacing any current one. */
suspend fun SnackbarHostState.showMessage(message: String) {
    currentSnackbarData?.dismiss()
    showSnackbar(message)
}
