package ai.pickyour.android.preview

import android.os.Bundle
import android.os.Parcel
import ai.pickyour.core.preview.PreviewContextPolicyInput
import ai.pickyour.core.preview.PreviewModelInput
import ai.pickyour.core.preview.PreviewInferenceInput
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], manifest = Config.NONE)
class InferenceFormCheckpointTest {
    private fun checkpoint(input: PreviewInferenceInput = PreviewInferenceInput(), saving: Boolean = false) =
        InferenceFormCheckpoint("draft_fixture", "baseline_fixture", "selection_fixture", "inference.fixture", "1", input, saving)
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
    private fun captured(input: PreviewInferenceInput): Bundle = Bundle().also { checkpoint(input).recovery().save(it) }.let(::parcel)

    @Test fun allFourPrimitiveValuesExplicitAbsenceAndInvalidTextSurviveActualParcelRoundTrip() {
        val input = PreviewInferenceInput("", "unfinished", "tf32", "none")
        val restored = (ComponentFormRecovery.read(captured(input)) as ComponentFormRecovery.TypedInferencePending).form
        assertEquals(input, restored.input)
        assertEquals("draft_fixture", restored.draftId)
        assertEquals("baseline_fixture", restored.baseline)
        assertEquals("selection_fixture", restored.selectionId)
        assertEquals("inference.fixture", restored.catalogItemId)
        assertEquals("1", restored.catalogItemVersion)
        assertFalse(restored.wasSaving)
        for (mixed in listOf(PreviewInferenceInput(), input.copy(tensorParallelSize = null, quantization = null),
            PreviewInferenceInput("9007199254740993", "1.000", "fp16", "awq"))) {
            assertEquals(mixed, (ComponentFormRecovery.read(captured(mixed)) as ComponentFormRecovery.TypedInferencePending).form.input)
        }
    }

    @Test fun combinedTwentyThousandUtf16BudgetIncludesEveryFieldAndRejectsOneExtraWithoutPrefix() {
        val prefix = PreviewInferenceInput("", "1", "tf32", "awq")
        val other = listOf(prefix.maxBatchTokens, prefix.precision, prefix.quantization).sumOf { it!!.length }
        val exact = prefix.copy(tensorParallelSize = "x".repeat(ComponentFormCheckpoint.MAX_RAW_CHARACTERS - other))
        assertNotNull(checkpoint(exact).bundle())
        assertEquals(exact, (ComponentFormRecovery.read(captured(exact)) as ComponentFormRecovery.TypedInferencePending).form.input)
        assertSame(ComponentFormRecovery.Unavailable, checkpoint(exact.copy(maxBatchTokens = "11")).recovery())
        val emoji = PreviewInferenceInput(tensorParallelSize = "\uD83D\uDE00".repeat(10_000))
        assertNotNull(checkpoint(emoji).bundle())
        assertSame(ComponentFormRecovery.Unavailable, ComponentFormRecovery.read(captured(emoji.copy(maxBatchTokens = "1"))))
        val retained = captured(exact.copy(maxBatchTokens = "11")).getBundle(ComponentFormRecovery.KEY)!!
        assertEquals(setOf("version", "unavailable"), retained.keySet())
        assertTrue(retained.getBoolean("unavailable"))
    }

    @Test fun strictPrimitiveKeysNeverCoerceAbsentEmptyOrWrongTypedValues() {
        val original = requireNotNull(checkpoint().bundle())
        val mutations: List<(Bundle) -> Unit> = listOf(
            { it.putString("version", "6") }, { it.putLong("version", 6L) },
            { it.putString("wasSaving", "false") }, { it.remove("wasSaving") },
            { it.putInt("tensorParallelSize", 1) }, { it.putDouble("maxBatchTokens", 1.0) },
            { it.putBoolean("precision", false) }, { it.putByteArray("quantization", byteArrayOf(1)) },
            { it.remove("tensorParallelSize") }, { it.remove("quantization") }, { it.putString("extra", "not interpreted") },
        )
        for (mutate in mutations) {
            val value = Bundle(original).also(mutate)
            assertNull(InferenceFormCheckpoint.read(value))
            assertSame(ComponentFormRecovery.Unavailable, ComponentFormRecovery.read(parcel(outer(value))))
        }
        assertNotNull(InferenceFormCheckpoint.read(Bundle(original).apply { putString("tensorParallelSize", "") }))
        assertNotNull(InferenceFormCheckpoint.read(Bundle(original).apply { putString("tensorParallelSize", null) }))
    }

    @Test fun wrongVersionFormKindAndEnumValuesNeverSelectAnotherRecoveryProtocol() {
        val original = requireNotNull(checkpoint().bundle())
        for (version in listOf(0, 1, 2, 3, 4, 5, Int.MAX_VALUE)) {
            val changed = Bundle(original).apply { putInt("version", version) }
            assertNull(InferenceFormCheckpoint.read(changed))
            assertSame(ComponentFormRecovery.Unavailable, ComponentFormRecovery.read(outer(changed)))
        }
        for (form in listOf("", "configure", "add", "configure_model", "configure_compute_profile"))
            assertNull(InferenceFormCheckpoint.read(Bundle(original).apply { putString("form", form) }))
        for (kind in listOf("", "context_policy", "model", "compute_profile", "INFERENCE_ENGINE"))
            assertNull(InferenceFormCheckpoint.read(Bundle(original).apply { putString("kind", kind) }))
        for (value in listOf("", "unknown", "Not supplied", "TF32", "awq ")) {
            assertNull(checkpoint(PreviewInferenceInput(precision = value)).bundle())
            assertNull(checkpoint(PreviewInferenceInput(quantization = value)).bundle())
            assertSame(ComponentFormRecovery.Unavailable, ComponentFormRecovery.read(outer(Bundle(original).apply {
                putString("quantization", value)
            })))
        }
    }

    @Test fun allOriginalIdentityFieldsAreRequiredBoundedAndNeverReplacedByCurrentIdentity() {
        val original = requireNotNull(checkpoint().bundle())
        for (key in listOf("draftId", "baseline", "selectionId", "catalogItemId", "catalogItemVersion")) {
            val max = if (key == "catalogItemId") 256 else 128
            for (value in listOf("", "x".repeat(max + 1)))
                assertNull(InferenceFormCheckpoint.read(Bundle(original).apply { putString(key, value) }))
            assertNull(InferenceFormCheckpoint.read(Bundle(original).apply { putString(key, null) }))
            assertNull(InferenceFormCheckpoint.read(Bundle(original).apply { remove(key) }))
            assertNull(InferenceFormCheckpoint.read(Bundle(original).apply { putInt(key, 1) }))
            assertNotNull(InferenceFormCheckpoint.read(Bundle(original).apply { putString(key, "x".repeat(max)) }))
        }
    }

    @Test fun rawV1V2ContextV3AndModelV4RemainSeparateUnmodifiedReaders() {
        val raw = """{"tensor_parallel_size":"""
        val legacy = ComponentFormCheckpoint(ComponentFormCheckpoint.CONFIGURE, "draft_fixture", "baseline_fixture",
            "selection_fixture", null, null, null, null, "", raw, false, false)
        for (version in 1..2) {
            val bundle = requireNotNull(legacy.bundle()).apply { putInt("version", version) }
            val restored = ComponentFormRecovery.read(parcel(outer(bundle))) as ComponentFormRecovery.Pending
            assertEquals(raw, restored.form.settings)
            assertNull(InferenceFormCheckpoint.read(bundle))
            assertEquals(legacy.baseline, restored.form.baseline)
        }
        val context = ContextPolicyFormCheckpoint("draft_fixture", "baseline_fixture", "selection_context",
            PreviewContextPolicyInput("unfinished", "0", null, "fp16"), false)
        val contextState = Bundle().also { context.recovery().save(it) }
        assertEquals(context.input, (ComponentFormRecovery.read(parcel(contextState)) as ComponentFormRecovery.TypedContextPending).form.input)
        assertNull(InferenceFormCheckpoint.read(context.bundle()!!))
        val model = ModelFormCheckpoint("draft_fixture", "baseline_fixture", "selection_model", "model.fixture", "1",
            PreviewModelInput(temperature = "unfinished", precision = "tf32"), false)
        val modelState = Bundle().also { model.recovery().save(it) }
        assertEquals(model.input, (ComponentFormRecovery.read(parcel(modelState)) as ComponentFormRecovery.TypedModelPending).form.input)
        assertNull(InferenceFormCheckpoint.read(model.bundle()!!))
    }

    @Test fun savingFlagRetainsOnlyStateAndDescriptionsNeverEchoInput() {
        val input = PreviewInferenceInput(tensorParallelSize = "synthetic-private-correction")
        val original = checkpoint(input, saving = true)
        val state = Bundle().also { original.recovery().save(it) }
        val restored = (ComponentFormRecovery.read(parcel(state)) as ComponentFormRecovery.TypedInferencePending).form
        assertTrue(restored.wasSaving)
        assertEquals(input, restored.input)
        assertEquals("InferenceFormCheckpoint", restored.toString())
        assertEquals("PreviewInferenceInput", restored.input.toString())
        for (marker in listOf(ComponentFormRecovery.Unavailable, ComponentFormRecovery.UriUserinfoExcluded)) {
            val value = Bundle().also { marker.save(it) }
            assertSame(marker, ComponentFormRecovery.read(parcel(value)))
        }
    }
}
