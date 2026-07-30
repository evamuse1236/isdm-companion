// Persistent daily log, so you can look back at the end of the day and see exactly what the
// app did: what it marked, what the LMS refused, and where it lost its session.
//
// One file per day in logs/, mirrored to the console. Old files are pruned on startup.

import fs from 'node:fs';
import path from 'node:path';

const LEVELS = { debug: 10, info: 20, warn: 30, error: 40 };

const pad = (n) => String(n).padStart(2, '0');
const ymd = (d) => `${d.getFullYear()}-${pad(d.getMonth() + 1)}-${pad(d.getDate())}`;
const hms = (d) => `${pad(d.getHours())}:${pad(d.getMinutes())}:${pad(d.getSeconds())}`;

export class Logger {
  constructor({ dir, level = 'info', mirror = true, keepDays = 30 } = {}) {
    this.dir = dir;
    this.min = LEVELS[level] ?? LEVELS.info;
    this.mirror = mirror;
    this.keepDays = keepDays;
    fs.mkdirSync(dir, { recursive: true });
    this.#prune();
  }

  #file(date = new Date()) {
    return path.join(this.dir, `${ymd(date)}.log`);
  }

  #prune() {
    try {
      const cutoff = Date.now() - this.keepDays * 86_400_000;
      for (const name of fs.readdirSync(this.dir)) {
        if (!/^\d{4}-\d{2}-\d{2}\.log$/.test(name)) continue;
        const when = Date.parse(name.slice(0, 10));
        if (Number.isFinite(when) && when < cutoff) fs.unlinkSync(path.join(this.dir, name));
      }
    } catch { /* logging must never take the app down */ }
  }

  write(level, scope, message) {
    if ((LEVELS[level] ?? 0) < this.min) return;
    const now = new Date();
    const line = `${hms(now)}  ${level.toUpperCase().padEnd(5)} ${`[${scope}]`.padEnd(10)} ${message}`;
    try {
      fs.appendFileSync(this.#file(now), `${line}\n`, 'utf8');
    } catch { /* ignore disk errors */ }
    if (this.mirror) {
      const out = level === 'error' || level === 'warn' ? console.error : console.log;
      out(line);
    }
  }

  debug(scope, msg) { this.write('debug', scope, msg); }
  info(scope, msg) { this.write('info', scope, msg); }
  warn(scope, msg) { this.write('warn', scope, msg); }
  error(scope, msg) { this.write('error', scope, msg); }

  /** Which days have logs, newest first. */
  days() {
    try {
      return fs.readdirSync(this.dir)
        .filter((n) => /^\d{4}-\d{2}-\d{2}\.log$/.test(n))
        .map((n) => n.slice(0, 10))
        .sort()
        .reverse();
    } catch { return []; }
  }

  /** Parsed lines for a day, oldest first. */
  read(date, limit = 1000) {
    const file = path.join(this.dir, `${date}.log`);
    let raw;
    try { raw = fs.readFileSync(file, 'utf8'); } catch { return []; }
    const lines = raw.split(/\r?\n/).filter(Boolean).slice(-limit);
    return lines.map((line) => {
      const m = /^(\d{2}:\d{2}:\d{2})\s+(\w+)\s+\[([^\]]+)\]\s+(.*)$/.exec(line);
      return m
        ? { time: m[1], level: m[2].toLowerCase(), scope: m[3], message: m[4] }
        : { time: '', level: 'info', scope: '', message: line };
    });
  }
}
