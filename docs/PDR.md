
# PDR.md  
**Fart-Looper 1.0 — Project Definition & Requirements**  
*(Date: 2025-06-07 · Author: Wobbz)*  

---

## 0 · Revision History
| Date | Ver | Author | Notes |
|------|-----|--------|-------|
| 2025-06-07 | 1.0 | Core Dev Team | Initial PDR with full 1.0 architecture |

---

## 1 · Vision / Elevator Pitch
> *“Pick any sound, tap one button, watch every Sonos, Chromecast, DLNA box or smart TV on the Wi-Fi instantly play it—while you monitor real-time metrics in a slick Compose UI.”*  
Fart-Looper is a **network-audio lab**: non-root, developer-friendly, rule-driven and visually polished.  
It bundles its own HTTP micro-server, exhaustive multi-protocol discovery (SSDP + mDNS + port scanning), a hot-swappable media engine, a visual rule builder, built-in simulator, ADB façade and live telemetry HUD.

---

## 2 · Objectives & Success Criteria
| ID | Objective | Metric |
|----|-----------|--------|
| OBJ-1 | Blast audio clip on **≥90 %** of UPnP/DLNA/Chromecast/Sonos devices on /24 test net | Success ratio shown in HUD ≥ 0.9 |
| OBJ-2 | Cold-start (Wi-Fi→first sound) under **7 s** on Pixel 7 | Measured Latency ≤ 7 000 ms |
| OBJ-3 | End-to-end flow works **without root** on API 21–34 | Manual regression on emulator matrix |
| OBJ-4 | User can pick local file or paste URL in ≤ 3 taps | UX walkthrough recording |
| OBJ-5 | Compose UI passes accessibility checks (contrast, labels) | AccessibilityTests 0 blocking issues |

---

## 3 · Scope
### In-Scope  
* Android APK (debug & dev flavors)  
* All modules, services, WorkManager jobs, Compose screens  
* Rule DSL + visual builder  
* Port spectrum discovery (see §6.3)  

### Out-of-Scope  
* Play-Store hardening, ProGuard/R8 rules, obfuscation  
* OAuth / sign-in / cloud back-end  
* Device security mitigations (to be tackled 2.x)  

---

## 4 · Stakeholders
| Role | Name | Interest |
|------|------|----------|
| Product Owner | Wobbz | Delivers compelling demo + hacking playground |
| Lead Dev | Wobbz | Build & maintain architecture |
| QA | Wobbz | Functional + UX validation |
| DevOps | Wobbz | Green pipeline & artefacts |

---

## 5 · Functional Requirements
| ID | Requirement | Priority |
|----|-------------|----------|
| FR-01 | Detect Wi-Fi connectivity and trigger auto-blast via rules | High |
| FR-02 | Serve local MP3 or proxy remote stream over embedded HTTP | High |
| FR-03 | Discover renderers using SSDP, mDNS and port scan list (§6.3) | High |
| FR-04 | Send UPnP control commands via UPnPCast to each device | High |
| FR-05 | Parallel blasts with adjustable concurrency (default 3) | Med |
| FR-06 | Visual rule builder (SSID regex, time, day, clip) | Med |
| FR-07 | Hot-swap clip while service is running | Med |
| FR-08 | Real-time metrics HUD (HTTP startup, discovery RTT, UPnP control success) | Low |
| FR-09 | Foreground notification with progress & cancel | Low |
| FR-10 | In-app help + log console share/export | Low |

---

## 6 · Technical Requirements
### 6.1 Stack
* **Language** Kotlin 1.9  
* **UI** Jetpack Compose + Material 3  
* **DI** Hilt 2.48  
* **Concurrency** Kotlin Coroutines + Flow  
* **Background** WorkManager 2.9  
* **HTTP Server** NanoHTTPD 2.3.1  
* **UPnP** UPnPCast 1.1.1 (⚠️ **NEVER use Cling 2.1.2** - End-of-Life, unavailable)  
* **mDNS** jMDNS 3.5.8 (⚠️ **NEVER use mdns-java** - deprecated)  
* **Min SDK** 24 **Target** 34  

### 6.2 Permissions  
`INTERNET`, `ACCESS_NETWORK_STATE`, `CHANGE_WIFI_MULTICAST_STATE`, `FOREGROUND_SERVICE`.

### 6.3 Discovery Port Spectrum  
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
5353 (mDNS)
```

---

## 7 · System Architecture

```

┌────────────────────────────┐
│    MainActivity (Compose)  │
│  ──────────────┐          │
│  FAB / HUD /   │──────────┼─┐
│  NavHost       │          │ │
└────────────────────────────┘ │
│LiveData/Flow
bottom-sheet & metrics    │
▼
┌───────────────┐   DataStore   ┌─────────────────┐
│ RuleBuilderUI │──────────────▶│  RuleEvaluator  │
└───────────────┘               └─────────────────┘
(auto-run)
│
▼
Intent / WorkRequest
┌────────────────────────────────────────────────────────────────┐
│            BlastService   (Foreground, Hilt)                  │
│ ──────────────────────────────────────────────────────────────│
│ 1 Start HttpServerManager            4 pushClip()             │
│ 2 DiscoveryBus.discoverAll() ─────▶ ModernUpnpControlClient   │
│ 3 Flow<Device>                      ▲                         │
└────────────────────────────────────────────────────────────────┘
▲      ▲                                    │SOAP over HTTP
│      │ broadcast Metrics                  ▼
│      └────────────┐             ┌──────────────────────┐
│                    │             │  UPnP / DLNA Device │
│MetricsOverlay <────┘             └──────────────────────┘

```

---

### 7.1 Module Breakdown

| Gradle Module | Key Classes | Purpose |
|---------------|-------------|---------|
| **:app** | `FartLooperApp`, `MainActivity`, `BlastService`, `NetworkCallbackUtil` | App entry & glue |
| **:design** | `Theme.kt`, `Color.kt`, `Typography.kt`, `Shapes.kt` | Shared M3 theme |
| **:feature:home** | `HomeScreen`, `DeviceChip`, `MetricsOverlay` | Main UX |
| **:feature:library** | `LibraryScreen`, `ClipThumbnail`, `StorageUtil` | Pick local file / URL |
| **:feature:rules** | `RuleBuilderScreen`, `RuleCardEditable`, `RuleDSL.kt` | Visual rule editor |
| **:core:media** | `HttpServerManager`, `MediaSource`, `HotSwap` | Serve or proxy audio |
| **:core:network** | `UPnPCastDiscoverer`, `JmDNSDiscoverer`, `PortScanDiscoverer`, `ModernUpnpControlClient`, `DiscoveryBus` | Discovery + UPnP Control |
| **:core:simulator** (dev flavor) | `SimulatedRendererService` | Local UPnP stub |
| **:design-test-utils** | `ComposeTestHelpers`, `MockServerRule` | Shared test code |

---

### 7.2 Data Flow

1. **Connectivity** → `NetworkCallbackUtil` receives Wi-Fi ON.  
2. `RuleEvaluator` loads rules from DataStore; if match → emits *Run!* intent.  
3. `BlastService` starts in foreground, spins **NanoHTTPD** on free port.  
4. `DiscoveryBus` concurrently merges three discoverers (UPnPCast, jMDNS, port scan).  
5. Each emitted `UpnpDevice` is piped to `ModernUpnpControlClient.pushClip()` (max 3 parallel).  
6. Metrics collected (HTTP ms, discover ms, UPnP ok/fail) stream to HUD & notification.  
7. Service stops, broadcasts final stats; UI animates chips green/red.

---

## 8 · Non-Functional Requirements

| NFR | Requirement |
|-----|-------------|
| NFR-01 | Codebase passes *ktlint* & *detekt* gates in CI |
| NFR-02 | Unit test line coverage ≥ 80 % on core modules |
| NFR-03 | Compose UI integrates `AccessibilityChecks.enable()` with 0 blocking issues |
| NFR-04 | BlastService survives Doze via WorkManager retry |
| NFR-05 | APK (debug) ≤ 15 MB |

---

## 9 · Testing Strategy

| Layer | Tool | Example Tests |
|-------|------|---------------|
| Unit  | JUnit 4 + Robolectric | `StorageUtilTest`, `RuleEvaluatorTest`, `PortScanDiscovererTest` |
| UI    | Compose-UI-testing | `RunNowSuccessTest`, `RuleBuilderSaveTest`, `HotSwapWhileRunning` |
| Instrumented | Espresso + simulator | `SimulatorE2E` verifies SOAP hits stub |
| CI Matrix | GitHub Actions + Headless AVD API 33/34 | `connectedDebugAndroidTest` |

---

## 10 · CI / CD Pipeline

* **Runner** ubuntu-latest  
* **Jobs** `lint`, `ktlintCheck`, `detekt`, `unitTest`, `connectedDebugAndroidTest`, `assembleDebug`  
* **Caching** Gradle home keyed on `gradle.lockfile`  
* **Artifacts** upload `app-debug.apk` on every commit  
* **Headless Emulator** spawns for UI + instrumentation stages

---

## 11 · Milestones & Schedule

| Sprint (1 wk) | Deliverables |
|---------------|-------------|
| S-1 | Project skeleton, Design module, CI stub passes green |
| S-2 | Core-media (HTTP server + StorageUtil) & waveform preview |
| S-3 | SSDP + mDNS discovery working, log console |
| S-4 | Port-scan discoverer & exhaustive spectrum |
| S-5 | UpnpControlClient + first end-to-end blast (CLI only) |
| S-6 | Compose Home UI + Metrics HUD |
| S-7 | Library & Rule builder UX |
| S-8 | BlastService integration, notification, haptics |
| S-9 | Simulator, unit + UI tests, accessibility pass |
| S-10 | Docs, help overlay, polish, 1.0-alpha tag |

---

## 12 · Glossary

| Term | Meaning |
|------|---------|
| **AVTransport** | UPnP service controlling playback |
| **SSDP** | Simple Service Discovery Protocol (UDP 1900) |
| **mDNS** | Multicast DNS service discovery on 224.0.0.251:5353 |
| **Port scan spectrum** | Hard-coded list of common media ports (see §6.3) |
| **Hot-swap** | Swap clip bytes while HTTP server stays alive |

---

## 13 · Risks & Mitigations

| Risk | Impact | Mitigation |
|------|--------|-----------|
| IP-only port scan may miss host behind firewall | Missed devices | Combine SSDP + mDNS first; scan only ARP-seen IPs |
| Large clip streaming via proxy could choke memory | OOM | Use chunked response & BufferedInputStream |
| Doze kills foreground service on aggressive OEMs | Clip stops | Schedule Worker fallback; mark service as `mediaPlayback` |

---

## 14 · References
* NanoHTTPD 2.3.1 docs  
* Cling 2.1.2 User Guide  
* Google Material 3 motion specs  
* mdns-java GitHub wiki  

---

### **Approval**

|    Name     |     Role      |
|-------------|---------------|
|    Wobbz    | Creator | 
