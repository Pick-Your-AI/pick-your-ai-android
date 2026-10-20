package ai.pickyour.android.preview

import android.os.Bundle
import android.os.Parcel
import ai.pickyour.core.preview.PreviewComputeInput
import ai.pickyour.core.preview.PreviewContextPolicyInput
import ai.pickyour.core.preview.PreviewModelInput
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], manifest = Config.NONE)
class ComputeFormCheckpointTest {
    private fun checkpoint(input: PreviewComputeInput = PreviewComputeInput("1"), saving: Boolean = false) =
        ComputeFormCheckpoint("draft_fixture", "baseline_fixture", "selection_fixture", "compute.fixture", "1", input, saving)
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
    private fun captured(input: PreviewComputeInput): Bundle = Bundle().also { checkpoint(input).recovery().save(it) }.let(::parcel)

    @Test fun requiredRawTextAndOptionalAbsenceEmptyAndLiteralRegionSurviveParcel() {
        for (input in listOf(PreviewComputeInput("1"), PreviewComputeInput("", "", ""),
            PreviewComputeInput("unfinished", "1,5", " \t\r\n"), PreviewComputeInput("9007199254740993", "0", "unknown"))) {
            val restored = (ComponentFormRecovery.read(captured(input)) as ComponentFormRecovery.TypedComputePending).form
            assertEquals(input, restored.input)
            assertEquals("draft_fixture", restored.draftId)
            assertEquals("baseline_fixture", restored.baseline)
            assertEquals("selection_fixture", restored.selectionId)
            assertEquals("compute.fixture", restored.catalogItemId)
            assertEquals("1", restored.catalogItemVersion)
            assertFalse(restored.wasSaving)
        }
    }

    @Test fun totalUtf16BudgetIncludesAllThreeFieldsAndRejectsAPrefixFallback() {
        val exact = PreviewComputeInput("x".repeat(ComponentFormCheckpoint.MAX_RAW_CHARACTERS - 2), "0", " ")
        assertNotNull(checkpoint(exact).bundle())
        assertEquals(exact, (ComponentFormRecovery.read(captured(exact)) as ComponentFormRecovery.TypedComputePending).form.input)
        val oversized = exact.copy(region = "  ")
        assertSame(ComponentFormRecovery.Unavailable, checkpoint(oversized).recovery())
        assertNull(checkpoint(oversized).bundle())
        val value = captured(oversized).getBundle(ComponentFormRecovery.KEY)!!
        assertEquals(setOf("version", "unavailable"), value.keySet())
        assertTrue(value.getBoolean("unavailable"))
        val unicode = PreviewComputeInput("", region = "\uD83D\uDE00".repeat(10_000))
        assertNotNull(checkpoint(unicode).bundle())
        assertSame(ComponentFormRecovery.Unavailable, checkpoint(unicode.copy(instances = "1")).recovery())
    }

    @Test fun primitiveShapeNeverCoercesRequiredNullOptionalMissingOrNumericValues() {
        val original = requireNotNull(checkpoint().bundle())
        val mutations: List<(Bundle) -> Unit> = listOf(
            { it.putString("version", "5") }, { it.putLong("version", 5L) },
            { it.putString("wasSaving", "false") }, { it.remove("wasSaving") },
            { it.putString("instances", null) }, { it.remove("instances") }, { it.putInt("instances", 1) },
            { it.putBoolean("reservedHoursPerMonth", false) }, { it.putDouble("reservedHoursPerMonth", 0.0) },
            { it.remove("reservedHoursPerMonth") }, { it.putBundle("region", Bundle()) }, { it.remove("region") },
            { it.putString("extra", "not interpreted") },
        )
        for (mutate in mutations) {
            val value = Bundle(original).also(mutate)
            assertNull(ComputeFormCheckpoint.read(value))
            assertSame(ComponentFormRecovery.Unavailable, ComponentFormRecovery.read(parcel(outer(value))))
        }
        assertNotNull(ComputeFormCheckpoint.read(Bundle(original).apply { putString("instances", "") }))
        for (key in listOf("reservedHoursPerMonth", "region")) {
            assertNotNull(ComputeFormCheckpoint.read(Bundle(original).apply { putString(key, null) }))
            assertNotNull(ComputeFormCheckpoint.read(Bundle(original).apply { putString(key, "") }))
        }
    }

    @Test fun versionFormAndKindCannotSelectAnotherRecoveryProtocol() {
        val original = requireNotNull(checkpoint().bundle())
        for (version in listOf(0, 1, 2, 3, 4, 6, Int.MAX_VALUE)) {
            val changed = Bundle(original).apply { putInt("version", version) }
            assertNull(ComputeFormCheckpoint.read(changed))
            assertSame(ComponentFormRecovery.Unavailable, ComponentFormRecovery.read(outer(changed)))
        }
        for (form in listOf("", "configure", "add", "configure_model")) {
            assertNull(ComputeFormCheckpoint.read(Bundle(original).apply { putString("form", form) }))
        }
        for (kind in listOf("", "model", "context_policy", "COMPUTE_PROFILE")) {
            assertNull(ComputeFormCheckpoint.read(Bundle(original).apply { putString("kind", kind) }))
        }
    }

    @Test fun everyCapturedIdentityIsRequiredAndBoundedWithoutCurrentStateSubstitution() {
        val original = requireNotNull(checkpoint().bundle())
        for (key in listOf("draftId", "baseline", "selectionId", "catalogItemId", "catalogItemVersion")) {
            val max = if (key == "catalogItemId") 256 else 128
            for (value in listOf("", "x".repeat(max + 1))) {
                assertNull(ComputeFormCheckpoint.read(Bundle(original).apply { putString(key, value) }))
            }
            assertNull(ComputeFormCheckpoint.read(Bundle(original).apply { putString(key, null) }))
            assertNull(ComputeFormCheckpoint.read(Bundle(original).apply { remove(key) }))
            assertNull(ComputeFormCheckpoint.read(Bundle(original).apply { putInt(key, 1) }))
            assertNotNull(ComputeFormCheckpoint.read(Bundle(original).apply { putString(key, "x".repeat(max)) }))
        }
    }

    @Test fun legacyRawAndTypedContextAndModelReadersKeepTheirOriginalBoundaries() {
        val raw = """{"instances":"""
        val legacy = ComponentFormCheckpoint(ComponentFormCheckpoint.CONFIGURE, "draft_fixture", "baseline_fixture",
            "selection_fixture", null, null, null, null, "", raw, false, false)
        for (version in 1..2) {
            val value = requireNotNull(legacy.bundle()).apply { putInt("version", version) }
            assertEquals(raw, (ComponentFormRecovery.read(parcel(outer(value))) as ComponentFormRecovery.Pending).form.settings)
            assertNull(ComputeFormCheckpoint.read(value))
        }
        val context = ContextPolicyFormCheckpoint("draft_fixture", "baseline_fixture", "selection_context",
            PreviewContextPolicyInput("unfinished", "0", null, "fp16"), false)
        val contextState = Bundle().also { context.recovery().save(it) }
        assertEquals(context.input, (ComponentFormRecovery.read(parcel(contextState)) as ComponentFormRecovery.TypedContextPending).form.input)
        val model = ModelFormCheckpoint("draft_fixture", "baseline_fixture", "selection_model", "model.fixture", "1",
            PreviewModelInput(temperature = "1,5", credentialRef = "secret://fixture/key"), false)
        val modelState = Bundle().also { model.recovery().save(it) }
        assertEquals(model.input, (ComponentFormRecovery.read(parcel(modelState)) as ComponentFormRecovery.TypedModelPending).form.input)
        assertEquals(3, contextState.getBundle(ComponentFormRecovery.KEY)!!.getInt("version"))
        assertEquals(4, modelState.getBundle(ComponentFormRecovery.KEY)!!.getInt("version"))
        assertNull(ComputeFormCheckpoint.read(context.bundle()!!))
        assertNull(ComputeFormCheckpoint.read(model.bundle()!!))
    }

    @Test fun savingFlagRemainsOnlyRetainedStateAndDescriptionsAreValueFree() {
        val input = PreviewComputeInput("synthetic-private-correction", "1,5", "")
        val state = Bundle().also { checkpoint(input, saving = true).recovery().save(it) }
        val restored = (ComponentFormRecovery.read(parcel(state)) as ComponentFormRecovery.TypedComputePending).form
        assertTrue(restored.wasSaving)
        assertEquals(input, restored.input)
        assertEquals("ComputeFormCheckpoint", restored.toString())
        assertEquals("PreviewComputeInput", restored.input.toString())
    }
}
