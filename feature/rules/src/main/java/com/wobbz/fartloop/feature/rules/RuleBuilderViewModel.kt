package com.wobbz.fartloop.feature.rules

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.wobbz.fartloop.feature.rules.model.*
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import timber.log.Timber
import java.time.DayOfWeek
import java.util.*
import javax.inject.Inject

/**
 * ViewModel for the rule builder screen.
 *
 * Architecture Finding: Separates UI state management from business logic.
 * Uses StateFlow for reactive UI updates and maintains validation state.
 * Rule creation/editing logic is centralized here for testability.
 */
@HiltViewModel
class RuleBuilderViewModel @Inject constructor(
    private val ruleRepository: RuleRepository
) : ViewModel() {

    private val _uiState = MutableStateFlow(RuleBuilderUiState())
    val uiState: StateFlow<RuleBuilderUiState> = _uiState.asStateFlow()

    private var editingRuleId: String? = null

    /**
     * Load an existing rule for editing.
     */
    fun loadRule(ruleId: String) {
        Timber.d("Loading rule for editing: $ruleId")
        editingRuleId = ruleId

        viewModelScope.launch {
            try {
                val rule = ruleRepository.getRule(ruleId)
                if (rule != null) {
                    _uiState.value = rule.toUiState()
                    Timber.d("Loaded rule: ${rule.name}")
                } else {
                    Timber.w("Rule not found: $ruleId")
                }
            } catch (e: Exception) {
                Timber.e(e, "Failed to load rule: $ruleId")
            }
        }
    }

    /**
     * Update rule name and validate.
     */
    fun updateRuleName(name: String) {
        _uiState.value = _uiState.value.copy(
            ruleName = name,
            isValidRule = validateRule(_uiState.value.copy(ruleName = name))
        )
    }

    /**
     * Toggle rule enabled state.
     */
    fun updateRuleEnabled(enabled: Boolean) {
        _uiState.value = _uiState.value.copy(ruleEnabled = enabled)
    }

    /**
     * Update SSID condition.
     */
    fun updateSsidCondition(condition: SsidCondition?) {
        _uiState.value = _uiState.value.copy(
            ssidCondition = condition,
            isValidRule = validateRule(_uiState.value.copy(ssidCondition = condition))
        )
    }

    /**
     * Update time condition.
     */
    fun updateTimeCondition(condition: TimeCondition?) {
        _uiState.value = _uiState.value.copy(
            timeCondition = condition,
            isValidRule = validateRule(_uiState.value.copy(timeCondition = condition))
        )
    }

    /**
     * Update day of week condition.
     */
    fun updateDayCondition(condition: DayOfWeekCondition?) {
        _uiState.value = _uiState.value.copy(
            dayCondition = condition,
            isValidRule = validateRule(_uiState.value.copy(dayCondition = condition))
        )
    }

    /**
     * Update blast action.
     */
    fun updateBlastAction(action: BlastAction) {
        _uiState.value = _uiState.value.copy(
            blastAction = action,
            isValidRule = validateRule(_uiState.value.copy(blastAction = action))
        )
    }

    /**
     * Save the current rule.
     */
    fun saveRule() {
        val currentState = _uiState.value
        if (!currentState.isValidRule) {
            Timber.w("Attempted to save invalid rule")
            return
        }

        viewModelScope.launch {
            try {
                val rule = currentState.toRule(editingRuleId)
                ruleRepository.saveRule(rule)

                Timber.i("Rule saved successfully: ${rule.name} (${rule.id})")

                // Reset state for potential new rule creation
                if (editingRuleId == null) {
                    _uiState.value = RuleBuilderUiState()
                }

            } catch (e: Exception) {
                Timber.e(e, "Failed to save rule")
                // TODO: Show error to user via UI state
            }
        }
    }

    /**
     * Validate rule completeness and correctness.
     *
     * Implementation Finding: Real-time validation provides immediate feedback.
     * Rule is valid if it has a name, at least one condition, and all conditions are valid.
     */
    private fun validateRule(state: RuleBuilderUiState): Boolean {
        // Must have a non-empty name
        if (state.ruleName.isBlank()) return false

        // Must have at least one condition
        val hasConditions = state.ssidCondition != null ||
                           state.timeCondition != null ||
                           state.dayCondition != null

        if (!hasConditions) return false

        // All present conditions must be valid
        state.ssidCondition?.let { ssid ->
            if (ssid.pattern.isBlank()) return false
            if (ssid.isRegex && !isValidRegex(ssid.pattern)) return false
        }

        state.timeCondition?.let { time ->
            if (!isValidTimeFormat(time.startTime) || !isValidTimeFormat(time.endTime)) return false
        }

        state.dayCondition?.let { days ->
            if (days.allowedDays.isEmpty()) return false
        }

        // Blast action must be valid
        if (!state.blastAction.useDefaultClip && state.blastAction.clipId.isNullOrBlank()) {
            return false
        }

        return true
    }

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
            val parts = time.split(":")
            if (parts.size != 2) return false
            val hour = parts[0].toInt()
            val minute = parts[1].toInt()
            hour in 0..23 && minute in 0..59
        } catch (e: Exception) {
            false
        }
    }
}

/**
 * UI state for the rule builder screen.
 * Contains all form data and validation state.
 */
data class RuleBuilderUiState(
    val ruleName: String = "",
    val ruleEnabled: Boolean = true,
    val ssidCondition: SsidCondition? = null,
    val timeCondition: TimeCondition? = null,
    val dayCondition: DayOfWeekCondition? = null,
    val blastAction: BlastAction = BlastAction(clipId = null, useDefaultClip = true),
    val isValidRule: Boolean = false
) {
    /**
     * Convert UI state to Rule domain model.
     * Generates new ID if not editing existing rule.
     */
    fun toRule(existingId: String? = null): Rule {
        val conditions = mutableListOf<RuleCondition>()

        ssidCondition?.let { conditions.add(it) }
        timeCondition?.let { conditions.add(it) }
        dayCondition?.let { conditions.add(it) }

        return Rule(
            id = existingId ?: "rule_${UUID.randomUUID()}",
            name = ruleName,
            isEnabled = ruleEnabled,
            conditions = conditions,
            action = blastAction
        )
    }
}

/**
 * Extension function to convert Rule to UI state for editing.
 */
private fun Rule.toUiState(): RuleBuilderUiState {
    var ssidCondition: SsidCondition? = null
    var timeCondition: TimeCondition? = null
    var dayCondition: DayOfWeekCondition? = null

    // Extract conditions by type
    conditions.forEach { condition ->
        when (condition) {
            is SsidCondition -> ssidCondition = condition
            is TimeCondition -> timeCondition = condition
            is DayOfWeekCondition -> dayCondition = condition
        }
    }

    return RuleBuilderUiState(
        ruleName = name,
        ruleEnabled = isEnabled,
        ssidCondition = ssidCondition,
        timeCondition = timeCondition,
        dayCondition = dayCondition,
        blastAction = action as BlastAction, // Safe cast since we only support BlastAction currently
        isValidRule = true // Assume existing rules are valid
    )
}
