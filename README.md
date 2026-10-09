# zproot-android

Android frontend for [zproot](https://github.com/zproot/zproot) — Linux on Android without root or Termux.

The ptrace tracer is written in Zig and lives in the [zproot](https://github.com/zproot/zproot). This repository contains only the Android app.

## What it does

- Install and run Linux distributions (Alpine, Debian, Ubuntu, and more...) on any Android device
- No root, no Termux, no separate X11 server
- Sideload and F-Droid friendly, no Play Store required
- MIT licensed

## Status

Work in progress. The APK builds, installs, and runs the tracer on a real device. Alpine downloads and extracts to the app's private storage. Path rewriting is verified end to end.

| Component | Status |
|-----------|--------|
| APK skeleton | done |
| Foreground service | not started |
| Alpine download and extract | done |
| Static busybox shell | working |
| Dynamically linked binaries (M10) | in progress |
| Terminal UI | not started |
| Multi-distro registry | not started |
| Wayland compositor | planned |

Do not expect a usable Linux container yet. If you need something usable today, use [pr](https://github.com/oonid/pr) or [Termux](https://github.com/termux/termux-app).

## How it works

The app bundles a native aarch64 binary (`libzproot.so`) built from [zproot/zproot](https://github.com/zproot/zproot). That binary uses Linux `ptrace()` to intercept syscalls and translate filesystem paths, creating a virtual root filesystem without root privileges.

When a guest program calls `openat("/etc/passwd")`, the tracer rewrites the syscall argument to point at `/data/data/com.zproot/files/rootfs/etc/passwd`. The kernel opens the real file. The guest sees `/etc/passwd`.

## Build

The tracer binary is not committed. It is either downloaded from a release or built by CI.

### From GitHub Actions

Every push to this repository triggers a workflow that:

1. Clones `zproot/zproot`
2. Builds `libzproot.so` for `<abi>-linux-android`
3. Copies it into `app/src/main/jniLibs/<abi>/`
4. Runs `./gradlew assembleRelease`
5. Uploads the APK as an artifact

Download the APK from the [Actions tab](https://github.com/zproot/zproot-android/actions).

### Locally

Prerequisites:

- ***JDK 17***
- ***Android SDK*** with build-tools and platform-tools
- ***Zig 0.16.0*** (only if building the tracer yourself)

## Installation

```bash
git clone https://github.com/zproot/zproot-android
cd zproot-android

# Option A: fetch a prebuilt tracer
./scripts/fetch-binary.sh

# Option B: build the tracer from source
git clone https://github.com/zproot/zproot ../zproot
cd ../zproot && zig build -Dtarget=aarch64-linux-android -Doptimize=ReleaseSafe
cp zig-out/bin/zproot ../zproot-android/app/src/main/jniLibs/arm64-v8a/libzproot.so
cp zig-out/bin/zproot-loader ../zproot-android/app/src/main/jniLibs/arm64-v8a/libzproot-loader.so

# Build the APK
cd ../zproot-android
./gradlew assembleRelease
```

## The APK is at:

```
app/build/outputs/apk/release/app-release.apk
```

## Install with:

```bash
adb install -r app/build/outputs/apk/release/app-release.apk
```

## Project structure

```
zproot-android/
├── app/                       Android application module
│   ├── build.gradle.kts
│   └── src/main/
│       ├── AndroidManifest.xml
│       ├── java/com/zproot/
│       │   ├── ZActivity.kt           Compose UI
│       │   └── RootfsManager.kt       Download, extract, symlink
│       └── jniLibs/arm64-v8a/
│           ├── libzproot.so           Tracer (not committed)
│           └── libzproot-loader.so    PT_INTERP loader (not committed)
├── core/                      Shared library module (planned)
├── feature/                   Feature modules (planned)
├── native/                    Native build staging
├── rootfs/                    Per-distro recipes
├── service/                   Foreground service (planned)
├── compositor/                Wayland compositor (planned)
├── scripts/                   Build and fetch scripts
├── docs/                      Architecture and design notes
├── test/                      Unit and instrumented tests
├── tools/                     Developer utilities
├── distribution/              F-Droid metadata
├── third-party/               Vendored dependencies and licenses
├── translations/              strings.xml per locale
├── configs/                   Editor and linter configs
├── assets/                    Runtime assets
├── build-logic/               Gradle convention plugins
├── gradle/                    Wrapper and version catalog
├── releases/                  Release notes
├── patches/                   Dependency patches
├── dist/                      Build output staging
├── .github/                   Workflows and issue templates
├── build.gradle.kts
├── settings.gradle.kts
├── gradle.properties
├── gradlew
├── gradlew.bat
├── LICENSE
└── README.md
```

## Android compatibility notes

Android enforces restrictions on app processes that a `ptrace` tracer must work around:

- ***W^X (Write-XOR-Execute)***: prevents executing files in app-writable directories. The tracer and loader live in `nativeLibraryDir` and are named lib*.so.
- ***SELinux***: blocks certain filesystem operations. The app touches nothing outside its own `filesDir`.
- ***Zygote seccomp***: blocks 18+ `syscalls` via `BPF` filter. The tracer catches `SIGSYS` and returns `-ENOSYS` so callers fall back to older `syscall` variants.
- ***Phantom Process Killer (Android 12+)***: kills forked children that consume too much CPU in the background. A foreground service with a persistent notification is the only non-root mitigation.

## Data persistence

The rootfs lives in `/data/data/com.zproot/files/rootfs/`. It survives app restarts, reboots, and updates. It is deleted only when the user uninstalls the app or clears its data.

Installing a distro extracts a tarball into that directory. Removing a distro is `rm -rf` on the subdirectory. No filesystem, no mount, no image file.

## Building for other ABIs

> Currently only arm64-v8a is supported.

| ABI | Tracer | Loader | Status |
|-----|--------|--------|--------|
| arm64-v8a | yes | yes | supported |
| x86_64 | yes | partial | pending M10 verification |
| armeabi-v7a | no | no | not started |
| x86 no | no | not | started |

32-bit targets need separate register `structs`, `syscall` tables, and `_start` assembly. See [docs/architecture/architecture.md](https://github.com/zproot/zproot-android/blob/main/docs/architecture/architecture.md) in the tracer repository.

## License

[MIT](https://github.com/zproot/zproot-android/blob/main/LICENSE)

The tracer is a clean-room reimplementation. No source code from [proot](https://github.com/proot-me/proot), [termux-proot](https://github.com/termux/proot), or [proot-distro](https://github.com/termux/proot-distro) was read, copied, or translated. See docs/clean-room.md in the tracer repository.

## Related repositories

- [zproot/zproot](https://github.com/zproot/zproot) — tracer core, written in Zig
- [zproot/.github](https://github.com/zproot/.github) — organization profile
