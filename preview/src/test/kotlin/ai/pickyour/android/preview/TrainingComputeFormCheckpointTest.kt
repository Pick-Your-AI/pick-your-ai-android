package ai.pickyour.android.preview

import android.os.Bundle
import android.os.Parcel
import ai.pickyour.core.preview.PreviewComputeInput
import ai.pickyour.core.preview.PreviewTrainingComputeInput
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], manifest = Config.NONE)
class TrainingComputeFormCheckpointTest {
    private fun checkpoint(input: PreviewTrainingComputeInput = PreviewTrainingComputeInput(), saving: Boolean = false) =
        TrainingComputeFormCheckpoint("draft_fixture", "baseline_fixture", "selection_fixture", "training.compute.fixture", "1", input, saving)
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
    private fun captured(input: PreviewTrainingComputeInput): Bundle = Bundle().also { checkpoint(input).recovery().save(it) }.let(::parcel)

    @Test fun optionalRawNumbersAndThreeBooleanStatesSurviveParcelWithoutCoercion() {
        for (spotAllowed in listOf<Boolean?>(null, false, true)) for (budget in listOf(null, "", "-2.5000", "1e+3", "unknown"))
            for (interval in listOf(null, "", "1,5", "9007199254740993")) {
            val input = PreviewTrainingComputeInput(budget, interval, spotAllowed)
            val restored = (ComponentFormRecovery.read(captured(input)) as ComponentFormRecovery.TypedTrainingComputePending).form
            assertEquals(input, restored.input)
            assertEquals("draft_fixture", restored.draftId)
            assertEquals("baseline_fixture", restored.baseline)
            assertEquals("selection_fixture", restored.selectionId)
            assertEquals("training.compute.fixture", restored.catalogItemId)
            assertEquals("1", restored.catalogItemVersion)
            assertFalse(restored.wasSaving)
        }
        assertEquals(PreviewTrainingComputeInput(), (ComponentFormRecovery.read(captured(PreviewTrainingComputeInput()))
            as ComponentFormRecovery.TypedTrainingComputePending).form.input)
    }

    @Test fun malformedNumericCorrectionsRemainInputWithoutSaveAuthority() {
        for (raw in listOf("", "unfinished", "1,5", "null", "1e", "1\\uD800")) {
            val input = PreviewTrainingComputeInput(raw, "unfinished interval", true)
            val restored = (ComponentFormRecovery.read(captured(input)) as ComponentFormRecovery.TypedTrainingComputePending).form
            assertEquals(input, restored.input)
            assertFalse(restored.wasSaving)
            assertEquals(setOf("version", "kind", "draftId", "baseline", "selectionId", "catalogItemId",
                "catalogItemVersion", "gpuHoursBudget", "checkpointIntervalMinutes", "spotAllowedSupplied", "spotAllowed", "wasSaving"),
                requireNotNull(restored.bundle()).keySet())
        }
    }

    @Test fun aggregateUtf16LimitRejectsWholeInputWithoutAPrefixFallback() {
        val budget = "-2.5"
        val exact = PreviewTrainingComputeInput(budget, "x".repeat(ComponentFormCheckpoint.MAX_RAW_CHARACTERS - budget.length), false)
        assertEquals(exact, (ComponentFormRecovery.read(captured(exact)) as ComponentFormRecovery.TypedTrainingComputePending).form.input)
        assertNull(checkpoint(exact.copy(checkpointIntervalMinutes = exact.checkpointIntervalMinutes + "x")).bundle())
        val symbols = PreviewTrainingComputeInput(checkpointIntervalMinutes = "\uD83D\uDE00".repeat(10_000))
        assertNotNull(checkpoint(symbols).bundle())
        assertSame(ComponentFormRecovery.Unavailable, checkpoint(symbols.copy(checkpointIntervalMinutes = symbols.checkpointIntervalMinutes + "x")).recovery())
    }

    @Test fun exactPrimitiveShapeRejectsMissingFieldsBooleanAliasesAndExtraAuthority() {
        val original = requireNotNull(checkpoint().bundle())
        val mutations: List<(Bundle) -> Unit> = listOf(
            { it.putInt("version", 6) }, { it.putString("version", "typed-training-compute/2") },
            { it.remove("gpuHoursBudget") }, { it.putInt("gpuHoursBudget", 1) },
            { it.remove("checkpointIntervalMinutes") }, { it.putInt("checkpointIntervalMinutes", 1) },
            { it.remove("spotAllowedSupplied") }, { it.putString("spotAllowedSupplied", "false") },
            { it.remove("spotAllowed") }, { it.putString("spotAllowed", "false") }, { it.putInt("spotAllowed", 0) },
            { it.putBoolean("spotAllowed", true) },
            { it.putString("wasSaving", "false") }, { it.remove("wasSaving") },
            { it.putString("kind", "model") }, { it.putString("command", "save") },
        )
        for (mutate in mutations) {
            val value = Bundle(original).also(mutate)
            assertNull(TrainingComputeFormCheckpoint.read(value))
            assertSame(ComponentFormRecovery.Unavailable, ComponentFormRecovery.read(parcel(outer(value))))
        }
        assertNotNull(TrainingComputeFormCheckpoint.read(Bundle(original).apply { putString("checkpointIntervalMinutes", "") }))
        assertNotNull(TrainingComputeFormCheckpoint.read(Bundle(original).apply { putBoolean("spotAllowedSupplied", true) }))
    }

    @Test fun everyIdentityRemainsRequiredAndBoundedWithoutCurrentStateSubstitution() {
        val original = requireNotNull(checkpoint().bundle())
        for (key in listOf("draftId", "baseline", "selectionId", "catalogItemId", "catalogItemVersion")) {
            val max = if (key == "catalogItemId") 256 else 128
            for (value in listOf("", "x".repeat(max + 1))) {
                assertNull(TrainingComputeFormCheckpoint.read(Bundle(original).apply { putString(key, value) }))
            }
            assertNull(TrainingComputeFormCheckpoint.read(Bundle(original).apply { putString(key, null) }))
            assertNull(TrainingComputeFormCheckpoint.read(Bundle(original).apply { remove(key) }))
            assertNull(TrainingComputeFormCheckpoint.read(Bundle(original).apply { putInt(key, 1) }))
            assertNotNull(TrainingComputeFormCheckpoint.read(Bundle(original).apply { putString(key, "x".repeat(max)) }))
        }
    }

    @Test fun legacyRawTrainingComputeAndNumericTypedComputeReadersRemainIndependent() {
        val raw = """{"gpu_hours_budget":"unfinished"""
        val legacy = ComponentFormCheckpoint(ComponentFormCheckpoint.CONFIGURE, "draft_fixture", "baseline_fixture",
            "selection_fixture", null, null, null, null, "", raw, false, false)
        for (version in 1..2) {
            val value = requireNotNull(legacy.bundle()).apply { putInt("version", version) }
            assertEquals(raw, (ComponentFormRecovery.read(parcel(outer(value))) as ComponentFormRecovery.Pending).form.settings)
            assertNull(TrainingComputeFormCheckpoint.read(value))
        }
        val compute = ComputeFormCheckpoint("draft_fixture", "baseline_fixture", "selection_compute", "compute.fixture", "1",
            PreviewComputeInput("unfinished", "", ""), false)
        val state = Bundle().also { compute.recovery().save(it) }
        assertEquals(compute.input, (ComponentFormRecovery.read(parcel(state)) as ComponentFormRecovery.TypedComputePending).form.input)
        assertNull(TrainingComputeFormCheckpoint.read(requireNotNull(compute.bundle())))
    }

    @Test fun savingFlagAndDescriptionsCarryInputWithoutClaimingAcknowledgement() {
        val input = PreviewTrainingComputeInput("-2.5", "synthetic-private-correction", false)
        val state = Bundle().also { checkpoint(input, saving = true).recovery().save(it) }
        val restored = (ComponentFormRecovery.read(parcel(state)) as ComponentFormRecovery.TypedTrainingComputePending).form
        assertTrue(restored.wasSaving)
        assertEquals(input, restored.input)
        assertEquals("TrainingComputeFormCheckpoint", restored.toString())
        assertEquals("PreviewTrainingComputeInput", restored.input.toString())
    }
}
