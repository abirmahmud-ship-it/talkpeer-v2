# TalkPeer Android

Capacitor wrapper around the TalkPeer web app. Builds to a real `.apk`.

```
www/
├─ index.html              TalkPeer app (Android build uses the native
│                           bridge for screen share, see below)
└─ android-screenshare.js   turns native capture frames into a MediaStream
                            via canvas.captureStream()

android/                    generated Gradle project, already wired up
└─ app/src/main/
   ├─ AndroidManifest.xml           camera/mic/network/foreground-service perms
   └─ java/.../
      ├─ MainActivity.java          grants WebView getUserMedia(), registers plugin
      ├─ ScreenCapturePlugin.java   JS ↔ native bridge
      └─ ScreenCaptureService.java  MediaProjection screen capture

capacitor.config.json
.github/workflows/build-android.yml   CI build, no Android Studio needed
```

## Build

**CI (no local setup):**

```
git push -u origin main
```

Actions tab → `Build Android APK` → Run workflow (or push a tag `v1.0.0` to
trigger it automatically). Grab `TalkPeer-Android-APK` from the run's
artifacts when it's done — that's `app-debug.apk`. Sideload it; Android will
complain about unknown sources on first install, that's expected for
anything outside the Play Store.

Debug-signed (throwaway Gradle key). Fine to install and use. Not enough to
publish — see Play Store section below.

**Local:**

```
npm install
npx cap sync android
npx cap open android
```

Build → Build APK(s) in Android Studio, or skip the IDE:

```
cd android
./gradlew assembleDebug
```

Output: `android/app/build/outputs/apk/debug/app-debug.apk`

## App Links (`abir.ovh/<code>` opens the app, not a browser)

Needs `assetlinks.json` hosted at `https://abir.ovh/.well-known/assetlinks.json`
— already generated at repo root, just upload it as-is.

Verification checks the APK's actual signing cert against that file's
fingerprint. `android/keystore/talkpeer-debug.keystore` is checked in so
every build (local or CI) signs with the same key. Regenerate the keystore →
regenerate `assetlinks.json`'s fingerprint too, or verification silently
breaks with no error.

Test: open an `abir.ovh/<code>` link from Messages/email. Opens TalkPeer
directly = working. Opens a browser instead = check the JSON is reachable at
that exact path with no redirects, and the fingerprint matches the build
that's actually installed.

## Screen share

No native `getDisplayMedia()` in Android WebViews, so this isn't the browser
API — it's `MediaProjection` (same thing Android's own screen recorder
uses), running as a foreground service with a persistent notification +
"Stop sharing" button (mandatory for capture services, not optional).

Frames come over the Capacitor bridge as JPEGs, downscaled to 960px long
edge, drawn to an offscreen canvas, `captureStream()`'d into a normal
MediaStream. From there it's indistinguishable from a desktop share as far
as WebRTC/PeerJS is concerned.

~8fps, JPEG-compressed. Fine for slides/docs, not for video playback or fast
scrolling. That's the ceiling of bridging frames through a WebView this way
— a proper fix means native WebRTC integration instead of reusing the page's
PeerJS stack, which is a much bigger job than this.

No system audio capture — video feed only. `MediaProjection` can do app
audio too, just not wired in here.

Practically needs Android 10+ (foreground-service rules assume it), even
though `minSdkVersion` is lower for the rest of the app.

## Other notes

- Camera/mic permission handling is done — `MainActivity.java` requests
  runtime perms and grants the WebView's `getUserMedia()` calls explicitly.
  Without this, calls fail silently, since a WebView doesn't handle any of
  that on its own the way a real browser does.
- Requires internet — PeerJS's public signaling server + STUN/TURN, same as
  the desktop build. No offline mode.
- App icon: set (see `android/app/src/main/res/mipmap-*/`). Regenerate the
  same way if you rebrand — Android Studio's Image Asset Studio
  (right-click `res` → New → Image Asset) is the easiest path, or just
  overwrite the PNGs directly per density.
- Play Store publishing needs a signed `.aab`, not this debug `.apk` — different
  keystore setup, different build command. Ask if you need that.

MIT, same as the rest of TalkPeer.
