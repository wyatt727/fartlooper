Below is a **deep-dive catalogue of everything that can go wrong** while delivering, running, or demo-ing Fart-Looper 1.0.  Use it as a risk register during planning and retros; strike items off only when you’ve proved they **cannot** bite you.

---

## 1 · Local Dev & Tooling

| Code   | Roadblock                                                  | Typical Symptom                               | Mitigation Ideas                                                                                  |
| ------ | ---------------------------------------------------------- | --------------------------------------------- | ------------------------------------------------------------------------------------------------- |
| DEV-01 | **Gradle wrapper mismatch** (studio upgrades to 8.6 alpha) | `Unsupported major.minor` / build fails on CI | Pin wrapper distribution URL; use version catalog for plugins                                     |
| DEV-02 | **JDK 17 vs 21**—team mixes versions                       | “sealed classes not supported” errors         | .java-version file or Gradle toolchains                                                           |
| DEV-03 | **Version-catalog drift** between branches                 | `Could not find libs.androidxCore`            | Enable `VERSION_CATALOGS` lock mode + renovate bot                                                |
| DEV-04 | **Git submodule SHAs forgotten** (vendor libs)             | CI clone → missing code                       | Add `.gitmodules` & `submodule.*.shallow = true`; CI step `git submodule update --init --depth 1` |
| DEV-05 | **Detekt + Kotlin version lag**                            | Detekt rules crash on upgrade                 | Keep Detekt minor in lock file; run it after compile step                                         |

---

## 2 · Build & CI

| Code  | Roadblock                                          | Symptom                                        | Mitigation                                                                      |
| ----- | -------------------------------------------------- | ---------------------------------------------- | ------------------------------------------------------------------------------- |
| CI-01 | **Headless AVD ↔ UDP multicast blocked**           | SSDP tests pass locally but fail in GH Actions | Launch emulator with `-netfast -no-snapshot`; fall back to simulator tests only |
| CI-02 | **Mac M1 runners vs x86\_64 emu**                  | “cannot start system image”                    | Force `runs-on: macos-latest-xlarge` or Linux w/ x86\_64                        |
| CI-03 | **Gradle cache key too broad**                     | cache never re-used → 15-min job               | Key on `gradle.lockfile`, wrapper hash                                          |
| CI-04 | **Compose compiler OOM** under 4 GB RAM            | Daemon killed                                  | Split assemble and tests; increase `Xmx` in `gradle.properties`                 |
| CI-05 | **Espresso-accessibility plugin flakes** on API 34 | random test failures                           | Retry rule; quarantine until stable                                             |

---

## 3 · Dependency Hell

| Code   | Roadblock                                                                            | Symptom                                  | Work-around                                      |
| ------ | ------------------------------------------------------------------------------------ | ---------------------------------------- | ------------------------------------------------ |
| LIB-01 | **NanoHTTPD 2.3.1** breaks on Android 6 (`java.nio.channels.FileChannel.transferTo`) | Clip never serves, log shows `Errno 38`  | Fallback to manual stream copy                   |
| LIB-02 | **Cling 2.1.2** uses Apache HttpComponents removed from API 30                       | `NoClassDefFoundError: org/apache/http`  | Shade HttpClient; use Cling “Android Fix” build  |
| LIB-03 | **mdns-java** leaks thread handles                                                   | App never stops on exit                  | Add explicit `mdns.close()` in Service.onDestroy |
| LIB-04 | **compose-waveform** not in Maven Central                                            | Gradle sync fails                        | Use JitPack or fork & publish to GitHub packages |
| LIB-05 | **Kotlin 1.9.22** + AAPT2 bug (vector images)                                        | “java.lang.NoSuchFieldError: NO\_GETTER” | Upgrade Android Gradle Plugin 8.4+               |

---

## 4 · Networking Realities

| Code   | Roadblock                                               | Symptom                                | Notes                                                            |
| ------ | ------------------------------------------------------- | -------------------------------------- | ---------------------------------------------------------------- |
| NET-01 | **Client on guest/isolated Wi-Fi**—multicast blocked    | Finds zero devices                     | Show banner “AP isolates clients; switch networks”               |
| NET-02 | **IPv6-only or DS-Lite network**                        | `Inet4Address` null → HTTP URL invalid | Add IPv6 literal support; choose `scopeId`                       |
| NET-03 | **Enterprise Wi-Fi forces 802.1X re-auth** mid-blast    | Service killed, playback stops         | Detect disconnect in NetworkCallback; retry                      |
| NET-04 | **Router floods ARP table (2 k + devices)**             | PortScanDiscoverer 30 s+, UX stalls    | Limit scan to first /24; fallback to SSDP only                   |
| NET-05 | **Firewall IDS flags port scan**                        | User kicked or portal page redirect    | Provide “Light Discovery” toggle (SSDP+mDNS only)                |
| NET-06 | **Sonos S2 firmware** rejects plain HTTP on non-80 port | URI set fails                          | If SOAP error, fallback to port‐80 reverse proxy via WorkManager |
| NET-07 | **Chromecast HTTPS required** for >15 MB files          | Play but silent                        | Warn if local file size > 15 MB; allow user to choose URL stream |

---

## 5 · Device Diversity

| Code   | Roadblock                                             | Device Type          | Symptom                      | Fix                                       |
| ------ | ----------------------------------------------------- | -------------------- | ---------------------------- | ----------------------------------------- |
| DEV-01 | **Older DLNA TVs** need `SetNextAVTransportURI`       | LG 2012              | Only plays once, queue empty | Add optional second action                |
| DEV-02 | **Samsung Tizen** ignores non-ASCII URI               | Korean SSID          | `Error 711`                  | URL-encode file path                      |
| DEV-03 | **Yamaha MusicCast** requires `Content-Length` header | AVR RX-V685          | SOAP 500 error               | Compute length even for proxy stream      |
| DEV-04 | **Bose SoundTouch** prompts user confirmation         | “Accept new source?” | Playback blocked             | Nothing—known behaviour; highlight in FAQ |
| DEV-05 | **AirPlay (7000)** not UPnP                           | HomePod mini         | Device never appears         | Road-mapped AirPlay integration (v1.1)    |

---

## 6 · Android OS Quirks

| ID    | OS Version           | Roadblock                                                    | Symptom                      |                                                                          |
| ----- | -------------------- | ------------------------------------------------------------ | ---------------------------- | ------------------------------------------------------------------------ |
| OS-01 | MIUI 14, OxygenOS 14 | **Aggressive “battery optimisations”** kill BlastService     | Service dies 3 s after start | Request `REQUEST_IGNORE_BATTERY_OPTIMIZATIONS` intent; WorkManager retry |
| OS-02 | API 29+              | **Scoped Storage**—cannot copy file outside cache            | FileNotFoundException        | Always copy to app-private `cacheDir`                                    |
| OS-03 | API 23               | **`CHANGE_WIFI_MULTICAST_STATE`** ignored without Wi-Fi lock | SSDP missing                 | Acquire `MulticastLock` via WifiManager                                  |
| OS-04 | API 21–22            | `AnimatedVisibility` not available                           | Build fails                  | Use Accompanist backward compat                                          |

---

## 7 · User-Experience Pitfalls

| UX Code | Issue                                                    | Impact                          | Remedy                                                          |
| ------- | -------------------------------------------------------- | ------------------------------- | --------------------------------------------------------------- |
| UX-01   | Bottom-sheet progress never collapses if 0 devices found | Looks frozen                    | Auto timeout; show “0 devices visible on this Wi-Fi” CTA        |
| UX-02   | Waveform preview on 50 MB FLAC stalls UI                 | Jank                            | Decode off the UI thread; cap preview length                    |
| UX-03   | Regex field confusing for novices                        | Rules never fire                | Offer simple “contains” mode with toggle to regex expert        |
| UX-04   | Notification channel default importance high             | Obnoxious heads-up on every run | Set `IMPORTANCE_LOW`; use heads-up only when in background      |
| UX-05   | Multi-window split kills large FAB motion spec           | Layout overlap                  | Listen to window size classes, fall back to simple progress bar |


---

## 8 · Performance & Memory

\| PERF-01 | Copying 200 MB WAV uses 2✕ space in cache | OOM on low-end phones | Re-encode >10 MB to 128-kbps MP3 |
\| PERF-02 | Cling registry listener leak | Memory slope in metrics test | `registry.removeListener(this)` after timeout |
\| PERF-03 | PortScan coroutine fan-out 512 IPs ✕ 40 ports | CPU spike | Coroutine semaphore; early cutoff once success ratio reached |

---

## 9 · Documentation & Process

\| DOC-01 | README gifs >10 MB | Git LFS needed | Compress or host externally |
\| DOC-02 | Architecture diagram not updated after refactor | Onboarding pain | PlantUML script + CI “diagram drift” check |
\| PROC-01 | Branch naming inconsistency | CI badge chaos | Adopt Conventional Commits + git-flow |

---

**Use this matrix when grooming backlogs, writing sprint acceptance criteria, or setting up demos.  If a risk is irrelevant to your environment, mark it “N/A”; otherwise assign owner + resolution date.**
