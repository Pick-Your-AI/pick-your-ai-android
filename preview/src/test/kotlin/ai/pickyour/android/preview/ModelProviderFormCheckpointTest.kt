package ai.pickyour.android.preview

import android.os.Bundle
import android.os.Parcel
import ai.pickyour.core.preview.PreviewContextPolicyInput
import ai.pickyour.core.preview.PreviewModelInput
import ai.pickyour.core.preview.PreviewModelProviderInput
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], manifest = Config.NONE)
class ModelProviderFormCheckpointTest {
    private fun checkpoint(input: PreviewModelProviderInput = PreviewModelProviderInput(), saving: Boolean = false) =
        ModelProviderFormCheckpoint("draft_fixture", "baseline_fixture", "selection_fixture", "provider.fixture", "1", input, saving)
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
    private fun captured(input: PreviewModelProviderInput, saving: Boolean = false) =
        Bundle().also { checkpoint(input, saving).recovery().save(it) }.let(::parcel)
    private fun restored(input: PreviewModelProviderInput, saving: Boolean = false): ModelProviderFormCheckpoint {
        val recovery = ComponentFormRecovery.read(captured(input, saving))
        assertTrue(recovery is ComponentFormRecovery.TypedModelProviderPending)
        val state = Bundle().also(recovery::save)
        return requireNotNull(ModelProviderFormCheckpoint.read(requireNotNull(state.getBundle(ComponentFormRecovery.KEY))))
    }

    @Test fun allEightPresenceCombinationsAndOriginalIdentitiesSurviveActualParcelRoundTrip() {
        val full = PreviewModelProviderInput("literal-region", "secret://fixture/Case:Exact-1\n",
            "custom+fixture://Example.invalid/path@literal?q=@query#@fragment")
        for (mask in 0..7) {
            val input = PreviewModelProviderInput(if (mask and 1 != 0) full.region else null,
                if (mask and 2 != 0) full.credentialRef else null, if (mask and 4 != 0) full.apiBaseUrl else null)
            val value = restored(input)
            assertEquals(input, value.input)
            assertEquals("draft_fixture", value.draftId)
            assertEquals("baseline_fixture", value.baseline)
            assertEquals("selection_fixture", value.selectionId)
            assertEquals("provider.fixture", value.catalogItemId)
            assertEquals("1", value.catalogItemVersion)
            assertFalse(value.wasSaving)
        }
    }

    @Test fun suppliedEmptyWhitespaceUnknownAndUnicodeRegionRemainExactLiteralInput() {
        for (region in listOf("", " ", "\t\r\n", "unknown", "not_applicable", " Exact:Region ", "منطقة-\uD83D\uDE00")) {
            val input = PreviewModelProviderInput(region = region)
            val value = restored(input)
            assertEquals(region, value.input.region)
            assertNotEquals(PreviewModelProviderInput(), value.input)
            assertTrue(requireNotNull(value.bundle()).containsKey("region"))
        }
        assertNull(restored(PreviewModelProviderInput()).input.region)
    }

    @Test fun combinedTwentyThousandUtf16BudgetRejectsOneExtraWithoutRetainingAPrefix() {
        val ref = "secret://x"
        val uri = "urn:x:test"
        val available = ComponentFormCheckpoint.MAX_RAW_CHARACTERS - ref.length - uri.length
        val region = "\uD83D\uDE00".repeat(available / 2) + "x".repeat(available % 2)
        val exact = PreviewModelProviderInput(region, ref, uri)
        assertEquals(ComponentFormCheckpoint.MAX_RAW_CHARACTERS, region.length + ref.length + uri.length)
        assertEquals(exact, restored(exact).input)
        val over = exact.copy(region = region + "x")
        assertSame(ComponentFormRecovery.Unavailable, checkpoint(over).recovery())
        assertNull(checkpoint(over).bundle())
        val retained = requireNotNull(captured(over).getBundle(ComponentFormRecovery.KEY))
        assertEquals(setOf("version", "unavailable"), retained.keySet())
        assertTrue(retained.getBoolean("unavailable"))
        val forged = requireNotNull(checkpoint(exact).bundle()).apply { putString("apiBaseUrl", uri + "x") }
        assertNull(ModelProviderFormCheckpoint.read(forged))
        assertSame(ComponentFormRecovery.Unavailable, ComponentFormRecovery.read(parcel(outer(forged))))
    }

    @Test fun exactPrimitiveKeysRejectMissingExtraAndWrongTypedPayloadValues() {
        val original = requireNotNull(checkpoint().bundle())
        val mutations: List<(Bundle) -> Unit> = listOf(
            { it.remove("region") }, { it.remove("credentialRef") }, { it.remove("apiBaseUrl") },
            { it.remove("wasSaving") }, { it.putString("wasSaving", "false") },
            { it.putInt("region", 0) }, { it.putBoolean("credentialRef", true) },
            { it.putByteArray("apiBaseUrl", byteArrayOf(1)) }, { it.putBundle("region", Bundle()) },
            { it.putStringArrayList("region", arrayListOf("literal")) },
            { it.putString("extra", "not interpreted") }, { it.remove("kind") }, { it.remove("form") },
        )
        for (mutate in mutations) {
            val value = Bundle(original).also(mutate)
            assertNull(ModelProviderFormCheckpoint.read(value))
            assertSame(ComponentFormRecovery.Unavailable, ComponentFormRecovery.read(parcel(outer(value))))
        }
        assertNotNull(ModelProviderFormCheckpoint.read(Bundle(original).apply { putString("region", "") }))
        assertNotNull(ModelProviderFormCheckpoint.read(Bundle(original).apply { putString("region", null) }))
    }

    @Test fun namespacedStringVersionFormAndKindNeverAliasAnotherRecoveryProtocol() {
        val original = requireNotNull(checkpoint().bundle())
        assertEquals("typed-model-provider/1", original.getString("version"))
        assertEquals("modelProvider", original.getString("form"))
        assertEquals("model_provider", original.getString("kind"))
        val mutations: List<(Bundle) -> Unit> = listOf(
            { it.remove("version") }, { it.putInt("version", 1) }, { it.putInt("version", 4) },
            { it.putLong("version", 1L) }, { it.putString("version", "1") },
            { it.putString("version", "typed-model-provider/2") }, { it.putString("version", "typed-compute-profile/1") },
            { it.putString("form", "configure") }, { it.putString("form", "configure_model") },
            { it.putString("form", "") }, { it.putString("kind", "model") },
            { it.putString("kind", "compute_profile") }, { it.putString("kind", "MODEL_PROVIDER") },
        )
        for (mutate in mutations) {
            val value = Bundle(original).also(mutate)
            assertNull(ModelProviderFormCheckpoint.read(value))
            assertSame(ComponentFormRecovery.Unavailable, ComponentFormRecovery.read(parcel(outer(value))))
        }
        assertNull(ComponentFormCheckpoint.read(original))
        assertNull(ContextPolicyFormCheckpoint.read(original))
        assertNull(ModelFormCheckpoint.read(original))
    }

    @Test fun everyOriginalIdentityIsRequiredBoundedAndNeverSubstitutedDuringDecoding() {
        val original = requireNotNull(checkpoint().bundle())
        for (key in listOf("draftId", "baseline", "selectionId", "catalogItemId", "catalogItemVersion")) {
            val maximum = if (key == "catalogItemId") 256 else 128
            for (value in listOf("", "x".repeat(maximum + 1))) {
                assertNull(ModelProviderFormCheckpoint.read(Bundle(original).apply { putString(key, value) }))
            }
            assertNull(ModelProviderFormCheckpoint.read(Bundle(original).apply { putString(key, null) }))
            assertNull(ModelProviderFormCheckpoint.read(Bundle(original).apply { remove(key) }))
            assertNull(ModelProviderFormCheckpoint.read(Bundle(original).apply { putInt(key, 1) }))
            val identity = "x".repeat(maximum)
            val retained = requireNotNull(ModelProviderFormCheckpoint.read(Bundle(original).apply { putString(key, identity) }))
            assertEquals(identity, requireNotNull(retained.bundle()).getString(key))
        }
    }

    @Test fun recognizedApiUserinfoOverridesOtherInvalidInputWithOnlyTheFixedExclusionMarker() {
        for (uri in listOf("https://synthetic:private@host.invalid/path", "custom+fixture://user@host_name/resource",
            "custom+fixture://@host_name/resource", "https://user%3Aprivate@host.invalid")) {
            val input = PreviewModelProviderInput("literal pending region", "synthetic-password", uri)
            assertSame(ComponentFormRecovery.UriUserinfoExcluded, checkpoint(input).recovery())
            assertNull(checkpoint(input).bundle())
            val state = captured(input)
            assertSame(ComponentFormRecovery.UriUserinfoExcluded, ComponentFormRecovery.read(state))
            val retained = requireNotNull(state.getBundle(ComponentFormRecovery.KEY))
            assertEquals(setOf("version", "unavailable", "reason"), retained.keySet())
            assertEquals("uri_userinfo", retained.getString("reason"))
            assertTrue(retained.getBoolean("unavailable"))
        }
    }

    @Test fun invalidCredentialsCannotPersistWhileOpaqueReferenceBytesIncludingFinalLfSurvive() {
        for (reference in listOf("", " ", "synthetic-password", "secret://", "secret://fixture/@bad",
            "secret://fixture/key\r", "secret://fixture/key\r\n", "secret://fixture/key\n\n", "SECRET://fixture/key")) {
            val input = PreviewModelProviderInput("", reference, "https://bad authority")
            assertSame(ComponentFormRecovery.Unavailable, checkpoint(input).recovery())
            assertNull(checkpoint(input).bundle())
            val retained = requireNotNull(captured(input).getBundle(ComponentFormRecovery.KEY))
            assertEquals(setOf("version", "unavailable"), retained.keySet())
            assertTrue(retained.getBoolean("unavailable"))
        }
        for (reference in listOf(null, "secret://fixture/Case:Exact-1", "secret://fixture/key\n")) {
            val input = PreviewModelProviderInput(credentialRef = reference)
            assertEquals(input, restored(input).input)
        }
    }

    @Test fun ordinaryMalformedUrisAndLegitimateAtSignsRemainPrivateCorrectionInput() {
        for (uri in listOf("", " ", "https://bad authority", "https://[unfinished", "urn:fixture:opaque@value",
            "file:///literal/provider", "custom+fixture://Example.invalid/path@literal?q=@query#@fragment")) {
            val input = PreviewModelProviderInput("\tunknown\n", "secret://fixture/ref", uri)
            assertEquals(input, restored(input).input)
        }
    }

    @Test fun forgedTypedSensitivePayloadsCannotBypassReadSidePrivacyChecks() {
        val original = requireNotNull(checkpoint().bundle())
        val mutations: List<(Bundle) -> Unit> = listOf(
            { it.putString("credentialRef", "synthetic-password") },
            { it.putString("apiBaseUrl", "custom+fixture://user:private@host_name/resource") },
            { it.putString("apiBaseUrl", "https://@example.invalid"); it.putString("credentialRef", "") },
        )
        for (mutate in mutations) {
            val value = Bundle(original).also(mutate)
            assertNull(ModelProviderFormCheckpoint.read(value))
            assertSame(ComponentFormRecovery.Unavailable, ComponentFormRecovery.read(parcel(outer(value))))
        }
    }

    @Test fun rawProviderV1V2ContextV3AndModelV4KeepTheirSeparateOriginalReaders() {
        val raw = """{"api_base_url":"https://user:historical@host.invalid","unfinished":"""
        val legacy = ComponentFormCheckpoint(ComponentFormCheckpoint.CONFIGURE, "draft_fixture", "baseline_fixture",
            "selection_fixture", null, null, null, null, "", raw, false, false)
        for (version in 1..2) {
            val value = requireNotNull(legacy.bundle()).apply { putInt("version", version) }
            val recovered = ComponentFormRecovery.read(parcel(outer(value))) as ComponentFormRecovery.Pending
            assertEquals(raw, recovered.form.settings)
            assertEquals(legacy.baseline, recovered.form.baseline)
            assertNull(ModelProviderFormCheckpoint.read(value))
        }
        val context = ContextPolicyFormCheckpoint("draft_fixture", "baseline_fixture", "selection_context",
            PreviewContextPolicyInput("unfinished", "0", null, "fp16"), false)
        val contextState = Bundle().also { context.recovery().save(it) }
        val restoredContext = ComponentFormRecovery.read(parcel(contextState)) as ComponentFormRecovery.TypedContextPending
        assertEquals(context.input, restoredContext.form.input)
        assertEquals(3, requireNotNull(contextState.getBundle(ComponentFormRecovery.KEY)).getInt("version"))
        assertNull(ModelProviderFormCheckpoint.read(requireNotNull(context.bundle())))
        val model = ModelFormCheckpoint("draft_fixture", "baseline_fixture", "selection_model", "model.fixture", "1",
            PreviewModelInput(temperature = "unfinished", weightsUri = "urn:fixture:model"), false)
        val modelState = Bundle().also { model.recovery().save(it) }
        val restoredModel = ComponentFormRecovery.read(parcel(modelState)) as ComponentFormRecovery.TypedModelPending
        assertEquals(model.input, restoredModel.form.input)
        assertEquals(4, requireNotNull(modelState.getBundle(ComponentFormRecovery.KEY)).getInt("version"))
        assertNull(ModelProviderFormCheckpoint.read(requireNotNull(model.bundle())))
    }

    @Test fun inFlightFlagRemainsStateAndDescriptionsNeverDiscloseTheThreeValues() {
        val input = PreviewModelProviderInput("synthetic-private-region", "secret://fixture/private", "urn:fixture:private")
        val value = restored(input, saving = true)
        assertTrue(value.wasSaving)
        assertEquals(input, value.input)
        assertEquals("ModelProviderFormCheckpoint", value.toString())
        assertEquals("PreviewModelProviderInput", value.input.toString())
        for (marker in listOf(ComponentFormRecovery.Unavailable, ComponentFormRecovery.UriUserinfoExcluded)) {
            val state = Bundle().also { marker.save(it) }
            assertSame(marker, ComponentFormRecovery.read(parcel(state)))
        }
    }
}
