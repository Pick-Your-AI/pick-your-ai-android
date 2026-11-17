package ai.pickyour.android.preview

import android.os.Bundle
import android.os.Parcel
import ai.pickyour.core.preview.PreviewComputeInput
import ai.pickyour.core.preview.PreviewDeploymentTargetInput
import ai.pickyour.core.preview.PreviewModelProviderInput
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], manifest = Config.NONE)
class DeploymentTargetFormCheckpointTest {
    private fun checkpoint(input: PreviewDeploymentTargetInput = PreviewDeploymentTargetInput(), saving: Boolean = false) =
        DeploymentTargetFormCheckpoint("draft_fixture", "baseline_fixture", "selection_fixture", "deployment.fixture", "1", input, saving)
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
    private fun captured(input: PreviewDeploymentTargetInput): Bundle =
        Bundle().also { checkpoint(input).recovery().save(it) }.let(::parcel)

    @Test fun omissionExplicitZeroInvalidNumericTextAndLiteralStringsSurviveParcel() {
        for (input in listOf(PreviewDeploymentTargetInput(),
            PreviewDeploymentTargetInput("production", "0", "1", "", ""),
            PreviewDeploymentTargetInput("staging", "unfinished", "1,5", " \t\r\n", "unknown"),
            PreviewDeploymentTargetInput("development", "9007199254740993", "9223372036854775808", "日本語🙂", "unverified\nreference"))) {
            val restored = (ComponentFormRecovery.read(captured(input)) as ComponentFormRecovery.TypedDeploymentTargetPending).form
            assertEquals(input, restored.input)
            assertEquals("draft_fixture", restored.draftId)
            assertEquals("baseline_fixture", restored.baseline)
            assertEquals("selection_fixture", restored.selectionId)
            assertEquals("deployment.fixture", restored.catalogItemId)
            assertEquals("1", restored.catalogItemVersion)
            assertFalse(restored.wasSaving)
        }
    }

    @Test fun totalUtf16BudgetIncludesEveryFieldAndNeverRetainsAnOversizedPrefix() {
        val exact = PreviewDeploymentTargetInput("production", "0", "1",
            "x".repeat(ComponentFormCheckpoint.MAX_RAW_CHARACTERS - 13), " ")
        assertNotNull(checkpoint(exact).bundle())
        assertEquals(exact, (ComponentFormRecovery.read(captured(exact)) as ComponentFormRecovery.TypedDeploymentTargetPending).form.input)
        val oversized = exact.copy(approvalTicket = "  ")
        assertSame(ComponentFormRecovery.Unavailable, checkpoint(oversized).recovery())
        assertNull(checkpoint(oversized).bundle())
        val marker = captured(oversized).getBundle(ComponentFormRecovery.KEY)!!
        assertEquals(setOf("version", "unavailable"), marker.keySet())
        assertTrue(marker.getBoolean("unavailable"))
        val unicode = PreviewDeploymentTargetInput(region = "\uD83D\uDE00".repeat(10_000))
        assertNotNull(checkpoint(unicode).bundle())
        assertSame(ComponentFormRecovery.Unavailable, checkpoint(unicode.copy(replicasMin = "0")).recovery())
    }

    @Test fun closedPrimitiveShapeRejectsMissingOptionalKeysAndTypeCoercions() {
        val original = requireNotNull(checkpoint().bundle())
        val keys = listOf("environment", "replicasMin", "replicasMax", "region", "approvalTicket")
        for (key in keys) {
            for (mutate in listOf<(Bundle) -> Unit>({ it.remove(key) }, { it.putInt(key, 0) },
                { it.putBoolean(key, false) }, { it.putBundle(key, Bundle()) })) {
                val value = Bundle(original).also(mutate)
                assertNull(DeploymentTargetFormCheckpoint.read(value))
                assertSame(ComponentFormRecovery.Unavailable, ComponentFormRecovery.read(parcel(outer(value))))
            }
            assertNotNull(DeploymentTargetFormCheckpoint.read(Bundle(original).apply { putString(key, null) }))
            if (key != "environment") {
                assertNotNull(DeploymentTargetFormCheckpoint.read(Bundle(original).apply { putString(key, "") }))
            }
        }
        for (mutate in listOf<(Bundle) -> Unit>({ it.remove("wasSaving") }, { it.putString("wasSaving", "false") },
            { it.putString("extra", "not interpreted") })) {
            assertNull(DeploymentTargetFormCheckpoint.read(Bundle(original).also(mutate)))
        }
    }

    @Test fun unknownEnvironmentCannotReconstructANativeChoiceOrGrantAuthority() {
        for (environment in listOf("", "unknown", "not_applicable", "Production", "production\n")) {
            assertSame(ComponentFormRecovery.Unavailable, checkpoint(PreviewDeploymentTargetInput(environment = environment)).recovery())
            val value = checkpoint().bundle()!!.apply { putString("environment", environment) }
            assertNull(DeploymentTargetFormCheckpoint.read(value))
            assertSame(ComponentFormRecovery.Unavailable, ComponentFormRecovery.read(parcel(outer(value))))
        }
    }

    @Test fun versionFormAndKindCannotSelectAnUnrelatedRecoveryProtocol() {
        val original = requireNotNull(checkpoint().bundle())
        for (version in listOf("", "typed-deployment-target/2", "typed-model-provider/1", "8")) {
            val value = Bundle(original).apply { putString("version", version) }
            assertNull(DeploymentTargetFormCheckpoint.read(value))
            assertSame(ComponentFormRecovery.Unavailable, ComponentFormRecovery.read(outer(value)))
        }
        for (version in listOf(1, 2, 5, 8, Int.MAX_VALUE)) {
            assertNull(DeploymentTargetFormCheckpoint.read(Bundle(original).apply { putInt("version", version) }))
        }
        for (form in listOf("", "configure", "add", "modelProvider")) {
            assertNull(DeploymentTargetFormCheckpoint.read(Bundle(original).apply { putString("form", form) }))
        }
        for (kind in listOf("", "model", "deployment_mode", "DEPLOYMENT_TARGET")) {
            assertNull(DeploymentTargetFormCheckpoint.read(Bundle(original).apply { putString("kind", kind) }))
        }
    }

    @Test fun everyOriginalIdentityRemainsRequiredBoundedAndUnsubstituted() {
        val original = requireNotNull(checkpoint().bundle())
        for (key in listOf("draftId", "baseline", "selectionId", "catalogItemId", "catalogItemVersion")) {
            val max = if (key == "catalogItemId") 256 else 128
            for (value in listOf("", "x".repeat(max + 1))) {
                assertNull(DeploymentTargetFormCheckpoint.read(Bundle(original).apply { putString(key, value) }))
            }
            assertNull(DeploymentTargetFormCheckpoint.read(Bundle(original).apply { putString(key, null) }))
            assertNull(DeploymentTargetFormCheckpoint.read(Bundle(original).apply { remove(key) }))
            assertNull(DeploymentTargetFormCheckpoint.read(Bundle(original).apply { putInt(key, 1) }))
            assertNotNull(DeploymentTargetFormCheckpoint.read(Bundle(original).apply { putString(key, "x".repeat(max)) }))
        }
    }

    @Test fun rawComputeAndProviderReadersKeepTheirSeparateExistingProtocols() {
        val raw = """{"replicas_min":"""
        val legacy = ComponentFormCheckpoint(ComponentFormCheckpoint.CONFIGURE, "draft_fixture", "baseline_fixture",
            "selection_fixture", null, null, null, null, "", raw, false, false)
        for (version in 1..2) {
            val value = requireNotNull(legacy.bundle()).apply { putInt("version", version) }
            assertEquals(raw, (ComponentFormRecovery.read(parcel(outer(value))) as ComponentFormRecovery.Pending).form.settings)
            assertNull(DeploymentTargetFormCheckpoint.read(value))
        }
        val compute = ComputeFormCheckpoint("draft_fixture", "baseline_fixture", "selection_compute", "compute.fixture", "1",
            PreviewComputeInput("unfinished", "0", ""), false)
        val computeState = Bundle().also { compute.recovery().save(it) }
        assertEquals(compute.input, (ComponentFormRecovery.read(parcel(computeState)) as ComponentFormRecovery.TypedComputePending).form.input)
        val provider = ModelProviderFormCheckpoint("draft_fixture", "baseline_fixture", "selection_provider", "provider.fixture", "1",
            PreviewModelProviderInput(region = "unknown", credentialRef = "secret://fixture/key"), false)
        val providerState = Bundle().also { provider.recovery().save(it) }
        assertEquals(provider.input, (ComponentFormRecovery.read(parcel(providerState)) as ComponentFormRecovery.TypedModelProviderPending).form.input)
        assertNull(DeploymentTargetFormCheckpoint.read(compute.bundle()!!))
        assertNull(DeploymentTargetFormCheckpoint.read(provider.bundle()!!))
    }

    @Test fun savingFlagAndDescriptionsNeverBecomeApprovalOrWriteAuthority() {
        val input = PreviewDeploymentTargetInput("staging", "unfinished", "1,5", "", "synthetic-private-reference")
        val state = Bundle().also { checkpoint(input, saving = true).recovery().save(it) }
        val restored = (ComponentFormRecovery.read(parcel(state)) as ComponentFormRecovery.TypedDeploymentTargetPending).form
        assertTrue(restored.wasSaving)
        assertEquals(input, restored.input)
        assertEquals("DeploymentTargetFormCheckpoint", restored.toString())
        assertEquals("PreviewDeploymentTargetInput", restored.input.toString())
    }
}
