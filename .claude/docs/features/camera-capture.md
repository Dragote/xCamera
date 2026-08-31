# Camera capture

**Purpose:** The core capture pipeline for `feature:camera` — a flat minimal-chrome viewfinder driving a raw Camera2 capture flow.

**What the feature contains:**
- Auto mode (AE compensation dial) and manual mode (ISO + shutter dials), switched by `ModeToggle`
- Manual focus: tap-to-focus, a hold-to-position ring with a magnified loupe, and a dedicated `FocusDial`
- Lens switching, flash, JPEG capture to `MediaStore`, opt-in RAW/DNG alongside it
- Viewfinder overlays: grid, horizon level, zebra clipping, histogram, focus-peaking inside the loupe
- No manual white balance yet

**Key decisions:**

*Why Camera2 at all*
- Migrated off CameraX in #10. `Camera2Interop`/`Camera2CameraControl` only expose session-wide capture-request overrides, with no supported way to keep a fast repeating preview independent of a still request. Long manual shutter speeds (4s+) therefore stalled the preview and eventually failed capture outright — queued long-exposure preview frames backed up in the HAL ahead of the still request until CameraX's session watchdog aborted. Direct `CameraCaptureSession` ownership removes the coupling.
- `CameraController`/`CameraRepositoryImpl` must stay `@Singleton`. Unscoped, `CameraViewModel`'s injection and `CameraScreen`'s `EntryPointAccessors` call resolve *different* instances, so `bindCamera()` runs on one and `takePhoto()` on another that was never bound.
- `CameraController` is the **sole** lifecycle owner (`LifecycleEventObserver`, open at `ON_START` / close at `ON_STOP`). Don't add a second observer on the same `Lifecycle` from the UI: an attempt to swap in a fresh `SurfaceTexture` per `ON_START` raced this one for `Surface` identity and crashed with `IllegalArgumentException: Surface was abandoned` inside `createCaptureSession`.

*The preview is rendered by the app, not by the compositor*
- `CameraController` targets its repeating request at its own `YUV_420_888` `ImageReader`; `CameraPreviewRenderer` (`ui/gl/`) draws each frame to the `TextureView` through a GLES YUV→RGB pipeline. The original `SurfaceTexture`-direct design stretched the preview after *some* session reopens: the HAL silently delivered a different stream size than `setDefaultBufferSize` requested, with no API to detect it, so a transform computed from the *requested* size was miscalibrated. An `ImageReader`'s dimensions are a construction-time contract (`onConfigureFailed` if unmet, never substituted), and the transform is rebuilt from each frame's actual `Image.width`/`height`, so it self-corrects on every reopen.
- Ruled out before landing on that, so don't retry them: re-asserting the requested size on resume; awaiting `onClosed()` before reopening (a real hygiene fix, kept, but not this bug); switching center-fit vs center-crop (the distortion is baked in earlier in the transform math); a second lifecycle observer (crashed, above).
- Two performance constraints are load-bearing, both confirmed on-device as the difference between smooth and visibly janky: `repackPlane` must bulk-read each row into a reused `ByteArray` and de-interleave in a plain loop, never per-byte `ByteBuffer.get`/`put`; and `uploadTexture` must call `glTexImage2D` once per size and `glTexSubImage2D` for every later frame, never reallocating storage per frame. `GL_UNPACK_ALIGNMENT` is 1 because GL's 4-byte default misreads rows whose width isn't a multiple of 4.
- The renderer must apply `SENSOR_ORIENTATION` itself. An `ImageReader` buffer is always in the sensor's native orientation — the free rotation a `SurfaceTexture` consumer got from the compositor is gone.

*Focus*
- Tap-to-focus's trigger request uses `CONTROL_AF_MODE_AUTO`, **not** `CONTINUOUS_PICTURE`. Triggering while already in `CONTINUOUS_PICTURE` does not re-scan toward newly-set `CONTROL_AF_REGIONS` on this hardware: `CONTROL_AF_STATE` jumps `PASSIVE_FOCUSED`→`FOCUSED_LOCKED` in one frame with `LENS_FOCUS_DISTANCE` unchanged, so a tap never actually refocuses. The *repeating* request stays in `AUTO` until the scan settles, then reverts, with a 2s fallback for a HAL that never reports settling.
- `FocusDial` is the only control that changes focus distance; the viewfinder's long-press only positions the ring and loupe. The dial reuses `DialWheel`'s barrel/well drawing primitives but not its click-ratchet gesture — focus distance has no discrete ladder.
- `FocusRing` scales to ~92% of the viewfinder's shorter dimension, loupe crop radius scaled to match. Focus-peaking is loupe-local by design; a full-viewfinder version is an explicit non-goal.
- `displayFractionToSensorFraction` treats the whole active array as visible, ignoring center-crop offset — deliberate, since `CONTROL_AF_REGIONS` is a coarse rectangle. Unverified on hardware with a significant preview-vs-array aspect mismatch.

*Teardown*
- `ImageReader.close()` is posted onto `backgroundHandler`, never called from the main-thread scope. All three readers' `OnImageAvailableListener`s run on that `Looper`; closing from another thread can invalidate a buffer mid-read (`IllegalStateException: buffer is inaccessible`, hit in production during a routine lens switch). Posting makes close and callback mutually exclusive, since a `Looper` runs one message at a time. Buffer-reading sites additionally catch that exception narrowly, for frames already in flight through the HAL when teardown starts.

*The permission gate*
- A denial has two states, not one: once Android stops showing the dialog, re-requesting returns denied instantly, so a single `Denied` state made Retry a no-op the app could never leave, even across relaunches (#63). `PermanentlyDenied` exists to send the user to `ACTION_APPLICATION_DETAILS_SETTINGS` instead — the only route back. Permanence is read from `shouldShowRequestPermissionRationale` *in the result callback*; before the first request it is `false` for a permission that has never been asked for, so it cannot be checked up front.
- The gate re-checks on `ON_RESUME` while ungranted, because granting in system settings delivers no callback and does not restart the app. It reports only grants — a resume must not re-classify a denial from a stale rationale reading.

*Exposure and overlays*
- Preview honors manual ISO/shutter only up to `PreviewMaxExposureTimeNs` (brightness compensated via ISO) so frame rate never drops; the actual capture uses the real uncapped values.
- Pinning auto→manual waits on a debounce that restarts whenever live ISO or shutter changes, bounded by a timeout. A fixed delay is not enough — a large EV swing converges far slower than a small nudge, and pinning early visibly jumps brightness.
- AE compensation's EV index **resets** to the lens's 0 EV stop on lens switch rather than being re-clamped, because the range differs per lens.
- Capabilities are gated per *lens*, not per device — front/back/tele differ. The lens identity and capability types live in `shared:diagnostics`, not here; see `.claude/docs/features/camera-diagnostics.md`.
- Zebra classification piggybacks on preview `ImageReader` frames that already flow for the preview — no `PixelCopy`, no dedicated stream — and is only enabled while a dial is being dragged. `ZebraMask` samples a bounded number of pixels per cell so cost is independent of frame resolution.
- Overlays that rotate with the device must measure their *rotated* footprint (width/height swapped at 90°/270°) and then draw upright content via `DrawScope.rotate`. A `Modifier`-level rotation changes only painting, not the size layout believes it has, so a corner-anchored badge clips off-screen in landscape.
- `HorizonLineOverlay` picks its drawing *axis* from the device quadrant instead of rotating a fixed layout, because `ViewfinderGridOverlay` applies zero orientation compensation — its dividers are frame-relative by design. Only the residual off-level angle rotates, on the center segment alone.
- `DialWheel`'s shutter panels seal with a flat `CameraChrome.DeckColor` fill rather than reconstructing what is behind them: the deck is one flat color everywhere.

*RAW*
- The persisted `captureRawByDefault` setting is ANDed with the live per-lens `rawCaptureSupported` at capture time. `feature:settings` cannot know which lens is active, so it expresses intent only — enabling it on a non-RAW lens silently captures JPEG-only, and switching to a capable lens picks it up with no extra tap.
- A lens advertising RAW is not trusted outright: the 3-surface session is verified with `CameraDevice.isSessionConfigurationSupported` (API 29+, so RAW is never attempted below Q) before the reader is included; any failure falls back to the 2-surface session rather than risking `onConfigureFailed` for the whole session.
- JPEG and RAW come from one still-capture request, and the RAW `Image` is correlated with its `TotalCaptureResult` by `SENSOR_TIMESTAMP` — **not** by callback arrival order. LUT grading never touches the RAW output. A DNG write failure is swallowed rather than losing the JPEG that already succeeded.

**Open risks:**
- Everything above is verified on one Pixel 9 Pro only. A second GPU vendor would meaningfully de-risk the YUV plane-stride handling, and rotation/color correctness generally.
- No leak check yet on `HandlerThread`/EGL context across many rapid session reopens.
- `FocusDial`'s drag sensitivity, the simultaneous screen+dial hold release ordering, and the tap indicator's timing constants are reasoned defaults, never device-tuned by hand.
- Manual white balance scope is undefined.
