# Gallery

An Android gallery for Pixel phones that works like the iPhone Photos app: every photo and
video on the device in one grid, sorted by last modified (newest first), with no splitting
into Camera, Movies, Downloads and so on.

Built with Kotlin, Jetpack Compose and Coil. Minimum Android 10, targets Android 15.

## Build

```
./gradlew assembleDebug
```

The APK lands in `app/build/outputs/apk/debug/`. Every CI run on GitHub Actions also uploads
it as the `gallery-debug-apk` artifact.

## Layout

- `data/` MediaStore query for all images and videos, re-run whenever the library changes.
- `thumbnail/` Coil fetcher that uses the system thumbnail cache (handles any format the
  platform can decode).
- `ui/permission/` Photos and videos permission, including Android 14's "selected photos" mode.
- `ui/grid/` The all-media grid.

- `ui/viewer/` Full-screen viewer: swipe between items in grid order, pinch or double-tap to
  zoom photos, Media3 video playback.

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
