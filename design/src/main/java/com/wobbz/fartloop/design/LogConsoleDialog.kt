package com.wobbz.fartloop.design

import androidx.compose.animation.*
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.wobbz.fartloop.design.theme.*
import timber.log.Timber
import java.text.SimpleDateFormat
import java.util.*

/**
 * Developer console dialog for viewing application logs in real-time.
 *
 * DEVELOPMENT FINDING: Log console essential for debugging network discovery issues.
 * During testing, network timeouts and device detection failures are difficult to debug
 * without real-time log visibility. This console provides immediate insight into:
 * - SSDP/mDNS discovery packet flow
 * - UPnP SOAP command success/failure details
 * - HTTP server startup and serving issues
 * - Device compatibility problems and timing analysis
 *
 * UX FINDINGS FOR DEVELOPER TOOLS:
 * - Monospace font essential for log alignment and readability
 * - Color coding by log level improves pattern recognition
 * - Auto-scroll to bottom keeps latest logs visible
 * - Filter by log level reduces noise during debugging
 * - Copy to clipboard enables easy sharing of error details
 *
 * @param isVisible Whether the dialog is currently displayed
 * @param logs List of log entries to display in the console
 * @param onDismiss Callback when dialog should be closed
 * @param modifier Optional modifier for the dialog content
 */
@Composable
fun LogConsoleDialog(
    isVisible: Boolean,
    logs: List<LogEntry>,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier
) {
    if (isVisible) {
        Dialog(
            onDismissRequest = onDismiss,
            properties = DialogProperties(
                usePlatformDefaultWidth = false,
                dismissOnBackPress = true,
                dismissOnClickOutside = false
            )
        ) {
            LogConsoleContent(
                logs = logs,
                onDismiss = onDismiss,
                modifier = modifier.fillMaxSize()
            )
        }
    }
}

/**
 * Main content area of the log console with filtering and controls.
 *
 * DEVELOPER WORKFLOW FINDING: Log filtering dramatically improves debugging efficiency.
 * Without filtering, verbose logs from discovery and metrics create noise.
 * Filtering by ERROR/WARN level immediately highlights critical issues.
 */
@Composable
private fun LogConsoleContent(
    logs: List<LogEntry>,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier
) {
    var selectedLogLevel by remember { mutableStateOf<LogLevel?>(null) }
    var isAutoScroll by remember { mutableStateOf(true) }

    // Filter logs based on selected level
    val filteredLogs = remember(logs, selectedLogLevel) {
        if (selectedLogLevel == null) {
            logs
        } else {
            logs.filter { it.level == selectedLogLevel }
        }
    }

    Card(
        modifier = modifier.padding(16.dp),
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surface
        ),
        elevation = CardDefaults.cardElevation(defaultElevation = 8.dp)
    ) {
        Column(
            modifier = Modifier.fillMaxSize()
        ) {
            // Header with title and controls
            LogConsoleHeader(
                selectedLogLevel = selectedLogLevel,
                onLogLevelChange = {
                    selectedLogLevel = it
                    Timber.d("LogConsole filter changed to: ${it?.name ?: "ALL"}")
                },
                isAutoScroll = isAutoScroll,
                onAutoScrollChange = { isAutoScroll = it },
                logCount = filteredLogs.size,
                totalLogCount = logs.size,
                onDismiss = onDismiss
            )

            Divider()

            // Log content area
            LogConsoleList(
                logs = filteredLogs,
                isAutoScroll = isAutoScroll,
                modifier = Modifier.weight(1f)
            )

            Divider()

            // Footer with actions
            LogConsoleFooter(
                logs = filteredLogs,
                onClearLogs = {
                    // This would typically call a ViewModel method
                    Timber.d("LogConsole clear logs requested (${filteredLogs.size} entries)")
                }
            )
        }
    }
}

/**
 * Header section with title, filter controls, and close button.
 */
@Composable
private fun LogConsoleHeader(
    selectedLogLevel: LogLevel?,
    onLogLevelChange: (LogLevel?) -> Unit,
    isAutoScroll: Boolean,
    onAutoScrollChange: (Boolean) -> Unit,
    logCount: Int,
    totalLogCount: Int,
    onDismiss: () -> Unit
) {
    Column(
        modifier = Modifier.padding(16.dp)
    ) {
        // Title row
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    imageVector = Icons.Default.BugReport,
                    contentDescription = "Debug console",
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(24.dp)
                )
                Spacer(modifier = Modifier.width(8.dp))
                Text(
                    text = "Debug Console",
                    style = MaterialTheme.typography.titleLarge,
                    color = MaterialTheme.colorScheme.onSurface,
                    fontWeight = FontWeight.Bold
                )
            }

            IconButton(onClick = onDismiss) {
                Icon(
                    imageVector = Icons.Default.Close,
                    contentDescription = "Close console",
                    tint = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }

        Spacer(modifier = Modifier.height(16.dp))

        // Filter controls
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            // Log level filter chips
            Row(
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                LogLevelFilterChip(
                    level = null,
                    label = "ALL",
                    isSelected = selectedLogLevel == null,
                    onClick = { onLogLevelChange(null) }
                )

                LogLevel.values().forEach { level ->
                    LogLevelFilterChip(
                        level = level,
                        label = level.name,
                        isSelected = selectedLogLevel == level,
                        onClick = { onLogLevelChange(level) }
                    )
                }
            }

            // Auto-scroll toggle
            Row(
                verticalAlignment = Alignment.CenterVertically
            ) {
                Switch(
                    checked = isAutoScroll,
                    onCheckedChange = onAutoScrollChange,
                    modifier = Modifier.size(20.dp)
                )
                Spacer(modifier = Modifier.width(8.dp))
                Text(
                    text = "Auto-scroll",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }

        // Log count indicator
        Text(
            text = if (selectedLogLevel == null) {
                "$totalLogCount logs"
            } else {
                "$logCount of $totalLogCount logs (${selectedLogLevel.name})"
            },
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(top = 8.dp)
        )
    }
}

/**
 * Filter chip for log level selection.
 *
 * UX FINDING: Chip-based filtering more intuitive than dropdown for log levels.
 * Visual feedback with color coding helps developers quickly identify active filters.
 */
@Composable
private fun LogLevelFilterChip(
    level: LogLevel?,
    label: String,
    isSelected: Boolean,
    onClick: () -> Unit
) {
    val backgroundColor = if (isSelected) {
        level?.color ?: MaterialTheme.colorScheme.primary
    } else {
        MaterialTheme.colorScheme.surfaceVariant
    }

    val contentColor = if (isSelected) {
        Color.White
    } else {
        MaterialTheme.colorScheme.onSurfaceVariant
    }

    FilterChip(
        selected = isSelected,
        onClick = onClick,
        label = {
            Text(
                text = label,
                style = MaterialTheme.typography.labelMedium,
                color = contentColor,
                fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Normal
            )
        },
        colors = FilterChipDefaults.filterChipColors(
            selectedContainerColor = backgroundColor,
            containerColor = MaterialTheme.colorScheme.surfaceVariant
        )
    )
}

/**
 * Scrollable list of log entries with auto-scroll behavior.
 *
 * PERFORMANCE FINDING: LazyColumn essential for large log volumes.
 * Without virtualization, 1000+ log entries cause significant UI lag.
 * Auto-scroll requires careful state management to avoid scroll conflicts.
 */
@Composable
private fun LogConsoleList(
    logs: List<LogEntry>,
    isAutoScroll: Boolean,
    modifier: Modifier = Modifier
) {
    val listState = rememberLazyListState()

    // Auto-scroll to bottom when new logs arrive
    LaunchedEffect(logs.size, isAutoScroll) {
        if (isAutoScroll && logs.isNotEmpty()) {
            listState.animateScrollToItem(logs.size - 1)
        }
    }

    LazyColumn(
        state = listState,
        modifier = modifier.padding(horizontal = 16.dp),
        verticalArrangement = Arrangement.spacedBy(2.dp)
    ) {
        items(
            items = logs,
            key = { it.id }
        ) { logEntry ->
            LogEntryRow(
                entry = logEntry,
                modifier = Modifier.fillMaxWidth()
            )
        }
    }
}

/**
 * Individual log entry row with timestamp, level, and message.
 *
 * READABILITY FINDING: Monospace font critical for log alignment.
 * Without monospace, timestamps and structured log data appear misaligned.
 * Color coding by log level enables rapid visual scanning for issues.
 */
@Composable
private fun LogEntryRow(
    entry: LogEntry,
    modifier: Modifier = Modifier
) {
    Row(
        modifier = modifier
            .background(
                if (entry.level == LogLevel.ERROR) {
                    MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.1f)
                } else {
                    Color.Transparent
                },
                RoundedCornerShape(4.dp)
            )
            .padding(vertical = 2.dp, horizontal = 8.dp),
        verticalAlignment = Alignment.Top
    ) {
        // Timestamp
        Text(
            text = entry.formattedTimestamp,
            style = MaterialTheme.typography.bodySmall.copy(
                fontFamily = FontFamily.Monospace
            ),
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.width(80.dp)
        )

        Spacer(modifier = Modifier.width(8.dp))

        // Log level indicator
        Text(
            text = entry.level.shortName,
            style = MaterialTheme.typography.bodySmall.copy(
                fontFamily = FontFamily.Monospace,
                fontWeight = FontWeight.Bold
            ),
            color = entry.level.color,
            modifier = Modifier.width(24.dp)
        )

        Spacer(modifier = Modifier.width(8.dp))

        // Log message
        Text(
            text = entry.message,
            style = MaterialTheme.typography.bodySmall.copy(
                fontFamily = FontFamily.Monospace
            ),
            color = MaterialTheme.colorScheme.onSurface,
            modifier = Modifier.weight(1f)
        )
    }
}

/**
 * Footer with action buttons for log management.
 */
@Composable
private fun LogConsoleFooter(
    logs: List<LogEntry>,
    onClearLogs: () -> Unit
) {
    val clipboardManager = LocalClipboardManager.current

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(16.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        // Copy logs to clipboard
        OutlinedButton(
            onClick = {
                val logText = logs.joinToString("\n") { entry ->
                    "${entry.formattedTimestamp} ${entry.level.shortName} ${entry.message}"
                }
                clipboardManager.setText(AnnotatedString(logText))
                Timber.d("LogConsole copied ${logs.size} log entries to clipboard")
            },
            modifier = Modifier.weight(1f)
        ) {
            Icon(
                imageVector = Icons.Default.ContentCopy,
                contentDescription = "Copy logs",
                modifier = Modifier.size(16.dp)
            )
            Spacer(modifier = Modifier.width(8.dp))
            Text("Copy All")
        }

        // Clear logs
        OutlinedButton(
            onClick = onClearLogs,
            modifier = Modifier.weight(1f),
            colors = ButtonDefaults.outlinedButtonColors(
                contentColor = MaterialTheme.colorScheme.error
            )
        ) {
            Icon(
                imageVector = Icons.Default.Clear,
                contentDescription = "Clear logs",
                modifier = Modifier.size(16.dp)
            )
            Spacer(modifier = Modifier.width(8.dp))
            Text("Clear")
        }
    }
}

/**
 * Log entry data model with formatting utilities.
 *
 * DATA STRUCTURE FINDING: Timestamp formatting crucial for debugging.
 * Millisecond precision needed for network timing analysis.
 * Unique ID enables efficient LazyColumn key management.
 */
data class LogEntry(
    val id: String = UUID.randomUUID().toString(),
    val timestamp: Long = System.currentTimeMillis(),
    val level: LogLevel,
    val tag: String,
    val message: String
) {
    val formattedTimestamp: String by lazy {
        SimpleDateFormat("HH:mm:ss.SSS", Locale.getDefault())
            .format(Date(timestamp))
    }
}

/**
 * Log level enumeration with visual styling.
 *
 * COLOR STRATEGY FINDING: Log level colors must be distinct and meaningful.
 * Red for errors, yellow for warnings, blue for info, gray for debug/verbose.
 * Colors chosen for accessibility and quick recognition under various lighting.
 */
enum class LogLevel(
    val shortName: String,
    val color: Color,
    val icon: ImageVector
) {
    VERBOSE("V", Color(0xFF9E9E9E), Icons.Default.Info),
    DEBUG("D", Color(0xFF2196F3), Icons.Default.BugReport),
    INFO("I", Color(0xFF4CAF50), Icons.Default.Info),
    WARN("W", Color(0xFFFF9800), Icons.Default.Warning),
    ERROR("E", Color(0xFFF44336), Icons.Default.Error)
}

/**
 * Preview showing the log console with sample data.
 */
@Preview(name = "Log Console Dialog")
@Composable
private fun LogConsoleDialogPreview() {
    FartLooperTheme {
        val sampleLogs = listOf(
            LogEntry(
                level = LogLevel.INFO,
                tag = "HttpServer",
                message = "Starting NanoHTTPD on port 8080"
            ),
            LogEntry(
                level = LogLevel.DEBUG,
                tag = "SsdpDiscoverer",
                message = "Broadcasting M-SEARCH * HTTP/1.1"
            ),
            LogEntry(
                level = LogLevel.INFO,
                tag = "DiscoveryBus",
                message = "Found device: Living Room Sonos at 192.168.1.100:1400"
            ),
            LogEntry(
                level = LogLevel.WARN,
                tag = "UpnpClient",
                message = "SOAP timeout for Samsung TV after 5000ms"
            ),
            LogEntry(
                level = LogLevel.ERROR,
                tag = "BlastService",
                message = "Failed to set URI on device: Connection refused"
            ),
            LogEntry(
                level = LogLevel.DEBUG,
                tag = "Metrics",
                message = "Discovery completed in 2150ms, found 3 devices"
            )
        )

        LogConsoleDialog(
            isVisible = true,
            logs = sampleLogs,
            onDismiss = { }
        )
    }
}
