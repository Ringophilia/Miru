// Copyright (c) 2026 Ringophilia and xiaomao
// SPDX-License-Identifier: MPL-2.0
package app.miru.downloads

import android.content.Intent
import android.net.Uri
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.BackHandler
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import java.text.DateFormat
import java.util.Date
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

@OptIn(ExperimentalMaterial3Api::class, ExperimentalFoundationApi::class)
@Composable
fun DownloadsScreen(coordinator: DownloadCoordinator, onBack: () -> Unit, snackbarHostState:SnackbarHostState?=null) {
    val downloads by coordinator.downloads.collectAsState()
    val preferences by coordinator.settings.collectAsState()
    val context=LocalContext.current
    val scope=rememberCoroutineScope()
    val localSnackbar=remember { SnackbarHostState() }
    val snackbar=snackbarHostState?:localSnackbar
    var query by remember { mutableStateOf("") }
    var category by remember { mutableStateOf(DownloadCategory.ALL) }
    var selected by remember { mutableStateOf(setOf<String>()) }
    val hidden by coordinator.pendingDeletionIds.collectAsState()
    var pageMenu by remember { mutableStateOf(false) }
    var settingsVisible by remember { mutableStateOf(false) }
    var confirmDelete by remember { mutableStateOf<List<DownloadItem>?>(null) }
    var rename by remember { mutableStateOf<DownloadItem?>(null) }
    val deleted=stringResource(R.string.download_deleted)
    val undo=stringResource(R.string.download_undo)
    val filtered=downloads.filter { it.id !in hidden && (category==DownloadCategory.ALL || it.category==category) && (it.fileName.contains(query,true)||it.url.contains(query,true)) }
    fun delete(items:List<DownloadItem>,files:Boolean) {
        val ids=items.map { it.id }.toSet();selected-=ids
        val token=coordinator.stageDelete(items,files)
        snackbar.currentSnackbarData?.dismiss()
        scope.launch {
            val result=withTimeoutOrNull(6_000) { snackbar.showSnackbar(deleted,undo,duration=SnackbarDuration.Indefinite) }
            if(result==SnackbarResult.ActionPerformed)coordinator.undoDelete(token)
        }
    }
    BackHandler(enabled=settingsVisible){settingsVisible=false}
    if(settingsVisible) { DownloadSettingsScreen(coordinator) { settingsVisible=false }; return }
    BackHandler(enabled=selected.isNotEmpty()){selected=emptySet()}
    Scaffold(topBar={ TopAppBar(title={ Text(if(selected.isEmpty())stringResource(R.string.downloads_title) else stringResource(R.string.download_selected,selected.size)) },navigationIcon={ IconButton(onClick={ if(selected.isEmpty())onBack() else selected=emptySet() }) { Icon(Icons.AutoMirrored.Outlined.ArrowBack,stringResource(R.string.download_back)) } },actions={
        if(selected.isNotEmpty()) {
            IconButton(enabled=downloads.any { it.id in selected&&it.destUri!=null },onClick={coordinator.share(downloads.filter { it.id in selected&&it.destUri!=null })}){Icon(Icons.Outlined.Share,stringResource(R.string.download_share))}
            IconButton(onClick={ val rows=downloads.filter { it.id in selected }; if(preferences.deleteFileWithRecord && rows.any { it.destUri!=null })confirmDelete=rows else delete(rows,false) }) { Icon(Icons.Outlined.Delete,stringResource(R.string.download_delete)) }
        } else Box {
            IconButton(onClick={pageMenu=true}) { Icon(Icons.Outlined.MoreVert,stringResource(R.string.download_more)) }
            DropdownMenu(expanded=pageMenu,onDismissRequest={pageMenu=false}) {
                DropdownMenuItem(text={Text(stringResource(R.string.download_pause_all))},onClick={coordinator.pauseAll();pageMenu=false})
                DropdownMenuItem(text={Text(stringResource(R.string.download_resume_all))},onClick={coordinator.resumeAll();pageMenu=false})
                DropdownMenuItem(text={Text(stringResource(R.string.download_clear_completed))},onClick={delete(downloads.filter { it.status=="completed" },false);pageMenu=false})
                DropdownMenuItem(text={Text(stringResource(R.string.download_settings))},onClick={settingsVisible=true;pageMenu=false})
            }
        }
    }) },snackbarHost={if(snackbarHostState==null)SnackbarHost(snackbar)}) { padding ->
        Column(Modifier.fillMaxSize().padding(padding)) {
            OutlinedTextField(query,{query=it},Modifier.fillMaxWidth().padding(horizontal=16.dp),singleLine=true,placeholder={Text(stringResource(R.string.download_search))},leadingIcon={Icon(Icons.Outlined.Search,null)})
            Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal=16.dp,vertical=8.dp),horizontalArrangement=Arrangement.spacedBy(8.dp)) { DownloadCategory.entries.forEach { value ->
                FilterChip(selected=category==value,onClick={category=value},label={Text(categoryLabel(value))})
            } }
            if(filtered.isEmpty()) Column(Modifier.weight(1f).fillMaxWidth(),verticalArrangement=Arrangement.Center,horizontalAlignment=Alignment.CenterHorizontally) {
                Icon(Icons.Outlined.Download,null,Modifier.size(56.dp),tint=MaterialTheme.colorScheme.onSurfaceVariant)
                Spacer(Modifier.height(16.dp));Text(stringResource(if(query.isEmpty()&&category==DownloadCategory.ALL)R.string.download_empty else R.string.download_no_results))
            } else LazyColumn(Modifier.weight(1f)) {
                filtered.groupBy { DateFormat.getDateInstance().format(Date(it.createdAt)) }.forEach { (date,rows) ->
                    item(key="date-$date") { Text(date,Modifier.padding(horizontal=16.dp,vertical=12.dp),style=MaterialTheme.typography.labelLarge,color=MaterialTheme.colorScheme.primary) }
                    items(rows,key={it.id}) { item ->
                        var menu by remember(item.id) { mutableStateOf(false) }
                        val swipe=rememberSwipeToDismissBoxState(confirmValueChange={value->
                            if(value==SwipeToDismissBoxValue.EndToStart){if(preferences.deleteFileWithRecord&&item.destUri!=null)confirmDelete=listOf(item)else delete(listOf(item),false)}
                            false
                        })
                        SwipeToDismissBox(state=swipe,enableDismissFromStartToEnd=false,backgroundContent={Box(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.errorContainer).padding(24.dp),contentAlignment=Alignment.CenterEnd){Icon(Icons.Outlined.Delete,stringResource(R.string.download_delete),tint=MaterialTheme.colorScheme.onErrorContainer)}}){
                        ListItem(
                            modifier=Modifier.combinedClickable(onClick={ if(selected.isNotEmpty())selected=if(item.id in selected)selected-item.id else selected+item.id else if(item.status=="completed")coordinator.open(item) else menu=true },onLongClick={selected=selected+item.id}),
                            headlineContent={Text(item.fileName,maxLines=2,overflow=TextOverflow.Ellipsis)},
                            supportingContent={Column {
                                Text(downloadStatusLabel(context,item),style=MaterialTheme.typography.bodySmall.copy(fontFeatureSettings="tnum"))
                                if(item.status in setOf("running","paused","waiting_network","queued")) {
                                    Spacer(Modifier.height(8.dp));LinearProgressIndicator(progress={if(item.totalBytes>0)(item.downloadedBytes.toFloat()/item.totalBytes).coerceIn(0f,1f)else 0f},modifier=Modifier.fillMaxWidth(),drawStopIndicator={})
                                }
                            }},
                            leadingContent={if(selected.isNotEmpty())Checkbox(item.id in selected,{checked->selected=if(checked)selected+item.id else selected-item.id})else Icon(when(item.category){DownloadCategory.VIDEO->Icons.Outlined.Movie;DownloadCategory.AUDIO->Icons.Outlined.MusicNote;DownloadCategory.IMAGE->Icons.Outlined.Image;DownloadCategory.ARCHIVE->Icons.Outlined.FolderZip;else->Icons.Outlined.Description},null)},
                            trailingContent={Row {
                                if(item.status in setOf("running","queued","waiting_network"))IconButton(onClick={coordinator.pause(item.id)}){Icon(Icons.Outlined.Pause,stringResource(R.string.download_pause))}
                                else if(item.status in setOf("paused","failed"))IconButton(onClick={coordinator.resume(item.id)}){Icon(if(item.status=="failed")Icons.Outlined.Refresh else Icons.Outlined.PlayArrow,stringResource(R.string.download_resume))}
                                Box {
                                    IconButton(onClick={menu=true}){Icon(Icons.Outlined.MoreVert,stringResource(R.string.download_more))}
                                    DropdownMenu(menu,{menu=false}) {
                                        if(item.status=="completed") {
                                            DropdownMenuItem(text={Text(stringResource(R.string.download_open))},onClick={coordinator.open(item);menu=false})
                                            DropdownMenuItem(text={Text(stringResource(R.string.download_share))},onClick={coordinator.share(item);menu=false})
                                        }
                                        DropdownMenuItem(text={Text(stringResource(R.string.download_copy_link))},onClick={val clipboard=context.getSystemService(android.content.ClipboardManager::class.java);clipboard.setPrimaryClip(android.content.ClipData.newPlainText(item.fileName,item.url));menu=false})
                                        DropdownMenuItem(text={Text(stringResource(R.string.download_rename))},onClick={rename=item;menu=false})
                                        if(item.status in setOf("running","queued","paused","waiting_network","failed"))DropdownMenuItem(text={Text(stringResource(R.string.download_cancel))},onClick={coordinator.cancel(item.id);menu=false})
                                        DropdownMenuItem(text={Text(stringResource(R.string.download_delete))},onClick={menu=false;if(preferences.deleteFileWithRecord&&item.destUri!=null)confirmDelete=listOf(item)else delete(listOf(item),false)})
                                    }
                                }
                            }},
                        ) }
                    }
                }
            }
        }
    }
    confirmDelete?.let { items -> AlertDialog(onDismissRequest={confirmDelete=null},title={Text(stringResource(R.string.download_delete_files))},text={Text(stringResource(R.string.download_delete_files_detail))},dismissButton={TextButton(onClick={delete(items,false);confirmDelete=null}){Text(stringResource(R.string.download_record_only))}},confirmButton={TextButton(onClick={delete(items,true);confirmDelete=null}){Text(stringResource(R.string.download_delete))}}) }
    rename?.let { item -> var name by remember(item.id){mutableStateOf(item.fileName)};AlertDialog(onDismissRequest={rename=null},title={Text(stringResource(R.string.download_rename))},text={OutlinedTextField(name,{name=it},singleLine=true)},dismissButton={TextButton(onClick={rename=null}){Text(stringResource(R.string.download_cancel))}},confirmButton={TextButton(enabled=name.isNotBlank(),onClick={coordinator.rename(item.id,name);rename=null}){Text(stringResource(R.string.download_save))}}) }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DownloadConfirmSheet(request:DownloadRequest,onDismiss:()->Unit,onConfirm:(DownloadRequest)->Unit) {
    val context=LocalContext.current
    val defaults by DownloadCoordinator.get(context).settings.collectAsState()
    var name by remember(request){mutableStateOf(DownloadLogic.fileName(null,request.fileName?:request.url,request.mimeType))}
    var directory by remember(request){mutableStateOf(request.directoryUri?:defaults.directoryUri)}
    LaunchedEffect(defaults.directoryUri){if(directory==null)directory=defaults.directoryUri}
    val picker=rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()){uri->if(uri!=null){runCatching{context.contentResolver.takePersistableUriPermission(uri,Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION)};directory=uri.toString()}}
    ModalBottomSheet(onDismissRequest=onDismiss) {
        Column(Modifier.fillMaxWidth().padding(24.dp),verticalArrangement=Arrangement.spacedBy(16.dp)) {
            Text(stringResource(R.string.download_confirm_title),style=MaterialTheme.typography.titleLarge)
            OutlinedTextField(name,{name=it},Modifier.fillMaxWidth(),singleLine=true,label={Text(stringResource(R.string.download_filename))})
            Text(if(request.contentLength>=0)formatDownloadBytes(request.contentLength)else stringResource(R.string.download_unknown_size),style=MaterialTheme.typography.bodyMedium)
            TextButton(onClick={picker.launch(directory?.let(Uri::parse))}) { Icon(Icons.Outlined.FolderOpen,null);Spacer(Modifier.width(8.dp));Text(if(directory==null)stringResource(R.string.download_default_folder)else Uri.parse(directory).lastPathSegment.orEmpty()) }
            if(Build.VERSION.SDK_INT<29&&directory==null)Text(stringResource(R.string.download_choose_folder_required),color=MaterialTheme.colorScheme.error)
            if(request.isPrivate)Text(stringResource(R.string.download_private_warning),style=MaterialTheme.typography.bodySmall)
            Row(Modifier.fillMaxWidth(),horizontalArrangement=Arrangement.End){TextButton(onClick=onDismiss){Text(stringResource(R.string.download_cancel))};Spacer(Modifier.width(8.dp));Button(enabled=name.isNotBlank()&&(Build.VERSION.SDK_INT>=29||directory!=null),onClick={onConfirm(request.copy(fileName=name,directoryUri=directory))}){Text(stringResource(R.string.download_start))}}
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DownloadSettingsScreen(coordinator:DownloadCoordinator,onBack:()->Unit) {
    val preferences by coordinator.settings.collectAsState()
    val context=LocalContext.current
    val picker=rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()){uri->if(uri!=null){runCatching{context.contentResolver.takePersistableUriPermission(uri,Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION)};coordinator.updatePreferences{it.copy(directoryUri=uri.toString())}}}
    Scaffold(topBar={TopAppBar(title={Text(stringResource(R.string.download_settings))},navigationIcon={IconButton(onClick=onBack){Icon(Icons.AutoMirrored.Outlined.ArrowBack,stringResource(R.string.download_back))}})}){padding->
        Column(Modifier.padding(padding).verticalScroll(rememberScrollState())) {
            ListItem(headlineContent={Text(stringResource(R.string.download_ask))},trailingContent={Switch(preferences.askBeforeDownload,{checked->coordinator.updatePreferences{it.copy(askBeforeDownload=checked)}})})
            ListItem(headlineContent={Text(stringResource(R.string.download_location))},supportingContent={Text(preferences.directoryUri?.let { Uri.parse(it).lastPathSegment }.orEmpty().ifBlank { stringResource(R.string.download_default_folder) })},trailingContent={Icon(Icons.Outlined.FolderOpen,null)},modifier=Modifier.clickable{picker.launch(preferences.directoryUri?.let(Uri::parse))})
            if(preferences.directoryUri!=null&&Build.VERSION.SDK_INT>=29)TextButton(onClick={coordinator.updatePreferences{it.copy(directoryUri=null)}}){Text(stringResource(R.string.download_reset_folder))}
            Text(stringResource(R.string.download_concurrent,preferences.maxConcurrent),Modifier.padding(16.dp));Slider(preferences.maxConcurrent.toFloat(),{value->coordinator.updatePreferences{it.copy(maxConcurrent=value.toInt())}},Modifier.padding(horizontal=24.dp),valueRange=1f..5f,steps=3)
            Text(stringResource(R.string.download_threads,preferences.threadsPerFile),Modifier.padding(16.dp));Slider(preferences.threadsPerFile.toFloat(),{value->coordinator.updatePreferences{it.copy(threadsPerFile=value.toInt())}},Modifier.padding(horizontal=24.dp),valueRange=1f..8f,steps=6)
            ListItem(headlineContent={Text(stringResource(R.string.download_delete_with_record))},trailingContent={Switch(preferences.deleteFileWithRecord,{checked->coordinator.updatePreferences{it.copy(deleteFileWithRecord=checked)}})})
        }
    }
}

@Composable private fun categoryLabel(category:DownloadCategory):String=stringResource(when(category){DownloadCategory.ALL->R.string.download_all;DownloadCategory.VIDEO->R.string.download_video;DownloadCategory.AUDIO->R.string.download_audio;DownloadCategory.IMAGE->R.string.download_image;DownloadCategory.DOCUMENT->R.string.download_document;DownloadCategory.ARCHIVE->R.string.download_archive;DownloadCategory.PACKAGE->R.string.download_package;DownloadCategory.OTHER->R.string.download_other})
