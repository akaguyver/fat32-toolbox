package com.akaguyver.fat32toolbox.format

import java.io.IOException

/** A whole disk addressed in sectors. Buffers passed to read/write are always a whole number of sectors. */
interface BlockDevice {
    val sectorSize: Int
    val sectorCount: Long

    @Throws(IOException::class)
    fun read(lba: Long, buffer: ByteArray)

    @Throws(IOException::class)
    fun write(lba: Long, buffer: ByteArray)
}
