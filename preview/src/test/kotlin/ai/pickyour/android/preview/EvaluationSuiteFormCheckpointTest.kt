package ai.pickyour.android.preview

import android.os.Bundle
import android.os.Parcel
import ai.pickyour.core.preview.PreviewComputeInput
import ai.pickyour.core.preview.PreviewEvaluationSuiteInput
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], manifest = Config.NONE)
class EvaluationSuiteFormCheckpointTest {
    private fun checkpoint(input: PreviewEvaluationSuiteInput = PreviewEvaluationSuiteInput(), saving: Boolean = false) =
        EvaluationSuiteFormCheckpoint("draft_fixture", "baseline_fixture", "selection_fixture", "evaluation.fixture", "1", input, saving)
    private fun outer(value: Bundle?) = Bundle().apply { putBundle(ComponentFormRecovery.KEY, value) }
    private fun parcel(value: Bundle): Bundle {
        val writer = Parcel.obtain(); val reader = Parcel.obtain()
        return try {
            writer.writeBundle(value)
            val bytes = writer.marshall()
            reader.unmarshall(bytes, 0, bytes.size); reader.setDataPosition(0)
            requireNotNull(reader.readBundle(javaClass.classLoader))
        } finally { writer.recycle(); reader.recycle() }
    }
    private fun captured(input: PreviewEvaluationSuiteInput): Bundle = Bundle().also { checkpoint(input).recovery().save(it) }.let(::parcel)

    @Test fun optionalRawNumbersAndThreeBooleanStatesSurviveParcelWithoutCoercion() {
        for (gateRequired in listOf<Boolean?>(null, false, true)) for (score in listOf(null, "", "-2.5000", "1e+3", "unknown"))
            for (size in listOf(null, "", "1,5", "9007199254740993")) {
            val input = PreviewEvaluationSuiteInput(score, size, gateRequired)
            val restored = (ComponentFormRecovery.read(captured(input)) as ComponentFormRecovery.TypedEvaluationSuitePending).form
            assertEquals(input, restored.input)
            assertEquals("draft_fixture", restored.draftId)
            assertEquals("baseline_fixture", restored.baseline)
            assertEquals("selection_fixture", restored.selectionId)
            assertEquals("evaluation.fixture", restored.catalogItemId)
            assertEquals("1", restored.catalogItemVersion)
            assertFalse(restored.wasSaving)
        }
        assertEquals(PreviewEvaluationSuiteInput(), (ComponentFormRecovery.read(captured(PreviewEvaluationSuiteInput()))
            as ComponentFormRecovery.TypedEvaluationSuitePending).form.input)
    }

    @Test fun malformedNumericCorrectionsRemainInputWithoutSaveAuthority() {
        for (raw in listOf("", "unfinished", "1,5", "null", "1e", "1\\uD800")) {
            val input = PreviewEvaluationSuiteInput(raw, "unfinished size", true)
            val restored = (ComponentFormRecovery.read(captured(input)) as ComponentFormRecovery.TypedEvaluationSuitePending).form
            assertEquals(input, restored.input)
            assertFalse(restored.wasSaving)
            assertEquals(setOf("version", "kind", "draftId", "baseline", "selectionId", "catalogItemId",
                "catalogItemVersion", "minimumScore", "datasetSize", "gateRequiredSupplied", "gateRequired", "wasSaving"),
                requireNotNull(restored.bundle()).keySet())
        }
    }

    @Test fun aggregateUtf16LimitRejectsWholeInputWithoutAPrefixFallback() {
        val score = "-2.5"
        val exact = PreviewEvaluationSuiteInput(score, "x".repeat(ComponentFormCheckpoint.MAX_RAW_CHARACTERS - score.length), false)
        assertEquals(exact, (ComponentFormRecovery.read(captured(exact)) as ComponentFormRecovery.TypedEvaluationSuitePending).form.input)
        assertNull(checkpoint(exact.copy(datasetSize = exact.datasetSize + "x")).bundle())
        val symbols = PreviewEvaluationSuiteInput(datasetSize = "\uD83D\uDE00".repeat(10_000))
        assertNotNull(checkpoint(symbols).bundle())
        assertSame(ComponentFormRecovery.Unavailable, checkpoint(symbols.copy(datasetSize = symbols.datasetSize + "x")).recovery())
    }

    @Test fun exactPrimitiveShapeRejectsMissingFieldsBooleanAliasesAndExtraAuthority() {
        val original = requireNotNull(checkpoint().bundle())
        val mutations: List<(Bundle) -> Unit> = listOf(
            { it.putInt("version", 6) }, { it.putString("version", "typed-evaluation-suite/2") },
            { it.remove("minimumScore") }, { it.putInt("minimumScore", 1) },
            { it.remove("datasetSize") }, { it.putInt("datasetSize", 1) },
            { it.remove("gateRequiredSupplied") }, { it.putString("gateRequiredSupplied", "false") },
            { it.remove("gateRequired") }, { it.putString("gateRequired", "false") }, { it.putInt("gateRequired", 0) },
            { it.putBoolean("gateRequired", true) },
            { it.putString("wasSaving", "false") }, { it.remove("wasSaving") },
            { it.putString("kind", "model") }, { it.putString("command", "save") },
        )
        for (mutate in mutations) {
            val value = Bundle(original).also(mutate)
            assertNull(EvaluationSuiteFormCheckpoint.read(value))
            assertSame(ComponentFormRecovery.Unavailable, ComponentFormRecovery.read(parcel(outer(value))))
        }
        assertNotNull(EvaluationSuiteFormCheckpoint.read(Bundle(original).apply { putString("datasetSize", "") }))
        assertNotNull(EvaluationSuiteFormCheckpoint.read(Bundle(original).apply { putBoolean("gateRequiredSupplied", true) }))
    }

    @Test fun everyIdentityRemainsRequiredAndBoundedWithoutCurrentStateSubstitution() {
        val original = requireNotNull(checkpoint().bundle())
        for (key in listOf("draftId", "baseline", "selectionId", "catalogItemId", "catalogItemVersion")) {
            val max = if (key == "catalogItemId") 256 else 128
            for (value in listOf("", "x".repeat(max + 1))) {
                assertNull(EvaluationSuiteFormCheckpoint.read(Bundle(original).apply { putString(key, value) }))
            }
            assertNull(EvaluationSuiteFormCheckpoint.read(Bundle(original).apply { putString(key, null) }))
            assertNull(EvaluationSuiteFormCheckpoint.read(Bundle(original).apply { remove(key) }))
            assertNull(EvaluationSuiteFormCheckpoint.read(Bundle(original).apply { putInt(key, 1) }))
            assertNotNull(EvaluationSuiteFormCheckpoint.read(Bundle(original).apply { putString(key, "x".repeat(max)) }))
        }
    }

    @Test fun legacyRawEvaluationSuiteAndNumericTypedComputeReadersRemainIndependent() {
        val raw = """{"minimum_score":"unfinished"""
        val legacy = ComponentFormCheckpoint(ComponentFormCheckpoint.CONFIGURE, "draft_fixture", "baseline_fixture",
            "selection_fixture", null, null, null, null, "", raw, false, false)
        for (version in 1..2) {
            val value = requireNotNull(legacy.bundle()).apply { putInt("version", version) }
            assertEquals(raw, (ComponentFormRecovery.read(parcel(outer(value))) as ComponentFormRecovery.Pending).form.settings)
            assertNull(EvaluationSuiteFormCheckpoint.read(value))
        }
        val compute = ComputeFormCheckpoint("draft_fixture", "baseline_fixture", "selection_compute", "compute.fixture", "1",
            PreviewComputeInput("unfinished", "", ""), false)
        val state = Bundle().also { compute.recovery().save(it) }
        assertEquals(compute.input, (ComponentFormRecovery.read(parcel(state)) as ComponentFormRecovery.TypedComputePending).form.input)
        assertNull(EvaluationSuiteFormCheckpoint.read(requireNotNull(compute.bundle())))
    }

    @Test fun savingFlagAndDescriptionsCarryInputWithoutClaimingAcknowledgement() {
        val input = PreviewEvaluationSuiteInput("-2.5", "synthetic-private-correction", false)
        val state = Bundle().also { checkpoint(input, saving = true).recovery().save(it) }
        val restored = (ComponentFormRecovery.read(parcel(state)) as ComponentFormRecovery.TypedEvaluationSuitePending).form
        assertTrue(restored.wasSaving)
        assertEquals(input, restored.input)
        assertEquals("EvaluationSuiteFormCheckpoint", restored.toString())
        assertEquals("PreviewEvaluationSuiteInput", restored.input.toString())
    }
}
