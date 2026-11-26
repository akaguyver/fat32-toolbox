package com.akaguyver.fat32toolbox.format

import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.Calendar
import java.util.Locale

class FormatException(message: String) : Exception(message)

/** Where everything goes on the disk. All sector numbers are absolute (from the start of the disk). */
data class Fat32Layout(
    val sectorSize: Int,
    val diskSectors: Long,
    val partitionStart: Long,
    val partitionSectors: Long,
    val sectorsPerCluster: Int,
    val reservedSectors: Int,
    val fatSectors: Long,
    val clusterCount: Long,
) {
    val numFats get() = 2
    val fatStart get() = partitionStart + reservedSectors
    val dataStart get() = fatStart + numFats * fatSectors
    val clusterBytes get() = sectorsPerCluster * sectorSize
    val usableBytes get() = clusterCount * clusterBytes
}

/**
 * Writes a single-partition MBR disk with one FAT32 filesystem, following Microsoft's FAT32 spec
 * (fatgen103): 2 FATs, root directory in cluster 2, FSInfo in sector 1, backup boot sector in sector 6.
 */
object Fat32Formatter {
    const val MIN_CLUSTERS = 65525L          // fewer clusters than this is FAT16 by definition
    const val MAX_CLUSTERS = 0x0FFFFFF5L - 2 // highest valid cluster number is 0x0FFFFFF4
    private const val MAX_MBR_SECTORS = 0xFFFFFFFFL
    private const val CHUNK_BYTES = 64 * 1024

    fun plan(diskSectors: Long, sectorSize: Int): Fat32Layout {
        if (sectorSize !in listOf(512, 1024, 2048, 4096)) throw FormatException("Unsupported sector size: $sectorSize bytes")
        if (diskSectors > MAX_MBR_SECTORS) throw FormatException("Drive is larger than 2 TiB, which FAT32 with an MBR partition table can't address")
        val align = (1024L * 1024 / sectorSize)  // 1 MiB, the usual partition alignment
        if (diskSectors < align * 8) throw FormatException("Drive is too small for FAT32 (needs at least about 33 MB)")
        val partitionStart = align
        val partitionSectors = diskSectors - partitionStart
        val partitionBytes = partitionSectors * sectorSize

        // Microsoft's default cluster sizes for FAT32.
        val preferredClusterBytes = when {
            partitionBytes <= 260L shl 20 -> 512
            partitionBytes <= 8L shl 30 -> 4096
            partitionBytes <= 16L shl 30 -> 8192
            partitionBytes <= 32L shl 30 -> 16384
            else -> 32768
        }
        var spc = maxOf(1, preferredClusterBytes / sectorSize)
        while (true) {
            val layout = layoutFor(diskSectors, sectorSize, partitionStart, partitionSectors, spc)
            if (layout.clusterCount >= MIN_CLUSTERS) {
                if (layout.clusterCount > MAX_CLUSTERS) throw FormatException("Too many clusters for FAT32")
                return layout
            }
            if (spc == 1) throw FormatException("Drive is too small for FAT32 (needs at least about 33 MB)")
            spc /= 2
        }
    }

    private fun layoutFor(diskSectors: Long, ss: Int, start: Long, sectors: Long, spc: Int): Fat32Layout {
        var reserved = 32
        // Size the FATs for the most clusters the partition could hold; slightly oversized FATs are allowed.
        val maxClusters = (sectors - reserved) / spc
        val fatSectors = ceilDiv((maxClusters + 2) * 4, ss.toLong())
        // Line the data area up on a cluster boundary.
        while ((reserved + 2 * fatSectors) % spc != 0L) reserved++
        val clusters = (sectors - reserved - 2 * fatSectors) / spc
        return Fat32Layout(ss, diskSectors, start, sectors, spc, reserved, fatSectors, maxOf(0, clusters))
    }

    /** Turns user input into an 11-character FAT label, or throws if it has characters FAT doesn't allow. */
    fun normalizeLabel(input: String): String {
        val label = input.trim().uppercase(Locale.ROOT)
        if (label.length > 11) throw FormatException("Label can be at most 11 characters")
        val allowed = "ABCDEFGHIJKLMNOPQRSTUVWXYZ0123456789 !#$%&'()-@^_`{}~"
        label.firstOrNull { it !in allowed }?.let { throw FormatException("Label can't contain '$it'") }
        return label
    }

    /**
     * Formats the whole device. [progress] gets (sectors written, sectors to write).
     * Returns the layout that was written. Throws on any write or verify failure.
     */
    fun format(
        device: BlockDevice,
        label: String,
        volumeId: Int = (System.nanoTime() xor System.currentTimeMillis()).toInt(),
        diskSignature: Int = volumeId.rotateLeft(13),
        now: Calendar = Calendar.getInstance(),
        progress: (Long, Long) -> Unit = { _, _ -> },
    ): Fat32Layout {
        val layout = plan(device.sectorCount, device.sectorSize)
        val ss = layout.sectorSize
        val volLabel = normalizeLabel(label)
        val chunkSectors = maxOf(1, CHUNK_BYTES / ss)

        // Regions to zero: everything before the partition (old MBR/GPT), the reserved area, both FATs,
        // the root directory cluster, and the last 34 sectors of the disk (where a backup GPT lives).
        val tailStart = maxOf(layout.dataStart + layout.sectorsPerCluster, layout.diskSectors - 34)
        val regions = listOf(
            0L to layout.partitionStart,
            layout.partitionStart to layout.dataStart + layout.sectorsPerCluster,
            tailStart to layout.diskSectors,
        ).filter { it.second > it.first }
        val total = regions.sumOf { it.second - it.first }
        var done = 0L
        val zeros = ByteArray(chunkSectors * ss)
        for ((from, to) in regions) {
            var lba = from
            while (lba < to) {
                val n = minOf(chunkSectors.toLong(), to - lba).toInt()
                device.write(lba, if (n == chunkSectors) zeros else ByteArray(n * ss))
                lba += n
                done += n
                progress(done, total)
            }
        }

        // FAT entries 0 and 1 are reserved; entry 2 is the root directory (a one-cluster chain).
        val fat0 = sector(ss).apply {
            putInt(0, 0x0FFFFFF8); putInt(4, 0x0FFFFFFF); putInt(8, 0x0FFFFFFF)
        }.array()
        for (i in 0 until layout.numFats) device.write(layout.fatStart + i * layout.fatSectors, fat0)

        if (volLabel.isNotEmpty()) {
            val root = sector(ss)
            root.position(0); root.put(volLabel.padEnd(11).toByteArray(Charsets.US_ASCII))
            root.put(11, 0x08)  // volume label attribute
            root.putShort(22, dosTime(now)); root.putShort(24, dosDate(now))
            device.write(layout.dataStart, root.array())
        }

        val boot = bootSector(layout, volLabel, volumeId)
        val fsInfo = fsInfoSector(layout)
        val sig2 = sector(ss).apply { put(510, 0x55); put(511, 0xAA.toByte()) }.array()
        for (base in listOf(0L, 6L)) {
            device.write(layout.partitionStart + base, boot)
            device.write(layout.partitionStart + base + 1, fsInfo)
            device.write(layout.partitionStart + base + 2, sig2)
        }

        // The partition table goes last, so an interrupted format never points at a half-built filesystem.
        val mbr = mbrSector(layout, diskSignature)
        device.write(0, mbr)

        verify(device, listOf(
            0L to mbr,
            layout.partitionStart to boot,
            layout.partitionStart + 1 to fsInfo,
            layout.partitionStart + 6 to boot,
            layout.partitionStart + 7 to fsInfo,
            layout.fatStart to fat0,
            layout.fatStart + layout.fatSectors to fat0,
        ), ss)
        return layout
    }

    private fun verify(device: BlockDevice, expected: List<Pair<Long, ByteArray>>, ss: Int) {
        val buf = ByteArray(ss)
        for ((lba, bytes) in expected) {
            device.read(lba, buf)
            if (!buf.contentEquals(bytes)) throw FormatException("Verification failed: sector $lba didn't read back as written")
        }
    }

    internal fun bootSector(l: Fat32Layout, volLabel: String, volumeId: Int): ByteArray {
        val b = sector(l.sectorSize)
        b.put(0, 0xEB.toByte()); b.put(1, 0x58); b.put(2, 0x90.toByte())
        b.position(3); b.put("MSWIN4.1".toByteArray(Charsets.US_ASCII))
        b.putShort(11, l.sectorSize.toShort())
        b.put(13, l.sectorsPerCluster.toByte())
        b.putShort(14, l.reservedSectors.toShort())
        b.put(16, l.numFats.toByte())
        b.put(21, 0xF8.toByte())           // media: fixed disk
        b.putShort(24, 63); b.putShort(26, 255)
        b.putInt(28, l.partitionStart.toInt())   // hidden sectors before the partition
        b.putInt(32, l.partitionSectors.toInt())
        b.putInt(36, l.fatSectors.toInt())
        b.putInt(44, 2)                    // root directory cluster
        b.putShort(48, 1)                  // FSInfo sector
        b.putShort(50, 6)                  // backup boot sector
        b.put(64, 0x80.toByte())           // drive number
        b.put(66, 0x29)                    // extended boot signature
        b.putInt(67, volumeId)
        b.position(71); b.put((volLabel.ifEmpty { "NO NAME" }).padEnd(11).toByteArray(Charsets.US_ASCII))
        b.put("FAT32   ".toByteArray(Charsets.US_ASCII))
        // Boot code at 0x5A: "int 18h" (tell the BIOS this disk isn't bootable), then halt.
        b.put(0x5A, 0xCD.toByte()); b.put(0x5B, 0x18); b.put(0x5C, 0xEB.toByte()); b.put(0x5D, 0xFE.toByte())
        b.put(510, 0x55); b.put(511, 0xAA.toByte())
        return b.array()
    }

    internal fun fsInfoSector(l: Fat32Layout): ByteArray {
        val b = sector(l.sectorSize)
        b.putInt(0, 0x41615252)
        b.putInt(484, 0x61417272)
        b.putInt(488, (l.clusterCount - 1).toInt())  // free clusters (root uses one)
        b.putInt(492, 3)                              // next free cluster hint
        b.putInt(508, 0xAA550000.toInt())
        return b.array()
    }

    internal fun mbrSector(l: Fat32Layout, diskSignature: Int): ByteArray {
        val b = sector(l.sectorSize)
        b.put(0, 0xCD.toByte()); b.put(1, 0x18); b.put(2, 0xEB.toByte()); b.put(3, 0xFE.toByte())
        b.putInt(440, diskSignature)
        val e = 446
        b.put(e, 0x00)                                   // not marked bootable
        putChs(b, e + 1, l.partitionStart)
        b.put(e + 4, 0x0C)                               // FAT32 with LBA addressing
        putChs(b, e + 5, l.partitionStart + l.partitionSectors - 1)
        b.putInt(e + 8, l.partitionStart.toInt())
        b.putInt(e + 12, l.partitionSectors.toInt())
        b.put(510, 0x55); b.put(511, 0xAA.toByte())
        return b.array()
    }

    /** CHS address with the conventional 255 heads / 63 sectors geometry, capped at cylinder 1023. */
    private fun putChs(b: ByteBuffer, at: Int, lba: Long) {
        var c = lba / (255 * 63)
        var h = (lba / 63) % 255
        var s = lba % 63 + 1
        if (c > 1023) { c = 1023; h = 254; s = 63 }
        b.put(at, h.toByte())
        b.put(at + 1, ((s and 0x3F) or ((c shr 2) and 0xC0)).toByte())
        b.put(at + 2, (c and 0xFF).toByte())
    }

    private fun dosTime(c: Calendar): Short =
        ((c.get(Calendar.HOUR_OF_DAY) shl 11) or (c.get(Calendar.MINUTE) shl 5) or (c.get(Calendar.SECOND) / 2)).toShort()

    private fun dosDate(c: Calendar): Short =
        (((c.get(Calendar.YEAR) - 1980).coerceIn(0, 127) shl 9) or ((c.get(Calendar.MONTH) + 1) shl 5) or c.get(Calendar.DAY_OF_MONTH)).toShort()

    private fun sector(size: Int) = ByteBuffer.allocate(size).order(ByteOrder.LITTLE_ENDIAN)
    private fun ceilDiv(a: Long, b: Long) = (a + b - 1) / b
}
