# Upshift VR Edition

Upshift, tuned for headsets.

## What this does

When you run Minecraft with **Vivecraft** and **Upshift**, this addon activates automatically and changes three things:

1. **Per-eye temporal upscaling** — The temporal pass (TAAU) keeps a separate history buffer for the left and right eye. One shared buffer would mix the two views and break stereo depth.
2. **Stricter temporal clamp** — In VR a sub-pixel shimmer reads as a wobble, so the neighbourhood clamp is tightened from 1.5 px to 1.0 px. You lose a tiny bit of micro-detail but the image stays rock-steady.
3. **Symmetric auto-resolution** — The dynamic scaler measures the *combined* GPU work of both eyes and drops the internal resolution for both lenses together.
4. **Frame generation steps aside** — Upshift's built-in interpolator is disabled in stereo mode; the headset's own reprojection (OpenXR / SteamVR) takes over.
5. **Flat overlay hidden** — The 2D performance panel is suppressed in VR. A single diagnostic line is printed instead.

## Install

1. Install [Upshift](https://www.curseforge.com/minecraft/mc-mods/upshift) 1.6.4 or newer.
2. Install Vivecraft for 26.2.
3. Drop `upshift_vr_edition-1.0.0.jar` into your `mods` folder.
4. Launch with Fabric.

## Configuration

A separate `upshift_vr.json` appears in the config folder:

```json
{
  "vrEnabled": true,
  "vrTemporalEnabled": true,
  "vrTemporalClampWeight": 0.08,
  "vrAutoResolution": true,
  "vrTargetFps": 72,
  "vrFrameGenEnabled": false,
  "vrHideFlatOverlay": true,
  "vrProjectOverlayToWrist": false,
  "vrOverlayMode": 0
}
```

All settings can be changed at runtime; the file is reloaded every second.

## Compatibility

- **OpenGL & Vulkan** backends both work.
- **Sodium** is detected and its video settings screen still works.
- No other mods are required.

## Building

```bash
./gradlew build
```

Output: `build/libs/upshift_vr_edition-1.0.0.jar`

## License

MIT