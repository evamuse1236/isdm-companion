import assert from "node:assert/strict";
import { readFile } from "node:fs/promises";
import test from "node:test";

async function render() {
  const workerUrl = new URL("../dist/server/index.js", import.meta.url);
  workerUrl.searchParams.set("test", `${process.pid}-${Date.now()}`);
  const { default: worker } = await import(workerUrl.href);

  return worker.fetch(
    new Request("http://localhost/", {
      headers: { accept: "text/html" },
    }),
    {
      ASSETS: {
        fetch: async () => new Response("Not found", { status: 404 }),
      },
    },
    {
      waitUntil() {},
      passThroughOnException() {},
    },
  );
}

test("server-renders the beta command desk", async () => {
  const response = await render();
  assert.equal(response.status, 200);
  assert.match(response.headers.get("content-type") ?? "", /^text\/html\b/i);

  const html = await response.text();
  assert.match(html, /<title>ISDM Companion Beta Control<\/title>/i);
  assert.match(html, /PRIVATE BETA COMMAND DESK/);
  assert.match(html, /Auto attendance for all testers/);
  assert.match(html, /Stop auto attendance for everyone/);
  assert.match(html, /T-01/);
  assert.match(html, /T-10/);
  assert.doesNotMatch(html, /Your site is taking shape|Building your site/);
});

test("keeps the dashboard dynamic and owner-aware", async () => {
  const [page, layout, dashboard] = await Promise.all([
    readFile(new URL("../app/page.tsx", import.meta.url), "utf8"),
    readFile(new URL("../app/layout.tsx", import.meta.url), "utf8"),
    readFile(new URL("../app/Dashboard.tsx", import.meta.url), "utf8"),
  ]);

  assert.match(page, /export const dynamic = "force-dynamic"/);
  assert.match(page, /getChatGPTUser\(\)/);
  assert.match(layout, /title:\s*"ISDM Companion Beta Control"/);
  assert.match(dashboard, /Stop auto attendance for everyone/);
  assert.match(dashboard, /role=\{latestSuspected \? "alert" : "status"\}/);
});
