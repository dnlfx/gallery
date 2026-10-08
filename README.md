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

Tapping an item currently opens it in the system's default viewer; the in-app viewer with
video speed control comes next.
