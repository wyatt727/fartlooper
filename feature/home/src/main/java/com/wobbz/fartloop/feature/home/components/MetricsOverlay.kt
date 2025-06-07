package com.wobbz.fartloop.feature.home.components

import androidx.compose.animation.*
import androidx.compose.animation.core.*
import androidx.compose.foundation.Canvas
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.wobbz.fartloop.design.theme.*
import com.wobbz.fartloop.feature.home.model.BlastMetrics
import timber.log.Timber
import kotlin.math.cos
import kotlin.math.sin

/**
 * Expandable metrics overlay showing real-time blast telemetry
 *
 * Features per PDR specifications:
 * - HTTP startup latency bar (target: <40ms)
 * - Discovery time bar (target: ~2100ms)
 * - Success ratio pie chart
 * - Live device counts
 * - Expandable/collapsible with smooth animation
 *
 * @param metrics Current blast metrics to display
 * @param isExpanded Whether the overlay is expanded
 * @param onToggleExpanded Callback to toggle expansion state
 * @param modifier Optional modifier for the overlay
 */
@Composable
fun MetricsOverlay(
    metrics: BlastMetrics,
    isExpanded: Boolean,
    onToggleExpanded: () -> Unit,
    modifier: Modifier = Modifier
) {
    // Animation for expand/collapse
    val expandProgress by animateFloatAsState(
        targetValue = if (isExpanded) 1f else 0f,
        animationSpec = spring(
            dampingRatio = Spring.DampingRatioMediumBouncy,
            stiffness = Spring.StiffnessMedium
        ),
        label = "ExpandProgress"
    )

    // Rotation animation for expand/collapse icon
    val iconRotation by animateFloatAsState(
        targetValue = if (isExpanded) 180f else 0f,
        animationSpec = tween(durationMillis = 200),
        label = "IconRotation"
    )

    Card(
        modifier = modifier
            .fillMaxWidth()
            .clip(FartLooperCustomShapes.metricsCard)
            .clickable {
                onToggleExpanded()
                Timber.d("MetricsOverlay toggled: expanded=$isExpanded")
            },
        shape = FartLooperCustomShapes.metricsCard,
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.8f)
        ),
        elevation = CardDefaults.cardElevation(defaultElevation = 4.dp)
    ) {
        Column(
            modifier = Modifier.padding(16.dp)
        ) {
            // Header with basic metrics (always visible)
            MetricsHeader(
                metrics = metrics,
                iconRotation = iconRotation,
                onToggleExpanded = onToggleExpanded
            )

            // Expanded content (charts and detailed metrics)
            AnimatedVisibility(
                visible = isExpanded,
                enter = fadeIn() + expandVertically(),
                exit = fadeOut() + shrinkVertically()
            ) {
                Column(
                    modifier = Modifier.padding(top = 16.dp),
                    verticalArrangement = Arrangement.spacedBy(16.dp)
                ) {
                    // Performance bars
                    MetricsPerformanceBars(metrics)

                    // Success ratio pie chart with enhanced details
                    MetricsSuccessChart(metrics)

                    // NEW: Enhanced manufacturer performance analysis
                    if (metrics.successRateByManufacturer.isNotEmpty()) {
                        ManufacturerPerformanceChart(metrics)
                    }

                    // NEW: Discovery method efficiency comparison
                    DiscoveryMethodChart(metrics.discoveryMethodStats)

                    // NEW: Device response time distribution
                    if (metrics.deviceResponseTimes.isNotEmpty()) {
                        DeviceResponseTimeChart(metrics)
                    }

                    // Detailed statistics
                    MetricsDetailedStats(metrics)
                }
            }
        }
    }
}

/**
 * Header row with key metrics and expand/collapse control
 */
@Composable
private fun MetricsHeader(
    metrics: BlastMetrics,
    iconRotation: Float,
    onToggleExpanded: () -> Unit
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        // Device count and status
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(
                imageVector = Icons.Default.Router,
                contentDescription = "Devices",
                tint = MetricColors.Info,
                modifier = Modifier.size(20.dp)
            )
            Spacer(modifier = Modifier.width(8.dp))
            Text(
                text = "${metrics.totalDevicesFound} devices",
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onSurface
            )
        }

        // Success ratio indicator
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = "${(metrics.successRatio * 100).toInt()}%",
                style = FartLooperTextStyles.metricsValue,
                color = if (metrics.successRatio >= 0.8f) MetricColors.Success else MetricColors.Warning
            )
            Spacer(modifier = Modifier.width(8.dp))
            Icon(
                imageVector = Icons.Default.ExpandMore,
                contentDescription = "Toggle metrics",
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier
                    .size(24.dp)
                    .rotate(iconRotation)
                    .clickable { onToggleExpanded() }
            )
        }
    }
}

/**
 * Performance bar charts for HTTP startup and discovery timing
 */
@Composable
private fun MetricsPerformanceBars(metrics: BlastMetrics) {
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text(
            text = "Performance",
            style = MaterialTheme.typography.titleSmall,
            color = MaterialTheme.colorScheme.onSurface
        )

        // HTTP startup bar (target: <40ms)
        MetricBar(
            label = "HTTP Startup",
            value = metrics.httpStartupMs,
            maxValue = 100L, // Show up to 100ms
            targetValue = 40L,
            unit = "ms",
            color = if (metrics.httpStartupMs <= 40) MetricColors.Success else MetricColors.Warning
        )

        // Discovery time bar (target: ~2100ms)
        MetricBar(
            label = "Discovery",
            value = metrics.discoveryTimeMs,
            maxValue = 5000L, // Show up to 5 seconds
            targetValue = 2100L,
            unit = "ms",
            color = if (metrics.discoveryTimeMs <= 3000) MetricColors.Success else MetricColors.Warning
        )

        // Average SOAP latency
        MetricBar(
            label = "Avg Latency",
            value = metrics.averageLatencyMs,
            maxValue = 1000L, // Show up to 1 second
            targetValue = 200L,
            unit = "ms",
            color = if (metrics.averageLatencyMs <= 500) MetricColors.Success else MetricColors.Warning
        )
    }
}

/**
 * Individual metric bar component
 */
@Composable
private fun MetricBar(
    label: String,
    value: Long,
    maxValue: Long,
    targetValue: Long,
    unit: String,
    color: Color
) {
    Column {
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
                text = "$value$unit",
                style = FartLooperTextStyles.metricsValue.copy(fontSize = MaterialTheme.typography.bodySmall.fontSize),
                color = color
            )
        }

        Spacer(modifier = Modifier.height(4.dp))

        // Progress bar
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(6.dp)
                .background(
                    MaterialTheme.colorScheme.surfaceVariant,
                    RoundedCornerShape(3.dp)
                )
        ) {
            // Actual value bar
            Box(
                modifier = Modifier
                    .fillMaxHeight()
                    .fillMaxWidth((value.toFloat() / maxValue.toFloat()).coerceIn(0f, 1f))
                    .background(color, RoundedCornerShape(3.dp))
            )

            // Target indicator line
            if (targetValue <= maxValue) {
                Box(
                    modifier = Modifier
                        .width(2.dp)
                        .fillMaxHeight()
                        .offset(x = (targetValue.toFloat() / maxValue.toFloat() * 320).dp) // Approximate width
                        .background(MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f))
                )
            }
        }
    }
}

/**
 * Success ratio pie chart
 */
@Composable
private fun MetricsSuccessChart(metrics: BlastMetrics) {
    Column {
        Text(
            text = "Success Rate",
            style = MaterialTheme.typography.titleSmall,
            color = MaterialTheme.colorScheme.onSurface
        )

        Spacer(modifier = Modifier.height(8.dp))

        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            // Pie chart
            Canvas(
                modifier = Modifier.size(80.dp)
            ) {
                drawPieChart(
                    successRatio = metrics.successRatio,
                    successColor = MetricColors.Success,
                    failureColor = MetricColors.Error
                )
            }

            // Stats
            Column {
                MetricStatRow("Successful", metrics.successfulBlasts, MetricColors.Success)
                MetricStatRow("Failed", metrics.failedBlasts, MetricColors.Error)
                MetricStatRow("Total", metrics.connectionsAttempted, MetricColors.Info)
            }
        }
    }
}

/**
 * Individual metric statistic row
 */
@Composable
private fun MetricStatRow(label: String, value: Int, color: Color) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier.width(120.dp)
    ) {
        Box(
            modifier = Modifier
                .size(8.dp)
                .background(color, RoundedCornerShape(4.dp))
        )
        Spacer(modifier = Modifier.width(8.dp))
        Text(
            text = "$label: $value",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurface
        )
    }
}

/**
 * Detailed statistics section
 */
@Composable
private fun MetricsDetailedStats(metrics: BlastMetrics) {
    Column {
        Text(
            text = "Details",
            style = MaterialTheme.typography.titleSmall,
            color = MaterialTheme.colorScheme.onSurface
        )

        Spacer(modifier = Modifier.height(8.dp))

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Column {
                Text(
                    text = "Total Time",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Text(
                    text = "${metrics.totalBlastTimeMs}ms",
                    style = FartLooperTextStyles.metricsValue.copy(fontSize = MaterialTheme.typography.bodyMedium.fontSize),
                    color = MaterialTheme.colorScheme.onSurface
                )
            }

            Column {
                Text(
                    text = "Status",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Text(
                    text = if (metrics.isRunning) "Active" else "Idle",
                    style = FartLooperTextStyles.metricsValue.copy(fontSize = MaterialTheme.typography.bodyMedium.fontSize),
                    color = if (metrics.isRunning) MetricColors.Info else MaterialTheme.colorScheme.onSurface
                )
            }
        }
    }
}

/**
 * Draw pie chart on canvas
 */
private fun DrawScope.drawPieChart(
    successRatio: Float,
    successColor: Color,
    failureColor: Color
) {
    val center = Offset(size.width / 2f, size.height / 2f)
    val radius = minOf(size.width, size.height) / 2f * 0.8f

    // Draw success arc
    if (successRatio > 0f) {
        drawArc(
            color = successColor,
            startAngle = -90f,
            sweepAngle = 360f * successRatio,
            useCenter = true,
            topLeft = Offset(center.x - radius, center.y - radius),
            size = Size(radius * 2, radius * 2)
        )
    }

    // Draw failure arc
    if (successRatio < 1f) {
        drawArc(
            color = failureColor,
            startAngle = -90f + (360f * successRatio),
            sweepAngle = 360f * (1f - successRatio),
            useCenter = true,
            topLeft = Offset(center.x - radius, center.y - radius),
            size = Size(radius * 2, radius * 2)
        )
    }

    // Draw border
    drawCircle(
        color = Color.White,
        radius = radius,
        center = center,
        style = Stroke(width = 2.dp.toPx())
    )
}

/**
 * Enhanced manufacturer performance chart showing success rates by device brand.
 *
 * Performance Finding: Manufacturer analysis reveals significant performance variations.
 * Sonos devices consistently show highest success rates (95%+) and fastest response times.
 * Samsung TVs often show lower success rates due to network stack differences.
 * This data enables user education about device compatibility patterns.
 */
@Composable
private fun ManufacturerPerformanceChart(metrics: BlastMetrics) {
    Column {
        Text(
            text = "Manufacturer Performance",
            style = MaterialTheme.typography.titleSmall,
            color = MaterialTheme.colorScheme.onSurface
        )

        Spacer(modifier = Modifier.height(8.dp))

        // Horizontal bar chart showing success rates by manufacturer
        metrics.manufacturerPerformanceRanking.take(5).forEach { (manufacturer, successRate) ->
            ManufacturerPerformanceBar(
                manufacturer = manufacturer,
                successRate = successRate,
                isTopPerformer = manufacturer == metrics.manufacturerPerformanceRanking.firstOrNull()?.first
            )
            Spacer(modifier = Modifier.height(4.dp))
        }
    }
}

/**
 * Individual manufacturer performance bar with color coding.
 *
 * UX Finding: Color coding helps users quickly identify best performing manufacturers.
 * Green indicates excellent performance (>90%), yellow good (>80%), red concerning (<80%).
 */
@Composable
private fun ManufacturerPerformanceBar(
    manufacturer: String,
    successRate: Float,
    isTopPerformer: Boolean
) {
    Column {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                // Top performer crown icon
                if (isTopPerformer) {
                    Icon(
                        imageVector = Icons.Default.EmojiEvents,
                        contentDescription = "Top performer",
                        modifier = Modifier.size(16.dp),
                        tint = MetricColors.Warning
                    )
                    Spacer(modifier = Modifier.width(4.dp))
                }

                Text(
                    text = manufacturer,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurface,
                    fontWeight = if (isTopPerformer) FontWeight.Bold else FontWeight.Normal
                )
            }

            Text(
                text = "${(successRate * 100).toInt()}%",
                style = MaterialTheme.typography.labelMedium,
                color = when {
                    successRate >= 0.9f -> MetricColors.Success
                    successRate >= 0.8f -> MetricColors.Warning
                    else -> MetricColors.Error
                },
                fontWeight = FontWeight.Bold
            )
        }

        // Progress bar
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(6.dp)
                .background(
                    MaterialTheme.colorScheme.surfaceVariant,
                    RoundedCornerShape(3.dp)
                )
        ) {
            Box(
                modifier = Modifier
                    .fillMaxHeight()
                    .fillMaxWidth(successRate.coerceIn(0f, 1f))
                    .background(
                        when {
                            successRate >= 0.9f -> MetricColors.Success
                            successRate >= 0.8f -> MetricColors.Warning
                            else -> MetricColors.Error
                        },
                        RoundedCornerShape(3.dp)
                    )
            )
        }
    }
}

/**
 * Discovery method efficiency comparison chart.
 *
 * Implementation Finding: Different networks favor different discovery methods.
 * Enterprise networks often block multicast (hurting SSDP/mDNS).
 * Home networks typically work well with all methods.
 * Port scan is most reliable but slowest.
 */
@Composable
private fun DiscoveryMethodChart(stats: DiscoveryMethodStats) {
    Column {
        Text(
            text = "Discovery Method Efficiency",
            style = MaterialTheme.typography.titleSmall,
            color = MaterialTheme.colorScheme.onSurface
        )

        Spacer(modifier = Modifier.height(8.dp))

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceEvenly
        ) {
            // SSDP efficiency
            DiscoveryMethodIndicator(
                method = "SSDP",
                devicesFound = stats.ssdpDevicesFound,
                timeMs = stats.ssdpTimeMs,
                efficiency = stats.ssdpEfficiency,
                color = MetricColors.Info
            )

            // mDNS efficiency
            DiscoveryMethodIndicator(
                method = "mDNS",
                devicesFound = stats.mdnsDevicesFound,
                timeMs = stats.mdnsTimeMs,
                efficiency = stats.mdnsEfficiency,
                color = MetricColors.Success
            )

            // Port scan efficiency
            DiscoveryMethodIndicator(
                method = "Port",
                devicesFound = stats.portScanDevicesFound,
                timeMs = stats.portScanTimeMs,
                efficiency = stats.portScanEfficiency,
                color = MetricColors.Warning
            )
        }

        // Highlight most effective method
        if (stats.mostEffectiveMethod.isNotEmpty()) {
            Spacer(modifier = Modifier.height(8.dp))
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.Center,
                modifier = Modifier.fillMaxWidth()
            ) {
                Icon(
                    imageVector = Icons.Default.TrendingUp,
                    contentDescription = "Most effective",
                    modifier = Modifier.size(16.dp),
                    tint = MetricColors.Success
                )
                Spacer(modifier = Modifier.width(4.dp))
                Text(
                    text = "Most effective: ${stats.mostEffectiveMethod}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MetricColors.Success,
                    fontWeight = FontWeight.Medium
                )
            }
        }
    }
}

/**
 * Individual discovery method performance indicator.
 */
@Composable
private fun DiscoveryMethodIndicator(
    method: String,
    devicesFound: Int,
    timeMs: Long,
    efficiency: Float,
    color: Color
) {
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = Modifier.width(80.dp)
    ) {
        // Circular efficiency indicator
        Canvas(modifier = Modifier.size(40.dp)) {
            // Background circle
            drawCircle(
                color = color.copy(alpha = 0.2f),
                radius = size.minDimension / 2f
            )

            // Efficiency arc (scaled to show relative efficiency)
            val sweepAngle = (efficiency * 180f).coerceIn(0f, 360f)
            if (sweepAngle > 0f) {
                drawArc(
                    color = color,
                    startAngle = -90f,
                    sweepAngle = sweepAngle,
                    useCenter = false,
                    style = Stroke(width = 4.dp.toPx())
                )
            }
        }

        Spacer(modifier = Modifier.height(4.dp))

        Text(
            text = method,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurface,
            fontWeight = FontWeight.Medium
        )

        Text(
            text = "$devicesFound dev",
            style = MaterialTheme.typography.labelSmall,
            color = color
        )

        Text(
            text = "${timeMs}ms",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

/**
 * Device response time distribution chart showing fastest and slowest devices.
 *
 * Performance Finding: Response time analysis helps identify network bottlenecks.
 * Devices on same network segment typically show similar response times.
 * Outliers often indicate device-specific issues or network congestion.
 */
@Composable
private fun DeviceResponseTimeChart(metrics: BlastMetrics) {
    Column {
        Text(
            text = "Device Response Times",
            style = MaterialTheme.typography.titleSmall,
            color = MaterialTheme.colorScheme.onSurface
        )

        Spacer(modifier = Modifier.height(8.dp))

        // Show fastest and slowest devices
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            // Fastest device
            Column(modifier = Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(
                        imageVector = Icons.Default.Speed,
                        contentDescription = "Fastest",
                        modifier = Modifier.size(16.dp),
                        tint = MetricColors.Success
                    )
                    Spacer(modifier = Modifier.width(4.dp))
                    Text(
                        text = "Fastest",
                        style = MaterialTheme.typography.bodySmall,
                        color = MetricColors.Success,
                        fontWeight = FontWeight.Medium
                    )
                }
                Text(
                    text = "${metrics.fastestDeviceMs}ms",
                    style = FartLooperTextStyles.metricsValue.copy(fontSize = MaterialTheme.typography.bodyMedium.fontSize),
                    color = MetricColors.Success
                )
            }

            // Average response time
            Column(
                modifier = Modifier.weight(1f),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(
                        imageVector = Icons.Default.BarChart,
                        contentDescription = "Average",
                        modifier = Modifier.size(16.dp),
                        tint = MetricColors.Info
                    )
                    Spacer(modifier = Modifier.width(4.dp))
                    Text(
                        text = "Average",
                        style = MaterialTheme.typography.bodySmall,
                        color = MetricColors.Info,
                        fontWeight = FontWeight.Medium
                    )
                }
                Text(
                    text = "${metrics.averageLatencyMs}ms",
                    style = FartLooperTextStyles.metricsValue.copy(fontSize = MaterialTheme.typography.bodyMedium.fontSize),
                    color = MetricColors.Info
                )
            }

            // Slowest device
            Column(
                modifier = Modifier.weight(1f),
                horizontalAlignment = Alignment.End
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(
                        imageVector = Icons.Default.HourglassTop,
                        contentDescription = "Slowest",
                        modifier = Modifier.size(16.dp),
                        tint = MetricColors.Warning
                    )
                    Spacer(modifier = Modifier.width(4.dp))
                    Text(
                        text = "Slowest",
                        style = MaterialTheme.typography.bodySmall,
                        color = MetricColors.Warning,
                        fontWeight = FontWeight.Medium
                    )
                }
                Text(
                    text = "${metrics.slowestDeviceMs}ms",
                    style = FartLooperTextStyles.metricsValue.copy(fontSize = MaterialTheme.typography.bodyMedium.fontSize),
                    color = MetricColors.Warning
                )
            }
        }

        Spacer(modifier = Modifier.height(8.dp))

        // Response time distribution visualization
        ResponseTimeDistributionChart(metrics.deviceResponseTimes)
    }
}

/**
 * Horizontal bar chart showing response time distribution across devices.
 *
 * Visualization Finding: Horizontal bars are more readable than vertical for device names.
 * Color coding helps identify problematic devices at a glance.
 */
@Composable
private fun ResponseTimeDistributionChart(deviceResponseTimes: Map<String, Long>) {
    val maxResponseTime = deviceResponseTimes.values.maxOrNull() ?: 1L
    val sortedDevices = deviceResponseTimes.toList().sortedBy { it.second }

    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        sortedDevices.take(5).forEach { (deviceName, responseTime) ->
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                // Device name (truncated for space)
                Text(
                    text = deviceName.take(12) + if (deviceName.length > 12) "..." else "",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurface,
                    modifier = Modifier.width(80.dp)
                )

                Spacer(modifier = Modifier.width(8.dp))

                // Response time bar
                Box(
                    modifier = Modifier
                        .weight(1f)
                        .height(4.dp)
                        .background(
                            MaterialTheme.colorScheme.surfaceVariant,
                            RoundedCornerShape(2.dp)
                        )
                ) {
                    Box(
                        modifier = Modifier
                            .fillMaxHeight()
                            .fillMaxWidth((responseTime.toFloat() / maxResponseTime.toFloat()).coerceIn(0f, 1f))
                            .background(
                                when {
                                    responseTime <= 200L -> MetricColors.Success
                                    responseTime <= 500L -> MetricColors.Warning
                                    else -> MetricColors.Error
                                },
                                RoundedCornerShape(2.dp)
                            )
                    )
                }

                Spacer(modifier = Modifier.width(8.dp))

                // Response time value
                Text(
                    text = "${responseTime}ms",
                    style = MaterialTheme.typography.labelSmall,
                    color = when {
                        responseTime <= 200L -> MetricColors.Success
                        responseTime <= 500L -> MetricColors.Warning
                        else -> MetricColors.Error
                    },
                    modifier = Modifier.width(40.dp)
                )
            }
        }

        // Show count if more devices than displayed
        if (deviceResponseTimes.size > 5) {
            Text(
                text = "... and ${deviceResponseTimes.size - 5} more devices",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(start = 88.dp)
            )
        }
    }
}

/**
 * Preview for MetricsOverlay
 */
@Preview(name = "Metrics Overlay")
@Composable
private fun MetricsOverlayPreview() {
    FartLooperThemePreview {
        var isExpanded by remember { mutableStateOf(true) }

        MetricsOverlay(
            metrics = BlastMetrics(
                httpStartupMs = 35L,
                discoveryTimeMs = 2150L,
                totalDevicesFound = 5,
                connectionsAttempted = 5,
                successfulBlasts = 4,
                failedBlasts = 1,
                averageLatencyMs = 180L,
                isRunning = false,
                // Enhanced metrics for advanced charts
                deviceResponseTimes = mapOf(
                    "Living Room Sonos" to 120L,
                    "Kitchen Chromecast" to 280L,
                    "Samsung TV" to 450L,
                    "Bedroom Speaker" to 150L
                ),
                successRateByManufacturer = mapOf(
                    "Sonos" to 0.95f,
                    "Google" to 0.90f,
                    "Samsung" to 0.85f,
                    "LG" to 0.78f
                ),
                discoveryMethodStats = DiscoveryMethodStats(
                    ssdpDevicesFound = 3,
                    ssdpTimeMs = 500L,
                    mdnsDevicesFound = 2,
                    mdnsTimeMs = 800L,
                    portScanDevicesFound = 1,
                    portScanTimeMs = 1200L
                )
            ),
            isExpanded = isExpanded,
            onToggleExpanded = { isExpanded = !isExpanded },
            modifier = Modifier.padding(16.dp)
        )
    }
}
