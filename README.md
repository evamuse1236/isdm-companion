# ISDM Companion

A small local dashboard over [lms.isdm.org.in](https://lms.isdm.org.in) that does the three
things you actually need every day:

- **One-tap attendance.** The big button appears the moment the LMS will accept a mark, with a
  live countdown of how long you have left to be counted on time.
- **A desktop alert** when a class opens for marking, so you don't have to keep the page open.
- **Your schedule, already filtered** to your own section and group — with the room for every
  session, which normally takes a click into each session page to find.

It runs entirely on your own machine. Your login lives in a local file, and the app talks to
nothing except the LMS itself.

<sub>Not affiliated with or endorsed by ISDM. It drives the same endpoints the LMS's own web
pages use, as you, with your own login.</sub>

## Setup

You need [Node.js](https://nodejs.org) 20 or newer (pick the LTS installer). There are no npm
packages to install — the app has zero dependencies.

1. Download this project and unzip it somewhere sensible.
2. Double-click **`setup.cmd`** (or run `npm run setup`).
3. Enter your LMS email and password. Setup checks them against the LMS straight away, and
   offers to put a launcher on your Desktop.

Then start it any time from the Desktop launcher, or:

```bash
npm start
```

and open **http://localhost:4321**.

Your details go into a `.env` file next to the app. It is gitignored, never uploaded, and you
can delete it at any time to wipe your credentials.

> **Google sign-in.** The app logs in with the email/password form. If you only ever use
> *Sign in with Google*, set a password on your LMS account first (Profile → Change Password).

### Settings

Edit `.env` to change any of these.

| Variable | Default | What it does |
| --- | --- | --- |
| `PORT` | `4321` | Local port for the dashboard |
| `NOTIFY` | `1` | Windows toast when a class opens for marking; `0` turns it off |
| `LATE_AFTER_MINUTES` | `10` | How long you have to mark before being counted late — drives the countdown |
| `AUTO_MARK` | `0` | Mark classes automatically — see below |
| `AUTO_MARK_HOURS` | `0` | Extra expiry on an arming; `0` = while the app is open |
| `SHUTDOWN_AT` | `18:00` | Stop the server at this time daily; blank = never |
| `ROOM_FLOORS` | `Sahyog:3,Majlis:6` | Which floor each room is on |
| `LOG_LEVEL` | `info` | `debug` for much more detail |
| `COHORTS` | auto | Override cohort detection, e.g. `Section A,Group 1` |

## Auto-marking, and the guards on it

With `AUTO_MARK=1`, the app marks you present the moment the LMS opens a class — no tap.

This is only honest if the app is running **because you are sitting in the class**. Left
running unattended, it records you present for sessions you never attended, which is a
misrepresentation your institution acts on.

The intended shape is: you start the app when you get in, it covers the teaching day, and it
stops itself in the evening. Two guards keep that from drifting:

- **`SHUTDOWN_AT`** stops the server at the end of the day (default 18:00), so it cannot sit
  running for days on end.
- **Auto-marking never carries into a new calendar day.** Even if the server somehow survives
  midnight, it will not mark you into tomorrow's 9:30 class without you arming it again.

`AUTO_MARK_HOURS` adds a shorter expiry on top if you want one — set it to `3` and an arming
lasts three hours. `0` means "for as long as the app is open".

The dashboard always shows whether auto-marking is on and what will stop it, and there's a
switch to turn it off the moment you need to step out.

**Do not combine `AUTO_MARK=1` with launching the app at Windows startup.** That removes the
"I opened it because I'm here" signal entirely, and the app will quietly mark you present
every class day whether you turn up or not.

If you'd rather stay in control, leave `AUTO_MARK=0`: you still get the alert and the one-tap
button, which is most of the convenience with none of the problem.

## Rooms and floors

The LMS only tells you a room name. `ROOM_FLOORS` maps names to floors so the dashboard can
show *Sahyog · Floor 3* instead of leaving you guessing in a stairwell. Add rooms as you meet
them:

```
ROOM_FLOORS=Sahyog:3,Majlis:6,Aangan:1
```

Unknown rooms simply show without a floor. Use `:0` for the ground floor.

## The activity log

Everything the app does is written to `logs/YYYY-MM-DD.log` and shown in the dashboard's
**Log** tab, so at the end of the day you can see exactly what happened:

```
09:41:09  INFO  [boot]     signed in as uid 1042 (Section B, Group 3)
09:41:09  INFO  [boot]     auto-mark ARMED for as long as the app is open
09:41:09  INFO  [boot]     will shut down at 18:00 (in 499 min)
11:30:12  INFO  [auto]     Excel is open - marking (attempt 1)
11:30:13  INFO  [auto]     marked Excel (1285329) - status Present
```

The Log tab filters to **Problems only** (warnings and errors), **Auto-mark**, or **Marks**,
which is usually the fastest way to answer "did it actually mark everything today?". Logs are
kept for 30 days and are gitignored.

## How it works

Everything comes from endpoints the LMS's own front-end uses. There is no scraping of rendered
pages beyond two small, very regular tables.

| Endpoint | Used for |
| --- | --- |
| `GET /user/login` + `POST` | Drupal form login; fields are read off the form rather than hard-coded |
| `GET /calendar/json?start=&end=` | All events in a range. Entries whose `url` is `/classroom/{nid}/view` are attendance sessions and are already personalised to you |
| `GET /classroom/{nid}/view` | Room (`Location`), trainer, course, and whether you're marked |
| `GET /manage/classroom/attendance` | Whether marking is open right now |
| `POST /api/mark/classroomsession/attendance` | `{nid, uid, status:"present"}` — the actual mark |

Two details worth knowing:

**The marking window is the LMS's call, not ours.** On `/manage/classroom/attendance` each row
carries a `mark-attend` class when the server will accept a mark and `mark-attend-disabled`
when it won't. The app reads that flag instead of guessing from the clock, so the button
enables exactly when the real one does.

**Every class appears in the calendar twice** — once as a batch-wide session event and once as
your personalised attendance event. The app merges the pair into a single row. Leftover
batch-wide events (like group-split field visits, which have no attendance record) are filtered
to your section and group, learned from the attendance sessions the LMS shows only to you.

## Testing

```bash
npm test
```

Twenty-six checks over real captured LMS responses and a local stub — title parsing, cohort
filtering, event merging, the detail and attendance-list HTML, the login form, room floors,
the auto-mark guards, the daily shutdown time, the log, and the exact shape of the mark
request. No credentials, no calls to the real LMS.

## Troubleshooting

**"LMS rejected the login"** — check `.env`. If you only ever use Google sign-in, set an LMS
password first.

**The Mark button never turns on** — the LMS opens the window itself, usually a few minutes
before the start. Cross-check the row on `/manage/classroom/attendance`; if its button is grey
there too, the LMS hasn't opened it yet.

**A class is missing, or one you don't attend shows up** — cohort auto-detection went wrong.
Set `COHORTS` explicitly in `.env`, e.g. `COHORTS=Section A,Group 1`.

**No desktop toasts** — check Windows notification settings, or set `NOTIFY=0` and rely on the
browser alerts instead.

**Port already in use** — something else is on 4321. Change `PORT` in `.env`.

## Layout

```
src/lms.js        LMS client: login, session handling, the five endpoints
src/schedule.js   merging events, cohort filtering, session state
src/service.js    caching layer
src/automark.js   auto-mark guards and the daily shutdown time
src/rooms.js      room -> floor lookup
src/log.js        daily activity log
src/server.js     local HTTP server + background watcher
public/           the dashboard (no build step, no framework)
tools/setup.js    first-run setup
tests/            self-tests, no credentials needed
logs/             daily activity logs (gitignored)
```

## Licence

MIT — see [LICENSE](LICENSE).
