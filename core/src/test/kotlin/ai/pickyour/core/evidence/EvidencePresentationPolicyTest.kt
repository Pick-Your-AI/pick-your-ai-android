package ai.pickyour.core.evidence

import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import java.time.Instant

class EvidencePresentationPolicyTest {
    private val identity = EvidenceIdentity("claim.fixture", "release.1", "snapshot.fixture", 1)
    private fun claim(status: FrozenVerificationStatus = FrozenVerificationStatus.VERIFIED) =
        EvidenceClaim(identity, status, FactClass.VERSION_PINNED)

    @Test
    fun `legacy verified establishes neither independent support nor an actual source reading`() {
        val result = EvidencePresentationPolicy.present(claim())
        assertEquals(FrozenVerificationStatus.VERIFIED, result.rawStatus)
        assertEquals("verified", result.rawStatus.encoded)
        assertEquals(EvidenceLabel.VERIFICATION_UNESTABLISHED, result.label)
        assertEquals(SourceCheck.NOT_ESTABLISHED, result.sourceCheck)
        assertEquals(IndependentSupport.NOT_ESTABLISHED, result.independentSupport)
        assertNull(result.appliedReviewReference)
    }

    @Test
    fun `all raw statuses retain their separate meanings`() {
        val labels = mapOf(
            FrozenVerificationStatus.VERIFIED to EvidenceLabel.VERIFICATION_UNESTABLISHED,
            FrozenVerificationStatus.UNVERIFIED to EvidenceLabel.UNVERIFIED,
            FrozenVerificationStatus.USER_SUPPLIED to EvidenceLabel.USER_SUPPLIED,
            FrozenVerificationStatus.ESTIMATED to EvidenceLabel.ESTIMATED,
        )
        labels.forEach { (raw, label) ->
            val result = EvidencePresentationPolicy.present(claim(raw))
            assertEquals(raw, result.rawStatus)
            assertEquals(label, result.label)
        }
    }

    @Test
    fun `source checked alone does not imply independent verification`() {
        val assessment = ReviewedEvidenceAssessment(identity, "review.fixture", sourceCheck = SourceCheck.CHECKED)
        val result = EvidencePresentationPolicy.present(claim(FrozenVerificationStatus.UNVERIFIED), assessment)
        assertEquals(SourceCheck.CHECKED, result.sourceCheck)
        assertEquals(IndependentSupport.NOT_ESTABLISHED, result.independentSupport)
        assertEquals(EvidenceLabel.UNVERIFIED, result.label)
    }

    @Test
    fun `independent support requires explicit reviewed assessment not a reference alone`() {
        val onlyReference = ReviewedEvidenceAssessment(identity, "https://example.invalid/review")
        assertEquals(EvidenceLabel.VERIFICATION_UNESTABLISHED, EvidencePresentationPolicy.present(claim(), onlyReference).label)
        for (support in listOf(IndependentSupport.CORROBORATED, IndependentSupport.REPRODUCED)) {
            val assessment = onlyReference.copy(independentSupport = support)
            val result = EvidencePresentationPolicy.present(claim(), assessment)
            assertEquals(EvidenceLabel.INDEPENDENTLY_VERIFIED, result.label)
            assertEquals(support, result.independentSupport)
            assertEquals(SourceCheck.NOT_ESTABLISHED, result.sourceCheck)
            assertEquals(assessment.reviewReference, result.appliedReviewReference)
        }
    }

    @Test
    fun `reviewed support never erases estimated or user supplied origin`() {
        val assessment = ReviewedEvidenceAssessment(identity, "review.fixture", independentSupport = IndependentSupport.CORROBORATED)
        assertEquals(EvidenceLabel.ESTIMATED, EvidencePresentationPolicy.present(claim(FrozenVerificationStatus.ESTIMATED), assessment).label)
        assertEquals(EvidenceLabel.USER_SUPPLIED, EvidencePresentationPolicy.present(claim(FrozenVerificationStatus.USER_SUPPLIED), assessment).label)
    }

    @Test
    fun `assessment for another claim version or snapshot cannot verify selected evidence`() {
        val identities = listOf(identity.copy(claimId = "other"), identity.copy(version = "release.2"),
            identity.copy(snapshotId = "other"), identity.copy(snapshotRevision = 2))
        for (other in identities) {
            val assessment = ReviewedEvidenceAssessment(other, "review.fixture", SourceCheck.CHECKED, IndependentSupport.REPRODUCED)
            val result = EvidencePresentationPolicy.present(claim(), assessment)
            assertEquals(EvidenceLabel.VERIFICATION_UNESTABLISHED, result.label)
            assertEquals(SourceCheck.NOT_ESTABLISHED, result.sourceCheck)
            assertNull(result.appliedReviewReference)
        }
    }

    @Test
    fun `pinned fact retains exact identity and explicitly absent capture date`() {
        val result = EvidencePresentationPolicy.present(claim())
        assertEquals(EvidenceFreshness.VersionPinned(identity), result.freshness)
        assertNull(result.capturedAt)
    }

    @Test
    fun `current state uses its capture timestamp without elapsed age expiration`() {
        for (date in listOf("1970-01-01T00:00:00Z", "2100-01-01T00:00:00Z")) {
            val instant = Instant.parse(date)
            val input = claim().copy(factClass = FactClass.CURRENT_STATE, capturedAt = instant)
            val result = EvidencePresentationPolicy.present(input)
            assertEquals(EvidenceFreshness.AsOf(identity, instant), result.freshness)
            assertEquals(result, EvidencePresentationPolicy.present(input))
        }
    }

    @Test
    fun `current state missing date stays explicitly unavailable`() {
        assertEquals(EvidenceFreshness.DateUnavailable(identity),
            EvidencePresentationPolicy.present(claim().copy(factClass = FactClass.CURRENT_STATE)).freshness)
    }

    @Test
    fun `new capture does not mutate historical presentation or reuse its reviewed support`() {
        val old = claim().copy(factClass = FactClass.CURRENT_STATE, capturedAt = Instant.parse("2026-01-01T00:00:00Z"))
        val assessment = ReviewedEvidenceAssessment(identity, "review.fixture", independentSupport = IndependentSupport.REPRODUCED)
        val historical = EvidencePresentationPolicy.present(old, assessment)
        val latest = old.copy(identity = identity.copy(snapshotRevision = 2), capturedAt = Instant.parse("2026-02-01T00:00:00Z"))
        assertEquals(EvidenceLabel.VERIFICATION_UNESTABLISHED, EvidencePresentationPolicy.present(latest, assessment).label)
        assertEquals(historical, EvidencePresentationPolicy.present(old, assessment))
        assertEquals(EvidenceFreshness.AsOf(identity, old.capturedAt!!), historical.freshness)
    }
}
