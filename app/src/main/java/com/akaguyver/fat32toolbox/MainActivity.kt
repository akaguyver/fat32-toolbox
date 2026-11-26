package com.akaguyver.fat32toolbox

import android.content.Intent
import android.hardware.usb.UsbConstants
import android.hardware.usb.UsbManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.os.StatFs
import android.os.storage.StorageManager
import android.os.storage.StorageVolume
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
import java.util.Locale

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

    private val folderPicker = registerForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri: Uri? ->
        uri?.let { inspectFolder(it) }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
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
                            listOf("USB").forEach { type ->
                                FilterChip(selected = deviceFilter == type, onClick = { deviceFilter = type; selectedIndex = -1 }, label = { Text(type) })
                            }
                        }
                        val filtered = volumes.filter { deviceFilter == "All" || it.category == deviceFilter || (deviceFilter == "USB" && it.category == "USB / card reader") }
                        Box {
                            OutlinedButton(onClick = { deviceMenuOpen = true }, modifier = Modifier.fillMaxWidth()) {
                                Text(if (selectedIndex in filtered.indices) filtered[selectedIndex].label else "Select detected storage volume")
                            }
                            DropdownMenu(expanded = deviceMenuOpen, onDismissRequest = { deviceMenuOpen = false }) {
                                filtered.forEachIndexed { index, v ->
                                    DropdownMenuItem(text = { Text("${v.label} • ${v.category}") }, onClick = {
                                        selectedIndex = index
                                        deviceMenuOpen = false
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
                        OutlinedButton(onClick = { folderPicker.launch(null) }) { Text("Choose USB / SD card folder") }
                        Text(selectedFolderInfo)
                        HorizontalDivider()
                        Text("Choose desired format", style = MaterialTheme.typography.titleLarge)
                        Box {
                            OutlinedButton(onClick = { formatMenuOpen = true }, modifier = Modifier.fillMaxWidth()) { Text("Format: $formatChoice") }
                            DropdownMenu(expanded = formatMenuOpen, onDismissRequest = { formatMenuOpen = false }) {
                                listOf("FAT32", "exFAT", "NTFS").forEach { f ->
                                    DropdownMenuItem(text = { Text(f) }, onClick = { formatChoice = f; formatMenuOpen = false })
                                }
                            }
                        }
                        Text(formatDescription(formatChoice))
                        Text("Formatting is not enabled yet. This selector records your choice only; no disk changes are made. A verified backend with raw-device permission is required, and stock Android does not provide a public API for arbitrary removable-drive formatting.", style = MaterialTheme.typography.bodyMedium)
                        Text("No format button is shown until the selected device can be positively identified and a safe formatter is available.", style = MaterialTheme.typography.bodyMedium)
                    }
                }
            }
        }
    }

    private fun refreshAll() {
        refreshUsbDevices()
        refreshVolumes()
    }

    private fun refreshUsbDevices() {
        val manager = getSystemService(USB_SERVICE) as UsbManager
        val devices = manager.deviceList.values.toList()
        usbInfo = if (devices.isEmpty()) "No USB peripherals detected." else devices.joinToString("\n\n") { d ->
            val storage = (0 until d.interfaceCount).any { d.getInterface(it).interfaceClass == UsbConstants.USB_CLASS_MASS_STORAGE }
            "Device: ${d.deviceName}\nManufacturer: ${d.manufacturerName ?: "Unknown"}\nProduct: ${d.productName ?: "Unknown"}\nVendor ID: ${d.vendorId} • Product ID: ${d.productId}\nInterfaces: ${d.interfaceCount}\nMass-storage interface: ${if (storage) "Yes" else "Not detected"}"
        }
    }

    private fun refreshVolumes() {\n        android.util.Log.i("FAT32Toolbox", "SD_FUTURE_PROBE: checking Android StorageManager for removable volumes; SD formatting is not enabled.")
        val sm = getSystemService(STORAGE_SERVICE) as StorageManager
        val found = mutableListOf<VolumeInfo>()
        for (v in sm.storageVolumes) {
            if (!v.isRemovable) continue
            val label = v.getDescription(this)
            val state = v.state
            val path = if (Build.VERSION.SDK_INT >= 30) v.directory?.absolutePath else null
            val stats = try { path?.let { StatFs(it) } } catch (_: Exception) { null }
            val total = try { stats?.totalBytes } catch (_: Exception) { null }
            val free = try { stats?.availableBytes } catch (_: Exception) { null }
            val fs = path?.let { detectFilesystem(it) } ?: "Unknown"
            val lower = label.lowercase(Locale.ROOT)
            val category = when {
                lower.contains("sd") || lower.contains("card") -> "SD future candidate (not enabled)"
                lower.contains("usb") || usbHasMassStorage() -> "USB / card reader"
                else -> "Removable storage"
            }
            found.add(VolumeInfo(v, label, category, state, path, total, free, fs))
        }
        volumes = found
        selectedIndex = if (found.isNotEmpty()) 0 else -1
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
        } catch (_: Exception) { "Unknown (permission restricted)" }
    }

    private fun inspectFolder(uri: Uri) {
        try {
            contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION)
        } catch (_: SecurityException) { }
        val folder = DocumentFile.fromTreeUri(this, uri)
        selectedFolderInfo = if (folder == null) "Could not read selected folder." else {
            val children = try { folder.listFiles().size } catch (_: Exception) { -1 }
            "Selected: ${folder.name ?: "Unnamed volume"}\nReadable entries at root: ${if (children >= 0) children else "Unavailable"}\nFile access: Granted by Android. Filesystem and full disk capacity may still be hidden by the document provider."
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
