package com.akaguyver.fat32toolbox.format

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.io.File
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Disk image backed by two files: the sectors before the partition in memory, the partition itself in a
 * sparse file, so tools like fsck.fat can check the partition file directly.
 */
private class SplitImage(override val sectorCount: Long, override val sectorSize: Int, partFile: File) : BlockDevice {
    val start = 1024L * 1024 / sectorSize
    val head = ByteArray((start * sectorSize).toInt())
    private val part = RandomAccessFile(partFile, "rw").apply { setLength((sectorCount - start) * sectorSize) }
    val writes = mutableListOf<Long>()

    override fun read(lba: Long, buffer: ByteArray) {
        if (lba < start) System.arraycopy(head, (lba * sectorSize).toInt(), buffer, 0, buffer.size)
        else { part.seek((lba - start) * sectorSize); part.readFully(buffer) }
    }

    override fun write(lba: Long, buffer: ByteArray) {
        require(buffer.size % sectorSize == 0)
        require(lba >= 0 && lba + buffer.size / sectorSize <= sectorCount) { "write past end of disk at $lba" }
        writes += lba
        if (lba < start) {
            require(lba + buffer.size / sectorSize <= start) { "write straddles partition start" }
            System.arraycopy(buffer, 0, head, (lba * sectorSize).toInt(), buffer.size)
        } else { part.seek((lba - start) * sectorSize); part.write(buffer) }
    }

    fun close() = part.close()
}

class Fat32FormatterTest {
    private val outDir = File("build/fat32-images").apply { mkdirs() }
    private val mib = 1024L * 1024
    private val gib = 1024 * mib

    @Test fun clusterSizesFollowMicrosoftDefaults() {
        assertEquals(512, Fat32Formatter.plan(64 * mib / 512, 512).clusterBytes)
        assertEquals(4096, Fat32Formatter.plan(4 * gib / 512, 512).clusterBytes)
        assertEquals(8192, Fat32Formatter.plan(12 * gib / 512, 512).clusterBytes)
        assertEquals(16384, Fat32Formatter.plan(30 * gib / 512, 512).clusterBytes)
        assertEquals(32768, Fat32Formatter.plan(128 * gib / 512, 512).clusterBytes)
    }

    @Test fun layoutsAreValidAcrossSizes() {
        val sizes = listOf(40 * mib, 64 * mib, 260 * mib, 300 * mib, gib, 8 * gib, 32 * gib, 64 * gib, 256 * gib, 2047 * gib)
        for (bytes in sizes) for (ss in listOf(512, 4096)) {
            if (ss == 4096 && bytes < 300 * mib) continue  // 4 KiB sectors can't make 65525 clusters that small
            val l = Fat32Formatter.plan(bytes / ss, ss)
            val what = "${bytes / mib} MiB @ $ss"
            assertTrue("$what cluster count ${l.clusterCount}", l.clusterCount in Fat32Formatter.MIN_CLUSTERS..Fat32Formatter.MAX_CLUSTERS)
            assertTrue("$what FAT too small", l.fatSectors * ss / 4 >= l.clusterCount + 2)
            assertTrue("$what reserved ${l.reservedSectors}", l.reservedSectors in 32..0xFFFF)
            assertEquals("$what data not cluster aligned", 0L, (l.dataStart - l.partitionStart) % l.sectorsPerCluster)
            assertTrue("$what clusters overflow partition", l.dataStart + l.clusterCount * l.sectorsPerCluster <= l.partitionStart + l.partitionSectors)
            assertEquals("$what partition not 1 MiB aligned", 0L, l.partitionStart * ss % mib)
        }
    }

    @Test fun rejectsDrivesFat32CannotHold() {
        expectFormatError { Fat32Formatter.plan(16 * mib / 512, 512) }
        expectFormatError { Fat32Formatter.plan(0x1_0000_0000L, 512) }  // over 2 TiB
        expectFormatError { Fat32Formatter.plan(gib / 520, 520) }
    }

    @Test fun labelRules() {
        assertEquals("MY USB", Fat32Formatter.normalizeLabel(" my usb "))
        assertEquals("", Fat32Formatter.normalizeLabel(""))
        expectFormatError { Fat32Formatter.normalizeLabel("TWELVE CHARS") }
        expectFormatError { Fat32Formatter.normalizeLabel("A*B") }
        expectFormatError { Fat32Formatter.normalizeLabel("café") }
    }

    @Test fun formatsImagesForFsck() {
        // Images are left in app/build/fat32-images for fsck.fat (see tools/check-fat32-images.sh).
        for ((name, bytes, ss) in listOf(Triple("64m", 64 * mib, 512), Triple("300m", 300 * mib, 512), Triple("16g", 16 * gib, 512), Triple("40g", 40 * gib, 512), Triple("8g-4k", 8 * gib, 4096))) {
            val dev = SplitImage(bytes / ss, ss, File(outDir, "$name.part.img"))
            // Pretend there was an old partition table and GPT on the drive.
            dev.head.fill(0x5A)
            var lastProgress = 0L to 0L
            val layout = Fat32Formatter.format(dev, "Test $name", volumeId = 0x12345678, diskSignature = 0x0BADF00D) { d, t -> lastProgress = d to t }
            dev.close()
            assertEquals("progress should end complete", lastProgress.second, lastProgress.first)

            val mbr = ByteBuffer.wrap(dev.head, 0, ss).order(ByteOrder.LITTLE_ENDIAN)
            assertEquals(0x55, dev.head[510].toInt() and 0xFF)
            assertEquals(0xAA, dev.head[511].toInt() and 0xFF)
            assertEquals(0x0C, dev.head[446 + 4].toInt())
            assertEquals(layout.partitionStart, mbr.getInt(446 + 8).toLong() and 0xFFFFFFFFL)
            assertEquals(layout.partitionSectors, mbr.getInt(446 + 12).toLong() and 0xFFFFFFFFL)
            assertEquals(0x0BADF00D, mbr.getInt(440))
            for (i in 1 until 4) assertEquals("partition entry $i should be empty", 0, mbr.get(446 + 16 * i + 4).toInt())
            assertArrayEquals("old data before the partition should be wiped", ByteArray(dev.head.size - ss), dev.head.copyOfRange(ss, dev.head.size))
            assertEquals("MBR must be written last", 0L, dev.writes.last())
        }
    }

    private fun expectFormatError(block: () -> Unit) {
        try { block(); fail("expected FormatException") } catch (_: FormatException) {}
    }
}
