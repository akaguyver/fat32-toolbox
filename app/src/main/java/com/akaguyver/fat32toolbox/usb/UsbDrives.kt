package com.akaguyver.fat32toolbox.usb

import android.hardware.usb.UsbConstants
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbEndpoint
import android.hardware.usb.UsbInterface
import android.hardware.usb.UsbManager
import com.akaguyver.fat32toolbox.format.BlockDevice
import me.jahnen.libaums.core.driver.BlockDeviceDriver
import me.jahnen.libaums.core.driver.BlockDeviceDriverFactory
import me.jahnen.libaums.core.driver.scsi.commands.sense.MediaNotInserted
import me.jahnen.libaums.core.usb.UsbCommunication
import me.jahnen.libaums.core.usb.UsbCommunicationFactory
import java.io.Closeable
import java.io.IOException
import java.nio.ByteBuffer
import java.util.Locale

/** A USB mass-storage (SCSI, bulk-only) interface: a flash drive or a USB card reader. */
class UsbDrive(val device: UsbDevice, val usbInterface: UsbInterface, val inEndpoint: UsbEndpoint, val outEndpoint: UsbEndpoint) {
    val name: String
        get() {
            val n = listOfNotNull(device.manufacturerName?.trim(), device.productName?.trim()).filter { it.isNotEmpty() }.distinct().joinToString(" ")
            return n.ifEmpty { "USB drive" }
        }
    val ids: String get() = String.format(Locale.US, "%04X:%04X", device.vendorId, device.productId)
}

/** One slot (LUN) of an opened drive. A card reader with several slots has several. */
class UsbDisk(val lun: Int, private val driver: BlockDeviceDriver) : BlockDevice {
    override val sectorSize: Int get() = driver.blockSize

    /** libaums reports the last block address as a signed Int; read it as unsigned and add one. */
    override val sectorCount: Long
        get() {
            val last = driver.blocks.toInt().toLong() and 0xFFFFFFFFL
            if (last == 0xFFFFFFFFL) throw IOException("Drive is 2 TiB or larger, which FAT32 can't use")
            return last + 1
        }

    override fun read(lba: Long, buffer: ByteArray) = driver.read(lba, ByteBuffer.wrap(buffer))
    override fun write(lba: Long, buffer: ByteArray) = driver.write(lba, ByteBuffer.wrap(buffer))
}

/** An open connection. Opening takes the drive away from Android, which unmounts it. */
class OpenedUsbDrive(val drive: UsbDrive, private val communication: UsbCommunication, val disks: List<UsbDisk>) : Closeable {
    override fun close() = communication.close()
}

object UsbDrives {
    private const val SUBCLASS_SCSI = 6
    private const val PROTOCOL_BULK_ONLY = 0x50

    fun find(usbManager: UsbManager): List<UsbDrive> = usbManager.deviceList.values.flatMap { device ->
        (0 until device.interfaceCount).map { device.getInterface(it) }
            .filter { it.interfaceClass == UsbConstants.USB_CLASS_MASS_STORAGE && it.interfaceSubclass == SUBCLASS_SCSI && it.interfaceProtocol == PROTOCOL_BULK_ONLY }
            .mapNotNull { iface ->
                val eps = (0 until iface.endpointCount).map { iface.getEndpoint(it) }.filter { it.type == UsbConstants.USB_ENDPOINT_XFER_BULK }
                val inEp = eps.firstOrNull { it.direction == UsbConstants.USB_DIR_IN }
                val outEp = eps.firstOrNull { it.direction == UsbConstants.USB_DIR_OUT }
                if (inEp != null && outEp != null) UsbDrive(device, iface, inEp, outEp) else null
            }
    }

    /** Needs USB permission for the device first. Blocks; call off the main thread. */
    fun open(usbManager: UsbManager, drive: UsbDrive, log: (String) -> Unit): OpenedUsbDrive {
        if (!usbManager.hasPermission(drive.device)) throw IOException("No permission for this USB device")
        val comm = UsbCommunicationFactory.createUsbCommunication(usbManager, drive.device, drive.usbInterface, drive.outEndpoint, drive.inEndpoint)
        try {
            // GET MAX LUN (bulk-only class request); devices with one slot may stall it, which means LUN 0 only.
            val maxLun = ByteArray(1)
            val maxLunResult = try { comm.controlTransfer(0xA1, 0xFE, 0, drive.usbInterface.id, maxLun, 1) } catch (e: IOException) { -1 }
            val luns = if (maxLunResult == 1) maxLun[0].toInt().coerceIn(0, 15) else 0
            log("GET MAX LUN result=$maxLunResult maxLun=$luns")
            val disks = (0..luns).mapNotNull { lun ->
                val driver = BlockDeviceDriverFactory.createBlockDevice(comm, lun.toByte())
                try {
                    driver.init()
                    UsbDisk(lun, driver).also { log("LUN $lun: ${it.sectorCount} sectors x ${it.sectorSize} bytes") }
                } catch (e: MediaNotInserted) {
                    log("LUN $lun: no media inserted")
                    null
                } catch (e: IOException) {
                    log("LUN $lun: init failed: ${e.javaClass.simpleName}: ${e.message}")
                    null
                }
            }
            return OpenedUsbDrive(drive, comm, disks)
        } catch (e: Exception) {
            comm.close()
            throw e
        }
    }
}
