package com.akaguyver.fat32toolbox.format

import java.nio.ByteBuffer
import java.nio.ByteOrder

data class PartitionInfo(val number: Int, val startSector: Long, val sectorCount: Long, val filesystem: String, val label: String?)

data class DiskReport(val table: String, val partitions: List<PartitionInfo>)

/** Reads a drive's first sectors and names its partition table and each partition's filesystem. Read-only. */
object DiskProbe {
    fun probe(device: BlockDevice): DiskReport {
        val ss = device.sectorSize
        val mbr = readBytes(device, 0, ss)
        val hasSig = u8(mbr, 510) == 0x55 && u8(mbr, 511) == 0xAA

        // A filesystem written straight onto the drive, with no partition table ("superfloppy").
        identify(device, 0)?.let { (fs, label) ->
            return DiskReport("None (whole drive is one filesystem)", listOf(PartitionInfo(1, 0, device.sectorCount, fs, label)))
        }
        if (!hasSig) {
            return if (mbr.all { it == 0.toByte() }) DiskReport("No partition table (drive looks blank)", emptyList())
            else DiskReport("Unknown (no partition table found)", emptyList())
        }

        val entries = (0 until 4).map { i ->
            val e = 446 + i * 16
            Triple(u8(mbr, e + 4), u32(mbr, e + 8), u32(mbr, e + 12))
        }.filter { it.first != 0 && it.third != 0L }

        if (entries.any { it.first == 0xEE }) {
            val hdr = readBytes(device, 1, ss)
            if (String(hdr, 0, 8, Charsets.US_ASCII) == "EFI PART") return DiskReport("GPT", gptPartitions(device, hdr))
        }
        val parts = entries.mapIndexed { i, (type, start, count) ->
            val fs = identify(device, start)
            PartitionInfo(i + 1, start, count, fs?.first ?: unknown(type), fs?.second)
        }
        return DiskReport("MBR", parts)
    }

    private fun gptPartitions(device: BlockDevice, hdr: ByteArray): List<PartitionInfo> {
        val ss = device.sectorSize
        val entriesLba = le64(hdr, 72)
        val count = u32(hdr, 80).coerceAtMost(128).toInt()
        val size = u32(hdr, 84).toInt().coerceIn(128, 512)
        val bytes = readBytes(device, entriesLba, ((count.toLong() * size + ss - 1) / ss * ss).toInt())
        return (0 until count).mapNotNull { i ->
            val o = i * size
            if ((0 until 16).all { bytes[o + it] == 0.toByte() }) return@mapNotNull null
            val first = le64(bytes, o + 32); val last = le64(bytes, o + 40)
            val fs = identify(device, first)
            PartitionInfo(i + 1, first, last - first + 1, fs?.first ?: "Unknown", fs?.second)
        }
    }

    /** Filesystem name and label at [lba], or null if nothing recognisable is there. */
    private fun identify(device: BlockDevice, lba: Long): Pair<String, String?>? {
        val ss = device.sectorSize
        if (lba >= device.sectorCount) return null
        val n = minOf(device.sectorCount - lba, ((4096 + ss - 1) / ss).toLong()).toInt()
        val b = readBytes(device, lba, n * ss)
        val sig = u8(b, 510) == 0x55 && u8(b, 511) == 0xAA
        val oem = ascii(b, 3, 8)
        if (oem == "EXFAT   ") return "exFAT" to null
        if (oem == "NTFS    ") return "NTFS" to null
        if (sig && u16(b, 11) in listOf(512, 1024, 2048, 4096) && u8(b, 13) != 0 && u8(b, 16) in 1..2) {
            if (ascii(b, 82, 5) == "FAT32" || (u16(b, 22) == 0 && u32(b, 36) != 0L)) return "FAT32" to label(ascii(b, 71, 11))
            val fat = ascii(b, 54, 5).let { if (it == "FAT12" || it == "FAT16") it else "FAT16" }
            return fat to label(ascii(b, 43, 11))
        }
        if (b.size >= 2048) {
            if (u16(b, 1024 + 56) == 0xEF53) {
                val compat = u32(b, 1024 + 92); val incompat = u32(b, 1024 + 96)
                val fs = when {
                    incompat and 0x40L != 0L -> "ext4"
                    compat and 0x4L != 0L -> "ext3"
                    else -> "ext2"
                }
                return fs to label(ascii(b, 1024 + 120, 16))
            }
            val hfs = ascii(b, 1024, 2)
            if (hfs == "H+" || hfs == "HX") return "HFS+ (Mac)" to null
        }
        if (ascii(b, 32, 4) == "NXSB") return "APFS (Mac)" to null
        return null
    }

    private fun unknown(type: Int) = "Unknown (type 0x%02X)".format(type)
    private fun label(s: String) = s.trim { it <= ' ' || it == '\u0000' }.takeUnless { it.isEmpty() || it == "NO NAME" }
    private fun readBytes(d: BlockDevice, lba: Long, len: Int) = ByteArray(len).also { d.read(lba, it) }
    private fun ascii(b: ByteArray, o: Int, n: Int) = if (o + n <= b.size) String(b, o, n, Charsets.ISO_8859_1) else ""
    private fun u8(b: ByteArray, o: Int) = b[o].toInt() and 0xFF
    private fun u16(b: ByteArray, o: Int) = if (o + 2 <= b.size) u8(b, o) or (u8(b, o + 1) shl 8) else -1
    private fun u32(b: ByteArray, o: Int) = ByteBuffer.wrap(b, o, 4).order(ByteOrder.LITTLE_ENDIAN).int.toLong() and 0xFFFFFFFFL
    private fun le64(b: ByteArray, o: Int) = ByteBuffer.wrap(b, o, 8).order(ByteOrder.LITTLE_ENDIAN).long
}
