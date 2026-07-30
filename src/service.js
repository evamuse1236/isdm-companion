// Caching layer between the HTTP server and the LMS.
// The LMS is slow-ish and rate-sensitive, so every read goes through a short TTL cache
// and per-session detail lookups run in a small parallel pool.

import {
  buildSessions, detectCohorts, parseCohortOverride, sessionState, ymd, addDays,
} from './schedule.js';
import { parseRoomFloors, floorFor, floorLabel } from './rooms.js';

const MINUTE = 60_000;

async function pooled(items, limit, worker) {
  const results = new Array(items.length);
  let cursor = 0;
  const runners = Array.from({ length: Math.min(limit, items.length) }, async () => {
    while (cursor < items.length) {
      const index = cursor++;
      results[index] = await worker(items[index], index);
    }
  });
  await Promise.all(runners);
  return results;
}

export class Service {
  constructor(client, { lateAfterMinutes = 10, cohortOverride = '', roomFloors = '' } = {}) {
    this.client = client;
    this.lateAfterMinutes = lateAfterMinutes;
    this.cohortOverride = parseCohortOverride(cohortOverride);
    this.roomFloors = parseRoomFloors(roomFloors);
    this.calendarCache = new Map();
    this.detailCache = new Map();
    this.markCache = null;
    this.cohortCache = null;
  }

  async calendar(start, end, maxAge = 2 * MINUTE) {
    const key = `${start}|${end}`;
    const hit = this.calendarCache.get(key);
    if (hit && Date.now() - hit.ts < maxAge) return hit.data;
    const data = await this.client.calendar(start, end);
    this.calendarCache.set(key, { ts: Date.now(), data });
    return data;
  }

  /** Your section and groups, learned from a wide window of your own attendance sessions. */
  async cohorts() {
    if (this.cohortOverride) return this.cohortOverride;
    if (this.cohortCache && Date.now() - this.cohortCache.ts < 60 * MINUTE) return this.cohortCache.data;
    const today = new Date();
    const events = await this.calendar(ymd(addDays(today, -120)), ymd(addDays(today, 120)), 60 * MINUTE);
    const data = detectCohorts(events);
    this.cohortCache = { ts: Date.now(), data };
    return data;
  }

  async detail(nid, maxAge) {
    const hit = this.detailCache.get(nid);
    if (hit && Date.now() - hit.ts < maxAge) return hit.data;
    const data = await this.client.classroom(nid);
    this.detailCache.set(nid, { ts: Date.now(), data });
    return data;
  }

  invalidate(nid) {
    this.detailCache.delete(nid);
    this.markCache = null;
  }

  /** Drop everything cached. Used after a resume, when any of it could be stale. */
  resetCaches() {
    this.calendarCache.clear();
    this.detailCache.clear();
    this.markCache = null;
    this.cohortCache = null;
  }

  async markability(maxAge = 20_000) {
    if (this.markCache && Date.now() - this.markCache.ts < maxAge) return this.markCache.data;
    const data = await this.client.markability();
    this.markCache = { ts: Date.now(), data };
    return data;
  }

  /**
   * Fully assembled sessions for a date range.
   * `live` (the day you are looking at is today) tightens cache TTLs so the Mark button
   * turns on within seconds of the LMS opening it.
   */
  async sessions(startDate, endDate, { live = false } = {}) {
    const [events, cohorts] = await Promise.all([
      this.calendar(ymd(startDate), ymd(endDate), live ? 60_000 : 5 * MINUTE),
      this.cohorts(),
    ]);

    const rows = buildSessions(events, cohorts);
    const withAttendance = rows.filter((row) => row.nid);

    let markMap = new Map();
    if (live && withAttendance.length) {
      try {
        markMap = await this.markability();
      } catch {
        markMap = new Map();
      }
    }

    await pooled(withAttendance, 5, async (row) => {
      // Static fields (room, trainer) never change; status matters only for today.
      const cached = this.detailCache.get(row.nid);
      const maxAge = live && !(cached && cached.data.marked) ? 45_000 : 6 * 60 * MINUTE;
      try {
        const detail = await this.detail(row.nid, maxAge);
        row.room = detail.room;
        row.trainer = detail.trainer || row.trainer;
        row.subject = detail.course || row.subject;
        row.marked = detail.marked;
        row.status = detail.status;
      } catch (err) {
        row.detailError = err.message;
      }
      const entry = markMap.get(row.nid);
      row.markable = Boolean(entry && entry.markable) && !row.marked;
    });

    const now = Date.now();
    for (const row of rows) {
      row.state = sessionState(row, now);
      row.lateAfter = row.nid ? new Date(row.startMs + this.lateAfterMinutes * MINUTE).toISOString() : null;
      row.floor = floorFor(row.room, this.roomFloors);
      row.floorLabel = floorLabel(row.room, this.roomFloors);
      delete row.key;
      delete row.mine;
    }
    return rows;
  }

  async day(dateStr) {
    const date = dateStr ? new Date(`${dateStr}T00:00:00`) : new Date();
    const today = ymd(new Date());
    const live = ymd(date) === today;
    const sessions = await this.sessions(date, addDays(date, 1), { live });
    return {
      date: ymd(date),
      today,
      isToday: live,
      now: new Date().toISOString(),
      lateAfterMinutes: this.lateAfterMinutes,
      sessions,
    };
  }

  async week(startStr) {
    const anchor = startStr ? new Date(`${startStr}T00:00:00`) : new Date();
    // Weeks run Monday to Sunday.
    const monday = addDays(anchor, -((anchor.getDay() + 6) % 7));
    const sessions = await this.sessions(monday, addDays(monday, 7), { live: false });
    const days = Array.from({ length: 7 }, (_, i) => {
      const date = addDays(monday, i);
      return { date: ymd(date), sessions: sessions.filter((s) => ymd(new Date(s.startMs)) === ymd(date)) };
    });
    return { start: ymd(monday), end: ymd(addDays(monday, 6)), today: ymd(new Date()), days };
  }

  async mark(nid) {
    const detail = await this.client.markPresent(nid);
    this.detailCache.set(String(nid), { ts: Date.now(), data: detail });
    this.markCache = null;
    return detail;
  }
}
