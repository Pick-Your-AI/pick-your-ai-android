package ai.pickyour.android.preview

import android.os.Bundle
import android.os.Parcel
import ai.pickyour.core.preview.PreviewContextPolicyInput
import ai.pickyour.core.preview.PreviewModelInput
import ai.pickyour.core.preview.PreviewWorkloadSelectionInput
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class WorkloadSelectionFormCheckpointTest {
    private fun checkpoint(input: PreviewWorkloadSelectionInput = PreviewWorkloadSelectionInput(), saving: Boolean = false) =
        WorkloadSelectionFormCheckpoint("draft", "baseline", "selection", "item", "version", input, saving)
    private fun state(value: Bundle) = Bundle().apply { putBundle(ComponentFormRecovery.KEY, value) }
    private fun parcel(value: Bundle): Bundle {
        val writer = Parcel.obtain()
        val reader = Parcel.obtain()
        return try {
            writer.writeBundle(value)
            val bytes = writer.marshall()
            reader.unmarshall(bytes, 0, bytes.size); reader.setDataPosition(0)
            requireNotNull(reader.readBundle(javaClass.classLoader))
        } finally { writer.recycle(); reader.recycle() }
    }
    private fun restored(value: WorkloadSelectionFormCheckpoint): WorkloadSelectionFormCheckpoint {
        val saved = Bundle().also { value.recovery().save(it) }
        return (ComponentFormRecovery.read(parcel(saved)) as ComponentFormRecovery.TypedWorkloadSelectionPending).form
    }
    private val textKeys = listOf("concurrency", "requestsPerDay", "averageInputTokens", "averageOutputTokens", "latencyBudgetMs")

    @Test fun exactLargeAndInvalidTextAndAllCapturedPinsSurviveActualParcel() {
        val input = PreviewWorkloadSelectionInput("900719925474099312345678", "", "1,5", "unknown\n", "未確定\uD800")
        val before = checkpoint(input)
        val after = restored(before)
        assertEquals(input, after.input)
        assertEquals(before.draftId, after.draftId)
        assertEquals(before.baseline, after.baseline)
        assertEquals(before.selectionId, after.selectionId)
        assertEquals(before.catalogItemId, after.catalogItemId)
        assertEquals(before.catalogItemVersion, after.catalogItemVersion)
        assertFalse(after.wasSaving)
        assertEquals("typed-workload/1", after.bundle()!!.getString("version"))
    }

    @Test fun absentEmptyAndSuppliedZeroRemainDistinctExactPrimitiveStates() {
        for (input in listOf(PreviewWorkloadSelectionInput(), PreviewWorkloadSelectionInput("", "", "", "", ""),
            PreviewWorkloadSelectionInput("1", "0", "0", "0", "1"))) {
            val value = checkpoint(input)
            assertEquals(input, restored(value).input)
            val bundle = requireNotNull(value.bundle())
            assertTrue(bundle.keySet().containsAll(textKeys))
            if (input == PreviewWorkloadSelectionInput()) textKeys.forEach { assertNull(bundle.getString(it)) }
        }
    }

    @Test fun combinedUtf16BoundaryRejectsOneExtraWithoutKeepingAPartialInput() {
        val part = "9".repeat(ComponentFormCheckpoint.MAX_RAW_CHARACTERS / 5)
        val exact = PreviewWorkloadSelectionInput(part, part, part, part, part)
        assertEquals(exact, restored(checkpoint(exact)).input)
        val excess = checkpoint(exact.copy(latencyBudgetMs = part + "0"))
        assertNull(excess.bundle())
        assertSame(ComponentFormRecovery.Unavailable, excess.recovery())
        val saved = Bundle().also { excess.recovery().save(it) }
        val marker = requireNotNull(parcel(saved).getBundle(ComponentFormRecovery.KEY))
        assertEquals(setOf("version", "unavailable"), marker.keySet())
        assertSame(ComponentFormRecovery.Unavailable, ComponentFormRecovery.read(parcel(saved)))
    }

    @Test fun exactKeysAndNullableStringPrimitivesRejectCoercionMissingKeysAndExtras() {
        val good = requireNotNull(checkpoint().bundle())
        for (key in good.keySet()) {
            val missing = Bundle(good).apply { remove(key) }
            assertNull(WorkloadSelectionFormCheckpoint.read(parcel(missing)))
            assertSame(ComponentFormRecovery.Unavailable, ComponentFormRecovery.read(parcel(state(missing))))
        }
        val extra = Bundle(good).apply { putString("extra", "synthetic") }
        assertNull(WorkloadSelectionFormCheckpoint.read(parcel(extra)))
        for (key in textKeys) {
            for (mutation in listOf<(Bundle) -> Unit>({ it.putInt(key, 0) }, { it.putBoolean(key, false) },
                { it.putDouble(key, 1.0) }, { it.putBundle(key, Bundle()) })) {
                val wrong = Bundle(good).also(mutation)
                assertNull(WorkloadSelectionFormCheckpoint.read(parcel(wrong)))
            }
        }
        assertNull(WorkloadSelectionFormCheckpoint.read(Bundle(good).apply { putString("wasSaving", "false") }))
    }

    @Test fun wrongStringOrNumericVersionFormAndKindCannotSelectARecoveryProtocol() {
        val good = requireNotNull(checkpoint().bundle())
        for (key in listOf("version", "form", "kind")) {
            val bad = Bundle(good).apply { putString(key, "other_protocol") }
            assertSame(ComponentFormRecovery.Unavailable, ComponentFormRecovery.read(parcel(state(bad))))
        }
        for (version in listOf(1, 2, 3, 4, 8, 9)) {
            val bad = Bundle(good).apply { putInt("version", version) }
            assertSame(ComponentFormRecovery.Unavailable, ComponentFormRecovery.read(parcel(state(bad))))
        }
        for (version in listOf("typed-workload/2", "typed-storage/1", "typed-knowledge-base/1")) {
            assertSame(ComponentFormRecovery.Unavailable, ComponentFormRecovery.read(parcel(state(Bundle(good).apply {
                putString("version", version)
            }))))
        }
    }

    @Test fun capturedIdentityIsRequiredBoundedAndNeverFilledFromAnotherState() {
        val good = requireNotNull(checkpoint().bundle())
        for ((key, limit) in listOf("draftId" to 128, "baseline" to 128, "selectionId" to 128,
            "catalogItemId" to 256, "catalogItemVersion" to 128)) {
            for (bad in listOf("", "x".repeat(limit + 1))) {
                assertNull(WorkloadSelectionFormCheckpoint.read(parcel(Bundle(good).apply { putString(key, bad) })))
            }
            assertNull(WorkloadSelectionFormCheckpoint.read(Bundle(good).apply { putString(key, null) }))
            val exact = requireNotNull(WorkloadSelectionFormCheckpoint.read(parcel(Bundle(good).apply {
                putString(key, "x".repeat(limit))
            })))
            assertEquals("x".repeat(limit), requireNotNull(exact.bundle()).getString(key))
        }
    }

    @Test fun existingRawContextAndModelReadersRemainSeparate() {
        val raw = ComponentFormCheckpoint(ComponentFormCheckpoint.CONFIGURE, "draft", "baseline", "selection",
            null, null, null, null, "", "{unfinished", false, false)
        for (version in 1..2) {
            val value = requireNotNull(raw.bundle()).apply { putInt("version", version) }
            val read = ComponentFormRecovery.read(parcel(state(value))) as ComponentFormRecovery.Pending
            assertEquals("{unfinished", read.form.settings)
        }
        val context = ContextPolicyFormCheckpoint("draft", "baseline", "selection", PreviewContextPolicyInput("1", "0", null, null), false)
        val contextState = Bundle().also { context.recovery().save(it) }
        assertTrue(ComponentFormRecovery.read(parcel(contextState)) is ComponentFormRecovery.TypedContextPending)
        val model = ModelFormCheckpoint("draft", "baseline", "selection", "item", "version", PreviewModelInput(), false)
        val modelState = Bundle().also { model.recovery().save(it) }
        assertTrue(ComponentFormRecovery.read(parcel(modelState)) is ComponentFormRecovery.TypedModelPending)
    }

    @Test fun savingFlagAndDescriptionsRetainInputWithoutGrantingOrEchoingAuthority() {
        val input = PreviewWorkloadSelectionInput("synthetic-private-input", null, "", "unknown", "pending")
        val before = checkpoint(input, true)
        val after = restored(before)
        assertTrue(after.wasSaving)
        assertEquals(input, after.input)
        for (text in listOf(before.toString(), after.toString(), input.toString(), before.recovery().toString())) {
            assertFalse(text.contains("synthetic-private-input"))
            assertFalse(text.contains("unknown"))
        }
        val empty = Bundle().also { ComponentFormRecovery.None.save(it) }
        assertFalse(empty.containsKey(ComponentFormRecovery.KEY))
    }
}
