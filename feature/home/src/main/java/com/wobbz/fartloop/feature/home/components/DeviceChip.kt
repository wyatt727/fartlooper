package com.wobbz.fartloop.feature.home.components

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.wobbz.fartloop.design.theme.*
import com.wobbz.fartloop.feature.home.model.*
import timber.log.Timber

/**
 * Individual device chip showing real-time connection status
 * Animates colors based on device status per DeviceChipColors in design system
 *
 * Features:
 * - Color-coded status indicators (green=success, red=failed, etc.)
 * - Device type icons (Chromecast, Sonos, etc.)
 * - Animated transitions between states
 * - Click handling for device details
 *
 * @param device The discovered device to display
 * @param onClick Callback when chip is tapped
 * @param modifier Optional modifier for the chip
 */
@Composable
fun DeviceChip(
    device: DiscoveredDevice,
    onClick: (DiscoveredDevice) -> Unit = {},
    modifier: Modifier = Modifier
) {
    // Animate color transitions based on device status
    val chipColor by animateColorAsState(
        targetValue = when (device.status) {
            DeviceStatus.DISCOVERED -> DeviceChipColors.Discovered
            DeviceStatus.CONNECTING -> DeviceChipColors.Connecting
            DeviceStatus.CONNECTED -> DeviceChipColors.Connected
            DeviceStatus.BLASTING -> DeviceChipColors.Connected
            DeviceStatus.SUCCESS -> DeviceChipColors.Connected
            DeviceStatus.FAILED -> DeviceChipColors.Failed
            DeviceStatus.IDLE -> DeviceChipColors.Idle
        },
        animationSpec = tween(durationMillis = 300),
        label = "ChipColor"
    )

    // Animate chip scale for "pulse" effect during active states
    val chipScale by animateFloatAsState(
        targetValue = if (device.status in listOf(DeviceStatus.CONNECTING, DeviceStatus.BLASTING)) 1.05f else 1.0f,
        animationSpec = tween(durationMillis = 200),
        label = "ChipScale"
    )

    // Animate alpha for failed devices
    val chipAlpha by animateFloatAsState(
        targetValue = if (device.status == DeviceStatus.FAILED) 0.6f else 1.0f,
        animationSpec = tween(durationMillis = 300),
        label = "ChipAlpha"
    )

    Card(
        modifier = modifier
            .scale(chipScale)
            .alpha(chipAlpha)
            .clickable {
                onClick(device)
                Timber.d("DeviceChip clicked: ${device.name} (${device.status})")
            },
        shape = FartLooperCustomShapes.deviceChip,
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surface
        ),
        elevation = CardDefaults.cardElevation(
            defaultElevation = if (device.status == DeviceStatus.SUCCESS) 4.dp else 2.dp
        )
    ) {
        Row(
            modifier = Modifier
                .padding(horizontal = 12.dp, vertical = 8.dp)
                .fillMaxWidth(),
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
                    tint = chipColor,
                    modifier = Modifier.size(20.dp)
                )

                Spacer(modifier = Modifier.width(8.dp))

                // Device name and IP
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = device.name,
                        style = FartLooperTextStyles.deviceChip,
                        color = MaterialTheme.colorScheme.onSurface,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    Text(
                        text = "${device.ipAddress}:${device.port}",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
            }

            // Status indicator
            Box(
                modifier = Modifier
                    .size(8.dp)
                    .background(
                        color = chipColor,
                        shape = RoundedCornerShape(4.dp)
                    )
            )
        }
    }

    // Log status changes for debugging
    LaunchedEffect(device.status) {
        Timber.d("Device ${device.name} status changed to ${device.status}")
    }
}

/**
 * Get appropriate icon for device type
 */
@Composable
private fun getDeviceIcon(type: DeviceType): ImageVector {
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
 * Preview for DeviceChip in different states
 */
@Preview(name = "Device Chip States")
@Composable
private fun DeviceChipPreview() {
    FartLooperThemePreview {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            DeviceChip(
                device = DiscoveredDevice(
                    id = "1",
                    name = "Living Room Sonos",
                    type = DeviceType.SONOS,
                    ipAddress = "192.168.1.100",
                    port = 1400,
                    status = DeviceStatus.DISCOVERED
                )
            )

            DeviceChip(
                device = DiscoveredDevice(
                    id = "2",
                    name = "Kitchen Chromecast",
                    type = DeviceType.CHROMECAST,
                    ipAddress = "192.168.1.101",
                    port = 8008,
                    status = DeviceStatus.CONNECTING
                )
            )

            DeviceChip(
                device = DiscoveredDevice(
                    id = "3",
                    name = "Samsung TV",
                    type = DeviceType.SAMSUNG,
                    ipAddress = "192.168.1.102",
                    port = 8001,
                    status = DeviceStatus.SUCCESS
                )
            )

            DeviceChip(
                device = DiscoveredDevice(
                    id = "4",
                    name = "Unknown Device",
                    type = DeviceType.UNKNOWN,
                    ipAddress = "192.168.1.103",
                    port = 8080,
                    status = DeviceStatus.FAILED
                )
            )
        }
    }
}
