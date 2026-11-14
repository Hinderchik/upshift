# Changelog

## 1.0.0 - Initial VR Edition Release

### Added

- **Per-eye temporal upscaling**: Separate history buffers and temporal matrices for left and right eyes. Prevents cross-eye contamination that would break stereo depth.
- **Stereo detection**: Automatically detects when Vivecraft is rendering in stereo mode (two `renderLevel` passes per `renderFrame`).
- **Shared jitter synchronization**: Both eyes use the same jitter pattern per frame pair, advanced once per frame.
- **VR temporal clamp**: Stricter neighbourhood clamp (1.0px vs 1.5px) in `taau_resolve_vr.fsh` to suppress sub-pixel shimmer that causes nausea in VR.
- **VR auto-resolution**: Scales both eyes symmetrically based on combined stereo GPU work measurement.
- **Built-in frame generation disabled in VR**: When stereo is detected, the addon disables Upshift's built-in frame generator and lets the headset's reprojection handle it.
- **VR configuration**: Separate `upshift_vr.json` config with VR-specific options.
- **Minimal VR overlay**: Diagnostic line showing "VR: stereo gen=off (VR: headset reprojection)" when flat overlay is hidden in VR.

### Technical

- No compile-time dependency on Vivecraft classes; all VR detection is runtime reflection-based.
- Flat rendering path unchanged when VR is inactive.
- Uses `classTweaker` access widener on base Upshift to expose internals without `@Overwrite`.

### Requirements

- Upshift 1.6.4+
- Vivecraft for Minecraft 26.2
- Fabric Loader 0.19.5+
- Sodium (recommended)