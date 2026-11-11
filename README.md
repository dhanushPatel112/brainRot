# Brainrot

<img src="app/src/main/res/drawable-nodpi/ic_launcher_brain.png" width="96" align="right" alt="Brainrot icon">

An Android app that limits your Instagram time, with a brain that rots a little more with every
minute you spend there.

- **Works without accessibility.** Payment apps (GPay, Paytm, banking apps) refuse to work while an
  accessibility service is on, so Brainrot uses **usage access** (to see when Instagram is open) and
  **display over other apps** (to draw on top of it). Payments keep working.
- **Timer badge.** Whenever Instagram is open, a draggable brain shows `today · this session`,
  e.g. `23m · 4m`.
- **Session limit (10 min).** Instagram turns grey, and a popup asks if you want to keep going. Its
  layout, colours, wording and button order are shuffled every time, so you can't tap through
  it from muscle memory. It comes back every 5 minutes. A session ends after 2 minutes away.
- **Daily budget (30 min).** Once it's used up, Instagram stays grey for the rest of the day and
  can't be switched back. A pause screen with a countdown appears when you open Instagram and every
  5 minutes. It starts at 10 s and gets 10 s longer each time (up to 2 min), for the rest of the day.
- **Day resets at 4 AM**, so late-night scrolling counts against the day you started it.
- **Changing a limit applies from tomorrow**, so you can't raise it in a weak moment.
- **Home-screen widget** and a **Today screen** with minutes, sessions, pauses and minutes per hour.

It can't tell Reels apart from the rest of Instagram without accessibility. Everything counts
all of Instagram, and the grey screen is what makes Reels unappealing while chats stay usable.

---

## The rot

The brain has seven stages, driven by today's Instagram minutes.

| Minutes today | 0 | 5 | 15 | 30 | 45 | 60 | 90+ |
|---|---|---|---|---|---|---|---|
| Stage | Fresh | Bruised | Foggy | Fried | Mushy | Rotting | Brain rot |

The thresholds live in one place, [`BrainRot.kt`](service/src/main/java/com/reeltracker/service/BrainRot.kt),
so the badge, the widget and the app always agree.

---

## Install and set up

1. **Build and install** (see [Building](#building)), or `adb install -r app-debug.apk`.
2. Open Brainrot and allow the two permissions it asks for:
   - **Usage access**: find Brainrot in the list and turn it on.
   - **Display over other apps**: turn it on.

   If a switch is greyed out (Android blocks this for sideloaded apps), go to Settings → Apps →
   Brainrot → ⋮ → **Allow restricted settings**, then try again.
3. **Unrestricted battery** (recommended): the app offers it. Without it, Nothing OS and similar
   systems may stop the service. The status pill shows "Not running" when that happens.
4. If you used the old version, **turn off Brainrot under Accessibility**. Updating the app removes
   the service anyway.

A small "Brainrot" notification stays while it's watching. Android requires it for a service that
runs all the time.

---

## How it works

```
UsageStatsManager ──▶ ForegroundWatcher ──▶ UsageService (1 tick/s) ──▶ LimitEngine.tick ──▶ Screen ──▶ OverlayHost
 (usage access)        is Instagram in front?   (foreground service)     (core-model, pure)    badge / grey / pause / popup
                                                       │
                                                       ├──▶ LimitStore (SharedPreferences): limits and rule state
                                                       └──▶ usage_slices (Room): history for the Today screen
```

- **`LimitEngine`** holds all the rules as pure functions of (state, limits, time), and runs in
  plain JVM tests (`LimitEngineTest`). Time only counts while Instagram is in front **and** no pause or
  popup covers it.
- A pause or popup you walk away from stays owed. It shows again the next time Instagram opens.
  Coming back within 30 s doesn't cost a new launch pause (copying a link, a quick reply).
- While a pause or popup is up, Brainrot takes audio focus so the reel underneath goes quiet.
- **The grey** is a full-screen overlay at 55% opacity that passes every touch through. Android 12+
  only lets touches through another app's overlay at 80% opacity or less. To change the strength,
  edit `TINT_ALPHA` in [`OverlayHost.kt`](service/src/main/java/com/reeltracker/service/OverlayHost.kt).

### Privacy

- No internet permission, no analytics, no accounts. Everything stays on the phone.
- Usage access only says which app is in front. Brainrot never sees what is on screen.

---

## Building

**Requirements**
- JDK 17
- Android SDK with platform 35 (`compileSdk 35`, `minSdk 29`, i.e. Android 10+)
- Gradle 8.9 (the wrapper downloads it)

`local.properties` points at your SDK:

```properties
sdk.dir=D\:/android-sdk
```

**Commands** (use `./gradlew` on macOS/Linux)

```bash
gradlew.bat :app:assembleDebug
```

```bash
gradlew.bat :app:installDebug
```

```bash
gradlew.bat :core-model:test
```

The APK lands in `app/build/outputs/apk/debug/app-debug.apk`.

---

## Project structure

| Module | What's in it | Android? |
|---|---|---|
| `core-model` | `LimitEngine` (the rules), `UsageDay` (4 AM days), `UsageStats` (sessions, hourly minutes), plus tests | No, pure Kotlin |
| `core-data` | Room database: `usage_slices` (Instagram time) and the old reel tables, kept as history | Yes |
| `service` | `UsageService`, `ForegroundWatcher`, `OverlayHost`, `PauseView`, `SessionPopupView`, `BrainCounterView` (badge), `LimitStore`, widget | Yes |
| `app` | Compose UI: setup, Today screen, limits | Yes |
| `core-detect`, `spike` | The old accessibility-based Reels detector. No longer used by the app | |

Rule constants (session gap, pause lengths, popup interval) are at the top of
[`LimitEngine.kt`](core-model/src/main/kotlin/com/reeltracker/limits/LimitEngine.kt). Default
limits are in [`LimitStore.kt`](service/src/main/java/com/reeltracker/service/LimitStore.kt).

---

## Known limitations

- **All of Instagram counts, not just Reels.** Without accessibility, Android won't say which tab
  is open.
- **Instagram in a browser isn't tracked.**
- **It can be bypassed** by revoking a permission or uninstalling. This is a speed bump, not a lock.
- **The service can be killed** by aggressive battery managers. Set battery use to Unrestricted.
- **Package name** is still `com.reeltracker` from the project's original name. Changing it would
  install Brainrot as a separate app and lose existing history.
