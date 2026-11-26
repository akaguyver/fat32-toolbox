# FAT32 Toolbox

An Android-first, no-root app that inspects and formats USB drives and SD cards in USB readers. No ads, subscriptions, tokens, or root requirement.

## Included in the current build

- USB peripheral discovery (manufacturer, product, vendor/product IDs, interfaces, mass-storage interface detection).
- Removable-volume listing from Android's StorageManager.
- Device-type filter for All, USB, and SD card (classification is best-effort because Android does not consistently expose the physical media type).
- Capacity and available-space reporting when Android exposes a mounted volume path.
- Filesystem detection from the Android mount table when readable.
- Manual volume/folder selection through Android's Storage Access Framework.
- FAT32 formatting of USB drives and SD cards in USB readers (see below).
- Desired-format selector for FAT32, exFAT, and NTFS, with compatibility notes.

## Formatting (FAT32)

The app formats USB flash drives, and SD/microSD cards in a USB card reader, as FAT32, without root.

How it works: through Android's USB host API the app claims the drive's mass-storage interface and sends SCSI read/write commands to it directly (using [libaums](https://github.com/magnusja/libaums) for the USB transport). This takes the drive away from Android, which unmounts it. The app then writes:

- an MBR partition table with one FAT32 (LBA, type 0x0C) partition starting at 1 MiB,
- a FAT32 filesystem following Microsoft's spec: 2 FATs, Microsoft's default cluster sizes, FSInfo, backup boot sector, optional volume label,
- zeros over the old partition table area and the end of the disk (where a GPT backup lives).

The partition table is written last, and every structure is read back and compared afterwards.

Safety: the formatter only ever reaches devices attached over USB, so it can't touch the phone's internal storage. You must pick the drive, see its size, and type ERASE before anything is written.

Limits:

- FAT32 only. exFAT and NTFS can be selected for information but aren't written yet.
- Drives from about 33 MB to 2 TiB (the MBR limit). Single files on FAT32 can't exceed 4 GB.
- A card in the phone's own SD slot isn't reachable this way (that needs root or a system formatter).
- After formatting, unplug and replug the drive so Android mounts it again.

The formatter is tested on disk images: `gradle :app:testDebugUnitTest`, then `tools/check-fat32-images.sh` checks the images with `fsck.fat` from dosfstools.

## Detection limitations

- Android may hide mount paths, filesystem type, exact capacity, or partition tables from ordinary apps.
- A USB card reader and a USB flash drive can look similar to Android; USB-vs-SD classification is best-effort, and a manual picker is provided.
- Partition-table inspection requires lower-level access not generally available to a normal app.

## Build

Open in Android Studio, or build locally with Gradle 8.9+ and JDK 17: `gradle :app:assembleDebug`.

## Test safely

Try formatting on an expendable drive first. Check the size shown in the confirmation dialog matches the drive you mean to erase.

## License

MIT (see `LICENSE`).