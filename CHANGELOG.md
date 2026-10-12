# Changelog

All notable changes to LemonLib, newest version first.

## Unreleased

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
