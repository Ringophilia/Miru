// Copyright (c) 2026 Ringophilia and xiaomao
// SPDX-License-Identifier: MPL-2.0
package app.miru.browser.capability

import android.content.Context
import android.content.ContextWrapper
import android.util.Log
import androidx.media3.effect.R as EffectR
import androidx.test.platform.app.InstrumentationRegistry

/** Verify the actual R owner before GL; main/test dependency placement can change it. */
internal fun seekPreviewDecoderContext(): Context {
    val instrumentation = InstrumentationRegistry.getInstrumentation()
    val shaders = listOf(
        EffectR.raw.vertex_shader_transformation_es2 to "vertex_shader_transformation_es2",
        EffectR.raw.fragment_shader_copy_es2 to "fragment_shader_copy_es2",
    )
    val owner = listOf(instrumentation.targetContext, instrumentation.context).firstOrNull { candidate ->
        shaders.all { (id, name) -> runCatching {
            candidate.resources.getResourceTypeName(id) == "raw" &&
                candidate.resources.getResourceEntryName(id) == name &&
                candidate.resources.openRawResource(id).bufferedReader().use { it.readText() }.startsWith("#version")
        }.getOrDefault(false) }
    } ?: throw AssertionError("Neither main nor test resource table owns the required Media3 shader IDs")
    Log.i("MiruSeekPreviewProbe", "verified shader resource owner=${owner.packageName}")
    return object : ContextWrapper(instrumentation.targetContext) {
        override fun getResources() = owner.resources
        override fun getAssets() = owner.assets
        // MediaCodecVideoRenderer normalizes context; retain the verified resources.
        override fun getApplicationContext(): Context = this
    }
}
