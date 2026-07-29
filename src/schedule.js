// Turns raw LMS calendar entries into the day/week view the UI renders.
//
// Every class shows up in the calendar twice: once as a session event (batch-wide, e.g.
// "Maths - Section B - Session 2") and once as an attendance event (personalised, e.g.
// "Attendance - Maths - Section B - Session 2") that carries the nid you mark against.
// We merge the pair into one row, and filter the leftover batch-wide events down to
// your own section and group.

const COHORT_RE = /\b(Section\s+[A-Z](?:\s*&\s*[A-Z])*|Group\s+\d+)\b/i;

export function parseLmsTime(value) {
  const m = /^(\d{4})-(\d{2})-(\d{2})[ T](\d{2}):(\d{2})(?::(\d{2}))?/.exec(String(value || ''));
  if (!m) return null;
  // LMS timestamps are wall-clock IST with no offset; the machine running this is on IST too,
  // so constructing a local Date is the correct reading.
  return new Date(+m[1], +m[2] - 1, +m[3], +m[4], +m[5], +(m[6] || 0));
}

export function ymd(date) {
  const p = (n) => String(n).padStart(2, '0');
  return `${date.getFullYear()}-${p(date.getMonth() + 1)}-${p(date.getDate())}`;
}

export function addDays(date, days) {
  const next = new Date(date);
  next.setDate(next.getDate() + days);
  return next;
}

/** "Attendance - Maths - Section B - Session 2" -> {name:"Maths", cohort:"Section B", session:2} */
export function parseTitle(rawTitle) {
  let rest = String(rawTitle || '').replace(/^\s*Attendance\s*-\s*/i, '').trim();
  let session = null;
  let cohort = null;

  const sessionMatch = rest.match(/\s*-\s*Session\s+(\d+)\s*$/i);
  if (sessionMatch) {
    session = Number(sessionMatch[1]);
    rest = rest.slice(0, sessionMatch.index).trim();
  }
  const cohortMatch = rest.match(/\s*-\s*(Section\s+[A-Z](?:\s*&\s*[A-Z])*|Group\s+\d+)\s*$/i);
  if (cohortMatch) {
    cohort = cohortMatch[1].replace(/\s+/g, ' ').trim();
    rest = rest.slice(0, cohortMatch.index).trim();
  }
  return { name: rest || String(rawTitle || '').trim(), cohort, session };
}

function mergeKey(event) {
  const { name, cohort, session } = parseTitle(event.title);
  return `${name.toLowerCase()}|${(cohort || '').toLowerCase()}|${session ?? ''}|${event.start}`;
}

const isAttendanceEvent = (event) => typeof event.url === 'string' && event.url.includes('/classroom/');

/**
 * Work out which section and groups you belong to, by looking at the attendance sessions
 * the LMS personalises to you. "Section A & B" is shared with the other section, so only
 * single-cohort tokens are treated as evidence.
 */
export function detectCohorts(events) {
  const sections = new Set();
  const groups = new Set();
  for (const event of events) {
    if (!isAttendanceEvent(event)) continue;
    const { cohort } = parseTitle(event.title);
    if (!cohort) continue;
    const group = cohort.match(/^Group\s+(\d+)$/i);
    if (group) { groups.add(group[1]); continue; }
    const letters = cohort.match(/\b[A-Z]\b/g) || [];
    if (letters.length === 1) sections.add(letters[0].toUpperCase());
  }
  return { sections, groups };
}

export function parseCohortOverride(text) {
  const sections = new Set();
  const groups = new Set();
  for (const token of String(text || '').split(',').map((t) => t.trim()).filter(Boolean)) {
    const group = token.match(/^Group\s+(\d+)$/i);
    if (group) { groups.add(group[1]); continue; }
    for (const letter of token.match(/\b[A-Z]\b/g) || []) sections.add(letter.toUpperCase());
  }
  return sections.size || groups.size ? { sections, groups } : null;
}

/** Does this batch-wide event belong to you? Events with no cohort marker are for everyone. */
function belongsToMe(event, cohorts) {
  const token = (event.title.match(COHORT_RE) || [])[1];
  if (!token) return true;
  const group = token.match(/^Group\s+(\d+)$/i);
  if (group) return cohorts.groups.size === 0 || cohorts.groups.has(group[1]);
  const letters = (token.match(/\b[A-Z]\b/g) || []).map((l) => l.toUpperCase());
  if (!letters.length) return true;
  if (cohorts.sections.size === 0) return true;
  return letters.some((letter) => cohorts.sections.has(letter));
}

/**
 * Merge raw calendar events into one row per class.
 * Rows carry `nid` when the class has an attendance session you can mark.
 */
export function buildSessions(events, cohorts) {
  const byKey = new Map();

  for (const event of events) {
    const key = mergeKey(event);
    const start = parseLmsTime(event.start);
    const end = parseLmsTime(event.end);
    if (!start) continue;

    let row = byKey.get(key);
    if (!row) {
      const { name, cohort, session } = parseTitle(event.title);
      row = {
        key,
        name,
        cohort,
        session,
        start: start.toISOString(),
        end: end ? end.toISOString() : null,
        startMs: start.getTime(),
        endMs: end ? end.getTime() : start.getTime(),
        nid: null,
        eventNid: null,
        subject: null,
        trainer: null,
        room: null,
        mine: true,
      };
      byKey.set(key, row);
    }

    if (isAttendanceEvent(event)) {
      row.nid = String(event.nid);
      row.subject = event.subject || row.subject;
      row.trainer = event.trainers || row.trainer;
    } else {
      row.eventNid = String(event.nid);
      // An attendance row proves the class is yours regardless of how the title reads;
      // otherwise fall back to matching the cohort in the title.
      row.mine = row.nid ? true : belongsToMe(event, cohorts);
    }
  }

  return [...byKey.values()]
    .filter((row) => row.nid || row.mine)
    .sort((a, b) => a.startMs - b.startMs || a.name.localeCompare(b.name));
}

/**
 * The UI state for a session.
 *   marked   - already recorded
 *   open     - the LMS will accept a mark right now
 *   upcoming - hasn't opened yet
 *   missed   - window has passed and nothing was recorded
 *   closed   - nothing to mark (no attendance session attached)
 */
export function sessionState(row, now) {
  if (!row.nid) return row.endMs < now ? 'done' : 'noattendance';
  if (row.marked) return 'marked';
  if (row.markable) return 'open';
  if (now < row.startMs) return 'upcoming';
  return 'missed';
}
