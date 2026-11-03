// Copyright (c) 2026 Ringophilia and xiaomao
// SPDX-License-Identifier: MPL-2.0
package app.miru.browser

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import android.widget.TextView

/** Separate test-APK Activity: reports only the synthetic link sent by these tests. */
class ExternalLinkFixtureActivity : Activity() {
    override fun onCreate(state: Bundle?) {
        super.onCreate(state)
        val grants = intent.flags and (Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION)
        val text = "Miru external fixture\nuri=${intent.dataString}\naction=${intent.action}\nbrowsable=${intent.hasCategory(Intent.CATEGORY_BROWSABLE)}\ngrants=$grants\nattack=${intent.hasExtra("attack")}\nfallback=${intent.hasExtra("browser_fallback_url")}"
        setContentView(TextView(this).apply { this.text = text; textSize = 18f; setPadding(24, 48, 24, 24) })
    }
}
