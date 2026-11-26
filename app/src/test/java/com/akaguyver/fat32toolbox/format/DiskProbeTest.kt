package com.akaguyver.fat32toolbox.format

import org.junit.Assert.assertEquals
import org.junit.Test
import java.nio.ByteBuffer
import java.nio.ByteOrder

/** Sparse in-memory disk: unwritten sectors read as zeros. */
private class MemDisk(override val sectorCount: Long, override val sectorSize: Int = 512) : BlockDevice {
    private val sectors = HashMap<Long, ByteArray>()
    override fun read(lba: Long, buffer: ByteArray) {
        for (i in 0 until buffer.size / sectorSize) {
            val s = sectors[lba + i] ?: ByteArray(sectorSize)
            System.arraycopy(s, 0, buffer, i * sectorSize, sectorSize)
        }
    }
    override fun write(lba: Long, buffer: ByteArray) {
        for (i in 0 until buffer.size / sectorSize) sectors[lba + i] = buffer.copyOfRange(i * sectorSize, (i + 1) * sectorSize)
    }
    fun poke(lba: Long, offset: Int, bytes: ByteArray) {
        val s = sectors.getOrPut(lba) { ByteArray(sectorSize) }
        System.arraycopy(bytes, 0, s, offset, bytes.size)
    }
    fun le(lba: Long, offset: Int, value: Int, width: Int) {
        val b = ByteBuffer.allocate(4).order(ByteOrder.LITTLE_ENDIAN).putInt(value).array()
        poke(lba, offset, b.copyOf(width))
    }
}

class DiskProbeTest {
    private val gib = 1024L * 1024 * 1024

    private fun MemDisk.mbrEntry(index: Int, type: Int, start: Long, count: Long) {
        val e = 446 + index * 16
        poke(0, e + 4, byteArrayOf(type.toByte()))
        le(0, e + 8, start.toInt(), 4); le(0, e + 12, count.toInt(), 4)
        poke(0, 510, byteArrayOf(0x55, 0xAA.toByte()))
    }

    @Test fun blankDriveIsReportedAsEmpty() {
        val r = DiskProbe.probe(MemDisk(8 * gib / 512))
        assertEquals("No partition table (drive looks blank)", r.table)
        assertEquals(emptyList<PartitionInfo>(), r.partitions)
    }

    @Test fun ourOwnFormatIsDetectedAsFat32WithLabel() {
        val d = MemDisk(8 * gib / 512)
        Fat32Formatter.format(d, "MYSTICK")
        val r = DiskProbe.probe(d)
        assertEquals("MBR", r.table)
        assertEquals(1, r.partitions.size)
        assertEquals("FAT32", r.partitions[0].filesystem)
        assertEquals("MYSTICK", r.partitions[0].label)
        assertEquals(2048L, r.partitions[0].startSector)
    }

    @Test fun exfatAndNtfsPartitionsAreNamed() {
        val d = MemDisk(64 * gib / 512)
        d.mbrEntry(0, 0x07, 2048, 1_000_000)
        d.mbrEntry(1, 0x07, 1_002_048, 1_000_000)
        d.poke(2048, 3, "EXFAT   ".toByteArray()); d.poke(2048, 510, byteArrayOf(0x55, 0xAA.toByte()))
        d.poke(1_002_048, 3, "NTFS    ".toByteArray()); d.poke(1_002_048, 510, byteArrayOf(0x55, 0xAA.toByte()))
        val r = DiskProbe.probe(d)
        assertEquals(listOf("exFAT", "NTFS"), r.partitions.map { it.filesystem })
    }

    @Test fun ext4PartitionIsDetected() {
        val d = MemDisk(8 * gib / 512)
        d.mbrEntry(0, 0x83, 2048, 1_000_000)
        d.le(2048 + 2, 56, 0xEF53, 2)                // superblock at byte 1024, magic at +56
        d.le(2048 + 2, 96, 0x0040, 4)                // feature_incompat: extents -> ext4
        assertEquals("ext4", DiskProbe.probe(d).partitions[0].filesystem)
    }

    @Test fun gptDiskListsItsPartitions() {
        val d = MemDisk(64 * gib / 512)
        d.mbrEntry(0, 0xEE, 1, 0xFFFFFFFFL)          // protective MBR
        d.poke(1, 0, "EFI PART".toByteArray())
        d.le(1, 72, 2, 4); d.le(1, 80, 128, 4); d.le(1, 84, 128, 4)  // entries at LBA 2, 128 entries of 128 bytes
        d.poke(2, 0, ByteArray(16) { 1 })            // non-zero type GUID
        d.le(2, 32, 2048, 4); d.le(2, 40, 2_099_199, 4)
        d.poke(2048, 3, "EXFAT   ".toByteArray()); d.poke(2048, 510, byteArrayOf(0x55, 0xAA.toByte()))
        val r = DiskProbe.probe(d)
        assertEquals("GPT", r.table)
        assertEquals(1, r.partitions.size)
        assertEquals("exFAT", r.partitions[0].filesystem)
        assertEquals(2_097_152L, r.partitions[0].sectorCount)
    }

    @Test fun filesystemWithoutPartitionTableIsDetected() {
        val d = MemDisk(8 * gib / 512)
        d.poke(0, 3, "EXFAT   ".toByteArray()); d.poke(0, 510, byteArrayOf(0x55, 0xAA.toByte()))
        val r = DiskProbe.probe(d)
        assertEquals("None (whole drive is one filesystem)", r.table)
        assertEquals("exFAT", r.partitions[0].filesystem)
    }

    @Test fun unknownContentIsReportedHonestly() {
        val d = MemDisk(8 * gib / 512)
        d.mbrEntry(0, 0x83, 2048, 1_000_000)
        d.poke(2048, 0, byteArrayOf(1, 2, 3))
        assertEquals("Unknown (type 0x83)", DiskProbe.probe(d).partitions[0].filesystem)
    }
}
