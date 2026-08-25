package org.combustiblelemon.lemonlib.render.composition

import java.util.concurrent.ConcurrentHashMap

/**
 * Global debug state for composed shader systems. The `ShaderLayers` console
 * command talks to this; [ComposedShaderProgram] instances register here and
 * pick up layer-set overrides lazily at render time.
 *
 * A system appears in [systems] once the first [ComposedShaderProgram] with
 * that name has been constructed (e.g. the shield system registers when the
 * first shielded ship enters combat).
 */
object CompositionDebug {

    // system name -> canonical layer names (registered at program construction)
    private val knownSystems = ConcurrentHashMap<String, List<String>>()

    // system name -> enabled-layer override; absent = no override (all layers)
    private val overrides = ConcurrentHashMap<String, Set<String>>()

    fun registerSystem(name: String, layerNames: List<String>) {
        knownSystems[name] = layerNames
    }

    /** All registered systems and their layer names. */
    fun systems(): Map<String, List<String>> {
        return knownSystems.toMap()
    }

    /**
     * Restrict a system to the given layers. null or empty clears the override
     * (all layers enabled).
     */
    fun setEnabledLayers(system: String, layers: Set<String>?) {
        if (layers.isNullOrEmpty()) {
            overrides.remove(system)
        } else {
            overrides[system] = layers
        }
    }

    /** Current override for a system, or null when all layers are enabled. */
    fun enabledLayersFor(system: String): Set<String>? {
        return overrides[system]
    }
}
