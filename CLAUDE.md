# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## Project overview

xCamera is a multi-module native Android app (Kotlin + Jetpack Compose) built on Clean Architecture: `feature/` and `shared/` Gradle modules, MVVM screens, Kotlin Coroutines/Flow, Hilt DI, and compose-destinations navigation. Product direction: an Android take on iOS's (Not Boring) Camera app — see project memory `project-vision` / `camera-feasibility-android` for the target feature set and Android API mapping. `feature:camera` is the actual product feature. Each feature module follows the package-per-layer Clean Architecture split described below (`data`/`domain`/`presentation`/`ui`/`di`), typically backed by Retrofit and/or Room in `data/` and an MVVM `presentation/` layer — when in doubt about a convention, follow that layering rather than inventing something new.

## Module map

| Module | Purpose | Depends on |
|---|---|---|
| `:app` | Application/Activity shell, Hilt entry point, root nav graph aggregation | all `shared:*` and `feature:*` modules |
| `:shared:common` | Cross-cutting infra: dispatchers, `Result`/`DataError`, base `UseCase`, shared `OkHttpClient`/`Json` | — |
| `:shared:designsystem` | Compose theme (`XCameraTheme`) + reusable UI (`LoadingIndicator`, `ErrorState`) | — |
| `:feature:camera` | The actual product feature — CameraX-based capture, start destination of the app | `shared:common`, `shared:designsystem` |

Feature modules never depend on each other directly — cross-feature communication goes through `shared:common` domain contracts (none needed yet). Camera hardware access (`Camera2`/`CameraX`) conventions specific to `feature:camera` are documented in `.claude/agents/camera-engineer.md`, not duplicated here.

## Feature docs & issue writing

`docs/features/` holds one short file per feature (`docs/features/README.md` is the one-line-per-feature index, mirroring the pattern of this repo's memory `MEMORY.md`) — deliberately kept lean (~40 lines/feature) so it's cheap to load into context, not a full spec dump. GitHub issues follow a Problem/Requirements/Non-goals/Technical-notes template, flat (no epics). Both are maintained by the `spec-writer` agent (`.claude/agents/spec-writer.md`) — use it instead of hand-writing issues or feature docs.

## Build system: build-logic convention plugins

`build-logic/` is an included build (`pluginManagement.includeBuild("build-logic")` in root `settings.gradle.kts`). Its `convention` subproject defines Kotlin `Plugin<Project>` classes registered as precompiled Gradle plugins:

| Plugin ID | What it configures |
|---|---|
| `xcamera.android.application` | `com.android.application` + Kotlin, compileSdk/minSdk/targetSdk, JVM 11, base test deps |
| `xcamera.android.application.compose` | `xcamera.android.application` + Compose compiler + Compose BOM/UI/Material3 deps |
| `xcamera.android.library` | `com.android.library` + Kotlin, same SDK/JVM/test config as application |
| `xcamera.android.library.compose` | `xcamera.android.library` + Compose compiler + Compose/lifecycle-compose deps |
| `xcamera.android.hilt` | KSP + Hilt Gradle plugin + `hilt-android`/`hilt-compiler` |
| `xcamera.android.room` | KSP + `room-runtime`/`room-ktx`/`room-compiler` |
| `xcamera.compose.destinations` | KSP + compose-destinations `core`/`ksp`, sets KSP args `mode = "destinations"` and `moduleName = <gradle module name>` |
| `xcamera.kotlin.serialization` | Kotlin serialization plugin + kotlinx-serialization-json + Retrofit/OkHttp |
| `xcamera.feature` | Bundle: `android.library` + `android.library.compose` + `android.hilt` + `compose.destinations` + lifecycle/coroutines/hilt-navigation-compose deps — apply this to any new feature module |

Versions live in `gradle/libs.versions.toml`; convention plugins read them via a `Project.libs` accessor (`build-logic/convention/src/main/kotlin/com/dragote/xcamera/buildlogic/Libs.kt`), not hardcoded strings. To add a new convention plugin: add a `Plugin<Project>` class under `build-logic/convention/src/main/kotlin/com/dragote/xcamera/buildlogic/`, register it in `build-logic/convention/build.gradle.kts`'s `gradlePlugin { plugins { ... } }` block, then apply its ID from a module's `build.gradle.kts`. Gradle plugin artifacts (AGP, Kotlin, KSP, Hilt, Compose compiler) are declared `implementation` (not `compileOnly`) in `build-logic/convention/build.gradle.kts` — imperative `pluginManager.apply(...)` calls from within a convention plugin need those classes on the plugin's own runtime classpath, `compileOnly` is not sufficient there.

## Package-per-layer convention

Inside every feature module, Clean Architecture layers are **packages**, not sub-modules:

```
feature/<name>/src/main/java/.../feature/<name>/
  data/
    remote/          Retrofit API interface + DTOs (data/remote/dto/)
    local/            Room Entity + Dao + <Feature>Database (each feature owns its own Room database)
    mapper/           Dto.toEntity(), Entity.toDomain() extension functions
    repository/       <Feature>RepositoryImpl
  domain/
    model/            Plain domain data classes (no Android/Room/Retrofit types)
    repository/       Repository interface consumed by domain/presentation
    usecase/          One class per use case, `operator fun invoke(...)`
  presentation/
    <screen>/          <Screen>ViewModel (@HiltViewModel) + <Screen>UiState (StateFlow-based)
  ui/
    <screen>/          @Destination-annotated @Composable screen + screen-local components
  di/                  Hilt @Module classes (@Provides for API/DB, @Binds for repository)
```

`data/` talks to the network/DB and maps into domain models; `domain/` holds plain Kotlin models, the repository interface, and use cases with no Android/Room/Retrofit types; `presentation/` holds the `@HiltViewModel` + `StateFlow`-based UI state; `ui/` holds the `@Destination`-annotated composables that render that state; `di/` wires it all together with Hilt.

## Naming conventions

`<Screen>Screen` (composable) · `<Screen>ViewModel` · `<Screen>UiState` · `<Feature>Repository` / `<Feature>RepositoryImpl` · `<Thing>Dto` (remote) · `<Thing>Entity` (Room) · `<Feature>Api` (Retrofit) · `Get<Thing>UseCase` (use cases) · `toDomain()` / `toEntity()` (mapper extension functions, in `data/mapper/`).

## Compose preview convention

Every reusable Compose UI element — `feature/*/ui/component/*` composables and `shared:designsystem`'s components alike — gets a `@Preview` composable in the same file, wrapped in `XCameraTheme { ... }`. When a component has meaningful states (checked/unchecked, on/off, enabled/disabled), preview more than one side by side (e.g. in a `Row`) rather than just the default state. This applies going forward: adding a new reusable composable means adding its preview in the same change, and editing a composable's public parameters means checking its preview still compiles and still represents the component honestly — a stale preview is a bug the same way a stale test is.

## Duplication vs. abstraction

Don't extract a shared abstraction the first time you write something — write it inline/local to whatever needs it. The **second** time the exact same logic is needed elsewhere, extract it into a shared function/composable/module rather than copying it again (tightened 2026-08-06 from an earlier "duplicate until a third occurrence" rule — two identical copies is already enough proof the pattern is real, and the third-copy version was letting real duplication sit for too long, e.g. `feature/camera/ui/component/FocusDial.kt` byte-for-byte copying `DialWheel.kt`'s value/label `Text` styling before issue #25 extracted `DialText.kt`). This applies at whatever scope the two occurrences share — same-package `internal` (e.g. `DialText.kt`, or `DialWheel`'s own `drawBarrel`/`drawWell`) if both live in one feature module, `shared:common`/`shared:designsystem` if they cross module boundaries (see the `MainDispatcherRule`/haptics-vibrator examples elsewhere in this file). One occurrence: leave it alone. Two occurrences of the *same* thing: extract. Two *similar-but-not-identical* things (e.g. `DialWheel`'s click-detent gesture vs. `FocusDial`'s continuous-drag gesture): duplication is legitimate — don't force a shared abstraction over a genuinely different interaction model just because the surrounding chrome looks similar.

## Comment conventions

Comments describe the component's current state and behavior only — never its history. No "used to be X", "previously did Y", "replaced Z", "ported from `legacy/...`", "as of <date>", or narrated bug-investigation timelines ("on-device testing found... then we tried... turned out to be..."). That kind of material belongs in commit messages and, for anything worth a permanent record, `docs/features/<feature>.md` — never in a comment sitting next to the code, since a comment about the past goes stale the moment the code changes again and nobody's obligated to touch it.

What a comment *should* say: a short, present-tense "why" for a genuinely non-obvious constraint (a HAL quirk this code works around, an invariant a type alone can't express, a reason the obvious simpler approach doesn't work) — 1-3 lines is normal, longer only if the constraint itself is genuinely that dense to state. If a component is complex enough that its full rationale doesn't fit in a few lines, keep the short version inline and point to `docs/features/<feature>.md` for the rest (e.g. `// see docs/features/camera-capture.md`) rather than writing the long version in the comment itself.

Applies to every comment, long or short, in every module — not just KDoc class docs. When touching a comment for an unrelated reason, fix it to match this if it doesn't already.

## DI rules

- Hilt modules live in each module's own `di/` package, `@InstallIn(SingletonComponent::class)` unless there's a specific reason for a narrower scope.
- `@Provides` for things you construct (Retrofit, Room database/DAO); `@Binds` (in an `abstract class`) for interface→impl bindings (repositories).
- Only modules that actually declare Hilt bindings/injection apply `xcamera.android.hilt` — currently `:app`, `:shared:common`, and every `feature:*` module (via `xcamera.feature`).
- Qualifiers (e.g. `@IoDispatcher`) live in `shared:common`'s `qualifiers/` package; use them instead of ad hoc `Dispatchers.IO` references outside of the `DispatcherModule` that provides them.
- `shared:common`'s `NetworkModule` only provides the base `OkHttpClient`/`Json` — each feature builds its own `Retrofit` instance in its own `di/` module because base URLs differ per feature.

## Navigation rules (compose-destinations)

- Annotate the top-level screen composable with `@Destination` (from `com.ramcosta.composedestinations.annotation.Destination`); pass screen args as plain composable parameters (e.g. `postId: Int`) — compose-destinations generates the route/arg plumbing.
- Because `xcamera.compose.destinations` sets `mode = "destinations"`, each feature module does **not** generate its own `NavGraphs` object — instead it generates a flat `<moduleName>Destinations: List<...>` (e.g. `cameraDestinations` in package `<feature package>.ui`) and per-destination `<Screen>Destination` objects under `.ui.destinations`.
- `:app`'s `navigation/AppNavGraph.kt` hand-assembles the single root `NavGraphSpec` from every feature's `<moduleName>Destinations` list. **When you add a new feature module, add its destinations list to `destinationsByRoute` in `AppNavGraph.kt`.**
- Navigate with `navigator.navigate(SomeScreenDestination(arg = value))` — the generated `<Screen>Destination` objects are callable with the same parameters as the composable.
- To read nav args in a ViewModel, prefer `savedStateHandle["argName"]` over the generated `NavArgs` data class — it avoids the presentation layer importing KSP-generated `ui`-layer types. See `PostDetailViewModel`.
- The generated destination package is derived from the **common package prefix** of all `@Destination` composables in the module (here, `.ui`) — keep every `@Destination` composable under that module's `ui/` package so the generated package stays predictable.

## Data layer conventions

- Repositories are offline-first: reads come from Room (`Flow`-returning DAO queries mapped to domain models), a network refresh is triggered on first collection (`.onStart { refresh() }`), and refresh failures are swallowed into a logged `Result.Error` rather than crashing the read flow — cached data still gets shown.
- Every repository/use-case boundary returns `com.dragote.xcamera.shared.common.domain.result.Result<D, DataError>` instead of throwing — this keeps ViewModels free of try/catch. `DataError` has `Network` and `Local` sub-enums; extend them rather than adding new ad hoc error types.
- **Each feature module owns its own Room database** (named `<Feature>Database`) rather than a single shared entities registry — this keeps feature modules self-contained (`shared:common` never depends on feature-owned entities). `shared:common` only owns the `xcamera.android.room` convention plugin for future genuinely cross-cutting tables; it does not currently have its own database (an empty `@Database` isn't valid Room — add one only once there's a real cross-cutting table to put in it).

## Testing conventions

- MockK + Turbine + `kotlinx-coroutines-test`. No Mockito.
- Test files mirror the production package structure 1:1 under `src/test/java/...`.
- What gets unit tested per layer: repository (mock API/DAO, assert `Result` mapping and error translation), use case (mock repository, assert delegation), ViewModel (mock use case, assert `uiState` sequence via Turbine's `.test { awaitItem() }`).
- ViewModel tests need a `MainDispatcherRule` (`Dispatchers.setMain`/`resetMain` around each test) — duplicate this small rule into a new feature module's test sources until a second feature needs it, at which point extract it into a `shared:testing` module.
- `SavedStateHandle(mapOf("argName" to value))` works directly in JVM unit tests for ViewModels that read nav args — no Robolectric needed for this.

## Build commands

```bash
./gradlew build                        # full build: compile + lint + unit tests, all modules
./gradlew assembleDebug                 # debug APK
./gradlew test                          # all unit tests
./gradlew :feature:camera:test          # unit tests for one module
./gradlew :feature:camera:testDebugUnitTest --tests "*.SomeViewModelTest"  # a single test class
./gradlew lint                          # Android Lint, all modules
./gradlew connectedAndroidTest          # instrumented tests, requires a connected device/emulator
./gradlew :feature:camera:dependencies  # inspect resolved dependency versions for one module
```

Changes to anything under `build-logic/` require a fresh Gradle sync (Android Studio: File → Sync Project with Gradle Files) — precompiled plugin classes are cached per `build-logic` build.

## Adding a new feature module

1. Add the module directory `feature/<name>/` and register it in root `settings.gradle.kts` (`include(":feature:<name>")`).
2. `feature/<name>/build.gradle.kts`: apply `xcamera.feature`, plus `xcamera.android.room` and/or `xcamera.kotlin.serialization` if the feature needs local persistence or a network DTO layer. Set `android.namespace`. Depend on `project(":shared:common")` and `project(":shared:designsystem")`.
3. Build out `data/domain/presentation/ui/di` packages as described in "Package-per-layer convention" above.
4. Add an `<INTERNET/>`-style manifest permission in `feature/<name>/src/main/AndroidManifest.xml` only if the feature needs it (library modules don't need a manifest at all if they don't).
5. Add `implementation(project(":feature:<name>"))` to `app/build.gradle.kts`.
6. Add the feature's generated `<name>Destinations` list into `destinationsByRoute` in `app/src/main/java/com/dragote/xcamera/navigation/AppNavGraph.kt`.
7. Write unit tests mirroring the production package structure.

## Known gotchas

- **KSP version must lock-step with the Kotlin version** (`ksp = "<kotlin-version>-<ksp-patch>"` in `gradle/libs.versions.toml`) — bumping Kotlin without bumping KSP (or vice versa) breaks annotation processing across every module.
- compose-destinations' generated destination package is inferred from the common prefix of `@Destination` composables in a module — don't scatter `@Destination` composables across unrelated packages within a feature, or the generated package/import paths become unpredictable.
- Room requires at least one entity in a `@Database` — don't create a placeholder `AppDatabase` with an empty entities list "for later"; add it when there's an actual table to put in it.
- Gradle plugin artifacts in `build-logic/convention/build.gradle.kts` are `implementation`, not `compileOnly` — switching to `compileOnly` breaks imperative `pluginManager.apply(...)` calls inside convention plugins with a "could not generate a decorated class" error.
- Room's schema export directory isn't configured (no `androidx.room` Gradle plugin applied) — you'll see a benign KSP warning about schema export on every Room-using module. Fine to ignore; wire up schema export only if you actually need migration testing.
