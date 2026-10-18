plugins {
    id("com.android.application")
    kotlin("android")
}
android {
    namespace = "ai.pickyour.android.preview"
    compileSdk = 35
    defaultConfig {
        applicationId = "ai.pickyour.android.preview"
        minSdk = 26
        targetSdk = 35
        versionCode = 1
        versionName = "0.0.1-synthetic"
    }
    buildTypes.getByName("debug") { signingConfig = null }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    sourceSets.getByName("main").assets.srcDir(rootProject.file("contracts/v1/examples"))
    packaging.resources.merges += setOf("META-INF/LICENSE*", "META-INF/NOTICE*", "META-INF/DEPENDENCIES")
}
kotlin { jvmToolchain(21); compilerOptions { jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17) } }
androidComponents { beforeVariants(selector().withBuildType("release")) { it.enable = false } }
dependencies { implementation(project(":core")) }
dependencyLocking { lockAllConfigurations() }
