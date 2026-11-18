# Countertop

Android medication reminder (Kotlin, Jetpack Compose, Room). Package `app.countertop`, Kotlin
namespace `app.dosekeeper`. See `README.md` for the layout and how reminders stay reliable.

## Product rules

- Works fully offline. No account, no ads, no analytics, no network calls except Play Billing.
- One payment of $5 unlocks extra people and pets, full history, custom alarm sounds, refill
  tracking and doctor reports. Reminders, every schedule type, backups and the home screen widget
  are free. Never add a subscription.
- Reliability comes first. Anything that touches alarms or notifications must not make a reminder
  quieter, later or less likely to ring.
- Never lose data. Every database change is a numbered Room migration; destructive migration is
  never enabled. New columns go into the backup file too.

## Design rules

- Sleek and quiet: black, white and grey with one green accent (`Accent` in `ui/Theme.kt`), Geist
  font. No per-person colors, no colorful pill-organizer look.
- Color is allowed in one place only: inside the small pill picture (`ui/PillVisualizer.kt`), from
  the muted `PillColor` list.
- The counter art (`ui/CounterScene.kt`) stays uncolored and unlabeled: one container per medicine
  still due today.
- In-app Snooze buttons just say "Snooze". Only the notification button says the length.

## Motion rules

- Animate only scale, position and opacity, set inside `graphicsLayer { }`. Never animate a width,
  height, padding or color: those make the screen measure or rebuild itself on every frame.
- Use the shared springs in `ui/Motion.kt` rather than fixed timers, so a second tap partway
  through reverses smoothly.
- Anything that slides, grows or bounces needs a fade for people who turned on the phone's "Remove
  animations" setting. Read `LocalReducedMotion` and use `Motion.fade()` in its place.
- Dose rows on Today are rounded cards (`CardShape`, `cardColor()`), with no divider lines.

## Accessibility (WCAG 2.2 AA)

- Touch targets are at least 48dp. Give buttons `heightIn(min = …)`, not a fixed height, so text
  can grow.
- All text uses the theme's styles (sp), and screens must hold up at 200% font size.
- Text contrast at least 4.5:1, and 3:1 for the outlines of controls, in light and dark.
- Never signal a status by color alone: pair it with a word or an icon.
- Rows should read as one sentence in TalkBack; pictures get a content description.

## Privacy

- With "Hide medicine names on lock screen" on (the default), nothing shown while the phone is
  locked may name a medicine or a person: the notification and the full-screen alarm say only
  "Medication reminder".

## Code rules

- Scheduling, supply and other rules live in `core/` (plain Kotlin, unit tested with
  `./gradlew :core:test`). `app/` stays thin.
- Every change to a dose goes through `ReminderEngine`, so the log, supply count, alarms,
  notifications and widget stay in step. Don't write dose logs from anywhere else.
- A taken time is the real moment it was tapped. A dose can be unchecked (latest answer only), but
  the time is never editable. Snooze is a temporary state on the medicine, not a log entry.
- The Android build can't run in Claude's cloud container (no Google Maven). GitHub Actions builds
  and tests every push and pull request; check the run before calling work done.
- Comments and user-facing text are plain English, written for the person using the app.
