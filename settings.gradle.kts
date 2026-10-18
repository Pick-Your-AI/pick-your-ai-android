pluginManagement { repositories { gradlePluginPortal(); mavenCentral(); google() } }
dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories { mavenCentral(); google() }
}
rootProject.name = "pick-your-ai-android"
include(":core")

// Explicit opt-in keeps contract/JVM checks independent of an Android SDK.
if (providers.gradleProperty("androidPreview").orNull == "true") include(":preview")
