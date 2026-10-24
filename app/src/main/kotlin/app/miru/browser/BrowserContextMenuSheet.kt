// Copyright (c) 2026 Ringophilia and xiaomao
// SPDX-License-Identifier: MPL-2.0
package app.miru.browser

import android.content.ClipData
import android.content.ClipDescription
import android.content.ClipboardManager
import android.content.Intent
import android.net.Uri
import android.os.PersistableBundle
import android.os.Build
import android.widget.Toast
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import app.miru.browser.engine.BrowserContextMenu
import kotlinx.coroutines.*

/** Context credentials/fetching stay in the injected browser owner, never in a URI or Intent. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun BrowserContextMenuSheet(
    request: BrowserContextMenu,
    onDismiss: () -> Unit,
    onOpenTab: (String, Boolean) -> Unit,
    onDownload: (BrowserContextMenu, String) -> Unit,
    onPlayVideo: (BrowserContextMenu) -> Unit,
    loadImage: suspend (BrowserContextMenu) -> ContextImagePayload?,
    isCurrent: (BrowserContextMenu) -> Boolean,
    onMessage: ((String) -> Unit)? = null,
) {
    val context = LocalContext.current
    val resources = LocalResources.current
    val scope = rememberCoroutineScope()
    var busy by remember(request.token) { mutableStateOf(false) }
    var transfer by remember(request.token) { mutableStateOf<Job?>(null) }
    DisposableEffect(request.token) { onDispose { transfer?.cancel() } }
    val clipboard = remember(context) { context.getSystemService(ClipboardManager::class.java) }
    fun message(id: Int) { val text = resources.getString(id); onMessage?.invoke(text) ?: Toast.makeText(context, text, Toast.LENGTH_SHORT).show() }
    fun sensitive(clip: ClipData): ClipData = clip.apply {
        if(request.isPrivate && Build.VERSION.SDK_INT >= 33) description.extras = PersistableBundle().apply { putBoolean(ClipDescription.EXTRA_IS_SENSITIVE, true) }
    }
    fun action(block: () -> Unit) {
        if(busy) return
        if(!isCurrent(request)) { message(R.string.context_expired); onDismiss(); return }
        runCatching { block(); onDismiss() }.onFailure { message(R.string.context_action_failed) }
    }
    fun copyText(text: String) = action {
        clipboard.setPrimaryClip(sensitive(ClipData.newPlainText(resources.getString(R.string.context_clipboard_label), text)))
        message(R.string.context_copied)
    }
    fun image(share: Boolean) {
        if(busy) return
        busy = true
        transfer = scope.launch {
            var uri: Uri? = null
            var published = false
            try {
                if(!isCurrent(request)) { message(R.string.context_expired); return@launch }
                val payload = withTimeout(20_000) { loadImage(request) } ?: throw IllegalArgumentException("Image unavailable")
                ensureActive()
                if(!isCurrent(request)) { message(R.string.context_expired); return@launch }
                uri = withContext(Dispatchers.IO) { ContextImageStore.put(context, payload) }
                ensureActive()
                if(!isCurrent(request)) { message(R.string.context_expired); return@launch }
                val target = checkNotNull(uri)
                val clip = sensitive(ClipData.newUri(context.contentResolver, resources.getString(R.string.context_image_label), target))
                if(share) {
                    val send = Intent(Intent.ACTION_SEND).setType(context.contentResolver.getType(target))
                        .putExtra(Intent.EXTRA_STREAM, target).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION).apply { clipData = clip }
                    context.startActivity(Intent.createChooser(send, null).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION).apply { clipData = clip })
                } else { clipboard.setPrimaryClip(clip); message(R.string.context_copied) }
                published = true
                onDismiss()
            } catch(cancelled: CancellationException) { throw cancelled }
            catch(_: Exception) { message(R.string.context_image_failed) }
            finally { if(!published) uri?.let(ContextImageStore::remove); busy = false }
        }
    }
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)) {
        Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(horizontal = 16.dp).padding(bottom = 24.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(request.linkText?.takeIf { it.isNotBlank() } ?: request.title?.takeIf { it.isNotBlank() }
                ?: stringResource(if(request.imageUrl != null) R.string.context_image_label else if(request.videoUrl != null) R.string.context_video_label else R.string.context_link_label),
                style = MaterialTheme.typography.titleMedium, maxLines = 2, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp))
            val address = request.linkUrl ?: request.imageUrl ?: request.videoUrl
            address?.let { value ->
                val uri = Uri.parse(value)
                if(uri.scheme == "http" || uri.scheme == "https") Text(uri.host.orEmpty() + uri.path.orEmpty().take(200),
                    Modifier.padding(horizontal = 12.dp), style = MaterialTheme.typography.bodySmall, maxLines = 2, overflow = TextOverflow.Ellipsis)
            }
            if(busy) { LinearProgressIndicator(Modifier.fillMaxWidth()); Text(stringResource(R.string.context_preparing_image), Modifier.padding(12.dp)) }
            request.linkUrl?.let { link ->
                ContextAction(R.string.context_new_tab, "context_new_tab", !busy) { action { onOpenTab(link, request.isPrivate) } }
                ContextAction(R.string.context_private_tab, "context_private_tab", !busy) { action { onOpenTab(link, true) } }
                ContextAction(R.string.context_copy_link, "context_copy_link", !busy) { copyText(link) }
                ContextAction(R.string.context_copy_text, "context_copy_text", !busy && !request.linkText.isNullOrBlank()) { copyText(request.linkText.orEmpty()) }
                ContextAction(R.string.context_download_link, "context_download_link", !busy) { action { onDownload(request, link) } }
                ContextAction(R.string.context_share_link, "context_share_link", !busy) { action {
                    context.startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, link), null))
                } }
            }
            request.imageUrl?.let { imageUrl ->
                if(request.linkUrl != null) HorizontalDivider()
                ContextAction(R.string.context_open_image, "context_open_image", !busy) { action { onOpenTab(imageUrl, request.isPrivate) } }
                ContextAction(R.string.context_download_image, "context_download_image", !busy) { action { onDownload(request, imageUrl) } }
                ContextAction(R.string.context_copy_image, "context_copy_image", !busy) { image(false) }
                ContextAction(R.string.context_share_image, "context_share_image", !busy) { image(true) }
            }
            request.videoUrl?.let { videoUrl ->
                if(request.linkUrl != null || request.imageUrl != null) HorizontalDivider()
                ContextAction(R.string.context_play_video, "context_play_video", !busy) { action { onPlayVideo(request) } }
                if(request.linkUrl == null) {
                    ContextAction(R.string.context_download_video, "context_download_video", !busy) { action { onDownload(request, videoUrl) } }
                    ContextAction(R.string.context_copy_video, "context_copy_video", !busy) { copyText(videoUrl) }
                }
            }
        }
    }
}

@Composable
private fun ContextAction(label: Int, tag: String, enabled: Boolean, onClick: () -> Unit) {
    TextButton(onClick = onClick, enabled = enabled, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp).testTag(tag)) {
        Text(stringResource(label), Modifier.fillMaxWidth())
    }
}
