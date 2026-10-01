package org.combustiblelemon.lemonlib.hullmods

import com.fs.starfarer.api.Global
import com.fs.starfarer.api.combat.HullModEffect
import com.fs.starfarer.api.combat.ShipAPI
import org.combustiblelemon.lemonlib.reflection.ReflectionUtils

/**
 * Wraps a hullmod's effect so that it reports itself as incompatible with the given blockers,
 * vanilla-style (greyed out in the refit picker with a reason), instead of e.g. being removed
 * after the fact. Everything except the two applicability methods is delegated untouched.
 *
 * Ship parameter is nullable: the game's hullmod tooltip path passes null when no ship is
 * selected (e.g. codex), and every vanilla implementation guards for it.
 */
class HullmodIncompatibilityWrapper(
    val wrapped: HullModEffect,
    val blockers: List<HullmodBlocker>,
) : HullModEffect by wrapped {

    override fun isApplicableToShip(ship: ShipAPI?): Boolean {
        if (blockers.any { it.isOn(ship) }) return false
        return wrapped.isApplicableToShip(ship)
    }

    override fun getUnapplicableReason(ship: ShipAPI?): String? {
        blockers.firstOrNull { it.isOn(ship) }?.let { return it.reason }
        return wrapped.getUnapplicableReason(ship)
    }
}

/**
 * Installs [HullmodIncompatibilityWrapper] onto hullmod specs from a registry the consuming mod
 * builds in code (hullmod id -> blockers). Stateless: pass the registry to every call.
 *
 * Call [install] from the mod plugin's onApplicationLoad and [verifyAndReinstall] from
 * onGameLoad (recovers wrappers another mod replaced after us, load-order dependent).
 *
 * The spec's effect field is located structurally (value identity first, HullModEffect-assignable
 * declared type as fallback) - never by name - so it survives obfuscation renames. Any failure is
 * logged and skipped; there is no removal fallback, so an ERROR here means the hullmod stays
 * installable alongside its blocker.
 */
object HullmodIncompatibilityInstaller {
    private val logger = Global.getLogger(HullmodIncompatibilityInstaller::class.java)

    fun install(registry: Map<String, List<HullmodBlocker>>) {
        for ((modId, blockers) in registry) {
            try {
                installFor(modId, blockers)
            } catch (t: Throwable) {          // never crash game load
                logger.error(
                    "Hullmod incompatibility wrapper FAILED for '$modId' " +
                            "(blockers: ${blockers.joinToString { it.id }}); it stays installable", t
                )
            }
        }
        logger.info("Hullmod incompatibility: ${registry.size} hullmods processed")
    }

    /**
     * Re-checks that every registry entry is still wrapped and reinstalls any that another mod
     * replaced after us.
     */
    fun verifyAndReinstall(registry: Map<String, List<HullmodBlocker>>) {
        for ((modId, blockers) in registry) {
            try {
                val effect = Global.getSettings().getHullModSpec(modId)?.effect ?: continue
                if (effect !is HullmodIncompatibilityWrapper) {
                    logger.warn(
                        "Hullmod incompatibility wrapper on '$modId' was replaced " +
                                "(now ${effect.javaClass.name}); reinstalling"
                    )
                    installFor(modId, blockers)
                }
            } catch (t: Throwable) {
                logger.error("Hullmod incompatibility re-verify FAILED for '$modId'", t)
            }
        }
    }

    private fun installFor(modId: String, blockers: List<HullmodBlocker>) {
        val spec = Global.getSettings().getHullModSpec(modId)
        if (spec == null) {
            logger.warn("Hullmod '$modId' not found, skipping")
            return
        }
        val effect = spec.effect
        if (effect == null) {
            logger.warn("Hullmod '$modId' has no effect, skipping")
            return
        }
        if (effect is HullmodIncompatibilityWrapper) {
            logger.info("Hullmod '$modId' already wrapped, skipping")
            return
        }
        if (spec.effect !== effect) {
            logger.error("Hullmod '$modId' getEffect() is not identity-stable; cannot wrap, skipping")
            return
        }

        var fields = ReflectionUtils.findFieldsByValue(spec, effect)
        if (fields.isEmpty()) {
            fields = ReflectionUtils.findFieldsAssignableTo(spec, HullModEffect::class.java)
        }
        if (fields.isEmpty()) {
            logger.error("No field holding the HullModEffect found on ${spec.javaClass.name} for '$modId', skipping")
            return
        }

        val wrapper = HullmodIncompatibilityWrapper(effect, blockers)
        fields.forEach { it.set(spec, wrapper) }
        if (spec.effect !== wrapper) {
            fields.forEach { it.set(spec, effect) }
            logger.error("Wrapper install verification failed for '$modId'; rolled back")
            return
        }
        logger.info("Installed hullmod incompatibility wrapper on '$modId'")
    }
}
