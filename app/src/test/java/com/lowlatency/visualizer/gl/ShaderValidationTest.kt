package com.lowlatency.visualizer.gl

import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.io.File

/**
 * Headless shader validation — the safety net for the app's #1 "green build,
 * crashes on device" bug class.
 *
 * GLSL compiles at *runtime*, so a passing Gradle build proves nothing about the
 * 34 scenes' shaders; the only other check is swiping to each on a device. This
 * test extracts every embedded shader string from `src/main/java` and validates
 * each without a device, in two layers:
 *
 *  1. **Always-on (zero deps):** a GLSL-ES reserved-word denylist (the `sample`
 *     class of crash) plus structural sanity (`#version` present, balanced
 *     braces/parens). Pure text; tolerant of the `${...}` Kotlin interpolation
 *     some shaders use.
 *  2. **When available:** a full compile via `glslangValidator` (catches syntax,
 *     undeclared uniforms, type errors — everything). Skipped cleanly if the
 *     binary isn't on PATH, and skipped for shaders that use Kotlin interpolation
 *     (their final source only exists at runtime), so it never blocks a build.
 *
 * Install the full compiler with `brew install glslang` (or `apt install
 * glslang-tools`); CI should do the same to get layer 2.
 */
class ShaderValidationTest {

    private data class Shader(val file: File, val name: String, val source: String)

    // GLSL ES 3.00 words reserved for future use — all illegal as identifiers, so
    // any bare occurrence (after comments are stripped) is a real, device-only crash.
    private val reserved = setOf(
        "sample", "input", "output", "filter", "active", "common", "partition", "superp",
        "interface", "public", "static", "extern", "external", "union", "enum", "class",
        "template", "this", "namespace", "using", "resource", "goto", "inline", "noinline",
        "sizeof", "cast", "asm", "typedef", "double", "long", "short", "half", "fixed",
        "unsigned", "hvec2", "hvec3", "hvec4", "dvec2", "dvec3", "dvec4", "fvec2", "fvec3", "fvec4",
    )

    @Test
    fun everyEmbeddedShaderIsValid() {
        val srcRoot = locateSourceRoot()
        val shaders = extractShaders(srcRoot)
        assertTrue(
            "No shaders found under ${srcRoot.absolutePath} — extraction path is wrong.",
            shaders.isNotEmpty(),
        )

        val glslang = findGlslang()
        val problems = mutableListOf<String>()
        var compiled = 0

        for (s in shaders) {
            problems += lint(s)
            if (glslang != null && !s.source.contains('$')) {
                problems += glslangCompile(glslang, s)
                compiled++
            }
        }

        println(
            "[shader-validation] ${shaders.size} shaders linted; " +
                if (glslang == null) {
                    "glslangValidator not on PATH — layer 2 (full compile) skipped."
                } else {
                    "$compiled fully compiled via '$glslang' " +
                        "(${shaders.size - compiled} interpolated → lint only)."
                },
        )

        if (problems.isNotEmpty()) {
            fail("Shader validation found ${problems.size} problem(s):\n  " + problems.joinToString("\n  "))
        }
    }

    // ---- Layer 1: text lint -------------------------------------------------

    private fun lint(s: Shader): List<String> {
        val problems = mutableListOf<String>()
        val code = stripComments(s.source)
        val where = "${s.file.name} :: ${s.name}"

        if (!s.source.contains("#version")) {
            problems += "$where: missing #version directive"
        }
        for (w in reserved) {
            if (Regex("\\b$w\\b").containsMatchIn(code)) {
                problems += "$where: uses GLSL ES reserved word '$w' as an identifier"
            }
        }
        for ((open, close) in listOf('{' to '}', '(' to ')', '[' to ']')) {
            val delta = code.count { it == open } - code.count { it == close }
            if (delta != 0) {
                problems += "$where: unbalanced '$open$close' (delta $delta)"
            }
        }
        return problems
    }

    private fun stripComments(src: String): String =
        src.replace(Regex("/\\*.*?\\*/", RegexOption.DOT_MATCHES_ALL), " ")
            .replace(Regex("//[^\n]*"), " ")

    // ---- Layer 2: real compile ---------------------------------------------

    private fun findGlslang(): String? {
        for (bin in listOf("glslangValidator", "glslang")) {
            try {
                val p = ProcessBuilder(bin, "--version").redirectErrorStream(true).start()
                p.waitFor()
                return bin
            } catch (_: Exception) {
                // not on PATH — try the next name
            }
        }
        return null
    }

    private fun glslangCompile(bin: String, s: Shader): List<String> {
        val stage = if (s.source.contains("gl_Position")) "vert" else "frag"
        val tmp = File.createTempFile("shader_", ".$stage")
        return try {
            tmp.writeText(s.source)
            val p = ProcessBuilder(bin, tmp.path).redirectErrorStream(true).start()
            val out = p.inputStream.bufferedReader().readText()
            p.waitFor()
            if (p.exitValue() != 0) {
                listOf("${s.file.name} :: ${s.name}: glslang compile failed —\n    " +
                    out.trim().replace("\n", "\n    "))
            } else {
                emptyList()
            }
        } catch (e: Exception) {
            listOf("${s.file.name} :: ${s.name}: glslang invocation error — ${e.message}")
        } finally {
            tmp.delete()
        }
    }

    // ---- Extraction ---------------------------------------------------------

    private fun extractShaders(root: File): List<Shader> {
        val triple = Regex("\"\"\"(.*?)\"\"\"", RegexOption.DOT_MATCHES_ALL)
        val out = mutableListOf<Shader>()
        root.walkTopDown().filter { it.isFile && it.extension == "kt" }.forEach { f ->
            val text = f.readText()
            for (m in triple.findAll(text)) {
                val body = m.groupValues[1]
                if (!body.contains("#version")) continue
                out += Shader(f, shaderName(text, m.range.first), body)
            }
        }
        return out
    }

    /** Best-effort: the `val NAME =` immediately preceding the opening triple-quote. */
    private fun shaderName(text: String, quoteStart: Int): String {
        val before = text.substring(0, quoteStart).trimEnd()
        return Regex("(\\w+)\\s*=$").find(before)?.groupValues?.get(1) ?: "shader@$quoteStart"
    }

    private fun locateSourceRoot(): File {
        val rel = "src/main/java"
        (listOf(File(rel), File("app/$rel"))).firstOrNull { it.isDirectory }?.let { return it }
        var dir: File? = File(System.getProperty("user.dir") ?: ".")
        while (dir != null) {
            listOf(File(dir, rel), File(dir, "app/$rel")).firstOrNull { it.isDirectory }?.let { return it }
            dir = dir.parentFile
        }
        error("Could not locate src/main/java from ${System.getProperty("user.dir")}")
    }
}
