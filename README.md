# Moonlight Android: Desktop Mode

A fork of [Moonlight for Android](https://github.com/moonlight-stream/moonlight-android) that makes remote desktop use practical on a phone. It adds zoom, pan, portrait streaming, and controls modeled on dedicated remote desktop apps, for tasks such as terminal and IDE work.

This is an unofficial build. It is not affiliated with the Moonlight project. It tracks upstream Moonlight and is currently based on version 12.2. It works with [Sunshine](https://github.com/LizardByte/Sunshine) and NVIDIA GameStream hosts.

All changes are behind one setting. With **Enable zoom and pan** off, the app behaves like upstream Moonlight.

## Download

Get the signed APK from [Releases](https://github.com/ductai199x/moonlight-android/releases). Each release lists the APK and signing certificate SHA-256 hashes.

The app installs alongside the official Moonlight app (package `com.limelight.unofficial`), so you must pair your host again.

## Setup

1. Install the APK and pair your host.
2. Open **Settings > Input Settings** and turn on **Enable zoom and pan**.
3. Optional: set the touchscreen mode in the same section. Trackpad mode is the default. You can also switch modes during a session.

## Features

- Pinch to zoom (1-5x) and pan. Touch, stylus, and mouse input map correctly to the host while zoomed.
- Portrait streaming. When the soft keyboard opens, the stream resizes to the space above it.
- In trackpad mode, the view follows the cursor to the screen edges.
- Sticky modifier bar above the soft keyboard: Win, Shift, Ctrl, Alt, and Tab.
- Function key pad: F1-F12, Esc, Print Screen, navigation keys, arrow keys, Enter, and common shortcuts.
- Text input mode with swipe typing, autocorrect, and voice dictation.
- On-screen mouse buttons with drag lock, and a scroll strip.
- Resolution and frame rate changes during a session.
- Picture-in-picture when you switch apps.
- Zoom level and position are saved for each host.

## Controls

### Touch gestures

| Gesture | Action |
| --- | --- |
| Pinch | Zoom in or out |
| Two-finger double-tap | Toggle between full view and 2.5x zoom at that point |
| Two-finger drag | Scroll |
| Two-finger tap | Right click |
| Three-finger tap | Show or hide the soft keyboard |

### Trackpad mode

| Gesture | Action |
| --- | --- |
| Drag | Move the cursor. At the screen edge, the view follows the cursor. |
| Tap | Left click |
| Press and hold until the phone vibrates, then release | Right click |
| Tap, then press again and drag | Hold the left button to select text or drag |

### Direct touch mode

| Gesture | Action |
| --- | --- |
| Tap | Left click at that point |
| Long press | Right click |
| Press and drag | Select or drag |

### On-screen controls

- **Handle (right edge):** the arrow opens the quick menu. The keyboard icon shows or hides the soft keyboard.
- **Quick menu:** navigation mode (one-finger pan, with no input sent to the host), keyboard, touchscreen mode, resolution and frame rate, scroll direction, performance stats, and disconnect.
- **Mouse buttons (bottom):** press and hold to hold the button. Double-tap a button to lock it down, and tap again to release it.
- **Scroll strip (right edge):** drag to scroll.
- **Modifier bar (above the keyboard):** Win, Shift, Ctrl, and Alt stay pressed until you tap them again. Tab uses the active modifiers, for example Alt+Tab. **Aa** turns on text input mode. The gear opens the quick menu. The last button switches between the soft keyboard and the function key pad.

To end a session, use the power button in the quick menu or close the picture-in-picture window.

## Building

Requirements:

- JDK 17
- Android SDK Platform 37 and Build-Tools 36
- Android NDK 29.0.14206865

Steps:

1. Clone with submodules: `git clone --recursive https://github.com/ductai199x/moonlight-android`
2. Create `local.properties` with `sdk.dir` set to your Android SDK directory.
3. Build: `./gradlew assembleNonRootRelease`
4. Sign `app/build/outputs/apk/nonRoot/release/app-nonRoot-release-unsigned.apk` with your own key (`zipalign`, then `apksigner`).

An APK signed with your own key cannot update an installation of the release APK. Uninstall one before you install the other.

## Credits

This fork is based on Moonlight for Android by:

* [Cameron Gutman](https://github.com/cgutman)
* [Diego Waxemberg](https://github.com/dwaxemberg)
* [Aaron Neyer](https://github.com/Aaronneyer)
* [Andrew Hennessy](https://github.com/yetanothername)

Moonlight is the work of students at [Case Western](http://case.edu) and was started as a project at [MHacks](http://mhacks.org). Moonlight also has a [PC client](https://github.com/moonlight-stream/moonlight-qt) and an [iOS/tvOS client](https://github.com/moonlight-stream/moonlight-ios).

Desktop mode was developed with AI assistance and tested on a Pixel 10 Pro XL with a Sunshine host on Windows 11.

## License

GPLv3, the same as upstream Moonlight. See [LICENSE.txt](LICENSE.txt).
