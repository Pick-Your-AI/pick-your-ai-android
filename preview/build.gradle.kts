plugins {
    id("com.android.application")
    kotlin("android")
}
// Only the accepted synthetic application exists. Never reinterpret a real mode as preview.
val selectedEnvironment = providers.gradleProperty("androidEnvironment").orElse("preview").get()
when (selectedEnvironment) {
    "preview" -> Unit
    "staging", "production" -> throw GradleException("Android real-data configuration is unavailable: accepted endpoint, identity and capability handoffs are required by #35/#57.")
    else -> throw GradleException("Unsupported Android environment. Select preview, staging or production; no value is echoed.")
}
android {
    namespace = "ai.pickyour.android.preview"
    buildFeatures.buildConfig = true
    compileSdk = 35
    defaultConfig {
        applicationId = "ai.pickyour.android.preview"
        minSdk = 26
        targetSdk = 35
        versionCode = 1
        versionName = "0.0.1-synthetic"
        buildConfigField("String", "PYAI_ENVIRONMENT", "\"preview\"")
    }
    buildTypes.getByName("debug") { signingConfig = null }
    testOptions.unitTests.isIncludeAndroidResources = true
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    sourceSets.getByName("main").assets.srcDir(rootProject.file("contracts/v1/examples"))
    packaging.resources.merges += setOf("META-INF/LICENSE*", "META-INF/NOTICE*", "META-INF/DEPENDENCIES")
}
kotlin { jvmToolchain(21); compilerOptions { jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17) } }
androidComponents { beforeVariants(selector().withBuildType("release")) { it.enable = false } }

// Robolectric's runtime resolver otherwise downloads this SDK outside Gradle's
// dependency locks and checksum verification. Keep it off production classpaths.
val robolectricRuntime by configurations.creating {
    isCanBeConsumed = false
    isCanBeResolved = true
    isTransitive = false
}
val robolectricRuntimeDirectory = layout.buildDirectory.dir("robolectric-runtime")
val prepareRobolectricRuntime by tasks.registering(Sync::class) {
    from(robolectricRuntime)
    into(robolectricRuntimeDirectory)
}
tasks.withType<Test>().configureEach {
    dependsOn(prepareRobolectricRuntime)
    inputs.files(robolectricRuntime).withPropertyName("robolectricRuntime")
        .withPathSensitivity(PathSensitivity.NONE)
    systemProperty("robolectric.offline", "true")
    systemProperty("robolectric.dependency.dir", robolectricRuntimeDirectory.get().asFile.absolutePath)
}
dependencies {
    implementation(project(":core"))
    testImplementation("junit:junit:4.13.2")
    testImplementation("org.robolectric:robolectric:4.17")
    // Matches @Config(sdk = [28]) and Robolectric 4.17's instrumentation revision.
    robolectricRuntime("org.robolectric:android-all-instrumented:9-robolectric-4913185-2-i7")
}
dependencyLocking { lockAllConfigurations() }
