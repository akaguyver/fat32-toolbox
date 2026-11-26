package com.akaguyver.fat32toolbox

import android.content.Intent
import android.hardware.usb.UsbConstants
import android.hardware.usb.UsbManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.StatFs
import android.os.storage.StorageManager
import android.os.storage.StorageVolume
import android.util.Log
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.documentfile.provider.DocumentFile
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
        refreshAll()
        setContent {
            MaterialTheme {
                Surface(modifier = Modifier.fillMaxSize()) {
                    Column(modifier = Modifier.fillMaxSize().padding(18.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
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
                        Text("Formatting is not enabled. This selector records your choice only; no disk changes are made. Stock Android does not provide a public API for arbitrary removable-drive formatting.", style = MaterialTheme.typography.bodyMedium)
                        Text("Diagnostics help determine what Android exposes. They do not prove that raw formatting is safe or permitted.", style = MaterialTheme.typography.bodyMedium)
                        OutlinedButton(onClick = { logPicker.launch("fat32-toolbox-diagnostics.txt") }, modifier = Modifier.fillMaxWidth()) {
                            Text("Export diagnostic log")
                        }
                    }
                }
            }
        }
    }

    private fun logEvent(message: String) {
        val stamp = SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS", Locale.US).format(Date())
        diagnosticEvents.add("$stamp  $message")
        if (diagnosticEvents.size > 300) diagnosticEvents.removeAt(0)
        Log.i("FAT32Toolbox", message)
    }

    private fun refreshAll() {
        logEvent("Refresh started")
        refreshUsbDevices()
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
        logEvent("SD_FUTURE_PROBE: checking Android StorageManager removable volumes; SD formatting remains disabled")
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
            diagnosticEvents.forEach { appendLine(it) }
            appendLine()
            appendLine("Limitations: this report is observational only. It does not establish raw block-device access, exclusive device control, formatter availability, or safe-to-format status.")
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
