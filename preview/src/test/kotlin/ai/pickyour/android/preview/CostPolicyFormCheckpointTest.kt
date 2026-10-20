package ai.pickyour.android.preview

import android.os.Bundle
import android.os.Parcel
import ai.pickyour.core.preview.PreviewContextPolicyInput
import ai.pickyour.core.preview.PreviewCostPolicyInput
import ai.pickyour.core.preview.PreviewModelInput
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], manifest = Config.NONE)
class CostPolicyFormCheckpointTest {
    private fun checkpoint(input: PreviewCostPolicyInput = PreviewCostPolicyInput(), saving: Boolean = false) =
        CostPolicyFormCheckpoint("draft_fixture", "baseline_fixture", "selection_fixture", "cost.fixture", "1", input, saving)
    private fun outer(value: Bundle?) = Bundle().apply { putBundle(ComponentFormRecovery.KEY, value) }
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
    private fun captured(input: PreviewCostPolicyInput) =
        Bundle().also { checkpoint(input).recovery().save(it) }.let(::parcel)

    @Test fun exactDecimalStringsInvalidTextAndAllIdentityFieldsSurviveActualParcel() {
        val input = PreviewCostPolicyInput("9007199254740993.123456789012\n", "1,5", false, "unfinished\n\uD83D\uDE00")
        val restored = (ComponentFormRecovery.read(captured(input)) as ComponentFormRecovery.TypedCostPolicyPending).form
        assertEquals(input, restored.input)
        assertEquals("draft_fixture", restored.draftId)
        assertEquals("baseline_fixture", restored.baseline)
        assertEquals("selection_fixture", restored.selectionId)
        assertEquals("cost.fixture", restored.catalogItemId)
        assertEquals("1", restored.catalogItemVersion)
        assertFalse(restored.wasSaving)
        val empty = input.copy(monthlyBudgetAmount = "", perRequestBudgetAmount = null, notifyAtPct = "")
        assertEquals(empty, (ComponentFormRecovery.read(captured(empty)) as ComponentFormRecovery.TypedCostPolicyPending).form.input)
    }

    @Suppress("DEPRECATION")
    @Test fun absentFalseAndTrueBooleansRemainDistinctExactPrimitives() {
        for (cap in listOf<Boolean?>(null, false, true)) {
            val input = PreviewCostPolicyInput(enforceHardCap = cap)
            val state = captured(input)
            val bundle = requireNotNull(state.getBundle(ComponentFormRecovery.KEY))
            assertTrue(bundle.containsKey("enforceHardCap"))
            assertEquals(cap, bundle.get("enforceHardCap"))
            if (cap != null) assertTrue(bundle.get("enforceHardCap") is Boolean)
            val restored = (ComponentFormRecovery.read(state) as ComponentFormRecovery.TypedCostPolicyPending).form
            assertEquals(input, restored.input)
        }
    }

    @Test fun combinedUtf16BoundRejectsOneExtraWithoutKeepingAValidPrefix() {
        val exact = PreviewCostPolicyInput("x".repeat(ComponentFormCheckpoint.MAX_RAW_CHARACTERS - 3), "1", false, "99")
        assertEquals(exact, (ComponentFormRecovery.read(captured(exact)) as ComponentFormRecovery.TypedCostPolicyPending).form.input)
        val extra = exact.copy(perRequestBudgetAmount = "11")
        assertNull(checkpoint(extra).bundle())
        assertSame(ComponentFormRecovery.Unavailable, checkpoint(extra).recovery())
        val marker = requireNotNull(captured(extra).getBundle(ComponentFormRecovery.KEY))
        assertEquals(setOf("version", "unavailable"), marker.keySet())
        assertTrue(marker.getBoolean("unavailable"))
        val emoji = PreviewCostPolicyInput(monthlyBudgetAmount = "\uD83D\uDE00".repeat(10_000))
        assertNotNull(checkpoint(emoji).bundle())
        assertSame(ComponentFormRecovery.Unavailable, ComponentFormRecovery.read(captured(emoji.copy(notifyAtPct = "0"))))
    }

    @Test fun exactKeysAndPrimitiveTypesRejectCoercionAndMissingPresence() {
        val original = requireNotNull(checkpoint().bundle())
        val mutations: List<(Bundle) -> Unit> = listOf(
            { it.putString("version", "8") }, { it.putLong("version", 8L) },
            { it.putString("wasSaving", "false") }, { it.remove("wasSaving") },
            { it.putInt("monthlyBudgetAmount", 0) }, { it.putDouble("perRequestBudgetAmount", 1.0) },
            { it.putBoolean("notifyAtPct", false) }, { it.putString("enforceHardCap", "false") },
            { it.putInt("enforceHardCap", 0) }, { it.remove("enforceHardCap") },
            { it.remove("monthlyBudgetAmount") }, { it.remove("perRequestBudgetAmount") },
            { it.remove("notifyAtPct") }, { it.putString("extra", "not interpreted") },
        )
        for (mutate in mutations) {
            val value = Bundle(original).also(mutate)
            assertNull(CostPolicyFormCheckpoint.read(value))
            assertSame(ComponentFormRecovery.Unavailable, ComponentFormRecovery.read(parcel(outer(value))))
        }
        assertNotNull(CostPolicyFormCheckpoint.read(Bundle(original).apply { putString("monthlyBudgetAmount", "") }))
        assertNotNull(CostPolicyFormCheckpoint.read(Bundle(original).apply { putString("monthlyBudgetAmount", null) }))
    }

    @Test fun wrongVersionFormOrKindCannotSelectARecoveryProtocol() {
        val original = requireNotNull(checkpoint().bundle())
        for (version in listOf(0, 1, 2, 3, 4, 5, 6, 7, 9, Int.MAX_VALUE)) {
            val changed = Bundle(original).apply { putInt("version", version) }
            assertNull(CostPolicyFormCheckpoint.read(changed))
            assertSame(ComponentFormRecovery.Unavailable, ComponentFormRecovery.read(parcel(outer(changed))))
        }
        for (form in listOf("", "configure", "add", "configure_context_policy", "configure_model"))
            assertNull(CostPolicyFormCheckpoint.read(Bundle(original).apply { putString("form", form) }))
        for (kind in listOf("", "model", "model_provider", "COST_POLICY"))
            assertNull(CostPolicyFormCheckpoint.read(Bundle(original).apply { putString("kind", kind) }))
    }

    @Test fun capturedIdentityIsRequiredBoundedAndNeverFilledFromCurrentState() {
        val original = requireNotNull(checkpoint().bundle())
        for (key in listOf("draftId", "baseline", "selectionId", "catalogItemId", "catalogItemVersion")) {
            val maximum = if (key == "catalogItemId") 256 else 128
            for (value in listOf("", "x".repeat(maximum + 1)))
                assertNull(CostPolicyFormCheckpoint.read(Bundle(original).apply { putString(key, value) }))
            assertNull(CostPolicyFormCheckpoint.read(Bundle(original).apply { putString(key, null) }))
            assertNull(CostPolicyFormCheckpoint.read(Bundle(original).apply { remove(key) }))
            assertNull(CostPolicyFormCheckpoint.read(Bundle(original).apply { putInt(key, 1) }))
            assertNotNull(CostPolicyFormCheckpoint.read(Bundle(original).apply { putString(key, "x".repeat(maximum)) }))
        }
    }

    @Test fun existingRawContextAndModelReadersRemainSeparate() {
        val raw = """{"monthly_budget_amount":"unfinished"""
        val legacy = ComponentFormCheckpoint(ComponentFormCheckpoint.CONFIGURE, "draft_fixture", "baseline_fixture",
            "selection_fixture", null, null, null, null, "", raw, false, false)
        for (version in 1..2) {
            val value = requireNotNull(legacy.bundle()).apply { putInt("version", version) }
            val restored = ComponentFormRecovery.read(parcel(outer(value))) as ComponentFormRecovery.Pending
            assertEquals(raw, restored.form.settings)
            assertEquals(legacy.baseline, restored.form.baseline)
            assertNull(CostPolicyFormCheckpoint.read(value))
        }
        val context = ContextPolicyFormCheckpoint("draft_fixture", "baseline_fixture", "context_fixture",
            PreviewContextPolicyInput("unfinished", "0", null, "fp16"), false)
        val contextState = Bundle().also { context.recovery().save(it) }
        assertEquals(context.input, (ComponentFormRecovery.read(parcel(contextState)) as ComponentFormRecovery.TypedContextPending).form.input)
        val model = ModelFormCheckpoint("draft_fixture", "baseline_fixture", "model_fixture", "model.fixture", "1",
            PreviewModelInput(temperature = "1,5"), false)
        val modelState = Bundle().also { model.recovery().save(it) }
        assertEquals(model.input, (ComponentFormRecovery.read(parcel(modelState)) as ComponentFormRecovery.TypedModelPending).form.input)
        assertNull(CostPolicyFormCheckpoint.read(requireNotNull(context.bundle())))
        assertNull(CostPolicyFormCheckpoint.read(requireNotNull(model.bundle())))
    }

    @Test fun savingFlagAndDescriptionsDoNotGrantAuthorityOrEchoEnteredValues() {
        val input = PreviewCostPolicyInput("synthetic-private-correction", "1,5", true, "101")
        val original = checkpoint(input, saving = true)
        val state = Bundle().also { original.recovery().save(it) }
        val restored = (ComponentFormRecovery.read(parcel(state)) as ComponentFormRecovery.TypedCostPolicyPending).form
        assertTrue(restored.wasSaving)
        assertEquals(input, restored.input)
        assertEquals("CostPolicyFormCheckpoint", restored.toString())
        assertEquals("PreviewCostPolicyInput", restored.input.toString())
        for (marker in listOf(ComponentFormRecovery.Unavailable, ComponentFormRecovery.UriUserinfoExcluded)) {
            val value = Bundle().also { marker.save(it) }
            assertSame(marker, ComponentFormRecovery.read(parcel(value)))
        }
    }
}
