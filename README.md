# LemonLib

Shared utility library mod for Combustiblelemon's Starsector mods. Utility mod — adds no content
of its own; other mods depend on it.

- Mod id: `combustiblelemon_lemonlib`
- Package: `org.combustiblelemon.lemonlib`
- Jar: `jars/LemonLib.jar`

## Contents

| Package                       | What                                                                                                                                                                                                                        |
| ----------------------------- | --------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------- |
| `lemonlib.render.composition` | **Shader composition system** — build fragment shaders from composable layer modules, with solo-layer debugging and module-tagged compile errors. Full docs: [docs/shader-composition/](docs/shader-composition/README.md). |
| `lemonlib.render`             | `Shader` interface (render/cleanup contract), `getShaderCode` (cached shader-source loader).                                                                                                                                |
| `lemonlib.geometry`           | Polygon/segment/point math: `rotatePointByDegrees`, `isPointInPolygon`, `getRandomPointsInPolygon`, perpendicular-intersection helpers, `PolygonSmoothing` (+ `SmoothingMethod`), `normalize`, `getPercentage`.             |
| `lemonlib.ship`               | `ShipAPI` helpers: `getShipPolygonInWorld`, `getShipDimensions`, `getLocationInFrontOfShip`, `getVelocityVectorInFacing`, `isShieldFacingShip`.                                                                             |
| `lemonlib.commands`           | `ShaderLayers` console command (needs the Console Commands mod at runtime; optional).                                                                                                                                       |

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
gradlew buildMod        -> jars/LemonLib.jar
```

**Build order matters:** build LemonLib before any mod that depends on it — dependent mods compile
against `jars/LemonLib.jar`. Dependent mods can automate this with a Gradle composite build:

```kotlin
// settings.gradle.kts
includeBuild("../LemonLib")

// build.gradle.kts
tasks.named("compileKotlin") {
    dependsOn(gradle.includedBuild("LemonLib").task(":buildMod"))
}
```

(Covenant Remnants does this — its `gradlew buildMod` builds LemonLib first automatically.)

At runtime Starsector's shared mod classloader makes LemonLib's classes visible to dependent mods;
the `mod_info.json` dependency guarantees load order and gives users a clear error if LemonLib is
missing.
