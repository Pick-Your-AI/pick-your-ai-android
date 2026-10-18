package ai.pickyour.core.evidence

import java.time.Instant

/** Exact frozen-v1 provenance values; display policy never rewrites the stored value. */
enum class FrozenVerificationStatus(val encoded: String) {
    VERIFIED("verified"), USER_SUPPLIED("user_supplied"), ESTIMATED("estimated"), UNVERIFIED("unverified"),
}

data class EvidenceIdentity(val claimId: String, val version: String, val snapshotId: String, val snapshotRevision: Long) {
    init {
        require(listOf(claimId, version, snapshotId).all { it.isNotBlank() })
        require(snapshotRevision >= 1)
    }
}

enum class FactClass { VERSION_PINNED, CURRENT_STATE }
enum class IndependentSupport { NOT_ESTABLISHED, CORROBORATED, REPRODUCED }
enum class SourceCheck { NOT_ESTABLISHED, CHECKED }

/**
 * An external, claim-level receiving assessment, not a URL detector or an evidence evaluator.
 * The adapter must supply an actually reviewed record and its exact evidence identity.
 * A reference alone never constructs an affirmative assessment in this policy.
 * Source reading and independent support are separate assertions: neither implies the other.
 */
data class ReviewedEvidenceAssessment(
    val identity: EvidenceIdentity,
    val reviewReference: String,
    val sourceCheck: SourceCheck = SourceCheck.NOT_ESTABLISHED,
    val independentSupport: IndependentSupport = IndependentSupport.NOT_ESTABLISHED,
) {
    init { require(reviewReference.isNotBlank()) }
}

data class EvidenceClaim(
    val identity: EvidenceIdentity,
    val rawStatus: FrozenVerificationStatus,
    val factClass: FactClass,
    /** Capture/access timestamp from the selected evidence, never the device's current clock. */
    val capturedAt: Instant? = null,
)

enum class EvidenceLabel { INDEPENDENTLY_VERIFIED, VERIFICATION_UNESTABLISHED, UNVERIFIED, USER_SUPPLIED, ESTIMATED }

sealed interface EvidenceFreshness {
    /** This pin does not expire; changed/corrected evidence needs a separate evaluation. */
    data class VersionPinned(val identity: EvidenceIdentity) : EvidenceFreshness
    data class AsOf(val identity: EvidenceIdentity, val capturedAt: Instant) : EvidenceFreshness
    data class DateUnavailable(val identity: EvidenceIdentity) : EvidenceFreshness
}

data class EvidencePresentation(
    val rawStatus: FrozenVerificationStatus,
    val label: EvidenceLabel,
    val sourceCheck: SourceCheck,
    val independentSupport: IndependentSupport,
    val freshness: EvidenceFreshness,
    /** Preserve date absence even for pinned facts, whose freshness is determined by identity. */
    val capturedAt: Instant?,
    val appliedReviewReference: String?,
)

/** Pure presentation only: does not authorize reuse, evaluate compatibility, or invalidate a build. */
object EvidencePresentationPolicy {
    /** Presentation when no reviewed assessment was supplied; this grants no source-check meaning. */
    fun unassessedLabel(rawStatus: FrozenVerificationStatus): EvidenceLabel = when (rawStatus) {
        FrozenVerificationStatus.VERIFIED -> EvidenceLabel.VERIFICATION_UNESTABLISHED
        FrozenVerificationStatus.UNVERIFIED -> EvidenceLabel.UNVERIFIED
        FrozenVerificationStatus.USER_SUPPLIED -> EvidenceLabel.USER_SUPPLIED
        FrozenVerificationStatus.ESTIMATED -> EvidenceLabel.ESTIMATED
    }

    fun present(claim: EvidenceClaim, assessment: ReviewedEvidenceAssessment? = null): EvidencePresentation {
        val applicable = assessment?.takeIf { it.identity == claim.identity }
        val support = applicable?.independentSupport ?: IndependentSupport.NOT_ESTABLISHED
        val label = when (claim.rawStatus) {
            FrozenVerificationStatus.ESTIMATED, FrozenVerificationStatus.USER_SUPPLIED -> unassessedLabel(claim.rawStatus)
            FrozenVerificationStatus.VERIFIED, FrozenVerificationStatus.UNVERIFIED -> when {
                support != IndependentSupport.NOT_ESTABLISHED -> EvidenceLabel.INDEPENDENTLY_VERIFIED
                else -> unassessedLabel(claim.rawStatus)
            }
        }
        val freshness = when (claim.factClass) {
            FactClass.VERSION_PINNED -> EvidenceFreshness.VersionPinned(claim.identity)
            FactClass.CURRENT_STATE -> claim.capturedAt?.let { EvidenceFreshness.AsOf(claim.identity, it) }
                ?: EvidenceFreshness.DateUnavailable(claim.identity)
        }
        return EvidencePresentation(
            claim.rawStatus, label, applicable?.sourceCheck ?: SourceCheck.NOT_ESTABLISHED,
            support, freshness, claim.capturedAt, applicable?.reviewReference,
        )
    }
}
