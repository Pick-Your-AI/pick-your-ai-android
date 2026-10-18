package ai.pickyour.core.drafts

import ai.pickyour.core.cache.ArtifactHash
import ai.pickyour.core.contracts.FrozenDocument
import ai.pickyour.core.contracts.FrozenValidation
import ai.pickyour.core.importing.localSnapshotRevision
import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.databind.node.ObjectNode
import java.io.ByteArrayOutputStream
import java.io.DataOutputStream
import java.math.BigInteger
import java.nio.ByteBuffer
import java.security.MessageDigest
import java.time.Instant
import java.time.format.DateTimeParseException

internal class ArchiveFormatFailure(val reason: SnapshotArchiveFailure) : RuntimeException()
internal fun archiveBad(reason: SnapshotArchiveFailure = SnapshotArchiveFailure.CORRUPT): Nothing = throw ArchiveFormatFailure(reason)
internal fun archiveHash(bytes: ByteArray) = ArtifactHash(MessageDigest.getInstance("SHA-256").digest(bytes)
    .joinToString("") { "%02x".format(it.toInt() and 255) })

/** Private version 1, fixed member order, no paths, extraction, compression or shared sidecar claim. */
internal object SnapshotBundleCodec {
    const val MAX_BYTES = 8 * 1024 * 1024
    private val magic = "PYAI-SNAPSHOT\u0000".toByteArray(Charsets.US_ASCII)
    private val mapper = ObjectMapper()
    private val requiredMembers = listOf("build", "source", "catalog", "ruleset", "graph")

    fun encode(candidate: DraftSnapshotCandidate): ByteArray {
        val sourceBytes = candidate.sourceBytes()
        if (archiveHash(sourceBytes) != candidate.source.version.hash) archiveBad()
        if (candidate.pins.map { it.kind }.toSet().size != candidate.pins.size) archiveBad()
        val members = linkedMapOf("build" to candidate.bytes(), "source" to sourceBytes)
        candidate.pins.forEach { pin ->
            val raw = candidate.artifactBytes(pin.kind) ?: archiveBad()
            if (archiveHash(raw) != pin.hash) archiveBad()
            members[pin.kind.name.lowercase()] = raw
        }
        val manifest = mapper.createObjectNode().put("bundle_version", 1).put("projection_profile", candidate.profileVersion)
        manifest.set<ObjectNode>("identity", keyNode(SnapshotArchiveKey(candidate.identity.id, candidate.identity.revision)))
        manifest.putObject("source").put("format", candidate.source.localFormatVersion).put("revision", candidate.source.version.number)
        manifest.putArray("retained_only_paths").also { array -> candidate.retainedOnlyPaths.forEach(array::add) }
        manifest.putArray("members").also { array -> members.forEach { (kind, bytes) ->
            val pin = candidate.pins.find { it.kind.name.lowercase() == kind }
            val node = array.addObject().put("kind", kind).put("bytes", bytes.size).put("sha256", (pin?.hash ?: archiveHash(bytes)).hex)
            pin?.let { node.put("id", it.id).put("version", it.version) }
        } }
        val metadata = try { SelectionJson.encode(manifest) } catch (_: IllegalArgumentException) {
            archiveBad(SnapshotArchiveFailure.LIMIT_EXCEEDED)
        }
        val size = magic.size.toLong() + 8 + metadata.size + members.values.sumOf { it.size.toLong() }
        if (size > MAX_BYTES || members.values.any { it.size > LocalDraftStore.MAX_BYTES }) archiveBad(SnapshotArchiveFailure.LIMIT_EXCEEDED)
        val output = ByteArrayOutputStream(size.toInt())
        DataOutputStream(output).use { stream ->
            stream.write(magic); stream.writeInt(1); stream.writeInt(metadata.size); stream.write(metadata)
            members.values.forEach(stream::write)
        }
        return output.toByteArray()
    }

    fun decode(bytes: ByteArray): RetainedDraftSnapshot = try {
        decodeChecked(bytes)
    } catch (failure: ArchiveFormatFailure) { throw failure }
    catch (failure: EnvelopeFailure) { archiveBad(if (failure.reason == DraftStorageFailure.UNSUPPORTED_VERSION)
        SnapshotArchiveFailure.UNSUPPORTED_VERSION else SnapshotArchiveFailure.CORRUPT) }
    catch (_: IllegalArgumentException) { archiveBad() }
    catch (_: DateTimeParseException) { archiveBad() }

    private fun decodeChecked(bytes: ByteArray): RetainedDraftSnapshot {
        if (bytes.size > MAX_BYTES || bytes.size < magic.size + 8) archiveBad()
        val input = ByteBuffer.wrap(bytes)
        val prefix = ByteArray(magic.size).also(input::get)
        if (!prefix.contentEquals(magic)) archiveBad()
        if (input.int != 1) archiveBad(SnapshotArchiveFailure.UNSUPPORTED_VERSION)
        val size = input.int
        if (size <= 0 || size > LocalDraftStore.MAX_BYTES || size > input.remaining()) archiveBad()
        val metadata = ByteArray(size).also(input::get)
        val manifest = SelectionJson.parse(metadata)
        fields(manifest, setOf("bundle_version", "projection_profile", "identity", "source", "retained_only_paths", "members"))
        if (integer(manifest["bundle_version"], 0, Int.MAX_VALUE) != 1 || integer(manifest["projection_profile"], 0, Int.MAX_VALUE) != 1)
            archiveBad(SnapshotArchiveFailure.UNSUPPORTED_VERSION)
        val key = key(manifest["identity"])
        val descriptor = manifest["members"]
        if (descriptor == null || !descriptor.isArray || descriptor.size() !in 5..6) archiveBad()
        val expectedOrder = if (descriptor.size() == 5) requiredMembers else requiredMembers + "pricebook"
        val retained = linkedMapOf<String, ByteArray>()
        val artifacts = mutableMapOf<ProjectionArtifactKind, DraftProjectionArtifact>()
        for ((i, member) in descriptor.withIndex()) {
            val kind = expectedOrder[i]
            val isArtifact = i >= 2
            fields(member, setOf("kind", "bytes", "sha256") + if (isArtifact) setOf("id", "version") else emptySet())
            if (text(member["kind"]) != kind) archiveBad()
            val length = integer(member["bytes"], 1, LocalDraftStore.MAX_BYTES)
            if (length > input.remaining()) archiveBad()
            val raw = ByteArray(length).also(input::get)
            val hash = ArtifactHash(text(member["sha256"]))
            if (archiveHash(raw) != hash) archiveBad()
            retained[kind] = raw
            if (isArtifact) {
                if (text(SelectionJson.parse(raw)["schema_version"]) != "1.0.0") archiveBad(SnapshotArchiveFailure.UNSUPPORTED_VERSION)
                artifacts[ProjectionArtifactKind.valueOf(kind.uppercase())] =
                    DraftProjectionArtifact(text(member["id"]), text(member["version"]), hash, raw)
            }
        }
        if (input.hasRemaining()) archiveBad()
        val rawBuild = retained.getValue("build")
        val build = SelectionJson.parse(rawBuild)
        if (text(build["schema_version"]) != "1.0.0") archiveBad(SnapshotArchiveFailure.UNSUPPORTED_VERSION)
        if (SelectionJson.validator.validate(FrozenDocument.BUILD, rawBuild.inputStream()) != FrozenValidation.Valid) archiveBad()
        val revision = localSnapshotRevision(build["revision"]) ?: archiveBad(SnapshotArchiveFailure.LIMIT_EXCEEDED)
        if (key != SnapshotArchiveKey(text(build["id"]), revision)) archiveBad()
        val identity = DraftSnapshotIdentity(key.id, key.revision, text(build["name"]), text(build["owner_ref"]),
            Instant.parse(text(build["created_at"])), Instant.parse(text(build["updated_at"])))
        val rawSource = retained.getValue("source")
        val source = DraftEnvelope.decode(rawSource)
        val sourceDescriptor = manifest["source"]
        fields(sourceDescriptor, setOf("format", "revision"))
        if (integer(sourceDescriptor["format"], 0, Int.MAX_VALUE) != source.format ||
            long(sourceDescriptor["revision"]) != source.history.last().number) archiveBad()
        val retainedOnly = manifest["retained_only_paths"]
        if (retainedOnly == null || !retainedOnly.isArray) archiveBad()
        val paths = retainedOnly.map(::text)
        if (paths != retainedOnlyPaths(source.history.last().metadata)) archiveBad()
        val inputs = DraftSnapshotInputs(1, identity, artifacts[ProjectionArtifactKind.CATALOG], artifacts[ProjectionArtifactKind.RULESET],
            artifacts[ProjectionArtifactKind.GRAPH], artifacts[ProjectionArtifactKind.PRICEBOOK])
        val inspected = DraftSnapshotProjection.inspect(source.history.last().content, inputs) as? ProjectionInspection.Ready ?: archiveBad()
        // Compare JSON values after frozen validation. Integral decimal/exponent spellings denote
        // the same mathematical revision while their original raw bytes and bundle hash stay distinct.
        if (!sameValue(build, SelectionJson.parse(inspected.candidateBytes))) archiveBad()
        val entry = SnapshotArchiveEntry(key, archiveHash(bytes), bytes.size)
        return RetainedDraftSnapshot(entry, identity, source, paths, inspected.artifacts, rawBuild, rawSource)
    }

    private fun sameValue(left: JsonNode, right: JsonNode): Boolean = when {
        left.isNumber && right.isNumber -> left.decimalValue().compareTo(right.decimalValue()) == 0
        left.isObject && right.isObject -> left.fieldNames().asSequence().toSet() == right.fieldNames().asSequence().toSet() &&
            left.properties().all { (name, value) -> sameValue(value, right[name]) }
        left.isArray && right.isArray -> left.size() == right.size() && (0 until left.size()).all { sameValue(left[it], right[it]) }
        else -> left == right
    }

    internal fun keyNode(key: SnapshotArchiveKey): ObjectNode = mapper.createObjectNode().put("id", key.id).put("revision", key.revision.toString())
    internal fun key(node: JsonNode?): SnapshotArchiveKey {
        fields(node, setOf("id", "revision"))
        val revision = text(node!!["revision"])
        if (revision.length > 128 || !revision.matches(Regex("[1-9][0-9]*"))) archiveBad()
        return SnapshotArchiveKey(text(node["id"]), BigInteger(revision))
    }
    internal fun fields(node: JsonNode?, names: Set<String>) {
        if (node !is ObjectNode || node.fieldNames().asSequence().toSet() != names) archiveBad()
    }
    internal fun text(node: JsonNode?): String {
        if (node == null || !node.isTextual || node.textValue().length > 16_384) archiveBad()
        return node.textValue()
    }
    internal fun integer(node: JsonNode?, minimum: Int, maximum: Int): Int {
        if (node == null || !node.isNumber) archiveBad()
        val number = node.decimalValue()
        if (number < minimum.toBigDecimal() || number > maximum.toBigDecimal()) archiveBad()
        return try { number.intValueExact() } catch (_: ArithmeticException) { archiveBad() }
    }
    private fun long(node: JsonNode?): Long {
        if (node == null || !node.isNumber) archiveBad()
        val number = node.decimalValue()
        if (number.signum() < 0 || number > Long.MAX_VALUE.toBigDecimal()) archiveBad()
        return try { number.longValueExact() } catch (_: ArithmeticException) { archiveBad() }
    }
}
