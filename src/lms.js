// Client for lms.isdm.org.in (a Drupal site).
//
// Endpoints this leans on, all discovered from the site's own front-end:
//   GET  /user/login                          Drupal form login, sets a session cookie
//   GET  /calendar/json?start=&end=           every event in a date range, as JSON.
//                                             Entries whose url is /classroom/{nid}/view are
//                                             attendance sessions and are already personalised
//                                             to the logged-in student.
//   GET  /classroom/{nid}/view                session detail: Location, Trainers, Status
//   GET  /manage/classroom/attendance         list of your sessions; each row carries a div whose
//                                             class is "mark-attend" when the LMS will accept a
//                                             mark right now, and "mark-attend-disabled" otherwise
//   POST /api/mark/classroomsession/attendance  {nid, uid, status:"present"}
//
// The `mark-attend` class is the server's own answer to "can this be marked now", so we use it
// as the source of truth rather than guessing the window from the clock.

import { parseForms, parseLabelTable, parseAttrs, stripTags } from './html.js';

// Overridable so tests can point the client at a local stub. Never set this in .env.
const BASE = process.env.LMS_BASE || 'https://lms.isdm.org.in';
const UA = 'Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/126.0 Safari/537.36';

class CookieJar {
  #cookies = new Map();

  absorb(res) {
    const lines = typeof res.headers.getSetCookie === 'function' ? res.headers.getSetCookie() : [];
    for (const line of lines) {
      const pair = line.split(';')[0];
      const eq = pair.indexOf('=');
      if (eq < 0) continue;
      const name = pair.slice(0, eq).trim();
      const value = pair.slice(eq + 1).trim();
      if (!value || value === 'deleted') this.#cookies.delete(name);
      else this.#cookies.set(name, value);
    }
  }

  get header() {
    return [...this.#cookies].map(([k, v]) => `${k}=${v}`).join('; ');
  }

  get size() { return this.#cookies.size; }
  clear() { this.#cookies.clear(); }
}

export class LmsError extends Error {}

export class LmsClient {
  constructor({ email, password }) {
    this.email = email;
    this.password = password;
    this.jar = new CookieJar();
    this.uid = null;
    this.displayName = null;
    this.loginPromise = null;
  }

  async request(path, { method = 'GET', body = null, headers = {}, maxRedirects = 6 } = {}) {
    let url = path.startsWith('http') ? path : BASE + path;
    let currentMethod = method;
    let currentBody = body;
    let currentHeaders = { ...headers };

    for (let hop = 0; hop <= maxRedirects; hop++) {
      const sent = {
        'User-Agent': UA,
        Accept: 'text/html,application/json,*/*',
        'Accept-Language': 'en-US,en;q=0.9',
        ...currentHeaders,
      };
      if (this.jar.size) sent.Cookie = this.jar.header;

      const res = await fetch(url, { method: currentMethod, headers: sent, body: currentBody, redirect: 'manual' });
      this.jar.absorb(res);

      if ([301, 302, 303, 307, 308].includes(res.status)) {
        const location = res.headers.get('location');
        if (!location) break;
        url = new URL(location, url).toString();
        // A POST that redirects becomes a GET, and must drop its body and content-type.
        if (currentMethod === 'POST' && res.status !== 307 && res.status !== 308) {
          currentMethod = 'GET';
          currentBody = null;
          currentHeaders = { ...currentHeaders };
          delete currentHeaders['Content-Type'];
        }
        continue;
      }

      const text = await res.text();
      return { status: res.status, url, text };
    }
    throw new LmsError(`Too many redirects starting at ${path}`);
  }

  /** A page served to a logged-out visitor always contains the login form. */
  #looksLoggedOut(page) {
    return /name="form_id"[^>]*value="user_login/i.test(page.text) || /\/user\/login/.test(page.url);
  }

  /**
   * Throw away the session so the next request logs in afresh.
   * Used after the laptop wakes: the cookie may have lapsed and the kept-alive sockets are
   * dead, and it is cheaper to start clean than to work out which.
   */
  reset() {
    this.jar.clear();
    this.uid = null;
    this.loginPromise = null;
  }

  async login() {
    // Collapse concurrent callers onto a single in-flight login.
    if (this.loginPromise) return this.loginPromise;
    this.loginPromise = this.#doLogin().finally(() => { this.loginPromise = null; });
    return this.loginPromise;
  }

  async #doLogin() {
    if (!this.email || !this.password) {
      throw new LmsError('LMS_EMAIL and LMS_PASSWORD are not set. Copy .env.example to .env and fill them in.');
    }
    this.jar.clear();
    this.uid = null;

    const page = await this.request('/user/login');
    const forms = parseForms(page.text);
    const form = forms.find((f) => f.fields.some((i) => i.name === 'form_id' && i.value === 'user_login'))
      || forms.find((f) => f.fields.some((i) => i.type === 'password'));
    if (!form) throw new LmsError('Could not find the login form on /user/login — the LMS page layout may have changed.');

    const params = new URLSearchParams();
    let userField = null;
    let passField = null;
    for (const field of form.fields) {
      if (field.type === 'password' && !passField) { passField = field.name; continue; }
      if ((field.type === 'text' || field.type === 'email') && !userField) { userField = field.name; continue; }
      if (field.type === 'hidden' || field.type === 'submit') params.set(field.name, field.value);
    }
    if (!userField || !passField) throw new LmsError('Login form is missing its email or password field.');

    params.set(userField, this.email);
    params.set(passField, this.password);
    // Drupal mirrors form_build_id into a "secure token" field named `st`.
    if (params.has('form_build_id') && params.has('st')) params.set('st', params.get('form_build_id'));

    const action = form.attrs.action ? new URL(form.attrs.action, BASE).toString() : BASE + '/user/login';
    const result = await this.request(action, {
      method: 'POST',
      headers: { 'Content-Type': 'application/x-www-form-urlencoded' },
      body: params.toString(),
    });

    if (this.#looksLoggedOut(result)) {
      const error = stripTags((result.text.match(/<div[^>]*class="[^"]*(?:messages|error)[^"]*"[^>]*>([\s\S]*?)<\/div>/i) || [])[1] || '');
      throw new LmsError(error ? `LMS rejected the login: ${error.slice(0, 200)}` : 'LMS rejected the login — check LMS_EMAIL and LMS_PASSWORD.');
    }

    this.#captureIdentity(result.text);
    if (!this.uid) {
      const home = await this.request('/home');
      this.#captureIdentity(home.text);
    }
    if (!this.uid) throw new LmsError('Logged in but could not determine your user id.');
    return { uid: this.uid, name: this.displayName };
  }

  #captureIdentity(html) {
    const uid = html.match(/\/user\/(\d+)\/edit\/chgpwd/) || html.match(/[?&]uid=(\d+)/);
    if (uid) this.uid = uid[1];
    const name = html.match(/<div[^>]*class="[^"]*user-name[^"]*"[^>]*>([\s\S]*?)<\/div>/i);
    if (name) this.displayName = stripTags(name[1]) || this.displayName;
  }

  /** Run a request, logging in first if needed and retrying once if the session lapsed. */
  async authed(path, options) {
    if (!this.uid) await this.login();
    let page = await this.request(path, options);
    if (this.#looksLoggedOut(page)) {
      await this.login();
      page = await this.request(path, options);
      if (this.#looksLoggedOut(page)) throw new LmsError(`Still logged out after re-authenticating (${path}).`);
    }
    return page;
  }

  /** Raw calendar entries for a date range. `start`/`end` are YYYY-MM-DD; `end` is exclusive. */
  async calendar(start, end) {
    const page = await this.authed(`/calendar/json?start=${start}&end=${end}&_=${Date.now()}`);
    try {
      const data = JSON.parse(page.text);
      if (!Array.isArray(data)) throw new Error('not an array');
      return data;
    } catch {
      throw new LmsError('Calendar response was not JSON — the LMS session may have expired.');
    }
  }

  /** Session detail: room, trainer, course and your own attendance status. */
  async classroom(nid) {
    const page = await this.authed(`/classroom/${nid}/view`);
    const table = parseLabelTable(page.text);
    const pick = (...labels) => {
      for (const label of labels) {
        const hit = Object.keys(table).find((k) => k.toLowerCase() === label.toLowerCase());
        if (hit && table[hit] && table[hit] !== '-') return table[hit];
      }
      return null;
    };
    const markedRaw = pick('Attendance Marked (For me)', 'Attendance Marked');
    return {
      nid: String(nid),
      title: pick('Title'),
      room: pick('Location', 'Venue'),
      trainer: pick('Trainers', 'Trainer'),
      course: pick('Courses', 'Course'),
      marked: markedRaw ? /^yes$/i.test(markedRaw) : false,
      status: pick('Status'),
      comment: pick('Comment'),
    };
  }

  /**
   * Which of your sessions the LMS will accept a mark for right now.
   * Returns Map<nid, {markable, label, when}>.
   */
  async markability() {
    const page = await this.authed('/manage/classroom/attendance');
    const map = new Map();
    const re = /<div\b([^>]*\buid=[^>]*)>/gi;
    let m;
    while ((m = re.exec(page.text))) {
      const attrs = parseAttrs(m[1]);
      if (!attrs.class || !attrs.class.includes('mark-attend') || !/^\d+$/.test(attrs.id || '')) continue;
      map.set(attrs.id, {
        markable: attrs.class.trim().split(/\s+/).includes('mark-attend'),
        uid: attrs.uid,
      });
    }
    return map;
  }

  /** Mark yourself present. Returns the refreshed session detail. */
  async markPresent(nid) {
    if (!this.uid) await this.login();
    const payload = JSON.stringify({ nid: String(nid), uid: String(this.uid), status: 'present' });
    const res = await this.authed('/api/mark/classroomsession/attendance', {
      method: 'POST',
      headers: {
        'Content-Type': 'application/json',
        'X-Requested-With': 'XMLHttpRequest',
        Referer: `${BASE}/manage/classroom/attendance`,
      },
      body: payload,
    });
    if (res.status >= 400) throw new LmsError(`The LMS returned HTTP ${res.status} when marking attendance.`);
    // Confirm against the LMS rather than trusting the POST's response body.
    return this.classroom(nid);
  }
}
