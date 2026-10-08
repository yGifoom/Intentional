# On-device acceptance checklist

These checks require an Android device or emulator. They have not been executed in the restricted development environment.

## Demo and layout

- Launch Intentional without Instagram or accessibility enabled. Try the 75-second demo.
- Verify the opening pause; “Not really” should return to Home without a journal record.
- Enter an intention containing accented characters, emoji, quotes, and multiple lines.
- Verify the keyboard does not cover the submit button; test a small display and large font setting.
- Check the default 15-minute selection, presets, lower/upper bounds, and Back navigation.
- Start the demo. At 60 seconds remaining the reminder should appear in the app. Use the demo controls to exercise expiry.
- Ask for more time: blank reasons cannot continue; set a reason and duration. Verify a second reminder and expiry.
- Finish with both Yes and No. Both buttons must have the same size, fill, border, and text styling, with neither selected. Each should save exactly one record with matching before/after ratings. No Back button should appear on expiry or final reflection, and Android Back must not change either question.
- Demo records must not change real-session pattern statistics.

## Real Instagram interception

Repeat this section for the official YouTube Android app, including a regular video and Shorts. Confirm every screen, banner, notification, and exported record uses the correct app name. Check landscape playback, picture-in-picture, and background audio: the check-in interrupts foreground use, but does not explicitly pause playback.

Switch directly between Instagram and YouTube during an active session. The prior interaction must pause and keep its app label, while the new app starts its own gate/timer. Returning within five minutes must restore the original interaction and unused time. After five minutes, the old interaction must be saved with N/A outcomes without affecting the new app. Also check a rapid switch near expiry and upgrading with existing journal records and accessibility enabled.

- Enable accessibility via the disclosure flow. Try allowing and denying notification permission on Android 13+.
- Open Instagram from the launcher, Recents, and a deep link. Each new session should ask for an intention.
- During an active session, reopening Instagram within five minutes should preserve the remaining usage budget and skip the initial gate.
- Set a two-minute session. Verify a notification / temporary overlay with one minute remaining and a check-in at expiry.
- Set a one-minute session, leave YouTube idle on its home page, and compare against a stopwatch. It should expire near 60 seconds of foreground use, not two minutes. Repeat with Instagram, with the reminder visible, and after upgrading/re-enabling Accessibility. Background notifications/toasts must not pause the countdown. A focused system panel should pause it; dismissing the panel must resume without needing to navigate within YouTube.
- At expiry, verify only **Finish and check in** / **Add more time** are offered. After finishing (or choosing early finish), the relaxation screen has exactly one fulfillment question with equal Yes/No buttons. Repeat through multiple extensions and Back navigation: no prior screen should ask fulfillment or record an answer.
- Tap the warning banner; finish early or extend with a reason. Confirm the original intention remains visible.
- Try switching back to Instagram during the gate, extension, and reflection. The pending check-in should return.
- Leave Instagram using Home, Recents, another app, and Intentional. The countdown and usage must pause immediately, without opening a reflection screen. Wait one minute and return: only foreground time should have been used.
- Leave for five minutes. Verify the timer resets, exactly one record is saved, tracked time excludes all time away, and the CSV has `inactivity_timeout` with `N/A` in both outcome columns. Returning must open a fresh intention gate.
- Open/close the keyboard: input within the app should still count. Open/close the notification shade: system UI should pause usage and returning to the app should resume it. Neither should finalize an interaction before five minutes.
- Verify closing the final screen returns to Home without reopening Instagram.

## Lifecycle and privacy

- Verify the one-time automatic collection notice appears before any upload, lists all transmitted fields, fits/scrolls on small displays, and cannot be silently dismissed. **Close app** must leave uploads disabled; **Continue** acknowledges it and enables default background collection. Reopen/rotate: an acknowledged notice must not reappear. No participant export, share, JSON backup, manual-send, or sync controls should appear on any screen.
- Download the received CSV with the researcher tool and inspect Unicode/multiline motivations, app labels, original duration, both ratings, tracked time, and extension reasons. Open Intentional during an ongoing session: its timer pauses, and final rating/outcome remain blank in the snapshot until answered or timed out.
- Record a declined opening, a completed session, and a return through Intentional. Confirm the declined attempt is retained and the managed return is not counted twice. Verify opening counts after app restart.
- Combine two exports from one installation with `scripts/aggregate-study.py`; their overlapping records should not double-count. Combine exports from two installations and verify separate participant IDs.
- Configure a test Firebase project per [FIREBASE_SUBMISSIONS.md](FIREBASE_SUBMISSIONS.md) and deploy the updated rules. Confirm no upload occurs without configuration/notice acknowledgment. After acknowledgment, inspect automatic creation and replacement of the installation's `latest` CSV, with local data retained. No changes must mean no further uploads. Make changes inside the cooldown; restart: they remain pending and upload only after the 15-minute minimum. Test offline retry, interrupted/rotated uploads, process death, reboot, and a journal over 512 KiB (blocked, retained locally). Check exact byte limit, cross-account denial, no read/delete access, canonical replacement, and concurrent requests in the emulator suite before live collection.

- Tap **Uninstall Intentional** from both Home and an active session. Cancel the in-app dialog and then, separately, Android’s confirmation: records, session state, and check-ins must remain intact.
- The uninstall dialog must have no export link; it warns that unsynced changes can be lost and received study data is not erased.
- Confirm uninstallation on a test device. Only Intentional should be removed; Instagram and YouTube must remain, and check-ins must stop. Reinstall and verify the local journal is empty (previously exported files remain at their chosen location).
- If the device cannot open the uninstall confirmation directly, verify fallback to Intentional’s App info screen.

- Rotate the device and recreate the activity in the middle of an intention, timer, extension, and reflection. State should survive.
- Recreate the app process during a session. It must recover confirmed usage, exclude the unobserved gap, and retain the original inactivity window. Returning within five minutes resumes unused time; after five minutes it creates an N/A record.
- Lock the phone for one minute, unlock, and reopen Instagram: the timer should resume unchanged. Repeat with a five-minute lock: the interaction should be saved with N/A outcomes and the next opening should start a new gate.
- Reboot during a running or paused interaction. Confirmed usage must survive. Recovery must not count the reboot interval or grant a new five-minute window.
- Disable accessibility during a session. The app should show the disabled state on resume; no background interception is promised.
- Test voice input success, cancellation, and an emulator without a speech provider; text should remain available.
- Delete the journal, restart, and verify records remain deleted locally. The next `latest` snapshot must reflect the remaining journal; researcher downloads and legacy cloud snapshots are not erased by local deletion.
- Test accessibility prompt behavior on each participant's Android version and phone manufacturer before a study.
