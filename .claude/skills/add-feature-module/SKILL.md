---
name: add-feature-module
description: How to add a Gradle module to xCamera and how its build-logic convention plugins work. Load before creating a feature or shared module, before editing anything under build-logic/, and before adding or changing a convention plugin.
---

# Adding a module, and the build-logic plugins behind it

## Adding a feature module

1. Create `feature/<name>/` and register it in root `settings.gradle.kts` (`include(":feature:<name>")`).
2. `feature/<name>/build.gradle.kts`: apply `xcamera.feature`, set `android.namespace`, and depend on `project(":shared:common")`, `project(":shared:designsystem")`, `project(":shared:navigation")`, plus `testImplementation(project(":shared:testing"))`. Add `project(":shared:diagnostics")` only if the feature reads camera capabilities. Apply `xcamera.android.room` / `xcamera.kotlin.serialization` only if it genuinely needs a database or a JSON/network layer — no module does today, and adding one speculatively is the exact thing `CLAUDE.md`'s "Minimal infrastructure" rules out.
3. Build out the `data`/`domain`/`presentation`/`ui`/`di` packages per `CLAUDE.md`'s package-per-layer convention.
4. Add a manifest at `feature/<name>/src/main/AndroidManifest.xml` only if the feature needs a permission — a library module without one needs no manifest at all.
5. Add `implementation(project(":feature:<name>"))` to `app/build.gradle.kts`.
6. Add the feature's generated `<name>Destinations` list to `destinationsByRoute` in `app/src/main/java/com/dragote/xcamera/navigation/AppNavGraph.kt`. Forgetting this compiles fine and leaves the screens unreachable.
7. Write unit tests mirroring the production package structure.

A new module never depends on another `feature:*` module — see `CLAUDE.md`'s module map for where shared things go instead.

## The convention plugins

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

Only modules that actually declare Hilt bindings or injection apply `xcamera.android.hilt`: today `:app`, `:shared:common`, `:shared:diagnostics`, and every `feature:*` module via `xcamera.feature`.

## Adding a convention plugin

1. Add a `Plugin<Project>` class under `build-logic/convention/src/main/kotlin/com/dragote/xcamera/buildlogic/`.
2. Register it in `build-logic/convention/build.gradle.kts`'s `gradlePlugin { plugins { ... } }` block.
3. Apply its ID from a module's `build.gradle.kts`.

Read versions through the `Project.libs` accessor (`build-logic/convention/src/main/kotlin/com/dragote/xcamera/buildlogic/Libs.kt`) against `gradle/libs.versions.toml` — never hardcode a version string.

**Gradle plugin artifacts (AGP, Kotlin, KSP, Hilt, Compose compiler) are `implementation`, not `compileOnly`,** in `build-logic/convention/build.gradle.kts`. Imperative `pluginManager.apply(...)` calls from inside a convention plugin need those classes on the plugin's own runtime classpath; switching to `compileOnly` fails with "could not generate a decorated class".

**Anything changed under `build-logic/` needs a fresh Gradle sync** (Android Studio: File → Sync Project with Gradle Files) — precompiled plugin classes are cached per `build-logic` build.
