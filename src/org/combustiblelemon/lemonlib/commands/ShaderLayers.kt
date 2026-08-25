package org.combustiblelemon.lemonlib.commands

import org.combustiblelemon.lemonlib.render.composition.CompositionDebug
import org.lazywizard.console.BaseCommand
import org.lazywizard.console.BaseCommand.CommandContext
import org.lazywizard.console.BaseCommand.CommandResult
import org.lazywizard.console.Console

/**
 * Dev tool: restrict any composed shader system to a subset of its layers so a
 * single layer can be inspected in isolation. Disabled layers compile as
 * neutral GLSL stubs; every instance of the system rebuilds its program on its
 * next render.
 *
 * Systems register themselves in [CompositionDebug] when their first instance
 * is constructed (e.g. "shield" appears once a shielded ship is in combat).
 *
 *  - `ShaderLayers`                      — list registered systems + their layers
 *  - `ShaderLayers shield`               — show shield's layers + current set
 *  - `ShaderLayers shield border`        — render only the border layer
 *  - `ShaderLayers shield fill,ripple`   — render only those layers
 *  - `ShaderLayers shield all`           — reset to all layers
 */
class ShaderLayers : BaseCommand {

    override fun runCommand(args: String, context: CommandContext): CommandResult? {
        val parts = args.trim().split(' ', limit = 2).filter { it.isNotEmpty() }
        val systems = CompositionDebug.systems()

        if (systems.isEmpty()) {
            Console.showMessage("No composed shader systems registered yet (they register when first rendered, e.g. shield in combat).")
            return CommandResult.SUCCESS
        }

        // No args: list all systems
        if (parts.isEmpty()) {
            for ((name, layerNames) in systems) {
                val override = CompositionDebug.enabledLayersFor(name)
                val state = if (override == null) "all" else override.joinToString(",")
                Console.showMessage("$name: layers [${layerNames.joinToString(", ")}], enabled: $state")
            }
            return CommandResult.SUCCESS
        }

        val system = parts[0].lowercase()
        val layerNames = systems[system]
        if (layerNames == null) {
            Console.showMessage("Unknown system '$system'. Registered: ${systems.keys.joinToString(", ")}.")
            return CommandResult.BAD_SYNTAX
        }

        // Only system given: show its state
        if (parts.size == 1) {
            val override = CompositionDebug.enabledLayersFor(system)
            val state = if (override == null) "all" else override.joinToString(",")
            Console.showMessage("$system: layers [${layerNames.joinToString(", ")}], enabled: $state")
            return CommandResult.SUCCESS
        }

        val selection = parts[1].trim()
        if (selection.equals("all", ignoreCase = true)) {
            CompositionDebug.setEnabledLayers(system, null)
            Console.showMessage("$system: all layers enabled.")
            return CommandResult.SUCCESS
        }

        val requested = selection.split(',', ' ').map { it.trim().lowercase() }.filter { it.isNotEmpty() }
        val unknown = requested.filter { it !in layerNames }
        if (unknown.isNotEmpty()) {
            Console.showMessage(
                "Unknown layer(s) for $system: ${unknown.joinToString(", ")}. Available: ${layerNames.joinToString(", ")}."
            )
            return CommandResult.BAD_SYNTAX
        }

        CompositionDebug.setEnabledLayers(system, requested.toSet())
        Console.showMessage("$system: enabled layers ${requested.joinToString(", ")}.")
        return CommandResult.SUCCESS
    }
}
