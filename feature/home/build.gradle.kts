plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.kapt)
    alias(libs.plugins.hilt)
}

android {
    namespace = "com.wobbz.fartloop.feature.home"
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
    implementation("androidx.localbroadcastmanager:localbroadcastmanager:1.1.0")
    implementation(libs.hilt.android)
    implementation(libs.hilt.navigation.compose)
    implementation(libs.timber)
    kapt(libs.hilt.compiler)

    // MISSING DEPENDENCY FINDING: Material Icons Extended required for all device and UI icons
    // Home module uses BugReport, CloudUpload, PlayCircle, Speaker, CastConnected, VolumeUp,
    // Wifi, Tv, Router, QuestionMark, ExpandMore, EmojiEvents, TrendingUp, Speed, BarChart, HourglassTop
    implementation("androidx.compose.material:material-icons-extended:1.6.0")

    // MISSING DEPENDENCY FINDING: Compose Foundation required for advanced UI components and layouts
    implementation("androidx.compose.foundation:foundation:1.6.0")

    // MISSING DEPENDENCY FINDING: ViewModel Compose for state management integration
    // HomeScreen and components require ViewModel integration for proper state handling
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.7.0")

    implementation(project(":design"))
    implementation(project(":core:blast"))
    implementation(project(":core:network"))
    implementation(project(":core:media"))

    testImplementation(libs.junit)
    androidTestImplementation(libs.androidx.junit)
    androidTestImplementation(libs.androidx.espresso.core)
    androidTestImplementation(platform(libs.compose.bom))
    androidTestImplementation(libs.compose.ui.test.junit4)
}
