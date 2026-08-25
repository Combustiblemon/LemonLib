package org.combustiblelemon.lemonlib.render

import com.fs.starfarer.api.Global

private val shaderCodeMap: MutableMap<String, String> = mutableMapOf()

/**
 * Loads (and caches) a shader source file via Starsector's merged mod
 * filesystem — the path can point into any enabled mod's data directories.
 */
fun getShaderCode(filepath: String): String {
    var code = shaderCodeMap[filepath]

    if (code != null) {
        return code
    }

    code = Global.getSettings().loadText(filepath)
    shaderCodeMap[filepath] = code
    return code
}
