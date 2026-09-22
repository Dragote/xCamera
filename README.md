# xCamera

A manual camera app for Android. ISO, shutter speed and focus sit on dials you drag with a thumb; the
viewfinder shows a live 3D-LUT grade, a histogram, a horizon level and zebra clipping; capture writes a JPEG
and, if you want it, a RAW/DNG beside it.

It drives Camera2 directly and draws the preview with its own GLES renderer. That is what puts the LUT and the
manual exposure on the live preview rather than only in the saved file.

No network, no account, no analytics. Min SDK 26. A physical device is required — emulators expose neither the
extra lenses nor the manual controls.

## Features

**Capture**
- Still capture to `MediaStore`: JPEG, with optional RAW/DNG alongside
- Every physical back lens (ultra-wide, main, telephoto), flash modes
- Auto with an AE-compensation dial, or manual ISO and shutter speed
- Tap-to-focus, a hold-to-position focus ring with a magnified loupe, and a focus dial
- A last-shot thumbnail in the viewfinder corner; tapping it opens the system gallery

**Viewfinder**
- Composition grid, horizon level, live histogram
- Zebra clipping while a dial is being dragged, focus peaking inside the loupe
- Overlays rotate with the device while the controls stay put

**Color**
- 3D LUTs imported from `.cube` files, applied to the live preview and the saved JPEG
- A LUT dial on the viewfinder; the library and the 0–100% blend intensity live in Settings
- Imports are validated, resampled to a 33³ grid and stored as binary

**Diagnostics**
- A card per lens: aperture, resolution, sensor size, focal length with 35 mm equivalent, and whether that lens
  supports RAW, manual ISO and manual focus. Those capabilities differ between lenses on the same phone

**Settings**
- Persisted: grid, histogram and horizon toggles, RAW by default, haptics, inverted chrome,
  accent color, focus-peaking sensitivity

## Tech stack

| Area | Choice |
|---|---|
| Language / UI | Kotlin, Jetpack Compose — Material 3 as a base, custom Canvas chrome on top |
| Architecture | Clean Architecture + MVVM, `StateFlow` UI state |
| Async | Coroutines / Flow |
| DI | Hilt |
| Navigation | [compose-destinations](https://github.com/raamcosta/compose-destinations) |
| Camera | Camera2 + a GLES 3.0 preview renderer |
| Persistence | Jetpack DataStore |
| Testing | JUnit 4, MockK, Turbine, `kotlinx-coroutines-test` |
| Build | Gradle, an included `build-logic` build of convention plugins, version catalog |

Target and compile SDK 36, JVM target 11.
