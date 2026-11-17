// Copyright (c) 2026 Ringophilia and xiaomao
// SPDX-License-Identifier: MPL-2.0

package app.miru.toolbar

import android.net.Uri
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.animation.togetherWith
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInRoot
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import app.miru.design.MiruMotion
import app.miru.design.MiruTheme

data class ToolbarState(
    val url: String = "about:newtab",
    val title: String = "",
    val editing: Boolean = false,
    val input: String = "",
    val inlineAddress: String? = null,
    val inlineDestination: String? = null,
    val tabCount: Int = 1,
    val loading: Boolean = false,
    val progress: Int = 0,
    val incognito: Boolean = false,
    val adblockAvailable: Boolean = false,
    val blockedCount: Int = 0,
    val secure: Boolean = false,
    val downloading: Boolean = false,
    val adblockNeedsReload: Boolean = false,
    val mediaCount: Int = 0,
)

@Composable
fun MiruToolbar(
    state: ToolbarState,
    onEdit: () -> Unit,
    onCancelEdit: () -> Unit,
    onTabs: () -> Unit,
    onMenu: () -> Unit,
    onSiteInfo: () -> Unit,
    onAdblock: () -> Unit,
    modifier: Modifier = Modifier,
    progressAtTop: Boolean = false,
    onMedia: () -> Unit = {},
    focusProgress: () -> Float = { 0f },
    reducedMotion: Boolean = false,
    onAddressBounds: (Rect) -> Unit = {},
    onEditorBounds: (Rect) -> Unit = {},
) {
    val keyboard = LocalSoftwareKeyboardController.current
    Box(modifier.background(MaterialTheme.colorScheme.surface)) {
        val row = Modifier.fillMaxWidth().height(56.dp).padding(horizontal = 4.dp)
        Row(
            if (state.editing) row.clearAndSetSemantics {} else row,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Surface(
                Modifier.weight(1f).height(44.dp)
                    .addressBounds(onAddressBounds)
                    .testTag("address-rest-capsule")
                    .graphicsLayer { alpha = if (!state.editing) 1f else if (reducedMotion) 1f - focusProgress() else 0f },
                shape = RoundedCornerShape(50),
                color = if (state.incognito) MiruTheme.colors.incognitoContainer else MaterialTheme.colorScheme.surfaceContainer,
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    if (state.url != "about:newtab") {
                        IconButton(onClick = onSiteInfo, enabled = !state.editing) {
                            Icon(
                                if (state.secure) Icons.Outlined.Lock else Icons.Outlined.Info,
                                stringResource(R.string.toolbar_site_info),
                                Modifier.size(20.dp),
                                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    } else {
                        Icon(Icons.Outlined.Search, null, Modifier.padding(start = 12.dp).size(20.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
                        Spacer(Modifier.width(8.dp))
                    }
                    Box(Modifier.weight(1f).fillMaxHeight().clickable(enabled = !state.editing, onClick = onEdit), contentAlignment = Alignment.CenterStart) {
                        Text(
                            if (state.url == "about:newtab") stringResource(R.string.toolbar_search_hint) else displayAddress(state.url),
                            style = MaterialTheme.typography.bodyLarge,
                            color = if (state.url == "about:newtab") MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.onSurface,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                    if (state.url != "about:newtab" && state.adblockAvailable) {
                        IconButton(onClick = onAdblock, enabled = !state.editing) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Icon(
                                    if(state.adblockNeedsReload) Icons.Outlined.WarningAmber else Icons.Outlined.Shield,
                                    stringResource(if(state.adblockNeedsReload) R.string.toolbar_adblock_reload else R.string.toolbar_adblock),
                                    Modifier.size(18.dp),
                                    tint = if(state.adblockNeedsReload) MaterialTheme.colorScheme.error else if (state.adblockAvailable && state.blockedCount > 0) MiruTheme.colors.adblock else MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                                if (state.adblockAvailable && state.blockedCount > 0) {
                                    AnimatedContent(state.blockedCount, transitionSpec = {
                                        (slideInVertically(tween(MiruMotion.short2)) { it } + fadeIn(tween(MiruMotion.short2))) togetherWith
                                            (slideOutVertically(tween(MiruMotion.short2)) { -it } + fadeOut(tween(MiruMotion.short2)))
                                    }, label = "blockedCount") { count ->
                                        Text(if (count > 999) "999+" else count.toString(), style = MaterialTheme.typography.labelSmall.copy(fontFeatureSettings = "tnum"))
                                    }
                                }
                            }
                        }
                    }
                }
            }
            Row(Modifier.graphicsLayer {
                val progress = focusProgress()
                alpha = 1f - progress
                val scale = if (reducedMotion) 1f else 1f - 0.2f * progress
                scaleX = scale; scaleY = scale
            }, verticalAlignment = Alignment.CenterVertically) {
                androidx.compose.animation.AnimatedVisibility(state.mediaCount > 0) {
                    TextButton(onClick = onMedia, enabled = !state.editing, contentPadding = PaddingValues(horizontal = 6.dp)) {
                        Icon(Icons.Outlined.PlayArrow, stringResource(R.string.toolbar_media), Modifier.size(20.dp))
                        AnimatedContent(state.mediaCount, label = "mediaCount") { Text(it.toString(), style = MaterialTheme.typography.labelMedium) }
                    }
                }
                val tabsDescription = stringResource(R.string.toolbar_tabs)
                val tabsCount = pluralStringResource(R.plurals.toolbar_tab_count, state.tabCount, state.tabCount)
                IconButton(onClick = onTabs, enabled = !state.editing, modifier = Modifier.semantics { contentDescription = tabsDescription; stateDescription = tabsCount }) {
                    Surface(shape = RoundedCornerShape(5.dp), border = androidx.compose.foundation.BorderStroke(1.7.dp, MaterialTheme.colorScheme.onSurface)) {
                        Box(Modifier.size(23.dp), contentAlignment = Alignment.Center) {
                            Text(
                                if (state.tabCount > 99) ":D" else state.tabCount.toString(),
                                style = MaterialTheme.typography.labelSmall.copy(fontFeatureSettings = "tnum"),
                                modifier = Modifier,
                            )
                        }
                    }
                }
                IconButton(onClick = onMenu, enabled = !state.editing) {
                    Box {
                        Icon(Icons.Outlined.MoreVert, stringResource(R.string.toolbar_menu))
                        if (state.downloading) Box(Modifier.align(Alignment.TopEnd).size(6.dp).background(MaterialTheme.colorScheme.primary, androidx.compose.foundation.shape.CircleShape))
                    }
                }
            }
        }
        if (state.editing) Row(row, verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = { keyboard?.hide(); onCancelEdit() }) {
                Box(contentAlignment = Alignment.Center) {
                    val previous = when {
                        state.url == "about:newtab" -> Icons.Outlined.Search
                        state.secure -> Icons.Outlined.Lock
                        else -> Icons.Outlined.Info
                    }
                    Icon(previous, null, Modifier.size(20.dp).graphicsLayer { alpha = 1f - focusProgress() })
                    Icon(
                        Icons.AutoMirrored.Outlined.ArrowBack, stringResource(R.string.toolbar_cancel_edit),
                        Modifier.graphicsLayer { alpha = focusProgress() },
                    )
                }
            }
            Box(Modifier.weight(1f).height(44.dp).addressBounds(onEditorBounds))
        }
        LoadingBar(state.loading && !state.editing, state.progress, Modifier.align(if (progressAtTop) Alignment.TopStart else Alignment.BottomStart))
    }
}

private fun Modifier.addressBounds(onBounds: (Rect) -> Unit) = onGloballyPositioned { coordinates ->
    val size = coordinates.size
    onBounds(Rect(coordinates.positionInRoot(), Size(size.width.toFloat(), size.height.toFloat())))
}

@Composable
fun MiruAddressField(
    state: ToolbarState,
    onTextChanged: (String) -> Unit,
    onSubmit: (String) -> Unit,
    onCompositionChanged: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
) {
    val keyboard = LocalSoftwareKeyboardController.current
    val searchDescription = stringResource(R.string.toolbar_search_hint)
    val focusRequester = remember { FocusRequester() }
    var field by remember {
        mutableStateOf(TextFieldValue(state.input, TextRange(0, state.input.length)))
    }
    var completedFrom by remember { mutableStateOf<String?>(null) }
    var resolvedText by remember { mutableStateOf<String?>(null) }
    var resolvedUrl by remember { mutableStateOf<String?>(null) }
    var mayComplete by remember { mutableStateOf(false) }
    var suppressed by remember { mutableStateOf<String?>(null) }
    fun complete(prefix: String) {
        val candidate=state.inlineAddress ?: return
        if(prefix.isEmpty() || candidate.length<=prefix.length || !candidate.startsWith(prefix,true))return
        val text=prefix+candidate.substring(prefix.length)
        field=TextFieldValue(text,TextRange(prefix.length,text.length))
        completedFrom=prefix;resolvedText=text;resolvedUrl=state.inlineDestination
    }
    LaunchedEffect(state.input) {
        if(field.text!=state.input && completedFrom!=state.input) {
            field=TextFieldValue(state.input,TextRange(state.input.length))
            completedFrom=null;resolvedText=null;resolvedUrl=null;mayComplete=false;suppressed=null
            onCompositionChanged(false)
        }
    }
    LaunchedEffect(state.inlineAddress,state.input) {
        if(mayComplete && suppressed!=state.input && field.composition==null && field.text==state.input && field.selection.collapsed && field.selection.end==field.text.length)complete(state.input)
    }
    LaunchedEffect(Unit) { focusRequester.requestFocus() }
    Surface(
        modifier.fillMaxSize(),
        shape = RoundedCornerShape(50),
        color = MaterialTheme.colorScheme.surface,
        border = androidx.compose.foundation.BorderStroke(2.dp, MaterialTheme.colorScheme.primary),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            BasicTextField(
                value = field,
                onValueChange = { next ->
                    val previous=completedFrom ?: field.text
                    val replacedAll=field.selection.min==0 && field.selection.max==field.text.length
                    val committed=field.composition!=null && next.composition==null
                    val atEnd=next.selection.collapsed && next.selection.end==next.text.length
                    mayComplete=next.composition==null && atEnd && (committed || replacedAll || next.text.length>previous.length && next.text.startsWith(previous))
                    suppressed=if(mayComplete)null else next.text
                    if(next.text!=resolvedText) { resolvedText=null;resolvedUrl=null }
                    field=next;completedFrom=null
                    onCompositionChanged(next.composition!=null)
                    onTextChanged(next.text)
                    if(mayComplete)complete(next.text)
                },
                modifier = Modifier.weight(1f).padding(start = 16.dp).focusRequester(focusRequester)
                    .semantics { contentDescription = searchDescription },
                singleLine = true,
                textStyle = MaterialTheme.typography.bodyLarge.copy(color = MaterialTheme.colorScheme.onSurface),
                cursorBrush = SolidColor(MaterialTheme.colorScheme.primary),
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri, imeAction = ImeAction.Go),
                keyboardActions = KeyboardActions(onGo = { keyboard?.hide(); onSubmit(if(field.text==resolvedText) resolvedUrl ?: field.text else field.text) }),
                decorationBox = { inner ->
                    if (field.text.isEmpty()) Text(stringResource(R.string.toolbar_search_hint), color = MaterialTheme.colorScheme.onSurfaceVariant)
                    inner()
                },
            )
            IconButton(onClick = { field = TextFieldValue("");completedFrom=null;resolvedText=null;resolvedUrl=null;mayComplete=false;suppressed="";onCompositionChanged(false);onTextChanged("") }) {
                Icon(Icons.Outlined.Close, stringResource(R.string.toolbar_clear_input))
            }
        }
    }
}

/** Overlays the toolbar edge, so starting or finishing a load never resizes the web content below. */
@Composable
private fun LoadingBar(loading: Boolean, progress: Int, modifier: Modifier) {
    val target by rememberUpdatedState(progress / 100f)
    val value = remember { Animatable(0f) }
    LaunchedEffect(loading) {
        if (loading) {
            value.snapTo(0.1f)
            snapshotFlow { target }.collect { value.animateTo(it.coerceAtLeast(value.value), spring(MiruMotion.springDamping, MiruMotion.springStiffness)) }
        } else if (value.value > 0f) {
            value.animateTo(1f, tween(MiruMotion.short2))
        }
    }
    // Hold at 100% for 150 ms, then fade out.
    val alpha by animateFloatAsState(
        if (loading) 1f else 0f,
        if (loading) tween(MiruMotion.short1) else tween(MiruMotion.short3, delayMillis = 150, easing = MiruMotion.standardAccelerate),
        label = "loading",
    )
    if (alpha > 0f) {
        LinearProgressIndicator(
            progress = { value.value },
            modifier = modifier.fillMaxWidth().height(2.dp).alpha(alpha),
            gapSize = 0.dp,
            drawStopIndicator = {},
        )
    }
}

private fun displayAddress(url: String): String {
    val uri = runCatching { Uri.parse(url) }.getOrNull() ?: return url
    val host = uri.host ?: return url
    val queryKey = when {
        host == "google.com" || host.endsWith(".google.com") || host == "bing.com" || host.endsWith(".bing.com") || host == "duckduckgo.com" -> "q"
        host == "baidu.com" || host.endsWith(".baidu.com") -> "wd"
        else -> null
    }
    return queryKey?.let { uri.getQueryParameter(it)?.takeIf(String::isNotBlank) } ?: host.removePrefix("www.")
}
