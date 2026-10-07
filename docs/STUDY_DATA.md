# Collecting study data (0.5.0)

## Participant workflow

Open Intentional and tap **Export study data (CSV)** near the top of Home. It is also available at the bottom of other screens and in the uninstall dialog. Android opens its share chooser: select WhatsApp, Telegram, Gmail, or another installed app that accepts CSV attachments, then choose the recipient and send. Intentional never selects a recipient or sends automatically. Canceling does not change the journal.

The export includes free-text motivations, so participants should send it only to their intended study recipient. A random installation ID identifies repeated exports from the same installation; it is not a name, email, Android device ID, or advertising ID. Researchers can keep their own participant-to-installation mapping. Reinstalling generates a new ID.

Each tap generates a new UTF-8 CSV attachment in app-private cache, shared through a narrowly scoped FileProvider with temporary read access. Android may clear cached copies; the journal remains until the user deletes it or uninstalls. The existing JSON export remains available in Your patterns as an additional history backup.

## One CSV, four record types

| `record_type` | Meaning |
| --- | --- |
| `metadata` | Participant ID, export time, opening-tracking start time; present even with no records. |
| `opening` | An observed app entry or a tap on Intentional’s intentional-launch button. Includes any captured draft motivation, baseline rating, chosen duration, and linked session ID. Abandoned check-ins remain in the data. |
| `session` | One started session, including incomplete sessions. Contains app, selected minutes, baseline rating, motivation, purpose, tracked elapsed seconds, after rating, fulfillment, and extension totals. |
| `extension` | A continuation reason and duration, linked to its session. |

All records include `schema_version`, `participant_id`, `exported_at_utc_ms`, `tracking_started_at_utc_ms`, `record_type`, and `record_id`. Related records use `opening_id` and `session_id`. `is_demo` identifies demos. Times ending in `_utc_ms` are Unix milliseconds in UTC; durations explicitly use minutes or seconds. Ratings are 1–5, from not at all relaxed to very relaxed. Ratings begin at the UI default of 3 unless adjusted; submitted defaults are included.

`selected_minutes` is the original selection; `extension_minutes` is additional selected time. New real sessions use `timing_method=observed_foreground`. `tracked_elapsed_seconds` accumulates intervals while the corresponding app is observed in the foreground with the screen unlocked, including extensions. Leaving, locking the screen, or entering Intentional pauses both usage and the remaining timer immediately; keyboard use in the foreground app remains counted. Returning before five minutes resumes the same interaction with its unused budget. Separate apps retain separate paused interactions.

At five minutes away, the interaction is finalized with `status=inactivity_timeout`. `relaxation_after` and `task_completed` contain the literal **N/A**, not a zero or false result; the original intention/rating and observed usage remain. The timer resets, and returning starts a new gate. This occurs without reopening Intentional or asking for a response. Paused and active interactions exported before ending still have blank outcomes. `status=completed` means an explicit reflection was submitted, while `task_completed` records the Yes/No answer.

Usage relies on delivered foreground/lock events, not a retroactive OS usage-history query. If the process dies, recovery pauses at its last checkpoint (at most five seconds of uncheckpointed usage may be lost while the service is running normally); unobserved downtime is not fabricated as usage. Paused deadlines survive restart; reboot recovery uses wall time, so changing the phone clock can affect that case. If Android suspends execution, finalization occurs on the next tick/reopen and records the original five-minute deadline. Picture-in-picture and background audio are not counted as foreground usage when another app is foregrounded. Device event behavior needs testing.

Older records keep their original timing method: `elapsed_until_checkin` for 0.4.0, or `legacy_capped_elapsed` for older versions. Do not reinterpret those durations as foreground time. Demo sessions use `demo_elapsed` and continue ticking in Intentional without real app detection.

## Counting openings accurately

Count `opening` rows, not `session` rows. `opening_source=foreground_entry` means the enabled accessibility service observed a transition into the supported native app; `intentional_launcher` means the user requested a session through Intentional, possibly stepping away before launching. Both count as **opening attempts**, and the summary separates them. Repeated window events within the same foreground app do not count again. Returns initiated by Intentional to start/resume/extend are suppressed for up to five seconds so the same guided entry is not counted twice. Banner/dialog events from Intentional are ignored as app transitions.

Counts are observations, not a complete phone usage log. Tracking starts with this version, cannot reconstruct older openings, and misses entries while the service is disabled or stopped. The first observed app after a service restart may be counted as a new entry. Browser use, YouTube Music, and third-party clients are outside scope. `tracking_started_at_utc_ms` marks the start of retained tracking, not proof that the service remained enabled. Deleting the journal clears openings too and resets that timestamp. Previously exported data cannot be recalled.

No 500-session truncation is applied from this version onward. Already-discarded records from older versions cannot be recovered. The screen still shows only the latest 30 sessions, but the CSV contains all retained data.

## Researcher workflow

Save participants’ CSV attachments into a folder, then run:

```sh
python3 scripts/aggregate-study.py /path/to/received-exports --output study-results
```

Python 3.9+ is sufficient; no packages are required. Use a new output folder each run. The script searches the input folder recursively and produces:

- `all_records.csv`: all unique records across participants, including demo and incomplete rows, with the latest export of each record retained.
- `participant_app_summary.csv`: one row per participant/app with opening attempts by source, sessions started/completed, fulfilled intentions, mean selected duration, and paired before/after relaxation means and changes. `sessions_ended_without_response` counts inactivity timeouts. `total_ended_tracked_seconds` and `mean_ended_tracked_seconds` include both explicit completions and timeouts, so unattended usage is retained. Existing `*_completed_tracked_seconds` columns include only explicit reflections. Demo sessions are excluded. Incomplete sessions and N/A outcomes never enter outcome averages; empty averages stay blank.

Repeated submissions are deduplicated by participant ID + record type + record ID, using the latest export timestamp. Do not change the phone’s date/time during the study; export ordering uses its wall clock. A later export does not remove records previously received if the participant has since deleted their local journal. Researcher deletion requests must therefore be handled separately. Raw motivation strings are preserved apart from CSV escaping and a leading apostrophe for strings that could be interpreted as spreadsheet formulas. CSV parsers correctly handle quoted commas, newlines, and Unicode.

## Verify on study phones

Try direct and Intentional-initiated openings, declining the opening prompt, finishing early, extending, canceling export, and sharing to each recipient app used by the study. Confirm one guided launch is not double-counted and that the receiver can open the attachment after Intentional is backgrounded. Export twice and aggregate both: counts should not double. Export during a running session: its after-rating should be blank. Confirm old journals still load and reinstalling changes the participant ID. Full Android sharing and accessibility behavior need on-device verification.
