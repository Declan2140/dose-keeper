# Dose Keeper (working name)

A medication reminder for Android that rings every time, works fully offline, and needs no account.
Tracks medicines for you, your family and your pets.

Spec: see the "Medication reminder MVP spec" doc in the App Redesign project.

## Install the test build on your phone

1. Open the repo's **Actions** tab, pick the latest green **Build** run, and download
   **dose-keeper-debug-apk** under Artifacts. It's a zip with one `.apk` inside.
2. Copy or download the `.apk` to your phone and open it. Android asks to allow installs from that
   app (Files, Chrome or Drive); allow it once.
3. Open Dose Keeper and work through the setup screen. On a Samsung phone, also follow
   Settings → Reliability checklist → Samsung steps, then tap **Test alarm in 10 seconds** and lock the phone.

The test build installs as "Dose Keeper" with the package `app.dosekeeper.debug`, so it can sit next
to the Play Store version later. In test builds, Settings has a switch to pretend the $5 unlock was
bought, since purchases only work once the app is on Google Play.

## Layout

- `core/` is plain Kotlin with no Android code: schedules, the dose state machine, backup
  encryption. All the scheduling rules (including daylight saving changes) are tested here.
  Run with `./gradlew :core:test`.
- `app/` is the Android app: Jetpack Compose UI, Room database, AlarmManager alarms, WorkManager
  watchdog, Play Billing.

## How reminders stay reliable

- Each medicine has exactly one alarm set: its next dose, via `AlarmManager.setAlarmClock`.
- An alarm that goes unanswered rings again every snooze interval until the dose is answered or
  counts as missed (2 hours by default).
- Alarms are re-set after a reboot, app update, clock change and time zone change, and a daily
  watchdog repairs anything missing.
- The reliability checklist shows what Android or the phone maker is blocking, with fix buttons.

## Build

Needs JDK 17 and the Android SDK (API 35). `./gradlew :app:assembleDebug`. CI builds every push.

## Credits

Geist font by Vercel, under the SIL Open Font License 1.1 (see `FONT-LICENSE-Geist.txt`).
