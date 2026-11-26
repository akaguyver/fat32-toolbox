package com.akaguyver.fat32toolbox

import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.hardware.usb.UsbConstants
import android.hardware.usb.UsbManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.StatFs
import android.os.storage.StorageManager
import android.os.storage.StorageVolume
import android.util.Log
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.documentfile.provider.DocumentFile
import androidx.lifecycle.lifecycleScope
import com.akaguyver.fat32toolbox.format.DiskProbe
import com.akaguyver.fat32toolbox.format.DiskReport
import com.akaguyver.fat32toolbox.format.Fat32Formatter
import com.akaguyver.fat32toolbox.format.Fat32Layout
import com.akaguyver.fat32toolbox.format.FormatException
import com.akaguyver.fat32toolbox.usb.OpenedUsbDrive
import com.akaguyver.fat32toolbox.usb.UsbDisk
import com.akaguyver.fat32toolbox.usb.UsbDrive
import com.akaguyver.fat32toolbox.usb.UsbDrives
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import me.jahnen.libaums.core.driver.scsi.commands.sense.DataProtect
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone

private data class VolumeInfo(
    val volume: StorageVolume,
    val label: String,
    val category: String,
    val state: String,
    val path: String?,
    val total: Long?,
    val free: Long?,
    val filesystem: String
)

class MainActivity : ComponentActivity() {
    private var usbInfo by mutableStateOf("Checking connected USB devices…")
    private var volumes by mutableStateOf(listOf<VolumeInfo>())
    private var selectedIndex by mutableIntStateOf(-1)
    private var selectedFolderInfo by mutableStateOf("No folder selected.")
    private var deviceFilter by mutableStateOf("USB")
    private var formatChoice by mutableStateOf("FAT32")
    private var formatMenuOpen by mutableStateOf(false)
    private var deviceMenuOpen by mutableStateOf(false)
    private val diagnosticEvents = mutableListOf<String>()

    // Formatting through the USB host API (raw sector access; works without root).
    private var usbDrives by mutableStateOf(listOf<UsbDrive>())
    private var opened by mutableStateOf<OpenedUsbDrive?>(null)
    private var selectedLun by mutableIntStateOf(-1)
    private var labelInput by mutableStateOf("USB DRIVE")
    private var formatStatus by mutableStateOf("")
    private var busy by mutableStateOf(false)
    private var formatting by mutableStateOf(false)
    private var formatProgress by mutableFloatStateOf(0f)
    private var confirmOpen by mutableStateOf(false)
    private var confirmText by mutableStateOf("")
    private var pendingDrive: UsbDrive? = null
    private var probes by mutableStateOf<Map<Int, Result<DiskReport>>>(emptyMap())
    private val usbPermissionAction by lazy { "$packageName.USB_PERMISSION" }

    private val usbPermissionReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            if (intent.action != usbPermissionAction) return
            val drive = pendingDrive ?: return
            pendingDrive = null
            val usbManager = getSystemService(USB_SERVICE) as UsbManager
            if (usbManager.hasPermission(drive.device)) {
                logEvent("USB permission granted for ${drive.name}")
                openDrive(drive)
            } else {
                logEvent("USB permission denied for ${drive.name}")
                formatStatus = "USB permission was denied, so the app can't reach the drive."
            }
        }
    }

    private val folderPicker = registerForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri: Uri? ->
        logEvent("Folder picker result: ${if (uri == null) "cancelled" else "URI granted"}")
        uri?.let { inspectFolder(it) }
    }

    private val logPicker = registerForActivityResult(ActivityResultContracts.CreateDocument("text/plain")) { uri: Uri? ->
        if (uri != null) exportDiagnostics(uri) else logEvent("Diagnostic export cancelled")
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        logEvent("App started; Android ${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT}); device ${Build.MANUFACTURER} ${Build.MODEL}")
        ContextCompat.registerReceiver(this, usbPermissionReceiver, IntentFilter(usbPermissionAction), ContextCompat.RECEIVER_NOT_EXPORTED)
        refreshAll()
        setContent {
            MaterialTheme {
                Surface(modifier = Modifier.fillMaxSize()) {
                    Column(modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(18.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                        Text("FAT32 Toolbox", style = MaterialTheme.typography.headlineMedium)
                        Text("Inspect removable storage • no ads • no root")
                        HorizontalDivider()
                        Text("Choose device type", style = MaterialTheme.typography.titleLarge)
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            FilterChip(selected = deviceFilter == "USB", onClick = { deviceFilter = "USB"; selectedIndex = -1; logEvent("Device filter set to USB") }, label = { Text("USB") })
                        }
                        val filtered = volumes.filter { it.category == "USB / card reader" }
                        Box {
                            OutlinedButton(onClick = { deviceMenuOpen = true }, modifier = Modifier.fillMaxWidth()) {
                                Text(if (selectedIndex in filtered.indices) filtered[selectedIndex].label else "Select detected USB storage volume")
                            }
                            DropdownMenu(expanded = deviceMenuOpen, onDismissRequest = { deviceMenuOpen = false }) {
                                filtered.forEachIndexed { index, v ->
                                    DropdownMenuItem(text = { Text("${v.label} • ${v.category}") }, onClick = {
                                        selectedIndex = index
                                        deviceMenuOpen = false
                                        logEvent("Selected volume: ${v.label}")
                                    })
                                }
                            }
                        }
                        Button(onClick = { refreshAll() }) { Text("Refresh devices") }
                        Text("USB peripherals", style = MaterialTheme.typography.titleMedium)
                        Text(usbInfo)
                        if (selectedIndex in filtered.indices) {
                            val v = filtered[selectedIndex]
                            HorizontalDivider()
                            Text("Selected volume", style = MaterialTheme.typography.titleLarge)
                            Text("Name: ${v.label}")
                            Text("Type: ${v.category}")
                            Text("Mount state: ${v.state}")
                            Text("Filesystem: ${v.filesystem}")
                            Text("Capacity: ${v.total?.let { formatBytes(it) } ?: "Not exposed by Android"}")
                            Text("Available: ${v.free?.let { formatBytes(it) } ?: "Not exposed by Android"}")
                            Text("Mount path: ${v.path ?: "Not exposed by Android"}")
                            Text("Partition layout: Android does not expose raw partition tables to ordinary apps.")
                        }
                        HorizontalDivider()
                        Text("Manual storage selection", style = MaterialTheme.typography.titleLarge)
                        Text("Use Android's picker if a volume is not listed above.")
                        OutlinedButton(onClick = { folderPicker.launch(null) }) { Text("Choose USB folder") }
                        Text(selectedFolderInfo)
                        HorizontalDivider()
                        Text("Choose desired format", style = MaterialTheme.typography.titleLarge)
                        Box {
                            OutlinedButton(onClick = { formatMenuOpen = true }, modifier = Modifier.fillMaxWidth()) { Text("Format: $formatChoice") }
                            DropdownMenu(expanded = formatMenuOpen, onDismissRequest = { formatMenuOpen = false }) {
                                listOf("FAT32", "exFAT", "NTFS").forEach { f ->
                                    DropdownMenuItem(text = { Text(f) }, onClick = { formatChoice = f; formatMenuOpen = false; logEvent("Desired filesystem selected: $f") })
                                }
                            }
                        }
                        Text(formatDescription(formatChoice))
                        FormatSection()
                        HorizontalDivider()
                        Text("Diagnostics", style = MaterialTheme.typography.titleLarge)
                        Text("The log records what Android exposed and every step of a format. Review it before sharing: it includes device names.", style = MaterialTheme.typography.bodyMedium)
                        OutlinedButton(onClick = { logPicker.launch("fat32-toolbox-diagnostics.txt") }, modifier = Modifier.fillMaxWidth()) {
                            Text("Export diagnostic log")
                        }
                    }
                }
            }
        }
    }

    override fun onDestroy() {
        unregisterReceiver(usbPermissionReceiver)
        // Never cut the connection in the middle of a format; the write would be left half done.
        if (!formatting) opened?.close()
        super.onDestroy()
    }

    @Composable
    private fun FormatSection() {
        HorizontalDivider()
        Text("Format a USB drive", style = MaterialTheme.typography.titleLarge)
        Text("Works with USB flash drives and SD cards in a USB card reader, through USB OTG. It talks to the drive directly over USB, so it can never touch the phone's internal storage. A card in the phone's own SD slot can't be formatted this way.", style = MaterialTheme.typography.bodyMedium)
        val o = opened
        if (o == null) {
            if (usbDrives.isEmpty()) Text("No USB drive found. Plug one in and tap Refresh devices.")
            usbDrives.forEach { d ->
                OutlinedButton(onClick = { connect(d) }, enabled = !busy, modifier = Modifier.fillMaxWidth()) {
                    Text("Check / format ${d.name} (${d.ids})")
                }
            }
            if (usbDrives.isNotEmpty()) Text("Connecting takes the drive away from Android: it disappears from Files until you unplug and replug it. Close any app that is copying to it first.", style = MaterialTheme.typography.bodySmall)
        } else {
            Text("Connected: ${o.drive.name} (${o.drive.ids})")
            if (o.disks.isEmpty()) Text("No media found. For a card reader, check that a card is inserted, then disconnect and connect again.")
            o.disks.forEachIndexed { i, disk ->
                val size = diskSize(disk)
                FilterChip(selected = selectedLun == i, onClick = { selectedLun = i }, enabled = !busy,
                    label = { Text((if (o.disks.size > 1) "Slot ${disk.lun + 1}: " else "") + size) })
                Text("Current format: " + describe(probes[i], disk.sectorSize), style = MaterialTheme.typography.bodyMedium)
            }
            val disk = o.disks.getOrNull(selectedLun)
            if (disk != null) {
                val plan = remember(disk) { planFor(disk) }
                OutlinedTextField(value = labelInput, onValueChange = { labelInput = it.take(11) }, enabled = !busy,
                    label = { Text("Volume label (up to 11 characters)") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                val labelError = try { Fat32Formatter.normalizeLabel(labelInput); null } catch (e: FormatException) { e.message }
                labelError?.let { Text(it, color = MaterialTheme.colorScheme.error) }
                when {
                    formatChoice != "FAT32" -> Text("Only FAT32 can be written for now. Choose FAT32 above to format.", color = MaterialTheme.colorScheme.error)
                    plan.second != null -> Text(plan.second!!, color = MaterialTheme.colorScheme.error)
                    else -> {
                        val l = plan.first!!
                        Text("Will create one MBR partition with FAT32, ${formatBytes(l.clusterBytes.toLong())} clusters, ${formatBytes(l.usableBytes)} usable. Files over 4 GB can't be stored on FAT32.")
                        Button(onClick = { confirmText = ""; confirmOpen = true }, enabled = !busy && labelError == null,
                            colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error), modifier = Modifier.fillMaxWidth()) {
                            Text("Erase and format as FAT32")
                        }
                    }
                }
            }
            OutlinedButton(onClick = { disconnect("Disconnected. Unplug and replug the drive so Android mounts it again.") }, enabled = !busy) { Text("Disconnect") }
        }
        if (formatting) LinearProgressIndicator(progress = { formatProgress }, modifier = Modifier.fillMaxWidth())
        if (formatStatus.isNotEmpty()) Text(formatStatus)

        val target = opened?.disks?.getOrNull(selectedLun)
        if (confirmOpen && target != null) {
            AlertDialog(
                onDismissRequest = { confirmOpen = false },
                title = { Text("Erase this drive?") },
                text = {
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text("Everything on ${opened?.drive?.name} (${diskSize(target)}) will be permanently erased and it will be formatted as FAT32.")
                        Text("Type ERASE to confirm.")
                        OutlinedTextField(value = confirmText, onValueChange = { confirmText = it }, singleLine = true)
                    }
                },
                confirmButton = {
                    TextButton(onClick = { confirmOpen = false; startFormat(target) }, enabled = confirmText.trim() == "ERASE") { Text("Erase and format") }
                },
                dismissButton = { TextButton(onClick = { confirmOpen = false }) { Text("Cancel") } },
            )
        }
    }

    private fun describe(r: Result<DiskReport>?, sectorSize: Int): String {
        if (r == null) return "Not read"
        val report = r.getOrElse { return "Couldn't read the drive (${it.message ?: it.javaClass.simpleName})" }
        if (report.partitions.isEmpty()) return report.table
        val parts = report.partitions.joinToString("; ") { p ->
            val size = formatBytes(p.sectorCount * sectorSize)
            "${p.filesystem}${p.label?.let { " \"$it\"" } ?: ""}, $size"
        }
        return "$parts (partition table: ${report.table})"
    }

    private fun diskSize(disk: UsbDisk): String = try {
        "${formatBytes(disk.sectorCount * disk.sectorSize)} (${disk.sectorCount} sectors of ${disk.sectorSize} bytes)"
    } catch (e: Exception) { e.message ?: "Unknown size" }

    private fun planFor(disk: UsbDisk): Pair<Fat32Layout?, String?> = try {
        Fat32Formatter.plan(disk.sectorCount, disk.sectorSize) to null
    } catch (e: Exception) { null to (e.message ?: "Can't format this drive as FAT32") }

    private fun connect(drive: UsbDrive) {
        val usbManager = getSystemService(USB_SERVICE) as UsbManager
        if (usbManager.hasPermission(drive.device)) { openDrive(drive); return }
        pendingDrive = drive
        formatStatus = "Waiting for USB permission…"
        logEvent("Requesting USB permission for ${drive.name} (${drive.ids})")
        val flags = PendingIntent.FLAG_UPDATE_CURRENT or (if (Build.VERSION.SDK_INT >= 31) PendingIntent.FLAG_MUTABLE else 0)
        val pi = PendingIntent.getBroadcast(this, 0, Intent(usbPermissionAction).setPackage(packageName), flags)
        usbManager.requestPermission(drive.device, pi)
    }

    private fun openDrive(drive: UsbDrive) {
        busy = true
        formatStatus = "Connecting to ${drive.name}…"
        lifecycleScope.launch(Dispatchers.IO) {
            val result = runCatching { UsbDrives.open(getSystemService(USB_SERVICE) as UsbManager, drive, ::logEvent) }
            val found = result.getOrNull()?.disks?.mapIndexed { i, disk ->
                i to runCatching { DiskProbe.probe(disk) }.onFailure { logEvent("Probe of LUN ${disk.lun} failed: ${it.javaClass.simpleName}: ${it.message}") }
            }?.toMap() ?: emptyMap()
            result.getOrNull()?.disks?.forEachIndexed { i, disk -> logEvent("Probe LUN ${disk.lun}: ${describe(found[i], disk.sectorSize)}") }
            withContext(Dispatchers.Main) {
                busy = false
                probes = found
                result.onSuccess { o ->
                    opened = o
                    selectedLun = if (o.disks.size == 1) 0 else -1
                    formatStatus = if (o.disks.size > 1) "Choose the slot to format." else ""
                    logEvent("Connected to ${drive.name}: ${o.disks.size} disk(s) with media")
                }.onFailure { e ->
                    logEvent("Open failed: ${e.javaClass.simpleName}: ${e.message}")
                    formatStatus = "Couldn't connect: ${e.message ?: e.javaClass.simpleName}. Unplug and replug the drive, then try again."
                }
            }
        }
    }

    private fun disconnect(message: String) {
        try { opened?.close() } catch (e: Exception) { logEvent("Close failed: ${e.message}") }
        opened = null
        probes = emptyMap()
        selectedLun = -1
        formatStatus = message
        refreshAll()
    }

    private fun startFormat(disk: UsbDisk) {
        val label = try { Fat32Formatter.normalizeLabel(labelInput) } catch (e: FormatException) { formatStatus = e.message ?: ""; return }
        busy = true; formatting = true; formatProgress = 0f
        formatStatus = "Formatting… don't unplug the drive."
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        logEvent("Format started: LUN ${disk.lun}, ${disk.sectorCount} sectors x ${disk.sectorSize}, label='$label'")
        lifecycleScope.launch(Dispatchers.IO) {
            val message = try {
                val l = Fat32Formatter.format(disk, label, progress = { done, total -> formatProgress = done.toFloat() / total })
                logEvent("Format finished and verified: partitionStart=${l.partitionStart}, clusters=${l.clusterCount}, clusterBytes=${l.clusterBytes}, fatSectors=${l.fatSectors}")
                "Done: formatted as FAT32${if (label.isNotEmpty()) " \"$label\"" else ""}, ${formatBytes(l.usableBytes)} usable, and checked by reading it back. Unplug the drive and plug it back in to use it."
            } catch (e: DataProtect) {
                logEvent("Format failed: write protected")
                "The drive is write-protected. If it's an SD card, check the lock switch on its side. Nothing was changed."
            } catch (e: Exception) {
                logEvent("Format failed: ${e.javaClass.simpleName}: ${e.message}")
                "Format failed: ${e.message ?: e.javaClass.simpleName}. The drive may be left unreadable; unplug and replug it, then try again."
            }
            withContext(Dispatchers.Main) {
                window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
                formatting = false; busy = false
                disconnect(message)
            }
        }
    }

    private fun logEvent(message: String) {
        val stamp = SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS", Locale.US).format(Date())
        synchronized(diagnosticEvents) {
            diagnosticEvents.add("$stamp  $message")
            if (diagnosticEvents.size > 300) diagnosticEvents.removeAt(0)
        }
        Log.i("FAT32Toolbox", message)
    }

    private fun refreshAll() {
        logEvent("Refresh started")
        refreshUsbDevices()
        if (opened == null) usbDrives = try { UsbDrives.find(getSystemService(USB_SERVICE) as UsbManager) } catch (e: Exception) { emptyList() }
        refreshVolumes()
        logEvent("Refresh finished: ${volumes.size} removable volume(s) listed")
    }

    private fun refreshUsbDevices() {
        try {
            val manager = getSystemService(USB_SERVICE) as UsbManager
            val devices = manager.deviceList.values.toList()
            usbInfo = if (devices.isEmpty()) "No USB peripherals detected." else devices.joinToString("\n\n") { d ->
                val storage = (0 until d.interfaceCount).any { d.getInterface(it).interfaceClass == UsbConstants.USB_CLASS_MASS_STORAGE }
                "Device: ${d.deviceName}\nManufacturer: ${d.manufacturerName ?: "Unknown"}\nProduct: ${d.productName ?: "Unknown"}\nVendor ID: ${d.vendorId} • Product ID: ${d.productId}\nInterfaces: ${d.interfaceCount}\nMass-storage interface: ${if (storage) "Yes" else "Not detected"}"
            }
            logEvent("USB enumeration: ${devices.size} peripheral(s)")
            devices.forEach { d ->
                val classes = (0 until d.interfaceCount).map { d.getInterface(it).interfaceClass }.distinct()
                logEvent("USB device name=${d.deviceName}; manufacturer=${d.manufacturerName}; product=${d.productName}; VID=${d.vendorId}; PID=${d.productId}; interfaces=${d.interfaceCount}; interfaceClasses=$classes")
            }
        } catch (e: Exception) {
            usbInfo = "USB enumeration failed: ${e.javaClass.simpleName}: ${e.message}"
            logEvent(usbInfo)
        }
    }

    private fun refreshVolumes() {
        logEvent("Checking Android StorageManager removable volumes")
        try {
            val sm = getSystemService(STORAGE_SERVICE) as StorageManager
            val found = mutableListOf<VolumeInfo>()
            val allVolumes = sm.storageVolumes
            logEvent("StorageManager returned ${allVolumes.size} volume(s)")
            for (v in allVolumes) {
                val label = v.getDescription(this)
                val state = v.state
                val removable = v.isRemovable
                val path = if (Build.VERSION.SDK_INT >= 30) v.directory?.absolutePath else null
                logEvent("Volume: label=$label; removable=$removable; state=$state; path=${path ?: "not exposed"}")
                if (!removable) continue
                val stats = try { path?.let { StatFs(it) } } catch (e: Exception) {
                    logEvent("StatFs failed for $label: ${e.javaClass.simpleName}: ${e.message}")
                    null
                }
                val total = try { stats?.totalBytes } catch (_: Exception) { null }
                val free = try { stats?.availableBytes } catch (_: Exception) { null }
                val fs = path?.let { detectFilesystem(it) } ?: "Unknown"
                val lower = label.lowercase(Locale.ROOT)
                val category = when {
                    lower.contains("sd") || lower.contains("card") -> "SD future candidate (not enabled)"
                    lower.contains("usb") || usbHasMassStorage() -> "USB / card reader"
                    else -> "Removable storage (unclassified)"
                }
                found.add(VolumeInfo(v, label, category, state, path, total, free, fs))
                logEvent("Removable volume details: category=$category; filesystem=$fs; totalBytes=$total; availableBytes=$free")
            }
            volumes = found
            selectedIndex = -1
        } catch (e: Exception) {
            logEvent("StorageManager enumeration failed: ${e.javaClass.simpleName}: ${e.message}")
            volumes = emptyList()
            selectedIndex = -1
        }
    }

    private fun usbHasMassStorage(): Boolean {
        val manager = getSystemService(USB_SERVICE) as UsbManager
        return manager.deviceList.values.any { d ->
            (0 until d.interfaceCount).any { d.getInterface(it).interfaceClass == UsbConstants.USB_CLASS_MASS_STORAGE }
        }
    }

    private fun detectFilesystem(path: String): String {
        return try {
            val canonical = File(path).canonicalPath
            val matches = File("/proc/mounts").readLines().mapNotNull { line ->
                val parts = line.split(Regex("\\s+"))
                if (parts.size >= 3 && (canonical == parts[1] || canonical.startsWith(parts[1].trimEnd('/') + "/"))) parts[2] to parts[1].length else null
            }
            matches.maxByOrNull { it.second }?.first ?: "Unknown (mount table unavailable)"
        } catch (e: Exception) {
            logEvent("Mount table read failed: ${e.javaClass.simpleName}: ${e.message}")
            "Unknown (permission restricted)"
        }
    }

    private fun inspectFolder(uri: Uri) {
        try {
            contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION)
        } catch (e: SecurityException) {
            logEvent("Persistable folder permission unavailable: ${e.message}")
        }
        val folder = DocumentFile.fromTreeUri(this, uri)
        selectedFolderInfo = if (folder == null) "Could not read selected folder." else {
            val children = try { folder.listFiles().size } catch (e: Exception) {
                logEvent("SAF listFiles failed: ${e.javaClass.simpleName}: ${e.message}")
                -1
            }
            logEvent("SAF folder selected: name=${folder.name}; canRead=${folder.canRead()}; canWrite=${folder.canWrite()}; rootEntries=$children")
            "Selected: ${folder.name ?: "Unnamed volume"}\nReadable entries at root: ${if (children >= 0) children else "Unavailable"}\nRead access: ${folder.canRead()}\nWrite access: ${folder.canWrite()}\nFile access is not raw-disk access; filesystem and partition access may remain hidden."
        }
    }

    private fun exportDiagnostics(uri: Uri) {
        val report = buildString {
            appendLine("FAT32 Toolbox diagnostic report")
            appendLine("Generated: ${SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ssXXX", Locale.US).format(Date())}")
            appendLine("Device: ${Build.MANUFACTURER} ${Build.MODEL}")
            appendLine("Android: ${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT})")
            appendLine("App package: $packageName")
            appendLine("Selected filesystem target: $formatChoice")
            appendLine("Device filter: $deviceFilter")
            appendLine()
            appendLine("USB peripheral summary:")
            appendLine(usbInfo)
            appendLine()
            appendLine("Removable volumes:")
            if (volumes.isEmpty()) appendLine("None exposed by StorageManager")
            volumes.forEach { v ->
                appendLine("Name: ${v.label}")
                appendLine("Category: ${v.category}")
                appendLine("State: ${v.state}")
                appendLine("Path: ${v.path ?: "not exposed"}")
                appendLine("Filesystem: ${v.filesystem}")
                appendLine("Total bytes: ${v.total ?: "not exposed"}")
                appendLine("Available bytes: ${v.free ?: "not exposed"}")
                appendLine("---")
            }
            appendLine()
            appendLine("SAF selection:")
            appendLine(selectedFolderInfo)
            appendLine()
            appendLine("Event log (most recent 300 events):")
            synchronized(diagnosticEvents) { diagnosticEvents.forEach { appendLine(it) } }
            appendLine()
            appendLine("Privacy: includes device model, USB identifiers, volume labels and mount paths when Android exposes them. Review before sharing.")
        }
        try {
            contentResolver.openOutputStream(uri)?.use { it.write(report.toByteArray(Charsets.UTF_8)) }
                ?: throw IllegalStateException("Android did not provide an output stream")
            logEvent("Diagnostic report exported successfully")
        } catch (e: Exception) {
            logEvent("Diagnostic export failed: ${e.javaClass.simpleName}: ${e.message}")
        }
    }

    private fun formatDescription(format: String): String = when (format) {
        "FAT32" -> "Broad compatibility with older devices. FAT32 has a 4 GiB maximum individual file size."
        "exFAT" -> "Good for large drives and files; compatibility depends on the target device."
        else -> "NTFS supports large files, but many media players and embedded devices do not support it."
    }

    private fun formatBytes(bytes: Long): String {
        val units = listOf("B", "KB", "MB", "GB", "TB")
        var value = bytes.toDouble()
        var i = 0
        while (value >= 1024 && i < units.lastIndex) { value /= 1024; i++ }
        return String.format(Locale.US, "%.2f %s", value, units[i])
    }
}
