pluginManagement {
    includeBuild("build-logic")
    repositories {
        google {
            content {
                includeGroupByRegex("com\\.android.*")
                includeGroupByRegex("com\\.google.*")
                includeGroupByRegex("androidx.*")
            }
        }
        mavenCentral()
        gradlePluginPortal()
    }
}
dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google()
        mavenCentral()
    }
}

rootProject.name = "xCamera"
include(":app")
include(":shared:common")
include(":shared:designsystem")
include(":shared:diagnostics")
include(":shared:navigation")
include(":shared:testing")
include(":feature:camera")
include(":feature:settings")
include(":feature:diagnostics")