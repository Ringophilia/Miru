// Copyright (c) 2026 Ringophilia and xiaomao
// SPDX-License-Identifier: MPL-2.0

package app.miru.tabs

import android.graphics.Bitmap
import android.net.Uri
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.util.VelocityTracker
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import app.miru.design.AnchoredMenu
import app.miru.design.MiruMotion
import app.miru.design.SiteIcon
import app.miru.design.withReducedMotionFade
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlin.math.abs

data class TabItem(
    val id: String,
    val title: String,
    val url: String,
    val incognito: Boolean,
    val thumbnail: Bitmap? = null,
    val icon: Bitmap? = null,
)

@Composable
fun TabsTray(
    tabs: List<TabItem>,
    selectedTabId: String?,
    incognito: Boolean,
    onIncognitoChanged: (Boolean) -> Unit,
    onSelect: (String) -> Unit,
    onClose: (String) -> Unit,
    onNewTab: (Rect?) -> Unit,
    onCloseAll: () -> Unit,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
    progress: () -> Float = { 1f },
    transformingTabId: String? = null,
    reducedMotion: Boolean = false,
    onCardBounds: (String, Rect?) -> Unit = { _, _ -> },
) {
    var menuOpen by remember { mutableStateOf(false) }
    var addBounds by remember { mutableStateOf<Rect?>(null) }
    val visibleTabs = tabs.filter { it.incognito == incognito }
    Surface(modifier.fillMaxSize(), color = MaterialTheme.colorScheme.surfaceContainer) {
        Column {
            Row(Modifier.fillMaxWidth().height(64.dp).padding(horizontal = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                IconButton(
                    onClick = { onNewTab(addBounds) },
                    modifier = Modifier.testTag("tabs-add-button").onGloballyPositioned { coordinates ->
                        addBounds = coordinates.boundsInRoot().takeUnless { it.isEmpty }
                    },
                ) { Icon(Icons.Outlined.Add, stringResource(R.string.tabs_new)) }
                Row(Modifier.weight(1f), horizontalArrangement = Arrangement.Center) {
                    FilterChip(
                        selected = !incognito,
                        onClick = { onIncognitoChanged(false) },
                        label = { Text(stringResource(R.string.tabs_regular)) },
                    )
                    Spacer(Modifier.width(8.dp))
                    FilterChip(
                        selected = incognito,
                        onClick = { onIncognitoChanged(true) },
                        label = { Text(stringResource(R.string.tabs_incognito)) },
                        leadingIcon = { Icon(Icons.Outlined.VisibilityOff, null, Modifier.size(18.dp)) },
                    )
                }
                Box(Modifier.testTag("tabs-overflow-anchor")) {
                    IconButton(onClick = { menuOpen = true }) { Icon(Icons.Outlined.MoreVert, stringResource(R.string.tabs_menu)) }
                    AnchoredMenu(menuOpen, onDismissRequest = { menuOpen = false }, reducedMotion = reducedMotion, modifier = Modifier.testTag("tabs-overflow-menu")) {
                        DropdownMenuItem(
                            text = { Text(stringResource(R.string.tabs_close_all)) },
                            enabled = visibleTabs.isNotEmpty(),
                            onClick = {
                                if (menuOpen) {
                                    menuOpen = false
                                    onCloseAll()
                                }
                            },
                        )
                    }
                }
                IconButton(onClick = onDismiss) { Icon(Icons.Outlined.Close, stringResource(R.string.tabs_dismiss)) }
            }
            if (visibleTabs.isEmpty()) {
                Column(Modifier.fillMaxSize(), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
                    Icon(if (incognito) Icons.Outlined.VisibilityOff else Icons.Outlined.Tab, null, Modifier.size(48.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
                    Spacer(Modifier.height(16.dp))
                    Text(stringResource(if (incognito) R.string.tabs_empty_incognito else R.string.tabs_empty), style = MaterialTheme.typography.bodyLarge)
                    Spacer(Modifier.height(16.dp))
                    TextButton(onClick = { onNewTab(null) }) { Text(stringResource(R.string.tabs_new)) }
                }
            } else key(incognito) {
                val selectedIndex = visibleTabs.indexOfFirst { it.id == selectedTabId }.coerceAtLeast(0)
                LazyVerticalGrid(
                    columns = GridCells.Fixed(2),
                    state = rememberLazyGridState(initialFirstVisibleItemIndex = selectedIndex / 2 * 2),
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(16.dp),
                    horizontalArrangement = Arrangement.spacedBy(16.dp),
                    verticalArrangement = Arrangement.spacedBy(16.dp),
                ) {
                    items(visibleTabs, key = { it.id }) { tab ->
                        TabCard(
                            tab = tab,
                            selected = tab.id == selectedTabId,
                            onSelect = { onSelect(tab.id) },
                            onClose = { onClose(tab.id) },
                            onBounds = { onCardBounds(tab.id, it) },
                            modifier = Modifier.animateItem(
                                fadeInSpec = if (reducedMotion) null else tween(MiruMotion.short2),
                                placementSpec = if (reducedMotion) null else spring(MiruMotion.springDamping, MiruMotion.springStiffness),
                                fadeOutSpec = if (reducedMotion) null else tween(MiruMotion.short2),
                            ).graphicsLayer {
                                if (!reducedMotion) {
                                    val fraction = progress()
                                    if (tab.id == transformingTabId) {
                                        alpha = ((fraction - 0.8f) / 0.2f).coerceIn(0f, 1f)
                                    } else {
                                        scaleX = 0.92f + 0.08f * fraction
                                        scaleY = scaleX
                                    }
                                }
                            },
                            reducedMotion = reducedMotion,
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun TabCard(tab: TabItem, selected: Boolean, onSelect: () -> Unit, onClose: () -> Unit,
    onBounds: (Rect?) -> Unit, reducedMotion: Boolean, modifier: Modifier = Modifier) {
    val scope = rememberCoroutineScope()
    var offset by remember(tab.id) { mutableFloatStateOf(0f) }
    var cardWidth by remember { mutableIntStateOf(1) }
    var settle by remember { mutableStateOf<Job?>(null) }
    var closed by remember { mutableStateOf(false) }
    val closeTab by rememberUpdatedState(onClose)
    val velocityThreshold = with(LocalDensity.current) { 1000.dp.toPx() }
    val newTab = stringResource(R.string.tabs_new)
    val closeLabel = stringResource(R.string.tabs_close_named, tab.title.ifBlank { newTab })
    val shape = RoundedCornerShape(12.dp)
    val latestBounds by rememberUpdatedState(onBounds)
    DisposableEffect(tab.id) { onDispose { latestBounds(null) } }

    fun closeOnce() {
        if (!closed) {
            closed = true
            closeTab()
        }
    }

    fun finishSwipe(velocity: Float, cancelled: Boolean) {
        if (closed) return
        val direction = if (cancelled) 0 else tabDismissDirection(offset, cardWidth.toFloat(), velocity, velocityThreshold)
        settle?.cancel()
        settle = scope.launch {
            val animation = Animatable(offset)
            val target = cardWidth.toFloat() * direction
            if (reducedMotion) withReducedMotionFade(true) {
                animation.animateTo(target, tween(MiruMotion.short2)) { offset = value }
            } else animation.animateTo(target, spring(MiruMotion.springDamping, MiruMotion.springStiffness), initialVelocity = velocity) {
                offset = value
            }
            if (direction != 0) closeOnce()
        }
    }

    // Track the pointer in the stationary grid slot, even as its visible card translates.
    Box(
        modifier
            .onGloballyPositioned { onBounds(it.boundsInRoot().takeUnless { bounds -> bounds.isEmpty }) }
            .onSizeChanged { cardWidth = it.width }
            .pointerInput(tab.id, cardWidth, reducedMotion) {
                val tracker = VelocityTracker()
                detectHorizontalDragGestures(
                    onDragStart = {
                        tracker.resetTracking()
                        settle?.cancel()
                    },
                    onHorizontalDrag = { change, drag ->
                        tracker.addPosition(change.uptimeMillis, change.position)
                        if (!closed) offset = (offset + drag).coerceIn(-cardWidth.toFloat(), cardWidth.toFloat())
                    },
                    onDragCancel = { finishSwipe(0f, cancelled = true) },
                    onDragEnd = { finishSwipe(tracker.calculateVelocity().x, cancelled = false) },
                )
            },
    ) {
        Column(Modifier.testTag("tabs-card-${tab.id}")
            .graphicsLayer {
                translationX = if (reducedMotion) 0f else offset
                alpha = (1f - abs(offset) / cardWidth.coerceAtLeast(1)).coerceIn(0f, 1f)
            }
            .clip(shape)
            .background(MaterialTheme.colorScheme.surface)
            .then(if (selected) Modifier.border(2.dp, MaterialTheme.colorScheme.primary, shape) else Modifier)
            .clickable(enabled = !closed, onClick = onSelect)) {
            Row(Modifier.fillMaxWidth().height(48.dp).padding(start = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                SiteIcon(tab.url, tab.title, size = 16.dp, bitmap = tab.icon, isPrivate = tab.incognito)
                Spacer(Modifier.width(8.dp))
                Text(tab.title.ifBlank { newTab }, Modifier.weight(1f), style = MaterialTheme.typography.labelMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                IconButton(onClick = { settle?.cancel(); closeOnce() }, enabled = !closed, modifier = Modifier.size(48.dp)) {
                    Icon(Icons.Outlined.Close, closeLabel, Modifier.size(18.dp))
                }
            }
            TabPagePreview(tab, Modifier.fillMaxWidth().aspectRatio(0.65f).padding(2.dp)
                .clip(RoundedCornerShape(bottomStart = 10.dp, bottomEnd = 10.dp)))
        }
    }
}

@Composable
fun TabPagePreview(tab: TabItem, modifier: Modifier = Modifier) {
    Box(modifier.background(MaterialTheme.colorScheme.surfaceContainerHigh), contentAlignment = Alignment.Center) {
        when {
            tab.incognito -> Icon(Icons.Outlined.VisibilityOff, stringResource(R.string.tabs_private_preview), Modifier.size(40.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
            tab.thumbnail != null && !tab.thumbnail.isRecycled -> Image(
                remember(tab.thumbnail) { tab.thumbnail.asImageBitmap() }, tab.title,
                Modifier.fillMaxSize(), contentScale = ContentScale.Crop,
            )
            tab.url == "about:newtab" -> Text(stringResource(R.string.tabs_miru), style = MaterialTheme.typography.titleLarge, color = MaterialTheme.colorScheme.primary)
            else -> Column(Modifier.padding(16.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                Icon(Icons.Outlined.Public, null, Modifier.size(40.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
                Spacer(Modifier.height(12.dp))
                Text(runCatching { Uri.parse(tab.url).host }.getOrNull() ?: tab.url, style = MaterialTheme.typography.bodySmall, maxLines = 2, overflow = TextOverflow.Ellipsis)
            }
        }
    }
}
