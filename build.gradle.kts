// Keep Android tooling off the default JVM-only build classpath.
buildscript {
    if (providers.gradleProperty("androidPreview").orNull == "true") {
        repositories { mavenCentral(); google() }
        dependencies { classpath("com.android.tools.build:gradle:8.9.2") }
    }
}
plugins {
    kotlin("jvm") version "2.1.20" apply false
}
