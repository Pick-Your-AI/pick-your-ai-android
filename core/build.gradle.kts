plugins { kotlin("jvm") }
kotlin { jvmToolchain(21); compilerOptions { jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17) } }
tasks.withType<JavaCompile>().configureEach { options.release.set(17) }
dependencies {
    implementation("com.networknt:json-schema-validator:1.5.6")
    implementation("com.fasterxml.jackson.core:jackson-databind:2.22.4")
    testImplementation(platform("org.junit:junit-bom:5.11.4"))
    testImplementation("org.junit.jupiter:junit-jupiter")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}
tasks.test {
    useJUnitPlatform()
    // The fixture receipt binds each observation to the test that actually produced it.
    reports.junitXml.isOutputPerTestCase = true
}
dependencyLocking { lockAllConfigurations() }

tasks.processResources {
    from(rootProject.file("contracts/v1/schemas")) {
        include("*.schema.json", "component-registry.json")
        into("contracts/v1/schemas")
    }
}
tasks.processTestResources {
    from(rootProject.file("contracts/v1/examples")) { into("contracts/v1/examples") }
    from(rootProject.file("contracts/v1/baseline/core-ruleset.json")) { into("contracts/v1/baseline") }
}
