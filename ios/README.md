# LeviLauncher for iOS (world and pack manager)

A new SwiftUI app, not a port of the Android launcher. iOS does not allow an app
to run another app's code, so APK import, native `.so` mods and multi-version
isolation are not possible here. This app covers the part that is possible:
managing Minecraft Bedrock content through the Files-visible Minecraft folder.

## Features

- Pick the Minecraft folder once (access is remembered with a bookmark)
- List worlds, resource packs and behavior packs
- Back up a world to a `.mcworld` file (stored in the app's Documents/Backups,
  visible in the Files app) and share it
- Import `.mcworld`, `.mcpack`, `.mcaddon` or `.zip` files into the game folder
- Restore a backup as a new world

## Build

Requires a Mac with Xcode 15+ and [XcodeGen](https://github.com/yonaskolb/XcodeGen).

```sh
brew install xcodegen
cd ios
xcodegen generate
open LeviLauncher.xcodeproj
```

In Xcode, select your team under Signing & Capabilities, change the bundle
identifier if needed, then run on a device.

## Use

1. Open the app and tap **Choose Minecraft folder**.
2. In the picker go to **On My iPhone > Minecraft** and tap **Open**.
   The app looks for `games/com.mojang` inside the folder you choose.
3. If Minecraft does not appear in Files, set
   Minecraft > Settings > Storage > File Storage Location to **External**.

## Limits

- The game must be closed while importing or restoring, or it may not see changes.
- Imported worlds are always added as new worlds; nothing is overwritten.
- This code has not been compiled or tested on a device yet.
