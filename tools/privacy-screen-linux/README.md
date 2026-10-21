# Privacy screen (Linux host helper, X11)

Blanks every physical monitor of a Linux host while a remote session runs. The
stream is unaffected: the helper sets each output's gamma ramp to black with
`xrandr --brightness 0`. The ramp is applied after the framebuffer, so screen
capture (Sunshine, VNC, ...) still sees the real desktop.

## Use

1. Copy `privacy-screen` to the host, for example to `~/.local/bin`, and make
   it executable.
2. Run `privacy-screen` once per login. A tray icon appears: click it to
   toggle, right-click for the menu.
3. Bind `privacy-screen --toggle` to Ctrl+Alt+Shift+P in the desktop's
   keyboard settings. The Moonlight quick menu button (desktop mode) sends the
   same keys.

Command line for a running instance: `--on`, `--off`, `--toggle`, `--status`,
`--quit`. `--on --for 30` switches on and turns off again after 30 seconds.

Needs only Python 3 with PyGObject and `xrandr`. The tray icon is registered
directly over D-Bus (StatusNotifierItem), so no AppIndicator library is
needed. GNOME shows it through its AppIndicator extension (enabled by default
on Ubuntu); KDE and XFCE show it natively.

## Limits

- X11 only. Wayland compositors do not let other programs change gamma, so
  the helper refuses to start there. Log in to an Xorg session instead.
- A black gamma ramp makes the picture black, but the panel's backlight stays
  on, so a monitor shows black and not "no signal".
- The mouse cursor is hidden along with everything else, so there is no marker
  of the privacy state in the stream: use the tray icon or the phone button's
  tint.
- The helper re-applies the ramp every 2 seconds, so night-light or colour
  profile changes cannot undo it for long. If the helper is killed with
  SIGKILL the screen stays black: run `xrandr --output <name> --brightness 1`.
- Local keyboard and mouse are not blocked.
