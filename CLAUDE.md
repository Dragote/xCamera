# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## Project overview

xCamera is a multi-module native Android app (Kotlin + Jetpack Compose) built on Clean Architecture: `feature/` and `shared/` Gradle modules, MVVM screens, Kotlin Coroutines/Flow, Hilt DI, and compose-destinations navigation. Product direction: a camera app pairing a genuinely pro capture pipeline with a playful, tactile, highly customizable interface — see project memory `project-vision` / `camera-feasibility-android` for the target feature set and Android API mapping. `feature:camera` is the flagship product feature. Each feature module follows the package-per-layer Clean Architecture split described below (`data`/`domain`/`presentation`/`ui`/`di`) with an MVVM `presentation/` layer — when in doubt about a convention, follow that layering rather than inventing something new. The app is fully local: there is no network layer, and persistence is DataStore, not Room (see "Data layer conventions").

## Module map

| Module | Purpose | Depends on |
|---|---|---|
| `:app` | Application/Activity shell, Hilt entry point, root nav graph aggregation (`navigation/AppNavGraph.kt`) | `:shared:designsystem`, every `feature:*` |
| `:shared:common` | Dispatchers + `@IoDispatcher` qualifiers, `Result`/`DataError`, and the cross-feature domain contracts: `CameraSettings`/`CameraSettingsRepository`, `LutRepository`, `CubeLut*` parsing/resampling | — |
| `:shared:designsystem` | `XCameraTheme`, the `MinimalChrome` palette, and reusable Compose UI (`Toggle`, `SteppedToggle`, `LoadingIndicator`, `ErrorState`, `HapticTick`) | — |
| `:shared:navigation` | Plain route-string constants only (`SettingsRoutes`, `DiagnosticsRoutes`) — lets one feature navigate to another without depending on it | — |
| `:shared:diagnostics` | Camera-capability determination: lens enumeration and RAW/manual-ISO/manual-focus/AE-compensation checks. The app's sole source of this logic | `:shared:common` |
| `:shared:testing` | `MainDispatcherRule` for ViewModel tests; consumed via `testImplementation` | — |
| `:feature:camera` | The flagship product feature — Camera2-backed capture, start destination of the app | `:shared:common`, `:shared:designsystem`, `:shared:navigation`, `:shared:diagnostics` |
| `:feature:settings` | Persisted camera preferences and the imported-LUT library (DataStore-backed) | `:shared:common`, `:shared:designsystem`, `:shared:navigation` |
| `:feature:diagnostics` | Per-lens hardware diagnostics screen, reached from Settings | `:shared:common`, `:shared:designsystem`, `:shared:navigation`, `:shared:diagnostics` |

Every `feature:*` module additionally takes `:shared:testing` via `testImplementation`.

Feature modules never depend on each other directly. Cross-feature *domain* contracts live in `shared:common` (e.g. `CameraSettingsRepository` — implemented by `feature:settings`, consumed by `feature:camera`); cross-feature *navigation* goes through `shared:navigation`'s route-string constants, never by importing another feature's generated `<Screen>Destination`. Camera hardware access (`Camera2`) conventions specific to `feature:camera` are documented in `.claude/agents/camera-engineer.md`, not duplicated here.

## Feature docs & issue writing

`.claude/docs/features/` holds one short file per feature (`.claude/docs/features/README.md` is the one-line-per-feature index, mirroring the pattern of this repo's memory `MEMORY.md`) — kept lean so it's cheap to load into context, not a full spec dump. A doc holds only **what reading the code would not have told you** — why this approach and not the obvious alternative, what breaks if you change it, what was tried and failed — plus a one-line-per-capability inventory of what the feature contains. Description that duplicates code is both the bulk and the rot: it is what silently goes stale while rationale stays true. The validator warns past ~1500 words (`wc -w`) — not a budget, a smell threshold: genuine rationale for even the flagship capture pipeline fits in ~1200, so more than that usually means description crept back in. A line count is no use at all, since 600-character bullets satisfy it trivially. GitHub issues follow a Problem/Requirements/Non-goals/Technical-notes template, flat (no epics). Both are maintained by the `spec-writer` agent (`.claude/agents/spec-writer.md`) — use it instead of hand-writing issues or feature docs.

## Issue type labels

Every issue carries exactly one, and the branch prefix follows it (`<label>/<N>-<slug>`); commits are `#<N>: Message`.

| Label | Scope |
|---|---|
| `feature` | New or changed user-visible behavior |
| `bug` | Something in the app behaves wrong |
| `tech` | Changes to code that runs — refactors, architecture, dependency and build migrations, Gradle/`build-logic`. No user-visible behavior change, but the app is built or executed differently afterwards |
| `documentation` | Changes to text that instructs a reader — `CLAUDE.md`, `.claude/` (memory, agents, commands, skills, docs), READMEs, code comments. Nothing the app compiles or runs changes |

The line between the last two is **what the change acts on, not whether users can see it**: `tech` acts on the program, `documentation` acts on the instructions given to whoever works on the program. Reworking agent definitions, memory, or this file is `documentation` however infra-flavored it looks — that pull toward `tech` is the trap, and `tech/57-claude-context-in-repo` on `main` is an instance of falling into it. Don't cite it as precedent.

## When a change becomes a pull request

A request made in conversation — "fix this", "rename that", "try it the other way" — is a request for **the change itself**. Make it in the working tree and stop there. Don't file an issue, don't branch, don't commit unasked: the user reads the result, iterates on it, and often the next instruction changes it again.

The lifecycle starts on a separate, explicit signal — `/task` up front, `/ship` once work is already sitting in the tree, or the user simply saying to file it and push it for review. `/ship` handles the retroactive case: it files the issue when there isn't one and moves work off `main` onto a properly named branch, so nothing has to be planned as a task in advance.

Where the flow ends is decided by the issue's type label, not asked each time:

| Label | Ends at |
|---|---|
| `documentation` | **Merged.** Nothing here can be checked on a device, so green tests plus the context validator are the whole verification that exists — carrying it to `main` adds nothing but a round trip |
| `tech`, `feature`, `bug` | **The open PR**, board on In review, debug build installed, then report and stop. These change code that runs, and the user verifies on-device before merging |

## Commit conventions

Subject is `#<N>: Message` — leading `#`, colon, capitalized imperative. The body explains **why**, never what: the diff already shows what. Trailers may carry `Closes #<N>`.

**One coherent step per commit, not one commit per task.** A large piece of work arriving as a single commit is the failure mode to avoid — it cannot be reviewed, reverted, or bisected in pieces. Two tests for whether a split is right:

- **Atomic.** The commit does one thing. If its subject needs an "and", it is two commits.
- **Green.** Any commit can be checked out on its own and `./gradlew test` passes. This is a constraint on *ordering*, not a demand to run the suite N times: introduce a function before its call site, land a new component before the screen that uses it, delete a symbol only once nothing references it. When a split's ordering isn't obviously safe, check out the intermediate commit and build it rather than assuming.

Separate mechanical change from meaningful change even when they are part of one task: a rename or a file move goes in its own commit, so git can still see it as a rename and the commit that changes behavior stays small enough to read. Issue #59 moved `docs/` under `.claude/` in one commit and rewrote a doc's content in the next for exactly this reason — bundled, the move would have shown up as a delete plus an add.

If a change genuinely is one step, one commit is right. The rule is against *dumping*, not against small changes.

## Commands and skills

`.claude/commands/` holds the repo's repeatable procedures as commands rather than as prose someone has to recall and interpret: **`/take-issue <N>`** (assign the issue, move the board to In progress, cut the correctly-prefixed branch) and **`/ship`** (test, rebase, push, open the PR, move the board, and — only when asked — merge with this project's `Merge <branch>` subject). The board/label IDs they depend on live in project memory `reference-github-project`. When a workflow here becomes routine, add a command instead of writing it down in memory.

`.claude/skills/` holds the same idea for procedures *I* trigger rather than the user: `write-memory` and `add-feature-module`, both deep but rarely needed, so they load on demand instead of taxing this file every session. The test for whether something may move out of `CLAUDE.md` into a skill is **what a failure to load costs**: a missed formatting rule is visible and cheap to fix, so it can move; a missed prohibition fails silently and must stay here or in the memory index.

## Project memory

Claude Code's persistent memory for this project is **committed to the repo** at `.claude/memory/` — `MEMORY.md` is the always-loaded index, one file per memory beside it. It lives in the repo because `~/.claude/` does not survive moving between the user's machines; a `SessionStart` hook keeps the machine-local symlink pointing here. **Load the `write-memory` skill before saving, editing, or deleting a memory** — it carries the format, the index rules, and the test for what earns a memory at all.

## Build system: build-logic convention plugins

`build-logic/` is an included build whose `convention` subproject defines this project's Gradle plugins (`xcamera.android.library`, `xcamera.feature`, `xcamera.android.hilt`, …); every module's `build.gradle.kts` is a few plugin IDs and dependencies, with no per-module Android config. Versions come from `gradle/libs.versions.toml` through a `Project.libs` accessor, never hardcoded. **Load the `add-feature-module` skill** before adding a module or touching anything under `build-logic/` — it has the full plugin table and the registration steps.

## Package-per-layer convention

Inside every feature module, Clean Architecture layers are **packages**, not sub-modules:

```
feature/<name>/src/main/java/.../feature/<name>/
  data/
    local/            DataStore-backed local data sources (<Feature>LocalDataSource)
    mapper/           toDomain() extension functions
    repository/       <Feature>RepositoryImpl
  domain/
    model/            Plain domain data classes (no Android/Room/Retrofit types)
    repository/       Repository interface consumed by domain/presentation
    usecase/          One class per use case, `operator fun invoke(...)`
  presentation/
    <screen>/          <Screen>ViewModel (@HiltViewModel) + <Screen>UiState (StateFlow-based)
  ui/
    <screen>/          @Destination-annotated @Composable screen + screen-local components
  di/                  Hilt @Module classes (@Provides for constructed deps, @Binds for repository)
```

`data/` talks to the device (hardware, DataStore, files) and maps into domain models; `domain/` holds plain Kotlin models, the repository interface, and use cases with no Android/Room/Retrofit types; `presentation/` holds the `@HiltViewModel` + `StateFlow`-based UI state; `ui/` holds the `@Destination`-annotated composables that render that state; `di/` wires it all together with Hilt.

## Naming conventions

`<Screen>Screen` (composable) · `<Screen>ViewModel` · `<Screen>UiState` · `<Feature>Repository` / `<Feature>RepositoryImpl` · `<Feature>LocalDataSource` (DataStore/file-backed) · `Get<Thing>UseCase` (use cases) · `toDomain()` (mapper extension functions, in `data/mapper/`). If a network or Room layer is ever added, the corresponding names are `<Thing>Dto`, `<Thing>Entity`, `<Feature>Api`, and `toEntity()`.

## Compose preview convention

Every reusable Compose UI element — `feature/*/ui/component/*` composables and `shared:designsystem`'s components alike — gets a `@Preview` composable in the same file, wrapped in `XCameraTheme { ... }`. When a component has meaningful states (checked/unchecked, on/off, enabled/disabled), preview more than one side by side (e.g. in a `Row`) rather than just the default state. This applies going forward: adding a new reusable composable means adding its preview in the same change, and editing a composable's public parameters means checking its preview still compiles and still represents the component honestly — a stale preview is a bug the same way a stale test is.

## Duplication vs. abstraction

Don't extract a shared abstraction the first time you write something — write it inline/local to whatever needs it. The **second** time the exact same logic is needed elsewhere, extract it into a shared function/composable/module rather than copying it again (tightened 2026-08-06 from an earlier "duplicate until a third occurrence" rule — two identical copies is already enough proof the pattern is real, and the third-copy version was letting real duplication sit for too long, e.g. `feature/camera/ui/component/dial/FocusDial.kt` byte-for-byte copying `DialWheel.kt`'s value/label `Text` styling before issue #25 extracted `DialText.kt`). This applies at whatever scope the two occurrences share — same-package `internal` (e.g. `DialText.kt`, or `DialWheel`'s own `drawBarrel`/`drawWell`) if both live in one feature module, `shared:common`/`shared:designsystem` if they cross module boundaries (see the `MainDispatcherRule`/haptics-vibrator examples elsewhere in this file). One occurrence: leave it alone. Two occurrences of the *same* thing: extract. Two *similar-but-not-identical* things (e.g. `DialWheel`'s click-detent gesture vs. `FocusDial`'s continuous-drag gesture): duplication is legitimate — don't force a shared abstraction over a genuinely different interaction model just because the surrounding chrome looks similar.

**UI is stricter: extract on the first occurrence.** A Compose component, a color/typography token, or a `Modifier` that a second module needs moves into `shared:designsystem` the first time reuse comes up — don't wait for the rule above's second occurrence, and never copy it or reach into another feature's internal `ui/` package for it. Visual consistency across screens outweighs the risk of a premature abstraction here, and this is a deliberate exception the user asked for, not an oversight. Watch for `internal` helpers and constants that have to become public in the move.

## Comment conventions

Comments describe the component's current state and behavior only — never its history. No "used to be X", "previously did Y", "replaced Z", "ported from `legacy/...`", "as of <date>", or narrated bug-investigation timelines ("on-device testing found... then we tried... turned out to be..."). That kind of material belongs in commit messages and, for anything worth a permanent record, `.claude/docs/features/<feature>.md` — never in a comment sitting next to the code, since a comment about the past goes stale the moment the code changes again and nobody's obligated to touch it.

What a comment *should* say: a short, present-tense "why" for a genuinely non-obvious constraint (a HAL quirk this code works around, an invariant a type alone can't express, a reason the obvious simpler approach doesn't work) — 1-3 lines is normal, longer only if the constraint itself is genuinely that dense to state. If a component is complex enough that its full rationale doesn't fit in a few lines, keep the short version inline and point to `.claude/docs/features/<feature>.md` for the rest (e.g. `// see .claude/docs/features/camera-capture.md`) rather than writing the long version in the comment itself.

Applies to every comment, long or short, in every module — not just KDoc class docs. When touching a comment for an unrelated reason, fix it to match this if it doesn't already.

## DI rules

- Hilt modules live in each module's own `di/` package, `@InstallIn(SingletonComponent::class)` unless there's a specific reason for a narrower scope.
- `@Provides` for things you construct (Retrofit, Room database/DAO); `@Binds` (in an `abstract class`) for interface→impl bindings (repositories).
- Only modules that actually declare Hilt bindings/injection apply `xcamera.android.hilt` — currently `:app`, `:shared:common`, `:shared:diagnostics`, and every `feature:*` module (via `xcamera.feature`).
- Qualifiers (e.g. `@IoDispatcher`) live in `shared:common`'s `qualifiers/` package; use them instead of ad hoc `Dispatchers.IO` references outside of the `DispatcherModule` that provides them.
- `shared:common`'s `NetworkModule` only provides the base `OkHttpClient`/`Json` and is currently unused. Should a feature ever need network access, it builds its own `Retrofit` instance in its own `di/` module, since base URLs would differ per feature.

## Navigation rules (compose-destinations)

- Annotate the top-level screen composable with `@Destination` (from `com.ramcosta.composedestinations.annotation.Destination`); pass screen args as plain composable parameters (e.g. `postId: Int`) — compose-destinations generates the route/arg plumbing.
- Because `xcamera.compose.destinations` sets `mode = "destinations"`, each feature module does **not** generate its own `NavGraphs` object — instead it generates a flat `<moduleName>Destinations: List<...>` (e.g. `cameraDestinations` in package `<feature package>.ui`) and per-destination `<Screen>Destination` objects under `.ui.destinations`.
- `:app`'s `navigation/AppNavGraph.kt` hand-assembles the single root `NavGraphSpec` from every feature's `<moduleName>Destinations` list. **When you add a new feature module, add its destinations list to `destinationsByRoute` in `AppNavGraph.kt`.**
- Navigate with `navigator.navigate(SomeScreenDestination(arg = value))` — the generated `<Screen>Destination` objects are callable with the same parameters as the composable.
- To read nav args in a ViewModel, prefer `savedStateHandle["argName"]` over the generated `NavArgs` data class — it avoids the presentation layer importing KSP-generated `ui`-layer types. (No screen currently takes a nav arg, so there is no in-repo example.)
- The generated destination package is derived from the **common package prefix** of all `@Destination` composables in the module (here, `.ui`) — keep every `@Destination` composable under that module's `ui/` package so the generated package stays predictable.

## Data layer conventions

- The app is fully local and offline — there is no network layer and no Room. Persistence is Jetpack DataStore (`feature:settings`'s `CameraSettingsLocalDataSource`/`LutLocalDataSource`), and the imported-LUT library is a plain scanned directory on disk. Reads are `Flow`-returning and mapped to domain models in `data/`.
- Every repository/use-case boundary returns `com.dragote.xcamera.shared.common.domain.result.Result<D, DataError>` instead of throwing — this keeps ViewModels free of try/catch. `DataError` has a `Local` sub-enum; extend it (and add new sub-enums as new error sources appear, e.g. a future network layer) rather than adding new ad hoc error types.
- The `xcamera.android.room` and `xcamera.kotlin.serialization` convention plugins exist but **no module currently applies them** — nothing in the app uses Room, Retrofit, or JSON serialization. `shared:common`'s `NetworkModule` (base `OkHttpClient`/`Json`) is likewise unused scaffolding. If a feature ever does need a database, it owns its own `<Feature>Database` rather than a shared entities registry, so `shared:common` never depends on feature-owned entities.

## Testing conventions

- MockK + Turbine + `kotlinx-coroutines-test`. No Mockito.
- Test files mirror the production package structure 1:1 under `src/test/java/...`.
- What gets unit tested per layer: repository (mock API/DAO, assert `Result` mapping and error translation), use case (mock repository, assert delegation), ViewModel (mock use case, assert `uiState` sequence via Turbine's `.test { awaitItem() }`).
- ViewModel tests need a `MainDispatcherRule` (`Dispatchers.setMain`/`resetMain` around each test). It lives in `:shared:testing` — add `testImplementation(project(":shared:testing"))` and import it, don't re-copy the rule into the module.
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

## Known gotchas

- **KSP version must lock-step with the Kotlin version** (`ksp = "<kotlin-version>-<ksp-patch>"` in `gradle/libs.versions.toml`) — bumping Kotlin without bumping KSP (or vice versa) breaks annotation processing across every module.
- compose-destinations' generated destination package is inferred from the common prefix of `@Destination` composables in a module — don't scatter `@Destination` composables across unrelated packages within a feature, or the generated package/import paths become unpredictable.
- Room requires at least one entity in a `@Database` — don't create a placeholder `AppDatabase` with an empty entities list "for later"; add it when there's an actual table to put in it. (This is why the app has no Room database at all today.)
- Gradle plugin artifacts in `build-logic/convention/build.gradle.kts` are `implementation`, not `compileOnly` — switching to `compileOnly` breaks imperative `pluginManager.apply(...)` calls inside convention plugins with a "could not generate a decorated class" error.
- **A tool call's shell is not the user's interactive shell.** zsh reads `~/.zshrc` only for interactive shells, so `JAVA_HOME`/`ANDROID_HOME` can be unset and `gh`/`adb` missing from `PATH` in a tool call even though they work in the user's terminal. Before reporting a tool as not installed, check the real path (`/opt/homebrew/bin/gh`, `$HOME/Library/Android/sdk/platform-tools/adb`) or re-test with `zsh -ic`. Relatedly, anything needing `sudo` cannot run from a tool call at all — no TTY, so the password prompt fails — and has to be run by the user in a real terminal.
- Room's schema export directory isn't configured (no `androidx.room` Gradle plugin applied). No module uses Room today, so this is dormant; if one ever does, expect a benign KSP schema-export warning and wire up export only if you need migration testing.
