package com.wobbz.fartloop.feature.rules.model

import com.squareup.moshi.JsonClass
import java.time.DayOfWeek
import java.time.LocalTime

/**
 * Rule data model representing a complete if-then automation rule.
 *
 * Design Finding: Rules are structured as conditions (if) + action (then) for maximum flexibility.
 * Each rule can have multiple conditions that must ALL be satisfied (AND logic).
 * This prevents rule conflicts and makes debugging easier than complex OR/AND combinations.
 */
@JsonClass(generateAdapter = true)
data class Rule(
    val id: String,
    val name: String,
    val isEnabled: Boolean = true,
    val conditions: List<RuleCondition>,
    val action: RuleAction,
    val createdAt: Long = System.currentTimeMillis(),
    val lastTriggered: Long? = null
) {
    /**
     * Check if this rule should trigger for the given context.
     * ALL conditions must be satisfied (AND logic).
     */
    fun shouldTrigger(context: RuleEvaluationContext): Boolean {
        if (!isEnabled) return false
        return conditions.all { condition -> condition.matches(context) }
    }
}

/**
 * Base interface for rule conditions.
 * Each condition type implements specific matching logic.
 */
sealed interface RuleCondition {
    fun matches(context: RuleEvaluationContext): Boolean
}

/**
 * SSID matching condition with regex support.
 *
 * Implementation Finding: Provides both simple "contains" and regex modes to avoid UX confusion.
 * Most users want simple matching ("home", "office") but power users need regex flexibility.
 * Regex validation happens at UI level to provide immediate feedback.
 */
@JsonClass(generateAdapter = true)
data class SsidCondition(
    val pattern: String,
    val isRegex: Boolean = false,
    val caseSensitive: Boolean = false
) : RuleCondition {

    override fun matches(context: RuleEvaluationContext): Boolean {
        // SSID CONDITION FINDING: Only WiFi networks have SSID property
        // Must check network type first before accessing ssid property
        val ssid = when (val networkInfo = context.networkInfo) {
            is RuleEvaluationContext.NetworkInfo.WiFi -> networkInfo.ssid
            else -> return false // Non-WiFi networks don't match SSID conditions
        } ?: return false

        return if (isRegex) {
            try {
                val flags = if (caseSensitive) 0 else java.util.regex.Pattern.CASE_INSENSITIVE
                val regex = java.util.regex.Pattern.compile(pattern, flags)
                regex.matcher(ssid).matches()
            } catch (e: Exception) {
                // Invalid regex always fails - this should be caught at UI level
                false
            }
        } else {
            // Simple contains matching
            val searchText = if (caseSensitive) ssid else ssid.lowercase()
            val patternText = if (caseSensitive) pattern else pattern.lowercase()
            searchText.contains(patternText)
        }
    }
}

/**
 * Time window condition for restricting rules to specific hours.
 *
 * Design Finding: Uses LocalTime for 24-hour precision. Handles overnight ranges
 * (e.g., 22:00-06:00) by checking if current time falls in either range.
 * This prevents common "overnight rule" bugs where 23:30 doesn't match 22:00-06:00.
 */
@JsonClass(generateAdapter = true)
data class TimeCondition(
    val startTime: String, // HH:mm format
    val endTime: String    // HH:mm format
) : RuleCondition {

    override fun matches(context: RuleEvaluationContext): Boolean {
        try {
            val current = context.currentTime
            val start = LocalTime.parse(startTime)
            val end = LocalTime.parse(endTime)

            return if (start <= end) {
                // Same day: 09:00-17:00
                current >= start && current <= end
            } else {
                // Overnight: 22:00-06:00
                current >= start || current <= end
            }
        } catch (e: Exception) {
            // Invalid time format always fails
            return false
        }
    }
}

/**
 * Day of week condition for restricting rules to specific days.
 *
 * UX Finding: Uses Set<DayOfWeek> for efficient lookups and natural "weekdays" grouping.
 * Common patterns: weekdays [MON-FRI], weekends [SAT-SUN], all days, custom selection.
 */
@JsonClass(generateAdapter = true)
data class DayOfWeekCondition(
    val allowedDays: Set<DayOfWeek>
) : RuleCondition {

    override fun matches(context: RuleEvaluationContext): Boolean {
        return context.currentDayOfWeek in allowedDays
    }
}

/**
 * Context passed to rule evaluation containing current system state.
 * Immutable data class ensures thread-safe rule evaluation.
 */
data class RuleEvaluationContext(
    val networkInfo: NetworkInfo,
    val currentTime: LocalTime,
    val currentDayOfWeek: DayOfWeek
) {
    /**
     * Network information for rule evaluation.
     * Mirrors NetworkCallbackUtil.NetworkInfo but in rules module.
     */
    sealed class NetworkInfo {
        object Disconnected : NetworkInfo()
        object Mobile : NetworkInfo()
        data class WiFi(val ssid: String?) : NetworkInfo()
    }
}

/**
 * Rule action to execute when conditions are met.
 * Currently only supports blast action, but structured for future extensibility.
 */
sealed interface RuleAction

/**
 * Blast action with specific clip selection.
 *
 * Architecture Finding: References clip by ID rather than embedding content.
 * This allows clips to be updated independently of rules, and prevents
 * rule data from becoming large when clips are big files.
 */
@JsonClass(generateAdapter = true)
data class BlastAction(
    val clipId: String?,
    val useDefaultClip: Boolean = false
) : RuleAction

/**
 * Rule DSL builder for creating rules programmatically.
 * Provides fluent API for rule construction in tests and advanced scenarios.
 */
class RuleBuilder(private val id: String, private val name: String) {
    private val conditions = mutableListOf<RuleCondition>()
    private var action: RuleAction = BlastAction(clipId = null, useDefaultClip = true)
    private var enabled = true

    fun whenSsid(pattern: String, isRegex: Boolean = false): RuleBuilder {
        conditions.add(SsidCondition(pattern, isRegex))
        return this
    }

    fun whenTime(start: String, end: String): RuleBuilder {
        conditions.add(TimeCondition(start, end))
        return this
    }

    fun whenDays(vararg days: DayOfWeek): RuleBuilder {
        conditions.add(DayOfWeekCondition(days.toSet()))
        return this
    }

    fun thenBlast(clipId: String? = null): RuleBuilder {
        action = BlastAction(clipId, useDefaultClip = clipId == null)
        return this
    }

    fun enabled(enabled: Boolean): RuleBuilder {
        this.enabled = enabled
        return this
    }

    fun build(): Rule {
        return Rule(
            id = id,
            name = name,
            isEnabled = enabled,
            conditions = conditions.toList(),
            action = action
        )
    }
}

/**
 * Helper functions for common rule patterns.
 */
object RulePresets {
    fun homeAfterWork(homeSsid: String): Rule = RuleBuilder("home_after_work", "Home After Work")
        .whenSsid(homeSsid)
        .whenTime("17:00", "23:59")
        .whenDays(DayOfWeek.MONDAY, DayOfWeek.TUESDAY, DayOfWeek.WEDNESDAY, DayOfWeek.THURSDAY, DayOfWeek.FRIDAY)
        .thenBlast()
        .build()

    fun weekendMorning(homeSsid: String): Rule = RuleBuilder("weekend_morning", "Weekend Morning")
        .whenSsid(homeSsid)
        .whenTime("08:00", "12:00")
        .whenDays(DayOfWeek.SATURDAY, DayOfWeek.SUNDAY)
        .thenBlast()
        .build()
}
