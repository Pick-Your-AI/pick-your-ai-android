package ai.pickyour.core.preview

import ai.pickyour.core.contracts.ContractDecimal
import ai.pickyour.core.drafts.*
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.math.BigInteger
import java.nio.file.Path

class PreviewWorkloadInputsTest {
    @TempDir lateinit var root: Path

    @Test fun `empty input stays unanswered and entered zero and decimal spelling survive`() {
        assertEquals(DraftWorkload(), PreviewWorkloadInputs().parsed())
        val inputs = PreviewWorkloadInputs(budget = "0.0000", requests = "0", inputTokens = "9".repeat(128))
        val value = inputs.parsed()!!
        assertNull(value.currency)
        assertEquals("0.0000", value.monthlyBudgetAmount!!.encoded)
        assertEquals(BigInteger.ZERO, value.requestsPerDay)
        assertEquals(inputs, PreviewWorkloadInputs.from(value))
        assertEquals("PreviewWorkloadInputs", inputs.toString())
    }

    @Test fun `invalid raw input is retained without narrowing trimming or replacement`() {
        val invalid = listOf(PreviewWorkloadInputs(budget = "12.00", rejectedEdit = true),PreviewWorkloadInputs(currency = "usd"), PreviewWorkloadInputs(currency = "USDD"),
            PreviewWorkloadInputs(budget = "1e2"), PreviewWorkloadInputs(budget = "0.0000000000000"),
            PreviewWorkloadInputs(requests = "01"), PreviewWorkloadInputs(requests = " 1"),
            PreviewWorkloadInputs(requests = "-1"), PreviewWorkloadInputs(requests = "9".repeat(129)),
            PreviewWorkloadInputs(concurrency = "0"), PreviewWorkloadInputs(latency = "0"),
            PreviewWorkloadInputs(region = "x".repeat(16_385)))
        invalid.forEach { raw ->
            assertNull(raw.parsed())
            assertSame(raw, PreviewWorkloadCheckpoint(true, raw).forLoad(true, DraftWorkload()))
        }
    }

    @Test fun `checkpoint distinguishes absent old keys from intentionally empty pending inputs`() {
        val baseline = DraftWorkload(region = "entered", monthlyBudgetAmount = ContractDecimal.parse("12.00"))
        val empty = PreviewWorkloadInputs()
        assertSame(empty, PreviewWorkloadCheckpoint(true, empty).forLoad(true, baseline))
        assertEquals(baseline, PreviewWorkloadCheckpoint(false, empty).forLoad(true, baseline).parsed())
        assertEquals(baseline, PreviewWorkloadCheckpoint(true, empty).forLoad(false, baseline).parsed())
    }

    @Test fun `workload saves preserve context metadata and tier settings and reject stale authority`() {
        val adapter = PreviewDraftPersistence(LocalDraftStore(root))
        val first = (adapter.save(null, PreviewDraftFields("name"), PreviewDraftContext("goal", "context")) as PreviewDraftSave.Saved).record
        val workload = DraftWorkload(currency = "EUR", monthlyBudgetAmount = ContractDecimal.parse("1.2300"),
            dataClassification = DraftDataClassification.CONFIDENTIAL)
        val saved = (adapter.save(first, first.fields, workloadInput = workload) as PreviewDraftSave.Saved).record
        assertEquals(first.contextInput, saved.contextInput)
        assertEquals(first.draft.metadata, saved.draft.metadata)
        assertEquals(first.optionalTiers, saved.optionalTiers)
        assertEquals(workload, saved.workloadInput)
        assertEquals(PreviewDraftSave.Conflict, adapter.save(first, first.fields, workloadInput = DraftWorkload()))
        val preserved = (adapter.save(saved, PreviewDraftFields("renamed")) as PreviewDraftSave.Saved).record
        assertEquals(workload, preserved.workloadInput)
        val cleared = (adapter.save(preserved, preserved.fields, workloadInput = DraftWorkload()) as PreviewDraftSave.Saved).record
        assertEquals(DraftWorkload(), cleared.workloadInput)
        val restored = (adapter.restore(cleared, saved.revision) as PreviewDraftSave.Saved).record
        assertEquals(workload, restored.workloadInput)
        assertEquals(cleared.fields, restored.fields)
    }
    @Test fun `retained adapter cannot save workload after library deletion`() {
        val library = PreviewDraftLibrary(root)
        val adapter = library.openLegacy()
        val saved = (adapter.save(null, PreviewDraftFields()) as PreviewDraftSave.Saved).record
        assertEquals(PreviewLibraryDelete.Deleted, library.delete(PreviewDraftId("guest-draft"), saved))
        assertEquals(PreviewDraftSave.Failed(DraftStorageFailure.CORRUPT),
            adapter.save(saved, saved.fields, workloadInput = DraftWorkload(region = "stale")))
        assertTrue((library.list() as PreviewLibraryRead.Available).entries.isEmpty())
    }

}
