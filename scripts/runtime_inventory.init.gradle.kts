import groovy.json.JsonOutput
import org.gradle.api.artifacts.component.ComponentIdentifier
import org.gradle.api.artifacts.component.ModuleComponentIdentifier
import org.gradle.api.artifacts.component.ProjectComponentIdentifier
import org.gradle.api.artifacts.result.ResolvedArtifactResult
import org.gradle.api.artifacts.result.ResolvedDependencyResult
import org.gradle.api.artifacts.verification.DependencyVerificationMode
import org.gradle.api.tasks.bundling.Jar
import org.gradle.maven.MavenModule
import org.gradle.maven.MavenPomArtifact
import org.w3c.dom.Element
import java.io.File
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.StandardCopyOption
import java.security.MessageDigest
import java.util.concurrent.TimeUnit
import javax.xml.parsers.DocumentBuilderFactory

fun digest(file: File, algorithm: String = "SHA-256", gitBlob: Boolean = false): String {
    check(Files.isRegularFile(file.toPath(), LinkOption.NOFOLLOW_LINKS)) {
        "Inventory input is not a regular file"
    }
    val digest = MessageDigest.getInstance(algorithm)
    if (gitBlob) digest.update("blob ${file.length()}\u0000".toByteArray(Charsets.UTF_8))
    file.inputStream().use { input ->
        val buffer = ByteArray(64 * 1024)
        while (true) {
            val count = input.read(buffer)
            if (count < 0) break
            digest.update(buffer, 0, count)
        }
    }
    return digest.digest().joinToString("") { "%02x".format(it) }
}

fun sha256(file: File): String = digest(file)

fun identity(id: ComponentIdentifier): String = when (id) {
    is ModuleComponentIdentifier -> "${id.group}:${id.module}:${id.version}"
    is ProjectComponentIdentifier -> "project:${id.projectPath}"
    else -> error("Unsupported resolved component identity")
}

fun children(element: Element, name: String): List<Element> =
    (0 until element.childNodes.length).mapNotNull { index ->
        (element.childNodes.item(index) as? Element)?.takeIf { (it.localName ?: it.tagName) == name }
    }

fun text(element: Element, name: String): String? {
    val value = children(element, name).singleOrNull()?.textContent?.trim()
    check(value == null || value.length <= 4096) { "POM field exceeded its inventory limit" }
    return value?.takeIf { it.isNotEmpty() }
}

fun pomEvidence(file: File): Map<String, Any?> {
    check(file.isFile && file.length() <= 2L * 1024 * 1024) { "POM is unavailable or exceeds its byte limit" }
    val factory = DocumentBuilderFactory.newInstance().apply {
        isNamespaceAware = true
        isXIncludeAware = false
        isExpandEntityReferences = false
        setFeature("http://apache.org/xml/features/disallow-doctype-decl", true)
        setFeature("http://xml.org/sax/features/external-general-entities", false)
        setFeature("http://xml.org/sax/features/external-parameter-entities", false)
        setAttribute("http://javax.xml.XMLConstants/property/accessExternalDTD", "")
        setAttribute("http://javax.xml.XMLConstants/property/accessExternalSchema", "")
    }
    val root = file.inputStream().use { factory.newDocumentBuilder().parse(it).documentElement }
    check((root.localName ?: root.tagName) == "project") { "POM root is not a Maven project" }
    val licenses = children(root, "licenses").flatMap { children(it, "license") }.map {
        mapOf("name" to text(it, "name"), "url" to text(it, "url"), "distribution" to text(it, "distribution"))
    }.sortedBy { JsonOutput.toJson(it) }
    val parent = children(root, "parent").singleOrNull()?.let {
        mapOf("group" to text(it, "groupId"), "module" to text(it, "artifactId"), "version" to text(it, "version"))
    }
    return mapOf(
        "sha256" to sha256(file),
        "direct_license_declarations" to licenses,
        "license_status" to if (licenses.isEmpty()) "no-direct-declaration" else "declared-unreviewed",
        "parent" to parent,
        "parent_license_inheritance" to if (parent == null) "no-parent" else "not-evaluated"
    )
}

gradle.projectsEvaluated {
    val root = rootProject
    val core = root.project(":core")
    val preview = root.findProject(":preview")
    root.tasks.register("runtimeInventory") {
        group = "verification"
        description = "Record resolved runtime inputs and exact core/unsigned-preview output hashes."
        dependsOn(core.tasks.named("jar"))
        if (preview != null) dependsOn(preview.tasks.named("assembleDebug"))
        // An inventory must inspect the current artifacts/source each time, not
        // accept a previously cached receipt for a different checkout.
        outputs.upToDateWhen { false }
        doLast {
            check(gradle.startParameter.dependencyVerificationMode == DependencyVerificationMode.STRICT) {
                "Runtime inventory requires strict dependency verification"
            }
            fun git(vararg arguments: String): String {
                val output = File.createTempFile("runtime-inventory-git-", ".out")
                val error = File.createTempFile("runtime-inventory-git-", ".err")
                try {
                    val process = ProcessBuilder(listOf("git", "--no-replace-objects", *arguments)).directory(root.rootDir)
                        .redirectOutput(output).redirectError(error).start()
                    val finished = process.waitFor(30, TimeUnit.SECONDS)
                    if (!finished) process.destroyForcibly().waitFor(5, TimeUnit.SECONDS)
                    check(finished && process.exitValue() == 0 && error.length() == 0L &&
                        output.length() <= 4L * 1024 * 1024) { "Inventory source inspection failed" }
                    return output.readText(Charsets.UTF_8).trim()
                } finally {
                    output.delete()
                    error.delete()
                }
            }
            check(git("status", "--porcelain", "--untracked-files=all").isEmpty()) {
                "Inventory requires a clean source checkout at its recorded commit"
            }
            val commit = git("rev-parse", "HEAD")
            val tree = git("rev-parse", "HEAD^{tree}")
            check(Regex("[0-9a-f]{40}").matches(commit) && Regex("[0-9a-f]{40}").matches(tree)) {
                "Inventory source identity is invalid"
            }
            val tracked = git("ls-tree", "-rz", "--full-tree", "HEAD").split('\u0000')
                .filter { it.isNotEmpty() }.associate { entry ->
                    val fields = entry.split('\t', limit = 2)
                    check(fields.size == 2) { "Invalid source tree entry" }
                    val metadata = fields[0].split(' ')
                    check(metadata.size == 3 && metadata[1] == "blob" &&
                        metadata[0] in setOf("100644", "100755")) { "Unsupported source tree entry" }
                    fields[1] to metadata[2]
                }
            check("scripts/runtime_inventory.init.gradle.kts" in tracked) {
                "Inventory driver is absent from the recorded source"
            }
            // Byte comparisons also catch assume-unchanged/skip-worktree flags.
            tracked.forEach { (path, blob) ->
                check(digest(root.file(path), "SHA-1", gitBlob = true) == blob) {
                    "Tracked source bytes differ from the recorded commit"
                }
            }
            val checkout = root.rootDir.toPath().toAbsolutePath().normalize()
            val generated = root.allprojects.flatMap { project ->
                listOf(project.layout.buildDirectory.get().asFile, project.file(".gradle"),
                    project.file(".kotlin"), project.file("local.properties"))
            }.plus(root.file(".git")).map { it.toPath().toAbsolutePath().normalize() }
            val visited = mutableSetOf<java.nio.file.Path>()
            fun verifyProjectInput(file: File) {
                val path = file.toPath().toAbsolutePath().normalize()
                if (!path.startsWith(checkout) || generated.any { path.startsWith(it) } ||
                    !visited.add(path) || !Files.exists(path, LinkOption.NOFOLLOW_LINKS)) return
                var ancestor = path
                while (ancestor != checkout) {
                    check(!Files.isSymbolicLink(ancestor)) { "Source symlinks are not inventoried" }
                    ancestor = ancestor.parent
                }
                if (Files.isDirectory(path, LinkOption.NOFOLLOW_LINKS)) {
                    Files.list(path).use { files -> files.forEach { verifyProjectInput(it.toFile()) } }
                } else {
                    check(Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS) &&
                        checkout.relativize(path).toString().replace(File.separatorChar, '/') in tracked) {
                        "Untracked project build input cannot enter an inventoried build"
                    }
                }
            }
            // Module roots alone miss imported schemas/assets. Follow declared
            // JVM sources and actual task/copy inputs beneath this checkout too.
            // Generated outputs and external build-tool inputs are not source.
            listOfNotNull(core, preview).forEach { verifyProjectInput(it.file("src")) }
            core.extensions.getByType(org.gradle.api.tasks.SourceSetContainer::class.java).forEach {
                it.allSource.srcDirs.forEach(::verifyProjectInput)
            }
            gradle.taskGraph.allTasks.filter { it.path != path }.forEach { task ->
                task.inputs.files.files.forEach(::verifyProjectInput)
                if (task is org.gradle.api.tasks.AbstractCopyTask) task.source.files.forEach(::verifyProjectInput)
            }
            val classpaths = mutableListOf(core to "runtimeClasspath")
            if (preview != null) classpaths.add(preview to "debugRuntimeClasspath")
            val scopes = classpaths.map { (project, configurationName) ->
                val configuration = project.configurations.getByName(configurationName)
                val artifacts = configuration.incoming.artifacts.artifacts.map { artifact ->
                    mapOf("component" to identity(artifact.id.componentIdentifier),
                          "file" to artifact.file.name, "sha256" to sha256(artifact.file),
                          "bytes" to artifact.file.length())
                }.sortedWith(compareBy({ it["component"].toString() }, { it["file"].toString() }))
                val components = configuration.incoming.resolutionResult.allComponents
                val modules = components.map { it.id }.filterIsInstance<ModuleComponentIdentifier>()
                val pomResults = project.dependencies.createArtifactResolutionQuery().forComponents(modules)
                    .withArtifacts(MavenModule::class.java, MavenPomArtifact::class.java).execute()
                check(pomResults.components.size == pomResults.resolvedComponents.size) {
                    "A runtime POM could not be resolved"
                }
                val poms = pomResults.resolvedComponents.associate { component ->
                    val results = component.getArtifacts(MavenPomArtifact::class.java)
                    check(results.size == 1 && results.single() is ResolvedArtifactResult) {
                        "Runtime component ${identity(component.id)} has no unique resolved POM"
                    }
                    identity(component.id) to pomEvidence((results.single() as ResolvedArtifactResult).file)
                }
                check(poms.keys == modules.map { identity(it) }.toSet()) { "Runtime POM inventory is incomplete" }
                val graph = components.map { component ->
                    val dependencies = component.dependencies.map { dependency ->
                        check(dependency is ResolvedDependencyResult) { "A runtime dependency was not resolved" }
                        mapOf("component" to identity(dependency.selected.id), "constraint" to dependency.isConstraint)
                    }.sortedWith(compareBy({ it["component"].toString() }, { it["constraint"].toString() }))
                    mapOf("component" to identity(component.id), "dependencies" to dependencies,
                          "pom" to poms[identity(component.id)])
                }.sortedBy { it["component"].toString() }
                mapOf("project" to project.path, "configuration" to configurationName,
                      "components" to graph, "artifacts" to artifacts)
            }
            val outputs = mutableListOf(
                "core-jar" to (core.tasks.named("jar").get() as Jar).archiveFile.get().asFile
            )
            if (preview != null) outputs.add("unsigned-debug-preview" to
                preview.layout.buildDirectory.file("outputs/apk/debug/preview-debug-unsigned.apk").get().asFile)
            val sourcePins = listOf("build.gradle.kts", "settings.gradle.kts", "core/build.gradle.kts",
                "core/gradle.lockfile", "gradle/verification-metadata.xml", "gradle/wrapper/gradle-wrapper.properties",
                "gradle/wrapper/gradle-wrapper.jar", "scripts/runtime_inventory.init.gradle.kts") +
                if (preview == null) emptyList() else listOf("preview/build.gradle.kts", "preview/gradle.lockfile")
            val receipt = linkedMapOf(
                "schema_version" to 1, "scope" to "resolved-runtime-classpaths",
                "source" to mapOf("commit" to commit, "tree" to tree,
                    "pins" to sourcePins.sorted().map { mapOf("file" to it, "sha256" to sha256(root.file(it))) }),
                "toolchain" to mapOf("gradle" to gradle.gradleVersion, "java" to System.getProperty("java.version")),
                "outputs" to outputs.map { (kind, file) ->
                    mapOf("kind" to kind, "file" to file.relativeTo(root.rootDir).invariantSeparatorsPath,
                          "sha256" to sha256(file), "bytes" to file.length())
                },
                "classpaths" to scopes,
                "limitations" to listOf(
                    "Resolved runtime inputs are not a claim that every byte is packaged or reachable in the APK.",
                    "POM license declarations are unreviewed metadata; inherited licenses and distribution duties need review.",
                    "Build plugins, test-only runtimes, native SDK tools and Gradle are outside this runtime graph; selected build inputs have source hashes.",
                    "No vulnerability scan, signed release, store delivery or installed-device acceptance is established."
                )
            )
            val output = root.layout.buildDirectory.file("inventory/runtime-inventory.json").get().asFile
            check(output.parentFile.isDirectory || output.parentFile.mkdirs()) { "Inventory output directory is unavailable" }
            val temporary = File.createTempFile("runtime-inventory-", ".json", output.parentFile)
            try {
                temporary.writeText(JsonOutput.prettyPrint(JsonOutput.toJson(receipt)) + "\n", Charsets.UTF_8)
                Files.move(temporary.toPath(), output.toPath(), StandardCopyOption.ATOMIC_MOVE,
                    StandardCopyOption.REPLACE_EXISTING)
            } finally {
                temporary.delete()
            }
            println("Runtime inventory written: build/inventory/runtime-inventory.json")
        }
    }
}
