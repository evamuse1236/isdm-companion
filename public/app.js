// ISDM Companion front-end.
// Polls the local server, renders the "what do I do right now" hero plus the day/week
// schedule, and fires a browser notification the moment a class opens for marking.

const $ = (sel) => document.querySelector(sel);

const state = {
  view: 'today',
  date: null,        // YYYY-MM-DD, null = today
  weekStart: null,
  day: null,
  week: null,
  busyNid: null,
  seenOpen: new Set(),
};

const POLL_MS = 20_000;

// ---------------------------------------------------------------- utilities

const pad = (n) => String(n).padStart(2, '0');

function ymd(date) {
  return `${date.getFullYear()}-${pad(date.getMonth() + 1)}-${pad(date.getDate())}`;
}

function shiftDay(dateStr, days) {
  const d = dateStr ? new Date(`${dateStr}T00:00:00`) : new Date();
  d.setDate(d.getDate() + days);
  return ymd(d);
}

function clock(iso) {
  return new Date(iso).toLocaleTimeString('en-IN', { hour: 'numeric', minute: '2-digit', hour12: true });
}

function humanDate(dateStr) {
  const date = new Date(`${dateStr}T00:00:00`);
  const today = ymd(new Date());
  if (dateStr === today) return 'Today';
  if (dateStr === shiftDay(today, 1)) return 'Tomorrow';
  if (dateStr === shiftDay(today, -1)) return 'Yesterday';
  return date.toLocaleDateString('en-IN', { weekday: 'long', day: 'numeric', month: 'short' });
}

function escapeHtml(value) {
  return String(value ?? '').replace(/[&<>"']/g, (c) => (
    { '&': '&amp;', '<': '&lt;', '>': '&gt;', '"': '&quot;', "'": '&#39;' }[c]
  ));
}

function toast(message, kind = '') {
  const el = document.createElement('div');
  el.className = `toast ${kind ? `toast--${kind}` : ''}`;
  el.textContent = message;
  $('#toast-host').append(el);
  setTimeout(() => el.remove(), 6000);
}

async function api(path, options) {
  const res = await fetch(path, { headers: { 'Content-Type': 'application/json' }, ...options });
  const data = await res.json().catch(() => ({}));
  if (!res.ok) throw new Error(data.error || `Request failed (${res.status})`);
  return data;
}

// ---------------------------------------------------------------- rendering

function renderHero(day) {
  const hero = $('#hero');
  const now = Date.now();
  const markable = day.sessions.filter((s) => s.nid);

  // What deserves the big slot: something markable right now, else the next class,
  // else the most recent thing that still needs attention.
  const open = markable.find((s) => s.state === 'open');
  const upcoming = day.sessions.find((s) => s.startMs > now);
  const current = day.sessions.find((s) => s.startMs <= now && s.endMs >= now);
  const focus = open || current || upcoming || null;

  if (!day.isToday) {
    const count = day.sessions.length;
    hero.className = 'hero hero--empty';
    hero.innerHTML = `<div class="hero-skeleton">${count
      ? `${count} session${count === 1 ? '' : 's'} on ${escapeHtml(humanDate(day.date))}`
      : `Nothing scheduled on ${escapeHtml(humanDate(day.date))}`}</div>`;
    return;
  }

  if (!focus) {
    hero.className = 'hero hero--empty';
    const unmarked = markable.filter((s) => s.state === 'missed').length;
    hero.innerHTML = `<div class="hero-skeleton">Nothing left today${
      unmarked ? ` — ${unmarked} session${unmarked === 1 ? '' : 's'} went unmarked` : '. All clear.'}</div>`;
    return;
  }

  const facts = [
    focus.room ? `<div class="fact fact--room"><div class="fact-k">Room</div><div class="fact-v">${escapeHtml(focus.room)}${
      focus.floorLabel ? ` <span class="fact-floor">${escapeHtml(focus.floorLabel)}</span>` : ''}</div></div>` : '',
    `<div class="fact"><div class="fact-k">Time</div><div class="fact-v mono">${clock(focus.start)}–${focus.end ? clock(focus.end) : ''}</div></div>`,
    focus.trainer ? `<div class="fact"><div class="fact-k">Trainer</div><div class="fact-v">${escapeHtml(focus.trainer)}</div></div>` : '',
  ].join('');

  let eyebrow;
  let action;
  let cls = 'hero';

  if (focus.state === 'open') {
    cls += ' hero--open';
    eyebrow = '<span class="pulse-dot"></span> Open for marking';
    action = `
      <button class="mark-btn" id="mark-now" data-nid="${focus.nid}">Mark me present</button>
      <span class="countdown" id="countdown" data-late="${focus.lateAfter}"></span>`;
  } else if (focus.state === 'marked') {
    cls += ' hero--marked';
    eyebrow = 'Marked present';
    action = '<span class="done-badge">✓ Attendance recorded</span>';
  } else if (focus.state === 'missed') {
    eyebrow = 'Marking window closed';
    action = '<span class="countdown">This one was not marked — sort it out with the office.</span>';
  } else if (focus.startMs > now) {
    eyebrow = 'Next up';
    action = `<button class="mark-btn" disabled>Not open yet</button>
      <span class="countdown" id="countdown" data-start="${focus.start}"></span>`;
  } else {
    eyebrow = 'In progress';
    action = focus.nid
      ? '<span class="countdown">Waiting for the LMS to open marking…</span>'
      : '<span class="countdown">No attendance to mark for this one.</span>';
  }

  hero.className = cls;
  hero.innerHTML = `
    <div class="hero-eyebrow">${eyebrow}</div>
    <h1 class="hero-title">${escapeHtml(focus.name)}</h1>
    <p class="hero-sub">${escapeHtml([focus.cohort, focus.session ? `Session ${focus.session}` : null]
      .filter(Boolean).join(' · ') || '')}</p>
    <div class="hero-facts">${facts}</div>
    <div class="hero-action">${action}</div>`;

  const button = $('#mark-now');
  if (button) button.addEventListener('click', () => markSession(button.dataset.nid, button));
  tickCountdown();
}

const STATE_PILL = {
  marked: ['pill--marked', 'Marked'],
  open: ['pill--open', 'Mark now'],
  missed: ['pill--missed', 'Not marked'],
  upcoming: ['', 'Later'],
  noattendance: ['', 'No marking'],
  done: ['', '—'],
};

function renderTimeline(day) {
  const list = $('#timeline');
  if (!day.sessions.length) {
    list.innerHTML = '<div class="empty">No classes scheduled.</div>';
    return;
  }
  const now = Date.now();
  list.innerHTML = day.sessions.map((s) => {
    const [pillClass, pillText] = STATE_PILL[s.state] || ['', ''];
    const meta = [
      s.room ? `<span class="row-room">${escapeHtml(s.room)}${s.floor !== null && s.floor !== undefined ? ` · Fl ${s.floor}` : ''}</span>` : '',
      s.trainer ? `<span>${escapeHtml(s.trainer)}</span>` : '',
      s.session ? `<span>Session ${s.session}</span>` : '',
    ].filter(Boolean).join('');

    const rowClass = ['row', `row--${s.state}`, s.endMs < now && s.state !== 'open' ? 'row--past' : ''].join(' ');
    const trailing = s.state === 'open'
      ? `<button class="row-mark" data-nid="${s.nid}">Mark</button>`
      : `<span class="pill ${pillClass}">${pillText}</span>`;

    return `<li class="${rowClass}">
      <div class="row-time"><b>${clock(s.start)}</b>${s.end ? clock(s.end) : ''}</div>
      <div>
        <div class="row-name">${escapeHtml(s.name)}</div>
        <div class="row-meta">${meta}</div>
      </div>
      ${trailing}
    </li>`;
  }).join('');

  list.querySelectorAll('.row-mark').forEach((btn) => {
    btn.addEventListener('click', () => markSession(btn.dataset.nid, btn));
  });
}

function renderWeek(week) {
  $('#week-label').textContent = `${humanDate(week.start).replace('Today', 'This week')} → ${
    new Date(`${week.end}T00:00:00`).toLocaleDateString('en-IN', { day: 'numeric', month: 'short' })}`;

  $('#week').innerHTML = week.days.map((d) => {
    const date = new Date(`${d.date}T00:00:00`);
    const isToday = d.date === week.today;
    const items = d.sessions.length
      ? d.sessions.map((s) => `<div class="witem">
          <span class="witem-time">${clock(s.start)}–${s.end ? clock(s.end) : ''}</span>
          <span class="witem-name">${escapeHtml(s.name)}${s.session ? ` <span style="color:var(--ink-faint);font-weight:400">· S${s.session}</span>` : ''}</span>
          <span class="witem-room">${escapeHtml(s.room || '')}${s.floor !== null && s.floor !== undefined ? ` · Fl ${s.floor}` : ''}</span>
        </div>`).join('')
      : '<div class="wday-none">No classes</div>';

    return `<div class="wday ${isToday ? 'wday--today' : ''} ${d.sessions.length ? '' : 'wday--empty'}">
      <div class="wday-head">
        <span class="wday-name">${date.toLocaleDateString('en-IN', { weekday: 'long' })}</span>
        <span class="wday-date">${date.toLocaleDateString('en-IN', { day: '2-digit', month: 'short' })}</span>
        ${isToday ? '<span class="wday-today-tag">Today</span>' : ''}
      </div>${items}</div>`;
  }).join('');
}

// ---------------------------------------------------------------- countdown

function tickCountdown() {
  const el = $('#countdown');
  if (!el) return;
  const now = Date.now();

  if (el.dataset.late) {
    const left = new Date(el.dataset.late).getTime() - now;
    if (left > 0) {
      const m = Math.floor(left / 60000);
      const s = Math.floor((left % 60000) / 1000);
      el.classList.toggle('is-late', left < 3 * 60000);
      el.innerHTML = `<strong>${m}:${pad(s)}</strong> left to be counted on time`;
    } else {
      el.classList.add('is-late');
      el.innerHTML = '<strong>Past the on-time window</strong> — mark anyway';
    }
  } else if (el.dataset.start) {
    const until = new Date(el.dataset.start).getTime() - now;
    const mins = Math.round(until / 60000);
    el.textContent = until <= 0 ? 'Starting now'
      : mins < 60 ? `Starts in ${mins} min`
      : `Starts in ${Math.floor(mins / 60)}h ${mins % 60}m`;
  }
}

// ---------------------------------------------------------------- actions

async function markSession(nid, button) {
  if (state.busyNid) return;
  state.busyNid = nid;
  button.classList.add('is-busy');
  button.disabled = true;
  try {
    await api('/api/mark', { method: 'POST', body: JSON.stringify({ nid }) });
    toast('Attendance marked — you are present.', 'good');
    await load({ silent: true });
  } catch (err) {
    toast(err.message, 'bad');
    button.classList.remove('is-busy');
    button.disabled = false;
  } finally {
    state.busyNid = null;
  }
}

function notifyIfNewlyOpen(day) {
  if (!day.isToday || Notification?.permission !== 'granted') return;
  for (const session of day.sessions) {
    if (session.state !== 'open' || state.seenOpen.has(session.nid)) continue;
    state.seenOpen.add(session.nid);
    new Notification(`Mark attendance: ${session.name}`, {
      body: `${clock(session.start)}${session.room ? ` · ${session.room}` : ''} — tap Mark in ISDM Companion.`,
      tag: `isdm-${session.nid}`,
      requireInteraction: true,
    });
  }
}

// ---------------------------------------------------------------- auto-mark

function describeRemaining(ms) {
  const mins = Math.round(ms / 60000);
  if (mins < 60) return `${mins} min`;
  const h = Math.floor(mins / 60);
  return `${h}h ${mins % 60}m`;
}

const AUTO_STOPPED = {
  'window-elapsed': 'The arming window ran out. Switch it back on if you are still in class.',
  'new-day': 'It was armed yesterday. Switch it on again for today.',
};

function renderAuto(status) {
  const bar = $('#autobar');
  $('#auto-toggle').checked = status.active;
  bar.classList.toggle('autobar--on', status.active);

  if (status.active) {
    $('#auto-title').textContent = 'Auto-mark is on';
    const until = status.expiresAt
      ? `until ${new Date(status.expiresAt).toLocaleTimeString('en-IN', { hour: 'numeric', minute: '2-digit', hour12: true })} (${describeRemaining(status.msRemaining)} left)`
      : status.shutdownAt
        ? `for as long as the app is open — it shuts down at ${status.shutdownAt}`
        : 'for as long as the app is open';
    $('#auto-sub').textContent = `Classes will be marked without asking, ${until}.`;
  } else if (status.expired) {
    $('#auto-title').textContent = 'Auto-mark stopped';
    $('#auto-sub').textContent = AUTO_STOPPED[status.reason] || 'It is no longer marking automatically.';
  } else {
    $('#auto-title').textContent = 'Auto-mark off';
    $('#auto-sub').textContent = "You'll be asked to tap Mark.";
  }
}

async function loadAuto() {
  try { renderAuto(await api('/api/automark')); } catch { /* server will report elsewhere */ }
}

$('#auto-toggle').addEventListener('change', async (event) => {
  const on = event.target.checked;
  try {
    const status = await api('/api/automark', { method: 'POST', body: JSON.stringify({ on }) });
    renderAuto(status);
    toast(on
      ? 'Auto-mark on — classes will be marked without asking.'
      : 'Auto-mark off.', on ? 'good' : '');
    if (on) load({ silent: true });
  } catch (err) {
    toast(err.message, 'bad');
    event.target.checked = !on;
  }
});

// ---------------------------------------------------------------- activity log

let logFilter = 'all';
let logEntries = [];

const LOG_FILTERS = {
  all: () => true,
  problems: (e) => e.level === 'warn' || e.level === 'error',
  auto: (e) => e.scope === 'auto',
  mark: (e) => e.scope === 'mark' || e.scope === 'auto',
};

function renderLog() {
  const box = $('#logbox');
  const rows = logEntries.filter(LOG_FILTERS[logFilter] || LOG_FILTERS.all);
  if (!rows.length) {
    box.innerHTML = '<div class="empty" style="border:0">Nothing logged for this filter.</div>';
    return;
  }
  box.innerHTML = rows.map((e) => `<div class="logrow logrow--${escapeHtml(e.level)}">
    <span class="logrow-time">${escapeHtml(e.time)}</span>
    <span class="logrow-level">${escapeHtml(e.level.toUpperCase())}</span>
    <span class="logrow-scope">${escapeHtml(e.scope)}</span>
    <span class="logrow-msg">${escapeHtml(e.message)}</span>
  </div>`).join('');
  box.scrollTop = box.scrollHeight;
}

async function loadLog(date) {
  try {
    const query = date ? `?date=${date}` : '';
    const data = await api(`/api/log${query}`);
    logEntries = data.entries;
    const picker = $('#log-date');
    if (picker.options.length !== data.days.length || picker.value !== data.date) {
      picker.innerHTML = data.days.map((d) => `<option value="${d}"${d === data.date ? ' selected' : ''}>${d}</option>`).join('');
    }
    renderLog();
  } catch (err) {
    $('#logbox').innerHTML = `<div class="empty" style="border:0">Could not read the log: ${escapeHtml(err.message)}</div>`;
  }
}

$('#log-date').addEventListener('change', (e) => loadLog(e.target.value));
$('#log-refresh').addEventListener('click', () => loadLog($('#log-date').value));
document.querySelectorAll('#log-filters .chip').forEach((chip) => {
  chip.addEventListener('click', () => {
    document.querySelectorAll('#log-filters .chip').forEach((c) => c.classList.toggle('is-active', c === chip));
    logFilter = chip.dataset.level;
    renderLog();
  });
});

// ---------------------------------------------------------------- loading

let loading = false;

async function load({ silent = false } = {}) {
  if (loading) return;
  loading = true;
  try {
    if (state.view === 'today') {
      const query = state.date ? `?date=${state.date}` : '';
      const day = await api(`/api/day${query}`);
      state.day = day;
      $('#day-label').textContent = humanDate(day.date);
      renderHero(day);
      renderTimeline(day);
      notifyIfNewlyOpen(day);
      loadAuto();
    } else if (state.view === 'log') {
      await loadLog($('#log-date').value || null);
    } else {
      const query = state.weekStart ? `?start=${state.weekStart}` : '';
      const week = await api(`/api/week${query}`);
      state.week = week;
      renderWeek(week);
    }
    $('#status-line').textContent = `Synced with the LMS at ${new Date().toLocaleTimeString('en-IN', { hour: 'numeric', minute: '2-digit', second: '2-digit', hour12: true })}`;
  } catch (err) {
    $('#status-line').textContent = `Sync failed: ${err.message}`;
    if (!silent) toast(err.message, 'bad');
  } finally {
    loading = false;
  }
}

// ---------------------------------------------------------------- wiring

document.querySelectorAll('.tab').forEach((tab) => {
  tab.addEventListener('click', () => {
    document.querySelectorAll('.tab').forEach((t) => t.classList.toggle('is-active', t === tab));
    state.view = tab.dataset.view;
    for (const view of ['today', 'week', 'log']) {
      $(`#view-${view}`).classList.toggle('is-hidden', state.view !== view);
    }
    load();
  });
});

$('#prev-day').addEventListener('click', () => { state.date = shiftDay(state.date, -1); load(); });
$('#next-day').addEventListener('click', () => { state.date = shiftDay(state.date, 1); load(); });
$('#jump-today').addEventListener('click', () => { state.date = null; load(); });
$('#prev-week').addEventListener('click', () => { state.weekStart = shiftDay(state.weekStart || ymd(new Date()), -7); load(); });
$('#next-week').addEventListener('click', () => { state.weekStart = shiftDay(state.weekStart || ymd(new Date()), 7); load(); });

$('#enable-notifs').addEventListener('click', async () => {
  const result = await Notification.requestPermission();
  toast(result === 'granted' ? 'Browser alerts are on.' : 'Browser alerts stayed off.', result === 'granted' ? 'good' : '');
  updateNotifButton();
});

function updateNotifButton() {
  const button = $('#enable-notifs');
  if (!('Notification' in window)) { button.style.display = 'none'; return; }
  button.style.display = Notification.permission === 'granted' ? 'none' : '';
}

function tickClock() {
  $('#clock').textContent = new Date().toLocaleTimeString('en-IN', { hour: '2-digit', minute: '2-digit', second: '2-digit', hour12: false });
  tickCountdown();
}

updateNotifButton();
tickClock();
setInterval(tickClock, 1000);
load();
setInterval(() => { if (!document.hidden) load({ silent: true }); }, POLL_MS);
document.addEventListener('visibilitychange', () => { if (!document.hidden) load({ silent: true }); });
