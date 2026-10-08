# Gallery

An Android gallery for Pixel phones that works like the iPhone Photos app: every photo and
video on the device in one grid, sorted by last modified (newest first), with no splitting
into Camera, Movies, Downloads and so on.

Built with Kotlin, Jetpack Compose and Coil. Minimum Android 10, targets Android 15.

## Install

Every merge to `main` publishes a signed APK as a GitHub Release. The newest one is always at

https://github.com/dnlfx/gallery/releases/latest/download/gallery.apk

and downloads without a GitHub account. Releases are signed with the same key every time, so a
new one installs over the old one without uninstalling. (A debug build from a CI run is
signed differently: uninstall it once before installing a release.)

## Build

```
./gradlew assembleDebug
```

The APK lands in `app/build/outputs/apk/debug/`. Every CI run on GitHub Actions also uploads
it as the `gallery-debug-apk` artifact.

### Release signing key (one-time setup)

CI signs releases with a key kept in two repository secrets. Make the key on a Mac or Linux
machine (the password prompt comes up twice; use the same one both times):

```
openssl req -x509 -newkey rsa:4096 -sha256 -days 10000 -nodes -subj "/CN=Gallery" \
  -keyout gallery-key.pem -out gallery-cert.pem
openssl pkcs12 -export -name gallery -inkey gallery-key.pem -in gallery-cert.pem -out gallery-release.p12
rm gallery-key.pem gallery-cert.pem
base64 -i gallery-release.p12 | pbcopy    # Linux: base64 -w0 gallery-release.p12
```

Then in the repository's Settings, Secrets and variables, Actions, add:

- `RELEASE_KEYSTORE_BASE64`: the copied text.
- `RELEASE_KEYSTORE_PASSWORD`: the password.

Keep `gallery-release.p12` and its password somewhere safe. Never commit them. If the key is
lost, the next release has to be installed fresh after uninstalling the app.

Until the secrets exist, CI signs with a throwaway key and publishes nothing.

## Layout

- `data/` MediaStore query for all images and videos, re-run whenever the library changes.
- `thumbnail/` Coil fetcher that uses the system thumbnail cache (handles any format the
  platform can decode).
- `ui/permission/` Photos and videos permission, including Android 14's "selected photos" mode.
- `ui/grid/` The all-media grid, with a fast-scroll thumb on the right edge for big libraries.

- `ui/viewer/` Full-screen viewer: swipe between items in grid order, pinch or double-tap to
  zoom photos, Media3 video playback.

## Formats

- Photos: JPEG, PNG, HEIC, AVIF, WebP and RAW (DNG, plus other RAW files the phone can read).
  Zooming in past screen size decodes the part on screen from the original at full resolution
  (JPEG, PNG, WebP, HEIC, and AVIF where the phone supports it).
- Animated GIF, WebP and HEIF play in the viewer; the grid shows a still.
- Video: anything Media3 and the phone's decoders handle, including MP4, MKV, WebM and 3GP with
  H.264, HEVC, VP9 and AV1. A video the phone can't decode shows a message instead of a black
  screen.

## Privacy

Nothing leaves the phone. The app has no network permission (the manifest strips it even if a
library asks for it, and CI fails if it ever appears), no analytics or tracking libraries, and
backups are turned off.

## Video controls

- Tap the left third of the screen to go back 10 seconds, the right third to go forward 10
  seconds (tap again to add 10 more). Tap the middle to show or hide the controls.
- Slide sideways anywhere on the video to scrub through it.
- Slide up or down to change the volume.
- Speed button in the bottom bar: 0.25x to 3x. The speed stays set for the next video.
- With controls hidden, progress is a 2dp line along the bottom edge.
- On a video, sideways slides scrub, so use the arrows in the bottom bar to move to the
  previous or next item. On photos, swipe as usual.
- The rotate button in the top bar flips between portrait and landscape; otherwise the
  viewer follows the phone's auto-rotate setting.
