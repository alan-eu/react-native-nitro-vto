#ifndef LensConstants_h
#define LensConstants_h

// Tuning constants for the clip-on / solar lens treatment. Mirrored on Android
// in LensConstants.kt — keep the two in sync.
//
// Clip-on lenses are rendered with our own lit glass material (clipon_lens.mat,
// screen-space refraction) swapped in over the glb's lens. It keeps the
// realistic refraction see-through but lets us control the reflection so it
// doesn't read as chrome. The lens color is the glb's authored baseColor,
// brightness-capped (hue-preserving), filtering the transmitted view.

// Max allowed channel of the clip-on tint. If the glb baseColor's brightest
// channel exceeds this, RGB is scaled down uniformly (hue-preserving) — so a
// near-white lens darkens to a sunglass tint while colored lenses keep their
// hue.
static const float kLensClipOnMaxChannel = 0.15f;

// Dielectric reflectance of the clip-on lens (Filament F0 = 0.16·reflectance²).
// Low = the IBL barely reflects (no chrome) while keeping a faint glassy sheen.
// Raise for more reflection/pop, lower toward 0 to kill the reflection
// entirely.
static const float kLensClipOnReflectance = 0.40f;

#endif /* LensConstants_h */
