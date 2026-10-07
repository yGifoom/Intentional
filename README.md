# Intentional

An Android prototype that adds a pause before Instagram or YouTube and a reflection afterward. Built with **Kotlin Multiplatform and Compose Multiplatform**, with the dark/violet visual direction from `ui idea/`.

## YouTube support (0.2.0)

The same accessibility service now covers the official Instagram and YouTube Android apps, including YouTube Shorts. Choose either app on Your space to launch intentionally or try its demo. Opening either app directly also triggers the check-in when accessibility is enabled. Warnings, expiry prompts, intentions, and journal records identify the app. Existing records load as Instagram sessions.

Only the foreground app accrues usage. As of 0.5.0, switching between protected apps keeps a separate paused interaction for each, resumable within five minutes; pending expiry/reflection questions still require resolution. The service component name is preserved so updating does not intentionally reset accessibility access. Check it remains enabled after installation. Rebuild/share the latest APK with `./scripts/share-android.sh`; use the same signing key to retain data.

The Instagram walkthroughs below also apply to YouTube. Browser playback, YouTube Music, and third-party clients are not covered. An interruption brings Intentional forward; it does not explicitly pause video/audio or stop YouTube picture-in-picture/background playback. These need device testing, particularly on YouTube Premium accounts.

## Try it

Version 0.5.0 pauses a live session as soon as the moderated app leaves the foreground or the screen locks. Returning within five minutes resumes the remaining time. Otherwise, the interaction is saved with only observed usage, `status=inactivity_timeout`, and `N/A` for relaxation afterward and task completion. It then resets silently. Yes/No completion buttons have identical styling, and completion questions have no Back button.

Version 0.4.0 adds **Export study data (CSV)** and Android’s share chooser, plus opening-attempt tracking and a desktop aggregation script. See [study data instructions and field definitions](docs/STUDY_DATA.md). Opening tracking is prospective; prior versions cannot supply historical opening counts.

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

Version 0.3.0 adds **Uninstall Intentional** at the bottom of every app screen. Users can export their journal first, then open Android’s uninstall confirmation. Canceling leaves sessions and accessibility unchanged; confirmed removal deletes local app data and stops check-ins. If the direct uninstaller is unavailable, Intentional opens its Android App info screen instead. Long-pressing the launcher icon → App info → Uninstall also works.

Run `./scripts/share-android.sh` for a same-Wi-Fi download link, or `./scripts/share-android.sh --public` for a temporary internet link (requires cloudflared). The script builds the APK and provides a mobile download page with installation instructions. See [distribution instructions](docs/DISTRIBUTION.md) for existing APKs and permanent hosting bundles.

## Session flow

- **Opening pause:** “Is this intentional?” The user can step away without starting a session.
- **Intention:** editable voice transcription or text, a purpose category, and a 1–5 baseline relaxation rating.
- **Time:** defaults to **15 minutes**, with presets and adjustment from 1 to 120 minutes.
- **Session:** Instagram opens. At one minute remaining, the app posts a notification and a temporary banner over Instagram. The banner opens the session controls.
- **Time is up:** the check-in interrupts Instagram. Finishing leads to reflection; continuing requires a reason and a new duration. Each extension has its own reminder and expiry.
- **Leaving early:** switching away or locking the screen pauses the timer without opening a check-in. Returning within five minutes resumes it; five minutes away saves the usage with unanswered outcomes and resets the timer.
- **Final reflection:** a second relaxation rating and two equally styled **Yes / No** buttons. Neither is preselected or emphasized; both save an honest outcome, followed by a close button. No Back button is shown on either completion question.
- **Patterns:** local history with extension reasons, elapsed time, fulfillment, and before/after relaxation. JSON export through Android's document picker; deletion with confirmation.

Purpose selection is explicit rather than inferred from text. The relaxation summary includes only real sessions categorized as Relax. These are self-reported associations, not evidence of causation. The app does not claim personalized time recommendations from usage data.

## Code layout

```text
shared/src/commonMain/kotlin/org/intentional/shared/
  SessionEngine.kt       Platform-independent session state and timer transitions
  IntentionalApp.kt      Compose Multiplatform screens and components
  JournalCodec.kt        Versioned local persistence / export schema
shared/src/commonTest/   Session and persistence tests, run on the JVM target
androidApp/src/main/
  kotlin/.../MainActivity.kt                    Speech, settings, export, Instagram launch
  kotlin/.../InstagramAccessibilityService.kt   App transitions, reminders, interruptions
  kotlin/.../IntentionalApplication.kt          Process-wide engine and local storage
```

The shared module has Android and JVM targets. UI and state logic live in `commonMain`; Android services stay in the Android app. This delivery targets Android; no iOS interception or desktop app is implemented.

## Android behavior and limits

This is a companion app, not a modification of Instagram. The user explicitly enables an accessibility service. The service listens to package/window changes and brings the Compose activity forward; it cannot prevent Instagram's process from starting, so a brief view of Instagram may appear first. Returning to Instagram while a check-in is pending brings the check-in back.

The one-minute banner uses `TYPE_ACCESSIBILITY_OVERLAY`. Expiry opens Intentional while the session’s app is foregrounded. Android accessibility window events pause/resume the timer as apps change, and screen-off/lock pauses it too. Keyboard input within the app remains counted; time in Intentional, another app, or away from the screen does not. The timer is observed foreground time, not a retroactive Android usage-history query. Periodic checkpoints preserve confirmed usage across process restarts; unobserved gaps are never added. Five-minute inactivity deadlines survive restarts and use wall-clock recovery after a reboot. Physical-device event delivery still needs validation.

The service must remain enabled and running for background interruption. Android or manufacturer battery management, force-stop, screen sleep, and background activity rules can delay or prevent a prompt. There is no exact-alarm or wake-lock guarantee; an overdue session is checked when execution resumes. Incoming system panels and keyboards are ignored; another launchable app is treated as leaving Instagram. Check this behavior on the participant's actual device. Closing returns to Home; it does not force-stop Instagram.

Accessibility declarations and behavior would require a separate distribution review before a Play Store release. This project is a research prototype for local installation.

## Data

Intentions, ratings, extension reasons, opening attempts, and all retained sessions are stored in app-private preferences. There is no server, analytics SDK, or app internet permission. Android backups are disabled. The app never retrieves the accessibility node tree or reads Instagram content. Android's external speech provider may process speech online; text entry is always available. CSV sharing happens only when the user chooses an app and recipient. The history screen shows the latest 30 entries; exports include all retained entries, and CSV also includes unfinished sessions and abandoned opening attempts.

## Verification

The normal checks are `./gradlew :shared:jvmTest :androidApp:assembleDebug` and the device checklist in [docs/DEVICE_TESTS.md](docs/DEVICE_TESTS.md).

In the implementation environment, Gradle and ADB could not start because local socket access is restricted. The provided `scripts/verify-cached.sh` fallback uses locally cached Android libraries, the Kotlin/Compose compiler, Android resource tools, and JUnit. It compiles the shared and Android sources and runs the common session tests without Gradle. It does **not** resolve the Gradle dependency graph, assemble an installable APK, or verify device behavior. It is intended only for this restricted environment; Android Studio / Gradle is the supported build path.

## Platform references

- [Kotlin Multiplatform version compatibility](https://kotlinlang.org/docs/multiplatform/multiplatform-compatibility-guide.html)
- [Android Multiplatform library plugin and separate application module](https://developer.android.com/kotlin/multiplatform/plugin)
- [Android accessibility service setup](https://developer.android.com/guide/topics/ui/accessibility/service)
- [Android background activity launch restrictions](https://developer.android.com/guide/components/activities/background-starts)
