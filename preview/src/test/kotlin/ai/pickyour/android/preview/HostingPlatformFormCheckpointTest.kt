package ai.pickyour.android.preview

import android.os.Bundle
import android.os.Parcel
import ai.pickyour.core.preview.PreviewContextPolicyInput
import ai.pickyour.core.preview.PreviewModelInput
import ai.pickyour.core.preview.PreviewHostingPlatformInput
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], manifest = Config.NONE)
class HostingPlatformFormCheckpointTest {
    private fun checkpoint(input: PreviewHostingPlatformInput = PreviewHostingPlatformInput(), saving: Boolean = false) =
        HostingPlatformFormCheckpoint("draft_fixture", "baseline_fixture", "selection_fixture", "hosting.fixture", "1", input, saving)
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
    private fun captured(input: PreviewHostingPlatformInput, saving: Boolean = false) =
        Bundle().also { checkpoint(input, saving).recovery().save(it) }.let(::parcel)
    private fun restored(input: PreviewHostingPlatformInput, saving: Boolean = false): HostingPlatformFormCheckpoint {
        val recovery = ComponentFormRecovery.read(captured(input, saving))
        assertTrue(recovery is ComponentFormRecovery.TypedHostingPlatformPending)
        val state = Bundle().also(recovery::save)
        return requireNotNull(HostingPlatformFormCheckpoint.read(requireNotNull(state.getBundle(ComponentFormRecovery.KEY))))
    }

    @Test fun allPresenceCombinationsAndOriginalFiveIdentitiesSurviveActualParcelRoundTrip() {
        val full = PreviewHostingPlatformInput("literal-region", "secret://fixture/Case:Exact-1\n", "air_gapped")
        for (mask in 0..7) {
            val input = PreviewHostingPlatformInput(if (mask and 1 != 0) full.region else null,
                if (mask and 2 != 0) full.accountRef else null, if (mask and 4 != 0) full.networkIsolation else null)
            val value = restored(input)
            assertEquals(input, value.input)
            assertEquals("draft_fixture", value.draftId)
            assertEquals("baseline_fixture", value.baseline)
            assertEquals("selection_fixture", value.selectionId)
            assertEquals("hosting.fixture", value.catalogItemId)
            assertEquals("1", value.catalogItemVersion)
            assertFalse(value.wasSaving)
        }
    }

    @Test fun suppliedEmptyWhitespaceUnknownAndUnicodeRegionRemainExactLiteralInput() {
        for (region in listOf("", " ", "\t\r\n", "unknown", "not_applicable", " Exact:Region ", "منطقة-\uD83D\uDE00")) {
            val input = PreviewHostingPlatformInput(region = region)
            val value = restored(input)
            assertEquals(region, value.input.region)
            assertNotEquals(PreviewHostingPlatformInput(), value.input)
            assertTrue(requireNotNull(value.bundle()).containsKey("region"))
        }
        assertNull(restored(PreviewHostingPlatformInput()).input.region)
    }

    @Test fun combinedTwentyThousandUtf16BudgetRejectsOneExtraWithoutRetainingAPrefix() {
        val ref = "secret://x"
        val network = "private"
        val available = ComponentFormCheckpoint.MAX_RAW_CHARACTERS - ref.length - network.length
        val region = "\uD83D\uDE00".repeat(available / 2) + "x".repeat(available % 2)
        val exact = PreviewHostingPlatformInput(region, ref, network)
        assertEquals(ComponentFormCheckpoint.MAX_RAW_CHARACTERS, region.length + ref.length + network.length)
        assertEquals(exact, restored(exact).input)
        val over = exact.copy(region = region + "x")
        assertSame(ComponentFormRecovery.Unavailable, checkpoint(over).recovery())
        assertNull(checkpoint(over).bundle())
        val retained = requireNotNull(captured(over).getBundle(ComponentFormRecovery.KEY))
        assertEquals(setOf("version", "unavailable"), retained.keySet())
        assertTrue(retained.getBoolean("unavailable"))
        val forged = requireNotNull(checkpoint(exact).bundle()).apply { putString("accountRef", ref + "x") }
        assertNull(HostingPlatformFormCheckpoint.read(forged))
        assertSame(ComponentFormRecovery.Unavailable, ComponentFormRecovery.read(parcel(outer(forged))))
        val boundedRef = "secret://" + "x".repeat(ComponentFormCheckpoint.MAX_RAW_CHARACTERS - "secret://".length)
        assertEquals(boundedRef, restored(PreviewHostingPlatformInput(accountRef = boundedRef)).input.accountRef)
        assertNull(checkpoint(PreviewHostingPlatformInput(accountRef = boundedRef + "x")).bundle())
    }

    @Test fun exactPrimitiveKeysRejectMissingExtraAndWrongTypedPayloadValues() {
        val original = requireNotNull(checkpoint().bundle())
        val mutations: List<(Bundle) -> Unit> = listOf(
            { it.remove("region") }, { it.remove("accountRef") }, { it.remove("networkIsolation") },
            { it.remove("wasSaving") }, { it.putString("wasSaving", "false") },
            { it.putInt("region", 0) }, { it.putBoolean("accountRef", true) },
            { it.putByteArray("networkIsolation", byteArrayOf(1)) }, { it.putBundle("region", Bundle()) },
            { it.putStringArrayList("region", arrayListOf("literal")) },
            { it.putString("extra", "not interpreted") }, { it.remove("kind") }, { it.remove("form") },
        )
        for (mutate in mutations) {
            val value = Bundle(original).also(mutate)
            assertNull(HostingPlatformFormCheckpoint.read(value))
            assertSame(ComponentFormRecovery.Unavailable, ComponentFormRecovery.read(parcel(outer(value))))
        }
        assertNotNull(HostingPlatformFormCheckpoint.read(Bundle(original).apply { putString("region", "") }))
        assertNotNull(HostingPlatformFormCheckpoint.read(Bundle(original).apply { putString("region", null) }))
    }

    @Test fun namespacedVersionFormAndKindNeverAliasAnotherRecoveryProtocol() {
        val original = requireNotNull(checkpoint().bundle())
        assertEquals("typed-hosting-platform/1", original.getString("version"))
        assertEquals("hostingPlatform", original.getString("form"))
        assertEquals("hosting_platform", original.getString("kind"))
        val mutations: List<(Bundle) -> Unit> = listOf(
            { it.remove("version") }, { it.putInt("version", 1) }, { it.putInt("version", 4) },
            { it.putLong("version", 1L) }, { it.putString("version", "1") },
            { it.putString("version", "typed-hosting-platform/2") }, { it.putString("version", "typed-model-provider/1") },
            { it.putString("form", "configure") }, { it.putString("form", "configure_model") },
            { it.putString("form", "") }, { it.putString("kind", "model") },
            { it.putString("kind", "compute_profile") }, { it.putString("kind", "HOSTING_PLATFORM") },
        )
        for (mutate in mutations) {
            val value = Bundle(original).also(mutate)
            assertNull(HostingPlatformFormCheckpoint.read(value))
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
                assertNull(HostingPlatformFormCheckpoint.read(Bundle(original).apply { putString(key, value) }))
            }
            assertNull(HostingPlatformFormCheckpoint.read(Bundle(original).apply { putString(key, null) }))
            assertNull(HostingPlatformFormCheckpoint.read(Bundle(original).apply { remove(key) }))
            assertNull(HostingPlatformFormCheckpoint.read(Bundle(original).apply { putInt(key, 1) }))
            val identity = "x".repeat(maximum)
            val retained = requireNotNull(HostingPlatformFormCheckpoint.read(Bundle(original).apply { putString(key, identity) }))
            assertEquals(identity, requireNotNull(retained.bundle()).getString(key))
        }
    }

    @Test fun allNetworkChoicesAndOmissionSurviveWhileForgedAliasesAreUnavailable() {
        for (network in listOf(null, "public", "private", "air_gapped")) {
            val input = PreviewHostingPlatformInput(networkIsolation = network)
            assertEquals(input, restored(input).input)
        }
        for (network in listOf("", " ", "unknown", "not_applicable", "PUBLIC", "private\n", "air-gapped")) {
            assertSame(ComponentFormRecovery.Unavailable,
                checkpoint(PreviewHostingPlatformInput(networkIsolation = network)).recovery())
            val forged = requireNotNull(checkpoint().bundle()).apply { putString("networkIsolation", network) }
            assertNull(HostingPlatformFormCheckpoint.read(forged))
            assertSame(ComponentFormRecovery.Unavailable, ComponentFormRecovery.read(parcel(outer(forged))))
        }
    }

    @Test fun invalidAccountsAreExcludedWhileExactOpaqueReferencesIncludingFinalLfSurvive() {
        for (reference in listOf("", " ", "synthetic-password", "secret://", "secret://fixture/@bad",
            "secret://fixture/key\r", "secret://fixture/key\r\n", "secret://fixture/key\n\n", "SECRET://fixture/key")) {
            val input = PreviewHostingPlatformInput("", reference, "private")
            assertSame(ComponentFormRecovery.Unavailable, checkpoint(input).recovery())
            assertNull(checkpoint(input).bundle())
            val retained = requireNotNull(captured(input).getBundle(ComponentFormRecovery.KEY))
            assertEquals(setOf("version", "unavailable"), retained.keySet())
            assertTrue(retained.getBoolean("unavailable"))
            val forged = requireNotNull(checkpoint().bundle()).apply { putString("accountRef", reference) }
            assertNull(HostingPlatformFormCheckpoint.read(forged))
            assertSame(ComponentFormRecovery.Unavailable, ComponentFormRecovery.read(parcel(outer(forged))))
        }
        for (reference in listOf(null, "secret://fixture/Case:Exact-1", "secret://fixture/key\n")) {
            val input = PreviewHostingPlatformInput(accountRef = reference)
            assertEquals(input, restored(input).input)
        }
    }

    @Test fun rawV1V2ContextV3AndModelV4KeepTheirSeparateOriginalReaders() {
        val raw = """{"account_ref":"synthetic-historical","unfinished":"""
        val legacy = ComponentFormCheckpoint(ComponentFormCheckpoint.CONFIGURE, "draft_fixture", "baseline_fixture",
            "selection_fixture", null, null, null, null, "", raw, false, false)
        for (version in 1..2) {
            val value = requireNotNull(legacy.bundle()).apply { putInt("version", version) }
            val recovered = ComponentFormRecovery.read(parcel(outer(value))) as ComponentFormRecovery.Pending
            assertEquals(raw, recovered.form.settings)
            assertEquals(legacy.baseline, recovered.form.baseline)
            assertNull(HostingPlatformFormCheckpoint.read(value))
        }
        val context = ContextPolicyFormCheckpoint("draft_fixture", "baseline_fixture", "selection_context",
            PreviewContextPolicyInput("unfinished", "0", null, "fp16"), false)
        val contextState = Bundle().also { context.recovery().save(it) }
        val restoredContext = ComponentFormRecovery.read(parcel(contextState)) as ComponentFormRecovery.TypedContextPending
        assertEquals(context.input, restoredContext.form.input)
        assertEquals(3, requireNotNull(contextState.getBundle(ComponentFormRecovery.KEY)).getInt("version"))
        assertNull(HostingPlatformFormCheckpoint.read(requireNotNull(context.bundle())))
        val model = ModelFormCheckpoint("draft_fixture", "baseline_fixture", "selection_model", "model.fixture", "1",
            PreviewModelInput(temperature = "unfinished", weightsUri = "urn:fixture:model"), false)
        val modelState = Bundle().also { model.recovery().save(it) }
        val restoredModel = ComponentFormRecovery.read(parcel(modelState)) as ComponentFormRecovery.TypedModelPending
        assertEquals(model.input, restoredModel.form.input)
        assertEquals(4, requireNotNull(modelState.getBundle(ComponentFormRecovery.KEY)).getInt("version"))
        assertNull(HostingPlatformFormCheckpoint.read(requireNotNull(model.bundle())))
    }

    @Test fun inFlightStateAndFixedMarkersRemainSeparateWithoutDiagnosticValues() {
        val input = PreviewHostingPlatformInput("synthetic-private-region", "secret://fixture/private", "private")
        val value = restored(input, saving = true)
        assertTrue(value.wasSaving)
        assertEquals(input, value.input)
        assertEquals("HostingPlatformFormCheckpoint", value.toString())
        assertEquals("PreviewHostingPlatformInput", value.input.toString())
        for (marker in listOf(ComponentFormRecovery.Unavailable, ComponentFormRecovery.UriUserinfoExcluded)) {
            val state = Bundle().also { marker.save(it) }
            assertSame(marker, ComponentFormRecovery.read(parcel(state)))
        }
    }
}
