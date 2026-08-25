package org.combustiblelemon.lemonlib.render

import com.fs.starfarer.api.combat.ViewportAPI

/**
 * Interface for all shader implementations.
 * Provides a consistent API for rendering and resource management.
 */
interface Shader {
    /**
     * Renders the shader effect.
     * @param viewport Optional viewport for world-to-screen coordinate conversion.
     *                 Some shaders may not need this parameter.
     */
    fun render(viewport: ViewportAPI)

    /**
     * Cleans up OpenGL resources (VAO, VBO, shader programs, etc.).
     * Must be called when the shader is no longer needed to prevent memory leaks.
     */
    fun cleanup()
}
