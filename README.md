# !!! This README is under construction !!!

# zproot-android

Android frontend for [zproot](https://github.com/zproot/zproot) — Linux on Android without root or Termux.

The ptrace tracer is written in Zig and lives at zproot/zproot. This repository contains only the Android app.

## What it does

- Install and run Linux distributions (Alpine, Debian, Ubuntu, and more) on any Android device
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
| Dynamically linked binaries M10 | in progress |
| Terminal UI | not started |
| Multi-distro registry | not started |
| Wayland compositor | planned |

Do not expect a usable Linux container yet. If you need something usable today, use pr or Termux.

## How it works

The app bundles a native aarch64 binary (libzproot.so) built from zproot/zproot. That binary uses Linux ptrace() to intercept syscalls and translate filesystem paths, creating a virtual root filesystem without root privileges.

When a guest program calls openat("/etc/passwd"), the tracer rewrites the syscall argument to point at /data/data/com.zproot/files/rootfs/etc/passwd. The kernel opens the real file. The guest sees /etc/passwd.

## Build

The tracer binary is not committed. It is either downloaded from a release or built by CI.

## From GitHub Actions

Every push to this repository triggers a workflow that:

1. Clones zproot/zproot
2. Builds libzproot.so for aarch64-linux-android
3. Copies it into app/src/main/jniLibs/arm64-v8a/
4. Runs ./gradlew assembleRelease
5. Uploads the APK as an artifact

Download the APK from the Actions tab.

Locally

Prerequisites:

- JDK 17
- Android SDK with build-tools and platform-tools
- Zig 0.16.0 (only if building the tracer yourself)

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

The APK is at:

```
app/build/outputs/apk/release/app-release.apk
```

Install with:

```bash
adb install -r app/build/outputs/apk/release/app-release.apk
```

Project structure

zproot-android/
│
├── .github/                              GitHub configuration
│   ├── workflows/
│   │   ├── build.yml                     APK build on push
│   │   ├── release.yml                   Tagged release with signed APK
│   │   └── lint.yml                      ktlint + detekt
│   ├── actions/
│   │   └── setup-android/                Reusable setup action
│   ├── ISSUE_TEMPLATE/
│   │   ├── bug_report.md
│   │   └── feature_request.md
│   ├── PULL_REQUEST_TEMPLATE/
│   │   └── PULL_REQUEST_TEMPLATE.md
│   ├── CODEOWNERS
│   ├── FUNDING.yml
│   ├── SECURITY.md
│   ├── dependabot.yml
│   ├── discussions.yml
│   └── profile/
│       └── README.md
│
├── app/                                  Android application module
│   ├── build.gradle.kts
│   ├── proguard-rules.pro
│   └── src/
│       ├── main/
│       │   ├── AndroidManifest.xml
│       │   ├── java/com/zproot/
│       │   │   ├── ZActivity.kt          Compose entry point
│       │   │   ├── RootfsManager.kt      Download, extract, symlink
│       │   │   ├── DistroRegistry.kt     Distro list (planned)
│       │   │   ├── TracerLauncher.kt     ProcessBuilder wrapper
│       │   │   └── ZprootService.kt      Foreground service (planned)
│       │   ├── res/
│       │   │   ├── values/
│       │   │   │   ├── strings.xml
│       │   │   │   ├── colors.xml
│       │   │   │   └── themes.xml
│       │   │   ├── mipmap-anydpi-v26/
│       │   │   │   ├── ic_launcher.xml
│       │   │   │   └── ic_launcher_round.xml
│       │   │   └── xml/
│       │   │       └── backup_rules.xml
│       │   └── jniLibs/
│       │       └── arm64-v8a/
│       │           ├── libzproot.so          Tracer (not committed)
│       │           └── libzproot-loader.so   Loader (not committed)
│       ├── test/
│       │   └── java/com/zproot/
│       │       └── RootfsManagerTest.kt
│       └── androidTest/
│           └── java/com/zproot/
│               └── LaunchTest.kt
│
├── core/                                 Shared Android library module
│   ├── build.gradle.kts
│   └── src/main/
│       ├── AndroidManifest.xml
│       └── java/com/zproot/core/
│           ├── DistroConfig.kt
│           ├── TracerLauncher.kt
│           └── Downloader.kt
│
├── feature/                              UI features (one module each)
│   ├── home/
│   ├── install/
│   ├── login/
│   ├── terminal/
│   ├── settings/
│   ├── distro-list/
│   ├── distro-detail/
│   ├── file-manager/
│   ├── package-manager/
│   ├── display/
│   ├── onboarding/
│   ├── about/
│   ├── logs/
│   ├── shell/
│   └── keys/
│
├── native/                               Native build staging
│   ├── aarch64/
│   ├── x86_64/
│   ├── arm/
│   ├── x86/
│   ├── asm/
│   └── linker/
│
├── rootfs/                               Per-distro recipes
│   ├── alpine/
│   │   └── recipe.toml
│   ├── debian/
│   │   └── recipe.toml
│   ├── ubuntu/
│   │   └── recipe.toml
│   ├── arch/
│   │   └── recipe.toml
│   ├── fedora/
│   │   └── recipe.toml
│   ├── opensuse/
│   │   └── recipe.toml
│   ├── manjaro/
│   │   └── recipe.toml
│   ├── rocky/
│   │   └── recipe.toml
│   ├── void/
│   ├── gentoo/
│   ├── recipes/
│   └── mirrors/
│
├── service/                              Android services
│   ├── foreground/
│   ├── installer/
│   ├── downloader/
│   ├── extractor/
│   ├── pty/
│   ├── session/
│   └── notifications/
│
├── compositor/                           Wayland compositor (planned)
│   ├── wayland/
│   ├── smithay-bindings/
│   ├── shm/
│   ├── egl/
│   ├── input/
│   ├── seat/
│   ├── xdg-shell/
│   ├── buffer/
│   ├── damage/
│   ├── output/
│   └── tests/
│
├── scripts/                              Automation scripts
│   ├── fetch-binary.sh                   Download libzproot.so from releases
│   ├── build-android.sh                  Zig build + copy into jniLibs
│   ├── build-musl.sh                     Termux build for local testing
│   ├── build-apk.sh                      gradlew wrapper
│   ├── test-on-device.sh                 adb install + run
│   ├── release.sh                        Tag and upload
│   ├── sign.sh                           Keystore signing
│   ├── lint.sh                           ktlint + detekt
│   ├── format.sh                         Spotless
│   ├── ci.sh
│   ├── dev.sh
│   ├── deploy.sh
│   ├── rootfs.sh
│   └── native.sh
│
├── docs/                                 Documentation
│   ├── architecture.md
│   ├── adr/
│   │   ├── 0001-use-zig-for-tracer.md
│   │   ├── 0002-module-layout.md
│   │   └── 0003-foreground-service.md
│   ├── api/
│   ├── design/
│   ├── native/
│   ├── android/
│   ├── security/
│   ├── troubleshooting/
│   ├── roadmap.md
│   ├── tutorials/
│   ├── reference/
│   ├── contributing.md
│   └── images/
│
├── test/                                 Test layout
│   ├── unit/
│   ├── integration/
│   ├── instrumented/
│   ├── screenshot/
│   ├── benchmark/
│   ├── fuzz/
│   ├── fixtures/
│   └── helpers/
│
├── tools/                                Developer utilities
│   ├── detekt/
│   ├── ktlint/
│   ├── spotless/
│   ├── apk-analyzer/
│   ├── binary-inspector/
│   ├── rootfs-builder/
│   └── trace-viewer/
│
├── configs/                              Editor and linter configs
│   ├── editorconfig/
│   ├── formatting/
│   ├── linters/
│   └── hooks/
│
├── assets/                               Runtime assets
│   ├── fonts/
│   ├── icons/
│   ├── splash/
│   ├── terminfo/
│   ├── keyboard/
│   ├── shell-init/
│   └── motd/
│
├── third-party/                          Vendored dependencies
│   ├── termux-terminal/
│   ├── connectbot-termlib/
│   ├── zig-wlroots/
│   ├── licenses/
│   ├── patches/
│   └── notices/
│
├── translations/                         Per-locale strings
│   ├── en/
│   ├── it/
│   ├── de/
│   ├── fr/
│   ├── es/
│   ├── ja/
│   └── zh/
│
├── distribution/                         Store and release metadata
│   ├── f-droid/
│   │   └── metadata/
│   ├── github-releases/
│   ├── play-store/
│   ├── izzysoft/
│   └── metadata/
│
├── releases/                             Release notes
│   ├── templates/
│   ├── changelog/
│   └── v0.1/
│
├── patches/                              Dependency patches
│   ├── upstream/
│   ├── custom/
│   └── archived/
│
├── dist/                                 Build output staging
│   ├── apk/
│   ├── tarballs/
│   ├── musl/
│   ├── android/
│   └── checksums/
│
├── build-logic/                          Gradle convention plugins
│   ├── convention/
│   ├── plugins/
│   ├── android/
│   ├── kotlin/
│   ├── compose/
│   └── native/
│
├── gradle/                               Gradle wrapper and catalog
│   ├── wrapper/
│   │   ├── gradle-wrapper.jar
│   │   └── gradle-wrapper.properties
│   └── libs.versions.toml
│
├── .gitignore
├── build.gradle.kts                      Root build script
├── settings.gradle.kts                   Module includes
├── gradle.properties                     JVM args, AndroidX flags
├── gradlew
├── gradlew.bat
├── LICENSE
└── README.md

## Android compatibility notes

Android enforces restrictions on app processes that a ptrace tracer must work around:

- W^X (Write-XOR-Execute): prevents executing files in app-writable directories. The tracer and loader live in nativeLibraryDir and are named lib*.so.
- SELinux: blocks certain filesystem operations. The app touches nothing outside its own filesDir.
- Zygote seccomp: blocks 18+ syscalls via BPF filter. The tracer catches SIGSYS and returns -ENOSYS so callers fall back to older syscall variants.
- Phantom Process Killer (Android 12+): kills forked children that consume too much CPU in the background. A foreground service with a persistent notification is the only non-root mitigation.

## Data persistence

The rootfs lives in `/data/data/com.zproot/files/rootfs/`. It survives app restarts, reboots, and updates. It is deleted only when the user uninstalls the app or clears its data.

Installing a distro extracts a tarball into that directory. Removing a distro is rm -rf on the subdirectory. No filesystem, no mount, no image file.

## Building for other ABIs

Currently only arm64-v8a is supported.

| ABI | Tracer | Loader | Status |
|-----|--------|--------|--------|
arm64-v8a yes yes supported
x86_64 yes partial pending M10 verification
armeabi-v7a no no not started
x86 no no not started

32-bit targets need separate register structs, syscall tables, and _start assembly. See docs/architecture.md in the tracer repository.

## License

MIT

The tracer is a clean-room reimplementation. No source code from proot, termux-proot, or proot-distro was read, copied, or translated. See docs/clean-room.md in the tracer repository.

## Related repositories

- zproot/zproot — tracer core, written in Zig
- zproot/.github — organization profile