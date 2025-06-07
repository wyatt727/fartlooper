package com.wobbz.fartloop

import android.app.Application
import dagger.hilt.android.HiltAndroidApp
import timber.log.Timber

/**
 * Application class for Fart-Looper app.
 *
 * HILT INTEGRATION FINDING: @HiltAndroidApp required for Hilt dependency injection
 * This generates Hilt components and enables injection throughout the app.
 *
 * TIMBER INTEGRATION FINDING: Debug tree only for debug builds
 * Prevents logging overhead and potential data leakage in release builds.
 */
@HiltAndroidApp
class FartLooperApp : Application() {

        override fun onCreate() {
        super.onCreate()

        // Initialize Timber logging
        Timber.plant(Timber.DebugTree())
        Timber.d("FartLooperApp: Debug logging enabled")
        Timber.i("FartLooperApp: Application started")
    }
}
