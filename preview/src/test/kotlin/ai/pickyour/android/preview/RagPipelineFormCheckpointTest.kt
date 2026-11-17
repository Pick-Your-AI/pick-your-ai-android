package ai.pickyour.android.preview

import android.os.Bundle
import android.os.Parcel
import ai.pickyour.core.preview.PreviewComputeInput
import ai.pickyour.core.preview.PreviewModelProviderInput
import ai.pickyour.core.preview.PreviewRagPipelineInput
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** Primitive capture in simulation; this is not installed-process evidence. */
@Suppress("DEPRECATION") // Exact primitive checks preserve the API 26 reader boundary.
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], manifest = Config.NONE)
class RagPipelineFormCheckpointTest {
    private fun checkpoint(input: PreviewRagPipelineInput = PreviewRagPipelineInput(), saving: Boolean = false) =
        RagPipelineFormCheckpoint("draft_fixture", "baseline_fixture", "selection_fixture", "rag.fixture", "1", input, saving)

    private fun outer(value: Bundle) = Bundle().apply { putBundle(ComponentFormRecovery.KEY, value) }

    private fun parcel(value: Bundle): Bundle {
        val writer = Parcel.obtain()
        val reader = Parcel.obtain()
        return try {
            writer.writeBundle(value)
            val bytes = writer.marshall()
            reader.unmarshall(bytes, 0, bytes.size)
            reader.setDataPosition(0)
            requireNotNull(reader.readBundle(javaClass.classLoader))
        } finally {
            writer.recycle()
            reader.recycle()
        }
    }

    private fun captured(input: PreviewRagPipelineInput, saving: Boolean = false): Bundle =
        Bundle().also { checkpoint(input, saving).recovery().save(it) }.let(::parcel)

    private fun restored(input: PreviewRagPipelineInput, saving: Boolean = false): RagPipelineFormCheckpoint {
        val recovery = ComponentFormRecovery.read(captured(input, saving))
        assertTrue(recovery is ComponentFormRecovery.TypedRagPipelinePending)
        return (recovery as ComponentFormRecovery.TypedRagPipelinePending).form
    }

    private fun restoredBundle(value: Bundle): RagPipelineFormCheckpoint {
        val recovery = ComponentFormRecovery.read(parcel(outer(value)))
        assertTrue(recovery is ComponentFormRecovery.TypedRagPipelinePending)
        return (recovery as ComponentFormRecovery.TypedRagPipelinePending).form
    }

    private fun rejects(value: Bundle) {
        val state = parcel(outer(value))
        val retained = requireNotNull(state.getBundle(ComponentFormRecovery.KEY))
        assertNull(RagPipelineFormCheckpoint.read(retained))
        assertSame(ComponentFormRecovery.Unavailable, ComponentFormRecovery.read(state))
    }

    @Test fun allTwelvePresenceCombinationsAndFiveOriginalIdentitiesSurviveActualParcelRoundTrip() {
        var cases = 0
        for (topK in listOf(null, "1.000e+0")) {
            for (maxChunks in listOf(null, "9007199254740993")) {
                for (rerank in listOf<Boolean?>(null, false, true)) {
                    val input = PreviewRagPipelineInput(topK, maxChunks, rerank)
                    val value = restored(input)
                    assertEquals(input, value.input)
                    assertEquals("draft_fixture", value.draftId)
                    assertEquals("baseline_fixture", value.baseline)
                    assertEquals("selection_fixture", value.selectionId)
                    assertEquals("rag.fixture", value.catalogItemId)
                    assertEquals("1", value.catalogItemVersion)
                    assertFalse(value.wasSaving)
                    val encoded = requireNotNull(value.bundle())
                    assertTrue(encoded.containsKey("topK"))
                    assertTrue(encoded.containsKey("maxChunks"))
                    assertEquals(topK, encoded.get("topK"))
                    assertEquals(maxChunks, encoded.get("maxChunks"))
                    assertEquals(rerank != null, encoded.get("rerankSupplied"))
                    assertEquals(rerank ?: false, encoded.get("rerankEnabled"))
                    cases += 1
                }
            }
        }
        assertEquals(12, cases)
    }

    @Test fun suppliedEmptyMalformedAndExactNumericTextRemainsPrivatePendingInput() {
        val literals = listOf("", " ", "\t\r\n", "0", "-1", "1.5", "1,000", "unfinished",
            "9007199254740993", "1e4000", "1.000e+0", "\uD83D\uDE00")
        for (literal in literals) {
            for (input in listOf(
                PreviewRagPipelineInput(topK = literal, rerankEnabled = false),
                PreviewRagPipelineInput(maxChunks = literal, rerankEnabled = true),
            )) {
                val value = restored(input)
                assertEquals(input, value.input)
                assertNotEquals(PreviewRagPipelineInput(), value.input)
            }
        }
        assertNull(restored(PreviewRagPipelineInput()).input.topK)
        assertNull(restored(PreviewRagPipelineInput()).input.maxChunks)
        assertNull(restored(PreviewRagPipelineInput()).input.rerankEnabled)
    }

    @Test fun closedKeysAndExactPrimitiveTypesRejectMissingExtraAndCoercedPayloads() {
        val original = requireNotNull(checkpoint().bundle())
        assertEquals(setOf("version", "form", "kind", "draftId", "baseline", "selectionId",
            "catalogItemId", "catalogItemVersion", "topK", "maxChunks",
            "rerankSupplied", "rerankEnabled", "wasSaving"), original.keySet())
        for (key in original.keySet()) rejects(Bundle(original).apply { remove(key) })
        rejects(Bundle(original).apply { putString("extra", "not interpreted") })
        rejects(Bundle(original).apply { putString("settings", """{"top_k":1}""") })
        for (key in listOf("topK", "maxChunks")) {
            val mutations: List<(Bundle) -> Unit> = listOf(
                { it.putInt(key, 1) }, { it.putLong(key, 1L) }, { it.putDouble(key, 1.0) },
                { it.putBoolean(key, false) }, { it.putByteArray(key, byteArrayOf(1)) },
                { it.putBundle(key, Bundle()) }, { it.putStringArrayList(key, arrayListOf("1")) },
            )
            for (mutate in mutations) rejects(Bundle(original).also(mutate))
            assertNull(requireNotNull(restoredBundle(Bundle(original).apply { putString(key, null) }).bundle()).get(key))
            assertEquals("", requireNotNull(restoredBundle(Bundle(original).apply { putString(key, "") }).bundle()).get(key))
        }
        for (key in listOf("rerankSupplied", "rerankEnabled", "wasSaving")) {
            val mutations: List<(Bundle) -> Unit> = listOf(
                { it.putString(key, "false") }, { it.putString(key, null) },
                { it.putInt(key, 0) }, { it.putLong(key, 0L) }, { it.putByteArray(key, byteArrayOf(0)) },
            )
            for (mutate in mutations) rejects(Bundle(original).also(mutate))
        }
    }

    @Test fun namespacedVersionFormAndKindNeverAliasAnotherRecoveryProtocol() {
        val original = requireNotNull(checkpoint().bundle())
        assertEquals("typed-rag-pipeline/1", original.get("version"))
        assertEquals("configure_rag_pipeline", original.get("form"))
        assertEquals("rag_pipeline", original.get("kind"))
        for (version in 1..6) rejects(Bundle(original).apply { putInt("version", version) })
        for (version in listOf("", "1", "typed-rag-pipeline/2", "typed-accelerator/1",
            "typed-embedding-model/1", ModelProviderFormCheckpoint.VERSION)) {
            rejects(Bundle(original).apply { putString("version", version) })
        }
        rejects(Bundle(original).apply { putLong("version", 1L) })
        rejects(Bundle(original).apply { putBoolean("version", true) })
        for (form in listOf("", "configure", "add", "ragPipeline", "configure_compute_profile", "modelProvider")) {
            rejects(Bundle(original).apply { putString("form", form) })
        }
        for (kind in listOf("", "RAG_PIPELINE", "model", "model_provider", "compute_profile")) {
            rejects(Bundle(original).apply { putString("kind", kind) })
        }
        rejects(Bundle(original).apply { putBoolean("form", false) })
        rejects(Bundle(original).apply { putInt("kind", 1) })
        assertNull(ComponentFormCheckpoint.read(original))
        assertNull(ContextPolicyFormCheckpoint.read(original))
        assertNull(ModelFormCheckpoint.read(original))
        assertNull(ComputeFormCheckpoint.read(original))
        assertNull(ModelProviderFormCheckpoint.read(original))
    }

    @Test fun rerankAbsenceFalseAndTrueRemainDistinctAndForgedAbsenceTrueIsRejected() {
        val recovered = listOf<Boolean?>(null, false, true).map { rerank ->
            val input = PreviewRagPipelineInput("2", "3", rerank)
            val value = restored(input)
            val encoded = requireNotNull(value.bundle())
            assertTrue(encoded.get("rerankSupplied") is Boolean)
            assertTrue(encoded.get("rerankEnabled") is Boolean)
            assertEquals(rerank != null, encoded.get("rerankSupplied"))
            assertEquals(rerank ?: false, encoded.get("rerankEnabled"))
            value.input.rerankEnabled
        }
        assertEquals(listOf<Boolean?>(null, false, true), recovered)
        val absent = requireNotNull(checkpoint().bundle())
        rejects(Bundle(absent).apply { putBoolean("rerankEnabled", true) })
        val suppliedTrue = requireNotNull(checkpoint(PreviewRagPipelineInput(rerankEnabled = true)).bundle())
        rejects(Bundle(suppliedTrue).apply { putBoolean("rerankSupplied", false) })
    }

    @Test fun combinedTwentyThousandUtf16BudgetRejectsEitherOverflowWithoutKeepingAPrefix() {
        val exact = PreviewRagPipelineInput("\uD83D\uDE00".repeat(5_000), "9".repeat(10_000), true)
        assertEquals(20_000, ComponentFormCheckpoint.MAX_RAW_CHARACTERS)
        assertEquals(20_000, exact.topK!!.length + exact.maxChunks!!.length)
        assertEquals(exact, restored(exact).input)
        val oversized = listOf(exact.copy(topK = requireNotNull(exact.topK) + "x"),
            exact.copy(maxChunks = requireNotNull(exact.maxChunks) + "x"))
        for (input in oversized) {
            assertSame(ComponentFormRecovery.Unavailable, checkpoint(input).recovery())
            assertNull(checkpoint(input).bundle())
            val previous = Bundle().apply { putString("unrelated", "retained") }
            checkpoint(exact).recovery().save(previous)
            checkpoint(input).recovery().save(previous)
            val state = parcel(previous)
            assertEquals("retained", state.getString("unrelated"))
            val marker = requireNotNull(state.getBundle(ComponentFormRecovery.KEY))
            assertEquals(setOf("version", "unavailable"), marker.keySet())
            assertEquals(1, marker.get("version"))
            assertEquals(true, marker.get("unavailable"))
            assertSame(ComponentFormRecovery.Unavailable, ComponentFormRecovery.read(state))
        }
        for (key in listOf("topK", "maxChunks")) {
            val forged = requireNotNull(checkpoint(exact).bundle()).apply {
                putString(key, requireNotNull(getString(key)) + "x")
            }
            rejects(forged)
        }
    }

    @Test fun everyOriginalIdentityIsRequiredBoundedAndNeverReplacedWhileDecoding() {
        val original = requireNotNull(checkpoint(PreviewRagPipelineInput("2", null, false)).bundle())
        val keys = listOf("draftId", "baseline", "selectionId", "catalogItemId", "catalogItemVersion")
        for (key in keys) {
            val maximum = if (key == "catalogItemId") 256 else 128
            for (invalid in listOf("", "x".repeat(maximum + 1))) {
                rejects(Bundle(original).apply { putString(key, invalid) })
            }
            rejects(Bundle(original).apply { putString(key, null) })
            rejects(Bundle(original).apply { remove(key) })
            rejects(Bundle(original).apply { putInt(key, 1) })
            val boundary = "x".repeat(maximum)
            val value = restoredBundle(Bundle(original).apply { putString(key, boundary) })
            val encoded = requireNotNull(value.bundle())
            assertEquals(boundary, encoded.getString(key))
            for (other in keys.filter { it != key }) assertEquals(original.get(other), encoded.get(other))
            assertEquals(PreviewRagPipelineInput("2", null, false), value.input)
        }
    }

    @Test fun legacyRawComputeAndProviderRecoveriesKeepTheirSeparateOriginalReaders() {
        val raw = """{"top_k":"""
        val legacy = ComponentFormCheckpoint(ComponentFormCheckpoint.CONFIGURE, "draft_fixture", "baseline_fixture",
            "selection_fixture", null, null, null, null, "", raw, false, false)
        for (version in 1..2) {
            val value = requireNotNull(legacy.bundle()).apply { putInt("version", version) }
            val state = parcel(outer(value))
            val recovery = ComponentFormRecovery.read(state)
            assertTrue(recovery is ComponentFormRecovery.Pending)
            val form = (recovery as ComponentFormRecovery.Pending).form
            assertEquals(raw, form.settings)
            assertEquals("baseline_fixture", form.baseline)
            assertNull(RagPipelineFormCheckpoint.read(requireNotNull(state.getBundle(ComponentFormRecovery.KEY))))
        }
        val compute = ComputeFormCheckpoint("draft_fixture", "baseline_fixture", "selection_compute", "compute.fixture", "1",
            PreviewComputeInput("unfinished", "", null), true)
        val computeState = Bundle().also { compute.recovery().save(it) }.let(::parcel)
        val computeRecovery = ComponentFormRecovery.read(computeState)
        assertTrue(computeRecovery is ComponentFormRecovery.TypedComputePending)
        val computeForm = (computeRecovery as ComponentFormRecovery.TypedComputePending).form
        assertEquals(compute.input, computeForm.input)
        assertTrue(computeForm.wasSaving)
        assertEquals(5, requireNotNull(computeState.getBundle(ComponentFormRecovery.KEY)).get("version"))
        assertNull(RagPipelineFormCheckpoint.read(requireNotNull(computeState.getBundle(ComponentFormRecovery.KEY))))

        val provider = ModelProviderFormCheckpoint("draft_fixture", "baseline_fixture", "selection_provider", "provider.fixture", "1",
            PreviewModelProviderInput(region = "", credentialRef = "secret://fixture/key\n"), false)
        val providerState = Bundle().also { provider.recovery().save(it) }.let(::parcel)
        val providerRecovery = ComponentFormRecovery.read(providerState)
        assertTrue(providerRecovery is ComponentFormRecovery.TypedModelProviderPending)
        assertEquals(provider.input, (providerRecovery as ComponentFormRecovery.TypedModelProviderPending).form.input)
        assertEquals("typed-model-provider/1", requireNotNull(providerState.getBundle(ComponentFormRecovery.KEY)).get("version"))
        assertNull(RagPipelineFormCheckpoint.read(requireNotNull(providerState.getBundle(ComponentFormRecovery.KEY))))
    }

    @Test fun savingFlagRemainsRetainedStateAndDescriptionsNeverDiscloseInputValues() {
        val input = PreviewRagPipelineInput("synthetic-private-top-k", "synthetic-private-max-chunks", true)
        for (saving in listOf(false, true)) {
            val value = restored(input, saving)
            assertEquals(saving, value.wasSaving)
            assertEquals(input, value.input)
            assertEquals("RagPipelineFormCheckpoint", value.toString())
            assertEquals("PreviewRagPipelineInput", value.input.toString())
        }
    }
}
