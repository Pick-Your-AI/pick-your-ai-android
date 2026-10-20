package ai.pickyour.android.preview

import android.os.Bundle
import android.os.Parcel
import ai.pickyour.core.preview.PreviewContextPolicyInput
import ai.pickyour.core.preview.PreviewModelInput
import ai.pickyour.core.preview.PreviewStorageInput
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], manifest = Config.NONE)
class StorageFormCheckpointTest {
    private fun checkpoint(input: PreviewStorageInput = PreviewStorageInput(), saving: Boolean = false) =
        StorageFormCheckpoint("draft_fixture", "baseline_fixture", "selection_fixture", "storage.fixture", "1", input, saving)
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
    private fun captured(input: PreviewStorageInput, saving: Boolean = false) =
        Bundle().also { checkpoint(input, saving).recovery().save(it) }.let(::parcel)
    private fun restored(input: PreviewStorageInput, saving: Boolean = false): StorageFormCheckpoint {
        val recovery = ComponentFormRecovery.read(captured(input, saving))
        assertTrue(recovery is ComponentFormRecovery.TypedStoragePending)
        val state = Bundle().also(recovery::save)
        return requireNotNull(StorageFormCheckpoint.read(requireNotNull(state.getBundle(ComponentFormRecovery.KEY))))
    }

    @Test fun allPresenceCombinationsAndOriginalFiveIdentitiesSurviveActualParcelRoundTrip() {
        val full = PreviewStorageInput("9007199254740993.125", "literal-region", "secret://fixture/Case:Exact-1\n")
        for (mask in 0..7) {
            val input = PreviewStorageInput(if (mask and 1 != 0) full.provisionedGb else null,
                if (mask and 2 != 0) full.region else null, if (mask and 4 != 0) full.encryptionKeyRef else null)
            val value = restored(input)
            assertEquals(input, value.input)
            assertEquals("draft_fixture", value.draftId)
            assertEquals("baseline_fixture", value.baseline)
            assertEquals("selection_fixture", value.selectionId)
            assertEquals("storage.fixture", value.catalogItemId)
            assertEquals("1", value.catalogItemVersion)
            assertFalse(value.wasSaving)
        }
    }

    @Test fun suppliedEmptyWhitespaceUnknownAndUnicodeRegionRemainExactLiteralInput() {
        for (region in listOf("", " ", "\t\r\n", "unknown", "not_applicable", " Exact:Region ", "منطقة-\uD83D\uDE00")) {
            val input = PreviewStorageInput(region = region)
            val value = restored(input)
            assertEquals(region, value.input.region)
            assertNotEquals(PreviewStorageInput(), value.input)
            assertTrue(requireNotNull(value.bundle()).containsKey("region"))
        }
        assertNull(restored(PreviewStorageInput()).input.region)
    }

    @Test fun combinedTwentyThousandUtf16BudgetRejectsOneExtraWithoutRetainingAPrefix() {
        val ref = "secret://x"
        val number = "0.125"
        val available = ComponentFormCheckpoint.MAX_RAW_CHARACTERS - ref.length - number.length
        val region = "\uD83D\uDE00".repeat(available / 2) + "x".repeat(available % 2)
        val exact = PreviewStorageInput(number, region, ref)
        assertEquals(ComponentFormCheckpoint.MAX_RAW_CHARACTERS, region.length + ref.length + number.length)
        assertEquals(exact, restored(exact).input)
        val over = exact.copy(region = region + "x")
        assertSame(ComponentFormRecovery.Unavailable, checkpoint(over).recovery())
        assertNull(checkpoint(over).bundle())
        val retained = requireNotNull(captured(over).getBundle(ComponentFormRecovery.KEY))
        assertEquals(setOf("version", "unavailable"), retained.keySet())
        assertTrue(retained.getBoolean("unavailable"))
        val forged = requireNotNull(checkpoint(exact).bundle()).apply { putString("encryptionKeyRef", ref + "x") }
        assertNull(StorageFormCheckpoint.read(forged))
        assertSame(ComponentFormRecovery.Unavailable, ComponentFormRecovery.read(parcel(outer(forged))))
        val boundedRef = "secret://" + "x".repeat(ComponentFormCheckpoint.MAX_RAW_CHARACTERS - "secret://".length)
        assertEquals(boundedRef, restored(PreviewStorageInput(encryptionKeyRef = boundedRef)).input.encryptionKeyRef)
        assertNull(checkpoint(PreviewStorageInput(encryptionKeyRef = boundedRef + "x")).bundle())
    }

    @Test fun exactPrimitiveKeysRejectMissingExtraAndWrongTypedPayloadValues() {
        val original = requireNotNull(checkpoint().bundle())
        val mutations: List<(Bundle) -> Unit> = listOf(
            { it.remove("region") }, { it.remove("encryptionKeyRef") }, { it.remove("provisionedGb") },
            { it.remove("wasSaving") }, { it.putString("wasSaving", "false") },
            { it.putInt("region", 0) }, { it.putBoolean("encryptionKeyRef", true) },
            { it.putByteArray("provisionedGb", byteArrayOf(1)) }, { it.putBundle("region", Bundle()) },
            { it.putStringArrayList("region", arrayListOf("literal")) },
            { it.putString("extra", "not interpreted") }, { it.remove("kind") }, { it.remove("form") },
        )
        for (mutate in mutations) {
            val value = Bundle(original).also(mutate)
            assertNull(StorageFormCheckpoint.read(value))
            assertSame(ComponentFormRecovery.Unavailable, ComponentFormRecovery.read(parcel(outer(value))))
        }
        assertNotNull(StorageFormCheckpoint.read(Bundle(original).apply { putString("region", "") }))
        assertNotNull(StorageFormCheckpoint.read(Bundle(original).apply { putString("region", null) }))
    }

    @Test fun namespacedVersionFormAndKindNeverAliasAnotherRecoveryProtocol() {
        val original = requireNotNull(checkpoint().bundle())
        assertEquals("typed-storage/1", original.getString("version"))
        assertEquals("storage", original.getString("form"))
        assertEquals("storage", original.getString("kind"))
        val mutations: List<(Bundle) -> Unit> = listOf(
            { it.remove("version") }, { it.putInt("version", 1) }, { it.putInt("version", 4) },
            { it.putLong("version", 1L) }, { it.putString("version", "1") },
            { it.putString("version", "typed-storage/2") }, { it.putString("version", "typed-model-provider/1") },
            { it.putString("form", "configure") }, { it.putString("form", "configure_model") },
            { it.putString("form", "") }, { it.putString("kind", "model") },
            { it.putString("kind", "compute_profile") }, { it.putString("kind", "STORAGE") },
        )
        for (mutate in mutations) {
            val value = Bundle(original).also(mutate)
            assertNull(StorageFormCheckpoint.read(value))
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
                assertNull(StorageFormCheckpoint.read(Bundle(original).apply { putString(key, value) }))
            }
            assertNull(StorageFormCheckpoint.read(Bundle(original).apply { putString(key, null) }))
            assertNull(StorageFormCheckpoint.read(Bundle(original).apply { remove(key) }))
            assertNull(StorageFormCheckpoint.read(Bundle(original).apply { putInt(key, 1) }))
            val identity = "x".repeat(maximum)
            val retained = requireNotNull(StorageFormCheckpoint.read(Bundle(original).apply { putString(key, identity) }))
            assertEquals(identity, requireNotNull(retained.bundle()).getString(key))
        }
    }

    @Test fun numericCorrectionTextAndZeroRemainExactWithoutAcquiringValidationOrSaveAuthority() {
        for (number in listOf(null, "0", "0.00", "9007199254740993.125", "1e-400", "", " ",
            "-1", "1,25", "NaN", "unknown", "1e10001", "{}", "null", "true")) {
            val input = PreviewStorageInput(provisionedGb = number)
            val value = restored(input)
            assertEquals(input, value.input)
            assertEquals(number, requireNotNull(value.bundle()).getString("provisionedGb"))
        }
        assertNotEquals(restored(PreviewStorageInput()).input, restored(PreviewStorageInput(provisionedGb = "0")).input)
        assertNotEquals(restored(PreviewStorageInput()).input, restored(PreviewStorageInput(provisionedGb = "")).input)
    }

    @Test fun invalidKeyReferencesAreExcludedWhileExactOpaqueReferencesIncludingFinalLfSurvive() {
        for (reference in listOf("", " ", "synthetic-password", "secret://", "secret://fixture/@bad",
            "secret://fixture/key\r", "secret://fixture/key\r\n", "secret://fixture/key\n\n", "SECRET://fixture/key")) {
            val input = PreviewStorageInput("-1", "", reference)
            assertSame(ComponentFormRecovery.Unavailable, checkpoint(input).recovery())
            assertNull(checkpoint(input).bundle())
            val retained = requireNotNull(captured(input).getBundle(ComponentFormRecovery.KEY))
            assertEquals(setOf("version", "unavailable"), retained.keySet())
            assertTrue(retained.getBoolean("unavailable"))
            val forged = requireNotNull(checkpoint().bundle()).apply { putString("encryptionKeyRef", reference) }
            assertNull(StorageFormCheckpoint.read(forged))
            assertSame(ComponentFormRecovery.Unavailable, ComponentFormRecovery.read(parcel(outer(forged))))
        }
        for (reference in listOf(null, "secret://fixture/Case:Exact-1", "secret://fixture/key\n")) {
            val input = PreviewStorageInput(encryptionKeyRef = reference)
            assertEquals(input, restored(input).input)
        }
    }

    @Test fun rawV1V2ContextV3AndModelV4KeepTheirSeparateOriginalReaders() {
        val raw = """{"encryption_key_ref":"synthetic-historical","unfinished":"""
        val legacy = ComponentFormCheckpoint(ComponentFormCheckpoint.CONFIGURE, "draft_fixture", "baseline_fixture",
            "selection_fixture", null, null, null, null, "", raw, false, false)
        for (version in 1..2) {
            val value = requireNotNull(legacy.bundle()).apply { putInt("version", version) }
            val recovered = ComponentFormRecovery.read(parcel(outer(value))) as ComponentFormRecovery.Pending
            assertEquals(raw, recovered.form.settings)
            assertEquals(legacy.baseline, recovered.form.baseline)
            assertNull(StorageFormCheckpoint.read(value))
        }
        val context = ContextPolicyFormCheckpoint("draft_fixture", "baseline_fixture", "selection_context",
            PreviewContextPolicyInput("unfinished", "0", null, "fp16"), false)
        val contextState = Bundle().also { context.recovery().save(it) }
        val restoredContext = ComponentFormRecovery.read(parcel(contextState)) as ComponentFormRecovery.TypedContextPending
        assertEquals(context.input, restoredContext.form.input)
        assertEquals(3, requireNotNull(contextState.getBundle(ComponentFormRecovery.KEY)).getInt("version"))
        assertNull(StorageFormCheckpoint.read(requireNotNull(context.bundle())))
        val model = ModelFormCheckpoint("draft_fixture", "baseline_fixture", "selection_model", "model.fixture", "1",
            PreviewModelInput(temperature = "unfinished", weightsUri = "urn:fixture:model"), false)
        val modelState = Bundle().also { model.recovery().save(it) }
        val restoredModel = ComponentFormRecovery.read(parcel(modelState)) as ComponentFormRecovery.TypedModelPending
        assertEquals(model.input, restoredModel.form.input)
        assertEquals(4, requireNotNull(modelState.getBundle(ComponentFormRecovery.KEY)).getInt("version"))
        assertNull(StorageFormCheckpoint.read(requireNotNull(model.bundle())))
    }

    @Test fun inFlightStateAndFixedMarkersRemainSeparateWithoutDiagnosticValues() {
        val input = PreviewStorageInput("0.00", "synthetic-private-region", "secret://fixture/private")
        val value = restored(input, saving = true)
        assertTrue(value.wasSaving)
        assertEquals(input, value.input)
        assertEquals("StorageFormCheckpoint", value.toString())
        assertEquals("PreviewStorageInput", value.input.toString())
        for (marker in listOf(ComponentFormRecovery.Unavailable, ComponentFormRecovery.UriUserinfoExcluded)) {
            val state = Bundle().also { marker.save(it) }
            assertSame(marker, ComponentFormRecovery.read(parcel(state)))
        }
    }
}
