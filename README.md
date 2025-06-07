
# Fart-Looper 1.0  
_A playful, developer-focused “network-audio lab” that blasts any clip to every Sonos, Chromecast, DLNA, or UPnP box on your Wi-Fi—no root required._

<p align="center">
  <img src="docs/screenshots/home_light.png" width="280"/>
  <img src="docs/screenshots/library_dark.png" width="280"/>
  <img src="docs/screenshots/progress.gif" width="280"/>
</p>

---

## ✨ Key Features
|  | What it does | Why it’s cool |
|--|--------------|---------------|
| **One-tap Blast** | Starts an embedded HTTP server, discovers renderers (SSDP + mDNS + exhaustive port-scan) and sends `SetAVTransportURI → Play`. | Hear the clip on every speaker or TV within ~5-7 s. |
| **Pick Any Clip** | Use the default `fart.mp3` (from assets), choose a local file (SAF picker) _or_ paste a stream URL. | Demo latency with your own sounds—including live radio. |
| **Hot-Swap Media** | Change the clip while a blast is running—no restart. | Instantly prank-switch to an apology track. |
| **Visual Rule Builder** | GUI for Wi-Fi SSID regex, day/time windows, and clip selection. | Automate blasts only when you get home after 8 p.m. |
| **Live Metrics HUD** | Latency bar, success pie, per-device chips turning green/red. | Measure discovery vs. play bottlenecks. |
| **Built-in Simulator** | Dev-flavor UPnP stub for tests and demos without hardware. | CI and local e2e without real speakers. |
| **ADB Automation** | `adb shell am startservice -n … --es CLIP_URL http://…` | Integrate with Automate, Tasker or scripts. |

---

## 🏗 Project Structure
```

fart-looper/
├ app/                    # Main application module
├ design/                 # Material3 theme & shared composables
├ core/
│   ├ media/              # StorageUtil, HttpServerManager
│   ├ network/            # Discoverers + UpnpControlClient
│   └ simulator/          # Local renderer stub (dev flavor)
└ feature/
├ home/               # HomeScreen, metrics HUD
├ library/            # Clip picker & preview
└ rules/              # Visual rule builder

````

---

## ⚙️ Tech Stack
* Kotlin 1.9 · Jetpack Compose · Hilt   
* Kotlin Coroutines & Flow   
* WorkManager 2.9 (Doze-safe)   
* **NanoHTTPD 2.3** — embedded HTTP server  
* **Cling 2.1** — UPnP/SSDP control  
* **mdns-java 3.5** — mDNS discovery  
* Compose-Waveform 1.1 for audio previews  

---

## 🚀 Quick Start (Developer)

```bash
# 1. Clone
git clone https://github.com/wyatt727/fartlooper.git
cd fartlooper

# 2. First-time tool sync
./gradlew help         # downloads wrapper & dependencies

# 3. Install debug build on attached phone/emulator
./gradlew assembleDebug
adb install app/build/outputs/apk/debug/app-debug.apk
adb shell am start -n com.wobbz.fartloop/.MainActivity
````

> **Min SDK 24** — UPnPCast library requirement; best UX on Android 12+ (dynamic color).

## ✅ Build Status - SUCCESSFULLY BUILDING!

**🎉 ALL MAJOR ISSUES RESOLVED:**
- ✅ **Circular dependency resolved** - NetworkCallbackUtil moved to core:network
- ✅ **UPnP/mDNS libraries modernized** - UPnPCast + jMDNS replacing deprecated Cling
- ✅ **ViewModels implemented** - HomeViewModel and LibraryViewModel with proper state management
- ✅ **All module dependencies resolved** - No more compilation errors
- ✅ **Material Icons and NanoHTTPD integration** - All dependencies working correctly

---

## 🔬 Blast Workflow

1. **HTTP spin-up** – NanoHTTPD binds to the first free port ≥ 8080.
2. **Discovery** – Parallel *SSDP*, *mDNS* (`_googlecast._tcp`, `_airplay._tcp`, `_dlna._tcp`, `_raop._tcp`), and **port scan** of

   ```
   80 443 5000 554 7000 7100
   8008-8099 8200-8205 8873
   9000-9010 10000-10010
   1400-1410 49152-49170 50002 5353
   ```
3. **Control** – For each confirmed renderer
   `SetAVTransportURI(mediaUrl)` → delay 200 ms → `Play()`.
4. **Metrics** – HUD & notification update in real time.

All heavy I/O is on `Dispatchers.IO`; UI stays smooth.

---

## 📱 Using the App

1. **Library tab →** choose default fart, pick any file, or paste a URL.
2. Optional: **Rules tab →** create an if/then rule (regex SSID, time, day).
3. **Home tab → BLAST!**
   *Watch the bottom sheet animate through “HTTP → Discovery → Play” and the chips turn green.*

---

## 🔧 Developer Goodies

| Command                                          | Function                          |
| ------------------------------------------------ | --------------------------------- |
| `./gradlew ktlintCheck detekt`                   | Static analysis gates             |
| `./gradlew connectedDebugAndroidTest`            | UI/instrumented tests on emulator |
| `./gradlew :core:simulator:installDevDebug`      | Runs local UPnP stub on device    |
| `adb shell am startservice -a ACTION_RUN_CLIP …` | Scripted blasts via ADB           |

---

## 🛡 Permissions Explained

| Permission                    | Why                             |
| ----------------------------- | ------------------------------- |
| `INTERNET`                    | Serve clip & send SOAP          |
| `ACCESS_NETWORK_STATE`        | Detect Wi-Fi / airplane toggles |
| `CHANGE_WIFI_MULTICAST_STATE` | Receive SSDP & mDNS packets     |
| `FOREGROUND_SERVICE`          | Keep blast alive under Doze     |

---

## 📝 Roadmap

* **1.1** – Gapless playlist & clip queue
* **1.2** – Device groups & exclusions
* **2.0** – Play-Store beta

See [`CHANGELOG.md`](CHANGELOG.md) for detailed history.

---

## 🤝 Contributing

1. Fork & clone
2. Run `./gradlew check` (lint + tests) *before* PR
3. Make PR against `dev` branch with clear, single-purpose commits

Code style = Ktlint default + Detekt ruleset in `/lint`.

---

## 📜 License

MIT © 2025 Wobbz
