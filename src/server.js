// Local dashboard server. Serves the UI from /public and a small JSON API,
// and runs a background watcher that toasts you when a class opens for marking.

import http from 'node:http';
import fs from 'node:fs/promises';
import path from 'node:path';
import { fileURLToPath } from 'node:url';

import { LmsClient, LmsError } from './lms.js';
import { Service } from './service.js';
import { toast } from './notify.js';
import { AutoMarker } from './automark.js';

const HERE = path.dirname(fileURLToPath(import.meta.url));
const PUBLIC_DIR = path.join(HERE, '..', 'public');
const PORT = Number(process.env.PORT || 4321);
const NOTIFY = process.env.NOTIFY !== '0';
const LATE_AFTER_MINUTES = Number(process.env.LATE_AFTER_MINUTES || 10);
const AUTO_MARK_HOURS = Number(process.env.AUTO_MARK_HOURS || 3);

const client = new LmsClient({ email: process.env.LMS_EMAIL, password: process.env.LMS_PASSWORD });
const service = new Service(client, { lateAfterMinutes: LATE_AFTER_MINUTES, cohortOverride: process.env.COHORTS });
const autoMarker = new AutoMarker({
  enabled: process.env.AUTO_MARK === '1',
  windowMs: AUTO_MARK_HOURS * 3_600_000,
});

const MIME = { '.html': 'text/html; charset=utf-8', '.js': 'text/javascript; charset=utf-8', '.css': 'text/css; charset=utf-8', '.svg': 'image/svg+xml', '.ico': 'image/x-icon' };

function sendJson(res, status, payload) {
  const body = JSON.stringify(payload);
  res.writeHead(status, { 'Content-Type': 'application/json; charset=utf-8', 'Cache-Control': 'no-store' });
  res.end(body);
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
      const detail = await service.mark(String(nid));
      if (!detail.marked) {
        return sendJson(res, 409, { error: 'The LMS accepted the request but still shows you as unmarked. Its marking window may have closed.', detail });
      }
      return sendJson(res, 200, { ok: true, detail });
    }
    if (url.pathname === '/api/automark') {
      if (req.method === 'POST') {
        const { on } = await readBody(req);
        // Re-arming is explicit: each POST restarts the window from now.
        on ? autoMarker.arm() : autoMarker.disarm();
        console.log(`[auto] ${on ? `armed for ${AUTO_MARK_HOURS}h` : 'disarmed'}`);
      }
      return sendJson(res, 200, autoMarker.status());
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
    console.error(`[api] ${url.pathname}:`, err.message);
    return sendJson(res, status, { error: err.message });
  }
});

// ---------------------------------------------------------------------------
// Background watcher: notice the moment a class becomes markable, and — when
// auto-marking is armed — mark it without waiting for a tap.
// ---------------------------------------------------------------------------

const notified = new Map(); // nid -> {opened, nudged, autoTries}
let watcherDay = null;
let expiryAnnounced = false;

async function watch() {
  try {
    const today = await service.day();
    if (watcherDay !== today.date) { notified.clear(); watcherDay = today.date; }

    // Say so once when an arming lapses, so a quiet app is never mistaken for an armed one.
    if (autoMarker.isExpired() && !expiryAnnounced) {
      expiryAnnounced = true;
      console.log('[auto] arming window expired — no longer marking automatically');
      if (NOTIFY) toast('Auto-mark switched off', `The ${AUTO_MARK_HOURS}h window ended. Re-arm it at http://localhost:${PORT}`);
    }
    if (autoMarker.isActive()) expiryAnnounced = false;

    for (const session of today.sessions) {
      if (!session.nid) continue;
      const stage = notified.get(session.nid) || { opened: false, nudged: false, autoTries: 0 };

      if (session.marked) { notified.set(session.nid, { ...stage, opened: true, nudged: true }); continue; }

      const where = session.room ? ` in ${session.room}` : '';
      const at = new Date(session.startMs).toLocaleTimeString('en-IN', { hour: 'numeric', minute: '2-digit', hour12: true });

      // --- auto-mark ---------------------------------------------------
      if (session.state === 'open' && autoMarker.isActive() && stage.autoTries < 3) {
        stage.autoTries++;
        notified.set(session.nid, stage);
        try {
          const detail = await service.mark(session.nid);
          if (detail.marked) {
            stage.opened = true;
            stage.nudged = true;
            console.log(`[auto] marked ${session.name} (${session.nid})`);
            if (NOTIFY) toast(`Marked present: ${session.name}`, `${at}${where} — marked automatically.`);
            notified.set(session.nid, stage);
            continue;
          }
          console.warn(`[auto] ${session.name}: LMS still shows unmarked after marking`);
        } catch (err) {
          console.warn(`[auto] ${session.name}: ${err.message}`);
          if (stage.autoTries >= 3 && NOTIFY) {
            toast(`Could not auto-mark: ${session.name}`, 'Open the dashboard and mark it yourself.');
          }
        }
        notified.set(session.nid, stage);
        continue;
      }

      // --- otherwise, just nudge ---------------------------------------
      if (!NOTIFY) { notified.set(session.nid, stage); continue; }

      if (session.state === 'open' && !stage.opened) {
        toast(`Mark attendance: ${session.name}`, `${at}${where} — open http://localhost:${PORT} and tap Mark.`);
        stage.opened = true;
      }

      // A second nudge with a couple of minutes to spare before the late cutoff.
      const minutesIn = (Date.now() - session.startMs) / 60_000;
      if (session.state === 'open' && stage.opened && !stage.nudged
          && minutesIn >= LATE_AFTER_MINUTES - 3 && minutesIn < LATE_AFTER_MINUTES) {
        const left = Math.max(1, Math.round(LATE_AFTER_MINUTES - minutesIn));
        toast(`Still unmarked: ${session.name}`, `About ${left} min before you are counted late.`);
        stage.nudged = true;
      }
      notified.set(session.nid, stage);
    }
  } catch (err) {
    console.warn('[watch]', err.message);
  }
}

server.listen(PORT, '127.0.0.1', async () => {
  console.log(`\n  ISDM Companion  ->  http://localhost:${PORT}\n`);
  try {
    const who = await client.login();
    const cohorts = await service.cohorts();
    const tags = [...cohorts.sections].map((s) => `Section ${s}`).concat([...cohorts.groups].map((g) => `Group ${g}`));
    console.log(`  Signed in as uid ${who.uid}${tags.length ? ` (${tags.join(', ')})` : ''}`);
    console.log(autoMarker.isActive()
      ? `  Auto-mark ARMED for ${AUTO_MARK_HOURS}h — classes will be marked without asking`
      : '  Auto-mark off — you will be asked to tap Mark');
  } catch (err) {
    console.error(`  Sign-in failed: ${err.message}`);
  }
  watch();
  setInterval(watch, 30_000);
});
