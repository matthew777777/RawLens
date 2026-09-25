pluginManagement {
    repositories { google(); mavenCentral(); gradlePluginPortal() }
}
dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories { google(); mavenCentral() }
}
rootProject.name = "RawLens"
include(":app")
include(":tools:mosaic-desktop")
include(":tools:sr-vulkan")
include(":tools:linear-sr-desktop")
