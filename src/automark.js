// Auto-marking, with a deliberate safety catch.
//
// The whole justification for marking attendance without a tap is that you only run this
// app when you are actually sitting in the class. That makes *starting the app* the consent
// signal — so consent has to expire, or a server left running (or launched at boot) would
// keep marking you present for classes you never attended.
//
// So auto-mark is armed for a fixed window and then goes quiet until you arm it again.

const HOUR = 3_600_000;

export class AutoMarker {
  /**
   * @param enabled   arm immediately on start (from AUTO_MARK=1)
   * @param windowMs  how long an arming lasts
   * @param clock     injectable for tests
   */
  constructor({ enabled = false, windowMs = 3 * HOUR, clock = () => Date.now() } = {}) {
    this.windowMs = windowMs;
    this.clock = clock;
    this.armedAt = enabled ? clock() : null;
  }

  arm() { this.armedAt = this.clock(); return this.status(); }

  disarm() { this.armedAt = null; return this.status(); }

  get expiresAt() { return this.armedAt === null ? null : this.armedAt + this.windowMs; }

  /** True only while an arming is still within its window. */
  isActive() { return this.armedAt !== null && this.clock() < this.armedAt + this.windowMs; }

  /** Armed at some point, but the window has since run out. */
  isExpired() { return this.armedAt !== null && !this.isActive(); }

  msRemaining() { return this.isActive() ? this.expiresAt - this.clock() : 0; }

  status() {
    return {
      active: this.isActive(),
      expired: this.isExpired(),
      armed: this.armedAt !== null,
      expiresAt: this.expiresAt === null ? null : new Date(this.expiresAt).toISOString(),
      msRemaining: this.msRemaining(),
      windowHours: this.windowMs / HOUR,
    };
  }
}
