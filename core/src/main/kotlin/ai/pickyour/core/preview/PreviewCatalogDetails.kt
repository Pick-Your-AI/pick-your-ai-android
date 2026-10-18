package ai.pickyour.core.preview

import ai.pickyour.core.evidence.EvidenceLabel
import ai.pickyour.core.evidence.EvidencePresentationPolicy
import ai.pickyour.core.evidence.FrozenVerificationStatus
import ai.pickyour.core.importing.BoundedJsonReader
import ai.pickyour.core.importing.JsonInputResult
import com.fasterxml.jackson.databind.JsonNode
import java.math.BigInteger
import java.util.Collections

enum class PreviewCatalogValueState { MISSING, EXPLICIT_UNKNOWN, EMPTY_COLLECTION, CONTAINS_UNKNOWN, SUPPLIED }

/** Canonical field name and schema type; JSON preserves booleans, numbers, arrays and quoted text. */
class PreviewCatalogField internal constructor(
    val name: String, val type: String, val state: PreviewCatalogValueState, val valueJson: String?,
) {
    override fun toString() = "PreviewCatalogField"
}

class PreviewCatalogProvenance internal constructor(
    val sourceUri: String?, val checkedAt: String, val rawStatus: FrozenVerificationStatus, val notes: String?,
) {
    // A declared frozen status never supplies a receiving assessment or independent corroboration.
    val label: EvidenceLabel get() = EvidencePresentationPolicy.unassessedLabel(rawStatus)
    override fun toString() = "PreviewCatalogProvenance"
}

/** Read-only projection of one exact item in a validated, bounded snapshot. No rate or offering join. */
class PreviewCatalogDetails private constructor(
    val item: PreviewCatalogItem,
    val snapshotHash: String,
    val snapshotId: String,
    val snapshotRevision: BigInteger,
    val snapshotCapturedAt: String,
    val updatedAt: String,
    val status: String,
    val billingClass: String,
    val description: String?,
    specs: List<PreviewCatalogField>,
    license: List<PreviewCatalogField>,
    provenance: List<PreviewCatalogProvenance>,
) {
    val specs: List<PreviewCatalogField> = Collections.unmodifiableList(specs.toList())
    val license: List<PreviewCatalogField> = Collections.unmodifiableList(license.toList())
    val provenance: List<PreviewCatalogProvenance> = Collections.unmodifiableList(provenance.toList())
    override fun toString() = "PreviewCatalogDetails"

    companion object {
        private data class SpecField(val name: String, val type: String, val catalogReference: Boolean)

        private val specFields: Map<String, List<SpecField>> by lazy {
            val schema = requireNotNull(PreviewCatalogDetails::class.java.getResourceAsStream("/contracts/v1/schemas/catalog-item.schema.json")).use {
                (BoundedJsonReader().read(it) as JsonInputResult.Parsed).value
            }
            schema.path("$" + "defs").properties().associate { (name, spec) ->
                name.removePrefix("spec_") to spec.path("properties").properties().map { (field, definition) ->
                    val type = requireNotNull(definition.path("type").textValue())
                    val element = if (type == "array") definition.path("items") else definition
                    // The bundled frozen schema declares catalog IDs, including array members.
                    // Their literal spelling says nothing about target resolution or source validity.
                    val reference = element.path("type").asText() == "string" &&
                        element.path("description").asText().startsWith("Reference to catalog kind ")
                    SpecField(field, type, reference)
                }
            }
        }

        internal fun from(root: JsonNode, value: JsonNode, item: PreviewCatalogItem, hash: String): PreviewCatalogDetails {
            fun field(name: String, type: String, node: JsonNode?, catalogReference: Boolean = false): PreviewCatalogField {
                val state = when {
                    node == null -> PreviewCatalogValueState.MISSING
                    node.isArray && node.isEmpty -> PreviewCatalogValueState.EMPTY_COLLECTION
                    catalogReference -> PreviewCatalogValueState.SUPPLIED
                    node.isTextual && node.textValue() == "unknown" -> PreviewCatalogValueState.EXPLICIT_UNKNOWN
                    node.isArray && node.any { it.isTextual && it.textValue() == "unknown" } -> PreviewCatalogValueState.CONTAINS_UNKNOWN
                    else -> PreviewCatalogValueState.SUPPLIED
                }
                return PreviewCatalogField(name, type, state, node?.toString())
            }
            fun nullable(node: JsonNode, name: String): String? = node.get(name)?.textValue()
            val specs = specFields.getValue(item.kind).map { definition ->
                field(definition.name, definition.type, value.path("spec").get(definition.name), definition.catalogReference)
            }
            val license = listOf("spdx_id" to "string", "commercial_use_allowed" to "boolean", "self_hosting_allowed" to "boolean")
                .map { (name, type) -> field(name, type, value.path("license").get(name)) }
            val provenance = value.path("provenance").map { source ->
                PreviewCatalogProvenance(nullable(source, "source_uri"), source.path("checked_at").asText(),
                    FrozenVerificationStatus.entries.single { it.encoded == source.path("verification_status").asText() },
                    nullable(source, "notes"))
            }
            return PreviewCatalogDetails(item, hash, root.path("id").asText(), root.path("revision").bigIntegerValue(),
                root.path("captured_at").asText(), value.path("updated_at").asText(), value.path("status").asText(),
                value.path("billing_class").asText(), nullable(value, "description"), specs, license, provenance)
        }
    }
}
