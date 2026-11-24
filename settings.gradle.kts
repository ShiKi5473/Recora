pluginManagement {
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
        // WayMap（com.github.ShiKi5473.WayMap:*）由 JitPack 依 git tag 提供
        maven("https://jitpack.io") {
            content { includeGroupByRegex("com\\.github\\.ShiKi5473.*") }
        }
    }
}

rootProject.name = "Recora"
include(":app")
