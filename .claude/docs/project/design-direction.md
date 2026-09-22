# Design direction

Read before changing how anything looks — the shipped visual identity and the directions already ruled out.

The app's visual identity is **minimal chrome**, shipped in #49: flat OP-1-Field-style line art — thin strokes and flat black-or-white fills on a warm-white body, with a `Palette.Normal`/`Palette.Inverted` switch and deliberately no gradients, shadows, blur, or grain. `shared:designsystem`'s `MinimalChrome` is the source of truth. Treat any reference material showing dials with gradients, wells, or texture as obsolete.

The one sanctioned exception to the monochrome rule is `MinimalChrome.accent` — a hue the *user* picks, fixed under either palette, currently carried by the shutter release alone. Color is the user's to spend, not the design's: don't introduce a hue anywhere on your own initiative, and don't fold the accent into `Palette`.

**Ruled out — don't re-propose unless the user raises it:**
- The original dark "matte black plastic" skeuomorphic body: judged "cheap" against the user's own references.
- Fixing that body by **palette alone**: rejected pre-emptively, before any code was written.
- Fixing it by **rendering technique alone** — real cached soft-blur contact shadows, rim and specular highlights replacing hand-banded gradients. Built and shipped as a pilot, tested on device, verdict was "barely any visible difference."
- A sage-green neo-skeuomorphic direction authored outside the repo: never shipped, its components lost in a machine migration.

**Why:** the two isolated fixes were tested independently and neither closed the gap — which is the durable lesson. A "feels cheap" verdict is not reducible to a single axis; closing a real design gap can need several changed together, or a different technique entirely (pre-rendered image assets instead of procedural Canvas drawing was discussed but never attempted, since it needs an asset pipeline outside the repo).

**How to apply:** when a look is rejected, don't answer with another single-axis variation of the same body — propose a coherent whole, or say plainly that the gap needs a different technique. Any design verdict comes from the user on-device (`CLAUDE.md`, "Verifying a change"); nothing here can be settled by reasoning about the code.
