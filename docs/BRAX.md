# Brax Calendar

A fork of [Fossify Calendar](https://github.com/FossifyOrg/Calendar) that the Brax chat app can open from
agent cards. The fork adds one link scheme and one tool registry; everything else is upstream.

## How it fits

```
Stalwart (hub mail + CalDAV)  <-- CalDAV sync adapter (DAVx5) -->  Android calendar provider
                                                                        |
Brax chat app  -- card action "calendar.open_event" --> braxcal://event/<uid> --> Brax Calendar
```

* Events live on the hub's Stalwart server. A CalDAV sync adapter keeps them in the Android calendar
  provider; the app syncs the provider calendars it was told to (Settings → CalDAV → Manage synced calendars).
* The Calendar agent (bridge in `brax-chat`) books events on Stalwart with a UID such as `evt_9f2c1a7b` and
  attaches a signed card whose action names a **tool**, never a package or a screen.
* The chat app resolves the tool through this app's registry and follows the link. The calendar app is the
  only component that interprets the link.

## Links (`braxcal://`)

| Link | Opens | Notes |
| --- | --- | --- |
| `braxcal://day/2026-09-28` | The day view on that date | `yyyy-mm-dd` only |
| `braxcal://event/<uid>` | The event with that iCalendar UID | Resolved through the provider's `UID_2445` / `_SYNC_ID` (`<uid>.ics`), then the app's own import ids. Unknown UID → "Event not found" toast, nothing is created |
| `braxcal://draft?title=…&start=…&end=…` | A prefilled, **unsaved** new event | `start`/`end` ISO 8601 UTC (`2026-10-01T18:00:00Z`); optional `all_day=1`, `location`, `description`. The person still taps save |

Malformed links show a toast with the reason and do nothing else. Parsing and resolution live in
`app/src/main/kotlin/org/fossify/calendar/helpers/BraxLinks.kt`; the intent filter is on `MainActivity`.

## Tool registry

`AndroidManifest.xml` carries `<meta-data android:name="net.braxtech.tools" android:resource="@raw/brax_tools" />`.
The chat app reads that JSON (`res/raw/brax_tools.json`) with `PackageManager.getApplicationInfo(GET_META_DATA)`
and `Resources.openRawResource`, and uses it to validate card actions before offering them:

| Tool | Link | Confirm | Shared |
| --- | --- | --- | --- |
| `calendar.open_event` | `braxcal://event/{event_id}` | no | yes (a card for someone else may still offer it) |
| `calendar.open_day` | `braxcal://day/{date}` | no | no |
| `calendar.create_event` | `braxcal://draft?title={title}&start={start}&end={end}` | yes | no |

The same three entries are pinned on the server side (`brax-chat` → `account_chat.agents.registry`), so a card
can only ever name a tool both sides know.

## Building

Same as upstream: `./gradlew assembleCoreDebug` (JDK 21, compileSdk 36). The debug build installs as
`org.fossify.calendar.debug` and shows as "Brax Calendar". The application id stays `org.fossify.calendar`
because Fossify Commons derives icon-alias component names from it (`<appId>.activities.SplashActivity.*`);
renaming the id without moving the package tree breaks the icon-colour feature.

## Trying it on an emulator

```bash
adb shell 'am start -a android.intent.action.VIEW -d "braxcal://day/2026-09-28"'
adb shell 'am start -a android.intent.action.VIEW -d "braxcal://event/evt_lab0001"'
adb shell 'am start -a android.intent.action.VIEW -d "braxcal://draft?title=Dinner%20planning&start=2026-10-01T18:00:00Z&end=2026-10-01T18:20:00Z"'
```

Quote the whole `am start` for the device shell: an unquoted `&` in the link is taken as a shell operator and
the query is silently truncated. Without a CalDAV sync adapter you can seed the provider directly
(`adb shell content insert --uri "content://com.android.calendar/events?caller_is_syncadapter=true&…"` with
`uid2445` and `_sync_id`), then pick the calendar under Settings → CalDAV → Manage synced calendars.
