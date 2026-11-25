// Copyright (c) 2026 Ringophilia and xiaomao
// SPDX-License-Identifier: MPL-2.0
package app.miru.browser.engine

import android.content.Context
import java.io.File

/** Private, fixed configuration for the pinned Gecko version; no external/user configuration is read. */
internal object RuntimeConfiguration {
    private val contents = """
        prefs:
          "extensions.uBlock0@raymondhill.net.getAddons.cache.enabled": false
    """.trimIndent() + "\n"

    // AddonRepository's optional AMO description/screenshots lookup otherwise blocks local XPI
    // installation on an unavailable network. This leaves signature verification, the blocklist,
    // extension permissions, and add-on update verification at their Gecko defaults.
    fun prepare(context: Context): File = File(context.filesDir, "engine/runtime.yaml").apply {
        parentFile?.mkdirs()
        if (!exists() || readText() != contents) writeText(contents)
    }
}
