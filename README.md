# FAT32 Toolbox

An Android-first, no-root storage inspection utility for USB flash drives and SD/microSD cards.

## Current status

This repository starts with a buildable Android app scaffold. The first version focuses on safe device discovery and storage inspection. **It does not yet format drives.** Stock Android does not expose a public API for arbitrary USB/SD block-device formatting, and USB host access or the Storage Access Framework alone cannot safely provide that capability. The app will not pretend a format succeeded or offer a destructive button that cannot be implemented reliably.

## Goals

- Work across different USB mass-storage devices and SD/microSD readers, not just one drive.
- Show connected USB peripherals and let the user select an accessible storage folder through Android's system picker.
- Display filesystem and capacity details where Android exposes them.
- Keep all destructive operations opt-in, clearly identify the target, and verify results.
- No ads, subscriptions, tokens, or root requirement.
- Build APKs with GitHub Actions.

## Important Android limitation

On an unrooted phone, Android's public APIs generally grant file/document access, not raw block-device access. Formatting FAT32 on arbitrary external media therefore needs a separately validated privileged backend or a supported system/vendor formatting interface. Shizuku alone is not a guarantee: availability of `mkfs.fat`, block-device permissions, SELinux policy, and Android version all matter. This project deliberately treats formatting as unsupported until a backend can be tested on real devices without risking internal storage.

## Build

Open this repository in Android Studio, or use the included GitHub Actions workflow to build a debug APK. The workflow uploads the APK as an artifact.

## Device testing

Test with expendable media first. Confirm that Android mounts the device and that the selected folder is on the intended removable volume. Do not use the app for formatting until a verified formatting backend is implemented.

## License

MIT (see `LICENSE`).