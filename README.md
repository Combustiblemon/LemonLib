# LemonLib

Shared utility library mod for Combustiblelemon's Starsector mods. Utility mod — adds no content
of its own; other mods depend on it.

- Mod id: `combustiblelemon_lemonlib`
- Package: `org.combustiblelemon.lemonlib`
- Jars: `jars/LemonLib.jar`, `jars/lib/Kotlin-Runtime.jar` (bundled Kotlin runtime, see below)

## Contents

| Package                       | What                                                                                                                                                                                                                        |
| ----------------------------- | --------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------- |
| `lemonlib.render.composition` | **Shader composition system** — build fragment shaders from composable layer modules, with solo-layer debugging and module-tagged compile errors. Full docs: [docs/shader-composition/](docs/shader-composition/README.md). |
| `lemonlib.render`             | `Shader` interface (render/cleanup contract), `getShaderCode` (cached shader-source loader), `drawCircle` (immediate-mode circle outline or disc, ported from LazyLib's `DrawUtils`).                                      |
| `lemonlib.geometry`           | Polygon/segment/point math: `rotatePointByDegrees`, `isPointInPolygon`, `getRandomPointsInPolygon`, perpendicular-intersection helpers, `PolygonSmoothing` (+ `SmoothingMethod`; Laplacian / Taubin smoothing, `catmullRomAdaptive` corner rounding, `offsetAlongNormals`), `CubicCurve2D` (cubic-bezier easing), `normalize`, `getPercentage`. Collision (ported from LazyLib's `CollisionUtils`): `getSegmentIntersection`, `getCollisionPoint` (segment vs entity bounds), `isPointWithinBounds`. |
| `lemonlib.ship`               | `ShipAPI` helpers: `getShipPolygonInWorld`, `getShipDimensions`, `getLocationInFrontOfShip`, `getVelocityVectorInFacing`, `isShieldFacingShip`.                                                                             |
| `lemonlib.commands`           | `ShaderLayers` console command (needs the Console Commands mod at runtime; optional).                                                                                                                                       |

## Kotlin runtime

LemonLib ships the Kotlin runtime its dependents run on: `jars/lib/Kotlin-Runtime.jar`, one jar with
`kotlin-stdlib` 2.2.20, `kotlin-reflect` and `kotlinx-coroutines` 1.11.0, listed in `mod_info.json`.
A Kotlin mod that depends on LemonLib does not need LazyLib for the runtime.

- **Compile against it, compile-only.** Turn off the Kotlin Gradle plugin's default Maven stdlib
  with `kotlin.stdlib.default.dependency=false` in `gradle.properties`, then add
  `compileOnly(files("../LemonLib/jars/lib/Kotlin-Runtime.jar"))`. Use Kotlin Gradle plugin 2.2.20
  or older, so your classes never need a newer stdlib than the one that runs.
- **Which copy wins.** Starsector puts every enabled mod's jars into one shared classloader.
  Mods are sorted by `sortString`, falling back to `name`, and their jars are searched in
  **reverse** order. The mod that sorts last therefore wins a duplicate class.
  - LemonLib's `mod_info.json` sets `"sortString": "~LemonLib"`. `~` sorts after every letter and
    digit, so LemonLib's jars are searched first and its runtime replaces LazyLib's older bundled
    one for every mod in the install. That is backward-compatible for code built against an older
    Kotlin.
  - A dependent mod must not set a `sortString` that sorts after `~LemonLib`. If it did, a runtime
    that mod bundles could shadow this one.
  - `sortString` also drives data priority, the "Running with the following mods (in order of
    priority)" order. LemonLib's only data files are merged CSVs, so loading them last changes
    nothing.
- **Reflection and files.** The game's classloader blocks `java.lang.reflect.Method`/`Field`/
  `Constructor` and `java.io.File`. Most of `kotlin-reflect` and the `kotlin.io` file helpers
  therefore throw when used. Plain stdlib, collections and coroutines are fine.
- `jars/lib/Kotlin-Runtime-sources.jar` is for IDE source attachment only. It is not in
  `mod_info.json` and does not ship in the release zip.

## Depending on LemonLib

In your mod's `mod_info.json`:

```json
"dependencies": [
  {
    "id": "combustiblelemon_lemonlib",
    "name": "LemonLib"
  }
]
```

In your mod's `settings.gradle.kts` + `build.gradle.kts` (composite build — compiles against
LemonLib's classes, so your IDE never locks `jars/LemonLib.jar`):

```kotlin
// settings.gradle.kts
includeBuild("../LemonLib")

// build.gradle.kts
dependencies {
    // Substituted by the included build — version intentionally omitted, the
    // substitution matches on group:name and always uses the local LemonLib
    compileOnly("org.combustiblelemon:LemonLib")
}
tasks.named("compileKotlin") {
    dependsOn(gradle.includedBuild("LemonLib").task(":buildMod")) // refresh the runtime jar too
}
```

Avoid `compileOnly(files("../LemonLib/jars/LemonLib.jar"))` — compiling against the runtime jar
makes IDE language servers lock it, which blocks jar replacement on Windows.

## Building

```text
gradlew buildMod        -> jars/LemonLib.jar (code only; data/ and mod_info.json stay in the folder)
gradlew packageMod      -> LemonLib.zip      (or package_mod.bat / package_mod.sh)
gradlew cleanMod        -> removes build/ and jars/LemonLib.jar; the tracked jars/lib/ runtime stays
```

`packageMod` builds a fresh jar and zips one `LemonLib/` folder that players unzip into `mods/`.
The folder holds `mod_info.json`, `lemonlib.version`, `jars/LemonLib.jar`, `jars/lib/Kotlin-Runtime.jar`, `data/`, this README,
`CHANGELOG.md` and `docs/`. The file list lives only in `packageMod` (`build.gradle.kts`); the two scripts just call
it. The files are staged in `build/mod_package/LemonLib/`, and `LemonLib.zip` is git-ignored.

**Releasing:** on a clean `main`, `release.bat vX.Y.Z` (add `-DryRun` to rehearse) bumps
`mod_info.json`, `lemonlib.version`, `build.gradle.kts` and `gradle.properties`, turns the
`## Unreleased` section of `CHANGELOG.md` into the new version, runs `packageMod`, commits, tags,
pushes `main` and creates the GitHub release with `LemonLib.zip` attached. Needs the GitHub CLI
(`winget install --id GitHub.cli`, then `gh auth login`) once. The script is the same as Covenant
Remnants' `scripts/release.ps1`; only the config block at its top differs. Changes land under
`## Unreleased`; the script leaves no empty section behind, so add the heading back with the first
change after a release.

**Build order matters:** build LemonLib before any mod that depends on it, so that the runtime jar is
current. Dependent mods compile against LemonLib's classes through the composite build below. They
can let it build the jar automatically:

```kotlin
// settings.gradle.kts
includeBuild("../LemonLib")

// build.gradle.kts
tasks.named("compileKotlin") {
    dependsOn(gradle.includedBuild("LemonLib").task(":buildMod"))
}
```

(Covenant Remnants does this — its `gradlew buildMod` builds LemonLib first automatically.)

At runtime Starsector's shared mod classloader makes LemonLib's classes visible to dependent mods.
The `mod_info.json` dependency gives users a clear error if LemonLib is missing. It does **not** set
load order:
- jar order follows `sortString`/`name`;
- ModPlugin order follows `name` only;
- dependencies affect neither.

None of that matters for reaching LemonLib's classes. The game builds its single mod classloader
from every enabled mod's jars at once, before it loads any script or ModPlugin, so LemonLib is
available to every mod whatever the order.
