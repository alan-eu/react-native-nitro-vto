# Use Google Filament as the cross-platform rendering engine

## Context and Problem Statement

We render PBR glasses with screen-space refraction (lens transmission)
and IBL on top of an AR camera feed, on both iOS and Android. We need
one renderer rather than two diverging native ones.

## Considered Options

* Google Filament (Metal + GL/Vulkan, gltfio, transmission, IBL)
* SceneKit/RealityKit on iOS + SceneForm on Android

## Decision Outcome

Chosen option: **Google Filament**, because it's the only path to a
single shading codebase that produces matching results on iOS and
Android while still supporting `KHR_materials_transmission` for the
lenses out of the box.

SceneForm is unmaintained on Android.

### Consequences

* Good: visual parity across platforms; same `.glb`, same `.filamat`,
  same IBL.
* Good: screen-space refraction and gltfio asset loading for free.
* Bad: heavyweight library; offline material/IBL compilation step
  (`matc`, `cmgen`).
* Bad: platform leaks at the engine boundary (Android GL refraction
  fails with `setCustomProjection`, requires the FOV-extraction
  workaround in `VTORenderer.kt`).
