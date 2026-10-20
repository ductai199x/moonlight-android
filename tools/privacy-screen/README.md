# Privacy screen (host helper)

Blanks every physical monitor of a Windows host while a remote session is
running. The stream is unaffected: the black overlay windows are excluded from
screen capture (`WDA_EXCLUDEFROMCAPTURE`), so Sunshine and other capture
tools still see the real desktop. A small green dot at the top-right of the
primary monitor shows that the privacy screen is on (it is also visible in the
stream).

## Use

1. Build `PrivacyScreen.exe` with `build.ps1` (uses the C# compiler that ships
   with Windows; no SDK needed) and copy it to the host.
2. Run it once on the host. It sits in the system tray with no window.
3. Toggle with `Ctrl+Alt+Shift+P`, from the tray menu, or from the shield
   button in the Moonlight quick menu (desktop mode), which sends the same hotkey.

Command line for a running instance: `PrivacyScreen.exe --on`, `--off`,
`--toggle`. `--on --for 30` switches on and turns off again after 30 seconds.

## Notes and limits

- Requires Windows 10 version 2004 or later. If capture exclusion is not
  supported the helper warns, and the stream would show black.
- The mouse cursor stays visible on the blanked displays. Local keyboard and
  mouse are not blocked, and overlays are click-through so remote input works.
- The lock screen, UAC prompts and Ctrl+Alt+Del use the secure desktop and are
  not covered.
- If the hotkey is taken by another app the helper falls back to
  `Ctrl+Alt+Shift+F12`, then `F11`; the phone button only sends `P`. The log
  at `%TEMP%\PrivacyScreen.log` shows which hotkey was registered.
- The helper must be running on the host (start it with Windows if wanted).
