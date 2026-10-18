package ai.pickyour.core.preview

import ai.pickyour.core.contracts.FrozenDocument
import ai.pickyour.core.contracts.FrozenSchemaValidator
import ai.pickyour.core.contracts.FrozenValidation
import ai.pickyour.core.evidence.*
import ai.pickyour.core.importing.BoundedJsonReader
import ai.pickyour.core.importing.JsonInputResult
import java.time.Instant

/** Diagnostic projection only; never a publication adapter or evidence assessment. */
data class SyntheticCatalogEvidence(
    val identity: EvidenceIdentity,
    val name: String,
    val provenanceNote: String,
    val snapshotCapturedAt: Instant,
    val presentation: EvidencePresentation,
) {
    companion object {
        val FIXTURE_PIN = EvidenceIdentity("cat.workload.fixture", "2026.10-demo", "catalog.fixture.1", 1)

        /** A missing exact pin fails closed, never substitutes a newer item or catalog revision. */
        fun read(bytes: ByteArray, requested: EvidenceIdentity = FIXTURE_PIN): SyntheticCatalogEvidence? {
            if (bytes.size > 1_048_576 || requested != FIXTURE_PIN) return null
            if (FrozenSchemaValidator().validate(FrozenDocument.CATALOG, bytes.inputStream()) != FrozenValidation.Valid) return null
            val root = (BoundedJsonReader().read(bytes.inputStream()) as? JsonInputResult.Parsed)?.value ?: return null
            if (root.path("id").asText() != requested.snapshotId || root.path("revision").bigIntegerValue() != java.math.BigInteger.valueOf(requested.snapshotRevision)) return null
            val matches = root.path("items").filter {
                it.path("id").asText() == requested.claimId && it.path("version").asText() == requested.version
            }
            val item = matches.singleOrNull() ?: return null
            // This diagnostic explicitly selects the fixture's single provenance entry; never hides alternatives.
            val provenance = item.path("provenance").singleOrNull() ?: return null
            val raw = FrozenVerificationStatus.entries.singleOrNull {
                it.encoded == provenance.path("verification_status").asText()
            } ?: return null
            val captured = Instant.parse(provenance.path("checked_at").asText())
            return SyntheticCatalogEvidence(
                requested, item.path("name").asText(), provenance.path("notes").asText(),
                Instant.parse(root.path("captured_at").asText()),
                EvidencePresentationPolicy.present(EvidenceClaim(requested, raw, FactClass.VERSION_PINNED, captured)),
            )
        }
    }
}
