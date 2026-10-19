package ai.pickyour.core.preview

import ai.pickyour.core.drafts.DraftProjectionArtifact
import ai.pickyour.core.drafts.LocalSelection
import ai.pickyour.core.evaluation.CompatibleOptionSlot
import ai.pickyour.core.evaluation.CompatibleOptionsRequest
import ai.pickyour.core.evaluation.CompatibleOptionsUnavailable
import ai.pickyour.core.evaluation.DraftCompatibleOptionsPreparation
import ai.pickyour.core.evaluation.DraftCompatibleOptionsRequest
import ai.pickyour.core.cache.ArtifactHash
import ai.pickyour.core.contracts.FrozenDocument
import ai.pickyour.core.contracts.FrozenSchemaValidator
import ai.pickyour.core.contracts.FrozenValidation
import ai.pickyour.core.importing.BoundedJsonReader
import ai.pickyour.core.importing.JsonInputLimits
import ai.pickyour.core.importing.JsonInputResult
import java.security.MessageDigest

/** Native UI receives typed immutable pins, without depending on the core JSON implementation. */
object PreviewCompatibleOptionInputs {
    fun catalog(bytes: ByteArray): DraftProjectionArtifact? = artifact(bytes, FrozenDocument.CATALOG)
    fun ruleset(bytes: ByteArray): DraftProjectionArtifact? = artifact(bytes, FrozenDocument.RULESET)
    private fun artifact(source: ByteArray, document: FrozenDocument): DraftProjectionArtifact? {
        if (source.size > 262_144) return null
        val bytes = source.copyOf()
        val value = (BoundedJsonReader(JsonInputLimits(maxBytes = 262_144, maxTokens = 20_000, maxStringLength = 4096))
            .read(bytes.inputStream()) as? JsonInputResult.Parsed)?.value ?: return null
        if (FrozenSchemaValidator().validate(document, bytes.inputStream()) != FrozenValidation.Valid) return null
        val version = if (document == FrozenDocument.CATALOG) {
            if (!value["revision"].isIntegralNumber) return null
            value["revision"].bigIntegerValue().toString()
        } else value["version"].asText()
        val hash = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it.toInt() and 255) }
        return DraftProjectionArtifact(value["id"].asText(), version, ArtifactHash(hash), bytes)
    }
}

/** A hypothetical replacement retains every original setting, link endpoint, role and enabled state. */
fun prepareCompatibleReplacement(original: LocalSelection, item: PreviewCatalogItem): LocalSelection? = try {
    if (!original.enabled || !PreviewSelectionForms.supported(original.kind) || item.kind != original.kind) null
    else CompatibleOptionsRequest.encodeLocalContext(original.toJson().put("catalog_item_id", item.id)
        .put("catalog_item_version", item.version))?.toByteArray()?.let(LocalSelection::fromJson)
} catch (_: IllegalArgumentException) { null }

/** Capture only acknowledged stored fields. Pending text must be saved or stay explicitly unresolved. */
fun PreviewStoredDraft.prepareCompatibleOptions(
    catalog: DraftProjectionArtifact, ruleset: DraftProjectionArtifact,
    slot: CompatibleOptionSlot, candidates: List<ByteArray>,
): DraftCompatibleOptionsPreparation = DraftCompatibleOptionsRequest.prepare(draft, catalog, ruleset, slot, candidates)

/** Typed native forms use the existing bounded encoder, retaining floating versus integral JSON. */
fun PreviewStoredDraft.prepareCompatibleSelections(
    catalog: DraftProjectionArtifact, ruleset: DraftProjectionArtifact,
    slot: CompatibleOptionSlot, candidates: List<LocalSelection>,
): DraftCompatibleOptionsPreparation {
    if (candidates.size > CompatibleOptionsRequest.MAX_CANDIDATES)
        return DraftCompatibleOptionsPreparation.Unavailable(CompatibleOptionsUnavailable.WORK_LIMIT)
    val encoded = candidates.map {
        CompatibleOptionsRequest.encodeLocalContext(it.toJson())?.toByteArray()
            ?: return DraftCompatibleOptionsPreparation.Unavailable(CompatibleOptionsUnavailable.WORK_LIMIT)
    }
    return prepareCompatibleOptions(catalog, ruleset, slot, encoded)
}

/** Exact record-object membership only; the UI must also check its current lifecycle and store gate. */
fun DraftCompatibleOptionsRequest.belongsTo(record: PreviewStoredDraft): Boolean = belongsTo(record.draft)
