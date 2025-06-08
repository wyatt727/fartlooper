package com.wobbz.fartloop.feature.home.components

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.wobbz.fartloop.core.blast.*
import com.wobbz.fartloop.design.theme.*
import timber.log.Timber

/**
 * Individual device control dropdown that appears when a device chip is clicked.
 *
 * Features:
 * - Expandable dropdown showing device details
 * - Individual "Blast to Device" button
 * - Device Info dialog with comprehensive XML-parsed details
 * - Status indicator and device type icon
 * - Animated expand/collapse transitions
 *
 * UI Architecture Finding: Dropdown pattern provides contextual actions
 * without cluttering the main device list interface. Users can quickly
 * access individual device controls when needed.
 *
 * @param device The discovered device to control
 * @param isExpanded Whether the dropdown is currently expanded
 * @param onToggleExpanded Callback to toggle dropdown visibility
 * @param onBlastToDevice Callback when "Blast to Device" is clicked
 * @param modifier Optional modifier for the component
 */
@Composable
fun DeviceDropdownMenu(
    device: DiscoveredDevice,
    isExpanded: Boolean,
    onToggleExpanded: () -> Unit,
    onBlastToDevice: (DiscoveredDevice) -> Unit,
    modifier: Modifier = Modifier
) {
    // State for device info dialog
    var showDeviceInfoDialog by remember { mutableStateOf(false) }
    // Animate dropdown arrow rotation
    val arrowRotation by animateFloatAsState(
        targetValue = if (isExpanded) 180f else 0f,
        label = "DropdownArrowRotation"
    )

    Card(
        modifier = modifier,
        shape = FartLooperCustomShapes.deviceChip,
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surface
        ),
        elevation = CardDefaults.cardElevation(
            defaultElevation = if (isExpanded) 6.dp else 2.dp
        )
    ) {
        Column {
            // Main device chip (always visible)
            DeviceChipHeader(
                device = device,
                isExpanded = isExpanded,
                arrowRotation = arrowRotation,
                onToggleExpanded = onToggleExpanded
            )

            // Expandable dropdown content
            if (isExpanded) {
                DeviceDropdownContent(
                    device = device,
                    onBlastToDevice = onBlastToDevice,
                    onShowDeviceInfo = { showDeviceInfoDialog = true }
                )
            }
        }
    }

    // Device Info Dialog
    if (showDeviceInfoDialog) {
        DeviceInfoDialog(
            device = device,
            onDismiss = { showDeviceInfoDialog = false }
        )
    }
}

/**
 * Main device chip header that's always visible
 */
@Composable
private fun DeviceChipHeader(
    device: DiscoveredDevice,
    isExpanded: Boolean,
    arrowRotation: Float,
    onToggleExpanded: () -> Unit
) {
    // Get device status color for UI indicators
    val statusColor = when (device.status) {
        DeviceStatus.DISCOVERED -> DeviceChipColors.Discovered
        DeviceStatus.CONNECTING -> DeviceChipColors.Connecting
        DeviceStatus.CONNECTED -> DeviceChipColors.Connected
        DeviceStatus.BLASTING -> DeviceChipColors.Connected
        DeviceStatus.SUCCESS -> DeviceChipColors.Connected
        DeviceStatus.FAILED -> DeviceChipColors.Failed
        DeviceStatus.IDLE -> DeviceChipColors.Idle
    }

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable { onToggleExpanded() }
            .padding(horizontal = 12.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        // Device info section
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.weight(1f)
        ) {
            // Device type icon
            Icon(
                imageVector = getDeviceIcon(device.type),
                contentDescription = "Device type: ${device.type}",
                tint = statusColor,
                modifier = Modifier.size(20.dp)
            )

            Spacer(modifier = Modifier.width(8.dp))

            // Device name and IP
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = device.name,
                    style = FartLooperTextStyles.deviceChip,
                    color = MaterialTheme.colorScheme.onSurface,
                    maxLines = 1
                )
                Text(
                    text = "${device.ipAddress}:${device.port}",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1
                )
            }
        }

        // Status and expand controls
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            // Status indicator dot
            Box(
                modifier = Modifier
                    .size(8.dp)
                    .background(
                        color = statusColor,
                        shape = RoundedCornerShape(4.dp)
                    )
            )

            // Dropdown arrow
            Icon(
                imageVector = Icons.Default.ExpandMore,
                contentDescription = if (isExpanded) "Collapse device controls" else "Expand device controls",
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier
                    .size(20.dp)
                    .rotate(arrowRotation)
            )
        }
    }
}

/**
 * Expandable dropdown content with device controls
 */
@Composable
private fun DeviceDropdownContent(
    device: DiscoveredDevice,
    onBlastToDevice: (DiscoveredDevice) -> Unit,
    onShowDeviceInfo: () -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp)
            .padding(bottom = 12.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        // Divider line
        Divider(
            color = MaterialTheme.colorScheme.outlineVariant,
            thickness = 1.dp,
            modifier = Modifier.padding(vertical = 4.dp)
        )

        // Device details
        DeviceDetailsSection(device = device)

        // Action buttons
        DeviceActionButtons(
            device = device,
            onBlastToDevice = onBlastToDevice,
            onShowDeviceInfo = onShowDeviceInfo
        )
    }
}

/**
 * Device details section showing additional info
 */
@Composable
private fun DeviceDetailsSection(device: DiscoveredDevice) {
    Column(
        verticalArrangement = Arrangement.spacedBy(4.dp)
    ) {
        Text(
            text = "Device Details",
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            fontWeight = FontWeight.SemiBold
        )

        DeviceDetailRow(
            label = "Type",
            value = device.type.name.lowercase().replaceFirstChar { it.uppercase() }
        )

        DeviceDetailRow(
            label = "Status",
            value = device.status.name.lowercase().replaceFirstChar { it.uppercase() }
        )

        DeviceDetailRow(
            label = "Address",
            value = "${device.ipAddress}:${device.port}"
        )
    }
}

/**
 * Individual device detail row
 */
@Composable
private fun DeviceDetailRow(
    label: String,
    value: String
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Text(
            text = value,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurface,
            fontWeight = FontWeight.Medium
        )
    }
}

/**
 * Action buttons for device control
 */
@Composable
private fun DeviceActionButtons(
    device: DiscoveredDevice,
    onBlastToDevice: (DiscoveredDevice) -> Unit,
    onShowDeviceInfo: () -> Unit
) {
    Column(
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        // Main blast button
        Button(
            onClick = {
                onBlastToDevice(device)
                Timber.d("DeviceDropdownMenu: Blast to device clicked for ${device.name}")
            },
            modifier = Modifier.fillMaxWidth(),
            colors = ButtonDefaults.buttonColors(
                containerColor = MaterialTheme.colorScheme.primary
            ),
            enabled = device.status != DeviceStatus.CONNECTING && device.status != DeviceStatus.BLASTING
        ) {
            Icon(
                imageVector = Icons.Default.PlayArrow,
                contentDescription = "Blast to device",
                modifier = Modifier.size(18.dp)
            )
            Spacer(modifier = Modifier.width(8.dp))
            Text("Blast to ${device.name}")
        }

        // Device info button - shows comprehensive device details
        OutlinedButton(
            onClick = {
                Timber.d("DeviceDropdownMenu: Device info clicked for ${device.name}")
                onShowDeviceInfo()
            },
            modifier = Modifier.fillMaxWidth()
        ) {
            Icon(
                imageVector = Icons.Default.Info,
                contentDescription = "Device information",
                modifier = Modifier.size(16.dp)
            )
            Spacer(modifier = Modifier.width(8.dp))
            Text("Device Info")
        }
    }
}

/**
 * Get appropriate icon for device type (reused from DeviceChip)
 */
@Composable
private fun getDeviceIcon(type: DeviceType): androidx.compose.ui.graphics.vector.ImageVector {
    return when (type) {
        DeviceType.CHROMECAST -> Icons.Default.CastConnected
        DeviceType.SONOS -> Icons.Default.VolumeUp
        DeviceType.AIRPLAY -> Icons.Default.Wifi
        DeviceType.SAMSUNG -> Icons.Default.Tv
        DeviceType.UPNP -> Icons.Default.Router
        DeviceType.UNKNOWN -> Icons.Default.QuestionMark
    }
}

/**
 * Preview for DeviceDropdownMenu in different states
 */
@Preview(name = "Device Dropdown - Collapsed")
@Composable
private fun DeviceDropdownMenuCollapsedPreview() {
    FartLooperThemePreview {
        DeviceDropdownMenu(
            device = DiscoveredDevice(
                id = "1",
                name = "Living Room Sonos",
                type = DeviceType.SONOS,
                ipAddress = "192.168.1.100",
                port = 1400,
                controlUrl = "/MediaRenderer/AVTransport/Control",
                status = DeviceStatus.DISCOVERED
            ),
            isExpanded = false,
            onToggleExpanded = { },
            onBlastToDevice = { },
            modifier = Modifier.padding(16.dp)
        )
    }
}

@Preview(name = "Device Dropdown - Expanded")
@Composable
private fun DeviceDropdownMenuExpandedPreview() {
    FartLooperThemePreview {
        DeviceDropdownMenu(
            device = DiscoveredDevice(
                id = "2",
                name = "Kitchen Chromecast",
                type = DeviceType.CHROMECAST,
                ipAddress = "192.168.1.101",
                port = 8008,
                controlUrl = "/setup/eureka_info",
                status = DeviceStatus.SUCCESS
            ),
            isExpanded = true,
            onToggleExpanded = { },
            onBlastToDevice = { },
            modifier = Modifier.padding(16.dp)
        )
    }
}
