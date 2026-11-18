# Changelog

All notable changes to LemonLib, newest version first.

## Unreleased

### Breaking changes

- Changed the bundled Kotlin runtime to `kotlin-stdlib` 2.4.21 (was 2.2.20) with `kotlinx-coroutines-core` 1.11.0, so every mod in the install runs on current Kotlin. Mods built against it need Kotlin Gradle plugin 2.3 to 2.4.21; a 2.2 compiler cannot read 2.4 metadata.
- Removed `kotlin-reflect` from the bundled runtime, because it is built on the `java.lang.reflect` classes Starsector blocks and no installed mod used it. Use `ReflectionUtils` (MethodHandles) for reflection.

### New features

- Added `scripts/build-kotlin-runtime.py`, which rebuilds `jars/lib/Kotlin-Runtime.jar` and its sources jar from Maven Central for any Kotlin / coroutines version, with SHA1 checks, so a runtime update is one command.

### Behavioral changes

- Changed LemonLib to target Starsector 0.98.5a-RC7, so the launcher no longer flags it as made for another game version.
- Changed `gradlew` and `gradlew.bat` to build on Starsector's bundled Java 17 (`<Starsector>/jre`) when LemonLib sits in `mods/`, so a newer system Java (21+) no longer breaks the Gradle 8.4 build.

### Bug fixes

- Fixed `HullmodIncompatibilityWrapper` dropping the hullmod hooks that `HullModEffect` declares as Java default methods (`applyEffectsAfterShipCreation2ndPass` from 0.98.5a, `applyEffectsAfterShipAddedToCombatEngine`, `getRequiredItem`), so a wrapped hullmod keeps every effect and its required item.

## v0.1.2 — 2026-10-17

### Breaking changes

- Changed LemonLib to replace LazyLib's bundled Kotlin runtime with its own 2.2.20 runtime for every mod in the install, because Starsector lets the mod that sorts last win duplicate classes. Kotlin mods built against an older runtime keep working.
- Added `"sortString": "~LemonLib"` to `mod_info.json`, so LemonLib sorts after every other mod and its Kotlin runtime stays first on the classpath whatever other mods are installed. LemonLib now shows last in the launcher's "in order of priority" mod list.

### New features

- Added a bundled Kotlin runtime (`jars/lib/Kotlin-Runtime.jar`: stdlib 2.2.20, reflect, coroutines 1.11.0), so Kotlin mods that depend on LemonLib no longer need LazyLib just to run.
- Added `getCollisionPoint`, `isPointWithinBounds` and `getSegmentIntersection` to `lemonlib.geometry`, ported from LazyLib's `CollisionUtils`, so mods can test lines and points against a ship's exact bounds without LazyLib.
- Added `drawCircle` to `lemonlib.render`, which draws a circle outline or disc and is safe to call while a buffer object is still bound.

### Behavioral changes

- Changed `gradlew cleanMod` to keep the bundled runtime in `jars/lib/`, so a clean no longer deletes a tracked file.

## v0.1.1 — 2026-10-12

### New features

- Added Taubin smoothing to `PolygonSmoothing`, so outlines can be smoothed without shrinking inside their original bounds.
- Added `PolygonSmoothing.catmullRomAdaptive`, which rounds polygon corners with a spline that adds points only where the turn is sharp, within a caller-supplied point budget.
- Added `PolygonSmoothing.offsetAlongNormals`, which pushes a closed polygon outward (or inward) by a fixed distance along its vertex normals, independent of winding order.

## v0.1.0 — 2026-10-10

### New features

- Added the shader composition system (`lemonlib.render.composition`), so mods can assemble one GLSL program from per-layer modules and share it across ships.
- Added the hullmod incompatibility system (`lemonlib.hullmods`), so a mod can block vanilla or modded hullmods from installing alongside its own.
- Added polygon, segment and point geometry helpers (`lemonlib.geometry`), including Laplacian polygon smoothing and cubic-bezier easing.
- Added Version Checker support, so players with LunaLib are told when a newer LemonLib is available.
- Added release packaging: `gradlew packageMod` produces `LemonLib.zip` with only the files the game needs plus the modder docs.
