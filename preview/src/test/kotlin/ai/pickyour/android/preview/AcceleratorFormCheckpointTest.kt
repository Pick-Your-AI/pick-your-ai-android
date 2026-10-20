package ai.pickyour.android.preview

import android.os.Bundle
import android.os.Parcel
import ai.pickyour.core.preview.PreviewContextPolicyInput
import ai.pickyour.core.preview.PreviewModelInput
import ai.pickyour.core.preview.PreviewAcceleratorInput
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], manifest = Config.NONE)
class AcceleratorFormCheckpointTest {
    private fun checkpoint(input: PreviewAcceleratorInput = PreviewAcceleratorInput("1"), saving: Boolean = false) =
        AcceleratorFormCheckpoint("draft_fixture", "baseline_fixture", "selection_fixture", "accelerator.fixture", "1", input, saving)
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
    private fun captured(input: PreviewAcceleratorInput): Bundle = Bundle().also { checkpoint(input).recovery().save(it) }.let(::parcel)

    @Test fun requiredInvalidTextAndOptionalAbsenceRemainExactAcrossActualParcelRoundTrip() {
        for (input in listOf(PreviewAcceleratorInput(""), PreviewAcceleratorInput("unfinished", ""),
            PreviewAcceleratorInput("9007199254740993", "1.000"))) {
            val state = captured(input)
            assertEquals("typed-accelerator/1", state.getBundle(ComponentFormRecovery.KEY)!!.getString("version"))
            val restored = (ComponentFormRecovery.read(state) as ComponentFormRecovery.TypedAcceleratorPending).form
            assertEquals(input, restored.input)
            assertEquals("draft_fixture", restored.draftId)
            assertEquals("baseline_fixture", restored.baseline)
            assertEquals("selection_fixture", restored.selectionId)
            assertEquals("accelerator.fixture", restored.catalogItemId)
            assertEquals("1", restored.catalogItemVersion)
            assertFalse(restored.wasSaving)
        }
    }

    @Test fun combinedTwentyThousandUtf16BudgetIncludesBothInputsWithoutPrefixRecovery() {
        val exact = PreviewAcceleratorInput("x".repeat(ComponentFormCheckpoint.MAX_RAW_CHARACTERS - 1), "1")
        assertNotNull(checkpoint(exact).bundle())
        assertEquals(exact, (ComponentFormRecovery.read(captured(exact)) as ComponentFormRecovery.TypedAcceleratorPending).form.input)
        assertSame(ComponentFormRecovery.Unavailable, checkpoint(exact.copy(tensorParallelGroups = "11")).recovery())
        val emoji = PreviewAcceleratorInput("\uD83D\uDE00".repeat(10_000))
        assertNotNull(checkpoint(emoji).bundle())
        assertSame(ComponentFormRecovery.Unavailable, ComponentFormRecovery.read(captured(emoji.copy(tensorParallelGroups = "1"))))
        val retained = captured(exact.copy(tensorParallelGroups = "11")).getBundle(ComponentFormRecovery.KEY)!!
        assertEquals(setOf("version", "unavailable"), retained.keySet())
        assertTrue(retained.getBoolean("unavailable"))
    }

    @Test fun strictPrimitiveKeysNeverCoerceRequiredNullOptionalAbsenceOrWrongTypes() {
        val original = requireNotNull(checkpoint().bundle())
        val mutations: List<(Bundle) -> Unit> = listOf(
            { it.putString("wasSaving", "false") }, { it.remove("wasSaving") },
            { it.putInt("deviceCount", 1) }, { it.putString("deviceCount", null) },
            { it.putDouble("tensorParallelGroups", 1.0) }, { it.putBoolean("tensorParallelGroups", false) },
            { it.remove("deviceCount") }, { it.remove("tensorParallelGroups") }, { it.putString("extra", "not interpreted") },
        )
        for (mutate in mutations) {
            val value = Bundle(original).also(mutate)
            assertNull(AcceleratorFormCheckpoint.read(value))
            assertSame(ComponentFormRecovery.Unavailable, ComponentFormRecovery.read(parcel(outer(value))))
        }
        assertNotNull(AcceleratorFormCheckpoint.read(Bundle(original).apply { putString("deviceCount", "") }))
        assertNotNull(AcceleratorFormCheckpoint.read(Bundle(original).apply { putString("tensorParallelGroups", "") }))
        assertNotNull(AcceleratorFormCheckpoint.read(Bundle(original).apply { putString("tensorParallelGroups", null) }))
    }

    @Test fun namespacedVersionFormAndKindCannotSelectAnyParallelRecoveryProtocol() {
        val original = requireNotNull(checkpoint().bundle())
        val mutations: List<(Bundle) -> Unit> = (0..8).map { version -> { value: Bundle -> value.putInt("version", version) } } +
            listOf<(Bundle) -> Unit>({ it.putLong("version", 1L) }) +
            listOf("", "1", "typed-accelerator/2", "typed-model-provider/1", "typed-hosting-platform/1")
                .map { tag -> { value: Bundle -> value.putString("version", tag) } }
        for (mutate in mutations) {
            val changed = Bundle(original).also(mutate)
            assertNull(AcceleratorFormCheckpoint.read(changed))
            assertSame(ComponentFormRecovery.Unavailable, ComponentFormRecovery.read(parcel(outer(changed))))
        }
        for (form in listOf("", "configure", "add", "model", "modelProvider"))
            assertNull(AcceleratorFormCheckpoint.read(Bundle(original).apply { putString("form", form) }))
        for (kind in listOf("", "model", "compute_profile", "model_provider", "ACCELERATOR"))
            assertNull(AcceleratorFormCheckpoint.read(Bundle(original).apply { putString("kind", kind) }))
    }

    @Test fun allOriginalIdentityFieldsAreRequiredBoundedAndNeverReplacedByCurrentIdentity() {
        val original = requireNotNull(checkpoint().bundle())
        for (key in listOf("draftId", "baseline", "selectionId", "catalogItemId", "catalogItemVersion")) {
            val max = if (key == "catalogItemId") 256 else 128
            for (value in listOf("", "x".repeat(max + 1)))
                assertNull(AcceleratorFormCheckpoint.read(Bundle(original).apply { putString(key, value) }))
            assertNull(AcceleratorFormCheckpoint.read(Bundle(original).apply { putString(key, null) }))
            assertNull(AcceleratorFormCheckpoint.read(Bundle(original).apply { remove(key) }))
            assertNull(AcceleratorFormCheckpoint.read(Bundle(original).apply { putInt(key, 1) }))
            assertNotNull(AcceleratorFormCheckpoint.read(Bundle(original).apply { putString(key, "x".repeat(max)) }))
        }
    }

    @Test fun rawV1V2ContextV3AndModelV4KeepTheirIndependentUnmodifiedReaders() {
        val raw = """{"device_count":"""
        val legacy = ComponentFormCheckpoint(ComponentFormCheckpoint.CONFIGURE, "draft_fixture", "baseline_fixture",
            "selection_fixture", null, null, null, null, "", raw, false, false)
        for (version in 1..2) {
            val bundle = requireNotNull(legacy.bundle()).apply { putInt("version", version) }
            val restored = ComponentFormRecovery.read(parcel(outer(bundle))) as ComponentFormRecovery.Pending
            assertEquals(raw, restored.form.settings)
            assertNull(AcceleratorFormCheckpoint.read(bundle))
            assertEquals(legacy.baseline, restored.form.baseline)
        }
        val context = ContextPolicyFormCheckpoint("draft_fixture", "baseline_fixture", "selection_context",
            PreviewContextPolicyInput("unfinished", "0", null, "fp16"), false)
        val contextState = Bundle().also { context.recovery().save(it) }
        assertEquals(context.input, (ComponentFormRecovery.read(parcel(contextState)) as ComponentFormRecovery.TypedContextPending).form.input)
        assertNull(AcceleratorFormCheckpoint.read(context.bundle()!!))
        val model = ModelFormCheckpoint("draft_fixture", "baseline_fixture", "selection_model", "model.fixture", "1",
            PreviewModelInput(temperature = "unfinished", precision = "tf32"), false)
        val modelState = Bundle().also { model.recovery().save(it) }
        assertEquals(model.input, (ComponentFormRecovery.read(parcel(modelState)) as ComponentFormRecovery.TypedModelPending).form.input)
        assertNull(AcceleratorFormCheckpoint.read(model.bundle()!!))
    }

    @Test fun savingFlagRetainsOnlyStateAndDescriptionsNeverEchoInput() {
        val input = PreviewAcceleratorInput("synthetic-private-correction")
        val original = checkpoint(input, saving = true)
        val state = Bundle().also { original.recovery().save(it) }
        val restored = (ComponentFormRecovery.read(parcel(state)) as ComponentFormRecovery.TypedAcceleratorPending).form
        assertTrue(restored.wasSaving)
        assertEquals(input, restored.input)
        assertEquals("AcceleratorFormCheckpoint", restored.toString())
        assertEquals("PreviewAcceleratorInput", restored.input.toString())
        for (marker in listOf(ComponentFormRecovery.Unavailable, ComponentFormRecovery.UriUserinfoExcluded)) {
            val value = Bundle().also { marker.save(it) }
            assertSame(marker, ComponentFormRecovery.read(parcel(value)))
        }
    }
}
