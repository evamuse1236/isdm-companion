// Stop Windows dropping into Modern Standby while the app is running.
//
// This matters because a suspended machine cannot mark anything: networking is cut in
// standby, so a class that opens while the laptop is asleep is simply missed. We hold
// ES_SYSTEM_REQUIRED for as long as the server runs, and release it on exit.
//
// Caveat: this prevents *idle* sleep only. Closing the lid, or choosing Sleep from the
// Start menu, still suspends the machine whatever we ask for.

import { spawn } from 'node:child_process';

const SCRIPT = `
$signature = @'
[DllImport("kernel32.dll", SetLastError = true)]
public static extern uint SetThreadExecutionState(uint esFlags);
'@
$api = Add-Type -MemberDefinition $signature -Name Power -Namespace Win32 -PassThru
# ES_CONTINUOUS (0x80000000) | ES_SYSTEM_REQUIRED (0x00000001)
[void]$api::SetThreadExecutionState([uint32]'0x80000001')
while ($true) { Start-Sleep -Seconds 3600 }
`;

let child = null;

/** Returns true if the request was made. */
export function keepAwake(log) {
  if (process.platform !== 'win32' || child) return false;
  try {
    child = spawn('powershell.exe', ['-NoProfile', '-NonInteractive', '-Command', SCRIPT], {
      stdio: 'ignore',
      windowsHide: true,
    });
    child.on('error', (err) => {
      log?.warn('awake', `could not hold the system awake: ${err.message}`);
      child = null;
    });
    child.on('exit', () => { child = null; });
    return true;
  } catch (err) {
    log?.warn('awake', `could not hold the system awake: ${err.message}`);
    child = null;
    return false;
  }
}

export function releaseAwake() {
  if (!child) return;
  try { child.kill(); } catch { /* already gone */ }
  child = null;
}
