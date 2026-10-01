# Hullmod incompatibility

Vanilla-style hullmod incompatibility for mods whose blockers are built-in hullmods (or hull
features): the blocked hullmods grey out in the refit picker with a proper reason and cannot be
installed — instead of being silently removed after install (the MagicLib
`MagicIncompatibleHullmods` approach).

Everything is initialized **from code** — a registry map. No CSV/JSON/config files.

Package: `org.combustiblelemon.lemonlib.hullmods`.

## Why a wrapper

Vanilla's incompatibility check lives in the hullmod *being installed* (its
`isApplicableToShip` / `getUnapplicableReason`). If your blocker is built into your hulls, the
"can not be installed" message must come from the *other* (often vanilla) hullmod's effect — which
you don't own. `HullModSpecAPI` has no `setEffect()`, so the installer reflectively swaps a
delegating wrapper into the spec. Refit UI, tooltips, and autofit all call `spec.getEffect()`
live (verified against 0.98a-RC8), so the wrapper is honored everywhere.

## Usage

Build a registry (`hullmod id -> blockers`) and call the two hooks from your mod plugin:

```kotlin
object MyIncompatibilities {
    // The hullmod that blocks others. Default presence check: variant.hasHullMod(id).
    val MY_SYSTEMS = HullmodBlocker("mymod_systems", "My Systems")

    // Custom presence predicate: feature present via hull spec, not only via the hullmod.
    val MY_SHIELD = HullmodBlocker("mymod_shield", "My Shield") { variant ->
        variant.hasHullMod("mymod_shield") || variant.hullSpec?.shipDefenseId == "mymod_defense"
    }

    // Custom reason instead of "Incompatible with <name>".
    val MY_SHIELD_VS_GENERATORS = HullmodBlocker(
        "mymod_shield", "My Shield",
        reasonOverride = "This ship already has a shield system",
    ) { variant -> variant.hasHullMod("mymod_shield") }

    fun registry(): Map<String, List<HullmodBlocker>> = mapOf(
        "targetingunit" to listOf(MY_SYSTEMS),
        "hardenedshieldemitter" to listOf(MY_SHIELD),
        "frontshield" to listOf(MY_SHIELD_VS_GENERATORS),
    )
}

class MyModPlugin : BaseModPlugin() {
    override fun onApplicationLoad() {
        HullmodIncompatibilityInstaller.install(MyIncompatibilities.registry())
    }

    override fun onGameLoad(newGame: Boolean) {
        HullmodIncompatibilityInstaller.verifyAndReinstall(MyIncompatibilities.registry())
    }
}
```

The API is stateless — pass your registry to every call; the library stores nothing.

## The reverse direction

The wrapper only handles "other mod refuses install when your blocker is present". Your own
hullmods should mirror the check with the shared helpers:

```kotlin
override fun isApplicableToShip(ship: ShipAPI?): Boolean =
    isApplicableGiven(ship, ID, INCOMPATIBLE_HULLMODS)   // id -> fallback display name map

override fun getUnapplicableReason(ship: ShipAPI?): String? =
    unapplicableReasonFor(ship, INCOMPATIBLE_HULLMODS)
```

`isApplicableGiven` returns true when your mod is built into the hull. This matters: the refit
screen's `removeNotApplicableMods` strips ANY hullmod — built-ins included — whose
`isApplicableToShip` is false. Without the guard, a legacy variant carrying a blocked hullmod
would lose your built-in on refit open.

Applicability overrides must take `ShipAPI?`: the game's hullmod tooltip path passes a null ship
(codex / nothing selected) and a non-null Kotlin parameter throws there.

## Failure modes

- Every installer failure is log-and-skip, never a crash. There is **no removal fallback**: an
  ERROR in `starsector.log` means that hullmod remains installable alongside your blocker.
- `verifyAndReinstall` recovers wrappers another mod replaced after yours (load-order dependent):
  WARN + reinstall on each game load.
- Healthy load: one "Installed hullmod incompatibility wrapper on '<id>'" INFO line per entry.

## Contributor note: reflection rules

The effect field is located structurally — value-identity scan over the spec's class + superclass
chain, declared-type-assignable fallback — never by (obfuscated) name. All reflection goes through
`org.combustiblelemon.lemonlib.reflection.ReflectionUtils` (MethodHandles-based). Any direct
bytecode reference to `java.lang.reflect.Field` (e.g. a Kotlin `for` loop typed over
`clazz.declaredFields`) throws `SecurityException: File access and reflection are not allowed to
scripts` from the game's script classloader — upcast `getDeclaredFields()` results to
`Array<out Any>` immediately and touch them only through the MethodHandles.
