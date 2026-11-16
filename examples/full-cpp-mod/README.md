# Full C++ Lifecycle Mod Example

This directory is a complete C++ native mod example for LeviLauncher for Android. It shows
the recommended C++ lifecycle API, typed config, Mod Menu integration, and
`.levipack` packaging.

## What It Covers

- `PL_REGISTER_MOD` binding for a C++ lifecycle object
- `ll::mod::NativeMod::current()` cached as `mSelf`
- `pl::config::ConfigFile<T>` for typed JSON config
- Packaged default `config.json` and `config.schema.json`
- `pl::modmenu::ModuleBuilder` registration for Mod Menu modules
- `pl::modmenu::ButtonBuilder` registration for external HUD binding buttons
- PNG, WebP, and SVG custom icons for external HUD buttons
- Toggle/config callbacks that update and persist the same config file
- `build.ps1` packaging for the Android `.so` and `.levipack`

## Layout

```text
full-cpp-mod/
├── CMakeLists.txt
├── build.ps1
├── manifest.json
├── src/
│   ├── ExampleConfig.hpp
│   ├── FullCppMod.cpp
│   └── GenerateConfig.cpp
└── dist/
```

Key files:

| File | Purpose |
| --- | --- |
| `src/ExampleConfig.hpp` | Shared config definition used by runtime code and packaged defaults. |
| `src/FullCppMod.cpp` | Lifecycle, Mod Menu registration, and runtime persistence logic. |
| `src/GenerateConfig.cpp` | Creates default `config.json` and `config.schema.json` during build. |
| `manifest.json` | Uses `type: preload-native` and points `entry` to `libfull_cpp_mod.so`. |

## Build

Run from the repository root:

```powershell
.\examples\full-cpp-mod\build.ps1 -Clean
```

This project only supports `arm64-v8a`, so the script always builds that ABI.
You can pass an explicit NDK path:

```powershell
.\examples\full-cpp-mod\build.ps1 -Ndk <path-to-android-ndk>
```

If `-Ndk` is omitted, the script resolves it from `ANDROID_NDK_HOME`,
`ANDROID_NDK_ROOT`, `ANDROID_HOME`, or `ANDROID_SDK_ROOT`.

## Output

Successful builds generate:

```text
examples\full-cpp-mod\dist\arm64-v8a\full-cpp-mod\
examples\full-cpp-mod\dist\arm64-v8a\full-cpp-mod.levipack
```

The package root should contain:

```text
manifest.json
libfull_cpp_mod.so
config\config.json
config\config.schema.json
```

The `full-cpp-mod` directory name is the runtime mod id and the default Mod
Menu owner group.

## Verify

```powershell
tar -tf .\examples\full-cpp-mod\dist\arm64-v8a\full-cpp-mod.levipack
```

Expected entries:

```text
config/config.json
config/config.schema.json
libfull_cpp_mod.so
manifest.json
```

After importing the mod into LeviLauncher for Android, verify that:

- The launcher config page reads `config.json` and `config.schema.json`.
- The in-game Mod Menu shows `Full C++ Config Demo`.
- Enabling the module shows the external `Q`, `H`, `T`, and `Take` HUD buttons
  with fixed keycap surfaces. `Q` demonstrates `.pngIcon(...)`, `H`
  demonstrates `.webpIcon(...)`, `T` uses the accent style preset with
  `.svgIcon(...)`, and `Take` demonstrates a wider multi-letter keycap via
  `.sizeScale(2.0f, 1.0f)`.
- Tapping `Q` dispatches Android key code `45`, holding `H` logs `DOWN/UP`,
  tapping `T` toggles active state through `STATE_CHANGED`, and tapping `Take`
  logs a click callback.
- Changing a menu toggle or config entry updates `config/config.json`.
- Restarting uses the previously persisted config as the menu defaults.

See the [Native Mod Quick Start](https://levimc.org/LeviLamina/developer_guides/android/) for the full development guide.
