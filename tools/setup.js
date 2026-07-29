// First-run setup: asks for your LMS login, writes .env, checks the credentials actually
// work, and optionally puts a launcher on your Desktop.
//
// Run with:  npm run setup     (or double-click setup.cmd)

import fs from 'node:fs';
import path from 'node:path';
import os from 'node:os';
import readline from 'node:readline';
import { fileURLToPath } from 'node:url';

const ROOT = path.join(path.dirname(fileURLToPath(import.meta.url)), '..');
const ENV_PATH = path.join(ROOT, '.env');

// Prompting works two ways: interactively at a terminal (the normal case, where the password
// must not be echoed), and from piped input so the flow can be scripted and tested. Node's
// readline does not reliably answer a second question when stdin is a pipe, so piped input is
// read in one go instead.
const interactive = Boolean(process.stdin.isTTY);

const rl = interactive
  ? readline.createInterface({ input: process.stdin, output: process.stdout })
  : null;

let muted = false;
if (rl) {
  const echo = rl._writeToOutput.bind(rl);
  rl._writeToOutput = (str) => { if (!muted) echo(str); };
}

let pipedLines = null;
let pipedIndex = 0;
async function nextPipedLine() {
  if (pipedLines === null) {
    const chunks = [];
    for await (const chunk of process.stdin) chunks.push(chunk);
    pipedLines = Buffer.concat(chunks).toString('utf8').split(/\r?\n/);
  }
  return (pipedLines[pipedIndex++] ?? '').trim();
}

async function ask(question) {
  if (!interactive) {
    const value = await nextPipedLine();
    process.stdout.write(`${question}${value}\n`);
    return value;
  }
  return new Promise((resolve) => rl.question(question, (a) => resolve(a.trim())));
}

/** Read a line without echoing it to the terminal. */
async function askHidden(question) {
  if (!interactive) {
    const value = await nextPipedLine();
    process.stdout.write(`${question}${value ? '(hidden)' : ''}\n`);
    return value;
  }
  return new Promise((resolve) => {
    process.stdout.write(question);
    muted = true;
    rl.question('', (value) => {
      muted = false;
      process.stdout.write('\n');
      resolve(value.trim());
    });
  });
}

const closePrompts = () => { if (rl) rl.close(); };

function desktopLauncher(root) {
  return [
    '@echo off',
    'title ISDM Companion',
    `cd /d "${root}"`,
    '',
    'if not exist ".env" (',
    '  echo   Missing .env - run setup.cmd in the project folder first.',
    '  pause',
    '  exit /b 1',
    ')',
    '',
    'rem If it is already running, just open the dashboard instead of starting a second copy.',
    'netstat -ano | findstr ":4321" | findstr "LISTENING" >nul 2>&1',
    'if %errorlevel%==0 (',
    '  echo   Already running - opening the dashboard.',
    '  start "" http://localhost:4321',
    '  exit /b 0',
    ')',
    '',
    'echo.',
    'echo   Starting ISDM Companion...',
    'echo   Dashboard: http://localhost:4321',
    'echo   Keep this window open. Close it or press Ctrl+C to stop.',
    'echo.',
    '',
    'start "" /min powershell -NoProfile -WindowStyle Hidden -Command "Start-Sleep -Seconds 3; Start-Process \'http://localhost:4321\'"',
    '',
    'node --env-file=.env src/server.js',
    '',
    'echo.',
    'echo   Server stopped.',
    'pause',
    '',
  ].join('\r\n');
}

console.log(`
  ISDM Companion - setup
  ----------------------
  This writes a .env file in this folder with your LMS login.
  It stays on this computer and is never uploaded anywhere.
`);

if (fs.existsSync(ENV_PATH)) {
  const overwrite = await ask('  .env already exists. Overwrite it? [y/N] ');
  if (!/^y(es)?$/i.test(overwrite)) {
    console.log('\n  Keeping the existing .env. Nothing changed.\n');
    closePrompts();
    process.exit(0);
  }
}

const email = await ask('  LMS email (e.g. you@pgp.isdm.org.in): ');
const password = await askHidden('  LMS password (not shown as you type): ');

if (!email || !password) {
  console.error('\n  Both an email and a password are required. Nothing was written.\n');
  closePrompts();
  process.exit(1);
}

console.log(`
  Auto-marking marks you present the moment a class opens, with no tap.
  Only turn this on if you start the app *because you are in the class*.
  Left running unattended it records you present for classes you did not attend.
`);
const autoOn = /^y(es)?$/i.test(await ask('  Turn auto-marking on? [y/N] '));

const template = fs.readFileSync(path.join(ROOT, '.env.example'), 'utf8');
const env = template
  .replace(/^LMS_EMAIL=.*$/m, `LMS_EMAIL=${email}`)
  .replace(/^LMS_PASSWORD=.*$/m, `LMS_PASSWORD=${password}`)
  .replace(/^AUTO_MARK=.*$/m, `AUTO_MARK=${autoOn ? 1 : 0}`);

fs.writeFileSync(ENV_PATH, env, 'utf8');
console.log(`\n  Wrote ${ENV_PATH}`);

// --- check the login actually works -----------------------------------------
console.log('  Checking your login against the LMS...');
try {
  const { LmsClient } = await import('../src/lms.js');
  const client = new LmsClient({ email, password });
  const who = await client.login();
  const { Service } = await import('../src/service.js');
  const cohorts = await new Service(client).cohorts();
  const tags = [...cohorts.sections].map((s) => `Section ${s}`)
    .concat([...cohorts.groups].map((g) => `Group ${g}`));
  console.log(`  Signed in as uid ${who.uid}${tags.length ? ` - ${tags.join(', ')}` : ''}`);
} catch (err) {
  console.error(`\n  Could not sign in: ${err.message}`);
  console.error('  Your .env was still written - fix the details in it and run setup again.');
  console.error('  If you only ever use "Sign in with Google", set a password on your LMS');
  console.error('  account first (Profile -> Change Password).\n');
  closePrompts();
  process.exit(1);
}

// --- optional desktop launcher ----------------------------------------------
if (process.platform === 'win32') {
  const wantsShortcut = await ask('\n  Put a launcher on your Desktop? [Y/n] ');
  if (!/^n(o)?$/i.test(wantsShortcut)) {
    const desktop = path.join(os.homedir(), 'Desktop');
    if (fs.existsSync(desktop)) {
      const target = path.join(desktop, 'ISDM Companion.bat');
      fs.writeFileSync(target, desktopLauncher(ROOT), 'utf8');
      console.log(`  Created "${target}"`);
    } else {
      console.log('  Could not find your Desktop folder - skipped.');
    }
  }
}

console.log(`
  Done. Start it any time with:

      npm start

  then open http://localhost:4321
`);
closePrompts();
