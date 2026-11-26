# FAT32 Toolbox

An Android-first, no-root removable-storage inspection app. No ads, subscriptions, tokens, or root requirement.

## Included in the current build

- USB peripheral discovery (manufacturer, product, vendor/product IDs, interfaces, mass-storage interface detection).
- Removable-volume listing from Android's StorageManager.
- Device-type filter for All, USB, and SD card (classification is best-effort because Android does not consistently expose the physical media type).
- Capacity and available-space reporting when Android exposes a mounted volume path.
- Filesystem detection from the Android mount table when readable.
- Manual volume/folder selection through Android's Storage Access Framework.
- Desired-format selector for FAT32, exFAT, and NTFS, with compatibility notes.
- GitHub Actions workflow to build a debug APK.

## Formatting status — not implemented yet

The format selector is a preference only. It does not format or erase media. An ordinary unrooted Android app can generally obtain document/file access through the Storage Access Framework, but that is not raw block-device access. Android does not provide a public general-purpose API for third-party apps to format arbitrary USB/SD media.

A future formatter needs a verified backend and reliable device identity. Shizuku by itself is not a guarantee: the device must expose the correct block device, the required formatter binaries must exist, permissions and SELinux must allow the operation, and the app must prove the target is removable media rather than internal storage. Until those conditions are validated on real devices, formatting stays disabled for safety.

## Detection limitations

- Android may hide mount paths, filesystem type, exact capacity, or partition tables from ordinary apps.
- A USB card reader and a USB flash drive can look similar to Android; USB-vs-SD classification is best-effort, and a manual picker is provided.
- Partition-table inspection requires lower-level access not generally available to a normal app.

## Build

Open in Android Studio, or run the GitHub Actions workflow (`Actions` → `Android APK` → `Run workflow`). The workflow uploads `app-debug.apk` as an artifact.

## Test safely

Connect removable media, tap Refresh devices, and inspect the detected volume. No formatting is performed by this version. Test with expendable media before any future destructive feature.

## License

MIT (see `LICENSE`).