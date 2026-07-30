// Retry helper for transient network failures.
//
// The case this exists for: straight after the laptop wakes from Modern Standby, networking
// is not up yet and fetch fails immediately. Without a retry the app would sit idle for a
// whole tick, which is a big slice of a ten-minute attendance window.

export const sleep = (ms) => new Promise((resolve) => setTimeout(resolve, ms));

/**
 * Call `fn` until it succeeds or attempts run out. Rethrows the last error.
 * @param onRetry called with (error, attemptNumber) before each wait
 */
export async function withRetry(fn, { attempts = 2, delayMs = 4000, onRetry = null, wait = sleep } = {}) {
  let lastError;
  for (let attempt = 1; attempt <= attempts; attempt++) {
    try {
      return await fn();
    } catch (err) {
      lastError = err;
      if (attempt < attempts) {
        onRetry?.(err, attempt);
        await wait(delayMs);
      }
    }
  }
  throw lastError;
}
