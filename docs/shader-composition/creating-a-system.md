# Creating a composed shader system

How to build a new shader on the composition framework, end to end. The running example is a
fictional minimal system called **aura**: a soft radial glow around a point, with two layers —
`glow` (radial falloff color) and `tint` (screen-space color grade on top).

A "system" is:

1. a **base fragment template** (`base.frag`) — shared uniforms, helpers, the module insertion
   marker, and the orchestration code that calls layer functions in order;
2. a **vertex shader** — usually a trivial quad passthrough;
3. one **GLSL module + Kotlin class per layer** (see [writing-layers.md](writing-layers.md));
4. an **owner class** — your `Shader` implementation that owns geometry and the render loop, and
   delegates program lifecycle to `ComposedShaderProgram`.

Convention (in the consuming mod): GLSL for system `<name>` lives in `data/shaders/<name>/`; the
owner class in the mod's render package; its layer classes in a `<name>/` subpackage next to it.

## 1. The base fragment template

`data/shaders/aura/base.frag`:

```glsl
#version 330 core
out vec4 FragColor;

in vec2 TexCoord;

// Shared uniforms — used by more than one layer module.
// Layer-specific uniforms live in each layer's .glsl module.
uniform vec2 resolution;
uniform vec2 center;      // effect center, screen coordinates
uniform float time;

// Shared helpers go here (declared before the modules so every module can use them)
float distToCenter(vec2 pixelPos) {
    return distance(pixelPos, center);
}

// {{LAYER_MODULES}}

vec4 auraFrag(vec2 pixelPos) {
    vec4 result = glow_color(pixelPos);   // 1. base coat
    result = tint_apply(result, pixelPos); // 2. grade on top — ORDER IS THE PIXEL CONTRACT
    return result;
}

void main() {
    vec2 pixelPos = TexCoord * resolution;
    vec4 color = auraFrag(pixelPos);
    if (color.a <= 0.0) discard;
    FragColor = color;
}
```

Template rules:

- **The marker line `// {{LAYER_MODULES}}` is mandatory** — the engine splices layer modules
  there, in layer-list order. Missing marker = `RuntimeException` at construction.
- **Shared uniforms** (read by two or more modules, or by the template itself) are declared here,
  *only* here, and uploaded by the owner class. Everything single-layer lives in that layer's
  module.
- **Shared helpers** must be declared *before* the marker so modules can call them.
- The **orchestration function** (`auraFrag` above) is where layers combine. The call order is the
  visual contract — document it with a comment. Inter-layer control flow (early returns, one
  layer masking another) belongs here, visible, not hidden inside a module. Real example: the
  shield's sweep cull ([example-shield.md](example-shield.md)).
- `main()` should reference layer functions only through the orchestration path, so that any layer
  can be stubbed out for debugging.

## 2. The vertex shader

Most composed systems render a screen-space quad, so the vertex shader is a passthrough.
`data/shaders/aura/aura.vert`:

```glsl
#version 330 core
layout (location = 0) in vec2 aPos; // NDC coordinates of the quad

out vec2 TexCoord;

void main() {
    gl_Position = vec4(aPos, 0.0, 1.0);
    TexCoord = aPos * 0.5 + 0.5;
}
```

The vertex shader is compiled as-is — no modules, no placeholders. If your system needs real
vertex work, it all lives in this one file.

## 3. Layers

See [writing-layers.md](writing-layers.md) for the full contract. For the example, the two
modules:

`data/shaders/aura/glow.glsl`:

```glsl
// Glow layer — radial falloff from center.
uniform vec4 glowColor;
uniform float glowRadius;

vec4 glow_color(vec2 pixelPos) {
    float d = distToCenter(pixelPos);              // shared helper from base.frag
    float falloff = 1.0 - clamp(d / glowRadius, 0.0, 1.0);
    return vec4(glowColor.rgb, glowColor.a * falloff * falloff);
}
```

`data/shaders/aura/tint.glsl`:

```glsl
// Tint layer — flat color grade over whatever is below it.
uniform vec4 tintColor; // a = strength

vec4 tint_apply(vec4 color, vec2 pixelPos) {
    return vec4(mix(color.rgb, tintColor.rgb, tintColor.a), color.a);
}
```

And their Kotlin classes in `utils/render/aura/` (only `GlowLayer` shown; `TintLayer` is
analogous):

```kotlin
class GlowLayer : ShaderLayer {
    override val layerName = "glow"
    override val moduleFile = "data/shaders/aura/glow.glsl"
    override val stubSource = """
        vec4 glow_color(vec2 pixelPos) { return vec4(0.0); }
    """.trimIndent()

    private var color = floatArrayOf(0.5f, 0.8f, 1.0f, 0.6f)
    private var radius = 120f

    fun set(r: Float, g: Float, b: Float, a: Float, radiusPx: Float) {
        color = floatArrayOf(r, g, b, a)
        radius = radiusPx
    }

    override fun uploadUniforms(program: Int, viewport: ViewportAPI) {
        glUniform4f(glGetUniformLocation(program, "glowColor"), color[0], color[1], color[2], color[3])
        glUniform1f(glGetUniformLocation(program, "glowRadius"), radius)
    }
}
```

## 4. The owner class

The owner implements LemonLib's `Shader` interface
(`org.combustiblelemon.lemonlib.render.Shader` — `render(viewport)`, `cleanup()`) and owns
everything that is not program lifecycle:

- **Geometry** — VAO/VBO for its quad (or whatever it draws).
- **GL state** — save/restore around the draw so Starsector's renderer is untouched.
- **Shared uniforms** — everything declared in `base.frag`.
- **Cross-cutting CPU work** — anything spanning layers (the shield's polygon smoothing and sweep
  position, for instance).

```kotlin
class AuraShader(private val entity: CombatEntityAPI) : Shader {

    private val glowLayer = GlowLayer()
    private val tintLayer = TintLayer()

    // List order = GLSL concat order = upload order
    private val layers: List<ShaderLayer> = listOf(glowLayer, tintLayer)

    private val program = ComposedShaderProgram(
        systemName = "aura",
        vertexShaderFile = "data/shaders/aura/aura.vert",
        baseFragmentFile = "data/shaders/aura/base.frag",
        layers = layers
    )

    private var vao = 0
    private var vbo = 0
    private var time = 0f

    init {
        // build the quad VAO/VBO here (see ShieldShader.initQuad for a full example)
    }

    fun advance(amount: Float) {
        time += amount
        program.advance(amount)   // forwards to layers that keep CPU-side state
    }

    override fun render(viewport: ViewportAPI) {
        program.ensureUpToDate()  // debug layer-set pickup + lazy rebuild; MUST be on GL thread

        // ... save GL state (see ShieldShader.render for the full save/restore block) ...
        glUseProgram(program.programId)
        glBindVertexArray(vao)

        // Shared uniforms — owner's job
        glUniform2f(glGetUniformLocation(program.programId, "resolution"), width, height)
        glUniform2f(glGetUniformLocation(program.programId, "center"), screenX, screenY)
        glUniform1f(glGetUniformLocation(program.programId, "time"), time)

        // Layer uniforms — each enabled layer uploads its own
        program.uploadLayerUniforms(viewport)

        glEnable(GL_BLEND)
        glBlendFunc(GL_SRC_ALPHA, GL_ONE_MINUS_SRC_ALPHA)
        glDrawArrays(GL_TRIANGLE_FAN, 0, 4)
        // ... restore GL state ...
    }

    override fun cleanup() {
        // delete VAO/VBO, then:
        program.dispose()
    }
}
```

## Placeholders

`ComposedShaderProgram` takes an optional `placeholders: Map<String, String>`. Each key is
literally text-replaced in the assembled source, **after** module concatenation — so a placeholder
used inside a module resolves too. Use them for values GLSL requires as compile-time constants,
e.g. array sizes:

```kotlin
placeholders = mapOf("POINT_COUNT_PLACEHOLDER" to pointCount.toString())
```

```glsl
uniform vec2 points[POINT_COUNT_PLACEHOLDER];
```

**Values must be single-line** — the engine asserts this at construction, because a multi-line
replacement would shift line numbers and break the error translation map
([debugging.md](debugging.md)).

## Caveats

- **Construction compiles eagerly and needs a live GL context.** Create the program (and the owner)
  on the render thread — combat plugin construction/render is fine; a background thread is not.
- **GLSL errors surface at runtime, not at Gradle build.** First combat with a broken module
  crashes with a translated error message; see [debugging.md](debugging.md) for reading it.
- `systemName` must be unique per system — it is the registry key for the `ShaderLayers` command.
  Multiple instances of the same system (one per ship, say) sharing a name is correct and
  intended: the debug override then applies to all of them.
- `program.advance(dt)` is a convenience that forwards to every layer's `advance`. Owners with
  their own per-layer update logic (like the shield's ripple speed parameter) can skip it and call
  layer methods directly.
