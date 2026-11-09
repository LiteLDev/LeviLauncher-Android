<div align="center">

![LeviLauncher Logo](https://avatars.githubusercontent.com/u/78095377?s=200&v=4)

# LeviLauncher — Android version

![Banner](https://camo.githubusercontent.com/bd7bd77cb422a267057d9863095b239b096d4f46dc831a37b45867a9acfad697/68747470733a2f2f63617073756c652d72656e6465722e76657263656c2e6170702f6170693f747970653d576176696e6726636f6c6f723d74696d654772616469656e74266865696768743d33303026616e696d6174696f6e3d66616465496e2673656374696f6e3d68656164657226746578743d4c6576694d4326666f6e7453697a653d313230)

**A lightweight Android launcher for Minecraft Bedrock Edition**

[![GitHub Release](https://img.shields.io/github/v/release/LiteLDev/LeviLauncher-Android?style=flat-square&color=blue)](https://github.com/LiteLDev/LeviLauncher-Android/releases)
[![License: Apache 2.0](https://img.shields.io/github/license/LiteLDev/LeviLauncher-Android)](https://github.com/LiteLDev/LeviLauncher-Android/blob/main/LICENSE)
[![Issues](https://img.shields.io/github/issues/LiteLDev/LeviLauncher-Android?style=flat-square&color=red)](https://github.com/LiteLDev/LeviLauncher-Android/issues)
[![Stars](https://img.shields.io/github/stars/LiteLDev/LeviLauncher-Android?style=flat-square&color=yellow)](https://github.com/LiteLDev/LeviLauncher-Android)
[![Downloads](https://img.shields.io/github/downloads/LiteLDev/LeviLauncher-Android/total.svg)](https://github.com/LiteLDev/LeviLauncher-Android/releases)
[![Android](https://img.shields.io/badge/Android-9.0%2B-green?style=flat-square&logo=android)](https://www.android.com/)

**[Documentation](https://docs.levimc.org/LeviLauncher/guide/quick-start?platform=android)** · **[Native mod development](https://docs.levimc.org/LeviLamina/developer_guides/android/)**

</div>

---

## Introduction

This repository is the **Android version** of LeviLauncher; the **Windows version** is developed in [LiteLDev/LeviLauncher](https://github.com/LiteLDev/LeviLauncher). Both share one guide at [docs.levimc.org/LeviLauncher](https://docs.levimc.org/LeviLauncher/) (Windows / Android tabs); native mod development is documented in the [LeviLamina docs](https://docs.levimc.org/LeviLamina/developer_guides/android/). There is no iOS version, and none is planned.

LeviLauncher is a lightweight, open-source Android launcher specifically designed for legitimate players of Minecraft Bedrock Edition (MCBE). It works alongside your Google Play installation of Minecraft, allowing you to manage multiple game versions and extend functionality with native mods.

LeviLauncher enables you to import official Minecraft APKs and run them inside the launcher, without installing each version on the system. The official Minecraft app from Google Play must still be installed on the device. The launcher supports loading native mods to enhance gameplay, provides robust multi-version management with complete isolation between installations, and includes built-in tools for managing resource packs and worlds. Whether you're looking to organize different game versions, test modifications, or optimize your gaming experience, LeviLauncher offers the flexibility you need.

### Key Features

- **APK Import & In-Launcher Versions** – Import official Minecraft APKs and run them inside the launcher without installing each version on the system (requires the Google Play version of Minecraft on the device)
- **Native Mod Loading** – Load native mods (`.so` libraries) to extend or enhance Minecraft features and performance
- **Multi-Version Management & Version Isolation** – Manage multiple Minecraft versions independently, with separated configurations and data
- **Multiple Xbox Account Management** – Manage multiple Xbox accounts inside the launcher and switch between them seamlessly
- **Resource Pack & World Management** – Import, export, and back up your resource packs and worlds with the built-in manager

---

## System Requirements

Before installing LeviLauncher, ensure your device meets the following minimum specifications:

- **Operating System:** Android 9.0 (API 28) or higher
- **Device Architecture:** ARM64 (v8a)
- **RAM:** Minimum 1 GB available RAM (3 GB or more recommended)
- **Storage:** At least 2 GB of available storage for Minecraft and game data (5 GB or more recommended)
- **Minecraft:** Minecraft Bedrock Edition 1.21.80 or later, installed from Google Play (older versions are rejected by the launcher)
- **License Requirement:** You must own a licensed copy of Minecraft Bedrock Edition purchased from Google Play

> **Note:** For optimal performance and stability, especially when managing multiple versions or large worlds, use a device with at least 3 GB of available RAM and 5 GB of free storage.

---

## Installation

### Prerequisites

Before proceeding with LeviLauncher installation, ensure that you have the official Minecraft Bedrock Edition app (1.21.80 or later) installed on your device from Google Play. This is required for LeviLauncher to function properly.

### Installation Steps

1. Visit the [Releases Page](https://github.com/LiteLDev/LeviLauncher-Android/releases) and download the latest APK build
2. Open the downloaded APK from your browser or file manager
3. When Android asks, allow that app to install unknown apps (Settings > Apps > Special app access > Install unknown apps), then go back
4. Tap Install to install the APK
5. Open the app from your app drawer (on the device it is shown as "LeviLauncher") and grant the permissions it requests

> **Important:** LeviLauncher requires a legitimate, licensed copy of Minecraft Bedrock Edition. Do not use this launcher with pirated or unauthorized versions of the game. Ensure your Minecraft license is valid and properly linked to your Microsoft account.

---

## Development Setup

If you want to build LeviLauncher from source or contribute to development, follow these steps to set up your development environment:

### Prerequisites

- Git installed on your system
- Android Studio (latest version recommended)
- Java Development Kit (JDK) 21 or higher
- Android SDK with API level 28 or higher
- Android NDK and CMake (install them through Android Studio's SDK Manager) when native code is built

### Setup Instructions

1. Clone the repository:

   ```bash
   git clone https://github.com/LiteLDev/LeviLauncher-Android.git
   ```

2. Open the project directory in Android Studio

3. Allow Android Studio to download and install required dependencies and build tools

4. Wait for Gradle to complete the initial sync process

5. Connect your Android device or start an emulator (API 28+)

6. Click the "Run" button in Android Studio to build and deploy to your device

7. The app will launch automatically on successful build completion

> **Build Tip:** For faster builds during development, use `Build > Make Project` to compile incrementally instead of full rebuilds.

The project root is named `levilauncher` in the Gradle settings, while the repository is `LeviLauncher-Android`. To build from the command line:

```bash
./gradlew assembleDebug
```

On Windows, run `.\gradlew.bat assembleDebug`. Install the official Minecraft app on the test device before testing launcher flows.

---

## Contribution Guidelines

We welcome contributions from the community to improve LeviLauncher. To ensure a high-quality codebase and smooth collaboration, please adhere to the following guidelines:

### Code Quality

Write clean, modular code with descriptive variable names and consistent formatting. Follow Kotlin and Java style guidelines established in the project. Ensure your code is readable and well-structured for future maintainers.

### Commit Structure

Use small, focused commits with clear and descriptive messages. Each commit should address a single feature or bug fix. Example: "Fixed memory leak in version manager" or "Added support for ARM32 architecture".

### Documentation

Add comments for complex logic and update relevant documentation in the repository. If you add new features, update the README and any related documentation files.

### Performance

Optimize all additions to maintain low latency and smooth performance. Test your changes thoroughly to ensure they don't introduce lag or performance regressions.

### Testing

Test all changes on multiple devices and Android versions to ensure compatibility and stability.

### Pull Requests

Submit PRs with a detailed description of changes, including the problem solved or feature added. Reference any related issues and provide screenshots or videos if your changes affect the UI.

### Community Standards

Be constructive in feedback, respect others' work, and communicate professionally with all contributors.

**Before Submitting:** Run a full build cycle and test on at least one device to minimize errors. We review all contributions promptly and appreciate your efforts to enhance LeviLauncher.

---

## Usage Guidelines

LeviLauncher is designed for legitimate players of Minecraft Bedrock Edition. Please respect the following guidelines and terms of use:

### Permitted Uses

- Modify LeviLauncher for personal gameplay and to test new features
- Create educational content (videos, tutorials, blog posts) showcasing LeviLauncher's capabilities
- Fork the repository for learning purposes or to create derivative projects, provided you comply with the Apache License 2.0
- Share your modified versions with others as long as you comply with the Apache License 2.0 terms

### Prohibited Uses

- Do not claim LeviLauncher as your own without crediting LeviMC and its contributors
- Do not use LeviLauncher to violate Mojang or Microsoft's user agreements

> **Disclaimer:** The authors and contributors of LeviLauncher are not responsible for bans, damages, or issues arising from the use of this software. Use it at your own risk and in accordance with Minecraft's terms of service.

For full legal details, see the [LICENSE](LICENSE) file in the repository.

---

## Credits & Acknowledgements

LeviLauncher would not be possible without the contributions and support of many talented individuals and organizations:

### Special Thanks To

- **LeviMC** – For maintaining the LeviLauncher project and providing infrastructure support
- **Android Community** – For excellent documentation, libraries, and tools that made this launcher possible
- **Open Source Community** – For all the libraries, frameworks, and tools that power this project
- **Contributors** – A heartfelt thank you to all [contributors](https://github.com/LiteLDev/LeviLauncher-Android/graphs/contributors) who have continuously improved and maintained LeviLauncher through their time and expertise

---

## Contact & Support

**Author / Team:** LeviMC

**Project Repository:** [https://github.com/LiteLDev/LeviLauncher-Android](https://github.com/LiteLDev/LeviLauncher-Android)

**Documentation:** [docs.levimc.org/LeviLauncher](https://docs.levimc.org/LeviLauncher/guide/quick-start?platform=android) · native mods: [docs.levimc.org/LeviLamina](https://docs.levimc.org/LeviLamina/developer_guides/android/)

**Report Issues:** [GitHub Issues Page](https://github.com/LiteLDev/LeviLauncher-Android/issues)

**For support and questions:** Please create an issue on the GitHub repository or contact LeviMC directly

---

<div align="center">

[![GitHub Release](https://img.shields.io/github/v/release/LiteLDev/LeviLauncher-Android?style=flat-square&color=blue)](https://github.com/LiteLDev/LeviLauncher-Android/releases)
[![License: Apache 2.0](https://img.shields.io/badge/License-Apache%202.0-blue.svg?style=flat-square)](https://www.apache.org/licenses/LICENSE-2.0)
[![Issues](https://img.shields.io/github/issues/LiteLDev/LeviLauncher-Android?style=flat-square&color=red)](https://github.com/LiteLDev/LeviLauncher-Android/issues)
[![Stars](https://img.shields.io/github/stars/LiteLDev/LeviLauncher-Android?style=flat-square&color=yellow)](https://github.com/LiteLDev/LeviLauncher-Android)
[![Downloads](https://img.shields.io/github/downloads/LiteLDev/LeviLauncher-Android/total.svg)](https://github.com/LiteLDev/LeviLauncher-Android/releases)
[![Android](https://img.shields.io/badge/Android-9.0%2B-green?style=flat-square&logo=android)](https://www.android.com/)

**Made with ❤️ by LeviMC**

</div>
