// Copyright (c) 2026 Ringophilia and xiaomao
// SPDX-License-Identifier: MPL-2.0
package app.miru.browser

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import app.miru.browser.engine.BrowserAuthLevel
import app.miru.browser.engine.BrowserAuthMethod
import app.miru.browser.engine.BrowserPromptButton
import app.miru.browser.engine.BrowserPromptKind
import app.miru.browser.engine.BrowserPromptState
import app.miru.browser.engine.BrowserPrompts
import app.miru.browser.engine.BrowserFileUploads
import app.miru.browser.engine.BrowserUploadPhase
import app.miru.browser.engine.BrowserUploadSource

/** All drafts remain in this composition; private/form/password state is never saved. */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
internal fun BrowserPromptDialog(state: BrowserPromptState, prompts: BrowserPrompts, uploads: BrowserFileUploads) {
    val upload by uploads.state.collectAsState()
    val uploadBusy = upload.phase != BrowserUploadPhase.IDLE
    var text by remember(state.token) { mutableStateOf(state.inputValue) }
    var username by remember(state.token) { mutableStateOf(state.userName) }
    var password by remember(state.token) { mutableStateOf("") }
    var stopDialogs by remember(state.token) { mutableStateOf(false) }
    val allowMore = !stopDialogs
    fun dismiss() {
        when(state.kind) {
            BrowserPromptKind.BEFORE_UNLOAD -> prompts.answerBeforeUnload(state.token, leave = false)
            BrowserPromptKind.REPOST -> prompts.answerRepost(state.token, resubmit = false)
            BrowserPromptKind.FOLDER_CONFIRM -> prompts.answerFolderUpload(state.token, allow = false)
            else -> prompts.dismiss(state.token, allowMoreDialogs = allowMore)
        }
    }
    val heading = when(state.kind) {
        BrowserPromptKind.BEFORE_UNLOAD -> stringResource(R.string.page_prompt_leave_title)
        BrowserPromptKind.REPOST -> stringResource(R.string.page_prompt_repost_title)
        BrowserPromptKind.FILE -> stringResource(R.string.upload_title)
        BrowserPromptKind.FOLDER -> stringResource(R.string.upload_folder_title)
        BrowserPromptKind.FOLDER_CONFIRM -> stringResource(R.string.upload_folder_confirm_title)
        BrowserPromptKind.AUTHENTICATION -> stringResource(if(state.authMethod == BrowserAuthMethod.PROXY)
            R.string.page_prompt_proxy_title else R.string.page_prompt_auth_title)
        else -> state.title.ifBlank { stringResource(R.string.page_prompt_title) }
    }
    val message = when(state.kind) {
        BrowserPromptKind.BEFORE_UNLOAD -> stringResource(R.string.page_prompt_leave_detail)
        BrowserPromptKind.REPOST -> stringResource(R.string.page_prompt_repost_detail)
        BrowserPromptKind.FOLDER_CONFIRM -> stringResource(R.string.upload_folder_confirm_detail, state.folderName)
        else -> state.message
    }
    ModalBottomSheet(onDismissRequest = ::dismiss) {
        Column(Modifier.fillMaxWidth().testTag("browser_prompt_dialog").verticalScroll(rememberScrollState()).padding(horizontal = 24.dp).padding(bottom = 32.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)) {
            Text(state.origin ?: stringResource(R.string.page_prompt_unknown_origin), style = MaterialTheme.typography.labelLarge)
            state.topOrigin?.takeIf { it != state.origin }?.let {
                Text(stringResource(R.string.page_prompt_embedded, it), style = MaterialTheme.typography.bodySmall)
            }
            Text(heading, style = MaterialTheme.typography.titleLarge)
            if(message.isNotBlank()) Text(message, style = MaterialTheme.typography.bodyLarge)
            when(state.kind) {
                BrowserPromptKind.TEXT -> OutlinedTextField(value = text, onValueChange = { text = it },
                    label = { Text(state.inputLabel.ifBlank { stringResource(R.string.page_prompt_input) }) },
                    modifier = Modifier.fillMaxWidth().testTag("page_prompt_text"))
                BrowserPromptKind.AUTHENTICATION -> {
                    if(state.isCrossOrigin) Text(stringResource(R.string.page_prompt_auth_cross_origin), style = MaterialTheme.typography.bodySmall)
                    if(state.authLevel == BrowserAuthLevel.NONE) Text(stringResource(R.string.page_prompt_auth_unencrypted), style = MaterialTheme.typography.bodySmall)
                    if(state.previousFailed) Text(stringResource(R.string.page_prompt_auth_failed), color = MaterialTheme.colorScheme.error)
                    if(!state.onlyShowPassword) OutlinedTextField(username, { username = it }, singleLine = true,
                        label = { Text(stringResource(R.string.page_prompt_username)) },
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Text),
                        modifier = Modifier.fillMaxWidth().testTag("page_prompt_username"))
                    OutlinedTextField(password, { password = it }, singleLine = true,
                        label = { Text(stringResource(R.string.page_prompt_password)) },
                        visualTransformation = PasswordVisualTransformation(),
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                        modifier = Modifier.fillMaxWidth().testTag("page_prompt_password"))
                }
                BrowserPromptKind.FILE, BrowserPromptKind.FOLDER -> {
                    if(uploadBusy) Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
                        Text(stringResource(if(upload.phase == BrowserUploadPhase.PREPARING) R.string.upload_preparing else R.string.upload_waiting))
                    }
                    val sources = if(state.kind == BrowserPromptKind.FILE) uploads.captureSources(state.fileMimeTypes) else emptySet()
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        for(source in sources) OutlinedButton(enabled = !uploadBusy,
                            onClick = { uploads.choose(state.token, source) }, modifier = Modifier.testTag("page_prompt_capture_${source.name.lowercase()}")) {
                            Text(stringResource(when(source) {
                                BrowserUploadSource.PHOTO -> R.string.upload_photo
                                BrowserUploadSource.VIDEO -> R.string.upload_video
                                BrowserUploadSource.AUDIO -> R.string.upload_audio
                                else -> R.string.upload_choose
                            }))
                        }
                    }
                }
                else -> Unit
            }
            // Gecko 158 never sets hasShownManyDialogs. Offer the document policy
            // directly so a page cannot trap the user in repeated JS dialogs.
            if(state.kind in setOf(BrowserPromptKind.ALERT, BrowserPromptKind.CONFIRM, BrowserPromptKind.TEXT)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Checkbox(stopDialogs, { stopDialogs = it }, modifier = Modifier.testTag("page_prompt_stop_dialogs"))
                    Text(stringResource(R.string.page_prompt_stop_dialogs), style = MaterialTheme.typography.bodyMedium)
                }
            }
            FlowRow(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                if(state.kind == BrowserPromptKind.CONFIRM && state.neutralButtonTitle.isNotBlank()) {
                    TextButton(onClick = { prompts.answerConfirm(state.token, BrowserPromptButton.NEUTRAL, allowMore) },
                        modifier = Modifier.testTag("page_prompt_neutral")) { Text(state.neutralButtonTitle) }
                }
                if(state.kind != BrowserPromptKind.ALERT) TextButton(onClick = {
                    if(state.kind == BrowserPromptKind.CONFIRM) prompts.answerConfirm(state.token, BrowserPromptButton.NEGATIVE, allowMore)
                    else dismiss()
                }, modifier = Modifier.testTag("page_prompt_cancel")) {
                    Text(when(state.kind) {
                        BrowserPromptKind.BEFORE_UNLOAD -> stringResource(R.string.page_prompt_stay)
                        BrowserPromptKind.CONFIRM -> state.negativeButtonTitle.ifBlank { stringResource(R.string.page_prompt_cancel) }
                        else -> stringResource(R.string.page_prompt_cancel)
                    })
                }
                FilledTonalButton(enabled = state.kind !in setOf(BrowserPromptKind.FILE, BrowserPromptKind.FOLDER) || !uploadBusy, onClick = {
                    when(state.kind) {
                        BrowserPromptKind.ALERT -> prompts.acknowledgeAlert(state.token, allowMore)
                        BrowserPromptKind.CONFIRM -> prompts.answerConfirm(state.token, BrowserPromptButton.POSITIVE, allowMore)
                        BrowserPromptKind.TEXT -> prompts.answerText(state.token, text, allowMore)
                        BrowserPromptKind.BEFORE_UNLOAD -> prompts.answerBeforeUnload(state.token, leave = true)
                        BrowserPromptKind.REPOST -> prompts.answerRepost(state.token, resubmit = true)
                        BrowserPromptKind.AUTHENTICATION -> prompts.authenticate(state.token, username, password)
                        BrowserPromptKind.FILE -> uploads.choose(state.token)
                        BrowserPromptKind.FOLDER -> uploads.choose(state.token)
                        BrowserPromptKind.FOLDER_CONFIRM -> prompts.answerFolderUpload(state.token, allow = true)
                    }
                }, modifier = Modifier.testTag(when(state.kind) {
                    BrowserPromptKind.FILE -> "page_prompt_file_choose"
                    BrowserPromptKind.FOLDER -> "page_prompt_folder_choose"
                    BrowserPromptKind.FOLDER_CONFIRM -> "page_prompt_folder_confirm"
                    else -> "page_prompt_confirm"
                })) {
                    Text(when(state.kind) {
                        BrowserPromptKind.BEFORE_UNLOAD -> stringResource(R.string.page_prompt_leave)
                        BrowserPromptKind.REPOST -> stringResource(R.string.page_prompt_resubmit)
                        BrowserPromptKind.CONFIRM -> state.positiveButtonTitle.ifBlank { stringResource(R.string.page_prompt_confirm) }
                        BrowserPromptKind.FILE -> stringResource(R.string.upload_choose)
                        BrowserPromptKind.FOLDER -> stringResource(R.string.upload_folder_choose)
                        BrowserPromptKind.FOLDER_CONFIRM -> stringResource(R.string.upload_folder_allow)
                        else -> stringResource(R.string.page_prompt_confirm)
                    })
                }
            }
        }
    }
}
