# Writing layers

A layer is one visual effect: a GLSL module file + a Kotlin class implementing `ShaderLayer`.
This doc is the complete contract, with a worked example — a **pulse** layer that periodically
brightens whatever is below it.

## The interface

`lemonlib/render/composition/ShaderLayer.kt`:

```kotlin
interface ShaderLayer {
    /** Stable identifier used to enable/disable this layer (e.g. "fill", "border"). */
    val layerName: String

    /** Path to the file containing this layer's GLSL module (e.g. under data/shaders/). */
    val moduleFile: String

    /** Neutral GLSL stub substituted for the module when this layer is disabled. */
    val stubSource: String

    /** Upload this layer's uniforms. Called with the program already bound. */
    fun uploadUniforms(program: Int, viewport: ViewportAPI)

    /** Optional per-frame CPU-side state advance (seconds). */
    fun advance(deltaTime: Float) {}
}
```

## The module contract

Four rules. Break one and either the GLSL compiler stops you (rules 1–3) or the picture changes
(rule 4).

1. **Prefix every function with `<layerName>_`.** `pulse_apply`, not `apply`. Two modules
   defining `apply` = redefinition compile error; the prefix makes collisions impossible.

2. **Declare every uniform in exactly one file.** If only your layer reads it, declare it in your
   module. If two or more modules read it (or the base template does), it is a *shared* uniform:
   declared in `base.frag`, uploaded by the owner class. Declaring the same global uniform twice
   is a GLSL compile error — this rule is self-enforcing.

3. **Cross-module reads only point backwards.** The layer list order is the concatenation order;
   GLSL requires declaration before use. If your module reads another layer's uniform or calls its
   function, that layer must come earlier in the list — and you must document the dependency in a
   comment at the top of your module. Real example: the shield's `sweep.glsl` reads `borderColor`,
   so border precedes sweep in the list.

4. **The orchestration call order in `base.frag` is the pixel contract.** Layers usually take the
   running color and return a modified one (`vec4 x_apply(vec4 color, vec2 pixelPos)`). Where your
   call sits in that chain decides what renders on top of what. Adding a layer = adding one call
   at the right place; reordering calls changes the output.

## Writing the module

`data/shaders/aura/pulse.glsl`:

```glsl
// Pulse layer — periodic brightness boost.
uniform float pulseStrength;  // 0 = off
uniform float pulseSpeed;

vec4 pulse_apply(vec4 color, vec2 pixelPos) {
    if (pulseStrength <= 0.0) return color;
    float wave = 0.5 + 0.5 * sin(time * pulseSpeed);   // `time` is a shared uniform from base.frag
    return vec4(color.rgb * (1.0 + pulseStrength * wave), color.a);
}
```

Notes:

- Reading **shared** uniforms/helpers from the base template (`time`, `resolution`,
  `distToCenter(...)`) is always fine — the template precedes all modules.
- Prefer runtime toggles via your own uniforms (`pulseStrength <= 0.0` above) over conditional
  compilation. All layers are always compiled in; disabling is a uniform, not a rebuild. (The
  debug solo mode is the only rebuild path.)

## Writing the stub

The stub replaces your module when the layer is disabled for debugging. Requirements:

- **Same function signatures** as the module — the orchestration code in `base.frag` still calls
  them.
- **Identity/no-op behavior** — pass the color through, return transparent, return false.
- **No uniform declarations** — the engine skips your `uploadUniforms` while disabled, so any
  uniform you declared would sit at its default and any upload would target location -1.
- **Exception:** if a *later* module reads one of your uniforms (rule 3 dependency), your stub
  must still declare that uniform, or the later module won't compile while you're disabled. It
  will hold its zero default — document the visual consequence. Real example: `BorderLayer`'s stub
  declares `borderColor` because `sweep.glsl` reads it; with border disabled the sweep edge goes
  invisible (color stays vec4(0)).

```kotlin
override val stubSource = """
    vec4 pulse_apply(vec4 color, vec2 pixelPos) { return color; }
""".trimIndent()
```

Keep the stub inline in the Kotlin class — it is part of the layer's ownership, and it is small.

## Writing the class

```kotlin
package org.combustiblelemon.yourmod.render.aura

import com.fs.starfarer.api.combat.ViewportAPI
import org.combustiblelemon.lemonlib.render.composition.ShaderLayer
import org.lwjgl.opengl.GL20.glGetUniformLocation
import org.lwjgl.opengl.GL20.glUniform1f

/**
 * Periodic brightness pulse over the layers below it.
 */
class PulseLayer : ShaderLayer {
    override val layerName = "pulse"
    override val moduleFile = "data/shaders/aura/pulse.glsl"
    override val stubSource = """
        vec4 pulse_apply(vec4 color, vec2 pixelPos) { return color; }
    """.trimIndent()

    private var strength = 0f
    private var speed = 2f

    fun setPulse(strength: Float, speed: Float) {
        this.strength = strength.coerceIn(0f, 1f)
        this.speed = speed
    }

    override fun uploadUniforms(program: Int, viewport: ViewportAPI) {
        glUniform1f(glGetUniformLocation(program, "pulseStrength"), strength)
        glUniform1f(glGetUniformLocation(program, "pulseSpeed"), speed)
    }
}
```

### Upload patterns

Scalars and vectors, straight through:

```kotlin
glUniform1f(glGetUniformLocation(program, "borderWidth"), borderWidth)
glUniform4f(glGetUniformLocation(program, "borderColor"), c[0], c[1], c[2], c[3])
glUniform1i(glGetUniformLocation(program, "honeycombEnabled"), if (enabled) 1 else 0)
```

Uniform arrays — index in the name, count in a companion int uniform, and convert units at upload
time if the shader works in a different space (the shield's plasma hit spots convert ship-local to
screen here):

```kotlin
glUniform1i(glGetUniformLocation(program, "plasmaHitCount"), count)
for (i in 0 until count) {
    val spot = effect.spots[i]
    val worldX = ship.location.x + spot.localX * c - spot.localY * s
    val worldY = ship.location.y + spot.localX * s + spot.localY * c
    glUniform4f(
        glGetUniformLocation(program, "plasmaHits[$i]"),
        viewport.convertWorldXtoScreenX(worldX), viewport.convertWorldYtoScreenY(worldY),
        spot.radiusFraction * bodyRadiusPx, spot.strength
    )
}
```

Textures — declare the `sampler2D` in the layer's module only (never in the stub), bind on a
fixed unit and upload the unit index **every frame** (the program can be rebuilt by `ShaderLayers`,
and unset samplers default to unit 0); the owner saves/restores the active unit and the bindings of
every unit the layer touches (the shield's `PlasmaLayer` uses units 0 and 1). Past a non-uniform
`discard` use `textureLod(…, 0.0)` — derivatives are undefined there.

If the loop in the shader iterates a *fixed-size* array rather than a count, zero-fill the unused
slots. Prefer a count uniform though: a loop with a compile-time constant bound gets fully unrolled
by the driver, which multiplies compile time (the shield moved its six outline loops from a
placeholder constant to a `pointCount` uniform for that reason).

### CPU-side state and `advance`

Layers holding animated state (hit lists, timers) put the data in the class and step it in
`advance(deltaTime)`. The owner calls `program.advance(dt)` to forward to all layers (the shield
does this from `updateTime`), or calls a layer's own richer method directly when it needs extra
parameters.

## Wiring the layer in

Three edits in the owning system:

1. Instantiate the class and add it to the `layers` list **at the position matching its render
   order** (list order = concat order = upload order — and remember rule 3 if anything reads your
   uniforms).
2. Add one call in `base.frag`'s orchestration function at the right point in the chain:
   `result = pulse_apply(result, pixelPos);`
3. Expose whatever setters the owner should forward (e.g. `auraShader.setPulse(...)` delegating to
   the layer).

Then build (`gradlew buildMod`) and run — GLSL mistakes surface at first render with a
module-tagged error ([debugging.md](debugging.md)). Verify visually, including
`ShaderLayers <system> <yourlayer>` solo.
