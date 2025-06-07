package com.wobbz.fartloop.core.network

import android.content.Context
import android.content.Intent
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.net.wifi.WifiManager
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import timber.log.Timber
import javax.inject.Inject
import javax.inject.Singleton

/**
 * NetworkCallbackUtil monitors Wi-Fi connectivity changes and triggers automated blasts
 * based on rules evaluation.
 *
 * CIRCULAR DEPENDENCY RESOLUTION: Moved from app module to core:network to break
 * circular dependency between app and feature:rules modules. Rules module needs
 * NetworkCallbackUtil and RuleEvaluator interfaces, but app module depends on rules.
 *
 * This placement in core:network is logical since network monitoring is core infrastructure
 * that multiple modules need to access.
 *
 * Key behaviors observed during development:
 * - Android network callbacks can fire multiple times during connection establishment
 * - SSID information requires location permissions on API 26+
 * - Network transitions (mobile->wifi) can have brief "no network" states
 * - Some devices report transient network availability before full connectivity
 *
 * Architecture decision: Use debouncing to avoid triggering multiple blasts during
 * network handoffs, and integrate with a stub rule evaluator until Team B completes
 * the visual rule builder.
 *
 * NETWORK RECOVERY ENHANCEMENT: Added auto-recovery for interrupted blast operations
 * Network transitions during active blasts can cause failures. This implementation provides:
 * - Detection of network changes during active blast operations
 * - Automatic retry mechanisms with exponential backoff
 * - Discovery cache invalidation when network signature changes
 * - Graceful degradation when network becomes unreliable
 *
 * Recovery strategies based on testing findings:
 * - Wi-Fi reconnection during blast: Pause 3s, retry discovery, resume blast
 * - Network interface change: Invalidate discovery cache, restart HTTP server
 * - Repeated disconnections: Exponential backoff (3s, 6s, 12s max retry delay)
 * - Complete network loss: Graceful abort with retry when connectivity restored
 */
@Singleton
class NetworkCallbackUtil @Inject constructor(
    @ApplicationContext private val context: Context,
    private val ruleEvaluator: RuleEvaluator,
) : DefaultLifecycleObserver {

    private val connectivityManager = context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
    private val wifiManager = context.getSystemService(Context.WIFI_SERVICE) as WifiManager

    // Scope for managing network callback coroutines
    private val networkScope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

    // Track current network state
    private val _networkState = MutableStateFlow<NetworkState>(NetworkState.Disconnected)
    val networkState: StateFlow<NetworkState> = _networkState.asStateFlow()

    private var networkCallback: ConnectivityManager.NetworkCallback? = null

    // Debouncing to prevent multiple triggers during network transitions
    private var lastNetworkChangeMs = 0L
    private val debounceDelayMs = 2000L // 2 seconds to handle rapid network changes

    // Auto-recovery state tracking
    private var isBlastActive = false
    private var lastKnownNetworkSignature: String? = null
    private var networkRecoveryAttempts = 0
    private val maxRecoveryAttempts = 3
    private var lastRecoveryAttemptMs = 0L

    sealed class NetworkState {
        object Disconnected : NetworkState()
        object Mobile : NetworkState()
        data class WiFi(val ssid: String?) : NetworkState()
    }

    override fun onStart(owner: LifecycleOwner) {
        super.onStart(owner)
        startNetworkMonitoring()
    }

    override fun onStop(owner: LifecycleOwner) {
        super.onStop(owner)
        stopNetworkMonitoring()
    }

    /**
     * Start monitoring network connectivity changes.
     * Registers network callback for Wi-Fi networks specifically.
     */
    private fun startNetworkMonitoring() {
        Timber.d("Starting network connectivity monitoring")

        val networkRequest = NetworkRequest.Builder()
            .addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
            .addCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)
            .addTransportType(NetworkCapabilities.TRANSPORT_WIFI)
            .build()

        networkCallback = object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) {
                Timber.i("Network available: $network")
                handleNetworkAvailable(network)
            }

            override fun onLost(network: Network) {
                Timber.i("Network lost: $network")
                handleNetworkLost(network)
            }

            override fun onCapabilitiesChanged(network: Network, networkCapabilities: NetworkCapabilities) {
                Timber.d("Network capabilities changed: $network, capabilities: $networkCapabilities")
                // Re-evaluate in case transport type changed
                handleNetworkAvailable(network)
            }
        }

        connectivityManager.registerNetworkCallback(networkRequest, networkCallback!!)

        // Also check current network state immediately
        checkCurrentNetworkState()
    }

    private fun stopNetworkMonitoring() {
        networkCallback?.let { callback ->
            Timber.d("Stopping network connectivity monitoring")
            connectivityManager.unregisterNetworkCallback(callback)
            networkCallback = null
        }
    }

    /**
     * Handle network becoming available.
     * Uses debouncing to avoid rapid triggers during network handoffs.
     */
    private fun handleNetworkAvailable(network: Network) {
        val currentTimeMs = System.currentTimeMillis()
        lastNetworkChangeMs = currentTimeMs

        networkScope.launch {
            // Debounce network changes to avoid rapid-fire triggers
            delay(debounceDelayMs)

            // Only proceed if this is still the most recent network change
            if (lastNetworkChangeMs == currentTimeMs) {
                val networkCapabilities = connectivityManager.getNetworkCapabilities(network)
                networkCapabilities?.let { capabilities ->
                    updateNetworkState(capabilities)

                    // NETWORK RECOVERY: Check if network signature changed during blast
                    val newNetworkSignature = generateNetworkSignature(capabilities)
                    if (isBlastActive && lastKnownNetworkSignature != null &&
                        lastKnownNetworkSignature != newNetworkSignature
                    ) {
                        Timber.w("Network signature changed during blast - triggering recovery")
                        handleBlastNetworkInterruption("network_change", newNetworkSignature)
                    } else {
                        lastKnownNetworkSignature = newNetworkSignature
                    }

                    // Check if we should trigger an automated blast
                    if (!isBlastActive && ruleEvaluator.shouldAutoBlast(getCurrentNetworkInfo())) {
                        triggerAutomatedBlast()
                    }
                }
            }
        }
    }

    private fun handleNetworkLost(network: Network) {
        Timber.i("Network connection lost")
        _networkState.value = NetworkState.Disconnected

        // NETWORK RECOVERY: Handle network loss during active blasts
        if (isBlastActive) {
            Timber.w("Network lost during active blast - preparing for recovery")
            handleBlastNetworkInterruption("network_lost")
        }
    }

    /**
     * Update internal network state based on current capabilities.
     */
    private fun updateNetworkState(capabilities: NetworkCapabilities) {
        val newState = when {
            capabilities.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) -> {
                val ssid = getCurrentWifiSsid()
                Timber.i("Connected to Wi-Fi network: $ssid")
                NetworkState.WiFi(ssid)
            }
            capabilities.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) -> {
                Timber.i("Connected to cellular network")
                NetworkState.Mobile
            }
            else -> {
                Timber.i("Connected to unknown network type")
                NetworkState.Disconnected
            }
        }

        _networkState.value = newState
    }

    /**
     * Get current Wi-Fi SSID.
     * Note: Requires location permissions on API 26+ to get actual SSID.
     * Falls back to generic identifier if permissions not granted.
     */
    private fun getCurrentWifiSsid(): String? {
        return try {
            val wifiInfo = wifiManager.connectionInfo
            val ssid = wifiInfo?.ssid

            // Android wraps SSID in quotes, remove them
            when {
                ssid == null -> null
                ssid == "<unknown ssid>" -> "unknown" // No location permission
                ssid.startsWith("\"") && ssid.endsWith("\"") -> ssid.substring(1, ssid.length - 1)
                else -> ssid
            }
        } catch (e: Exception) {
            Timber.w(e, "Failed to get current Wi-Fi SSID")
            null
        }
    }

    private fun checkCurrentNetworkState() {
        val activeNetwork = connectivityManager.activeNetwork
        if (activeNetwork != null) {
            val capabilities = connectivityManager.getNetworkCapabilities(activeNetwork)
            capabilities?.let { updateNetworkState(it) }
        }
    }

    /**
     * Get current network info for rule evaluation.
     */
    private fun getCurrentNetworkInfo(): NetworkInfo {
        return when (val state = _networkState.value) {
            is NetworkState.WiFi -> NetworkInfo.WiFi(state.ssid ?: "unknown")
            is NetworkState.Mobile -> NetworkInfo.Mobile
            is NetworkState.Disconnected -> NetworkInfo.Disconnected
        }
    }

    /**
     * Trigger an automated blast by starting BlastService.
     * Uses the current media source from DataStore.
     *
     * BLAST SERVICE INTEGRATION: Uses fully qualified class name to avoid
     * importing BlastService directly, which would create circular dependencies.
     * The service is started via Intent using string-based class lookup.
     */
    private fun triggerAutomatedBlast() {
        Timber.i("Triggering automated blast based on rule evaluation")

        try {
            val intent = Intent().apply {
                setClassName(context, "com.wobbz.fartloop.service.BlastService")
                action = "com.wobbz.fartloop.ACTION_AUTO_BLAST"
                putExtra("EXTRA_TRIGGER_REASON", "network_rule_match")
            }

            context.startForegroundService(intent)
        } catch (e: Exception) {
            Timber.e(e, "Failed to start automated blast service")
        }
    }

    /**
     * Network information for rule evaluation.
     */
    sealed class NetworkInfo {
        object Disconnected : NetworkInfo()
        object Mobile : NetworkInfo()
        data class WiFi(val ssid: String) : NetworkInfo()
    }

    /**
     * Notify NetworkCallbackUtil that a blast operation has started.
     * This enables network recovery monitoring during active blasts.
     *
     * BLAST LIFECYCLE INTEGRATION: Critical for recovery functionality
     * Must be called when BlastService starts to enable network monitoring.
     */
    fun notifyBlastStarted() {
        isBlastActive = true
        networkRecoveryAttempts = 0
        lastKnownNetworkSignature = getCurrentNetworkSignature()
        Timber.i("Blast started - network recovery monitoring enabled")
    }

    /**
     * Notify NetworkCallbackUtil that a blast operation has completed.
     * Disables network recovery monitoring.
     */
    fun notifyBlastCompleted() {
        isBlastActive = false
        networkRecoveryAttempts = 0
        lastKnownNetworkSignature = null
        Timber.i("Blast completed - network recovery monitoring disabled")
    }

    /**
     * Handle network interruption during active blast operations.
     * Implements exponential backoff retry strategy with automatic recovery.
     *
     * RECOVERY STRATEGY FINDINGS:
     * - Immediate retry often fails due to network instability
     * - 3-second delay allows network to stabilize after transitions
     * - Exponential backoff prevents overwhelming unstable networks
     * - Discovery cache invalidation necessary after network changes
     *
     * @param reason Reason for the interruption (for logging/debugging)
     * @param newNetworkSignature New network signature if available
     */
    private fun handleBlastNetworkInterruption(reason: String, newNetworkSignature: String? = null) {
        if (networkRecoveryAttempts >= maxRecoveryAttempts) {
            Timber.e("Max network recovery attempts reached ($maxRecoveryAttempts) - aborting blast")
            sendBlastRecoveryIntent("recovery_failed", reason)
            return
        }

        networkRecoveryAttempts++
        val currentTime = System.currentTimeMillis()

        // Exponential backoff: 3s, 6s, 12s
        val retryDelayMs = 3000L * (1 shl (networkRecoveryAttempts - 1))
        lastRecoveryAttemptMs = currentTime

        Timber.w(
            "Network interruption during blast (reason: $reason, attempt: $networkRecoveryAttempts/$maxRecoveryAttempts) - retrying in ${retryDelayMs}ms",
        )

        networkScope.launch {
            delay(retryDelayMs)

            // Check if network is available before attempting recovery
            val activeNetwork = connectivityManager.activeNetwork
            val networkCapabilities = activeNetwork?.let { connectivityManager.getNetworkCapabilities(it) }

            if (networkCapabilities?.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED) == true) {
                Timber.i("Network recovered - attempting blast recovery")

                // Update network signature
                newNetworkSignature?.let { lastKnownNetworkSignature = it }

                // Send recovery intent to BlastService
                sendBlastRecoveryIntent("network_recovered", reason)
            } else {
                Timber.w("Network not yet stable after recovery delay - will retry on next network callback")
            }
        }
    }

    /**
     * Send intent to BlastService for network recovery handling.
     *
     * @param recoveryAction Action to take (network_recovered, recovery_failed)
     * @param originalReason Original reason for the interruption
     */
    private fun sendBlastRecoveryIntent(recoveryAction: String, originalReason: String) {
        try {
            val intent = Intent().apply {
                setClassName(context, "com.wobbz.fartloop.service.BlastService")
                action = "com.wobbz.fartloop.ACTION_NETWORK_RECOVERY"
                putExtra("EXTRA_RECOVERY_ACTION", recoveryAction)
                putExtra("EXTRA_RECOVERY_REASON", originalReason)
                putExtra("EXTRA_RECOVERY_ATTEMPT", networkRecoveryAttempts)
            }

            context.startService(intent)
        } catch (e: Exception) {
            Timber.e(e, "Failed to send blast recovery intent")
        }
    }

    /**
     * Generate a network signature for detecting significant network changes.
     *
     * NETWORK SIGNATURE FINDING: Different elements indicate different recovery needs
     * - SSID change: Complete discovery cache invalidation required
     * - IP subnet change: Discovery cache can be partially preserved
     * - Interface change: HTTP server restart may be needed
     *
     * @param capabilities Current network capabilities
     * @return Network signature string for comparison
     */
    private fun generateNetworkSignature(capabilities: NetworkCapabilities): String {
        val transportTypes = mutableListOf<String>()

        if (capabilities.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)) {
            transportTypes.add("wifi")
        }
        if (capabilities.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR)) {
            transportTypes.add("cellular")
        }
        if (capabilities.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET)) {
            transportTypes.add("ethernet")
        }

        // Include SSID for Wi-Fi networks to detect network changes
        val ssid = if (capabilities.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)) {
            getCurrentWifiSsid() ?: "unknown"
        } else {
            ""
        }

        // Include basic capability indicators
        val hasInternet = capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
        val hasValidated = capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)

        return "${transportTypes.joinToString(",")}|$ssid|$hasInternet|$hasValidated"
    }

    /**
     * Get current network signature.
     */
    private fun getCurrentNetworkSignature(): String? {
        val activeNetwork = connectivityManager.activeNetwork
        val capabilities = activeNetwork?.let { connectivityManager.getNetworkCapabilities(it) }
        return capabilities?.let { generateNetworkSignature(it) }
    }
}

/**
 * Rule evaluator interface for determining when to auto-blast.
 *
 * CIRCULAR DEPENDENCY RESOLUTION: Moved from app module to core:network
 * along with NetworkCallbackUtil to break circular dependency between
 * app and feature:rules modules.
 */
interface RuleEvaluator {
    /**
     * Evaluate if an automated blast should be triggered based on current network state.
     */
    fun shouldAutoBlast(networkInfo: NetworkCallbackUtil.NetworkInfo): Boolean
}

/**
 * Stub implementation of RuleEvaluator.
 * Always returns false until real rule system is implemented by Team B.
 */
@Singleton
class StubRuleEvaluator @Inject constructor() : RuleEvaluator {

    override fun shouldAutoBlast(networkInfo: NetworkCallbackUtil.NetworkInfo): Boolean {
        // Stub implementation - always return false for now
        // Team B will replace this with actual rule evaluation logic
        Timber.d("Rule evaluation (stub): $networkInfo -> no auto-blast")
        return false
    }
}
