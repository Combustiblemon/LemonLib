# Case study: the Covenant shield

The shield is the first real consumer of the composition system and the reference implementation
for every convention in these docs. This page maps the whole thing: files, layers, and the data
path from ship hull to pixels.

## Files

All paths below are in the **Covenant Remnants** mod unless marked LemonLib:

| Piece | Path |
| --- | --- |
| Owner class | `src/.../covenantremnants/utils/render/ShieldShader.kt` |
| Layer classes | `src/.../covenantremnants/utils/render/shield/{Fill,Border,Sweep,Honeycomb,Fortress,Plasma,Distortion,Debug}Layer.kt` (+ `ShieldImpactEffect.kt`, `ShieldImpactCurves.kt`, `ShieldTexture.kt` behind the plasma layer; `ShieldScreenCopy.kt` behind distortion) |
| CPU polygon smoothing | `org.combustiblelemon.lemonlib.geometry.PolygonSmoothing` (LemonLib) |
| Base template + vert | `data/shaders/shield/base.frag`, `data/shaders/shield/shield.vert` |
| Layer modules | `data/shaders/shield/{fill,border,sweep,honeycomb,fortress,plasma,distortion,debug}.glsl` |
| Consumer | `src/.../covenantremnants/plugins/CovenantShieldsRenderPlugin.kt` (per-ship combat render plugin) |

System name: `"shield"` — so `ShaderLayers shield ...` targets it.

**One program for all ships.** `ShieldProgram` (Covenant Remnants, `utils/render/shield/`) builds a
single `ComposedShaderProgram` from throw-away template layers and compiles it at game start
(`onApplicationLoad`). Each `ShieldShader` keeps its own layer instances for per-ship state and
calls `layer.uploadUniforms(program.programId, viewport)` / `layer.advance(dt)` itself instead of
`program.uploadLayerUniforms` / `program.advance`, skipping layers that
`CompositionDebug.enabledLayersFor("shield")` has disabled (those are stubbed in the shared program).
This works because the assembled source depends only on the layer set and the `points[]` capacity
(128); the live point count is the `pointCount` uniform. Pattern worth lifting into the engine if a
second system needs it: split "program" from "layer set".

## Layer table

List order below **is** the GLSL concat order and upload order:

| # | Layer | Module | Owns (uniforms) | Effect |
| --- | --- | --- | --- | --- |
| 1 | `fill` | `fill.glsl` | `shieldColor` | Base coat (flat colour) |
| 2 | `border` | `border.glsl` | `borderColor`, `borderWidth` | Ring along the polygon edges |
| 3 | `sweep` | `sweep.glsl` | `sweepPosition`, `sweepEdgeWidth` | Activation wipe nose-to-tail + edge glow |
| 4 | `honeycomb` | `honeycomb.glsl` | `honeycombEnabled`, `honeycombSize`, `edgeWidth`, `honeycombBreathing` | Hex grid with a breathing brightness wave (`honeycombBreathing` 0 holds it at full brightness) |
| 5 | `fortress` | `fortress.glsl` | `fortressLevel`, `fortressHexColor`, `fortressGlowColor`, `fortressHexSize`, `fortressHexEdge`, `fortressGlowWidth`, `fortressPulseSpeed` | Fortress Shield overlay: locked white lattice on the honeycomb's hex grid + pulsing magenta edge glow; no-op at `fortressLevel` 0 |
| 6 | `plasma` | `plasma.glsl` | `plasmaNoiseA/B` (samplers), `plasmaActive`, `plasmaBodyFade`, `plasmaBodyDepth`, `plasmaEdgeBand`, `plasmaPlasmaBand`, `plasmaSharpness`, `plasmaUvScale`, `plasmaOffsets`, `plasmaEdgeGlow`, `plasmaCenterColor`, `plasmaEdgeColor`, `plasmaHitCount` + `plasmaHits[8]` + `plasmaHitColors[8]`, `plasmaFlash` | Halo: Reach shield light: rim glow + scrolling plasma filaments that flare on hits / low HP, hit spots, the collapse flare and the recharge wave. Additive, composited into the alpha pass at the end of `plasma_apply` |
| 7 | `distortion` | `distortion.glsl` | `distortionScreen` (sampler, unit 2), `distortionViewport`, `distortionWaveCount` + `distortionWaves[8]` + `distortionWaveParams[8]` | Hit refraction rings: `distortion_offset` warps the position the whole chain is evaluated at, `distortion_compose` then outputs the displaced framebuffer copy with the shield composited on top (alpha 1); passes the colour through where no ring displaces the pixel |
| 8 | `debug` | `debug.glsl` | `debugMode`, `boundingBoxMin/Max` | Red bbox, green points, magenta outline |

Shared uniforms (declared in `base.frag`, uploaded by `ShieldShader`): `points[N]` (polygon,
screen coords; capacity `N` via the `POINT_COUNT_PLACEHOLDER` placeholder) + `pointCount` (points in
use — a uniform so the six outline loops are not unrolled at compile time), `resolution`, `center`,
`shipRotationMatrix`, `viewportScale`, `shipFacing`, `time`. Shared helpers: `distanceToLine`,
`isPointInPolygon`, `shipLocalPos`, `hexGridEdgeDistance`. The hex-grid helper is shared so that
`honeycomb` and `fortress` draw the same grid. Both use the same size (`HoneycombLayer.HEX_SIZE` on
the Kotlin side), so the fortress lattice sits exactly on the honeycomb hexes. The helper also keeps
working when the other layer is stubbed.

## The orchestration function

`base.frag`'s `shieldFrag` — the mix chain is the pixel contract:

```glsl
vec4 shieldFrag(vec2 pixelPos) {
    vec4 result = fill_color();

    float pixelShipRelX = sweep_shipRelX(pixelPos);
    vec4 sweepEdge = sweep_edgeColor(pixelShipRelX);

    if (sweep_isCulled(pixelShipRelX)) {
        return sweepEdge;                          // mask-modifying early return
    }

    result = border_apply(result, pixelPos);       // 1. border mix
    result = sweep_applyEdge(result, sweepEdge);   // 2. sweep edge mix
    result = honeycomb_apply(result, pixelPos);    // 3. honeycomb mix
    result = fortress_apply(result, pixelPos);     // 4. fortress overlay mix
    result = plasma_apply(result, pixelPos);       // 5. plasma: additive light, last so it sits on the lattice
    return result;
}
```

And in `main()`, after the mask:

```glsl
vec2 warp = distortion_offset(pixelPos);
FragColor = distortion_compose(shieldFrag(pixelPos + warp), pixelPos, warp);
```

The distortion layer is a pair of *compose* hooks rather than a mix step: it warps the position the
whole chain is evaluated at (so every layer bends with the ring) and then replaces the background
(framebuffer copy) rather than adding to the shield colour. Its stubs are the identity, so the hooks
are free when the layer is disabled.

Two conventions worth studying here:

- **Why the sweep cull sits in the base template.** Sweep is not a pure "paint on top" layer — it
  *modifies the mask* (pixels ahead of the wipe show only the edge glow). That inter-layer control
  flow lives visibly in the orchestration function; the sweep *module* only supplies the functions
  (`sweep_isCulled`, `sweep_edgeColor`, ...) and the sweep *class* only owns the state. This is
  also what makes sweep stubbable: the stub's `sweep_isCulled` returns `false`, so with sweep
  disabled the whole shield always renders.
- **The border/sweep rule-3 dependency.** `sweep.glsl` reads `borderColor` (edge glow uses the
  border color), so border precedes sweep in the layer list, the dependency is documented at the
  top of `sweep.glsl`, and `BorderLayer`'s stub still declares `borderColor` — with border
  disabled the sweep edge simply renders invisible (color stays vec4(0)).

## Data path

### CPU, per frame

`CovenantShieldsRenderPlugin.renderShield` pushes state, then calls `shader.render(viewport)`:

```text
ship.exactBounds.origSegments          hull outline, ship-local coords
  -> PolygonSmoothing.smooth()         Laplacian smoothing, repositions points, count unchanged
  -> scale (1.05, 1.1)                 shield slightly larger than hull
  -> translate to ship.location        world space
  -> rotate by ship.facing             world space, oriented
  -> viewport world-to-screen          SCREEN space  <- what the fragment shader receives
```

Along the way each point is also projected onto the ship-forward axis; the min/max of that gives
the sweep bounds, and `sweepPosition = maxX - phaseCloak.effectLevel * width` — cloak level 0..1
wipes the shield nose-to-tail. The screen points then feed `calculateBoundingBox()` (margin 20 px),
which rewrites the quad VBO so the fragment shader only runs over the shield's screen rectangle.

### Upload

`render()` does, in order: `program.ensureUpToDate()` (debug pickup + lazy rebuild of the shared
program), GL state save, bind program + VAO, shared uniforms, polygon `points[]` + `pointCount`,
then each enabled layer's `uploadUniforms` from the owner's own layer list.
`PlasmaLayer` binds its two noise maps (units 0 and 1) and converts hit spots from ship-local to
screen at upload time. `DistortionLayer` copies the framebuffer under the shield's bounding box into
the shared `ShieldScreenCopy` texture (unit 2) during its upload — only while a ring is running.

### GPU, per pixel

```text
vert: quad NDC -> gl_Position; TexCoord = NDC * 0.5 + 0.5
frag: pixelPos = TexCoord * resolution        (screen coords — same space as points[])
  1. debug_check() — gated on debugMode inside debug.glsl; may paint bbox/points/outline and return
  2. !isPointInPolygon(pixelPos) -> discard   (the mask: base controls WHERE layers render)
  3. shieldFrag(pixelPos)                     (the mix chain above)
  -> FragColor, alpha-blended once onto the framebuffer
```

Key invariant: **everything meets in screen space.** Polygon converted on CPU, `pixelPos` derived
from `TexCoord * resolution`, hit spots converted at upload — the fragment shader never
transforms anything except the two ship-relative rotations it needs (sweep axis, hex-grid
alignment), both from the precomputed `shipRotationMatrix` / `shipFacing`.

## CPU state flow (who calls what)

```text
CovenantShieldsRenderPlugin
  advance(dt):   shader.updateTime(dt)   (-> program.advance: every layer's advance)
  render(...):   shader.updatePolygon(...); shader.updateColor/Border/HPPercent(...);
                 shader.setFortressLevel(...); shader.render(viewport)
  on hit:        shader.triggerHit(x, y, level)
  on break:      shader.startCollapse();  regen resume: shader.triggerRecharge()

ShieldShader (owner)          delegates to layers:
  updateColor          -> fillLayer.setColor
  updateShieldHPPercent-> plasmaLayer.effect.vitality
  updateBorder         -> borderLayer.setBorder
  updatePolygon        -> sweepLayer.sweepPosition (computed here — needs polygon bounds + cloak level;
                          held at full while collapsing); plasmaLayer.setBodyMetrics(depth, radius)
  triggerHit           -> plasmaLayer.effect.addHit + distortionLayer.addWave
  startCollapse/triggerRecharge -> plasmaLayer.effect (collapse also clears the distortion rings)
  calculateBoundingBox -> debugLayer.setBoundingBox; distortionLayer.setCopyRegion
  setHoneycombEnabled  -> honeycombLayer.setEnabled
  setFortressLevel     -> fortressLayer.level; honeycombLayer.breathing = 1 - level
  setDebugMode         -> debugLayer.enabled; calculateBoundingBox -> debugLayer.setBoundingBox
```

Note `updatePolygon` must run every frame (the plugin does): sweep position derives from
`phaseCloak.effectLevel` there.

## Historical note

The shield began as a single ~330-line fragment shader + ~1075-line Kotlin monolith. The
composition refactor split it mechanically (pixel-identical), then the debug tooling, the generic
extraction, and the move into LemonLib followed. History and decisions live in the Covenant
Remnants repo: `planning/implementations/shield-shader-composition.md`,
`planning/implementations/shader-composition-generic.md`,
`planning/implementations/lemonlib-extraction.md`.
