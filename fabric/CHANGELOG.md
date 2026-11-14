# Changelog

## 1.6.6 - Russian translations & Sodium settings fix

### Fixed
- **Sodium settings icon conflict.** Removed hardcoded `.setIcon()` from `SodiumConfigEntry` which was causing the Upshift VR Edition addon's icon to appear in the base Upshift settings page. Each mod now displays its correct default icon.

### Added
- **Russian language translations** (`ru_ru.json`). All Upscaling, Frame Generation and Optimizations pages are now fully translated to Russian.

---

## 1.6.5 - Public API for VR addon & name change

### Changed
- **Sodium settings name:** "FSR + DLSS" → "Upshift" (cleaner, matches mod name)
- **All `UpscaleManager` internals made public** — fields (`taau`, `worldTarget`, `pristineProjection`, `previousRotation`, `rotationScratch`, `rotationQuat`, `sizeScratch`, `hasPreviousRotation`, `jitterValid`, `jitterIndex`, `hasPresented`, `menuReuseLeft`, `blurHandled`, `intermediate`, `intermediateView`, `intermediateW`, `intermediateH`, `worldW`, `worldH`, `targetCache`, `retired`, `frame`, `frameStartNs`, `active`, `lastProbeNs`, `measurePhase`, `warnedNotWired`, `loggedActive`, `fsr1`, `fast`, `nis`, `JITTER`) and methods (`ensureWorldTarget`, `present`) are now `public` to allow the VR Edition addon to hook per-eye rendering without `@Overwrite` or access wideners.

### Fixed
- VR Edition addon no longer needs class tweakers / access wideners; works with standard Fabric Loom.

## 1.6.4 - Latency and generator rate

### Added

- **`latency` row.** Time from the start of a frame to the moment the window
  surface is presented, smoothed over recent frames. It grows when frame
  generation inserts extra images, so it shows what the added frames cost.
- **`gen` row.** Interpolated frames presented per second, or `off` when the
  built-in generator is not selected. The `FPS` row keeps showing the real
  frame rate with the generated additions next to it.

## 1.6.3 - Full hardware sensors

### Added

- **CPU temperature and package power.** Windows has no plain API for them, so
  the panel now reads the sensors that LibreHardwareMonitor and
  OpenHardwareMonitor publish over WMI. When neither is running it falls back
  to the ACPI thermal zone that many boards answer.
- **GPU fallback for non-NVIDIA cards.** When NVML is unavailable, load,
  temperature, clock and VRAM are read from the same WMI sensor providers, so
  AMD and Intel systems are covered too.
- **Fan and power.** NVML supplies the GPU fan percentage and board power; the
  panel shows them on the GPU line and in a new `power` row.
- **Card name.** Full mode now lists the detected graphics card.
- A missing metric is omitted from its line instead of printing `--`, and the
  `power`/`card` rows only appear when there is something to show.

### Fixed

- On Linux, the NVIDIA temperature sensor was used as the CPU temperature when
  no `coretemp`/`k10temp` sensor existed. CPU temperature is now only taken from
  a CPU sensor.

### Technical

- WMI access uses the `jna-platform` helpers that Minecraft already bundles
  (JNA 5.17.0); nothing extra is repackaged into the mod.
- All queries run on the hardware sampling thread, are throttled to one refresh
  per second, and probe for a provider again every 20 seconds, so starting
  LibreHardwareMonitor later lights the fields up without a restart.

## 1.6.2 - Hardware panel on Windows

### Fixed

- **CPU and memory now show up on Windows.** The panel sampled `/proc` and
  `/sys`, which only exist on Linux, so on Windows the CPU, RAM and clock
  fields stayed at `--`. CPU load now comes from `GetSystemTimes`, RAM from
  `GlobalMemoryStatusEx`, and the CPU clock from `CallNtPowerInformation`.
- **NVML is found on Windows.** The GPU is opened through `nvml.dll` (including
  the `NVSMI` fallback path for older drivers) instead of only the Linux
  `nvidia-ml` name, so GPU load, temperature, clock and VRAM populate on NVIDIA
  systems.
- **No more walls of `--`.** Metrics a system does not expose are left out of
  the line instead of being printed as a dashed placeholder; a line only falls
  back to `--` when none of its values are available.

## 1.6.1 - Smooth frame interpolation

### Fixed

- **The generator no longer tears.** Extra frames are now interpolated between
  the previous and the current rendered frame instead of extrapolated past the
  newest one. The previous world image is warped with a rotation homography
  evaluated at the exact fraction between the two known poses, which removes
  the overshoot that produced the visible rip.
- **The HUD stays still.** Only the world-only part of the frame is warped. The
  HUD is taken from the finished frame and composited un-warped, so the
  crosshair, hotbar and chat do not slide with the camera.
- **No stretched edges.** Areas that the rotation reveals are filled from the
  current world at the same screen position instead of sampling the clamped
  border into long streaks.
- **Correct pacing.** Generated frames are presented immediately before the
  real frame and spread over the refresh interval, while the real frame keeps
  its own surface image. The previous version presented them as a burst at the
  start of the next frame.
- Sodium is now also loaded in the development client (`modRuntimeOnly`), so
  the settings page and Sodium compatibility can be checked in `runClient`.

### Technical

- `FrameGenPresenter` keeps two world-only snapshots with their camera rotation
  and projection. `captureWorld()` copies the world at the end of `renderLevel`,
  before the HUD is drawn, and the snapshots alternate every frame.
- `MinecraftMixin` wraps the window-surface blit with `@WrapOperation` (no
  `@Overwrite`), so generated frames and the real frame share the surface in the
  correct order.
- `fgr_reproject` now samples the previous world, the finished frame and the
  current world-only capture; the last two isolate the HUD mask.
- The generator pipeline is precompiled once after the shader resources load,
  so the first activation does not hitch.

## 1.6.0 — Real Nx frame generation

### Added

- **Real extra frames.** The built-in frame generator now presents additional
  images between the frames the game renders, through the same window surface
  used by the real frame. The display receives more images than the renderer
  produces, on both the OpenGL and the Vulkan backend.
- **Frame generation multiplier** (1x–4x, default 2x): 2x presents one extra
  frame per rendered frame, 3x two, 4x three. 1x turns generation off.
- **Force exact multiplier** to present the selected number of frames even when
  the renderer already fills the display interval.

### Behaviour

- Each extra frame is the last finished image, including the HUD, warped by the
  camera rotation predicted from the two previous poses. A pure rotation is a
  homography in clip space, so the warp needs one texture read and no depth
  buffer.
- Pacing is adaptive by default: the generator subtracts the time spent
  presenting extra frames from the measured interval between real frames and
  only adds as many frames as still fit into the refresh period. A scene that
  already holds the refresh rate pays nothing.
- Generation pauses while a screen is open, the game is paused or the window is
  minimized. Very small or very large rotation deltas (a teleport) are skipped
  instead of smearing the image.
- If presenting an extra frame fails, the window surface is released and the
  generator pauses for a few seconds before retrying; the game keeps rendering
  normal frames in the meantime.

### Technical

- New `FrameGenPresenter` owns the pose history, the rotation homography, the
  output texture and the adaptive pacing.
- `MinecraftMixin` injects at `HEAD` and `RETURN` of `Minecraft.renderFrame`
  (no `@Overwrite`), so the extra presents happen before the next real frame
  starts and the previous finished image is the source.
- Config schema 8 adds `frameGenMultiplier`; existing configs migrate
  automatically.
- Java 21 bytecode.

---

## 1.5.0 — Optimizations, stage 4

### Added

- **Distant mob AI throttling** on the integrated server. A mob farther than
  the configured distance from every player skips its sensing tick and only
  runs the cheap running-goal pass on most ticks; its full goal scan happens
  once per interval. Navigation, movement, look and jump control still run
  every tick, so distant mobs keep moving.
- **Throttle distant mob AI**, **AI distance** (48 blocks) and **AI update
  rate** (every 4 ticks) options on the Optimizations page. Mobs within range
  of any player are never throttled.

### Safety

- Persistent mobs, named mobs, passengers, vehicles, the Ender Dragon and the
  Wither are excluded, so farms, pets and bosses are unaffected.
- The phase gate lines up with the tick vanilla already reserves for a full
  goal update, so an even interval always lands on a full tick.
- The decision is made once per game tick and cached on the mob; the distance
  test uses the level's player list and primitive math, with no `Vec3`
  allocation.
- A dedicated server never sees the mixin: Upshift is a client mod and the
  throttle lives in the integrated server only.

### Technical

- `MobAiThrottleMixin` wraps `Sensing.tick` and `GoalSelector.tick` in
  `Mob.serverAiStep` with `@WrapOperation`; there is no `@Overwrite`.
- Config schema 7; existing configs migrate automatically.
- Java 21 bytecode.

---

## 1.4.0 — Optimizations, stage 3

### Added

- **Text layout cache.** HUD strings are laid out once and reused instead of
  being decomposed into styled code points every frame. This covers item
  counts, the scoreboard, the debug screen and any mod overlay that draws with
  `graphics.text(font, String, ...)`.
- **Font width cache.** `Font.width(String)` answers are cached per font
  instance, so centring and tooltips do not walk every code point each frame.
  A resource reload builds a new font and drops the cache with it.
- **Cache text layout** option (on by default) on the Optimizations page, for
  the rare mod whose string content stays the same while its meaning changes.

### Technical

- The layout cache is bounded (4096 entries) and cleared when the language
  instance changes, so language switches and resource reloads cannot serve
  stale right-to-left ordering.
- Strings longer than 512 characters are never cached: chat and books are not
  worth pinning in memory.
- String overloads are hooked with `@WrapOperation` (Mixin Extras, bundled with
  the loader), which leaves other mods free to wrap the same call.
- Config schema 6; existing configs migrate automatically.
- Java 21 bytecode.

---

## 1.3.0 — Optimizations, stage 2

### Added

- **Block entity distance culling.** Chests, signs, banners, beacons and other
  block entities beyond the configured distance are rejected before their
  render state is created. Default 64 blocks (the vanilla limit for most
  renderers); `0` restores the vanilla per-renderer limits.
- **Freeze animations when unfocused** (on by default). Animated textures stop
  advancing while the Minecraft window is not focused, which saves the per-sprite
  tick and the GPU uploads that follow it.
- **Animation update rate.** Optionally advance animated textures every second,
  third or fourth frame instead of every frame. Default is every frame (vanilla).

### Technical

- The block entity distance check reuses the camera position vanilla already
  passes to `BlockEntityRenderDispatcher.prepare` and compares integer block
  coordinates, so it does not allocate a `Vec3` per block entity per frame.
- New mixins: `BlockEntityCullMixin`, `TextureAtlasMixin`. Both use `@Inject`,
  no `@Overwrite`.
- Config schema 5; existing configs migrate to the new defaults automatically.
- Java 21 bytecode, same as 1.1.0 and 1.2.0.

---

## 1.2.0 — Optimizations, stage 1

### Added

- **Optimizations page** in Sodium's video settings ("Optimizations").
- **Entity distance culling.** `EntityRenderDispatcher.shouldRender` now
  rejects entities beyond a configurable distance before the render state is
  extracted. Default 128 blocks; set to 0 for vanilla behaviour.
- **Hide invisible mobs** (off by default). Skips invisible non-player mobs.
  Armour and held items of invisible players are still rendered.
- **Particle budget.** At most 1500 particles are created per tick by default;
  spawns above the budget are dropped until the next tick.
- **Particle distance.** Configurable, 32 blocks by default (matches vanilla for
  ordinary spawns).

### Technical

- New mixins use `@Inject(..., cancellable = true)` at `HEAD`; no `@Overwrite`,
  so other rendering mods keep working.
- The particle budget resets on `ParticleEngine.tick`, so the limit is per tick,
  not per frame.
- Config schema bumped to 4. Existing configs are migrated automatically:
  the new options start at the defaults above.
- Rebuilt against Minecraft 26.2 with Java 21 bytecode (same as 1.1.0).

---

## 1.1.0

Initial public build: FSR 1 (EASU + RCAS), NVIDIA Image Scaling, temporal
accumulate, reprojection frame generation, performance overlay, capability
report, CPU/GPU monitoring and performance profiles.
