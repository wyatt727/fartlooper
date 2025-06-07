package com.wobbz.fartloop.feature.rules.model

import com.wobbz.fartloop.core.network.NetworkCallbackUtil
import com.wobbz.fartloop.core.network.RuleEvaluator
import kotlinx.coroutines.flow.first
import timber.log.Timber
import java.time.DayOfWeek
import java.time.LocalTime
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Real implementation of RuleEvaluator using the rules repository.
 *
 * Architecture Finding: Uses runBlocking for synchronous evaluation within async context.
 * This approach maintains the simple interface while accessing repository data.
 * For production, we'd want to cache rules to avoid blocking calls.
 */
@Singleton
class RealRuleEvaluator @Inject constructor(
    private val ruleRepository: RuleRepository
) : RuleEvaluator {

    /**
     * Evaluate if any enabled rule should trigger an auto-blast.
     *
     * Implementation Finding: Uses runBlocking to convert async repository to sync interface.
     * First matching rule wins to prevent multiple auto-blasts firing simultaneously.
     *
     * INTERFACE IMPLEMENTATION FINDING: Implements main app RuleEvaluator interface
     * This allows dependency injection from app module to work correctly with Hilt bindings
     */
    override fun shouldAutoBlast(networkInfo: NetworkCallbackUtil.NetworkInfo): Boolean {
        return kotlinx.coroutines.runBlocking {
            evaluateRulesAsync(networkInfo)
        }
    }

    /**
     * Async implementation of rule evaluation.
     */
    private suspend fun evaluateRulesAsync(networkInfo: NetworkCallbackUtil.NetworkInfo): Boolean {
        try {
            // Get all enabled rules
            val enabledRules = ruleRepository.enabledRules.first()

            if (enabledRules.isEmpty()) {
                Timber.d("No enabled rules, skipping auto-blast evaluation")
                return false
            }

            // Build evaluation context from current system state
            val context = buildEvaluationContext(networkInfo)

            // Check each rule for matches
            for (rule in enabledRules) {
                if (rule.shouldTrigger(context)) {
                    Timber.i("Rule triggered auto-blast: ${rule.name} (${rule.id})")

                    // Mark rule as triggered for analytics/debugging
                    ruleRepository.markRuleTriggered(rule.id)

                    return true
                }
            }

            Timber.d("No rules matched for network: $networkInfo")
            return false

        } catch (e: Exception) {
            Timber.e(e, "Error evaluating rules for auto-blast")
            return false
        }
    }

    /**
     * Build rule evaluation context from current system state.
     *
     * Implementation Finding: Centralizes system state gathering for rule evaluation.
     * LocalTime and DayOfWeek are computed once per evaluation to ensure consistency
     * across multiple rule checks.
     */
    private fun buildEvaluationContext(networkInfo: NetworkCallbackUtil.NetworkInfo): RuleEvaluationContext {
        val currentTime = LocalTime.now()
        val currentDay = DayOfWeek.from(java.time.LocalDate.now())

        // Convert NetworkCallbackUtil.NetworkInfo to rule module format
        val ruleNetworkInfo = when (networkInfo) {
            is NetworkCallbackUtil.NetworkInfo.WiFi ->
                RuleEvaluationContext.NetworkInfo.WiFi(networkInfo.ssid)
            is NetworkCallbackUtil.NetworkInfo.Mobile ->
                RuleEvaluationContext.NetworkInfo.Mobile
            is NetworkCallbackUtil.NetworkInfo.Disconnected ->
                RuleEvaluationContext.NetworkInfo.Disconnected
        }

        return RuleEvaluationContext(
            networkInfo = ruleNetworkInfo,
            currentTime = currentTime,
            currentDayOfWeek = currentDay
        )
    }

    /**
     * Get human-readable description of all enabled rules.
     * Useful for debugging and UI display.
     */
    suspend fun getEnabledRulesSummary(): String {
        val rules = ruleRepository.enabledRules.first()
        return if (rules.isEmpty()) {
            "No active rules"
        } else {
            rules.joinToString("\n") { rule ->
                val conditions = rule.conditions.joinToString(" AND ") { condition ->
                    when (condition) {
                        is SsidCondition -> "SSID ${if (condition.isRegex) "matches" else "contains"} '${condition.pattern}'"
                        is TimeCondition -> "Time ${condition.startTime}-${condition.endTime}"
                        is DayOfWeekCondition -> "Days ${condition.allowedDays.joinToString(",")}"
                    }
                }
                "• ${rule.name}: WHEN $conditions THEN blast"
            }
        }
    }

    /**
     * Test rule evaluation against specific context.
     * Used for rule testing and validation in UI.
     */
    suspend fun testRule(ruleId: String, context: RuleEvaluationContext): Boolean {
        val rule = ruleRepository.getRule(ruleId) ?: return false
        return rule.shouldTrigger(context)
    }

    /**
     * Get statistics about rule triggers for analytics.
     */
    suspend fun getRuleStatistics(): Map<String, RuleStats> {
        val allRules = ruleRepository.rules.first()
        return allRules.associate { rule ->
            rule.id to RuleStats(
                ruleName = rule.name,
                isEnabled = rule.isEnabled,
                lastTriggered = rule.lastTriggered,
                conditionCount = rule.conditions.size
            )
        }
    }
}

/**
 * Statistics for a specific rule.
 */
data class RuleStats(
    val ruleName: String,
    val isEnabled: Boolean,
    val lastTriggered: Long?,
    val conditionCount: Int
)
