---
name: feedback-feature-module-isolation
description: "Established pattern for keeping feature modules decoupled — cross-feature nav goes through shared:navigation route constants, cross-feature domain contracts go in shared:common"
metadata: 
  node_type: memory
  type: feedback
  originSessionId: 462c417f-7634-4356-b594-ac8aa59aab45
  modified: 2026-08-08T11:44:44.482Z
---

CLAUDE.md already states "feature modules never depend on each other directly," but during the camera settings-screen feature (2026-08-08) this was tested for the first time with a real second feature module (`feature:settings`) that needs to be opened from `feature:camera`'s toolbar, and the user picked the concrete pattern to use going forward:

- **Cross-feature domain contracts** (e.g. a repository interface + model both features need to read/write) live in `shared:common`'s `domain/model`/`domain/repository` packages — e.g. `CameraSettings`/`CameraSettingsRepository`, implemented by whichever feature module owns the actual persistence (here, `feature:settings`'s DataStore-backed impl), consumed directly by the other feature (here, `feature:camera`'s `CameraViewModel`) since both already depend on `shared:common`.
- **Cross-feature navigation** goes through a dedicated new module, `shared:navigation`, holding plain route-string constants (e.g. `SettingsRoutes.SETTINGS_SCREEN`). The target feature's `@Destination` composable sets `route = SettingsRoutes.SETTINGS_SCREEN`; the calling feature navigates via `navigator.navigate(route = SettingsRoutes.SETTINGS_SCREEN)` (or `Direction(...)` depending on the compose-destinations navigator API in use) — never by importing the other feature's generated `<Screen>Destination` object, which would require a direct Gradle dependency between the two feature modules.
- Also established in the same effort: `MainDispatcherRule` for ViewModel tests gets extracted to a `shared:testing` module as soon as a *second* feature module needs it — this was already anticipated in a code comment in `feature:camera`'s original copy of the rule, and the settings feature was that second occurrence.

**Why:** The user explicitly rejected folding Settings into `feature:camera` (wanted a real separate Gradle module) and explicitly rejected letting `feature:camera` depend on `feature:settings` just for type-safe navigation, asking instead for a separate, shared navigation module. This is now the established precedent, not a one-off.

**How to apply:** When adding a new feature module that needs to interoperate with an existing one (opened from it, sharing state with it, etc.), default to this same split — shared:common for data/domain contracts, shared:navigation for route constants — rather than adding a direct project(...) dependency between the two feature modules or re-deriving a different pattern from scratch. See also [[feedback-shared-ui-reuse]] for the parallel rule about shared UI/design-system code.
