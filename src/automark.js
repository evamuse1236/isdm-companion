// Auto-marking.
//
// Marking without a tap is only honest if the app is running *because you are in the class*,
// so the app must not be able to keep marking long after you walked away. Two guards do that:
//
//   1. The daily shutdown (SHUTDOWN_AT) stops the server at the end of the teaching day.
//   2. Auto-marking only ever applies on the calendar day it was armed, so a server that
//      somehow survives midnight cannot mark you into tomorrow's 9:30 class.
//
// AUTO_MARK_HOURS optionally adds a shorter expiry on top; 0 means "for as long as the app
// is open", which is the usual setup alongside a daily shutdown.

const HOUR = 3_600_000;

const sameDay = (a, b) =>
  a.getFullYear() === b.getFullYear() && a.getMonth() === b.getMonth() && a.getDate() === b.getDate();

export class AutoMarker {
  /**
   * @param enabled   arm immediately on start (AUTO_MARK=1)
   * @param windowMs  expiry for an arming; <= 0 means no expiry
   * @param clock     injectable for tests
   */
  constructor({ enabled = false, windowMs = 0, clock = () => Date.now() } = {}) {
    this.windowMs = windowMs > 0 ? windowMs : 0;
    this.clock = clock;
    this.armedAt = enabled ? clock() : null;
  }

  arm() { this.armedAt = this.clock(); return this.status(); }

  disarm() { this.armedAt = null; return this.status(); }

  get expiresAt() {
    if (this.armedAt === null || !this.windowMs) return null;
    return this.armedAt + this.windowMs;
  }

  /** Armed, still inside any expiry, and still the same day it was armed. */
  isActive() {
    if (this.armedAt === null) return false;
    const now = this.clock();
    if (this.windowMs && now >= this.armedAt + this.windowMs) return false;
    return sameDay(new Date(now), new Date(this.armedAt));
  }

  /** Armed at some point, but no longer active. */
  isExpired() { return this.armedAt !== null && !this.isActive(); }

  /** Why it stopped, for the UI and the log. */
  reason() {
    if (this.armedAt === null) return 'off';
    if (this.isActive()) return 'active';
    if (this.windowMs && this.clock() >= this.armedAt + this.windowMs) return 'window-elapsed';
    return 'new-day';
  }

  msRemaining() {
    if (!this.isActive() || !this.windowMs) return null;
    return this.armedAt + this.windowMs - this.clock();
  }

  status() {
    return {
      active: this.isActive(),
      expired: this.isExpired(),
      armed: this.armedAt !== null,
      reason: this.reason(),
      // null expiresAt means "stays on while the app is open"
      expiresAt: this.expiresAt === null ? null : new Date(this.expiresAt).toISOString(),
      msRemaining: this.msRemaining(),
      windowHours: this.windowMs ? this.windowMs / HOUR : 0,
    };
  }
}

/**
 * Milliseconds until the next occurrence of "HH:MM" local time.
 * Returns null if the spec is blank or malformed.
 */
export function msUntilTimeOfDay(spec, now = new Date()) {
  const m = /^(\d{1,2}):(\d{2})$/.exec(String(spec || '').trim());
  if (!m) return null;
  const hour = Number(m[1]);
  const minute = Number(m[2]);
  if (hour > 23 || minute > 59) return null;
  const target = new Date(now);
  target.setHours(hour, minute, 0, 0);
  if (target <= now) target.setDate(target.getDate() + 1);
  return target - now;
}
