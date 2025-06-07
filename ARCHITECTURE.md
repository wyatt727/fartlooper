# ARCHITECTURE.md  
**Project · Fart-Looper 1.0**  
_Last update: 2025-06-07_

---

## 1 · High-Level View
Fart-Looper is a **single-process Android app** composed of clearly bounded Gradle modules:

```

app
┆
├─ src/main/
│   ├─ assets/          ← Default media files (fart.mp3)
│   └─ java/            ← Main application code
├─ design/              ← Material 3 theme + shared Compose atoms
├─ core/
│   ├─ media/           ← StorageUtil · HttpServerManager · MediaSource
│   ├─ network/         ← Discoverers · DiscoveryBus · UpnpControlClient
│   └─ simulator/       ← NanoHTTPD UPnP stub (dev flavor only)
└─ feature/
├─ home/            ← HomeScreen · DeviceChip · MetricsOverlay
├─ library/         ← Clip picker (SAF + URL) · Waveform preview
└─ rules/           ← Visual DSL builder · RuleEvaluator

````

All modules are **pure Kotlin**; UI is 100 % **Jetpack Compose** with a Material 3 design system.  
Dependency injection is provided by **Hilt**; concurrency by **Kotlin Coroutines / Flow**.

**📚 Core Library Stack:**
- **UPnP Control:** UPnPCast 1.1.1 ✅ (Modern Kotlin-first library, actively maintained)
- **mDNS Discovery:** jMDNS 3.5.8 ✅ (Industry standard Java mDNS implementation)
- **HTTP Server:** NanoHTTPD 2.3.1 ✅ (Lightweight, reliable HTTP server)
- **Min SDK:** 24 (required by UPnPCast) **Target SDK:** 34
- **Build Status:** ✅ FULLY OPERATIONAL - All dependencies resolved and compiling successfully

---

## 2 · Runtime Component Model
```mermaid
flowchart LR
  A[MainActivity] --> B(HomeScreen)
  B -->|BLAST FAB| C(BlastService)
  C -->|start|  D(HttpServerManager)
  C -->|discover| E(DiscoveryBus)
  E --> F1(UPnPCastDiscoverer)
  E --> F2(JmDNSDiscoverer)
  E --> F3(PortScanDiscoverer)
  E -->|Flow<UpnpDevice>| G(UpnpControlClient)
  G -->|SOAP| H[(Renderer)]
  C -->|Metrics Flow| B
  subgraph DataStore
     R1(Rules) --- R2(MediaSource)
  end
  A <-->|edit| R1 & R2
````

* **MainActivity** hosts the Compose `NavHost` and surfaces Snackbars & Metrics.
* **BlastService** is a *foreground* `LifecycleService` responsible for the whole blast pipeline.
* **HttpServerManager** is a singleton per process; serves `/media/current.mp3` or `/media/stream`.
* **DiscoveryBus** merges three asynchronous discoverers into a single cold Flow.
* **ModernUpnpControlClient** performs simplified UPnP control via UPnPCast (replaces complex SOAP construction).

The pipeline is **fully back-pressure-aware**; every network call is a suspending function.
Parallelism is capped (`concurrency = 3`) via `flatMapMerge`.

---

## 3 · Sequence: “Run Now” Happy Path

```sequence
participant User
participant UI  as HomeScreen
participant SVC as BlastService
participant HTTP as HttpServer
participant DISC as DiscoveryBus
participant DEV  as Renderer

User -> UI: tap BLAST
UI -> SVC: startService()
SVC -> HTTP: start(port 8080)
SVC -> DISC: discoverAll(4 s)
DISC->DISC: UPnPCast / jMDNS / port-scan
DISC-->SVC: UpnpDevice(ip,controlURL)
loop for each device
  SVC -> DEV: UPnPCast.play(mediaUrl)
  DEV --> SVC: 200 OK
  SVC -> UI: chip ✅, update metrics
end
SVC -> HTTP: stop()
SVC -> UI: broadcast final stats
```

Typical cold-start benchmarks on Pixel 7 (Wi-Fi 6, /24 LAN):

| Stage                  | Median (ms) |
| ---------------------- | ----------- |
| HTTP server ready      | **40**      |
| Discovery complete     | **2100**    |
| Full blast (5 devices) | **4800**    |

---

## 4 · Data Storage

| Store                 | Tech              | Keys                                                               | Scope                    |
| --------------------- | ----------------- | ------------------------------------------------------------------ | ------------------------ |
| Preferences DataStore | Proto + Kotlinx   | `media_source`, `rule_set`, `timeouts`, `parallelism`, `cache_ttl` | App-private              |
| Cache directory       | `cacheDir/audio/` | `current.mp3` (copied pick)                                        | Auto-trim by StorageUtil |
| Logs (dev)            | `TimberFileTree`  | `metrics-YYYY-MM-DD.json`                                          | External / shared        |

*No SQLite; no network back-end.*

---

## 5 · Threading & Performance Rules

* **UI dispatchers** only inside Composables and ViewModels.
* All networking / file I/O on `Dispatchers.IO`.
* Port-scan fan-out limited by `Semaphore(40)` to avoid ANR on /24 nets.
* WorkManager back-off policy (exponential) protects against Doze kills.
* Sound files larger than 15 MB auto-re-encode lazy to 128 kbps MP3.

---

## 6 · Port-Scan Heuristics

The PortScanDiscoverer probes IPs in ARP/neigh with this ordered list:

```
80,443,5000,554,
7000,7100,
8008-8010,8043,8060,8090-8099,
8200-8205,8873,
9000-9010,10000-10010,
1400-1410,
49152-49170,
50002,
5353
```

*Probe flow:* `SYN` → `GET /device.xml` (or `/description.xml`) → regex `<serviceType>.*AVTransport`.

---

## 7 · Security Surface (Deferred to 2.x)

| Area          | Current State                             | Future Hardening              |
| ------------- | ----------------------------------------- | ----------------------------- |
| HTTP server   | no auth, plain HTTP                       | TLS w/ self-signed cert       |
| Clipboard URL | validated by HEAD only                    | Add DNS whitelist & size caps |
| Port scan     | disabled on first run unless user accepts |                               |

---

## 8 · Extensibility Points

| Hook                      | How to extend                                                        |
| ------------------------- | -------------------------------------------------------------------- |
| **Additional discoverer** | Implement `DeviceDiscoverer`, register in `DiscoveryBus` list        |
| **New rule condition**    | Add `ConditionNode` subclass + UI chip in RuleBuilder                |
| **Different media codec** | Provide `MediaSource.CustomStream` with MIME type; server will proxy |

---

## 9 · Build & Deployment Pipeline

* GitHub Actions → Detekt / ktlint / unit → Headless AVD UI tests → assembleDebug → upload artefact
* Branch protection: all PRs must pass `android.yml`; main branch tags produce artefact `fart-looper-<ver>.apk`.

---

## 10 · Diagrams Source

PlantUML sources are in `/docs/uml/*.puml`.
Generate with `./gradlew generateUml` (custom task).

---

## 11 · Acknowledgements

**Modern Libraries:** NanoHTTPD, UPnPCast, jMDNS, Compose-Waveform, Accompanist, Material 3.

**⚠️ DEPRECATED LIBRARIES - DO NOT USE:**
- **Cling 2.1.2** - End-of-Life since 2019, security vulnerabilities, unavailable from Maven Central
- **mdns-java** - Poor maintenance, compatibility issues with modern Android

See ADR-006 for complete migration rationale and implementation details.

---

*This architecture is living documentation — update this file with every major refactor.*

