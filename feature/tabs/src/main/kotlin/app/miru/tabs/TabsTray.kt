// Copyright (c) 2026 Ringophilia and xiaomao
// SPDX-License-Identifier: MPL-2.0

package app.miru.tabs

import android.graphics.Bitmap
import android.net.Uri
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.spring
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.util.VelocityTracker
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import app.miru.design.MiruMotion
import kotlinx.coroutines.launch
import kotlin.math.abs

data class TabItem(
    val id: String,
    val title: String,
    val url: String,
    val incognito: Boolean,
    val thumbnail: Bitmap? = null,
)

@Composable
fun TabsTray(
    tabs: List<TabItem>,
    selectedTabId: String?,
    incognito: Boolean,
    onIncognitoChanged: (Boolean) -> Unit,
    onSelect: (String) -> Unit,
    onClose: (String) -> Unit,
    onNewTab: () -> Unit,
    onCloseAll: () -> Unit,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
) {
    var menuOpen by remember { mutableStateOf(false) }
    val visibleTabs = tabs.filter { it.incognito == incognito }
    Surface(modifier.fillMaxSize(), color = MaterialTheme.colorScheme.surfaceContainer) {
        Column {
            Row(Modifier.fillMaxWidth().height(64.dp).padding(horizontal = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClick = onNewTab) { Icon(Icons.Outlined.Add, stringResource(R.string.tabs_new)) }
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
                Box {
                    IconButton(onClick = { menuOpen = true }) { Icon(Icons.Outlined.MoreVert, stringResource(R.string.tabs_menu)) }
                    DropdownMenu(menuOpen, onDismissRequest = { menuOpen = false }) {
                        DropdownMenuItem(
                            text = { Text(stringResource(R.string.tabs_close_all)) },
                            enabled = visibleTabs.isNotEmpty(),
                            onClick = { menuOpen = false; onCloseAll() },
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
                    TextButton(onClick = onNewTab) { Text(stringResource(R.string.tabs_new)) }
                }
            } else {
                LazyVerticalGrid(
                    columns = GridCells.Fixed(2),
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
                            modifier = Modifier.animateItem(),
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun TabCard(tab: TabItem, selected: Boolean, onSelect: () -> Unit, onClose: () -> Unit, modifier: Modifier = Modifier) {
    val offset = remember(tab.id) { Animatable(0f) }
    val scope = rememberCoroutineScope()
    var cardWidth by remember { mutableIntStateOf(1) }
    val velocityThreshold = with(LocalDensity.current) { 1000.dp.toPx() }
    val newTab = stringResource(R.string.tabs_new)
    val closeLabel = stringResource(R.string.tabs_close_named, tab.title.ifBlank { newTab })
    val shape = RoundedCornerShape(12.dp)
    Column(
        modifier
            .onSizeChanged { cardWidth = it.width }
            .graphicsLayer { translationX = offset.value; alpha = (1f - abs(offset.value) / cardWidth).coerceIn(0.25f, 1f) }
            .pointerInput(tab.id, cardWidth) {
                val tracker = VelocityTracker()
                detectHorizontalDragGestures(
                    onDragStart = { tracker.resetTracking() },
                    onHorizontalDrag = { change, drag ->
                        change.consume()
                        tracker.addPosition(change.uptimeMillis, change.position)
                        scope.launch { offset.snapTo(offset.value + drag) }
                    },
                    onDragCancel = {
                        scope.launch { offset.animateTo(0f, spring(MiruMotion.springDamping, MiruMotion.springStiffness)) }
                    },
                    onDragEnd = {
                        val speed = tracker.calculateVelocity().x
                        scope.launch {
                            if (abs(offset.value) >= cardWidth * 0.4f || abs(speed) > velocityThreshold) {
                                val direction = if (abs(offset.value) >= cardWidth * 0.4f) {
                                    if (offset.value < 0f) -1 else 1
                                } else {
                                    if (speed < 0f) -1 else 1
                                }
                                offset.animateTo(cardWidth.toFloat() * direction, spring(MiruMotion.springDamping, MiruMotion.springStiffness), initialVelocity = speed)
                                onClose()
                            } else offset.animateTo(0f, spring(MiruMotion.springDamping, MiruMotion.springStiffness), initialVelocity = speed)
                        }
                    },
                )
            }
            .clip(shape)
            .background(MaterialTheme.colorScheme.surface)
            .then(if (selected) Modifier.border(2.dp, MaterialTheme.colorScheme.primary, shape) else Modifier)
            .clickable(onClick = onSelect),
    ) {
        Row(Modifier.fillMaxWidth().height(48.dp).padding(start = 12.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(if (tab.incognito) Icons.Outlined.VisibilityOff else Icons.Outlined.Public, null, Modifier.size(16.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
            Spacer(Modifier.width(8.dp))
            Text(tab.title.ifBlank { newTab }, Modifier.weight(1f), style = MaterialTheme.typography.labelMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
            IconButton(onClick = onClose, modifier = Modifier.size(48.dp)) { Icon(Icons.Outlined.Close, closeLabel, Modifier.size(18.dp)) }
        }
        Box(
            Modifier.fillMaxWidth().aspectRatio(0.65f).padding(2.dp).clip(RoundedCornerShape(bottomStart = 10.dp, bottomEnd = 10.dp))
                .background(MaterialTheme.colorScheme.surfaceContainerHigh),
            contentAlignment = Alignment.Center,
        ) {
            when {
                tab.incognito -> Icon(Icons.Outlined.VisibilityOff, stringResource(R.string.tabs_private_preview), Modifier.size(40.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
                tab.thumbnail != null && !tab.thumbnail.isRecycled -> Image(tab.thumbnail.asImageBitmap(), tab.title, Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
                tab.url == "about:newtab" -> Text(stringResource(R.string.tabs_miru), style = MaterialTheme.typography.titleLarge, color = MaterialTheme.colorScheme.primary)
                else -> Column(Modifier.padding(16.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                    Icon(Icons.Outlined.Public, null, Modifier.size(40.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
                    Spacer(Modifier.height(12.dp))
                    Text(runCatching { Uri.parse(tab.url).host }.getOrNull() ?: tab.url, style = MaterialTheme.typography.bodySmall, maxLines = 2, overflow = TextOverflow.Ellipsis)
                }
            }
        }
    }
}
