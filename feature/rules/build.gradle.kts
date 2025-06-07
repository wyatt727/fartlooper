plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.kapt)
    alias(libs.plugins.hilt)
}

android {
    namespace = "com.wobbz.fartloop.feature.rules"
    compileSdk = 34

    defaultConfig {
        minSdk = 21
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        consumerProguardFiles("consumer-rules.pro")
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_1_8
        targetCompatibility = JavaVersion.VERSION_1_8
    }
    kotlinOptions {
        jvmTarget = "1.8"
    }
    buildFeatures {
        compose = true
    }
    composeOptions {
        kotlinCompilerExtensionVersion = "1.5.8"
    }
}

dependencies {
    implementation(platform(libs.compose.bom))
    implementation(libs.androidx.core)
    implementation(libs.androidx.lifecycle.runtime)
    implementation(libs.compose.ui)
    implementation(libs.compose.ui.graphics)
    implementation(libs.compose.ui.tooling.preview)
    implementation(libs.material3)
    implementation(libs.hilt.android)
    implementation(libs.hilt.navigation.compose)
    implementation(libs.datastore.preferences)
    implementation(libs.moshi.kotlin)
    implementation(libs.moshi.adapters)
    implementation(libs.timber)
    kapt(libs.hilt.compiler)

    // MISSING DEPENDENCY FINDING: Material Icons Extended required for Wifi, Schedule, CalendarMonth, VolumeUp icons
    // Rules module uses various Material icons for condition and action representation
    implementation("androidx.compose.material:material-icons-extended:1.6.0")

    // MISSING DEPENDENCY FINDING: Compose Foundation required for advanced UI components
    implementation("androidx.compose.foundation:foundation:1.6.0")

    // MISSING DEPENDENCY FINDING: ViewModel Compose for state management
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.7.0")

    implementation(project(":design"))
    implementation(project(":core:media"))
    // CRITICAL DEPENDENCY FINDING: Rules module requires network module for NetworkCallbackUtil and network state access
    // RealRuleEvaluator depends on NetworkCallbackUtil for Wi-Fi state detection and auto-blast triggering
    implementation(project(":core:network"))

    // CIRCULAR DEPENDENCY RESOLUTION: NetworkCallbackUtil and RuleEvaluator interfaces moved to core:network
    // This eliminates the circular dependency between app and feature:rules modules
    // implementation(project(":app")) // REMOVED - was causing circular dependency

    testImplementation(libs.junit)
    androidTestImplementation(libs.androidx.junit)
    androidTestImplementation(libs.androidx.espresso.core)
    androidTestImplementation(platform(libs.compose.bom))
    androidTestImplementation(libs.compose.ui.test.junit4)
}
