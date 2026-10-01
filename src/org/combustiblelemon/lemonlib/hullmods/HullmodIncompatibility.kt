package org.combustiblelemon.lemonlib.hullmods

import com.fs.starfarer.api.Global
import com.fs.starfarer.api.combat.ShipAPI
import com.fs.starfarer.api.combat.ShipVariantAPI

/** Live display name of [hullmodId]; [fallbackName] if the spec is missing at runtime. */
fun hullmodDisplayName(hullmodId: String, fallbackName: String): String =
    runCatching { Global.getSettings().getHullModSpec(hullmodId)?.displayName }
        .getOrNull() ?: fallbackName

/** The one reason wording used for hullmod incompatibilities, matching vanilla's. */
fun incompatibleWith(hullmodId: String, fallbackName: String): String =
    "Incompatible with ${hullmodDisplayName(hullmodId, fallbackName)}"

/**
 * A hullmod (or hull feature) that makes other hullmods unapplicable.
 *
 * [presentOn] defaults to "this hullmod is on the variant"; pass a custom predicate when the
 * feature can be present without the hullmod (e.g. a custom defense declared via the hull spec's
 * defense id). [reasonOverride] replaces the default "Incompatible with <name>" wording.
 */
class HullmodBlocker(
    val id: String,
    val fallbackName: String,
    private val reasonOverride: String? = null,
    private val presentOn: (ShipVariantAPI) -> Boolean = { it.hasHullMod(id) },
) {
    fun isOn(ship: ShipAPI?): Boolean = ship?.variant?.let(presentOn) == true

    val reason: String get() = reasonOverride ?: incompatibleWith(id, fallbackName)
}

/**
 * Reason for the first blocked hullmod present on [ship], or null.
 * [incompatible]: hullmod id -> fallback display name.
 */
fun unapplicableReasonFor(ship: ShipAPI?, incompatible: Map<String, String>): String? {
    val variant = ship?.variant ?: return null
    val entry = incompatible.entries.firstOrNull { variant.hasHullMod(it.key) } ?: return null
    return incompatibleWith(entry.key, entry.value)
}

/**
 * Applicability check for a hullmod that blocks the ids in [incompatible].
 *
 * Always true when [ownId] is built into the hull: the refit screen strips ANY hullmod (built-ins
 * included) whose isApplicableToShip is false, so a legacy variant carrying a blocked hullmod
 * must never take the built-in blocker down with it.
 */
fun isApplicableGiven(ship: ShipAPI?, ownId: String, incompatible: Map<String, String>): Boolean {
    val variant = ship?.variant ?: return true
    if (variant.hullSpec?.isBuiltInMod(ownId) == true) return true
    return incompatible.keys.none { variant.hasHullMod(it) }
}
