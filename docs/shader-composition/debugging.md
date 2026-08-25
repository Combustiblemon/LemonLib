# Debugging composed shaders

Two tools ship with the system: **solo/subset layer rendering** (see exactly one layer's
contribution) and **module-tagged compile errors** (driver errors point at the guilty module file,
not the assembled blob). Plus a table of the failure modes you will actually hit.

## Solo / subset layer rendering

Every layer carries a neutral stub (identity functions, no uniforms). Disabling a layer swaps its
module for the stub and recompiles — the picture then shows exactly the enabled layers, with
correct interactions among them, because the orchestration code is unchanged.

### From the console (Console Commands mod)

```text
ShaderLayers
    shield: layers [fill, border, sweep, ripple, honeycomb, debug], enabled: all

ShaderLayers shield border
    shield: enabled layers border.
    -> every shield now renders only its border ring

ShaderLayers shield fill,honeycomb
    shield: enabled layers fill, honeycomb.

ShaderLayers shield all
    shield: all layers enabled.
```

Details:

- Systems **register on first construction** — `shield` appears in the list once a shielded ship
  has existed in combat. Before that: "No composed shader systems registered yet".
- The override is **global per system**: all instances (every ship's shield) pick it up on their
  next `render()` and rebuild. New instances spawned later also honor it.
- Rebuild is mid-frame safe: old program deleted, new one compiled, same VAO/uniform flow.
- Layer selection is validated; a typo lists the valid names.

### From code

```kotlin
// Per instance:
program.setEnabledLayers(setOf("fill", "border"))   // empty/unknown-only set coerces to all
program.getLayerNames()                              // canonical names

// Globally, same thing the command does:
CompositionDebug.setEnabledLayers("shield", setOf("border"))
CompositionDebug.setEnabledLayers("shield", null)    // clear override = all layers
```

Either way, nothing happens until the owner's next `program.ensureUpToDate()` (top of render) —
that is the GL-thread synchronization point.

### Reading solo output

- A layer that *adds* on top of others (border, ripples, honeycomb) shows against a transparent
  interior when solo'd — the fill stub returns `vec4(0)`.
- A **rule-3 dependency** dims with its provider: disable `border` and the sweep edge goes
  invisible, because sweep reads `borderColor` and the border stub leaves it at zero. That is
  expected, not a bug — check the layer's stub comment.

## Module-tagged compile errors

GLSL compiles at **runtime** (first render of the system), never at Gradle build. When a module is
broken, combat start crashes with a `RuntimeException` whose message has been translated.

Example — a typo in `honeycomb.glsl` line 14 (`flaot` instead of `float`):

```text
RuntimeException: [shield] Fragment shader compilation failed:
Translated to module files:
honeycomb.glsl:14  [0(213) : error C0000: syntax error, unexpected identifier at token "flaot"]

Original driver log:
0(213) : error C0000: syntax error, unexpected identifier at token "flaot"
```

- `honeycomb.glsl:14` is the file + **local line** you open and fix. `0(213)` is the raw driver
  reference into the assembled source.
- Both NVIDIA (`0(213) : error`) and AMD/Intel/Mesa (`ERROR: 0:213:`) log formats are parsed. If a
  driver prints something else entirely, you get the untranslated log — see the dump below.
- Errors in the header/footer of the template are tagged `base.frag (header)` / `base.frag (footer)`.
- On any fragment compile failure the engine also dumps the **complete assembled source,
  line-numbered**, to `starsector.log` at error level, tagged `[<systemName>]`. That is the ground
  truth when the translation is not enough — find the driver's line number in the dump.

## Failure modes

| Symptom | Cause | Fix |
| --- | --- | --- |
| Compile error: redefinition of uniform | Same uniform declared in two files (module + base, or two modules) | Rule 2: one declaration. Shared uniforms live in `base.frag` only. |
| Compile error: undeclared identifier `<x>_something` | Module calls a function from a layer *later* in the list, or the layer was removed from the list but its call is still in the orchestration function | Rule 3: reorder the list, or fix `base.frag`. |
| Compile error: undeclared identifier for a uniform, only when some layer is disabled | A later module reads a uniform declared in the disabled layer's module, and the stub does not re-declare it | Add the declaration to the stub (see the border/sweep exception in [writing-layers.md](writing-layers.md)). |
| Compile error mentioning your stub name in the segment tag | Stub signature drifted from the module's (renamed a function in the module, forgot the stub) | Keep stub signatures identical to the module's. |
| `RuntimeException: ... missing the // {{LAYER_MODULES}} insertion marker` | Marker deleted or altered in `base.frag` | Restore the exact marker line. |
| `IllegalArgumentException: Placeholder values must be single-line` at construction | Multi-line placeholder value | Placeholders are for constants (array sizes); keep values single-line. |
| Effect renders under/over the wrong thing | Call order changed in the orchestration function | Rule 4: the call chain is the pixel contract — restore the order. |
| Layer visually dead, no error | Its enabling uniform is zero (never uploaded, or `uploadUniforms` misspells the name — `glGetUniformLocation` returns -1 and the write is silently dropped) | Check the uniform name string matches the module declaration exactly. Solo the layer to confirm. |
| Everything gone / crash at combat start after editing GLSL | Any compile error (see above) — the exception aborts plugin creation | Read the translated message + the assembled dump in `starsector.log`. |
| Debug override seems ignored | Owner never calls `program.ensureUpToDate()` in its render pass | Add the call at the top of `render()`. |

## Workflow that works

1. Edit one module.
2. `gradlew buildMod` (catches Kotlin errors only).
3. Launch combat. If it crashes, the message names the module + line; `starsector.log` has the
   full assembled source.
4. Once it runs, `ShaderLayers <system> <layer>` to eyeball your layer alone, then
   `ShaderLayers <system> all` and check the composite.
