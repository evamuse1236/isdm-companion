#!/usr/bin/env node
// Drive the beta dashboard locally at phone (390x844) and desktop (1440x900) widths.
// Starts its own windowless vinext dev server, blocks every non-local request, opens
// the T-01 tester drilldown read-only, saves screenshots + drive.json, then stops the
// server it started. Never submits a form: the real dashboard controls tester access.
//
//   node .claude/skills/verify-isdm-companion/drive-dashboard.mjs [--out DIR] [--port 4417]
import { spawn, spawnSync } from "node:child_process";
import { createRequire } from "node:module";
import { createWriteStream, mkdirSync, writeFileSync } from "node:fs";
import { join, resolve } from "node:path";
import { fileURLToPath } from "node:url";

const root = fileURLToPath(new URL("../../../", import.meta.url));
const dashboard = join(root, "beta-dashboard");
const arg = (name, fallback) => {
  const i = process.argv.indexOf(name);
  return i > 0 ? process.argv[i + 1] : fallback;
};
const out = resolve(arg("--out", join(root, ".verify", "dashboard")));
const port = Number(arg("--port", "4417"));
const base = `http://localhost:${port}`;
mkdirSync(out, { recursive: true });

const require = createRequire(join(dashboard, "package.json"));
let chromium;
try {
  ({ chromium } = require("playwright-core"));
} catch {
  console.error("playwright-core is missing: run `npm run setup` from the repo root.");
  process.exit(1);
}

async function answers(url) {
  try {
    return (await fetch(url, { signal: AbortSignal.timeout(3000) })).status;
  } catch {
    return 0;
  }
}

function startServer() {
  const log = createWriteStream(join(out, "dev-server.log"));
  // Windows: windowsHide and no `detached`, so no console window opens. Elsewhere a
  // process group lets stop() take vinext's workerd children down with it.
  const child = spawn(process.execPath, [join(dashboard, "node_modules", "vinext", "dist", "cli.js"), "dev", "--port", String(port)], {
    cwd: dashboard, windowsHide: true, detached: process.platform !== "win32", stdio: ["ignore", "pipe", "pipe"],
  });
  child.stdout.pipe(log);
  child.stderr.pipe(log);
  return child;
}

function stop(child) {
  if (!child || child.exitCode !== null) return;
  if (process.platform === "win32") {
    spawnSync("taskkill", ["/PID", String(child.pid), "/T", "/F"], { windowsHide: true, stdio: "ignore" });
  } else {
    try { process.kill(-child.pid, "SIGTERM"); } catch { child.kill("SIGTERM"); }
  }
}

const widths = [
  { name: "phone", viewport: { width: 390, height: 844 } },
  { name: "desktop", viewport: { width: 1440, height: 900 } },
];
const report = { base, out, startedAt: new Date().toISOString(), runs: [], blockedRequests: [] };
let server;
let browser;
let failed = false;

try {
  if (await answers(base)) throw new Error(`Port ${port} is already in use; pass --port or stop what is listening there.`);
  server = startServer();
  const deadline = Date.now() + 120_000;
  while ((await answers(base)) !== 200) {
    if (server.exitCode !== null) throw new Error(`vinext dev exited early (code ${server.exitCode}); see ${join(out, "dev-server.log")}`);
    if (Date.now() > deadline) throw new Error(`vinext dev did not answer 200 on ${base} within 120 s; see dev-server.log`);
    await new Promise((r) => setTimeout(r, 1000));
  }
  browser = await chromium.launch();
  for (const { name, viewport } of widths) {
    const run = { width: name, viewport, checks: {}, consoleErrors: [], screenshots: [] };
    const context = await browser.newContext({ viewport });
    await context.route("**/*", (route) => {
      const host = new URL(route.request().url()).hostname;
      if (host === "localhost" || host === "127.0.0.1") return route.continue();
      report.blockedRequests.push(route.request().url());
      return route.abort();
    });
    const page = await context.newPage();
    page.on("console", (msg) => { if (msg.type() === "error") run.consoleErrors.push(msg.text()); });
    page.on("pageerror", (error) => run.consoleErrors.push(String(error)));
    const response = await page.goto(base, { waitUntil: "networkidle" });
    run.checks.status200 = response?.status() === 200;
    run.checks.heading = await page.getByText("PRIVATE BETA COMMAND DESK").first().isVisible();
    run.checks.rosterHasT01andT10 = (await page.locator("button.code-chip", { hasText: "T-01" }).count()) > 0
      && (await page.locator("button.code-chip", { hasText: "T-10" }).count()) > 0;
    run.checks.noViteErrorOverlay = (await page.locator("vite-error-overlay").count()) === 0;
    run.checks.noSidewaysScroll = await page.evaluate(() => document.documentElement.scrollWidth <= window.innerWidth + 1);
    const shot = join(out, `dashboard-${name}.png`);
    await page.screenshot({ path: shot });
    run.screenshots.push(shot);
    // Feature: open the T-01 tester drilldown (read-only), then close it.
    await page.locator("button.code-chip", { hasText: "T-01" }).first().click();
    const dialog = page.getByRole("dialog");
    run.checks.drilldownOpens = await dialog.getByRole("heading").first().isVisible();
    run.checks.drilldownNamesT01 = (await dialog.textContent())?.includes("T-01") ?? false;
    const drill = join(out, `drilldown-${name}.png`);
    await page.screenshot({ path: drill });
    run.screenshots.push(drill);
    await dialog.getByRole("button", { name: "Close" }).first().click();
    run.checks.drilldownCloses = (await page.getByRole("dialog").count()) === 0;
    run.pass = Object.values(run.checks).every(Boolean);
    failed ||= !run.pass;
    report.runs.push(run);
    console.log(`${run.pass ? "PASS" : "FAIL"} dashboard ${name} ${viewport.width}x${viewport.height} ${JSON.stringify(run.checks)}`);
    await context.close();
  }
} catch (error) {
  failed = true;
  report.error = String(error?.stack ?? error);
  console.error(report.error);
} finally {
  await browser?.close();
  stop(server);
  report.finishedAt = new Date().toISOString();
  writeFileSync(join(out, "drive.json"), `${JSON.stringify(report, null, 2)}\n`);
  console.log(`evidence: ${out}`);
}
process.exit(failed ? 1 : 0);
