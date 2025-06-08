package com.wobbz.fartloop.feature.home.components

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Launch
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.ClipboardManager
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.wobbz.fartloop.core.blast.DiscoveredDevice
import com.wobbz.fartloop.core.blast.DeviceType
import com.wobbz.fartloop.core.blast.DeviceStatus
import com.wobbz.fartloop.design.theme.FartLooperTheme
import timber.log.Timber
import java.text.SimpleDateFormat
import java.util.*

/**
 * Device Information Dialog - Comprehensive UPnP device details
 *
 * DEVICE INFO DIALOG FINDING: Users want detailed technical information about discovered devices
 * including manufacturer details, model specifications, supported services, and network configuration.
 * This dialog presents XML-parsed device information in a user-friendly, organized format.
 *
 * FEATURES:
 * - Organized sections (Identity, Technical, Network, Services)
 * - Clickable URLs that open in browser
 * - Copy-to-clipboard for technical values
 * - Comprehensive error handling for missing information
 * - Material Design 3 styling with proper accessibility
 *
 * @param device The discovered device to show information for
 * @param onDismiss Callback when dialog is dismissed
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DeviceInfoDialog(
    device: DiscoveredDevice,
    onDismiss: () -> Unit
) {
    val clipboardManager = LocalClipboardManager.current
    val uriHandler = LocalUriHandler.current

    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(
            dismissOnBackPress = true,
            dismissOnClickOutside = true,
            usePlatformDefaultWidth = false
        )
    ) {
        Card(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp)
                .heightIn(max = 600.dp),
            shape = RoundedCornerShape(16.dp),
            colors = CardDefaults.cardColors(
                containerColor = MaterialTheme.colorScheme.surface
            ),
            elevation = CardDefaults.cardElevation(defaultElevation = 8.dp)
        ) {
            Column(
                modifier = Modifier.fillMaxWidth()
            ) {
                // Header with device name and close button
                DeviceInfoHeader(
                    deviceName = device.name,
                    deviceType = device.type,
                    onDismiss = onDismiss
                )

                // Scrollable content
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .verticalScroll(rememberScrollState())
                        .padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(16.dp)
                ) {
                    // Device Identity Section
                    DeviceInfoSection(
                        title = "Device Identity",
                        icon = Icons.Default.Info
                    ) {
                        DeviceInfoRow("Name", device.name, clipboardManager)
                        DeviceInfoRow("Type", device.type.name, clipboardManager)
                        DeviceInfoRow("Status", device.status.name, clipboardManager)

                        // Show XML-parsed friendly name if different from discovered name
                        val xmlFriendlyName = device.metadata["friendlyName"]
                        if (xmlFriendlyName != null && xmlFriendlyName != device.name) {
                            DeviceInfoRow("XML Friendly Name", xmlFriendlyName, clipboardManager)
                        }
                    }

                    // Manufacturer Information
                    val manufacturer = device.metadata["manufacturer"]
                    val manufacturerURL = device.metadata["manufacturerURL"]
                    val modelDescription = device.metadata["modelDescription"]
                    val modelName = device.metadata["modelName"]
                    val modelNumber = device.metadata["modelNumber"]
                    val modelURL = device.metadata["modelURL"]

                    if (listOfNotNull(manufacturer, manufacturerURL, modelDescription, modelName, modelNumber, modelURL).isNotEmpty()) {
                        DeviceInfoSection(
                            title = "Manufacturer Info",
                            icon = Icons.Default.Info
                        ) {
                            manufacturer?.let {
                                DeviceInfoRow("Manufacturer", it, clipboardManager)
                            }
                            manufacturerURL?.let {
                                DeviceInfoUrlRow("Manufacturer URL", it, uriHandler, clipboardManager)
                            }
                            modelDescription?.let {
                                DeviceInfoRow("Model Description", it, clipboardManager)
                            }
                            modelName?.let {
                                DeviceInfoRow("Model Name", it, clipboardManager)
                            }
                            modelNumber?.let {
                                DeviceInfoRow("Model Number", it, clipboardManager)
                            }
                            modelURL?.let {
                                DeviceInfoUrlRow("Model URL", it, uriHandler, clipboardManager)
                            }
                        }
                    }

                    // Technical Specifications
                    DeviceInfoSection(
                        title = "Technical Specs",
                        icon = Icons.Default.Info
                    ) {
                        DeviceInfoRow("IP Address", device.ipAddress, clipboardManager)
                        DeviceInfoRow("Port", device.port.toString(), clipboardManager)
                        DeviceInfoRow("Control URL", device.controlUrl, clipboardManager)

                        device.metadata["deviceType"]?.let {
                            DeviceInfoRow("UPnP Device Type", it, clipboardManager)
                        }
                        device.metadata["UDN"]?.let {
                            DeviceInfoRow("Unique Device Name", it, clipboardManager)
                        }
                        device.metadata["serialNumber"]?.let {
                            DeviceInfoRow("Serial Number", it, clipboardManager)
                        }
                    }

                    // Network & Services
                    val services = device.metadata["services"]
                    val serviceCount = device.metadata["serviceCount"]
                    val presentationURL = device.metadata["presentationURL"]

                    if (listOfNotNull(services, serviceCount, presentationURL).isNotEmpty()) {
                        DeviceInfoSection(
                            title = "Network & Services",
                            icon = Icons.Default.Info
                        ) {
                            serviceCount?.let {
                                DeviceInfoRow("Service Count", it, clipboardManager)
                            }
                            services?.let {
                                DeviceInfoRow("Available Services", it, clipboardManager, maxLines = 3)
                            }
                            presentationURL?.let {
                                DeviceInfoUrlRow("Presentation URL", it, uriHandler, clipboardManager)
                            }
                        }
                    }

                    // Discovery Information
                    DeviceInfoSection(
                        title = "Discovery Info",
                        icon = Icons.Default.Info
                    ) {
                        DeviceInfoRow("Discovery Method", "SSDP", clipboardManager)
                        DeviceInfoRow("Device ID", device.id, clipboardManager)

                        val xmlParsedAt = device.metadata["xmlParsedAt"]
                        if (xmlParsedAt != null) {
                            val formattedTime = try {
                                val timestamp = xmlParsedAt.toLong()
                                val formatter = SimpleDateFormat("MMM dd, yyyy HH:mm:ss", Locale.getDefault())
                                formatter.format(Date(timestamp))
                            } catch (e: Exception) {
                                xmlParsedAt
                            }
                            DeviceInfoRow("XML Parsed At", formattedTime, clipboardManager)
                        }

                        val parseError = device.metadata["parseError"]
                        if (parseError != null) {
                            DeviceInfoRow("Parse Error", parseError, clipboardManager, isError = true)
                        }
                    }

                    // Show all metadata for debugging if there are extra fields
                    val knownFields = setOf(
                        "friendlyName", "manufacturer", "manufacturerURL", "modelDescription",
                        "modelName", "modelNumber", "modelURL", "serialNumber", "UDN",
                        "deviceType", "presentationURL", "services", "serviceCount",
                        "xmlParsedAt", "parseError"
                    )
                    val extraFields = device.metadata.filterKeys { it !in knownFields }
                    if (extraFields.isNotEmpty()) {
                        DeviceInfoSection(
                            title = "Additional Metadata",
                            icon = Icons.Default.Info
                        ) {
                            extraFields.forEach { (key, value) ->
                                DeviceInfoRow(key, value, clipboardManager)
                            }
                        }
                    }
                }
            }
        }
    }
}

/**
 * Header section with device name and close button
 */
@Composable
private fun DeviceInfoHeader(
    deviceName: String,
    deviceType: DeviceType,
    onDismiss: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(
                MaterialTheme.colorScheme.primaryContainer,
                RoundedCornerShape(topStart = 16.dp, topEnd = 16.dp)
            )
            .padding(16.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = deviceName,
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onPrimaryContainer,
                fontWeight = FontWeight.SemiBold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            Text(
                text = deviceType.name.lowercase().replaceFirstChar { it.uppercase() },
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.8f)
            )
        }

        IconButton(onClick = onDismiss) {
            Icon(
                imageVector = Icons.Default.Close,
                contentDescription = "Close device info",
                tint = MaterialTheme.colorScheme.onPrimaryContainer
            )
        }
    }
}

/**
 * Section container with title and icon
 */
@Composable
private fun DeviceInfoSection(
    title: String,
    icon: ImageVector,
    content: @Composable ColumnScope.() -> Unit
) {
    Column(
        modifier = Modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Icon(
                imageVector = icon,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(16.dp)
            )
            Text(
                text = title,
                style = MaterialTheme.typography.titleSmall,
                color = MaterialTheme.colorScheme.primary,
                fontWeight = FontWeight.SemiBold
            )
        }

        Card(
            modifier = Modifier.fillMaxWidth(),
            colors = CardDefaults.cardColors(
                containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.3f)
            ),
            elevation = CardDefaults.cardElevation(defaultElevation = 0.dp)
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(12.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                content()
            }
        }
    }
}

/**
 * Individual device info row with copy functionality
 */
@Composable
private fun DeviceInfoRow(
    label: String,
    value: String,
    clipboardManager: ClipboardManager,
    maxLines: Int = 1,
    isError: Boolean = false
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(8.dp))
            .clickable {
                clipboardManager.setText(AnnotatedString(value))
                Timber.d("DeviceInfoDialog: Copied to clipboard: $label = $value")
            }
            .padding(8.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.Top
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.weight(0.4f)
        )

        Row(
            modifier = Modifier.weight(0.6f),
            horizontalArrangement = Arrangement.spacedBy(4.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = value,
                style = MaterialTheme.typography.bodySmall,
                color = if (isError) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurface,
                fontWeight = FontWeight.Medium,
                maxLines = maxLines,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f)
            )

            Icon(
                imageVector = Icons.Default.ContentCopy,
                contentDescription = "Copy $label",
                tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f),
                modifier = Modifier.size(12.dp)
            )
        }
    }
}

/**
 * Device info row with URL functionality (clickable + copy)
 */
@Composable
private fun DeviceInfoUrlRow(
    label: String,
    url: String,
    uriHandler: androidx.compose.ui.platform.UriHandler,
    clipboardManager: ClipboardManager
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.weight(0.4f)
        )

        Row(
            modifier = Modifier.weight(0.6f),
            horizontalArrangement = Arrangement.spacedBy(4.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = url,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.primary,
                fontWeight = FontWeight.Medium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier
                    .weight(1f)
                    .clickable {
                        try {
                            uriHandler.openUri(url)
                            Timber.d("DeviceInfoDialog: Opened URL: $url")
                        } catch (e: Exception) {
                            Timber.w(e, "DeviceInfoDialog: Failed to open URL: $url")
                        }
                    }
            )

            IconButton(
                onClick = {
                    try {
                        uriHandler.openUri(url)
                        Timber.d("DeviceInfoDialog: Opened URL via button: $url")
                    } catch (e: Exception) {
                        Timber.w(e, "DeviceInfoDialog: Failed to open URL via button: $url")
                    }
                },
                modifier = Modifier.size(20.dp)
            ) {
                Icon(
                    imageVector = Icons.Default.Launch,
                    contentDescription = "Open $label",
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(12.dp)
                )
            }

            IconButton(
                onClick = {
                    clipboardManager.setText(AnnotatedString(url))
                    Timber.d("DeviceInfoDialog: Copied URL to clipboard: $url")
                },
                modifier = Modifier.size(20.dp)
            ) {
                Icon(
                    imageVector = Icons.Default.ContentCopy,
                    contentDescription = "Copy $label",
                    tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f),
                    modifier = Modifier.size(12.dp)
                )
            }
        }
    }
}

/**
 * Preview for Device Info Dialog
 */
@Preview(name = "Device Info Dialog")
@Composable
private fun DeviceInfoDialogPreview() {
    FartLooperTheme {
        DeviceInfoDialog(
            device = DiscoveredDevice(
                id = "preview-device",
                name = "Living Room Sonos",
                type = DeviceType.SONOS,
                ipAddress = "192.168.1.100",
                port = 1400,
                controlUrl = "/MediaRenderer/AVTransport/Control",
                status = DeviceStatus.SUCCESS,
                metadata = mapOf(
                    "friendlyName" to "Living Room Sonos Play:1",
                    "manufacturer" to "Sonos, Inc.",
                    "manufacturerURL" to "http://www.sonos.com",
                    "modelDescription" to "Sonos PLAY:1",
                    "modelName" to "PLAY:1",
                    "modelNumber" to "S1",
                    "serialNumber" to "00-0E-58-AA-BB-CC:7",
                    "UDN" to "uuid:RINCON_000E58AABBCC01400",
                    "deviceType" to "urn:schemas-upnp-org:device:MediaRenderer:1",
                    "services" to "AVTransport:1, RenderingControl:1, ConnectionManager:1",
                    "serviceCount" to "3"
                )
            ),
            onDismiss = { }
        )
    }
}
