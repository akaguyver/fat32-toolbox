package com.akaguyver.fat32toolbox

import android.app.Activity
import android.content.Intent
import android.hardware.usb.UsbManager
import android.net.Uri
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.documentfile.provider.DocumentFile

class MainActivity : ComponentActivity() {
    private var selectedInfo by mutableStateOf("No storage folder selected yet.")
    private var usbInfo by mutableStateOf("Checking connected USB devices…")
    private val folderPicker = registerForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri: Uri? ->
        uri?.let { inspectFolder(it) }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        refreshUsbDevices()
        setContent {
            MaterialTheme {
                Surface(modifier = Modifier.fillMaxSize()) {
                    Column(modifier = Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
                        Text("FAT32 Toolbox", style = MaterialTheme.typography.headlineMedium)
                        Text("Storage inspection • no ads • no root")
                        HorizontalDivider()
                        Text("USB devices", style = MaterialTheme.typography.titleLarge)
                        Text(usbInfo)
                        Button(onClick = { refreshUsbDevices() }) { Text("Refresh USB list") }
                        HorizontalDivider()
                        Text("Inspect a drive", style = MaterialTheme.typography.titleLarge)
                        Text("Choose the drive's top-level folder using Android's system picker. This grants file-level access only; it does not grant permission to format the disk.")
                        Button(onClick = { folderPicker.launch(null) }) { Text("Choose storage folder") }
                        Text(selectedInfo)
                        HorizontalDivider()
                        Text("Formatting", style = MaterialTheme.typography.titleLarge)
                        Text("Not available yet. Stock Android does not provide a safe public API for formatting arbitrary removable drives. No format operation will be attempted.")
                    }
                }
            }
        }
    }

    private fun refreshUsbDevices() {
        val manager = getSystemService(USB_SERVICE) as UsbManager
        val devices = manager.deviceList.values.toList()
        usbInfo = if (devices.isEmpty()) "No USB peripherals detected. Connect a drive or card reader using OTG, then refresh." else
            devices.joinToString("\n\n") { d ->
                "Device: ${d.deviceName}\nManufacturer: ${d.manufacturerName ?: "Unknown"}\nProduct: ${d.productName ?: "Unknown"}\nVendor ID: ${d.vendorId} • Product ID: ${d.productId}\nInterfaces: ${d.interfaceCount}"
            }
    }

    private fun inspectFolder(uri: Uri) {
        try {
            contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION)
        } catch (_: SecurityException) { }
        val folder = DocumentFile.fromTreeUri(this, uri)
        selectedInfo = if (folder == null) "Could not read the selected folder." else {
            val name = folder.name ?: "(unnamed volume)"
            val children = folder.listFiles().size
            val path = uri.path ?: uri.toString()
            "Selected: $name\nReadable entries at root: $children\nProvider URI: $path\n\nFilesystem type and exact disk capacity are not exposed consistently by Android's document picker. USB device details above are separate from mounted-volume details."
        }
    }
}