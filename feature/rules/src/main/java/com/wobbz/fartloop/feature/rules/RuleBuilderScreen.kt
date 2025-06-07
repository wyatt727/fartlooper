package com.wobbz.fartloop.feature.rules

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.wobbz.fartloop.design.theme.FartLooperTheme
import com.wobbz.fartloop.feature.rules.model.*
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import timber.log.Timber
import java.time.DayOfWeek
import java.time.format.DateTimeFormatter
import java.time.format.DateTimeParseException
import java.util.*
import javax.inject.Inject

/**
 * Visual rule builder screen for creating automation rules.
 *
 * UX Finding: Progressive disclosure pattern works best for rule complexity.
 * Start with simple condition types, then reveal advanced options (regex, case sensitivity).
 * This prevents overwhelming novice users while preserving power-user functionality.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun RuleBuilderScreen(
    onNavigateBack: () -> Unit,
    ruleId: String? = null, // null for new rule, non-null for editing
    viewModel: RuleBuilderViewModel = hiltViewModel()
) {
    val uiState by viewModel.uiState.collectAsState()

    // Load existing rule if editing
    LaunchedEffect(ruleId) {
        if (ruleId != null) {
            viewModel.loadRule(ruleId)
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
    ) {
        // Top app bar
        TopAppBar(
            title = {
                Text(
                    text = if (ruleId == null) "Create Rule" else "Edit Rule",
                    style = MaterialTheme.typography.headlineSmall
                )
            },
            navigationIcon = {
                IconButton(onClick = onNavigateBack) {
                    Icon(Icons.Default.ArrowBack, contentDescription = "Back")
                }
            },
            actions = {
                // Save button
                TextButton(
                    onClick = {
                        viewModel.saveRule()
                        onNavigateBack()
                    },
                    enabled = uiState.isValidRule
                ) {
                    Text("SAVE")
                }
            }
        )

        // Rule builder content
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(24.dp)
        ) {
            // Rule name section
            item {
                RuleNameSection(
                    name = uiState.ruleName,
                    onNameChange = viewModel::updateRuleName,
                    enabled = uiState.ruleEnabled,
                    onEnabledChange = viewModel::updateRuleEnabled
                )
            }

            // Conditions header
            item {
                Text(
                    text = "When (Conditions)",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.primary
                )
            }

            // SSID condition
            item {
                SsidConditionCard(
                    ssidCondition = uiState.ssidCondition,
                    onSsidConditionChange = viewModel::updateSsidCondition
                )
            }

            // Time condition
            item {
                TimeConditionCard(
                    timeCondition = uiState.timeCondition,
                    onTimeConditionChange = viewModel::updateTimeCondition
                )
            }

            // Day of week condition
            item {
                DayOfWeekConditionCard(
                    dayCondition = uiState.dayCondition,
                    onDayConditionChange = viewModel::updateDayCondition
                )
            }

            // Action header
            item {
                Text(
                    text = "Then (Action)",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.primary
                )
            }

            // Blast action
            item {
                BlastActionCard(
                    blastAction = uiState.blastAction,
                    onBlastActionChange = viewModel::updateBlastAction
                )
            }

            // Rule preview
            item {
                RulePreviewCard(
                    uiState = uiState
                )
            }
        }
    }
}

/**
 * Rule name and enabled toggle section.
 */
@Composable
private fun RuleNameSection(
    name: String,
    onNameChange: (String) -> Unit,
    enabled: Boolean,
    onEnabledChange: (Boolean) -> Unit
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant
        )
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            OutlinedTextField(
                value = name,
                onValueChange = onNameChange,
                label = { Text("Rule Name") },
                placeholder = { Text("e.g., Home After Work") },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true,
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Next)
            )

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = "Rule Enabled",
                    style = MaterialTheme.typography.bodyMedium
                )
                Switch(
                    checked = enabled,
                    onCheckedChange = onEnabledChange
                )
            }
        }
    }
}

/**
 * SSID matching condition with regex support.
 *
 * UX Finding: Simple/regex toggle prevents confusion. Most users want "contains" matching,
 * but regex power users need full pattern support. Regex validation provides immediate feedback.
 */
@Composable
private fun SsidConditionCard(
    ssidCondition: SsidCondition?,
    onSsidConditionChange: (SsidCondition?) -> Unit
) {
    var isExpanded by remember { mutableStateOf(ssidCondition != null) }

    ConditionCard(
        title = "Wi-Fi Network (SSID)",
        icon = Icons.Default.Wifi,
        isExpanded = isExpanded,
        onExpandedChange = { expanded ->
            isExpanded = expanded
            if (!expanded) {
                onSsidConditionChange(null)
            } else {
                onSsidConditionChange(SsidCondition(pattern = "", isRegex = false))
            }
        }
    ) {
        if (ssidCondition != null) {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                OutlinedTextField(
                    value = ssidCondition.pattern,
                    onValueChange = { pattern ->
                        onSsidConditionChange(ssidCondition.copy(pattern = pattern))
                    },
                    label = { Text(if (ssidCondition.isRegex) "Regex Pattern" else "Network Name") },
                    placeholder = {
                        Text(if (ssidCondition.isRegex) "^home.*|office.*$" else "home")
                    },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true,
                    isError = ssidCondition.isRegex && !isValidRegex(ssidCondition.pattern),
                    supportingText = {
                        if (ssidCondition.isRegex && !isValidRegex(ssidCondition.pattern)) {
                            Text(
                                text = "Invalid regex pattern",
                                color = MaterialTheme.colorScheme.error
                            )
                        }
                    }
                )

                // Regex toggle
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text("Use regex pattern")
                    Switch(
                        checked = ssidCondition.isRegex,
                        onCheckedChange = { isRegex ->
                            onSsidConditionChange(ssidCondition.copy(isRegex = isRegex))
                        }
                    )
                }

                // Case sensitive toggle (only show for regex)
                if (ssidCondition.isRegex) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text("Case sensitive")
                        Switch(
                            checked = ssidCondition.caseSensitive,
                            onCheckedChange = { caseSensitive ->
                                onSsidConditionChange(ssidCondition.copy(caseSensitive = caseSensitive))
                            }
                        )
                    }
                }
            }
        }
    }
}

/**
 * Time window condition with 24-hour time pickers.
 *
 * Implementation Finding: Text input for time is more reliable than time pickers across Android versions.
 * Validates format in real-time and handles overnight ranges (22:00-06:00) correctly.
 */
@Composable
private fun TimeConditionCard(
    timeCondition: TimeCondition?,
    onTimeConditionChange: (TimeCondition?) -> Unit
) {
    var isExpanded by remember { mutableStateOf(timeCondition != null) }

    ConditionCard(
        title = "Time Window",
        icon = Icons.Default.Schedule,
        isExpanded = isExpanded,
        onExpandedChange = { expanded ->
            isExpanded = expanded
            if (!expanded) {
                onTimeConditionChange(null)
            } else {
                onTimeConditionChange(TimeCondition(startTime = "09:00", endTime = "17:00"))
            }
        }
    ) {
        if (timeCondition != null) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                OutlinedTextField(
                    value = timeCondition.startTime,
                    onValueChange = { startTime ->
                        onTimeConditionChange(timeCondition.copy(startTime = startTime))
                    },
                    label = { Text("From") },
                    placeholder = { Text("09:00") },
                    modifier = Modifier.weight(1f),
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    isError = !isValidTimeFormat(timeCondition.startTime)
                )

                OutlinedTextField(
                    value = timeCondition.endTime,
                    onValueChange = { endTime ->
                        onTimeConditionChange(timeCondition.copy(endTime = endTime))
                    },
                    label = { Text("To") },
                    placeholder = { Text("17:00") },
                    modifier = Modifier.weight(1f),
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    isError = !isValidTimeFormat(timeCondition.endTime)
                )
            }

            // Overnight range indicator
            if (isValidTimeFormat(timeCondition.startTime) && isValidTimeFormat(timeCondition.endTime)) {
                val isOvernight = timeCondition.startTime > timeCondition.endTime
                if (isOvernight) {
                    Text(
                        text = "⚠️ Overnight range (crosses midnight)",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.primary
                    )
                }
            }
        }
    }
}

/**
 * Day of week selection with common presets.
 *
 * UX Finding: Visual day chips are more intuitive than dropdowns for day selection.
 * Common presets (weekdays, weekends, every day) speed up rule creation.
 */
@Composable
private fun DayOfWeekConditionCard(
    dayCondition: DayOfWeekCondition?,
    onDayConditionChange: (DayOfWeekCondition?) -> Unit
) {
    var isExpanded by remember { mutableStateOf(dayCondition != null) }

    ConditionCard(
        title = "Days of Week",
        icon = Icons.Default.CalendarMonth,
        isExpanded = isExpanded,
        onExpandedChange = { expanded ->
            isExpanded = expanded
            if (!expanded) {
                onDayConditionChange(null)
            } else {
                // Default to weekdays
                val weekdays = setOf(DayOfWeek.MONDAY, DayOfWeek.TUESDAY, DayOfWeek.WEDNESDAY, DayOfWeek.THURSDAY, DayOfWeek.FRIDAY)
                onDayConditionChange(DayOfWeekCondition(weekdays))
            }
        }
    ) {
        if (dayCondition != null) {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                // Preset buttons
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    PresetButton(
                        text = "Weekdays",
                        onClick = {
                            val weekdays = setOf(DayOfWeek.MONDAY, DayOfWeek.TUESDAY, DayOfWeek.WEDNESDAY, DayOfWeek.THURSDAY, DayOfWeek.FRIDAY)
                            onDayConditionChange(dayCondition.copy(allowedDays = weekdays))
                        }
                    )
                    PresetButton(
                        text = "Weekends",
                        onClick = {
                            val weekends = setOf(DayOfWeek.SATURDAY, DayOfWeek.SUNDAY)
                            onDayConditionChange(dayCondition.copy(allowedDays = weekends))
                        }
                    )
                    PresetButton(
                        text = "Every Day",
                        onClick = {
                            val allDays = DayOfWeek.values().toSet()
                            onDayConditionChange(dayCondition.copy(allowedDays = allDays))
                        }
                    )
                }

                // Individual day chips
                Text(
                    text = "Select specific days:",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    DayOfWeek.values().forEach { day ->
                        val isSelected = day in dayCondition.allowedDays
                        val dayAbbrev = day.name.take(3).lowercase().replaceFirstChar { it.uppercase() }

                        FilterChip(
                            selected = isSelected,
                            onClick = {
                                val newDays = if (isSelected) {
                                    dayCondition.allowedDays - day
                                } else {
                                    dayCondition.allowedDays + day
                                }
                                onDayConditionChange(dayCondition.copy(allowedDays = newDays))
                            },
                            label = { Text(dayAbbrev) },
                            modifier = Modifier.weight(1f)
                        )
                    }
                }
            }
        }
    }
}

/**
 * Blast action configuration with clip selection.
 */
@Composable
private fun BlastActionCard(
    blastAction: BlastAction,
    onBlastActionChange: (BlastAction) -> Unit
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.primaryContainer
        )
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Icon(
                    imageVector = Icons.Default.VolumeUp,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onPrimaryContainer
                )
                Text(
                    text = "Blast Audio",
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.onPrimaryContainer
                )
            }

            // Clip selection
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = "Use default clip",
                    color = MaterialTheme.colorScheme.onPrimaryContainer
                )
                Switch(
                    checked = blastAction.useDefaultClip,
                    onCheckedChange = { useDefault ->
                        onBlastActionChange(
                            blastAction.copy(
                                useDefaultClip = useDefault,
                                clipId = if (useDefault) null else blastAction.clipId
                            )
                        )
                    }
                )
            }

            if (!blastAction.useDefaultClip) {
                OutlinedTextField(
                    value = blastAction.clipId ?: "",
                    onValueChange = { clipId ->
                        onBlastActionChange(blastAction.copy(clipId = clipId.takeIf { it.isNotBlank() }))
                    },
                    label = { Text("Clip ID") },
                    placeholder = { Text("Enter clip identifier") },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true
                )
            }
        }
    }
}

/**
 * Rule preview showing human-readable description.
 */
@Composable
private fun RulePreviewCard(
    uiState: RuleBuilderUiState
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant
        )
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Text(
                text = "Rule Preview",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold
            )

            val description = buildRuleDescription(uiState)
            Text(
                text = description,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )

            if (!uiState.isValidRule) {
                Text(
                    text = "⚠️ Rule needs at least one condition and a valid name",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error
                )
            }
        }
    }
}

/**
 * Reusable condition card with expand/collapse functionality.
 */
@Composable
private fun ConditionCard(
    title: String,
    icon: ImageVector,
    isExpanded: Boolean,
    onExpandedChange: (Boolean) -> Unit,
    content: @Composable () -> Unit
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = if (isExpanded) {
                MaterialTheme.colorScheme.surfaceVariant
            } else {
                MaterialTheme.colorScheme.surface
            }
        )
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable { onExpandedChange(!isExpanded) },
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Icon(
                        imageVector = icon,
                        contentDescription = null,
                        tint = if (isExpanded) {
                            MaterialTheme.colorScheme.primary
                        } else {
                            MaterialTheme.colorScheme.onSurfaceVariant
                        }
                    )
                    Text(
                        text = title,
                        style = MaterialTheme.typography.titleMedium,
                        color = if (isExpanded) {
                            MaterialTheme.colorScheme.primary
                        } else {
                            MaterialTheme.colorScheme.onSurfaceVariant
                        }
                    )
                }

                Switch(
                    checked = isExpanded,
                    onCheckedChange = onExpandedChange
                )
            }

            if (isExpanded) {
                Spacer(modifier = Modifier.height(16.dp))
                content()
            }
        }
    }
}

/**
 * Preset button for common day selections.
 */
@Composable
private fun PresetButton(
    text: String,
    onClick: () -> Unit
) {
    OutlinedButton(
        onClick = onClick,
        modifier = Modifier.height(32.dp),
        contentPadding = PaddingValues(horizontal = 12.dp, vertical = 0.dp)
    ) {
        Text(
            text = text,
            style = MaterialTheme.typography.bodySmall
        )
    }
}

/**
 * Validation helper functions.
 */
private fun isValidRegex(pattern: String): Boolean {
    return try {
        java.util.regex.Pattern.compile(pattern)
        true
    } catch (e: Exception) {
        false
    }
}

private fun isValidTimeFormat(time: String): Boolean {
    return try {
        val formatter = DateTimeFormatter.ofPattern("HH:mm")
        formatter.parse(time)
        true
    } catch (e: DateTimeParseException) {
        false
    }
}

/**
 * Build human-readable rule description.
 */
private fun buildRuleDescription(uiState: RuleBuilderUiState): String {
    val conditions = mutableListOf<String>()

    uiState.ssidCondition?.let { ssid ->
        val pattern = if (ssid.isRegex) "matches \"${ssid.pattern}\"" else "contains \"${ssid.pattern}\""
        conditions.add("Wi-Fi $pattern")
    }

    uiState.timeCondition?.let { time ->
        conditions.add("between ${time.startTime} and ${time.endTime}")
    }

    uiState.dayCondition?.let { days ->
        val dayNames = days.allowedDays.map { it.name.lowercase().replaceFirstChar { c -> c.uppercase() } }
        conditions.add("on ${dayNames.joinToString(", ")}")
    }

    val conditionsText = if (conditions.isEmpty()) {
        "No conditions set"
    } else {
        conditions.joinToString(" AND ")
    }

    val actionText = if (uiState.blastAction.useDefaultClip) {
        "blast default clip"
    } else {
        "blast clip ${uiState.blastAction.clipId ?: "undefined"}"
    }

    return "WHEN $conditionsText THEN $actionText"
}

@Preview
@Composable
private fun RuleBuilderScreenPreview() {
    FartLooperTheme {
        // Preview with mock state - actual implementation would use ViewModel
        RuleBuilderScreen(
            onNavigateBack = { },
            ruleId = null
        )
    }
}
