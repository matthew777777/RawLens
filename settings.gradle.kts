pluginManagement {
    repositories { google(); mavenCentral(); gradlePluginPortal() }
}
dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories { google(); mavenCentral() }
}
rootProject.name = "RawLens"
include(":app")
include(":tools:sr-pipeline")
// Future SR hook: re-add :tools:mosaic-desktop, :tools:sr-vulkan,
// :tools:linear-sr-desktop here when SR returns.
