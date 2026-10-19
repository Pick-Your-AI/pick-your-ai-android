package ai.pickyour.android.preview

import android.os.Bundle
import android.os.Parcel
import ai.pickyour.core.preview.PreviewContextPolicyInput
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], manifest = Config.NONE)
class ContextPolicyFormCheckpointTest {
    private fun input(requested: String = "", reserved: String = "1,5", truncation: String? = null, precision: String? = null) =
        PreviewContextPolicyInput(requested, reserved, truncation, precision)
    private fun checkpoint(value: PreviewContextPolicyInput = input(), saving: Boolean = false) =
        ContextPolicyFormCheckpoint("draft_fixture", "original-baseline", "selection_fixture", value, saving)
    private fun outer(value: Bundle?) = Bundle().apply { putBundle(ComponentFormRecovery.KEY, value) }

    private fun parcel(value: Bundle): Bundle {
        val writer = Parcel.obtain()
        val reader = Parcel.obtain()
        return try {
            writer.writeBundle(value)
            val bytes = writer.marshall()
            reader.unmarshall(bytes, 0, bytes.size)
            reader.setDataPosition(0)
            requireNotNull(reader.readBundle(javaClass.classLoader))
        } finally { writer.recycle(); reader.recycle() }
    }

    @Test fun primitiveTypedCheckpointRetainsInvalidRawTextAndExplicitAbsence() {
        val original = checkpoint(input("", "1,5", null, "fp16"))
        val state = Bundle()
        original.recovery().save(state)
        val restored = (ComponentFormRecovery.read(parcel(state)) as ComponentFormRecovery.TypedContextPending).form
        assertEquals(original.draftId, restored.draftId)
        assertEquals(original.baseline, restored.baseline)
        assertEquals(original.selectionId, restored.selectionId)
        assertEquals(original.input, restored.input)
        assertEquals("", restored.input.requestedContextTokens)
        assertEquals("1,5", restored.input.reservedOutputTokens)
        assertNull(restored.input.truncationStrategy)
        assertEquals("fp16", restored.input.kvCachePrecision)
        assertFalse(restored.wasSaving)
    }

    @Test fun totalRawRecoveryBudgetIncludesAllFourFieldsAndRejectsMaxPlusOne() {
        val max = ComponentFormCheckpoint.MAX_RAW_CHARACTERS
        val exact = input("x".repeat(max - 7), "0", "reject")
        assertNotNull(checkpoint(exact).bundle())
        val original = (checkpoint(exact).recovery() as ComponentFormRecovery.TypedContextPending).form
        assertEquals(exact, ContextPolicyFormCheckpoint.read(parcel(original.bundle()!!))!!.input)
        val oversized = exact.copy(requestedContextTokens = exact.requestedContextTokens + "x")
        assertNull(checkpoint(oversized).bundle())
        assertSame(ComponentFormRecovery.Unavailable, checkpoint(oversized).recovery())
        val state = Bundle()
        checkpoint(oversized).recovery().save(state)
        assertSame(ComponentFormRecovery.Unavailable, ComponentFormRecovery.read(parcel(state)))
    }

    @Test fun wrongPrimitiveTypesMissingKeysAndExtraKeysAreUnavailable() {
        val valid = requireNotNull(checkpoint().bundle())
        val mutations: List<(Bundle) -> Unit> = listOf(
            { it.putString("version", "3") },
            { it.putInt("requested", 1) },
            { it.putBoolean("reserved", false) },
            { it.putBoolean("truncation", false) },
            { it.putInt("precision", 0) },
            { it.putString("wasSaving", "false") },
            { it.remove("requested") },
            { it.remove("precision") },
            { it.putString("extra", "not interpreted") },
        )
        for (mutate in mutations) {
            val changed = Bundle(valid).also(mutate)
            assertNull(ContextPolicyFormCheckpoint.read(changed))
            assertSame(ComponentFormRecovery.Unavailable, ComponentFormRecovery.read(parcel(outer(changed))))
        }
    }

    @Test fun unsupportedFormVersionsKindsAndEnumValuesNeverReceiveDefaults() {
        val valid = requireNotNull(checkpoint().bundle())
        for (version in listOf(0, 1, 2, 4, Int.MAX_VALUE)) {
            val changed = Bundle(valid).apply { putInt("version", version) }
            assertNull(ContextPolicyFormCheckpoint.read(changed))
            assertSame(ComponentFormRecovery.Unavailable, ComponentFormRecovery.read(outer(changed)))
        }
        for (form in listOf("configure", "configure_model", "future", "")) {
            assertNull(ContextPolicyFormCheckpoint.read(Bundle(valid).apply { putString("form", form) }))
        }
        for (value in listOf("", "unknown", "Not supplied", "fp16 ", "FP16")) {
            assertNull(checkpoint(input(precision = value)).bundle())
            assertSame(ComponentFormRecovery.Unavailable, checkpoint(input(truncation = value)).recovery())
        }
    }

    @Test fun identityBoundsDoNotCoerceMissingOrOversizedIdentifiers() {
        for (value in listOf("", "x".repeat(129))) {
            assertNull(ContextPolicyFormCheckpoint(value, "baseline", "selection", input(), false).bundle())
            assertNull(ContextPolicyFormCheckpoint("draft", value, "selection", input(), false).bundle())
            assertNull(ContextPolicyFormCheckpoint("draft", "baseline", value, input(), false).bundle())
        }
        val boundary = ContextPolicyFormCheckpoint("x".repeat(128), "b".repeat(128), "s".repeat(128), input(), false)
        assertNotNull(boundary.bundle())
        assertNull(ContextPolicyFormCheckpoint.read(boundary.bundle()!!.apply { putString("draftId", null) }))
    }

    @Test fun originalV1AndV2RawConfigureCheckpointsStayRawAndByteCompatible() {
        val raw = """{"requested_context_tokens": "unfinished"""
        val legacy = ComponentFormCheckpoint(ComponentFormCheckpoint.CONFIGURE, "draft_fixture", "baseline_fixture",
            "selection_fixture", null, null, null, null, "", raw, false, false)
        for (version in listOf(1, 2)) {
            val value = requireNotNull(legacy.bundle()).apply { putInt("version", version) }
            val restored = ComponentFormRecovery.read(parcel(outer(value))) as ComponentFormRecovery.Pending
            assertEquals(raw, restored.form.settings)
            assertEquals(ComponentFormCheckpoint.CONFIGURE, restored.form.mode)
            assertNull(ContextPolicyFormCheckpoint.read(value))
            assertEquals(legacy.draftId, restored.form.draftId)
            assertEquals(legacy.baseline, restored.form.baseline)
            assertFalse(restored.form.wasSaving)
        }
        assertArrayEquals(parcel(legacy.bundle()!!).keySet().sorted().toTypedArray(),
            legacy.bundle()!!.keySet().sorted().toTypedArray())
    }

    @Test fun originalUriExclusionAndUnavailableMarkersRetainTheirDistinctMeanings() {
        for (original in listOf(ComponentFormRecovery.UriUserinfoExcluded, ComponentFormRecovery.Unavailable)) {
            val state = Bundle()
            original.save(state)
            assertSame(original, ComponentFormRecovery.read(parcel(state)))
        }
        val empty = Bundle()
        ComponentFormRecovery.None.save(empty)
        assertFalse(empty.containsKey(ComponentFormRecovery.KEY))
        assertSame(ComponentFormRecovery.None, ComponentFormRecovery.read(empty))
    }

    @Test fun inFlightFlagSurvivesWhileDescriptionsContainNoRawInput() {
        val raw = "synthetic-private-input"
        val original = checkpoint(input(raw, "0"), saving = true)
        val state = Bundle()
        original.recovery().save(state)
        val restored = (ComponentFormRecovery.read(parcel(state)) as ComponentFormRecovery.TypedContextPending).form
        assertTrue(restored.wasSaving)
        assertEquals(raw, restored.input.requestedContextTokens)
        assertFalse(restored.toString().contains(raw))
        assertFalse(restored.input.toString().contains(raw))
    }
}
