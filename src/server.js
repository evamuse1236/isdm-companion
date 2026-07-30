// Local dashboard server. Serves the UI from /public and a small JSON API, runs a background
// watcher that marks (or nudges you about) classes as they open, and shuts itself down at the
// end of the teaching day.

import http from 'node:http';
import fs from 'node:fs/promises';
import path from 'node:path';
import { spawn } from 'node:child_process';
import { fileURLToPath } from 'node:url';

import { LmsClient, LmsError } from './lms.js';
import { Service } from './service.js';
import { toast } from './notify.js';
import { AutoMarker, msUntilTimeOfDay } from './automark.js';
import { Logger } from './log.js';
import { keepAwake, releaseAwake } from './keepawake.js';
import { withRetry } from './retry.js';

const HERE = path.dirname(fileURLToPath(import.meta.url));
const ROOT = path.join(HERE, '..');
const PUBLIC_DIR = path.join(ROOT, 'public');
const PORT = Number(process.env.PORT || 4321);
const NOTIFY = process.env.NOTIFY !== '0';
const LATE_AFTER_MINUTES = Number(process.env.LATE_AFTER_MINUTES || 10);
const AUTO_MARK_HOURS = Number(process.env.AUTO_MARK_HOURS || 0);
const SHUTDOWN_AT = (process.env.SHUTDOWN_AT || '').trim();

const log = new Logger({
  dir: path.join(ROOT, 'logs'),
  level: process.env.LOG_LEVEL || 'info',
});

const client = new LmsClient({ email: process.env.LMS_EMAIL, password: process.env.LMS_PASSWORD });
const service = new Service(client, {
  lateAfterMinutes: LATE_AFTER_MINUTES,
  cohortOverride: process.env.COHORTS,
  roomFloors: process.env.ROOM_FLOORS,
});
const autoMarker = new AutoMarker({
  enabled: process.env.AUTO_MARK === '1',
  windowMs: AUTO_MARK_HOURS * 3_600_000,
});

const MIME = { '.html': 'text/html; charset=utf-8', '.js': 'text/javascript; charset=utf-8', '.css': 'text/css; charset=utf-8', '.svg': 'image/svg+xml', '.ico': 'image/x-icon' };

function sendJson(res, status, payload) {
  res.writeHead(status, { 'Content-Type': 'application/json; charset=utf-8', 'Cache-Control': 'no-store' });
  res.end(JSON.stringify(payload));
}

async function readBody(req) {
  const chunks = [];
  for await (const chunk of req) chunks.push(chunk);
  if (!chunks.length) return {};
  try { return JSON.parse(Buffer.concat(chunks).toString('utf8')); } catch { return {}; }
}

async function serveStatic(res, urlPath) {
  const rel = urlPath === '/' ? 'index.html' : urlPath.replace(/^\/+/, '');
  const file = path.join(PUBLIC_DIR, rel);
  if (!file.startsWith(PUBLIC_DIR)) { res.writeHead(403).end('Forbidden'); return; }
  try {
    const data = await fs.readFile(file);
    res.writeHead(200, { 'Content-Type': MIME[path.extname(file)] || 'application/octet-stream', 'Cache-Control': 'no-store' });
    res.end(data);
  } catch {
    res.writeHead(404, { 'Content-Type': 'text/plain' }).end('Not found');
  }
}

const server = http.createServer(async (req, res) => {
  const url = new URL(req.url, `http://localhost:${PORT}`);

  try {
    if (url.pathname === '/api/day') {
      return sendJson(res, 200, await service.day(url.searchParams.get('date')));
    }
    if (url.pathname === '/api/week') {
      return sendJson(res, 200, await service.week(url.searchParams.get('start')));
    }
    if (url.pathname === '/api/mark' && req.method === 'POST') {
      const { nid } = await readBody(req);
      if (!nid || !/^\d+$/.test(String(nid))) return sendJson(res, 400, { error: 'A numeric session id is required.' });
      log.info('mark', `manual mark requested for ${nid}`);
      const detail = await service.mark(String(nid));
      if (!detail.marked) {
        log.warn('mark', `${nid}: LMS accepted the request but still reports unmarked`);
        return sendJson(res, 409, { error: 'The LMS accepted the request but still shows you as unmarked. Its marking window may have closed.', detail });
      }
      log.info('mark', `marked ${detail.title || nid} - status ${detail.status}`);
      return sendJson(res, 200, { ok: true, detail });
    }
    if (url.pathname === '/api/automark') {
      if (req.method === 'POST') {
        const { on } = await readBody(req);
        on ? autoMarker.arm() : autoMarker.disarm();
        log.info('auto', on
          ? `armed from the dashboard${AUTO_MARK_HOURS ? ` for ${AUTO_MARK_HOURS}h` : ' for as long as the app is open'}`
          : 'disarmed from the dashboard');
      }
      return sendJson(res, 200, { ...autoMarker.status(), shutdownAt: SHUTDOWN_AT || null });
    }
    if (url.pathname === '/api/log') {
      const days = log.days();
      const date = url.searchParams.get('date') || days[0] || null;
      return sendJson(res, 200, { date, days, entries: date ? log.read(date) : [] });
    }
    if (url.pathname === '/api/whoami') {
      if (!client.uid) await client.login();
      const cohorts = await service.cohorts();
      return sendJson(res, 200, {
        uid: client.uid,
        name: client.displayName,
        sections: [...cohorts.sections],
        groups: [...cohorts.groups],
      });
    }
    if (url.pathname.startsWith('/api/')) return sendJson(res, 404, { error: 'Unknown endpoint' });

    return await serveStatic(res, url.pathname);
  } catch (err) {
    const status = err instanceof LmsError ? 502 : 500;
    log.error('api', `${url.pathname}: ${err.message}`);
    return sendJson(res, status, { error: err.message });
  }
});

// ---------------------------------------------------------------------------
// Background watcher
// ---------------------------------------------------------------------------

const TICK_MS = 30_000;
const notified = new Map(); // nid -> {opened, nudged, autoTries}
let watcherDay = null;
let lastAutoReason = null;
let lastTickAt = Date.now();

/**
 * Read the day, retrying transient failures. Straight after the machine wakes, networking
 * isn't up yet and the first attempt fails with "fetch failed".
 */
const dayWithRetry = (attempts) => withRetry(() => service.day(), {
  attempts,
  delayMs: 4000,
  onRetry: (err, attempt) => log.warn('watch', `${err.message} - retrying (${attempt}/${attempts - 1})`),
});

async function watch() {
  // A gap far longer than the tick interval means the process was suspended - the laptop
  // slept. Say so plainly in the log, and try harder to get back on the network.
  const gap = Date.now() - lastTickAt;
  lastTickAt = Date.now();
  const resumed = gap > TICK_MS * 3;
  if (resumed) {
    log.warn('watch', `resumed after ${Math.round(gap / 60000)} min suspended (laptop asleep?) - catching up`);
  }

  try {
    const today = await dayWithRetry(resumed ? 5 : 2);
    lastTickAt = Date.now(); // retries can take a while; don't count them as a new gap
    if (watcherDay !== today.date) {
      notified.clear();
      watcherDay = today.date;
      log.info('watch', `tracking ${today.date}: ${today.sessions.length} session(s), ${today.sessions.filter((s) => s.nid).length} markable`);
    }

    // Record the moment auto-marking stops being effective, whatever the cause.
    const reason = autoMarker.reason();
    if (reason !== lastAutoReason) {
      if (lastAutoReason === 'active' && reason !== 'active') {
        log.warn('auto', `no longer marking automatically (${reason})`);
        if (NOTIFY) toast('Auto-mark switched off', `Reason: ${reason}. Re-arm at http://localhost:${PORT}`);
      }
      lastAutoReason = reason;
    }

    for (const session of today.sessions) {
      if (!session.nid) continue;
      const stage = notified.get(session.nid) || { opened: false, nudged: false, autoTries: 0 };

      if (session.marked) {
        if (!stage.opened) log.debug('watch', `${session.name} already marked (${session.status})`);
        notified.set(session.nid, { ...stage, opened: true, nudged: true });
        continue;
      }

      const where = session.room ? ` in ${session.room}${session.floorLabel ? `, ${session.floorLabel}` : ''}` : '';
      const at = new Date(session.startMs).toLocaleTimeString('en-IN', { hour: 'numeric', minute: '2-digit', hour12: true });

      // --- auto-mark ---------------------------------------------------
      if (session.state === 'open' && autoMarker.isActive() && stage.autoTries < 3) {
        stage.autoTries++;
        notified.set(session.nid, stage);
        log.info('auto', `${session.name} is open - marking (attempt ${stage.autoTries})`);
        try {
          const detail = await service.mark(session.nid);
          if (detail.marked) {
            stage.opened = true;
            stage.nudged = true;
            log.info('auto', `marked ${session.name} (${session.nid}) - status ${detail.status}`);
            if (NOTIFY) toast(`Marked present: ${session.name}`, `${at}${where} - marked automatically.`);
            notified.set(session.nid, stage);
            continue;
          }
          log.warn('auto', `${session.name}: LMS still reports unmarked after marking`);
        } catch (err) {
          log.error('auto', `${session.name}: ${err.message}`);
          if (stage.autoTries >= 3 && NOTIFY) {
            toast(`Could not auto-mark: ${session.name}`, 'Open the dashboard and mark it yourself.');
          }
        }
        notified.set(session.nid, stage);
        continue;
      }

      // --- otherwise, just nudge ---------------------------------------
      if (session.state === 'open' && !stage.opened) {
        log.info('watch', `${session.name} is open for marking${autoMarker.isActive() ? '' : ' (auto-mark off)'}`);
        if (NOTIFY) toast(`Mark attendance: ${session.name}`, `${at}${where} - open http://localhost:${PORT} and tap Mark.`);
        stage.opened = true;
      }

      const minutesIn = (Date.now() - session.startMs) / 60_000;
      if (session.state === 'open' && stage.opened && !stage.nudged
          && minutesIn >= LATE_AFTER_MINUTES - 3 && minutesIn < LATE_AFTER_MINUTES) {
        const left = Math.max(1, Math.round(LATE_AFTER_MINUTES - minutesIn));
        log.warn('watch', `${session.name} still unmarked, ~${left} min before late`);
        if (NOTIFY) toast(`Still unmarked: ${session.name}`, `About ${left} min before you are counted late.`);
        stage.nudged = true;
      }
      notified.set(session.nid, stage);
    }
  } catch (err) {
    log.error('watch', err.message);
  }
}

// ---------------------------------------------------------------------------
// Daily shutdown
// ---------------------------------------------------------------------------

function scheduleShutdown() {
  const ms = msUntilTimeOfDay(SHUTDOWN_AT);
  if (ms === null) {
    if (SHUTDOWN_AT) log.warn('boot', `SHUTDOWN_AT="${SHUTDOWN_AT}" is not HH:MM - ignoring`);
    return;
  }
  const when = new Date(Date.now() + ms);
  log.info('boot', `will shut down at ${SHUTDOWN_AT} (in ${Math.round(ms / 60000)} min)`);
  setTimeout(() => {
    log.info('exit', `shutting down at ${SHUTDOWN_AT} as configured`);
    if (NOTIFY) toast('ISDM Companion stopped', `Daily shutdown at ${SHUTDOWN_AT}. Start it again tomorrow.`);
    releaseAwake();
    server.close(() => process.exit(0));
    // Don't hang on a stuck connection.
    setTimeout(() => process.exit(0), 3000).unref();
  }, ms).unref?.();
  return when;
}

/** Open the dashboard in the default browser. Kept here rather than in the .cmd launcher,
 *  because batch scripts are a fragile place to do anything conditional. */
function openBrowser() {
  const url = `http://localhost:${PORT}`;
  try {
    if (process.platform === 'win32') {
      spawn('cmd', ['/c', 'start', '', url], { detached: true, stdio: 'ignore', windowsHide: true }).unref();
    } else {
      spawn(process.platform === 'darwin' ? 'open' : 'xdg-open', [url], { detached: true, stdio: 'ignore' }).unref();
    }
  } catch (err) {
    log.warn('boot', `could not open a browser: ${err.message}`);
  }
}

// If the port is taken, another copy is already running: show that one instead of failing.
server.on('error', (err) => {
  if (err.code === 'EADDRINUSE') {
    log.info('boot', `port ${PORT} is already in use - opening the copy that is already running`);
    console.log(`\n  Already running. Opening http://localhost:${PORT}\n`);
    if (process.env.OPEN_BROWSER === '1') openBrowser();
    process.exit(0);
  }
  log.error('boot', `server error: ${err.message}`);
  process.exit(1);
});

process.on('SIGINT', () => { log.info('exit', 'stopped by Ctrl+C'); releaseAwake(); process.exit(0); });
process.on('exit', releaseAwake);
process.on('uncaughtException', (err) => { log.error('crash', err.stack || err.message); releaseAwake(); process.exit(1); });
process.on('unhandledRejection', (err) => { log.error('crash', String(err && err.stack || err)); });

server.listen(PORT, '127.0.0.1', async () => {
  console.log(`\n  ISDM Companion  ->  http://localhost:${PORT}\n`);
  log.info('boot', `server listening on ${PORT}`);
  try {
    const who = await client.login();
    const cohorts = await service.cohorts();
    const tags = [...cohorts.sections].map((s) => `Section ${s}`).concat([...cohorts.groups].map((g) => `Group ${g}`));
    log.info('boot', `signed in as uid ${who.uid}${tags.length ? ` (${tags.join(', ')})` : ''}`);
  } catch (err) {
    log.error('boot', `sign-in failed: ${err.message}`);
  }
  log.info('boot', autoMarker.isActive()
    ? `auto-mark ARMED${AUTO_MARK_HOURS ? ` for ${AUTO_MARK_HOURS}h` : ' for as long as the app is open'}`
    : 'auto-mark off - you will be asked to tap Mark');
  scheduleShutdown();

  if (process.env.KEEP_AWAKE === '1') {
    log.info('awake', keepAwake(log)
      ? 'holding the system awake - idle sleep is blocked while this runs (closing the lid still sleeps)'
      : 'could not hold the system awake');
  } else if (autoMarker.isActive()) {
    log.warn('awake', 'KEEP_AWAKE is off - if the laptop sleeps through a class start, it cannot be marked');
  }

  if (process.env.OPEN_BROWSER === '1') openBrowser();

  watch();
  setInterval(watch, TICK_MS);
});
