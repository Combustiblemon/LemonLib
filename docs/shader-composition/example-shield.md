# Case study: the Covenant shield

The shield is the first real consumer of the composition system and the reference implementation
for every convention in these docs. This page maps the whole thing: files, layers, and the data
path from ship hull to pixels.

## Files

All paths below are in the **Covenant Remnants** mod unless marked LemonLib:

| Piece | Path |
| --- | --- |
| Owner class | `src/.../covenantremnants/utils/render/ShieldShader.kt` |
| Layer classes | `src/.../covenantremnants/utils/render/shield/{Fill,Border,Sweep,Ripple,Honeycomb,Debug}Layer.kt` |
| CPU polygon smoothing | `org.combustiblelemon.lemonlib.geometry.PolygonSmoothing` (LemonLib) |
| Base template + vert | `data/shaders/shield/base.frag`, `data/shaders/shield/shield.vert` |
| Layer modules | `data/shaders/shield/{fill,border,sweep,ripple,honeycomb,debug}.glsl` |
| Consumer | `src/.../covenantremnants/plugins/CovenantShieldsRenderPlugin.kt` (per-ship combat render plugin) |

System name: `"shield"` — so `ShaderLayers shield ...` targets it.

## Layer table

List order below **is** the GLSL concat order and upload order:

| # | Layer | Module | Owns (uniforms) | Effect |
| --- | --- | --- | --- | --- |
| 1 | `fill` | `fill.glsl` | `shieldColor`, `shieldHPPercent` | Base coat; brighter + more opaque as HP drops |
| 2 | `border` | `border.glsl` | `borderColor`, `borderWidth` | Ring along the polygon edges |
| 3 | `sweep` | `sweep.glsl` | `sweepPosition`, `sweepEdgeWidth` | Activation wipe nose-to-tail + edge glow |
| 4 | `ripple` | `ripple.glsl` | `numRipples` + 5 `ripple*[10]` arrays | Impact rings |
| 5 | `honeycomb` | `honeycomb.glsl` | `honeycombEnabled`, `honeycombSize`, `edgeWidth` | Animated hex grid |
| 6 | `debug` | `debug.glsl` | `debugMode`, `boundingBoxMin/Max` | Red bbox, green points, magenta outline |

Shared uniforms (declared in `base.frag`, uploaded by `ShieldShader`): `points[N]` (polygon,
screen coords; `N` via the `POINT_COUNT_PLACEHOLDER` placeholder), `resolution`, `center`,
`shipRotationMatrix`, `viewportScale`, `shipFacing`, `time`. Shared helpers: `distanceToLine`,
`isPointInPolygon`.

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
    result = ripple_apply(result, pixelPos);       // 3. ripples mix
    result = honeycomb_apply(result, pixelPos);    // 4. honeycomb mix
    return result;
}
```

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

`render()` does, in order: `program.ensureUpToDate()` (debug pickup + lazy rebuild), GL state
save, bind program + VAO, shared uniforms, polygon `points[]` (zero-filling unused slots — the
polygon test iterates the full fixed-size array), then `program.uploadLayerUniforms(viewport)`.
`RippleLayer` converts ripple centers/radii/widths from world to screen at upload time.

### GPU, per pixel

```text
vert: quad NDC -> gl_Position; TexCoord = NDC * 0.5 + 0.5
frag: pixelPos = TexCoord * resolution        (screen coords — same space as points[])
  1. debugMode? debug_check() may paint bbox/points/outline and return
  2. !isPointInPolygon(pixelPos) -> discard   (the mask: base controls WHERE layers render)
  3. shieldFrag(pixelPos)                     (the mix chain above)
  -> FragColor, alpha-blended once onto the framebuffer
```

Key invariant: **everything meets in screen space.** Polygon converted on CPU, `pixelPos` derived
from `TexCoord * resolution`, ripple parameters converted at upload — the fragment shader never
transforms anything except the two ship-relative rotations it needs (sweep axis, hex-grid
alignment), both from the precomputed `shipRotationMatrix` / `shipFacing`.

## CPU state flow (who calls what)

```text
CovenantShieldsRenderPlugin
  advance(dt):   shader.updateTime(dt); shader.updateRipple(dt, 1000f)
  render(...):   shader.updatePolygon(...); shader.updateColor/Border/HPPercent(...); shader.render(viewport)
  on hit:        shader.startRipple(x, y, ...)

ShieldShader (owner)          delegates to layers:
  updateColor          -> fillLayer.setColor
  updateShieldHPPercent-> fillLayer.setShieldHPPercent
  updateBorder         -> borderLayer.setBorder
  updatePolygon        -> sweepLayer.sweepPosition (computed here — needs polygon bounds + cloak level)
  startRipple/updateRipple -> rippleLayer.startRipple / update(dt, speed, viewportScale)
  setHoneycombEnabled  -> honeycombLayer.setEnabled
  setDebugMode         -> debugLayer.enabled; calculateBoundingBox -> debugLayer.setBoundingBox
```

Note `updatePolygon` must run every frame (the plugin does): sweep position derives from
`phaseCloak.effectLevel` there. Ripple update needs the cached viewport scale, which the owner
refreshes in both `updatePolygon` and `render`.

## Historical note

The shield began as a single ~330-line fragment shader + ~1075-line Kotlin monolith. The
composition refactor split it mechanically (pixel-identical), then the debug tooling, the generic
extraction, and the move into LemonLib followed. History and decisions live in the Covenant
Remnants repo: `planning/implementations/shield-shader-composition.md`,
`planning/implementations/shader-composition-generic.md`,
`planning/implementations/lemonlib-extraction.md`.
