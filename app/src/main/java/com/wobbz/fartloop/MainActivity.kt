package com.wobbz.fartloop

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import com.wobbz.fartloop.core.network.NetworkCallbackUtil
import com.wobbz.fartloop.navigation.FartLooperNavigation
import dagger.hilt.android.AndroidEntryPoint
import timber.log.Timber
import javax.inject.Inject

/**
 * Main activity for Fart-Looper app.
 *
 * Architecture Finding: MainActivity serves as the app's entry point and lifecycle coordinator.
 * NetworkCallbackUtil is registered as lifecycle observer to automatically monitor connectivity.
 * Navigation is delegated to FartLooperNavigation for clean separation of concerns.
 */
@AndroidEntryPoint
class MainActivity : ComponentActivity() {

    @Inject
    lateinit var networkCallbackUtil: NetworkCallbackUtil

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        Timber.d("MainActivity: onCreate - setting up navigation and connectivity monitoring")

        // Register NetworkCallbackUtil as lifecycle observer to monitor connectivity
        // This enables automatic rule-based blasting when network conditions change
        lifecycle.addObserver(networkCallbackUtil)

        setContent {
            // Main navigation handles theme, bottom nav, and all screen routing
            FartLooperNavigation()
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        Timber.d("MainActivity: onDestroy - cleaning up")
    }
}
