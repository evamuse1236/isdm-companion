// Windows toast notifications, so a class opening for marking reaches you even when the
// dashboard tab is closed. Uses the built-in WinRT toast API through PowerShell - no deps.

import { spawn } from 'node:child_process';

const APP_ID = '{1AC14E77-02E7-4E5D-B744-2EB1AE5198B7}\\WindowsPowerShell\\v1.0\\powershell.exe';

const SCRIPT = `
$ErrorActionPreference = 'Stop'
[void][Windows.UI.Notifications.ToastNotificationManager, Windows.UI.Notifications, ContentType = WindowsRuntime]
[void][Windows.Data.Xml.Dom.XmlDocument, Windows.Data.Xml.Dom.XmlDocument, ContentType = WindowsRuntime]
$template = [Windows.UI.Notifications.ToastNotificationManager]::GetTemplateContent([Windows.UI.Notifications.ToastTemplateType]::ToastText02)
$nodes = $template.GetElementsByTagName('text')
[void]$nodes.Item(0).AppendChild($template.CreateTextNode($env:TOAST_TITLE))
[void]$nodes.Item(1).AppendChild($template.CreateTextNode($env:TOAST_BODY))
$toast = New-Object Windows.UI.Notifications.ToastNotification $template
[Windows.UI.Notifications.ToastNotificationManager]::CreateToastNotifier($env:TOAST_APPID).Show($toast)
`;

let warned = false;

export function toast(title, body) {
  if (process.platform !== 'win32') return;
  try {
    const child = spawn('powershell.exe', ['-NoProfile', '-NonInteractive', '-STA', '-Command', SCRIPT], {
      env: { ...process.env, TOAST_TITLE: title, TOAST_BODY: body, TOAST_APPID: APP_ID },
      stdio: 'ignore',
      windowsHide: true,
      detached: false,
    });
    child.on('error', reportOnce);
  } catch (err) {
    reportOnce(err);
  }
}

function reportOnce(err) {
  if (warned) return;
  warned = true;
  console.warn(`[notify] Desktop notifications unavailable: ${err.message}`);
}
