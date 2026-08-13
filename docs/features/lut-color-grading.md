# LUT color grading

**Purpose:** Real-time 3D LUT-based color grading (importable custom `.cube` LUTs) applied to both the live preview and captured photos — one of (Not Boring) Camera's not-yet-ported features (see `camera-feasibility-android`).

**Current state:** Not implemented (issue #43). No color grading exists anywhere in the pipeline today — see `docs/features/camera-capture.md` for the current Camera2/GLES capture architecture this builds on.

**Planned scope:**
- Preview: `.cube` file (ASCII, `LUT_3D_SIZE N` + N³ RGB rows) parsed into a `GL_TEXTURE_3D`, sampled directly in the existing YUV→RGB fragment shader in `CameraPreviewRenderer` — single pass, no extra FBO for preview.
- Capture: after the existing JPEG capture, decode → run through the same LUT shader offscreen (FBO) → re-encode → write to MediaStore, replacing the current direct-JPEG-to-MediaStore write only when a LUT is active.
- Import via Storage Access Framework, copied into app-private storage; user-selectable list, "off" bypasses grading entirely.
- Blend intensity 0–100% between graded and original color.
- Selected LUT + intensity persisted via `CameraSettings`/`CameraSettingsRepository` (`shared:common`), same pattern as `focusPeakingSensitivity`; UI lives in `feature:settings`.

**Key decisions:**
- Renderer moves GLES 2.0 → GLES 3.0 for native `GL_TEXTURE_3D` support (GLES 2.0 has no 3D texture type) — costs nothing, GLES 3.0 needs only API 18+, well under minSdk 26.
- LUT sampled in the same fragment shader that already does YUV→RGB, not a second render pass — avoids extra FBO overhead per preview frame.
- Capture needs its own offscreen FBO pass (decode → shader → re-encode) since stills go through a separate JPEG `ImageReader`/`capture()` path with no GL involvement today — this is new complexity, not a preview-path reuse.
- `.cube` chosen as the only supported format — standard/portable LUT interchange format, avoids inventing a proprietary one.

**Open questions:**
- Whether to ship curated built-in presets (deferred out of issue #43 — import-only is the shippable first slice).
- GPU-vendor compatibility beyond the one Pixel 9 Pro device this project has validated GLES rendering on — same residual risk already flagged in `camera-capture.md`, not new to this feature.
