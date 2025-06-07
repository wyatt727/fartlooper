# Cross-Team Build Checklist  

All three teams depend on a common repository skeleton, Gradle configuration, and toolchain.  
Complete every item in **First Tasks** before sprint-starting the A / B / C lists.

---

## 🔑 First Tasks — Repository & Tooling Bootstrap (ALL HANDS)

| # | Task | Outcome |
|---|------|---------|
| ✅ **FT-1** Verify local toolchain<br>▪ Temurin JDK 17<br>▪ Android Studio Koala/Hedgehog (SDK 34)<br>▪ Git ≥ 2.40 | `java -version` → 17.\* `adb version` OK |
| ✅ **FT-2** Create root repo & global ignore | `git init fart-looper`<br>`echo '.idea/\nbuild/\nlocal.properties\n*.iml\nartifacts/' > .gitignore` |
| ✅ **FT-3** Lay down **directory structure** *(empty `README.md` in each to commit)* | ```fart-looper/   ├── gradle/ (wrapper)   ├── app/   ├── design/   ├── core/ │   ├── media/ │   ├── network/ │   └── simulator/   └── feature/       ├── home/       ├── library/       └── rules/ ``` |
| ✅ **FT-4** Add Gradle wrapper (`gradlew`, `gradlew.bat`, `/gradle/wrapper/gradle-wrapper.properties`) | Wrapper files at repo root |
| ✅ **FT-5** Copy **settings.gradle.kts** with module includes | `include(":app", ":design", ":core:media", ":core:network", ":core:simulator", ":feature:home", ":feature:library", ":feature:rules")` |
| ✅ **FT-6** Commit **version catalog** `gradle/libs.versions.toml` (locked libs) | Catalog file checked-in |
| ✅ **FT-7** Scaffold minimal `build.gradle.kts` for each module<br>• `plugins { kotlin("android") }` for libs<br>• `com.android.application` for `:app` | All modules sync in Android Studio |
| ⚠️ **FT-8** Add submodules **NanoHTTPD, Cling, mdns-java** under `/vendor`; record SHAs | Using Maven Central versions instead |
| ✅ **FT-9** CI stub: `.github/workflows/ci.yml` running `./gradlew help` | Green pipeline proves wrapper + catalog |
| ✅ **FT-10** Push to remote (GitHub/GitLab) so each team can branch | Origin repo online |

*After **FT-10** every module opens in Android Studio and compiles (even though empty).  
Teams A, B, C may now parallelise.*

---

## 🅰 Team A — Core Platform & Services

| # | Task |
|---|------|
| ⬜ A-1 ▸ Import libraries into catalog deps; extend module `build.gradle.kts` (`libs.nanohttpd`, `libs.clingCore`, `libs.mdnsjava`, Hilt, Work, Timber) |
| ⬜ A-2 ▸ Implement `StorageUtil` & `MediaSource`, unit test |
| ⬜ A-3 ▸ Build `HttpServerManager` (auto-port NanoHTTPD) |
| ⬜ A-4 ▸ Code discoverers: `SsdpDiscoverer`, `MdnsDiscoverer`, `PortScanDiscoverer` (full port list) |
| ⬜ A-5 ▸ Merge flows in `DiscoveryBus` |
| ⬜ A-6 ▸ Write `UpnpControlClient.pushClip()` |
| ⬜ A-7 ▸ Assemble `BlastService`, metrics struct |
| ⬜ A-8 ▸ Build `NetworkCallbackUtil` + rule trigger hook |
| ⬜ A-9 ▸ Dev-flavor `SimulatedRendererService` |
| ⬜ A-10 ▸ Unit tests for media + network layers |

---

## 🅱 Team B — UI / UX & Rules

| # | Task |
|---|------|
| ⬜ B-1 ▸ Create `design` theme (colors/typography/shapes) |
| ⬜ B-2 ▸ Compose **HomeScreen** (DeviceChip, MetricsOverlay, BLAST FAB) |
| ⬜ B-3 ▸ Implement FAB→progress bottom-sheet Motion |
| ⬜ B-4 ▸ Compose **LibraryScreen** (file picker, URL dialog, ClipThumbnail) |
| ⬜ B-5 ▸ Compose **RuleBuilderScreen** (regex, time, DOW, clip) |
| ⬜ B-6 ▸ Serialize visual builder → DSL + DataStore |
| ⬜ B-7 ▸ Wire BottomNav (`Home · Library · Rules · Settings`) |
| ⬜ B-8 ▸ Compose **SettingsScreen** (timeouts, concurrency, TTL) |
| ⬜ B-9 ▸ Integrate MetricsOverlay bar/pie charts |
| ⬜ B-10 ▸ Add LogConsoleDialog (dev) |
| ⬜ B-11 ▸ Accessibility & haptic feedback pass |
| ⬜ B-12 ▸ Compose UI tests (RunNowSuccess, RuleBuilderSave, HotSwap) |

---

## 🅲 Team C — Dev Ops / CI / QA

| # | Task |
|---|------|
| ⬜ C-1 ▸ Integrate ktlint-gradle + baseline config |
| ⬜ C-2 ▸ Integrate Detekt + ruleset; wire into `check` |
| ⬜ C-3 ▸ Expand CI workflow: lint → ktlint → detekt → unit tests |
| ⬜ C-4 ▸ Add Headless AVD matrix (API 33, 34) → run Compose UI & instrumentation |
| ⬜ C-5 ▸ Cache Gradle home keyed on `gradle.lockfile` |
| ⬜ C-6 ▸ Upload `app-debug.apk` as artefact |
| ⬜ C-7 ▸ Create manual QA checklist (cold-start ≤ 7 s, rotate, a11y) |
| ⬜ C-8 ▸ Wire Espresso-Accessibility into pipeline |
| ⬜ C-9 ▸ Generate docs: `/docs/architecture.md`, `/docs/metrics.md`, `/CHANGELOG.md` |
| ⬜ C-10 ▸ Nightly simulator E2E + metrics JSON attachment |

---

### ✳️ Dependency Matrix

* **Team A A-6** needs Team B’s rule DSL only for final hook—can stub until B-6.
* **Team B** requires `StorageUtil` & `MediaSource` (A-2) to preview clips.
* **Team C** can finish tasks C-1 → C-6 immediately after First Tasks; C-8 waits for UI layer.

Mark each check-box ✅ when complete
