# wasmcart Android player

An Android app that plays [wasmcart](https://github.com/wasmcart/wasmcart) `.wasc`
carts. Point it at a folder, tap a game. It also opens `.wasc` files from file
managers and Downloads.

No WebView: carts run on V8 (via libnode) behind the same shared C host as the
desktop player and the libretro core, [wasmcart-native](https://github.com/wasmcart/wasmcart-native).

- **Library**: any folder you pick, scanned recursively. **Rescan** finds new files.
- **Opening files**: the app answers to `.wasc` files, and to the generic types a
  download of one arrives as (`application/octet-stream`, zip). Android has no type
  for `.wasc`, so it also appears for other files of those types, and says so if
  the one you opened is not a cart.
- **Controls**: gamepads (four players, hot-plug, rumble), touch, and mice. Every
  cart gets an on-screen gamepad while no gamepad is connected: what the manifest's
  `controls` hint lists, or the whole pad (two sticks, shoulders, triggers) if it
  lists nothing, since a player can't tell what a cart reads. It fades out when a gamepad connects and back in when
  it drops. A touch on a control goes to the control and any other touch goes to the
  cart; a cart that wants the whole touchscreen declares `"controls": []`. A cart
  that declares both `dpad` and `left_stick` gets one stick that also drives the
  d-pad.
- **Your layout, per game**: the **Controls** button over the game pauses it and
  opens the settings. Switch sticks, stick clicks, shoulders and triggers on or off
  for any cart, whatever it declares (with sticks off, the left thumb gets a d-pad);
  drag any control; change size and opacity; choose fixed or floating sticks.
- **Either way up**: the player follows the device and its auto-rotate lock, even
  mid-game. Held upright, the game goes at the top with the controls below it, and
  the layout you arrange is kept separately for each orientation.
- **Saves**: kept in the app's private storage, keyed by cart file name and size,
  so a cart keeps its save however you opened it.

Android 13 or later on arm64 (libnode's floor). WebGPU-only carts are refused with
the reason; a cart that also imports GL runs on GL.

## Install

Download the APK from the [latest release](../../releases/latest), or from the
artifact of any Actions run, and install it, allowing installs from that source
when Android asks.

## Build

Needs JDK 17+ and the Android SDK (`ANDROID_HOME`, or `sdk.dir` in
`local.properties`). AGP downloads NDK r27 and CMake 3.22 if they are missing.

```sh
bash deps/fetch-deps.sh     # wasmcart-native, SDL2 source, prebuilt libnode (one-time)
./gradlew assembleDebug
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

`deps/fetch-deps.sh` pins all three. To move to a newer host, change
`WASMCART_NATIVE_REF` there and delete `deps/wasmcart-native`.

### CI and signing

`.github/workflows/build.yml` builds a release APK on every push, keeps it as a
workflow artifact, and attaches it to a GitHub release for `v*` tags. Android only
installs an update signed with the same key as the installed copy, so CI signs with
a key from these repository secrets:

| Secret | Value |
|---|---|
| `WASMCART_KEYSTORE_BASE64` | the `.jks` keystore, base64-encoded |
| `WASMCART_KEYSTORE_PASSWORD` | keystore password |
| `WASMCART_KEY_ALIAS` | key alias |
| `WASMCART_KEY_PASSWORD` | key password |

Without them (forks, pull requests) the APK is signed with a throwaway debug key.

## How it fits together

- `LibraryActivity` is the app's UI. It copies the chosen cart to a real file,
  since the host's zip reader needs a path, and starts the player.
- `PlayerActivity` extends SDL's `SDLActivity` and runs in its own process
  (`:player`), one process per cart: V8 cannot start twice in a process, and a cart
  that takes the host down leaves the library running. A cart that fails to load or
  stops with an error says why in a dialog.
- `native/android_main.c` is the frame loop: fixed 60 Hz cart clock with wall-clock
  catch-up and an audio queue kept near 80 ms. The GL context is owned by SDL;
  native code never creates an EGL context.
- `native/overlay.c` is the on-screen gamepad, sized in millimeters from the
  display DPI.

## License

MIT
