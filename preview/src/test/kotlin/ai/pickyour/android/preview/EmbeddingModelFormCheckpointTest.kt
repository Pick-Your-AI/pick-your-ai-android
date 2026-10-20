package ai.pickyour.android.preview

import android.os.Bundle
import android.os.Parcel
import ai.pickyour.core.preview.PreviewContextPolicyInput
import ai.pickyour.core.preview.PreviewEmbeddingModelInput
import ai.pickyour.core.preview.PreviewModelInput
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], manifest = Config.NONE)
class EmbeddingModelFormCheckpointTest {
    private fun checkpoint(input: PreviewEmbeddingModelInput = PreviewEmbeddingModelInput(), saving: Boolean = false) =
        EmbeddingModelFormCheckpoint("draft_fixture", "baseline_fixture", "selection_fixture", "embedding.fixture", "1", input, saving)
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
    private fun captured(input: PreviewEmbeddingModelInput): Bundle = Bundle().also { checkpoint(input).recovery().save(it) }.let(::parcel)

    @Test fun exactValuesAbsentAndSuppliedEmptyRegionSurviveParcelRoundTrip() {
        for (input in listOf(PreviewEmbeddingModelInput(),
            PreviewEmbeddingModelInput("secret://fixture/ref\n", "1,5", ""),
            PreviewEmbeddingModelInput(null, "", " \t\r\n"),
            PreviewEmbeddingModelInput("secret://fixture/Case:Exact-1", "9007199254740993", null))) {
            val state = captured(input)
            val restored = (ComponentFormRecovery.read(state) as ComponentFormRecovery.TypedEmbeddingModelPending).form
            assertEquals(input, restored.input)
            assertEquals("draft_fixture", restored.draftId)
            assertEquals("baseline_fixture", restored.baseline)
            assertEquals("selection_fixture", restored.selectionId)
            assertEquals("embedding.fixture", restored.catalogItemId)
            assertEquals("1", restored.catalogItemVersion)
            assertFalse(restored.wasSaving)
            assertEquals("typed-embedding-model/1", state.getBundle(ComponentFormRecovery.KEY)!!.getString("version"))
        }
    }

    @Test fun combinedUtf16BudgetIncludesAllThreeFieldsWithoutKeepingAPrefix() {
        val prefix = PreviewEmbeddingModelInput("secret://fixture/ref", "unfinished", "")
        val other = prefix.credentialRef!!.length + prefix.outputDimensions!!.length
        val exact = prefix.copy(region = "x".repeat(ComponentFormCheckpoint.MAX_RAW_CHARACTERS - other))
        assertNotNull(checkpoint(exact).bundle())
        assertEquals(exact, (ComponentFormRecovery.read(captured(exact)) as ComponentFormRecovery.TypedEmbeddingModelPending).form.input)
        val oversized = exact.copy(region = exact.region + "x")
        assertNull(checkpoint(oversized).bundle())
        val value = captured(oversized).getBundle(ComponentFormRecovery.KEY)!!
        assertEquals(setOf("version", "unavailable"), value.keySet())
        assertTrue(value.getBoolean("unavailable"))
        assertNotNull(checkpoint(PreviewEmbeddingModelInput(region = "\uD83D\uDE00".repeat(10_000))).bundle())
        assertNull(checkpoint(PreviewEmbeddingModelInput(outputDimensions = "1", region = "\uD83D\uDE00".repeat(10_000))).bundle())
    }

    @Test fun strictPrimitiveShapeNeverCoercesMissingEmptyAndWrongTypedValues() {
        val original = requireNotNull(checkpoint().bundle())
        val mutations: List<(Bundle) -> Unit> = listOf(
            { it.putInt("version", 1) }, { it.putLong("version", 1L) },
            { it.putString("wasSaving", "false") }, { it.remove("wasSaving") },
            { it.putByteArray("credentialRef", byteArrayOf(1)) }, { it.putDouble("outputDimensions", 1.0) },
            { it.putBoolean("region", false) }, { it.remove("credentialRef") },
            { it.remove("outputDimensions") }, { it.remove("region") }, { it.putString("extra", "not interpreted") },
        )
        for (mutate in mutations) {
            val changed = Bundle(original).also(mutate)
            assertNull(EmbeddingModelFormCheckpoint.read(changed))
            assertSame(ComponentFormRecovery.Unavailable, ComponentFormRecovery.read(parcel(outer(changed))))
        }
        assertNotNull(EmbeddingModelFormCheckpoint.read(Bundle(original).apply { putString("region", "") }))
        assertNotNull(EmbeddingModelFormCheckpoint.read(Bundle(original).apply { putString("region", null) }))
        assertNotNull(EmbeddingModelFormCheckpoint.read(Bundle(original).apply { putString("outputDimensions", "") }))
    }

    @Test fun foreignNamespacesVersionsFormsAndKindsCannotSelectTheEmbeddingCodec() {
        val original = requireNotNull(checkpoint().bundle())
        for (version in listOf("", "1", "typed-embedding-model/2", "typed-accelerator/1", "typed-model-provider/1",
            "typed-hosting-platform/1", "typed-storage/1", "typed-knowledge-base/1", "typed-workload/1")) {
            val changed = Bundle(original).apply { putString("version", version) }
            assertNull(EmbeddingModelFormCheckpoint.read(changed))
            assertSame(ComponentFormRecovery.Unavailable, ComponentFormRecovery.read(parcel(outer(changed))))
        }
        for (version in 1..8) assertNull(EmbeddingModelFormCheckpoint.read(Bundle(original).apply { putInt("version", version) }))
        for (form in listOf("", "configure", "add", "configure_model")) {
            assertNull(EmbeddingModelFormCheckpoint.read(Bundle(original).apply { putString("form", form) }))
        }
        for (kind in listOf("", "model", "model_provider", "EMBEDDING_MODEL")) {
            assertNull(EmbeddingModelFormCheckpoint.read(Bundle(original).apply { putString("kind", kind) }))
        }
    }

    @Test fun exactOriginalIdentitiesAreRequiredBoundedAndNeverReplaced() {
        val original = requireNotNull(checkpoint().bundle())
        for (key in listOf("draftId", "baseline", "selectionId", "catalogItemId", "catalogItemVersion")) {
            val max = if (key == "catalogItemId") 256 else 128
            for (value in listOf("", "x".repeat(max + 1))) assertNull(EmbeddingModelFormCheckpoint.read(Bundle(original).apply { putString(key, value) }))
            assertNull(EmbeddingModelFormCheckpoint.read(Bundle(original).apply { putString(key, null) }))
            assertNull(EmbeddingModelFormCheckpoint.read(Bundle(original).apply { remove(key) }))
            assertNull(EmbeddingModelFormCheckpoint.read(Bundle(original).apply { putInt(key, 1) }))
            assertNotNull(EmbeddingModelFormCheckpoint.read(Bundle(original).apply { putString(key, "x".repeat(max)) }))
        }
    }

    @Test fun invalidCredentialsNeverEnterCaptureOrForgedReadEvenWithOtherInvalidFields() {
        for (credential in listOf("", "synthetic-password", "secret://", "secret://fixture/@bad", "secret://fixture/ref\r")) {
            val input = PreviewEmbeddingModelInput(credential, "unfinished", "")
            assertSame(ComponentFormRecovery.Unavailable, checkpoint(input).recovery())
            assertNull(checkpoint(input).bundle())
            val value = captured(input).getBundle(ComponentFormRecovery.KEY)!!
            assertEquals(setOf("version", "unavailable"), value.keySet())
            assertTrue(value.getBoolean("unavailable"))
            val forged = requireNotNull(checkpoint().bundle()).apply { putString("credentialRef", credential) }
            assertNull(EmbeddingModelFormCheckpoint.read(forged))
            assertSame(ComponentFormRecovery.Unavailable, ComponentFormRecovery.read(parcel(outer(forged))))
        }
        for (reference in listOf(null, "secret://fixture/Case:Exact-1", "secret://fixture/ref\n")) {
            val input = PreviewEmbeddingModelInput(reference, "unfinished", "")
            assertEquals(input, (ComponentFormRecovery.read(captured(input)) as ComponentFormRecovery.TypedEmbeddingModelPending).form.input)
        }
    }

    @Test fun rawAndExistingTypedProtocolsKeepTheirOwnReadersAndValues() {
        val raw = """{"credential_ref":"synthetic-historical-text","unfinished":"""
        val legacy = ComponentFormCheckpoint(ComponentFormCheckpoint.CONFIGURE, "draft_fixture", "baseline_fixture",
            "selection_fixture", null, null, null, null, "", raw, false, false)
        for (version in 1..2) {
            val bundle = requireNotNull(legacy.bundle()).apply { putInt("version", version) }
            assertEquals(raw, (ComponentFormRecovery.read(parcel(outer(bundle))) as ComponentFormRecovery.Pending).form.settings)
            assertNull(EmbeddingModelFormCheckpoint.read(bundle))
        }
        val context = ContextPolicyFormCheckpoint("draft_fixture", "baseline_fixture", "selection_context",
            PreviewContextPolicyInput("unfinished", "0", null, "fp16"), false)
        assertTrue(ComponentFormRecovery.read(parcel(Bundle().also { context.recovery().save(it) })) is ComponentFormRecovery.TypedContextPending)
        val model = ModelFormCheckpoint("draft_fixture", "baseline_fixture", "selection_model", "model.fixture", "1",
            PreviewModelInput(temperature = "unfinished"), false)
        val restored = ComponentFormRecovery.read(parcel(Bundle().also { model.recovery().save(it) })) as ComponentFormRecovery.TypedModelPending
        assertEquals(model.input, restored.form.input)
        assertNull(EmbeddingModelFormCheckpoint.read(context.bundle()!!))
        assertNull(EmbeddingModelFormCheckpoint.read(model.bundle()!!))
    }

    @Test fun savingIsOnlyRetainedStateAndDescriptionsNeverEchoInput() {
        val input = PreviewEmbeddingModelInput("secret://fixture/private", "synthetic-private-correction", "")
        val original = checkpoint(input, saving = true)
        val restored = (ComponentFormRecovery.read(parcel(Bundle().also { original.recovery().save(it) }))
            as ComponentFormRecovery.TypedEmbeddingModelPending).form
        assertTrue(restored.wasSaving)
        assertEquals(input, restored.input)
        assertEquals("EmbeddingModelFormCheckpoint", restored.toString())
        assertEquals("PreviewEmbeddingModelInput", restored.input.toString())
        for (marker in listOf(ComponentFormRecovery.Unavailable, ComponentFormRecovery.UriUserinfoExcluded)) {
            assertSame(marker, ComponentFormRecovery.read(parcel(Bundle().also { marker.save(it) })))
        }
    }
}
