package com.wobbz.fartloop.feature.home

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.wobbz.fartloop.design.LogConsoleDialog
import com.wobbz.fartloop.design.LogEntry
import com.wobbz.fartloop.design.LogLevel
import com.wobbz.fartloop.design.theme.FartLooperThemePreview
import com.wobbz.fartloop.feature.home.components.*
import com.wobbz.fartloop.feature.home.model.*
import com.wobbz.fartloop.core.blast.*
import timber.log.Timber

/**
 * Main Home Screen for Fart-Looper
 *
 * Layout per PDR Section 8:
 * ```
 * HomeScreen
 *  ├─ LazyColumn(DeviceChip × n)
 *  ├─ MetricsOverlay (expandable)
 *  └─ Large FAB "BLAST"
 * ```
 *
 * Features:
 * - Scrollable device list with real-time status chips
 * - Expandable metrics HUD overlay
 * - Large BLAST FAB with stage-based animations
 * - Error state handling
 * - Loading states
 *
 * @param uiState Current UI state containing devices, metrics, and blast stage
 * @param onBlastClick Callback when BLAST FAB is clicked
 * @param onDeviceClick Callback when a device chip is clicked
 * @param onToggleMetrics Callback to toggle metrics overlay expansion
 * @param debugLogs List of debug log entries for developer console (dev builds only)
 * @param modifier Optional modifier for the screen
 */
@Composable
fun HomeScreen(
    uiState: HomeUiState,
    onBlastClick: () -> Unit,
    onStopClick: () -> Unit = {},
    onDiscoverClick: () -> Unit = {},
    onDeviceClick: (DiscoveredDevice) -> Unit = {},
    onToggleMetrics: () -> Unit,
    debugLogs: List<LogEntry> = emptyList(),
    modifier: Modifier = Modifier
) {
    // Debug console visibility state (dev builds only)
    var isLogConsoleVisible by remember { mutableStateOf(false) }

    Box(
        modifier = modifier.fillMaxSize()
    ) {
        Column(
            modifier = Modifier.fillMaxSize()
        ) {
            // Metrics overlay at top (always visible, expandable)
            MetricsOverlay(
                metrics = uiState.metrics,
                isExpanded = uiState.isMetricsExpanded,
                onToggleExpanded = onToggleMetrics,
                modifier = Modifier.padding(16.dp)
            )

            // Device list content
            HomeContent(
                uiState = uiState,
                onDeviceClick = onDeviceClick,
                onDiscoverClick = onDiscoverClick,
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f)
                    .padding(horizontal = 16.dp)
            )
        }

        // BLAST FAB with Motion transformation to bottom sheet
        // Integration Finding: BlastMotionController manages complex FAB ↔ bottom sheet transitions
        // This replaces the simple FAB with a coordinated motion system per Material Motion specs
        BlastMotionController(
            blastStage = uiState.blastStage,
            metrics = uiState.metrics,
            devices = uiState.devices,
            onStartBlast = onBlastClick,
            onStopBlast = onStopClick,
            onDiscoverDevices = onDiscoverClick,
            modifier = Modifier.align(Alignment.BottomEnd)
        )

        // Debug console FAB (dev builds only) - positioned at top-start for accessibility
        // DEVELOPER ACCESS FINDING: Debug console needs easy access but shouldn't interfere with main UI
        // Positioning at top-start prevents conflicts with BLAST FAB and metrics overlay
        if (debugLogs.isNotEmpty()) {
            FloatingActionButton(
                onClick = {
                    isLogConsoleVisible = true
                    Timber.d("HomeScreen opening log console with ${debugLogs.size} entries")
                },
                modifier = Modifier
                    .align(Alignment.TopStart)
                    .padding(16.dp)
                    .size(48.dp),
                containerColor = MaterialTheme.colorScheme.secondary,
                contentColor = MaterialTheme.colorScheme.onSecondary
            ) {
                Icon(
                    imageVector = Icons.Default.BugReport,
                    contentDescription = "Open debug console",
                    modifier = Modifier.size(24.dp)
                )
            }
        }

        // Error snackbar (if present)
        uiState.errorMessage?.let { error ->
            LaunchedEffect(error) {
                Timber.e("HomeScreen error: $error")
                // TODO: Show snackbar with error message
                // For now, just log the error
            }
        }
    }

    // Debug console dialog (dev builds only)
    // INTEGRATION FINDING: Dialog positioning outside main Box prevents z-index conflicts
    // Full-screen dialog with proper backdrop ensures logs are clearly visible
    LogConsoleDialog(
        isVisible = isLogConsoleVisible,
        logs = debugLogs,
        onDismiss = {
            isLogConsoleVisible = false
            Timber.d("HomeScreen log console dismissed")
        }
    )
}

/**
 * Main content area showing device list or empty/loading states
 */
@Composable
private fun HomeContent(
    uiState: HomeUiState,
    onDeviceClick: (DiscoveredDevice) -> Unit,
    onDiscoverClick: () -> Unit = {},
    modifier: Modifier = Modifier
) {
    when {
        // Loading state or blast starting
        uiState.isLoading || uiState.blastStage == BlastStage.HTTP_STARTING -> {
            LoadingContent(
                message = when (uiState.blastStage) {
                    BlastStage.HTTP_STARTING -> "Starting HTTP server..."
                    else -> "Loading..."
                },
                modifier = modifier
            )
        }

        // Empty state (no devices found)
        !uiState.hasDevices && !uiState.isBlastActive -> {
            EmptyDeviceList(
                onDiscoverClick = onDiscoverClick,
                modifier = modifier
            )
        }

        // Device list
        else -> {
            DeviceList(
                devices = uiState.devices,
                onDeviceClick = onDeviceClick,
                onDiscoverClick = onDiscoverClick,
                modifier = modifier
            )
        }
    }
}

/**
 * Scrollable list of discovered devices
 */
@Composable
private fun DeviceList(
    devices: List<DiscoveredDevice>,
    onDeviceClick: (DiscoveredDevice) -> Unit,
    onDiscoverClick: () -> Unit = {},
    modifier: Modifier = Modifier
) {
    LazyColumn(
        modifier = modifier,
        verticalArrangement = Arrangement.spacedBy(8.dp),
        contentPadding = PaddingValues(vertical = 8.dp)
    ) {
        // Section header with refresh button
        item {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 8.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = "Discovered Devices (${devices.size})",
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.onSurface,
                )

                IconButton(
                    onClick = onDiscoverClick,
                    modifier = Modifier.size(32.dp)
                ) {
                    Icon(
                        imageVector = Icons.Default.Refresh,
                        contentDescription = "Refresh devices",
                        modifier = Modifier.size(20.dp),
                        tint = MaterialTheme.colorScheme.primary
                    )
                }
            }
        }

        // Device chips
        items(
            items = devices,
            key = { device -> device.id }
        ) { device ->
            DeviceChip(
                device = device,
                onClick = onDeviceClick,
                modifier = Modifier.fillMaxWidth()
            )
        }

        // Bottom spacing for FAB
        item {
            Spacer(modifier = Modifier.height(80.dp))
        }
    }
}

/**
 * Empty state when no devices are discovered
 */
@Composable
private fun EmptyDeviceList(
    onDiscoverClick: () -> Unit = {},
    modifier: Modifier = Modifier
) {
    Column(
        modifier = modifier,
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Icon(
            imageVector = Icons.Default.Search,
            contentDescription = "No devices found",
            modifier = Modifier.size(64.dp),
            tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f)
        )

        Spacer(modifier = Modifier.height(16.dp))

        Text(
            text = "No devices found",
            style = MaterialTheme.typography.headlineSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )

        Spacer(modifier = Modifier.height(8.dp))

        Text(
            text = "Discover UPnP, Chromecast,\nand other network audio devices",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.8f),
            textAlign = androidx.compose.ui.text.style.TextAlign.Center
        )

        Spacer(modifier = Modifier.height(24.dp))

        Button(
            onClick = onDiscoverClick,
            colors = ButtonDefaults.buttonColors(
                containerColor = MaterialTheme.colorScheme.secondary
            )
        ) {
            Icon(
                imageVector = Icons.Default.Search,
                contentDescription = "Discover devices",
                modifier = Modifier.size(18.dp)
            )
            Spacer(modifier = Modifier.width(8.dp))
            Text("Discover Devices")
        }
    }
}

/**
 * Loading state indicator
 */
@Composable
private fun LoadingContent(
    message: String = "Loading...",
    modifier: Modifier = Modifier
) {
    Column(
        modifier = modifier,
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        CircularProgressIndicator(
            modifier = Modifier.size(48.dp),
            color = MaterialTheme.colorScheme.primary
        )

        Spacer(modifier = Modifier.height(16.dp))

        Text(
            text = message,
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

/**
 * Preview for HomeScreen in different states
 */
@Preview(name = "Home Screen - With Devices")
@Composable
private fun HomeScreenWithDevicesPreview() {
    FartLooperThemePreview {
        HomeScreen(
            uiState = HomeUiState(
                devices = listOf(
                    DiscoveredDevice(
                        id = "1",
                        name = "Living Room Sonos",
                        type = DeviceType.SONOS,
                        ipAddress = "192.168.1.100",
                        port = 1400,
                        status = DeviceStatus.SUCCESS
                    ),
                    DiscoveredDevice(
                        id = "2",
                        name = "Kitchen Chromecast",
                        type = DeviceType.CHROMECAST,
                        ipAddress = "192.168.1.101",
                        port = 8008,
                        status = DeviceStatus.CONNECTING
                    ),
                    DiscoveredDevice(
                        id = "3",
                        name = "Samsung TV",
                        type = DeviceType.SAMSUNG,
                        ipAddress = "192.168.1.102",
                        port = 8001,
                        status = DeviceStatus.FAILED
                    )
                ),
                metrics = BlastMetrics(
                    httpStartupMs = 35L,
                    discoveryTimeMs = 2150L,
                    totalDevicesFound = 3,
                    connectionsAttempted = 3,
                    successfulBlasts = 2,
                    failedBlasts = 1,
                    averageLatencyMs = 180L,
                    isRunning = false
                ),
                blastStage = BlastStage.COMPLETED,
                isMetricsExpanded = false
            ),
            onBlastClick = { },
            onStopClick = { },
            onDiscoverClick = { },
            onToggleMetrics = { },
            debugLogs = listOf(
                LogEntry(
                    level = LogLevel.INFO,
                    tag = "HomeScreen",
                    message = "Starting debug session with sample data"
                ),
                LogEntry(
                    level = LogLevel.DEBUG,
                    tag = "DiscoveryBus",
                    message = "SSDP discovery completed, found 3 devices"
                ),
                LogEntry(
                    level = LogLevel.WARN,
                    tag = "BlastService",
                    message = "Device timeout after 5000ms: Samsung TV"
                )
            )
        )
    }
}

@Preview(name = "Home Screen - Empty")
@Composable
private fun HomeScreenEmptyPreview() {
    FartLooperThemePreview {
        HomeScreen(
            uiState = HomeUiState(
                devices = emptyList(),
                metrics = BlastMetrics(),
                blastStage = BlastStage.IDLE,
                isMetricsExpanded = false
            ),
            onBlastClick = { },
            onStopClick = { },
            onDiscoverClick = { },
            onToggleMetrics = { }
        )
    }
}

@Preview(name = "Home Screen - Blasting")
@Composable
private fun HomeScreenBlastingPreview() {
    FartLooperThemePreview {
        HomeScreen(
            uiState = HomeUiState(
                devices = listOf(
                    DiscoveredDevice(
                        id = "1",
                        name = "Living Room Sonos",
                        type = DeviceType.SONOS,
                        ipAddress = "192.168.1.100",
                        port = 1400,
                        status = DeviceStatus.BLASTING
                    )
                ),
                metrics = BlastMetrics(
                    httpStartupMs = 42L,
                    discoveryTimeMs = 1890L,
                    totalDevicesFound = 1,
                    connectionsAttempted = 1,
                    successfulBlasts = 0,
                    failedBlasts = 0,
                    averageLatencyMs = 0L,
                    isRunning = true
                ),
                blastStage = BlastStage.BLASTING,
                isMetricsExpanded = true
            ),
            onBlastClick = { },
            onStopClick = { },
            onDiscoverClick = { },
            onToggleMetrics = { }
        )
    }
}
