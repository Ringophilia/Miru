// Adapted from Ringo-Cove's video_player_android plugin.
// Upstream license: feature/superres/licenses/Flutter-BSD-3-Clause.txt.
package app.miru.superres

/**
 * One pass of an mpv user shader (`//!HOOK` format). Anime4K ships its networks in this format;
 * the runtime binds textures and evaluates the size/condition expressions the same way mpv does.
 */
class HookPass(
    val desc: String,
    val hook: String,
    val binds: List<String>,
    val save: String?,
    val width: String?,
    val height: String?,
    val condition: String?,
    val body: String,
) {
    /** Texture written by this pass; unnamed saves replace the hooked texture. */
    val target: String get() = save ?: hook
}

object HookShader {
    fun parse(source: String): List<HookPass> {
        val passes = ArrayList<HookPass>()
        val lines = source.replace("\r", "").split('\n')
        var i = 0
        while (i < lines.size) {
            if (!lines[i].startsWith("//!")) {
                i++
                continue
            }
            val directives = LinkedHashMap<String, MutableList<String>>()
            while (i < lines.size && lines[i].startsWith("//!")) {
                val line = lines[i].removePrefix("//!").trim()
                val space = line.indexOf(' ')
                val key = if (space < 0) line else line.substring(0, space)
                val value = if (space < 0) "" else line.substring(space + 1).trim()
                directives.getOrPut(key.uppercase()) { ArrayList() }.add(value)
                i++
            }
            val body = StringBuilder()
            while (i < lines.size && !lines[i].startsWith("//!")) {
                body.append(lines[i]).append('\n')
                i++
            }
            val hook = directives["HOOK"]?.firstOrNull() ?: continue
            passes.add(
                HookPass(
                    desc = directives["DESC"]?.firstOrNull() ?: "",
                    hook = hook,
                    binds = directives["BIND"] ?: emptyList(),
                    save = directives["SAVE"]?.firstOrNull(),
                    width = directives["WIDTH"]?.firstOrNull(),
                    height = directives["HEIGHT"]?.firstOrNull(),
                    condition = directives["WHEN"]?.firstOrNull(),
                    body = body.toString(),
                ))
        }
        return passes
    }

    /**
     * Evaluates mpv's reverse-polish size expressions, e.g. `OUTPUT.w MAIN.w / 1.2 >`.
     * Unknown textures make the expression unusable, which skips the pass like mpv does.
     */
    fun eval(expr: String, size: (String) -> Pair<Int, Int>?): Double? {
        val stack = ArrayDeque<Double>()
        for (token in expr.trim().split(Regex("\\s+"))) {
            if (token.isEmpty()) continue
            val number = token.toDoubleOrNull()
            when {
                number != null -> stack.addLast(number)
                token.length == 1 && token in "+-*/><=" -> {
                    if (stack.size < 2) return null
                    val b = stack.removeLast()
                    val a = stack.removeLast()
                    stack.addLast(
                        when (token) {
                            "+" -> a + b
                            "-" -> a - b
                            "*" -> a * b
                            "/" -> a / b
                            ">" -> if (a > b) 1.0 else 0.0
                            "<" -> if (a < b) 1.0 else 0.0
                            else -> if (a == b) 1.0 else 0.0
                        })
                }
                token == "!" -> {
                    if (stack.isEmpty()) return null
                    stack.addLast(if (stack.removeLast() == 0.0) 1.0 else 0.0)
                }
                token.endsWith(".w") || token.endsWith(".width") ||
                    token.endsWith(".h") || token.endsWith(".height") -> {
                    val dot = token.lastIndexOf('.')
                    val dims = size(token.substring(0, dot)) ?: return null
                    stack.addLast(
                        (if (token[dot + 1] == 'w') dims.first else dims.second).toDouble())
                }
                else -> return null
            }
        }
        return if (stack.size == 1) stack.last() else null
    }
}
