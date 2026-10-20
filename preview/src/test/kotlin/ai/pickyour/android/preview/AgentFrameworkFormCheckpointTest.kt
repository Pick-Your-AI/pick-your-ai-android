package ai.pickyour.android.preview

import android.os.Bundle
import android.os.Parcel
import ai.pickyour.core.preview.PreviewComputeInput
import ai.pickyour.core.preview.PreviewModelProviderInput
import ai.pickyour.core.preview.PreviewAgentFrameworkInput
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** Primitive capture in simulation; this is not installed-process evidence. */
@Suppress("DEPRECATION") // Exact primitive checks preserve the API 26 reader boundary.
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], manifest = Config.NONE)
class AgentFrameworkFormCheckpointTest {
    private fun checkpoint(input: PreviewAgentFrameworkInput = PreviewAgentFrameworkInput(), saving: Boolean = false) =
        AgentFrameworkFormCheckpoint("draft_fixture", "baseline_fixture", "selection_fixture", "agent.fixture", "1", input, saving)

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

    private fun captured(input: PreviewAgentFrameworkInput, saving: Boolean = false): Bundle =
        Bundle().also { checkpoint(input, saving).recovery().save(it) }.let(::parcel)

    private fun restored(input: PreviewAgentFrameworkInput, saving: Boolean = false): AgentFrameworkFormCheckpoint {
        val recovery = ComponentFormRecovery.read(captured(input, saving))
        assertTrue(recovery is ComponentFormRecovery.TypedAgentFrameworkPending)
        return (recovery as ComponentFormRecovery.TypedAgentFrameworkPending).form
    }

    private fun restoredBundle(value: Bundle): AgentFrameworkFormCheckpoint {
        val recovery = ComponentFormRecovery.read(parcel(outer(value)))
        assertTrue(recovery is ComponentFormRecovery.TypedAgentFrameworkPending)
        return (recovery as ComponentFormRecovery.TypedAgentFrameworkPending).form
    }

    private fun rejects(value: Bundle) {
        val state = parcel(outer(value))
        val retained = requireNotNull(state.getBundle(ComponentFormRecovery.KEY))
        assertNull(AgentFrameworkFormCheckpoint.read(retained))
        assertSame(ComponentFormRecovery.Unavailable, ComponentFormRecovery.read(state))
    }

    @Test fun allEightPresenceCombinationsAndFiveOriginalIdentitiesSurviveActualParcelRoundTrip() {
        var cases = 0
        for (count in listOf(null, "1.000e+0")) {
            for (iterations in listOf(null, "9007199254740993")) {
                for (timeout in listOf(null, "1e3")) {
                    val input = PreviewAgentFrameworkInput(count, iterations, timeout)
                    val value = restored(input)
                    assertEquals(input, value.input)
                    assertEquals("draft_fixture", value.draftId)
                    assertEquals("baseline_fixture", value.baseline)
                    assertEquals("selection_fixture", value.selectionId)
                    assertEquals("agent.fixture", value.catalogItemId)
                    assertEquals("1", value.catalogItemVersion)
                    assertFalse(value.wasSaving)
                    val encoded = requireNotNull(value.bundle())
                    for ((key, raw) in listOf("agentCount" to count, "maxIterations" to iterations, "timeoutMs" to timeout)) {
                        assertTrue(encoded.containsKey(key))
                        assertEquals(raw, encoded.get(key))
                    }
                    cases += 1
                }
            }
        }
        assertEquals(8, cases)
    }

    @Test fun suppliedEmptyMalformedAndExactNumericTextRemainsPrivatePendingInput() {
        val literals = listOf("", " ", "\t\r\n", "0", "-1", "1.5", "1,000", "unfinished",
            "9007199254740993", "1e4000", "1.000e+0", "\uD83D\uDE00")
        for (literal in literals) {
            for (input in listOf(
                PreviewAgentFrameworkInput(agentCount = literal),
                PreviewAgentFrameworkInput(maxIterations = literal),
                PreviewAgentFrameworkInput(timeoutMs = literal),
            )) {
                val value = restored(input)
                assertEquals(input, value.input)
                assertNotEquals(PreviewAgentFrameworkInput(), value.input)
            }
        }
        assertNull(restored(PreviewAgentFrameworkInput()).input.agentCount)
        assertNull(restored(PreviewAgentFrameworkInput()).input.maxIterations)
        assertNull(restored(PreviewAgentFrameworkInput()).input.timeoutMs)
    }

    @Test fun closedKeysAndExactPrimitiveTypesRejectMissingExtraAndCoercedPayloads() {
        val original = requireNotNull(checkpoint().bundle())
        assertEquals(setOf("version", "form", "kind", "draftId", "baseline", "selectionId",
            "catalogItemId", "catalogItemVersion", "agentCount", "maxIterations", "timeoutMs", "wasSaving"), original.keySet())
        for (key in original.keySet()) rejects(Bundle(original).apply { remove(key) })
        rejects(Bundle(original).apply { putString("extra", "not interpreted") })
        rejects(Bundle(original).apply { putString("settings", """{"agent_count":1}""") })
        for (key in listOf("agentCount", "maxIterations", "timeoutMs")) {
            val mutations: List<(Bundle) -> Unit> = listOf(
                { it.putInt(key, 1) }, { it.putLong(key, 1L) }, { it.putDouble(key, 1.0) },
                { it.putBoolean(key, false) }, { it.putByteArray(key, byteArrayOf(1)) },
                { it.putBundle(key, Bundle()) }, { it.putStringArrayList(key, arrayListOf("1")) },
            )
            for (mutate in mutations) rejects(Bundle(original).also(mutate))
            assertNull(requireNotNull(restoredBundle(Bundle(original).apply { putString(key, null) }).bundle()).get(key))
            assertEquals("", requireNotNull(restoredBundle(Bundle(original).apply { putString(key, "") }).bundle()).get(key))
        }
        for (key in listOf("wasSaving")) {
            val mutations: List<(Bundle) -> Unit> = listOf(
                { it.putString(key, "false") }, { it.putString(key, null) },
                { it.putInt(key, 0) }, { it.putLong(key, 0L) }, { it.putByteArray(key, byteArrayOf(0)) },
            )
            for (mutate in mutations) rejects(Bundle(original).also(mutate))
        }
    }

    @Test fun namespacedVersionFormAndKindNeverAliasAnotherRecoveryProtocol() {
        val original = requireNotNull(checkpoint().bundle())
        assertEquals("typed-agent-framework/1", original.get("version"))
        assertEquals("configure_agent_framework", original.get("form"))
        assertEquals("agent_framework", original.get("kind"))
        for (version in 1..8) rejects(Bundle(original).apply { putInt("version", version) })
        for (version in listOf("", "1", "typed-agent-framework/2", "typed-accelerator/1",
            "typed-embedding-model/1", "typed-workload/1", "typed-rag-pipeline/1", ModelProviderFormCheckpoint.VERSION)) {
            rejects(Bundle(original).apply { putString("version", version) })
        }
        rejects(Bundle(original).apply { putLong("version", 1L) })
        rejects(Bundle(original).apply { putBoolean("version", true) })
        for (form in listOf("", "configure", "add", "agentFramework", "configure_compute_profile", "modelProvider")) {
            rejects(Bundle(original).apply { putString("form", form) })
        }
        for (kind in listOf("", "AGENT_FRAMEWORK", "model", "model_provider", "compute_profile")) {
            rejects(Bundle(original).apply { putString("kind", kind) })
        }
        rejects(Bundle(original).apply { putBoolean("form", false) })
        rejects(Bundle(original).apply { putInt("kind", 1) })
        assertNull(ComponentFormCheckpoint.read(original))
        assertNull(ContextPolicyFormCheckpoint.read(original))
        assertNull(ModelFormCheckpoint.read(original))
        assertNull(ComputeFormCheckpoint.read(original))
        assertNull(ModelProviderFormCheckpoint.read(original))
        assertNull(RagPipelineFormCheckpoint.read(original))
    }

    @Test fun checkpointWriterRejectsEveryEmptyOrOversizedOriginalIdentity() {
        val identities = listOf("draft_fixture", "baseline_fixture", "selection_fixture", "agent.fixture", "1")
        for (index in identities.indices) {
            val maximum = if (index == 3) 256 else 128
            for (invalid in listOf("", "x".repeat(maximum + 1))) {
                val values = identities.toMutableList().apply { this[index] = invalid }
                val value = AgentFrameworkFormCheckpoint(values[0], values[1], values[2], values[3], values[4],
                    PreviewAgentFrameworkInput("2", "3", "1000"), false)
                assertNull(value.bundle())
                assertSame(ComponentFormRecovery.Unavailable, value.recovery())
            }
        }
    }

    @Test fun combinedTwentyThousandUtf16BudgetRejectsAnyOverflowWithoutKeepingAPrefix() {
        val exact = PreviewAgentFrameworkInput("\uD83D\uDE00".repeat(3_000), "9".repeat(7_000), "8".repeat(7_000))
        assertEquals(20_000, ComponentFormCheckpoint.MAX_RAW_CHARACTERS)
        assertEquals(20_000, exact.agentCount!!.length + exact.maxIterations!!.length + exact.timeoutMs!!.length)
        assertEquals(exact, restored(exact).input)
        val oversized = listOf(exact.copy(agentCount = requireNotNull(exact.agentCount) + "x"),
            exact.copy(maxIterations = requireNotNull(exact.maxIterations) + "x"),
            exact.copy(timeoutMs = requireNotNull(exact.timeoutMs) + "x"))
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
        for (key in listOf("agentCount", "maxIterations", "timeoutMs")) {
            val forged = requireNotNull(checkpoint(exact).bundle()).apply {
                putString(key, requireNotNull(getString(key)) + "x")
            }
            rejects(forged)
        }
    }

    @Test fun everyOriginalIdentityIsRequiredBoundedAndNeverReplacedWhileDecoding() {
        val original = requireNotNull(checkpoint(PreviewAgentFrameworkInput("2", null, "1000")).bundle())
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
            assertEquals(PreviewAgentFrameworkInput("2", null, "1000"), value.input)
        }
    }

    @Test fun legacyRawComputeProviderAndRagRecoveriesKeepTheirSeparateOriginalReaders() {
        val raw = """{"agent_count":"""
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
            assertNull(AgentFrameworkFormCheckpoint.read(requireNotNull(state.getBundle(ComponentFormRecovery.KEY))))
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
        assertNull(AgentFrameworkFormCheckpoint.read(requireNotNull(computeState.getBundle(ComponentFormRecovery.KEY))))

        val provider = ModelProviderFormCheckpoint("draft_fixture", "baseline_fixture", "selection_provider", "provider.fixture", "1",
            PreviewModelProviderInput(region = "", credentialRef = "secret://fixture/key\n"), false)
        val providerState = Bundle().also { provider.recovery().save(it) }.let(::parcel)
        val providerRecovery = ComponentFormRecovery.read(providerState)
        assertTrue(providerRecovery is ComponentFormRecovery.TypedModelProviderPending)
        assertEquals(provider.input, (providerRecovery as ComponentFormRecovery.TypedModelProviderPending).form.input)
        assertEquals("typed-model-provider/1", requireNotNull(providerState.getBundle(ComponentFormRecovery.KEY)).get("version"))
        assertNull(AgentFrameworkFormCheckpoint.read(requireNotNull(providerState.getBundle(ComponentFormRecovery.KEY))))

        val rag = RagPipelineFormCheckpoint("draft_fixture", "baseline_fixture", "selection_rag", "rag.fixture", "1",
            ai.pickyour.core.preview.PreviewRagPipelineInput("unfinished", "", false), false)
        val ragState = Bundle().also { rag.recovery().save(it) }.let(::parcel)
        val ragRecovery = ComponentFormRecovery.read(ragState)
        assertTrue(ragRecovery is ComponentFormRecovery.TypedRagPipelinePending)
        assertEquals(rag.input, (ragRecovery as ComponentFormRecovery.TypedRagPipelinePending).form.input)
        assertEquals("typed-rag-pipeline/1", requireNotNull(ragState.getBundle(ComponentFormRecovery.KEY)).get("version"))
        assertNull(AgentFrameworkFormCheckpoint.read(requireNotNull(ragState.getBundle(ComponentFormRecovery.KEY))))
    }

    @Test fun savingFlagRemainsRetainedStateAndDescriptionsNeverDiscloseInputValues() {
        val input = PreviewAgentFrameworkInput("synthetic-private-count", "synthetic-private-iterations", "synthetic-private-timeout")
        for (saving in listOf(false, true)) {
            val value = restored(input, saving)
            assertEquals(saving, value.wasSaving)
            assertEquals(input, value.input)
            assertEquals("AgentFrameworkFormCheckpoint", value.toString())
            assertEquals("PreviewAgentFrameworkInput", value.input.toString())
        }
    }
}
