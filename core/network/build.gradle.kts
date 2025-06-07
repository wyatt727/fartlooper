plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.kotlin.android)
    // MODERN DEPENDENCIES: Re-enabling Hilt with UPnPCast and jMDNS libraries
    alias(libs.plugins.kotlin.kapt)
    alias(libs.plugins.hilt)
}

android {
    namespace = "com.wobbz.fartloop.core.network"
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
}

dependencies {
    implementation(libs.androidx.core)

    // UPNP/MDNS LIBRARY RESOLUTION: Using modern alternatives to deprecated Cling
    // jMDNS is available on Maven Central and actively maintained
    implementation(libs.jmdns)

    // UPnP Options: Choose ONE of the following based on project needs
    // Option A: UPnPCast - Modern, lightweight, Kotlin-based (RECOMMENDED)
    implementation(libs.upnpcast)

    // Option B: DM-UPnP - Actively maintained Cling fork with security fixes
    // implementation(libs.dm-upnp-android)

    // LEGACY CLING: Original deprecated libraries (fallback only)
    // implementation(libs.cling.core)
    // implementation(libs.cling.support)

    implementation(libs.timber)

    // HILT DEPENDENCY INJECTION: Re-enabled with modern libraries
    implementation(libs.hilt.android)
    kapt(libs.hilt.compiler)

    testImplementation(libs.junit)
    androidTestImplementation(libs.androidx.junit)
    androidTestImplementation(libs.androidx.espresso.core)
}
