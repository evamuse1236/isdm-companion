// cmd.exe mis-parses LF-only batch files: multi-line if blocks and pipes break
// (2f7fc12, start.cmd failed with ERROR_NO_DATA). .gitattributes forces CRLF on
// checkout; this test catches a launcher written with LF and the rule being dropped.
import assert from 'node:assert/strict';
import test from 'node:test';
import { readdir, readFile } from 'node:fs/promises';
import { join, relative } from 'node:path';
import { fileURLToPath } from 'node:url';

const root = fileURLToPath(new URL('..', import.meta.url));
const SKIP = new Set(['.git', 'node_modules', 'build', '.gradle', '.kotlin', 'dist', '.wrangler', '.vinext']);

async function launchers(dir) {
  const found = [];
  for (const entry of await readdir(dir, { withFileTypes: true })) {
    if (entry.isDirectory()) {
      if (!SKIP.has(entry.name)) found.push(...await launchers(join(dir, entry.name)));
    } else if (/\.(cmd|bat)$/i.test(entry.name)) {
      found.push(join(dir, entry.name));
    }
  }
  return found;
}

test('.gitattributes keeps Windows launchers on CRLF', async () => {
  const attributes = await readFile(join(root, '.gitattributes'), 'utf8');
  assert.match(attributes, /^\*\.cmd\s+text\s+eol=crlf\s*$/m, 'add "*.cmd text eol=crlf" to .gitattributes');
  assert.match(attributes, /^\*\.bat\s+text\s+eol=crlf\s*$/m, 'add "*.bat text eol=crlf" to .gitattributes');
});

test('every .cmd and .bat launcher uses CRLF line endings', async () => {
  const files = await launchers(root);
  assert.ok(files.length > 0, 'expected at least android/gradlew.bat');
  const lfOnly = [];
  for (const file of files) {
    const text = await readFile(file, 'latin1');
    if (/(^|[^\r])\n/.test(text)) lfOnly.push(relative(root, file));
  }
  assert.deepEqual(lfOnly, [], 'Re-save these with CRLF line endings (cmd.exe mis-parses LF-only scripts)');
});
