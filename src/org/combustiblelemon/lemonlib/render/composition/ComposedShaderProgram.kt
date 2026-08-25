package org.combustiblelemon.lemonlib.render.composition

import com.fs.starfarer.api.Global
import com.fs.starfarer.api.combat.ViewportAPI
import org.combustiblelemon.lemonlib.render.getShaderCode
import org.lwjgl.opengl.GL20.GL_COMPILE_STATUS
import org.lwjgl.opengl.GL20.GL_FRAGMENT_SHADER
import org.lwjgl.opengl.GL20.GL_LINK_STATUS
import org.lwjgl.opengl.GL20.GL_VERTEX_SHADER
import org.lwjgl.opengl.GL11.GL_FALSE
import org.lwjgl.opengl.GL20.glAttachShader
import org.lwjgl.opengl.GL20.glCompileShader
import org.lwjgl.opengl.GL20.glCreateProgram
import org.lwjgl.opengl.GL20.glCreateShader
import org.lwjgl.opengl.GL20.glDeleteProgram
import org.lwjgl.opengl.GL20.glDeleteShader
import org.lwjgl.opengl.GL20.glGetProgramInfoLog
import org.lwjgl.opengl.GL20.glGetProgrami
import org.lwjgl.opengl.GL20.glGetShaderInfoLog
import org.lwjgl.opengl.GL20.glGetShaderi
import org.lwjgl.opengl.GL20.glLinkProgram
import org.lwjgl.opengl.GL20.glShaderSource

/**
 * A shader program assembled from composable [ShaderLayer] modules.
 *
 * The base fragment template contains shared uniforms/helpers, the insertion
 * marker `// {{LAYER_MODULES}}`, and the orchestration code that calls layer
 * functions in the intended order. At build time each layer contributes its
 * GLSL module (or its neutral stub, when disabled) at the marker, in layer-list
 * order, and the result is compiled into a single program.
 *
 * This class owns only the program lifecycle. The owning shader keeps its own
 * VAO/VBO, render loop, GL state handling, and shared uniform uploads.
 *
 * Debugging:
 * - Any subset of layers can be enabled ([setEnabledLayers], or globally via
 *   [CompositionDebug] / the `ShaderLayers` console command); disabled layers
 *   compile as stubs and skip uniform uploads.
 * - Fragment compile errors are translated back to module file + local line,
 *   and the assembled source is dumped line-numbered to the log.
 *
 * Construction compiles eagerly, so instances must be created on the GL thread
 * (combat plugin construction/render — same thread Starsector renders on).
 *
 * @param systemName registry key for debug tooling, e.g. "shield"
 * @param placeholders text replacements applied to the assembled source AFTER
 *   concatenation (so occurrences inside modules resolve too). Values must be
 *   single-line, or the error line map would drift.
 */
class ComposedShaderProgram(
    val systemName: String,
    private val vertexShaderFile: String,
    private val baseFragmentFile: String,
    private val layers: List<ShaderLayer>,
    private val placeholders: Map<String, String> = mapOf()
) {
    var programId: Int = 0
        private set

    private val allLayerNames = layers.map { it.layerName }

    // Which layers render. Disabled layers compile as neutral stubs and skip uploads.
    private var enabledLayers: Set<String> = allLayerNames.toSet()

    // Set of layers the current program was compiled with
    private var compiledLayers: Set<String> = setOf()

    // Maps assembled-fragment-source line numbers back to module files
    private data class SourceSegment(val name: String, val startLine: Int)

    private var fragmentSegments: List<SourceSegment> = listOf()
    private var lastAssembledFragment: String = ""

    init {
        for ((_, value) in placeholders) {
            require('\n' !in value) { "Placeholder values must be single-line (error line map would drift)" }
        }
        build()
        CompositionDebug.registerSystem(systemName, allLayerNames)
    }

    fun getLayerNames(): List<String> {
        return allLayerNames
    }

    /**
     * Debug: render only the given layers; the rest compile as neutral stubs.
     * Empty or unknown-only set coerces to all layers. The program rebuild
     * happens lazily at the next [ensureUpToDate] call (GL thread).
     */
    fun setEnabledLayers(names: Set<String>) {
        val valid = names.intersect(allLayerNames.toSet())
        enabledLayers = if (valid.isEmpty()) allLayerNames.toSet() else valid
    }

    /**
     * Pick up debug layer-set changes ([CompositionDebug] override, or a prior
     * [setEnabledLayers] call) and rebuild the program if needed. The owner
     * must call this at the start of its render pass — the only point
     * guaranteed to be on the GL thread.
     */
    fun ensureUpToDate() {
        val override = CompositionDebug.enabledLayersFor(systemName)
        if (override != null) {
            setEnabledLayers(override)
        } else {
            enabledLayers = allLayerNames.toSet()
        }
        if (enabledLayers != compiledLayers) {
            if (programId != 0) {
                glDeleteProgram(programId)
                programId = 0
            }
            build()
        }
    }

    /**
     * Upload the uniforms of all enabled layers, in layer-list order. Called
     * with the program already bound. Disabled layers are skipped — their
     * stubs declare no uniforms, so uploads would go to location -1.
     */
    fun uploadLayerUniforms(viewport: ViewportAPI) {
        for (layer in layers) {
            if (layer.layerName in enabledLayers) {
                layer.uploadUniforms(programId, viewport)
            }
        }
    }

    /** Forward a CPU-side time step to every layer. */
    fun advance(deltaTime: Float) {
        for (layer in layers) {
            layer.advance(deltaTime)
        }
    }

    fun dispose() {
        if (programId != 0) {
            glDeleteProgram(programId)
            programId = 0
        }
    }

    /**
     * Assemble the fragment shader source: base template + layer modules
     * concatenated in layer-list order at the insertion marker. Disabled
     * layers contribute their neutral stub instead of the module file.
     * Placeholders are replaced after concatenation (they never change line
     * counts, so the segment map built here stays valid).
     */
    private fun assembleFragmentSource(): String {
        val base = getShaderCode(baseFragmentFile)
        val marker = "// {{LAYER_MODULES}}"
        val markerIndex = base.indexOf(marker)
        if (markerIndex < 0) {
            throw RuntimeException("[$systemName] $baseFragmentFile is missing the $marker insertion marker")
        }

        // Named source pieces, in final order
        val pieces = mutableListOf<Pair<String, String>>()
        pieces.add(Pair("$baseFragmentFile (header)", base.substring(0, markerIndex)))
        for (layer in layers) {
            if (layer.layerName in enabledLayers) {
                pieces.add(Pair(layer.moduleFile.substringAfterLast('/'), getShaderCode(layer.moduleFile) + "\n"))
            } else {
                pieces.add(Pair("${layer.layerName} (stub)", layer.stubSource + "\n"))
            }
        }
        pieces.add(Pair("$baseFragmentFile (footer)", base.substring(markerIndex + marker.length)))

        // Build source and the line map in one pass
        val segments = mutableListOf<SourceSegment>()
        val assembled = StringBuilder()
        var currentLine = 1
        for ((name, text) in pieces) {
            segments.add(SourceSegment(name, currentLine))
            assembled.append(text)
            currentLine += text.count { it == '\n' }
        }

        fragmentSegments = segments
        var source = assembled.toString()
        for ((key, value) in placeholders) {
            source = source.replace(key, value)
        }
        lastAssembledFragment = source
        return source
    }

    /**
     * Translate driver info-log line numbers (which point into the assembled
     * source) back to module file + local line. Handles the two dominant log
     * formats: NVIDIA `0(123) : error ...` and AMD/Intel/Mesa `ERROR: 0:123: ...`.
     */
    private fun translateShaderErrors(infoLog: String): String {
        val lineRef = Regex("""0[:(](\d+)\)?""")
        val translated = infoLog.lines().mapNotNull { logLine ->
            val match = lineRef.find(logLine) ?: return@mapNotNull null
            val globalLine = match.groupValues[1].toIntOrNull() ?: return@mapNotNull null
            val segment = fragmentSegments.lastOrNull { it.startLine <= globalLine }
                ?: return@mapNotNull null
            "${segment.name}:${globalLine - segment.startLine + 1}  [$logLine]"
        }
        if (translated.isEmpty()) return infoLog
        return "Translated to module files:\n" + translated.joinToString("\n") +
                "\n\nOriginal driver log:\n" + infoLog
    }

    private fun logAssembledSource() {
        val numbered = lastAssembledFragment.lines()
            .mapIndexed { i, l -> "${i + 1}\t$l" }
            .joinToString("\n")
        Global.getLogger(this.javaClass).error("[$systemName] Assembled fragment shader source:\n$numbered")
    }

    private fun build() {
        // Create and compile vertex shader
        val vertexShader = glCreateShader(GL_VERTEX_SHADER)
        glShaderSource(vertexShader, getShaderCode(vertexShaderFile))
        glCompileShader(vertexShader)

        if (glGetShaderi(vertexShader, GL_COMPILE_STATUS) == GL_FALSE) {
            val error = glGetShaderInfoLog(vertexShader, 4096)
            throw RuntimeException("[$systemName] Vertex shader compilation failed: $error")
        }

        // Create and compile the assembled fragment shader
        val fragmentShader = glCreateShader(GL_FRAGMENT_SHADER)
        glShaderSource(fragmentShader, assembleFragmentSource())
        glCompileShader(fragmentShader)

        // Translate driver line numbers back to module files and dump the
        // assembled source to the log on failure
        if (glGetShaderi(fragmentShader, GL_COMPILE_STATUS) == GL_FALSE) {
            val error = glGetShaderInfoLog(fragmentShader, 4096)
            logAssembledSource()
            throw RuntimeException("[$systemName] Fragment shader compilation failed:\n${translateShaderErrors(error)}")
        }

        // Create shader program
        programId = glCreateProgram()
        glAttachShader(programId, vertexShader)
        glAttachShader(programId, fragmentShader)
        glLinkProgram(programId)

        if (glGetProgrami(programId, GL_LINK_STATUS) == GL_FALSE) {
            val error = glGetProgramInfoLog(programId, 4096)
            throw RuntimeException("[$systemName] Shader program linking failed: $error")
        }

        // Shaders are linked into the program; the objects can go
        glDeleteShader(vertexShader)
        glDeleteShader(fragmentShader)

        compiledLayers = enabledLayers
    }
}
