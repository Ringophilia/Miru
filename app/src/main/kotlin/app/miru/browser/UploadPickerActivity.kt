// Copyright (c) 2026 Ringophilia and xiaomao
// SPDX-License-Identifier: MPL-2.0
package app.miru.browser

import android.content.Intent
import android.os.Bundle
import android.view.Gravity
import android.view.WindowManager
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView
import androidx.activity.ComponentActivity
import androidx.activity.OnBackPressedCallback
import app.miru.browser.engine.BrowserFileUploads

/**
 * Non-exported result receiver. Provider URIs never enter ActivityResultRegistry or
 * a result Intent to MainActivity. Its transient URI grants end when this receiver
 * finishes after IO has produced document-owned files.
 */
class UploadPickerActivity : ComponentActivity() {
    private val uploads get() = (application as MiruApplication).container.browser.uploads
    private var token = ""

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        token = intent.getStringExtra(BrowserFileUploads.PICKER_TOKEN_EXTRA).orEmpty()
        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() { uploads.cancelPicker(token) }
        })
        if(uploads.pickerIsPrivate(token)) window.addFlags(WindowManager.LayoutParams.FLAG_SECURE)
        val spacing = (24 * resources.displayMetrics.density).toInt()
        setContentView(LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            setPadding(spacing, spacing, spacing, spacing)
            addView(ProgressBar(this@UploadPickerActivity))
            addView(TextView(this@UploadPickerActivity).apply {
                setText(R.string.upload_preparing)
                gravity = Gravity.CENTER
                setPadding(0, spacing, 0, spacing)
            })
            addView(Button(this@UploadPickerActivity).apply {
                setText(R.string.page_prompt_cancel)
                minimumHeight = (48 * resources.displayMetrics.density).toInt()
                setOnClickListener { uploads.cancelPicker(token) }
            })
        })
        if(token.isEmpty() || !uploads.attachPickerHost(this, token)) finish()
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        if(BuildConfig.DEBUG && requestCode == BrowserFileUploads.PICKER_REQUEST_CODE) {
            android.util.Log.d("MiruUploadResult", "ok=${resultCode == RESULT_OK} " +
                "dataIntentPresent=${data != null} dataUriPresent=${data?.data != null} " +
                "clipItems=${data?.clipData?.itemCount ?: 0}")
        }
        if(!uploads.onActivityResult(token, requestCode, resultCode, data)) super.onActivityResult(requestCode, resultCode, data)
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<String>, grantResults: IntArray) {
        if(!uploads.onPermissionsResult(token, requestCode, permissions, grantResults))
            super.onRequestPermissionsResult(requestCode, permissions, grantResults)
    }

    override fun onDestroy() {
        uploads.detachPickerHost(this, token)
        super.onDestroy()
    }
}
