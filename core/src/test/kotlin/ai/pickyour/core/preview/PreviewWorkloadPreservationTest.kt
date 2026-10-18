package ai.pickyour.core.preview

import ai.pickyour.core.contracts.ContractDecimal
import ai.pickyour.core.drafts.*
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.math.BigInteger
import java.nio.file.Path

class PreviewWorkloadPreservationTest {
    @TempDir lateinit var root: Path

    @Test fun `guest form context and tier edits preserve workload and unknown metadata across reopen`() {
        val store = LocalDraftStore(root)
        val adapter = PreviewDraftPersistence(store)
        val workload = DraftWorkload("private region", "EUR", ContractDecimal.parse("1.2300"), BigInteger.ZERO,
            BigInteger.TEN, BigInteger.ONE, BigInteger.ONE, BigInteger.TEN, DraftDataClassification.CONFIDENTIAL)
        val original = DraftContent("objective", "context", "opaque-model", mapOf(OptionalTier.T3 to
            TierDraft(Activation.UNKNOWN, setOf("opaque"), mapOf("keep" to "0.00"))), workload)
        val metadata = mapOf("preview.format" to "1", "preview.title" to "old", "unknown-pin" to "retained")
        assertTrue(store.save(null, original, metadata) is DraftSave.Saved)
        val baseline = (adapter.load() as PreviewDraftLoad.Available).record
        val renamed = (adapter.save(baseline, PreviewDraftFields("renamed")) as PreviewDraftSave.Saved).record
        val context = (adapter.save(renamed, renamed.fields, PreviewDraftContext("", "new context")) as PreviewDraftSave.Saved).record
        val tier = (adapter.setActivation(context, OptionalTier.T3, Activation.DISABLED) as PreviewDraftSave.Saved).record
        assertEquals(workload, tier.draft.content.workload)
        assertEquals(workload, (adapter.load() as PreviewDraftLoad.Available).record.draft.content.workload)
        assertTrue(tier.history.all { it.content.workload == workload })
        assertEquals(original.primaryModel, tier.draft.content.primaryModel)
        assertEquals(original.optional.getValue(OptionalTier.T3).settings, tier.optionalTiers.getValue(OptionalTier.T3).settings)
        assertEquals("retained", tier.draft.metadata["unknown-pin"])
        assertEquals(PreviewDraftSave.Conflict, adapter.save(baseline, baseline.fields, PreviewDraftContext("stale")))
        assertEquals(tier.baselineIdentity, (adapter.load() as PreviewDraftLoad.Available).record.baselineIdentity)
        val restored = (adapter.restore(tier, 0) as PreviewDraftSave.Saved).record
        assertEquals(original, restored.draft.content)
        assertEquals(tier.fields, restored.fields)
        assertEquals(tier.draft.metadata, restored.draft.metadata)
        assertTrue(restored.compareRevision(0)!!.changes.none { it.field.startsWith("workload.") })
    }
}
