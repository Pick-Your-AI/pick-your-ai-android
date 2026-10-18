package ai.pickyour.core.preview

import ai.pickyour.core.evidence.*
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import java.time.Instant

class SyntheticCatalogEvidenceTest {
    private fun fixture() = checkNotNull(javaClass.getResourceAsStream("/contracts/v1/examples/catalog.json")).use { it.readBytes() }
    private fun changed(old: String, new: String) = fixture().toString(Charsets.UTF_8).replace(old, new).toByteArray()

    @Test fun `actual bundled provenance remains synthetic and supplies no assessment`() {
        val value = requireNotNull(SyntheticCatalogEvidence.read(fixture()))
        assertEquals(SyntheticCatalogEvidence.FIXTURE_PIN, value.identity)
        assertEquals("FICTIONAL Workload", value.name)
        assertTrue(value.provenanceNote.contains("FICTIONAL TEST FIXTURE"))
        assertEquals(FrozenVerificationStatus.USER_SUPPLIED, value.presentation.rawStatus)
        assertEquals(EvidenceLabel.USER_SUPPLIED, value.presentation.label)
        assertEquals(SourceCheck.NOT_ESTABLISHED, value.presentation.sourceCheck)
        assertEquals(IndependentSupport.NOT_ESTABLISHED, value.presentation.independentSupport)
        assertNull(value.presentation.appliedReviewReference)
        assertEquals(Instant.parse("2026-10-16T09:00:00Z"), value.presentation.capturedAt)
        assertEquals(value.presentation.capturedAt, value.snapshotCapturedAt)
        assertEquals(EvidenceFreshness.VersionPinned(value.identity), value.presentation.freshness)
    }

    @Test fun `missing historical version does not select replacement`() {
        assertNull(SyntheticCatalogEvidence.read(changed("2026.10-demo", "2026.11-demo")))
        assertNull(SyntheticCatalogEvidence.read(fixture(), SyntheticCatalogEvidence.FIXTURE_PIN.copy(version = "missing")))
    }

    @Test fun `wrong snapshot and revision never fall back`() {
        assertNull(SyntheticCatalogEvidence.read(changed("catalog.fixture.1", "catalog.fixture.2")))
        assertNull(SyntheticCatalogEvidence.read(changed("\"revision\": 1", "\"revision\": 2")))
        assertNull(SyntheticCatalogEvidence.read(changed("\"revision\": 1", "\"revision\": 18446744073709551617")))
    }

    @Test fun `malformed and oversized fixtures fail closed`() {
        assertNull(SyntheticCatalogEvidence.read("{}".toByteArray()))
        assertNull(SyntheticCatalogEvidence.read(ByteArray(1_048_577)))
        assertNull(SyntheticCatalogEvidence.read(changed("user_supplied", "unsupported")))
    }

    @Test fun `raw verified fixture never invents independent or source support`() {
        val value = SyntheticCatalogEvidence.read(changed("user_supplied", "verified"))!!
        assertEquals(EvidenceLabel.VERIFICATION_UNESTABLISHED, value.presentation.label)
        assertEquals(SourceCheck.NOT_ESTABLISHED, value.presentation.sourceCheck)
        assertEquals(IndependentSupport.NOT_ESTABLISHED, value.presentation.independentSupport)
    }
}
