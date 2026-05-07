# Offline asset pipeline for materials and IBL

## Context and Problem Statement

Filament needs platform-compiled `.filamat` materials (Metal for iOS,
OpenGL+Vulkan for Android) and pre-baked IBL artifacts (KTX cubemaps +
spherical harmonics) at runtime. We need a deterministic, repeatable
way to produce these from authored sources (`.mat`, `.hdr`).

## Considered Options

* Run Filament's `matc` / `cmgen` as scripted prebuild steps; commit
  outputs.
* Compile materials at runtime via Filament's reflection APIs.
* Ship Filament's CLI binaries as part of the published packages and
  compile on consumer install.

## Decision Outcome

Chosen option: **scripted prebuild via `scripts/matc.ts` and
`scripts/cmgen.ts`**, outputs land in `vto-core-native/{ios,android}/assets/`
and are bundled into both wrappers. Authoring lives at
`packages/vto-core-native/assets/`; nothing compiles on consumer install.

### Consequences

* Good: zero runtime compilation; consumers never need Filament's CLI.
* Good: one author edit (`.mat` / `.hdr`) → one script run → both
  platforms in sync.
* Bad: editing a shader is two steps (edit + run script) instead of
  hot-reload.
* Bad: Filament's CLI must be on PATH for contributors who touch
  materials or IBL; not all contributors will have it.
