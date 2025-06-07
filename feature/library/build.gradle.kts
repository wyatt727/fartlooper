plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.kapt)
    alias(libs.plugins.hilt)
}

android {
    namespace = "com.wobbz.fartloop.feature.library"
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
    implementation(libs.timber)
    kapt(libs.hilt.compiler)

    // MISSING DEPENDENCY FINDING: Material Icons Extended required for Folder, Link, Error, LibraryMusic icons
    // These icons are referenced throughout LibraryScreen and ClipThumbnail components
    // Without this dependency, all Material icon references fail compilation
    implementation("androidx.compose.material:material-icons-extended:1.6.0")

    // MISSING DEPENDENCY FINDING: Compose Foundation required for LazyListState and advanced UI components
    // LazyColumn, LazyListState, and foundation APIs are used extensively in LibraryScreen
    implementation("androidx.compose.foundation:foundation:1.6.0")

    // MISSING DEPENDENCY FINDING: Activity Compose for result launchers and integration
    // Storage Access Framework integration requires activity result launcher support
    implementation("androidx.activity:activity-compose:1.8.2")

    implementation(project(":design"))
    implementation(project(":core:media"))

    testImplementation(libs.junit)
    androidTestImplementation(libs.androidx.junit)
    androidTestImplementation(libs.androidx.espresso.core)
    androidTestImplementation(platform(libs.compose.bom))
    androidTestImplementation(libs.compose.ui.test.junit4)
}
