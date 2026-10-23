// Copyright (c) 2026 Ringophilia and xiaomao
// SPDX-License-Identifier: MPL-2.0
package app.miru.superres

import java.io.File
import org.junit.Assert.*
import org.junit.Test

class HookShaderTest {
    @Test fun parsesBindsAndConditionalDimensions() {
        val passes = HookShader.parse("""
            //!DESC test
            //!HOOK MAIN
            //!BIND HOOKED
            //!BIND NATIVE
            //!SAVE CONV
            //!WIDTH HOOKED.w 2 *
            //!HEIGHT HOOKED.h 2 *
            //!WHEN OUTPUT.w MAIN.w / 1.2 >
            vec4 hook() { return HOOKED_tex(HOOKED_pos); }
        """.trimIndent())
        assertEquals(1, passes.size)
        assertEquals(listOf("HOOKED", "NATIVE"), passes[0].binds)
        assertEquals("CONV", passes[0].target)
        val size = { name: String -> when(name) { "OUTPUT" -> 1080 to 1080; "MAIN", "HOOKED" -> 540 to 540; else -> null } }
        assertEquals(1080.0, HookShader.eval(passes[0].width!!, size)!!, .001)
        assertEquals(1.0, HookShader.eval(passes[0].condition!!, size)!!, .001)
        assertNull(HookShader.eval("UNKNOWN.w", size))
    }

    @Test fun unchangedAssetsContainFullCnnNetworksAndFsrPasses() {
        val assets = File("src/main/assets/anime4k").takeIf { it.isDirectory }
            ?: File("feature/superres/src/main/assets/anime4k")
        val counts = mapOf("Clamp_Highlights" to 3, "Restore_CNN_S" to 4, "Restore_CNN_M" to 8,
            "Restore_CNN_L" to 9, "Upscale_CNN_x2_S" to 5, "Upscale_CNN_x2_M" to 9,
            "Upscale_CNN_x2_L" to 10, "FSR_EASU" to 1, "FSR_RCAS" to 1)
        counts.forEach { (name, count) ->
            val source = File(assets, "$name.glsl").readText()
            val passes = HookShader.parse(source)
            assertEquals(name, count, passes.size)
            assertTrue(name, passes.all { it.body.contains("hook()") })
        }
        assertTrue(File(assets, "LICENSE").readText().contains("bloc97"))
        assertTrue(File(assets, "FSR_LICENSE").readText().contains("Advanced Micro Devices"))
    }
}
