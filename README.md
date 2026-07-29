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
| `AUTO_MARK_HOURS` | `3` | How long an auto-mark arming lasts |
| `COHORTS` | auto | Override cohort detection, e.g. `Section A,Group 1` |

## Auto-marking, and why it expires

With `AUTO_MARK=1`, the app marks you present the moment the LMS opens a class — no tap.

This is only honest if the app is running **because you are sitting in the class**. Left
running unattended, it will record you present for sessions you never attended, which is a
misrepresentation your institution acts on.

So the arming is deliberately temporary. Auto-marking stays active for `AUTO_MARK_HOURS` after
the server starts, or after you flip the switch at the top of the dashboard, and then stops
until you arm it again. The dashboard always shows whether it is on and when it runs out, and
you get a notification when the window closes.

**Do not combine `AUTO_MARK=1` with launching the app at Windows startup.** That removes the
"I opened it because I'm here" signal entirely, and the app will quietly mark you present
every class day whether you turn up or not.

If you'd rather stay in control, leave `AUTO_MARK=0`: you still get the alert and the one-tap
button, which is most of the convenience with none of the problem.

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

Twenty checks over real captured LMS responses and a local stub — title parsing, cohort
filtering, event merging, the detail and attendance-list HTML, the login form, the auto-mark
arming window, and the exact shape of the mark request. No credentials, no calls to the real
LMS.

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
src/automark.js   the arming window
src/server.js     local HTTP server + background watcher
public/           the dashboard (no build step, no framework)
tools/setup.js    first-run setup
tests/            self-tests, no credentials needed
```

## Licence

MIT — see [LICENSE](LICENSE).
