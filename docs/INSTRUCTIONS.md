# INSTRUCTIONS.md  
**Fart-Looper 1.0 – End-to-End Build & UX Playbook**  
*(Kotlin 1.9 · Jetpack Compose · Hilt · Coroutines · WorkManager · NanoHTTPD · Cling · mDNS-java)*  

This single document walks one developer from an empty folder to a **fully working, non-root “Network-Audio Lab.”**  
It already ships a polished Compose UI, rule builder, exhaustive device discovery (SSDP + mDNS + port scan), hot-swap media, live metrics HUD, built-in simulator, and ADB façade.  
Security hardening and store release are **deliberately out of scope**.

---

## 0 · Workstation Bootstrap 🛠️  

| Requirement | Command / Download | Verify |
|-------------|-------------------|--------|
| Temurin JDK 17 | <https://adoptium.net> | `java -version` → 17.\* |
| Android Studio Koala/Hedgehog | SDK 34, Emulator | IDE shows SDK 34 |
| Git | `brew install git` / `choco install git` | `git --version` |
| ADB CLI | comes with Studio | `adb devices` |

```bash
git init fart-looper && cd fart-looper
echo -e ".idea/\nbuild/\nlocal.properties\n*.iml\nartifacts/" > .gitignore
git submodule add https://github.com/NanoHttpd/nanohttpd    vendor/nanohttpd
git submodule add https://github.com/4thline/cling           vendor/cling
git submodule add https://github.com/hashicorp/mdns-java     vendor/mdns
```

---

## 1 · Project Skeleton 📂

### 1.1 Create modules

| Gradle module      | Purpose                           |
| ------------------ | --------------------------------- |
| `:app`             | entry APK, Hilt graph root        |
| `:design`          | Compose theme, typography, colors |
| `:feature:home`    | Home screen, live device list     |
| `:feature:library` | Clip picker (local & URL)         |
| `:feature:rules`   | Visual rule builder               |
| `:core:network`    | Up-stream discovery & control     |
| `:core:media`      | NanoHTTPD wrapper & storage       |

Create **“Empty Activity (Jetpack Compose)”** for `:app` in Android Studio, then add the rest via *New → Module → Android Library (Kotlin)*.

### 1.2 Settings & repository

```kotlin
// settings.gradle.kts
enableFeaturePreview("VERSION_CATALOGS")
dependencyResolutionManagement {
  repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
  repositories { google(); mavenCentral() }
}
include(
  ":app", ":design",
  ":feature:home", ":feature:library", ":feature:rules",
  ":core:network", ":core:media"
)
```

### 1.3 Version catalog

`gradle/libs.versions.toml`

```toml
[versions]
kotlin      = "1.9.22"
composeBom  = "2024.05.00"
hilt        = "2.48"
nanohttpd   = "2.3.1"
cling       = "2.1.2"
mdnsjava    = "3.5.7"
work        = "2.9.0"
timber      = "5.0.1"
moshi       = "1.15.0"
accompanist = "0.35.0"
detekt      = "1.23.5"
ktlint      = "11.6.0"

[libraries]
compose-bom         = { group = "androidx.compose", name = "compose-bom", version.ref = "composeBom" }
androidx-core       = { group = "androidx.core",     name = "core-ktx",     version = "1.13.0" }
material3           = { group = "androidx.compose.material3", name = "material3", version.ref = "composeBom" }
lifecycle-runtime   = { group = "androidx.lifecycle", name = "lifecycle-runtime-ktx", version = "2.8.0" }
work-runtime        = { group = "androidx.work", name = "work-runtime-ktx", version.ref = "work" }
hilt-android        = { group = "com.google.dagger", name = "hilt-android",  version.ref = "hilt" }
hilt-compiler       = { group = "com.google.dagger", name = "hilt-compiler", version.ref = "hilt" }
nanohttpd           = { group = "org.nanohttpd",     name = "nanohttpd",     version.ref = "nanohttpd" }
cling-core          = { group = "org.fourthline.cling", name = "cling-core", version.ref = "cling" }
mdns-java           = { group = "org.multicastdns", name = "mdns-java", version.ref = "mdnsjava" }
timber              = { group = "com.jakewharton.timber", name = "timber", version.ref = "timber" }
moshi-kotlin        = { group = "com.squareup.moshi", name = "moshi-kotlin", version.ref = "moshi" }
waveform            = { group = "com.github.jeziellago", name = "compose-waveform", version = "1.1.0" }
```

### 1.4 Base build file (`:app/build.gradle.kts`)

```kotlin
plugins {
  id("com.android.application")
  kotlin("android")
  kotlin("kapt")
  id("dagger.hilt.android.plugin")
}

android {
  compileSdk = 34
  defaultConfig {
    applicationId = "com.wobbz.fartlooper"
    minSdk = 21;  targetSdk = 34
    versionCode = 1; versionName = "1.0"
  }
  buildTypes { debug { applicationIdSuffix = ".dev" } }
  compileOptions { sourceCompatibility = JavaVersion.VERSION_1_8; targetCompatibility = JavaVersion.VERSION_1_8 }
  kotlinOptions { jvmTarget = "1.8" }
  buildFeatures { compose = true }
  composeOptions { kotlinCompilerExtensionVersion = libs.versions.composeBom.get() }
  packaging { resources.excludes += "/META-INF/{AL2.0,LGPL2.1}" }
}

dependencies {
  implementation(platform(libs.compose.bom))
  implementation(libs.androidx.core)
  implementation(libs.material3)
  implementation(libs.lifecycle.runtime)
  implementation(libs.work.runtime)
  implementation(libs.hilt.android); kapt(libs.hilt.compiler)

  implementation(project(":design"))
  implementation(project(":core:network"))
  implementation(project(":core:media"))
  implementation(project(":feature:home"))
  implementation(project(":feature:library"))
  implementation(project(":feature:rules"))
}
```

Verify:

```bash
./gradlew assembleDebug     # should be green
```

---

## 2 · Design-System 🎨

1. **`design/Theme.kt`** – Material 3 dynamic color (Android 12+) fallback to custom palette (`primary #F44336`, `secondary #FFAB91`).
2. **Typography** – `DisplayLarge` 32sp, `HeadlineMedium` 24sp, `BodyLarge` 16sp.
3. **Shapes** – 16 dp rounded cards & chips, large FAB transforms by Motion Spec.

---

## 3 · Application & DI 🔌

### 3.1 Application class

```kotlin
@HiltAndroidApp
class FartLooperApp : Application() {
  override fun onCreate() {
    super.onCreate()
    if (BuildConfig.DEBUG) Timber.plant(Timber.DebugTree())
  }
}
```

Set in `AndroidManifest.xml`:

```xml
<application
    android:name=".FartLooperApp"
    android:icon="@mipmap/ic_launcher"
    android:roundIcon="@mipmap/ic_launcher_round"
    android:label="Fart-Looper">
```

### 3.2 NetworkModule

```kotlin
@Module @InstallIn(SingletonComponent::class)
object NetworkModule {
  @Provides @Singleton
  fun nanoServer(@ApplicationContext ctx: Context) = HttpServerManager(ctx)

  @Provides @Singleton
  fun upnp(): UpnpService = UpnpServiceImpl(DefaultUpnpServiceConfiguration())

  @Provides @Singleton
  fun mdns() = MulticastDNSService()
}
```

---

## 4 · Core Media (`core:media`) 🎵

### 4.1 StorageUtil.kt

* Responsibilities: copy picked file to `cacheDir/audio/current.mp3`,
  validate URL streams, expose `Flow<MediaSource>`.

```kotlin
sealed interface MediaSource { 
  data class Local(val file: File) : MediaSource
  data class Remote(val url: String) : MediaSource
}
```

### 4.2 HttpServerManager.kt

```kotlin
class HttpServerManager(ctx: Context) : NanoHTTPD(0) { // auto-select port
  private val cacheDir = ctx.cacheDir.resolve("audio").apply { mkdirs() }
  @Volatile private var remoteUrl: String? = null
  val baseUrl: String get() = "http://${InetAddress.getLocalHost().hostAddress}:$listeningPort"

  fun proxyRemote(url: String) { remoteUrl = url }
  override fun serve(session: IHTTPSession): Response = when (session.uri) {
      "/media/current.mp3" -> newFixedLengthResponse(Response.Status.OK, "audio/mpeg",
        FileInputStream(cacheDir.resolve("current.mp3")), cacheDir.resolve("current.mp3").length())
      "/media/stream"      -> URL(remoteUrl).openStream().let { inp ->
        newChunkedResponse(Response.Status.OK, "audio/mpeg", inp)
      }
      else -> newFixedLengthResponse(Response.Status.NOT_FOUND, "text/plain", "404")
  }
}
```

---

## 5 · Core Network (`core:network`) 🌐

### 5.1 Discovery spectrum

```
80 443 5000 554
7000 7100
8008 8009 8010 8043 8060 8090-8099
8200-8205
8873
9000 9001
10000-10010
1400-1410
49152-49170
50002
5353
```

### 5.2 Discoverers

```kotlin
interface DeviceDiscoverer { suspend fun discover(timeout: Long): Flow<UpnpDevice> }
```

* **SsdpDiscoverer** – uses Cling `ControlPoint.search()`
* **MdnsDiscoverer** – queries `_googlecast._tcp.local`, `_airplay._tcp.local`, `_dlna._tcp.local`, `_raop._tcp.local`.
* **PortScanDiscoverer** – coroutine `withContext(Dispatchers.IO)` pinging the port list (above) on IPs from ARP/neigh; if any `/description.xml|device.xml` contains `<serviceType>.*AVTransport.*` then emit.

`DiscoveryBus.discoverAll(timeout)` merges flows via `channelFlow`.

### 5.3 Control client

```kotlin
suspend fun UpnpDevice.push(mediaUrl: String, upnp: UpnpService) {
  val service = findService("urn:schemas-upnp-org:service:AVTransport:1") ?: return
  val setUri = ActionInvocation(service.getAction("SetAVTransportURI")).apply {
     setInput("InstanceID", 0); setInput("CurrentURI", mediaUrl); setInput("CurrentURIMetaData", "")
  }
  upnp.controlPoint.execute(ActionCallback(setUri))
  delay(200)
  val play = ActionInvocation(service.getAction("Play")).apply {
     setInput("InstanceID", 0); setInput("Speed", "1")
  }
  upnp.controlPoint.execute(ActionCallback(play))
}
```

---

## 6 · Feature: Library 📚

* Pick file via `rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument)`.
* Enter URL via `OutlinedTextField` + “Load” button (HEAD validation).
* Display selection list with `ClipThumbnail` (waveform preview from `compose-waveform`).
* Persist selection in `DataStore` (`dataStore<Preferences>`).

---

## 7 · Feature: Rules ⚙️

Visual **if-then** builder:

| UI widget            | DSL output           |
| -------------------- | -------------------- |
| Regex SSID TextField | `ssid =~ /office.*/` |
| TimeRangeSlider      | `hour in 9..17`      |
| Day Chips (Mon-Sun)  | `dow in [1,2,3,4,5]` |
| Clip dropdown        | `clip = "fart.mp3"`  |

Rules saved to DataStore JSON array; `RuleEvaluator.evaluate()` determines auto-run.

---

## 8 · Feature: Home & UX flow 🏠

```
HomeScreen
 ├─ LazyColumn(DeviceChip × n)
 ├─ MetricsOverlay (expandable)
 └─ Large FAB “BLAST”
```

**When FAB pressed** ↴

1. FAB morphs (Compose Motion) into bottom sheet.
2. Sheet stages: *HTTP server → Discovery → Blasting → Success.*
3. Metrics HUD updates bars (HTTP startup ms, discovery RTT, SOAP mean) and pie (success / fail).
4. After finish, sheet collapses; list chips animate green for success, red for fail.

---

## 9 · Background Service 🔊

`BlastService : LifecycleService`

```kotlin
override fun onStartCommand(i: Intent?, f: Int, id: Int): Int = START_REDELIVER_INTENT
```

1. Reads `MediaSource` from DataStore.
2. Starts `HttpServerManager` (`proxyRemote()` if URL).
3. Collects devices via `DiscoveryBus.discoverAll(userTimeout)`.
4. `flatMapMerge(concurrency = userParallelism)` each device → `push()`.
5. Updates notification (low importance).
6. On completion: stop foreground, broadcast final Metrics.

`DiscoveryWorker` (WorkManager) re-runs if Doze kills service.

---

## 10 · Connectivity & ADB 🔗

`NetworkCallbackUtil` registers for Wi-Fi; on `onAvailable()` if `RuleEvaluator.shouldRun()` start service.

ADB one-liner:

```bash
adb shell am startservice \
  -n com.wobbz.fartlooper/.service.BlastService \
  -a com.wobbz.fartlooper.ACTION_RUN \
  --es CLIP_URL "https://example.com/boom.mp3"
```

---

## 11 · Built-in Simulator 📺

`SimulatedRendererService` (devFlavor) starts after app launch:

* NanoHTTPD on `127.0.0.1:1901`
* Serves `/description.xml` with minimal AVTransport.
* Logs any SOAP; always returns success XML.

Integration tests rely on simulator.

---

## 12 · Quality Gates 🔍

Install **Ktlint** & **Detekt** plugins in root build.

```kotlin
tasks.named("check") { dependsOn("ktlintCheck", "detekt") }
```

UI tests with Compose Test API:

| Test class            | Scenario                                       |
| --------------------- | ---------------------------------------------- |
| `RunNowSuccessTest`   | tap FAB, wait, assert chips green              |
| `RuleBuilderSaveTest` | create rule, reload, rule persists             |
| `HotSwapWhileRunning` | switch clip mid-blast, ensure new bytes served |

Add accessibility checks:

```kotlin
AccessibilityChecks.enable().setRunChecksFromRootView(true)
```

---

## 13 · CI Pipeline ⚙️

`.github/workflows/ci.yml`

```yaml
name: Android CI
on: [push, pull_request]

jobs:
  build:
    runs-on: ubuntu-latest
    steps:
    - uses: actions/checkout@v4
    - uses: actions/setup-java@v3
      with: distribution: 'temurin'
            java-version: '17'
    - uses: gradle/gradle-build-action@v2
    - run: |
        ./gradlew lint ktlintCheck detekt \
                 testDebugUnitTest \
                 connectedDebugAndroidTest \
                 assembleDebug
    - uses: actions/upload-artifact@v4
      with: name: app-debug
            path: app/build/outputs/apk/debug/app-debug.apk
```

Cache key: `${{ hashFiles('**/gradle.lockfile') }}`.

---

## 14 · In-App Help & Docs 📑

* **“?” FAB** on bottom-nav opens Markdown rendered by `com.mikepenz:markdown-view`.
* `/docs/architecture.md` – full flow diagram (PlantUML).
* `/CHANGELOG.md` – start at `1.0-alpha`.

---

## 15 · First Run 🚀

```bash
./gradlew installDebug
adb shell am start -n com.example.fartlooper/.MainActivity
```

1. **Pick** default *fart.mp3* or select your own.
2. **Tap BLAST** – watch bottom sheet animate through stages.
3. Enjoy simultaneous playback on every Sonos, Chromecast, DLNA box, Yamaha AVR, LG TV, etc.

---

### Done 🎉

You now have a **self-contained 1.0 app** featuring:

* Exhaustive SSDP, mDNS, and port-scan discovery (35+ ports inc. Sonos 1400-1410).
* File-picker **or** stream-URL input with hot-swap.
* Rule engine powered by a visual builder (no hand JSON).
* Compose UI with motion, metrics, accessibility & haptics.
* Built-in renderer simulator plus ADB programmable façade.

No root, no external analytics—just a slick network-audio playground ready for further experiments.
