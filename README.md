# Intentional

An Android prototype that adds a pause before Instagram or YouTube and a reflection afterward. Built with **Kotlin Multiplatform and Compose Multiplatform**, with the dark/violet visual direction from `ui idea/`.

## YouTube support (0.2.0)

The same accessibility service now covers the official Instagram and YouTube Android apps, including YouTube Shorts. Choose either app on Your space to launch intentionally or try its demo. Opening either app directly also triggers the check-in when accessibility is enabled. Warnings, expiry prompts, intentions, and journal records identify the app. Existing records load as Instagram sessions.

Only the foreground app accrues usage. As of 0.5.0, switching between protected apps keeps a separate paused interaction for each, resumable within five minutes; pending expiry/reflection questions still require resolution. The service component name is preserved so updating does not intentionally reset accessibility access. Check it remains enabled after installation. Rebuild/share the latest APK with `./scripts/share-android.sh`; use the same signing key to retain data.

The Instagram walkthroughs below also apply to YouTube. Browser playback, YouTube Music, and third-party clients are not covered. An interruption brings Intentional forward; it does not explicitly pause video/audio or stop YouTube picture-in-picture/background playback. These need device testing, particularly on YouTube Premium accounts.

## Try it

Version 0.7.0 makes study collection automatic after a one-time notice listing the exact transmitted fields. All participant CSV/JSON export, share, and send controls are removed. Background network jobs check periodically and after journal changes, uploading only changed data to the installation's private `latest` CSV. The server still enforces a 15-minute minimum interval and 512 KiB limit. There is no public broadcast. Configuration and updated Firebase rules are required before study distribution; see [researcher setup/download](docs/FIREBASE_SUBMISSIONS.md). Android scheduling can delay background work.

Version 0.6.0 checks the **focused window** on events and every 500 ms, instead of assuming the latest window-event sender is the foreground app. This prevents reminders/non-focused system windows from pausing an idle YouTube/Instagram screen and detects returns even without a new app event. Android now requires window-content capability for that check; the implementation only reads the selected window root's package name, never text or child nodes. After upgrading, check Accessibility remains enabled (toggle it off/on if the updated window capability is not active).

Expiry now offers **Finish and check in** / **Add more time**, not a fulfillment question. Only final reflection asks whether the intention was fulfilled, with equal Yes/No buttons, alongside the relaxation rating. Extensions never record a premature fulfillment answer.

Version 0.6.0 introduced optional manual Firebase submissions; version 0.7.0 replaces those controls with the automatic collection flow above.

Version 0.5.0 pauses a live session as soon as the moderated app leaves the foreground or the screen locks. Returning within five minutes resumes the remaining time. Otherwise, the interaction is saved with only observed usage, `status=inactivity_timeout`, and `N/A` for relaxation afterward and task completion. It then resets silently. Yes/No completion buttons have identical styling, and completion questions have no Back button.

Version 0.4.0 introduced CSV export, opening-attempt tracking, and the desktop aggregation script. Participant export/share controls were removed in 0.7.0; researchers download the CSVs from Firebase instead. See [study field definitions](docs/STUDY_DATA.md). Opening tracking is prospective; prior versions cannot supply historical opening counts.

Open this folder in Android Studio, sync Gradle, and run the `androidApp` configuration on an Android 8.0+ device (API 26+).

Build prerequisites: JDK 17, Android SDK platform 36, SDK build tools, and internet access for first-time Gradle dependency resolution. The project pins Gradle 9.1.0, AGP 9.0.1, Kotlin 2.4.10, and Compose Multiplatform 1.11.1. Material 3 is pinned to 1.11.0-alpha07, the available matching multiplatform artifact; this prototype uses an alpha UI dependency.

Point your local `local.properties` at your Android SDK (Android Studio can create this file). It is intentionally ignored by Git.

Both modules request a complete Java 17 compiler toolchain. If it is missing, Gradle uses the [Foojay toolchain resolver](https://github.com/gradle/foojay-toolchains) to download a JDK into its user cache on the first build. This requires internet access and does not change system Java settings. A Java 17 runtime alone is not a JDK: `java -version` can report 17 while `javac -version` reports 11 or is missing.

```sh
./gradlew :shared:jvmTest :androidApp:assembleDebug
adb install -r androidApp/build/outputs/apk/debug/androidApp-debug.apk
```

Choose **Try the 75-second demo** to exercise the entire flow without Instagram or accessibility access. The demo includes buttons to jump to the one-minute warning and timer expiry. Demo extensions also last 75 seconds, and demo records are labeled and excluded from the real-session summary.

For real Instagram sessions:

1. Install Instagram and Intentional on the same Android device.
2. In Intentional, choose **Set up opening check-ins**, read the disclosure, and agree to open Settings.
3. Enable **Intentional • Instagram & YouTube check-ins** in Android Accessibility settings. Notification access is optional; an accessibility banner also provides the reminder while the session’s app is foregrounded.
4. Open Instagram from its normal icon, Recents, or a link. Intentional brings its opening check-in forward.

Some Android versions restrict enabling accessibility for sideloaded apps. If Settings displays that restriction, use the device's app-info **Allow restricted settings** option for this locally built app before enabling the service.

## Share with other devices

**Uninstall Intentional** is available at the bottom of every app screen. It opens Android’s uninstall confirmation. Canceling leaves sessions and accessibility unchanged; confirmed removal deletes local app data, stops check-ins and future uploads, and may lose unsynced changes. It does not erase received study data. If the direct uninstaller is unavailable, Intentional opens its Android App info screen instead. Long-pressing the launcher icon → App info → Uninstall also works.

Run `./scripts/share-android.sh` for a same-Wi-Fi download link, or `./scripts/share-android.sh --public` for a temporary internet link (requires cloudflared). The script builds the APK and provides a mobile download page with installation instructions. See [distribution instructions](docs/DISTRIBUTION.md) for existing APKs and permanent hosting bundles.

## Session flow

- **Opening pause:** “Is this intentional?” The user can step away without starting a session.
- **Intention:** editable voice transcription or text, a purpose category, and a 1–5 baseline relaxation rating.
- **Time:** defaults to **15 minutes**, with presets and adjustment from 1 to 120 minutes.
- **Session:** Instagram opens. At one minute remaining, the app posts a notification and a temporary banner over Instagram. The banner opens the session controls.
- **Time is up:** the check-in interrupts Instagram. Finishing leads to reflection; continuing requires a reason and a new duration. Each extension has its own reminder and expiry.
- **Leaving early:** switching away or locking the screen pauses the timer without opening a check-in. Returning within five minutes resumes it; five minutes away saves the usage with unanswered outcomes and resets the timer.
- **Final reflection:** a second relaxation rating and two equally styled **Yes / No** buttons. Neither is preselected or emphasized; both save an honest outcome, followed by a close button. This is the only fulfillment question. No Back button is shown on expiry or reflection.
- **Patterns:** local history with extension reasons, elapsed time, fulfillment, and before/after relaxation. Deletion with confirmation; no manual export.

Purpose selection is explicit rather than inferred from text. The relaxation summary includes only real sessions categorized as Relax. These are self-reported associations, not evidence of causation. The app does not claim personalized time recommendations from usage data.

## Code layout

```text
shared/src/commonMain/kotlin/org/intentional/shared/
  SessionEngine.kt       Platform-independent session state and timer transitions
  IntentionalApp.kt      Compose Multiplatform screens and components
  JournalCodec.kt        Versioned local persistence / export schema
shared/src/commonTest/   Session and persistence tests, run on the JVM target
androidApp/src/main/
  kotlin/.../MainActivity.kt                    Study notice, speech, settings, app launch
  kotlin/.../StudySyncManager.kt                Changed-data detection, persisted job scheduling
  kotlin/.../StudySyncJobService.kt             Network-constrained background execution
  kotlin/.../FirebaseStudyUploader.kt           Anonymous auth, canonical CSV update / rate limit
  kotlin/.../InstagramAccessibilityService.kt   App transitions, reminders, interruptions
  kotlin/.../IntentionalApplication.kt          Process-wide engine and local storage
```

The shared module has Android and JVM targets. UI and state logic live in `commonMain`; Android services stay in the Android app. This delivery targets Android; no iOS interception or desktop app is implemented.

## Android behavior and limits

This is a companion app, not a modification of Instagram. The user explicitly enables an accessibility service. The service listens to package/window changes and brings the Compose activity forward; it cannot prevent Instagram's process from starting, so a brief view of Instagram may appear first. Returning to Instagram while a check-in is pending brings the check-in back.

The one-minute banner uses `TYPE_ACCESSIBILITY_OVERLAY`. Expiry opens Intentional while the session’s app is foregrounded. Focused-window checks on accessibility events and every 500 ms pause/resume the timer as apps change, and screen-off/lock pauses it too. Keyboard input and non-focused reminders do not pause an underlying app. A focused system panel (such as the notification shade), Intentional, another app, or a locked screen pauses usage. The timer is observed foreground time, not a retroactive Android usage-history query; polling can add a small observation delay. Periodic checkpoints preserve confirmed usage across process restarts; unobserved gaps are never added. Five-minute inactivity deadlines survive restarts and use wall-clock recovery after a reboot. Physical-device behavior still needs validation.

The service must remain enabled and running for background interruption. Android or manufacturer battery management, force-stop, screen sleep, and background activity rules can delay or prevent a prompt. There is no exact-alarm or wake-lock guarantee; an overdue session is checked when execution resumes. Check this behavior on the participant's actual device. Closing returns to Home; it does not force-stop Instagram.

Accessibility declarations and behavior would require a separate distribution review before a Play Store release. This project is a research prototype for local installation.

## Data

Intentions, ratings, extension reasons, opening attempts, and all retained sessions are stored in app-private preferences. Android backups are disabled. There is no analytics SDK. After the study notice is acknowledged, changed journal snapshots are automatically uploaded using Firebase anonymous authentication; the app has internet/network-state and persisted-job boot permissions for this. No network upload occurs before notice acknowledgment or without Firebase configuration. Accessibility window-content access is enabled only to read the focused root's package name; the app never reads node text or walks children. Android's external speech provider may process speech online; text entry is always available. The history screen shows the latest 30 entries; researcher CSVs contain all retained data including unfinished sessions and abandoned openings. Local deletion affects the next `latest` snapshot but not prior researcher downloads/legacy snapshots. Uninstall stops future collection without erasing received data; see the study's retention policy.

## Verification

The normal checks are `./gradlew :shared:jvmTest :androidApp:assembleDebug` and the device checklist in [docs/DEVICE_TESTS.md](docs/DEVICE_TESTS.md).

In the implementation environment, Gradle and ADB could not start because local socket access is restricted. The provided `scripts/verify-cached.sh` fallback uses locally cached Android libraries, the Kotlin/Compose compiler, Android resource tools, and JUnit. It compiles the shared and Android sources and runs the common session tests without Gradle. It does **not** resolve the Gradle dependency graph, assemble an installable APK, or verify device behavior. It is intended only for this restricted environment; Android Studio / Gradle is the supported build path.

## Platform references

- [Kotlin Multiplatform version compatibility](https://kotlinlang.org/docs/multiplatform/multiplatform-compatibility-guide.html)
- [Android Multiplatform library plugin and separate application module](https://developer.android.com/kotlin/multiplatform/plugin)
- [Android accessibility service setup](https://developer.android.com/guide/topics/ui/accessibility/service)
- [Android background activity launch restrictions](https://developer.android.com/guide/components/activities/background-starts)
