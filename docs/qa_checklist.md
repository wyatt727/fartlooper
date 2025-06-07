# QA CHECKLIST
**Fart-Looper 1.0 Manual Quality Assurance**  
_Version: 1.0 | Last update: 2025-06-07_

---

## 🎯 Test Environment Setup

### Pre-Test Requirements

| Requirement | Status | Notes |
|-------------|--------|-------|
| ⬜ **Test Device** Android 5.0+ (API 21+), ideally Android 12+ for dynamic color | |
| ⬜ **Network Setup** Wi-Fi with 2+ UPnP/DLNA devices available | |
| ⬜ **Audio Devices** Mix of Sonos, Chromecast, Samsung TV, or other renderers | |
| ⬜ **Test Media** Default fart.mp3 + custom audio file + streaming URL | |
| ⬜ **Accessibility Tools** TalkBack enabled, switch access if available | |
| ⬜ **Performance Tools** ADB access for timing measurements | |

### Test Data Preparation

```bash
# Performance baseline commands for QA validation
adb shell am start -W -n com.wobbz.fartloop/.MainActivity
# Record cold start time - TARGET: ≤7 seconds to first interaction

adb logcat -s "BlastService" "DiscoveryBus" "HttpServerManager" 
# Monitor for performance metrics during testing
```

---

## 🚀 Performance & Stability Tests

### P-1: Cold Start Performance
**TARGET: ≤7 seconds from tap to first meaningful interaction**

| Step | Expected Result | Actual Time | Status |
|------|----------------|-------------|--------|
| 1. Force-stop app `adb shell am force-stop com.wobbz.fartloop` | App fully terminated | | ⬜ |
| 2. Clear memory (restart device if needed) | Clean memory state | | ⬜ |
| 3. Tap app icon, start timer | App launch begins | | ⬜ |
| 4. Stop timer when HomeScreen fully loaded + FAB visible | **≤7 seconds total** | _____s | ⬜ |
| 5. Verify all UI elements respond to touch | Smooth interactions | | ⬜ |

**PERFORMANCE FINDING VALIDATION:**
- Cold start includes: Application startup + Hilt DI graph + Compose UI inflation + theme loading
- Target derived from PDR Section 8.1: "Users expect immediate response after app selection"
- Failure indicators: ANR dialog, black screen >3s, unresponsive UI elements

### P-2: Device Rotation Stability
**TARGET: No data loss, smooth transition, <2 second recomposition**

| Device Orientation | Rotation Action | Expected Result | Status |
|-------------------|-----------------|-----------------|--------|
| Portrait → Landscape | Rotate device slowly | UI recomposes smoothly, no flicker | ⬜ |
| Landscape → Portrait | Rotate device slowly | UI recomposes smoothly, no flicker | ⬜ |
| **During Discovery** | Rotate while discovering devices | Discovery continues, device list preserved | ⬜ |
| **During Blast** | Rotate while blasting | Progress maintained, metrics preserved | ⬜ |
| **Rule Builder Active** | Rotate during rule creation | Form data preserved, no validation loss | ⬜ |

**ROTATION FINDING VALIDATION:**
- UI state preservation critical for user trust during network operations
- Compose state hoisting should maintain all form data across configuration changes
- Bottom sheet and FAB positions should adapt gracefully to screen orientation

### P-3: Memory & Resource Management

| Scenario | Test Action | Expected Result | Status |
|----------|-------------|-----------------|--------|
| **Extended Discovery** | Run discovery 10 times consecutively | Memory usage stable, no leaks | ⬜ |
| **Background App** | Send app to background during blast | Foreground service continues, notification visible | ⬜ |
| **Network Changes** | Switch Wi-Fi networks during operation | Auto-recovery or graceful failure | ⬜ |
| **Low Memory** | Open 5+ heavy apps while running Fart-Looper | App survives or restarts cleanly | ⬜ |

---

## �� Core Functionality Tests

### F-1: Audio Blasting Workflow
**Primary user journey from media selection to successful playback**

| Step | Action | Expected Result | Status |
|------|--------|-----------------|--------|
| 1. **Media Selection** | Go to Library tab | Library screen loads with current selection | ⬜ |
| 2. **Default Media** | Verify fart.mp3 is selected by default | Default clip shown with waveform preview | ⬜ |
| 3. **Start Blast** | Return to Home, tap BLAST FAB | FAB transforms to bottom sheet | ⬜ |
| 4. **HTTP Stage** | Watch pipeline progress | "Starting HTTP Server" completes <40ms | ⬜ |
| 5. **Discovery Stage** | Monitor device discovery | "Discovering Devices" finds 1+ devices | ⬜ |
| 6. **Blasting Stage** | Watch device status updates | Device chips turn green (success) or red (fail) | ⬜ |
| 7. **Completion** | Wait for pipeline finish | Bottom sheet shows "Blast Complete" | ⬜ |
| 8. **Audio Verification** | **Check target devices manually** | **Audio plays on discovered devices** | ⬜ |

**CRITICAL VALIDATION POINT:**
Step 8 requires manual verification that audio actually plays on physical devices. This is the ultimate test of UPnP SOAP command success.

### F-2: Hot-Swap Media Functionality
**Change media source during active blast without restart**

| Hot-Swap Scenario | Action | Expected Result | Status |
|-------------------|--------|-----------------|--------|
| **Pre-Blast Swap** | Change clip, then blast | New clip plays on devices | ⬜ |
| **Mid-Discovery Swap** | Change clip during discovery | Discovery continues, new clip used | ⬜ |
| **Mid-Blast Swap** | Change clip during active blast | New clip immediately available on HTTP server | ⬜ |
| **URL to File Swap** | Switch from stream URL to local file | HTTP server switches serving mode | ⬜ |
| **File to URL Swap** | Switch from local file to stream URL | HTTP server switches to proxy mode | ⬜ |

**HOT-SWAP FINDING VALIDATION:**
- HTTP server should update serving route without restart: `/media/current.mp3` vs `/media/stream`
- Active connections should continue uninterrupted during media source changes
- UI should reflect new media immediately with updated waveform/metadata

### F-3: Discovery Method Verification

| Discovery Method | Test Scenario | Expected Devices | Status |
|------------------|---------------|------------------|--------|
| **SSDP Discovery** | Standard UPnP/DLNA devices | Sonos, DLNA TVs, media servers | ⬜ |
| **mDNS Discovery** | Modern cast devices | Chromecast, AirPlay, smart speakers | ⬜ |
| **Port Scan Discovery** | Devices not advertising | "Hidden" UPnP devices on alt ports | ⬜ |
| **Combined Discovery** | All methods enabled | Maximum device coverage | ⬜ |
| **Method Isolation** | Disable SSDP/mDNS, port scan only | Still finds 1+ devices via port scan | ⬜ |

**DISCOVERY FINDING VALIDATION:**
- Port scan should test all 100+ ports from spectrum: 80, 443, 5000, 554, 7000, 7100, 8008-8099, etc.
- Deduplication should prevent same device appearing multiple times from different methods
- Each method timeout should respect user settings (default 2-4 seconds per method)

---

## 🎨 UI/UX Quality Tests

### U-1: Material Motion Compliance
**FAB transformation and bottom sheet animations per Material Design specs**

| Motion Element | Test Action | Expected Behavior | Status |
|----------------|-------------|------------------|--------|
| **FAB Scale** | Tap BLAST FAB | Scales from 1.0→0.0 over 200ms | ⬜ |
| **Container Transform** | FAB→bottom sheet | Container scales 0.2→1.0 over 300ms | ⬜ |
| **Alpha Crossfade** | FAB disappears, sheet appears | Clean alpha transition, no flicker | ⬜ |
| **Spring Physics** | Sheet entrance | Natural spring animation, no overshoot | ⬜ |
| **Auto-Dismiss** | Wait after completion | Sheet auto-dismisses after 3 seconds | ⬜ |

**MOTION FINDING VALIDATION:**
- Animations should use proper easing curves: FastOutSlowIn for scale, Linear for alpha
- Z-index coordination prevents visual glitches during transformation
- Motion respects system animation scale settings (disabled = instant transitions)

### U-2: Navigation & State Management

| Navigation Flow | Test Steps | Expected Behavior | Status |
|-----------------|------------|------------------|--------|
| **Bottom Nav** | Tap each tab: Home, Library, Rules, Settings | Smooth transitions, state preserved | ⬜ |
| **Deep Linking** | Navigate Library→Rule Builder→Home | Back stack works correctly | ⬜ |
| **State Preservation** | Start rule creation, navigate away, return | Form data preserved across navigation | ⬜ |
| **Metrics Expansion** | Toggle metrics overlay on Home | Smooth expand/collapse animation | ⬜ |
| **URL Input Dialog** | Open URL dialog, dismiss, reopen | Dialog state resets properly | ⬜ |

### U-3: Error Handling & Edge Cases

| Error Scenario | Trigger Method | Expected User Experience | Status |
|----------------|---------------|-------------------------|--------|
| **No Devices Found** | Run blast on isolated network | Clear message: "No devices found on this network" | ⬜ |
| **All Devices Fail** | Mock device failure (disconnect devices) | Error state with retry option | ⬜ |
| **Network Disconnected** | Disable Wi-Fi during blast | Network error with reconnection prompt | ⬜ |
| **Invalid URL** | Enter malformed stream URL | Real-time validation with helpful error | ⬜ |
| **File Access Denied** | Revoke storage permission | Clear permission request with explanation | ⬜ |
| **HTTP Port Conflict** | Block ports 8080-8090 | Auto-port selection finds alternative | ⬜ |

**ERROR HANDLING FINDING VALIDATION:**
- Error messages should be user-friendly, not technical SOAP/HTTP details
- Retry mechanisms should be available for transient network failures  
- Graceful degradation when specific features unavailable (e.g., no multicast support)

---

## ♿ Accessibility & Inclusion Tests

### A-1: Screen Reader Compatibility (TalkBack)
**Enable TalkBack and verify app usability with eyes closed**

| UI Element | TalkBack Test | Expected Announcement | Status |
|------------|---------------|----------------------|--------|
| **BLAST FAB** | Focus and activate | "Audio blast control, button" | ⬜ |
| **Device Chips** | Swipe through device list | "Living Room Sonos, network device, success status" | ⬜ |
| **Progress States** | Monitor during blast | Stage announcements: "Starting HTTP Server" | ⬜ |
| **Rule Builder** | Navigate rule creation | Form labels and current values announced | ⬜ |
| **Navigation Tabs** | Swipe between tabs | "Home tab, selected" / "Library tab" | ⬜ |
| **Error States** | Trigger network error | Error announced with priority | ⬜ |

**ACCESSIBILITY FINDING VALIDATION:**
- Content descriptions must be meaningful and context-aware
- State changes should be announced automatically (live regions)
- Focus management should be logical and predictable
- Custom UI components need explicit accessibility roles

### A-2: Haptic Feedback Patterns

| Interaction Type | Test Action | Expected Haptic | Status |
|------------------|-------------|-----------------|--------|
| **Success State** | Successful blast completion | Light success vibration | ⬜ |
| **Error State** | Device connection failure | Heavy error vibration | ⬜ |
| **Selection** | Tap device chip or navigation | Light selection feedback | ⬜ |
| **Warning** | Network timeout warning | Medium warning vibration | ⬜ |
| **Long Press** | Long press on device chip | Long press haptic pattern | ⬜ |

**HAPTIC FINDING VALIDATION:**
- Haptic patterns should be distinct and meaningful
- Respects system haptic feedback settings (user can disable)
- Consistent across app - same pattern for same semantic meaning

### A-3: High Contrast & Large Text

| Accessibility Setting | Test Configuration | Expected Behavior | Status |
|----------------------|-------------------|------------------|--------|
| **High Contrast Mode** | Enable system high contrast | All text/icons remain visible | ⬜ |
| **Large Text** | System font size 200% | UI scales properly, no clipping | ⬜ |
| **Dark Mode** | Switch between light/dark themes | Smooth transition, proper contrast | ⬜ |
| **Color Blind Support** | Test without relying on color alone | Status indicated by shape/text, not just color | ⬜ |

---

## 🔧 Advanced Feature Tests

### AF-1: Rule Engine Functionality

| Rule Type | Test Configuration | Expected Behavior | Status |
|-----------|-------------------|------------------|--------|
| **SSID Rule** | Regex: `home.*` on "home-wifi" network | Auto-blast triggers on connection | ⬜ |
| **Time Rule** | 09:00-17:00 on weekdays | Auto-blast only during work hours | ⬜ |
| **Combined Rules** | SSID + Time + Day conditions | All conditions must match to trigger | ⬜ |
| **Rule Priority** | Multiple matching rules | First matching rule executes, others ignored | ⬜ |
| **Rule Persistence** | Create rule, restart app, verify | Rules survive app restart | ⬜ |

### AF-2: Developer Console & Debugging

| Debug Feature | Test Action | Expected Result | Status |
|---------------|-------------|-----------------|--------|
| **Log Console** | Tap debug FAB, open console | Real-time log entries visible | ⬜ |
| **Log Filtering** | Filter by ERROR level | Only error logs shown | ⬜ |
| **Log Export** | Copy logs to clipboard | Full log text copied | ⬜ |
| **Performance Metrics** | Expand metrics overlay | Detailed timing and stats visible | ⬜ |
| **Device Response Times** | Check per-device timing | Individual device latency shown | ⬜ |

### AF-3: Settings & Configuration

| Setting Category | Test Change | Expected Impact | Status |
|------------------|-------------|-----------------|--------|
| **Discovery Timeout** | Change from 3s to 8s | Discovery runs longer, finds more devices | ⬜ |
| **Blast Concurrency** | Change from 3 to 1 device | Devices blast sequentially, not parallel | ⬜ |
| **UPnP Timeout** | Increase SOAP timeout | More patient with slow devices | ⬜ |
| **Cache TTL** | Set cache to 1 hour | Metrics and device cache expires faster | ⬜ |
| **Debug Logging** | Toggle debug mode | More/fewer log entries in console | ⬜ |

---

## 📊 Performance Benchmarks

### B-1: Timing Benchmarks (Must Meet PDR Targets)

| Performance Metric | PDR Target | Test Environment | Measured | Status |
|-------------------|------------|------------------|----------|--------|
| **HTTP Startup** | <40ms | Standard Android device | ____ms | ⬜ |
| **Discovery Time** | ~2100ms | Home Wi-Fi, 3+ devices | ____ms | ⬜ |
| **SOAP Latency** | <500ms avg | Mixed device types | ____ms | ⬜ |
| **Full Pipeline** | <5000ms | End-to-end blast | ____ms | ⬜ |
| **Memory Usage** | <100MB peak | During active blast | ____MB | ⬜ |

### B-2: Device Compatibility Matrix

| Device Type | Model/Brand | Discovery Method | SOAP Success | Notes |
|-------------|------------|------------------|--------------|-------|
| **Sonos** | Play:1, Play:5, etc. | SSDP (ports 1400-1410) | ⬜ | Target: 95%+ success |
| **Chromecast** | Ultra, Audio, TV | mDNS (_googlecast._tcp) | ⬜ | Target: 90%+ success |
| **Samsung TV** | Smart TV series | Port scan (8000-8005) | ⬜ | Target: 85%+ success |
| **DLNA Generic** | Various brands | SSDP standard ports | ⬜ | Variable compatibility |
| **AirPlay** | HomePod, Apple TV | mDNS (_airplay._tcp) | ⬜ | Limited UPnP support |

**COMPATIBILITY FINDING VALIDATION:**
- Success rate targets based on DevUtils testing data from ADR-003
- Different discovery methods optimized for different device categories
- Port scan essential for devices that don't advertise properly

---

## 🏁 Final Validation Checklist

### Release Readiness Criteria

**MUST PASS ALL ITEMS BEFORE RELEASE:**

| Category | Requirement | Validation | Status |
|----------|-------------|------------|--------|
| **Performance** | Cold start ≤7s, discovery ≤3s, no ANRs | Timing tests passed | ⬜ |
| **Functionality** | Core blast workflow succeeds on 2+ device types | Audio verified on real devices | ⬜ |
| **Stability** | No crashes during 30-minute stress test | Memory leaks checked | ⬜ |
| **Accessibility** | TalkBack navigation works for all core functions | Screen reader tested | ⬜ |
| **Error Handling** | Graceful failure modes for network/device issues | Edge cases handled | ⬜ |
| **Documentation** | User-facing error messages are helpful | Non-technical language used | ⬜ |

### Known Limitations (Acceptable for 1.0)

| Limitation | Impact | Mitigation | Status |
|------------|--------|------------|--------|
| **Guest Networks** | Limited device discovery | Show network limitation warning | ⬜ |
| **AirPlay Devices** | No direct UPnP control | Document as future feature | ⬜ |
| **Enterprise Wi-Fi** | Reduced multicast discovery | Port scan compensates | ⬜ |
| **Large Files** | Chromecast HTTPS requirement | User education in FAQ | ⬜ |

---

**QA VALIDATION COMPLETE:**
- Total test items: ___ 
- Passed: ___
- Failed: ___
- Release recommendation: ✅ APPROVED / ❌ NEEDS WORK

**QA Tester:** _________________ **Date:** _______ **Device:** _________________

**CRITICAL FINDING:** Audio playback verification (F-1 Step 8) is the ultimate validation. All other tests support this core functionality, but actual audio output on physical devices is the definitive success measure.
</rewritten_file>
