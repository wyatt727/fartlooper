package com.wobbz.fartloop.feature.rules.model

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.squareup.moshi.JsonAdapter
import com.squareup.moshi.Moshi
import com.squareup.moshi.Types
import com.squareup.moshi.kotlin.reflect.KotlinJsonAdapterFactory
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import timber.log.Timber
import java.time.DayOfWeek
import java.time.LocalTime
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Repository for rule persistence using DataStore + Moshi JSON serialization.
 *
 * Architecture Finding: DataStore provides reactive Flow updates for real-time rule changes.
 * Moshi handles complex polymorphic serialization of RuleCondition sealed interface.
 * JSON format allows manual inspection/editing for debugging and testing.
 */
@Singleton
class RuleRepository @Inject constructor(
    @ApplicationContext private val context: Context
) {

    companion object {
        private val RULES_KEY = stringPreferencesKey("rules_json")
        private val Context.rulesDataStore: DataStore<Preferences> by preferencesDataStore(name = "rules")
    }

    private val moshi = Moshi.Builder()
        .add(KotlinJsonAdapterFactory())
        .add(DayOfWeekAdapter())
        .add(RuleConditionAdapter())
        .add(RuleActionAdapter())
        .build()

    private val rulesListType = Types.newParameterizedType(List::class.java, Rule::class.java)
    private val rulesAdapter: JsonAdapter<List<Rule>> = moshi.adapter(rulesListType)

    /**
     * Reactive flow of all rules.
     * Updates automatically when rules are added/modified/deleted.
     */
    val rules: Flow<List<Rule>> = context.rulesDataStore.data
        .map { preferences ->
            val json = preferences[RULES_KEY] ?: "[]"
            try {
                rulesAdapter.fromJson(json) ?: emptyList()
            } catch (e: Exception) {
                Timber.e(e, "Failed to deserialize rules from DataStore, returning empty list")
                emptyList()
            }
        }

    /**
     * Get a specific rule by ID.
     */
    suspend fun getRule(id: String): Rule? {
        return rules.map { rulesList ->
            rulesList.find { it.id == id }
        }.let { flow ->
            // Convert flow to single value for suspend function
            var result: Rule? = null
            flow.collect { result = it }
            result
        }
    }

    /**
     * Save a new rule or update an existing one.
     * Updates persist immediately to DataStore.
     */
    suspend fun saveRule(rule: Rule) {
        Timber.d("Saving rule: ${rule.name} (${rule.id})")

        context.rulesDataStore.edit { preferences ->
            val currentJson = preferences[RULES_KEY] ?: "[]"
            val currentRules = try {
                rulesAdapter.fromJson(currentJson)?.toMutableList() ?: mutableListOf()
            } catch (e: Exception) {
                Timber.w(e, "Corrupted rules data, starting fresh")
                mutableListOf()
            }

            // Replace existing rule or add new one
            val existingIndex = currentRules.indexOfFirst { it.id == rule.id }
            if (existingIndex >= 0) {
                currentRules[existingIndex] = rule
                Timber.d("Updated existing rule at index $existingIndex")
            } else {
                currentRules.add(rule)
                Timber.d("Added new rule, total count: ${currentRules.size}")
            }

            // Serialize back to JSON
            val updatedJson = rulesAdapter.toJson(currentRules)
            preferences[RULES_KEY] = updatedJson
        }
    }

    /**
     * Delete a rule by ID.
     */
    suspend fun deleteRule(id: String) {
        Timber.d("Deleting rule: $id")

        context.rulesDataStore.edit { preferences ->
            val currentJson = preferences[RULES_KEY] ?: "[]"
            val currentRules = try {
                rulesAdapter.fromJson(currentJson)?.toMutableList() ?: mutableListOf()
            } catch (e: Exception) {
                Timber.w(e, "Corrupted rules data, cannot delete")
                return@edit
            }

            val removed = currentRules.removeIf { it.id == id }
            if (removed) {
                val updatedJson = rulesAdapter.toJson(currentRules)
                preferences[RULES_KEY] = updatedJson
                Timber.d("Rule deleted, remaining count: ${currentRules.size}")
            } else {
                Timber.w("Rule not found for deletion: $id")
            }
        }
    }

    /**
     * Clear all rules.
     * Useful for testing and reset functionality.
     */
    suspend fun clearAllRules() {
        Timber.d("Clearing all rules")
        context.rulesDataStore.edit { preferences ->
            preferences[RULES_KEY] = "[]"
        }
    }

    /**
     * Update the last triggered timestamp for a rule.
     * Used by rule evaluation to track when rules fire.
     */
    suspend fun markRuleTriggered(id: String, timestamp: Long = System.currentTimeMillis()) {
        context.rulesDataStore.edit { preferences ->
            val currentJson = preferences[RULES_KEY] ?: "[]"
            val currentRules = try {
                rulesAdapter.fromJson(currentJson)?.toMutableList() ?: return@edit
            } catch (e: Exception) {
                return@edit
            }

            val ruleIndex = currentRules.indexOfFirst { it.id == id }
            if (ruleIndex >= 0) {
                currentRules[ruleIndex] = currentRules[ruleIndex].copy(lastTriggered = timestamp)
                val updatedJson = rulesAdapter.toJson(currentRules)
                preferences[RULES_KEY] = updatedJson
                Timber.d("Marked rule $id as triggered at $timestamp")
            }
        }
    }

    /**
     * Get rules that are currently enabled and could potentially trigger.
     * Filters out disabled rules for performance in rule evaluation.
     */
    val enabledRules: Flow<List<Rule>> = rules.map { allRules ->
        allRules.filter { it.isEnabled }
    }
}

/**
 * Custom Moshi adapter for DayOfWeek enum.
 * Handles serialization/deserialization of Set<DayOfWeek> in conditions.
 */
private class DayOfWeekAdapter {
    @com.squareup.moshi.ToJson
    fun toJson(dayOfWeek: DayOfWeek): String {
        return dayOfWeek.name
    }

    @com.squareup.moshi.FromJson
    fun fromJson(json: String): DayOfWeek {
        return DayOfWeek.valueOf(json)
    }
}

/**
 * Custom Moshi adapter for RuleCondition sealed interface.
 *
 * Implementation Finding: Uses type field to differentiate condition types.
 * This approach is more readable than class names and allows JSON evolution.
 * Each condition type gets its own JSON structure for maximum flexibility.
 */
private class RuleConditionAdapter {
    @com.squareup.moshi.ToJson
    fun toJson(condition: RuleCondition): Map<String, Any?> {
        return when (condition) {
            is SsidCondition -> mapOf(
                "type" to "ssid",
                "pattern" to condition.pattern,
                "isRegex" to condition.isRegex,
                "caseSensitive" to condition.caseSensitive
            )
            is TimeCondition -> mapOf(
                "type" to "time",
                "startTime" to condition.startTime,
                "endTime" to condition.endTime
            )
            is DayOfWeekCondition -> mapOf(
                "type" to "dayOfWeek",
                "allowedDays" to condition.allowedDays.map { it.name }
            )
        }
    }

    @com.squareup.moshi.FromJson
    fun fromJson(json: Map<String, Any?>): RuleCondition {
        return when (val type = json["type"] as? String) {
            "ssid" -> SsidCondition(
                pattern = json["pattern"] as? String ?: "",
                isRegex = json["isRegex"] as? Boolean ?: false,
                caseSensitive = json["caseSensitive"] as? Boolean ?: false
            )
            "time" -> TimeCondition(
                startTime = json["startTime"] as? String ?: "00:00",
                endTime = json["endTime"] as? String ?: "23:59"
            )
            "dayOfWeek" -> {
                val dayNames = json["allowedDays"] as? List<String> ?: emptyList()
                val days = dayNames.mapNotNull { dayName ->
                    try { DayOfWeek.valueOf(dayName) } catch (e: Exception) { null }
                }.toSet()
                DayOfWeekCondition(days)
            }
            else -> throw IllegalArgumentException("Unknown condition type: $type")
        }
    }
}

/**
 * Custom Moshi adapter for RuleAction sealed interface.
 * Currently only handles BlastAction but structured for future action types.
 */
private class RuleActionAdapter {
    @com.squareup.moshi.ToJson
    fun toJson(action: RuleAction): Map<String, Any?> {
        return when (action) {
            is BlastAction -> mapOf(
                "type" to "blast",
                "clipId" to action.clipId,
                "useDefaultClip" to action.useDefaultClip
            )
        }
    }

    @com.squareup.moshi.FromJson
    fun fromJson(json: Map<String, Any?>): RuleAction {
        return when (val type = json["type"] as? String) {
            "blast" -> BlastAction(
                clipId = json["clipId"] as? String,
                useDefaultClip = json["useDefaultClip"] as? Boolean ?: false
            )
            else -> throw IllegalArgumentException("Unknown action type: $type")
        }
    }
}
