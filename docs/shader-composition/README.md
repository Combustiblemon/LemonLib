# Shader composition system

A small framework for building OpenGL fragment shaders out of composable **layers** instead of one
monolithic source file. Each visual effect is a pair: a GLSL module file + a Kotlin class that owns
its state and uniforms. At init time the framework concatenates the modules into a single fragment
shader and compiles **one program** — one draw call, no multi-pass blending, no shader permutation
explosion.

Part of **LemonLib** (`org.combustiblelemon.lemonlib`). Lives in
`src/org/combustiblelemon/lemonlib/render/composition/`:

| File | What it is |
| --- | --- |
| `ShaderLayer.kt` | Interface every layer implements: module file, debug stub, uniform uploads. |
| `ComposedShaderProgram.kt` | The engine: assembles modules, compiles/links, rebuilds on layer-set changes, translates GLSL errors back to module files. |
| `CompositionDebug.kt` | Global registry + per-system layer overrides; what the `ShaderLayers` console command talks to. |

First consumer: the Covenant shield in the **Covenant Remnants** mod
(`utils/render/ShieldShader.kt` + `data/shaders/shield/` there).
Read [example-shield.md](example-shield.md) for the full real-world walkthrough.

## The docs

| Doc | Read it when |
| --- | --- |
| [creating-a-system.md](creating-a-system.md) | You want a new composed shader (base template, vertex shader, owner class, placeholders). Includes a complete minimal "aura" example. |
| [writing-layers.md](writing-layers.md) | You want to add/edit a layer in an existing system (module contract, stubs, uploads). Includes a complete "pulse" layer example. |
| [debugging.md](debugging.md) | Something looks wrong or won't compile (solo-layer rendering, `ShaderLayers` command, error translation, failure-mode table). |
| [example-shield.md](example-shield.md) | You want to see the whole thing working for real (shield case study + data path). |

## Design philosophy

**Composition at the source level, not the draw-call level.** There are two ways to "split a
shader": give every effect its own program and draw call (multi-pass), or keep effects in separate
files that get concatenated into one program (source composition). This system does the latter, on
purpose:

- Effects here **mix into each other inside the shader** (`mix(fill, border, f)`), *before* the
  single alpha-blend onto the framebuffer. Multi-pass would blend each effect against the
  framebuffer separately — visibly different output — and moves inter-effect coupling into blend
  equations and draw order: invisible GL state with no compiler and no error messages.
  Concatenation fails loud at compile with a line number; blend state fails quiet with wrong pixels.
- One program, one draw call: no per-pass GL state juggling inside Starsector's legacy renderer.
- The composition benefit is for the **human editing the code**, not the GPU. The GPU sees the
  same monolith it always saw; you see six small files.

**Ownership.** Everything about an effect lives in its layer pair: uniforms declared in its
`.glsl`, CPU state in its class, uploads in its `uploadUniforms`. A dead effect cannot hide — if a
class uploads a uniform, that uniform exists in the class's own module by construction.

**Contracts instead of machinery.** No `#include` preprocessor. Four rules, three of them
compiler-enforced (details in [writing-layers.md](writing-layers.md)):

1. Module functions are prefixed `<layerName>_`.
2. Every uniform is declared in exactly one file.
3. Cross-module reads only point backwards in layer-list order.
4. The call order in the base template's orchestration function is the pixel contract.

**Runtime toggles, not compile toggles.** All layers are always part of the program; effects turn
off via uniforms (`honeycombEnabled = 0`, `numRipples = 0`). Exactly one program variant exists per
system — deterministic output, no variant explosion. The only exception is the debug solo-layer
mode, which deliberately recompiles with stubs ([debugging.md](debugging.md)).

## Quick start

```kotlin
// 1. Layers, in render order (order = GLSL concat order = upload order)
private val layers: List<ShaderLayer> = listOf(glowLayer, tintLayer)

// 2. The program (compiles immediately — construct on the GL thread)
private val program = ComposedShaderProgram(
    systemName = "aura",                              // key for the ShaderLayers debug command
    vertexShaderFile = "data/shaders/aura/aura.vert",
    baseFragmentFile = "data/shaders/aura/base.frag", // contains // {{LAYER_MODULES}} marker
    layers = layers,
    placeholders = mapOf()                            // optional compile-time text substitutions
)

// 3. Per frame, inside your render pass:
program.ensureUpToDate()               // picks up ShaderLayers debug changes, rebuilds if needed
glUseProgram(program.programId)
// ... upload your shared uniforms ...
program.uploadLayerUniforms(viewport)  // each enabled layer uploads its own
glDrawArrays(...)

// 4. On cleanup:
program.dispose()
```

Debug any system from the in-game console:

```text
ShaderLayers                    -> list registered systems + layers
ShaderLayers shield border      -> render only the shield's border layer
ShaderLayers shield all         -> back to normal
```

## What the engine does and does not own

`ComposedShaderProgram` owns **the program lifecycle only**: assembly, compilation, linking,
error translation, debug rebuilds, deletion. The owning shader class keeps everything else —
geometry (VAO/VBO), the render loop, GL state save/restore, and uploads of *shared* uniforms
(those declared in the base template). This split keeps the engine reusable for shaders with
completely different geometry and orchestration.
