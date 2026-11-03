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
    }
}

rootProject.name = "yansit"

include(":app")
include(":protocol")
include(":sender")

// Test aracı: android/ dışında durur ama aynı version catalog ve :protocol modülünü kullanır.
include(":fake-sender")
project(":fake-sender").projectDir = file("../tools/fake-sender")
