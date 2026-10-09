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
- `ui/grid/` The all-media grid, grouped under month headers, with a fast-scroll thumb on the right
  edge for big libraries. Pinch to switch between about 3, 4, 6 and 8 columns; the choice is kept.
  Press and hold an item to select it, then keep holding and slide to select a run of items (the
  grid scrolls when you reach the top or bottom); tap to add or remove more. Selected items can be
  starred or moved to the trash together (Android 11+). The menu in the top bar opens the trash,
  where items can be restored or deleted for good before the system clears them after 30 days.

- `ui/viewer/` Full-screen viewer: swipe between items in grid order, pinch or double-tap to
  zoom photos, swipe a photo down to close, Media3 video playback. The top bar has a favorite
  button and a trash button (Android 11+), which moves the item to the system trash after the
  system's own confirmation, recoverable for 30 days. Its menu has Edit, which opens the built-in editor
  (there's no handing items to other apps' editors), details (dates, folder, resolution,
  size), and Rotate screen. Ultra HDR photos, which Pixel cameras save, show with their full
  brightness on Android 14 and later, as in Photos.
- `ui/editor/` The built-in editor, opened with Edit in the viewer's menu: crop for photos, crop
  and trim for videos (see below).

Other apps (Files, Messages, the camera) can open a photo or video in Gallery. One that's in the
library opens among its neighbours; anything else opens on its own, without needing the photos
permission.

## Formats

- Photos: JPEG, PNG, HEIC, AVIF, WebP and RAW (DNG, plus other RAW files the phone can read).
  Zooming in past screen size decodes the part on screen from the original at full resolution
  (JPEG, PNG, WebP, HEIC, and AVIF where the phone supports it).
- Animated GIF, WebP and HEIF play in the viewer; the grid shows a still.
- Video: anything Media3 and the phone's decoders handle, including MP4, MKV, WebM and 3GP with
  H.264, HEVC, VP9 and AV1. A video the phone can't decode shows a message instead of a black
  screen.

## Editing

Edit in the viewer's menu opens the built-in editor, which runs entirely on the phone. Animated
GIFs aren't offered it. Its tabs are Crop and Adjust, plus Trim for videos. Undo steps back one
change at a time, Reset goes back to the start, and holding the compare button shows the original.

- Saving an edited photo replaces the original, after the system asks for permission (Android 11
  and later). It's rendered from the original at full resolution and saved at the best quality
  the format allows: PNG and WebP losslessly, JPEG at quality 100. A crop alone is cut pixel for
  pixel. The date taken, camera details and location carry over. Formats the phone can't write
  back (HEIC, AVIF, RAW), files other apps handed over, and Android 10 get a new copy instead,
  which keeps the same details. The original is copied aside first and put back if the save
  fails partway, and a phone too full to hold both stops before anything changes. A photo save
  can't be stopped once it has started, so it's never left half applied.
- An edited video is always saved as a new copy next to the original, and the viewer moves to it.
  Copies go in Pictures/Gallery or Movies/Gallery where the original's folder can't take them.
- Crop: a box starts around the whole photo or video. Drag a corner or edge to resize it, or drag
  inside to move it. It can be free or locked to Original, Square, 4:3 or 16:9, turned upright
  or on its side.
- Rotate turns the picture 90° clockwise; the two Flip buttons mirror it left to right and top
  to bottom. Straighten tilts it up to 45° either way and zooms in just enough that no empty
  corners show.
- Auto fit: finds the picture inside black bars and flat app chrome (status bar, navigation bar,
  toolbars) and sets the box to it, ready to adjust. For videos it checks several frames, so a
  dark scene doesn't throw it off. It's a best guess and leaves edges alone when unsure, such as
  a clear sky at the top of a photo.
- Adjust: brightness, contrast, saturation and warmth.
- Trim (videos): drag the handles at each end of the frame strip to choose the part to keep; the
  video shows the frame under the handle and plays the kept part on a loop. The speaker button
  next to it mutes the saved copy. A trim alone copies the video as it is apart from its first
  moments, so it's quick and keeps the original quality; any change to the picture re-encodes it
  on the phone's hardware encoder (Media3 Transformer).

## Privacy

Nothing leaves the phone. The app has no network permission (the manifest strips it even if a
library asks for it, and CI fails if it ever appears, or if any permission other than photos and
videos does), no analytics or tracking libraries, and neither cloud backup nor moving to a new
phone copies anything the app keeps. Release builds write nothing to the system log. Other apps
can only hand it content links, never file paths.

## Video controls

- Tap anywhere to show or hide the controls. Double-tap the left third of the screen to go back
  10 seconds, the right third to go forward 10 seconds (keep tapping to add 10 more), or the
  middle to play or pause.
- Pinch to zoom up to 5x. While zoomed in, slide one finger to move around the picture; pinch
  back out to return to the full frame.
- While a video is paused, the camera button in the top bar saves the frame on screen as a
  picture in Pictures/Gallery, at the video's full resolution.
- With TalkBack, the video offers actions to skip back or forward 10 seconds and to change the
  volume, and the seek bar can be adjusted like a slider.
- Swipe sideways to move to the previous or next item, the same as on photos.
- Press and hold anywhere on the video until you feel a buzz, then slide sideways to scrub
  through it. Slow slides move precisely; quick ones cover more ground. You feel a tick at the
  start and end.
- Slide up or down to change the volume.
- Speed button in the bottom bar: 0.25x to 3x. The speed stays set for the next video.
- Loop button in the bottom bar: when it's on, every video plays again from the start when it
  finishes. The choice is saved, so it stays on until you turn it off.
- With controls hidden, progress is a 2dp line along the bottom edge.
- Rotate screen in the top bar's menu flips between portrait and landscape; otherwise the
  viewer follows the phone's auto-rotate setting.
