package ai.pickyour.android.preview

import android.os.Bundle
import android.os.Parcel
import ai.pickyour.core.preview.PreviewContextPolicyInput
import ai.pickyour.core.preview.PreviewModelInput
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], manifest = Config.NONE)
class ModelFormCheckpointTest {
    private fun checkpoint(input: PreviewModelInput = PreviewModelInput(), saving: Boolean = false) =
        ModelFormCheckpoint("draft_fixture", "baseline_fixture", "selection_fixture", "model.fixture", "1", input, saving)
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
    private fun captured(input: PreviewModelInput): Bundle = Bundle().also { checkpoint(input).recovery().save(it) }.let(::parcel)

    @Test fun allSevenPrimitiveValuesAndExplicitAbsenceSurviveActualParcelRoundTrip() {
        val input = PreviewModelInput("", "1,5", "unfinished", "tf32", "weights",
            "secret://fixture/ref\n", "custom+fixture://Example.invalid/path@literal?q=@query#@fragment")
        val restored = (ComponentFormRecovery.read(captured(input)) as ComponentFormRecovery.TypedModelPending).form
        assertEquals(input, restored.input)
        assertEquals("draft_fixture", restored.draftId)
        assertEquals("baseline_fixture", restored.baseline)
        assertEquals("selection_fixture", restored.selectionId)
        assertEquals("model.fixture", restored.catalogItemId)
        assertEquals("1", restored.catalogItemVersion)
        assertFalse(restored.wasSaving)
        val absent = (ComponentFormRecovery.read(captured(PreviewModelInput())) as ComponentFormRecovery.TypedModelPending).form
        assertEquals(PreviewModelInput(), absent.input)
        val mixed = input.copy(temperature = null, precision = null, credentialRef = null)
        assertEquals(mixed, (ComponentFormRecovery.read(captured(mixed)) as ComponentFormRecovery.TypedModelPending).form.input)
    }

    @Test fun combinedTwentyThousandUtf16BudgetIncludesEveryFieldAndRejectsOneExtra() {
        val prefix = PreviewModelInput("", "1", "2", "tf32", "api", "secret://fixture/ref", "urn:fixture:value")
        val other = listOf(prefix.topP, prefix.maxOutputTokens, prefix.precision, prefix.servingMode,
            prefix.credentialRef, prefix.weightsUri).sumOf { it!!.length }
        val exact = prefix.copy(temperature = "x".repeat(ComponentFormCheckpoint.MAX_RAW_CHARACTERS - other))
        assertNotNull(checkpoint(exact).bundle())
        assertEquals(exact, (ComponentFormRecovery.read(captured(exact)) as ComponentFormRecovery.TypedModelPending).form.input)
        assertSame(ComponentFormRecovery.Unavailable, checkpoint(exact.copy(topP = "11")).recovery())
        val emoji = PreviewModelInput(temperature = "\uD83D\uDE00".repeat(10_000))
        assertNotNull(checkpoint(emoji).bundle())
        assertSame(ComponentFormRecovery.Unavailable, ComponentFormRecovery.read(captured(emoji.copy(topP = "1"))))
        val retained = captured(exact.copy(topP = "11")).getBundle(ComponentFormRecovery.KEY)!!
        assertEquals(setOf("version", "unavailable"), retained.keySet())
        assertTrue(retained.getBoolean("unavailable"))
    }

    @Test fun strictPrimitiveKeysDoNotCoerceAbsentEmptyOrWrongTypedValues() {
        val original = requireNotNull(checkpoint().bundle())
        val mutations: List<(Bundle) -> Unit> = listOf(
            { it.putString("version", "4") }, { it.putLong("version", 4L) },
            { it.putString("wasSaving", "false") }, { it.remove("wasSaving") },
            { it.putInt("temperature", 0) }, { it.putBoolean("topP", true) },
            { it.putDouble("maxOutputTokens", 1.0) }, { it.putBoolean("precision", false) },
            { it.putInt("servingMode", 0) }, { it.putByteArray("credentialRef", byteArrayOf(1)) },
            { it.putBundle("weightsUri", Bundle()) }, { it.remove("temperature") },
            { it.remove("credentialRef") }, { it.putString("extra", "not interpreted") },
        )
        for (mutate in mutations) {
            val value = Bundle(original).also(mutate)
            assertNull(ModelFormCheckpoint.read(value))
            assertSame(ComponentFormRecovery.Unavailable, ComponentFormRecovery.read(parcel(outer(value))))
        }
        assertNotNull(ModelFormCheckpoint.read(Bundle(original).apply { putString("temperature", "") }))
        assertNotNull(ModelFormCheckpoint.read(Bundle(original).apply { putString("temperature", null) }))
    }

    @Test fun wrongVersionFormKindAndEnumValuesNeverSelectAnotherRecoveryProtocol() {
        val original = requireNotNull(checkpoint().bundle())
        for (version in listOf(0, 1, 2, 3, 5, Int.MAX_VALUE)) {
            val changed = Bundle(original).apply { putInt("version", version) }
            assertNull(ModelFormCheckpoint.read(changed))
            assertSame(ComponentFormRecovery.Unavailable, ComponentFormRecovery.read(outer(changed)))
        }
        for (form in listOf("", "configure", "add", "configure_context_policy")) {
            assertNull(ModelFormCheckpoint.read(Bundle(original).apply { putString("form", form) }))
        }
        for (kind in listOf("", "context_policy", "model_provider", "MODEL")) {
            assertNull(ModelFormCheckpoint.read(Bundle(original).apply { putString("kind", kind) }))
        }
        for (value in listOf("", "unknown", "Not supplied", "TF32", "api ")) {
            assertNull(checkpoint(PreviewModelInput(precision = value)).bundle())
            assertNull(checkpoint(PreviewModelInput(servingMode = value)).bundle())
        }
    }

    @Test fun allOriginalIdentityFieldsAreRequiredBoundedAndNotReplacedByCurrentIdentity() {
        val original = requireNotNull(checkpoint().bundle())
        for (key in listOf("draftId", "baseline", "selectionId", "catalogItemId", "catalogItemVersion")) {
            val max = if (key == "catalogItemId") 256 else 128
            for (value in listOf("", "x".repeat(max + 1))) {
                assertNull(ModelFormCheckpoint.read(Bundle(original).apply { putString(key, value) }))
            }
            assertNull(ModelFormCheckpoint.read(Bundle(original).apply { putString(key, null) }))
            assertNull(ModelFormCheckpoint.read(Bundle(original).apply { remove(key) }))
            assertNull(ModelFormCheckpoint.read(Bundle(original).apply { putInt(key, 1) }))
            val value = "x".repeat(max)
            assertNotNull(ModelFormCheckpoint.read(Bundle(original).apply { putString(key, value) }))
        }
    }

    @Test fun userinfoIsExcludedIndependentlyOfInvalidNumericTextWithAValueFreeMarker() {
        for (uri in listOf("https://synthetic:private@host.invalid/path", "custom+fixture://user@host_name/resource",
            "custom+fixture://@host_name/resource", "https://user%3Aprivate@host.invalid")) {
            val input = PreviewModelInput(temperature = "unfinished", topP = "0", maxOutputTokens = "1,5", weightsUri = uri)
            assertSame(ComponentFormRecovery.UriUserinfoExcluded, checkpoint(input).recovery())
            val state = captured(input)
            assertSame(ComponentFormRecovery.UriUserinfoExcluded, ComponentFormRecovery.read(state))
            val value = state.getBundle(ComponentFormRecovery.KEY)!!
            assertEquals(setOf("version", "unavailable", "reason"), value.keySet())
            assertEquals("uri_userinfo", value.getString("reason"))
            assertNull(checkpoint(input).bundle())
        }
    }

    @Test fun invalidTypedCredentialNeverPersistsAPasswordWhileValidOpaqueBytesAreRetained() {
        for (credential in listOf("", "synthetic-password", "secret://", "secret://fixture/@bad", "secret://fixture/key\r")) {
            val input = PreviewModelInput(temperature = "unfinished", credentialRef = credential)
            assertSame(ComponentFormRecovery.Unavailable, checkpoint(input).recovery())
            assertNull(checkpoint(input).bundle())
            val value = captured(input).getBundle(ComponentFormRecovery.KEY)!!
            assertEquals(setOf("version", "unavailable"), value.keySet())
            assertTrue(value.getBoolean("unavailable"))
        }
        for (credential in listOf(null, "secret://fixture/Case:Exact-1", "secret://fixture/key\n")) {
            val input = PreviewModelInput(credentialRef = credential)
            assertEquals(input, (ComponentFormRecovery.read(captured(input)) as ComponentFormRecovery.TypedModelPending).form.input)
        }
    }

    @Test fun legitimateAtSignsAndOrdinaryMalformedUriTextRemainPrivateCorrectionInput() {
        for (uri in listOf("", "https://bad authority", "urn:fixture:opaque@value", "file:///literal/model",
            "custom+fixture://Example.invalid/path@literal?q=@query#@fragment")) {
            val input = PreviewModelInput(temperature = "1,5", weightsUri = uri)
            assertEquals(input, (ComponentFormRecovery.read(captured(input)) as ComponentFormRecovery.TypedModelPending).form.input)
        }
    }

    @Test fun forgedTypedSensitivePayloadsCannotBypassTheReadSidePrivacyCheck() {
        val original = requireNotNull(checkpoint().bundle())
        val mutations: List<(Bundle) -> Unit> = listOf(
            { it.putString("credentialRef", "synthetic-password") },
            { it.putString("weightsUri", "custom+fixture://user:private@host_name/resource") },
            { it.putString("weightsUri", "https://@example.invalid"); it.putString("temperature", "unfinished") },
        )
        for (mutate in mutations) {
            val changed = Bundle(original).also(mutate)
            assertNull(ModelFormCheckpoint.read(changed))
            assertSame(ComponentFormRecovery.Unavailable, ComponentFormRecovery.read(parcel(outer(changed))))
        }
    }

    @Test fun rawModelV1V2AndTypedContextV3RemainSeparateUnmodifiedReaders() {
        val raw = """{"weights_uri":"https://user:historical@host.invalid","unfinished":"""
        val legacy = ComponentFormCheckpoint(ComponentFormCheckpoint.CONFIGURE, "draft_fixture", "baseline_fixture",
            "selection_fixture", null, null, null, null, "", raw, false, false)
        for (version in 1..2) {
            val bundle = requireNotNull(legacy.bundle()).apply { putInt("version", version) }
            val restored = ComponentFormRecovery.read(parcel(outer(bundle))) as ComponentFormRecovery.Pending
            assertEquals(raw, restored.form.settings)
            assertNull(ModelFormCheckpoint.read(bundle))
            assertEquals(legacy.baseline, restored.form.baseline)
        }
        val context = ContextPolicyFormCheckpoint("draft_fixture", "baseline_fixture", "selection_context",
            PreviewContextPolicyInput("unfinished", "0", null, "fp16"), false)
        val contextState = Bundle().also { context.recovery().save(it) }
        val restored = ComponentFormRecovery.read(parcel(contextState)) as ComponentFormRecovery.TypedContextPending
        assertEquals(context.input, restored.form.input)
        assertEquals(3, contextState.getBundle(ComponentFormRecovery.KEY)!!.getInt("version"))
        assertNull(ModelFormCheckpoint.read(context.bundle()!!))
    }

    @Test fun savingFlagIsOnlyRetainedStateAndDescriptionsStayValueFree() {
        val input = PreviewModelInput(temperature = "synthetic-private-correction", credentialRef = "secret://fixture/private")
        val original = checkpoint(input, saving = true)
        val state = Bundle().also { original.recovery().save(it) }
        val restored = (ComponentFormRecovery.read(parcel(state)) as ComponentFormRecovery.TypedModelPending).form
        assertTrue(restored.wasSaving)
        assertEquals(input, restored.input)
        assertEquals("ModelFormCheckpoint", restored.toString())
        assertEquals("PreviewModelInput", restored.input.toString())
        for (marker in listOf(ComponentFormRecovery.Unavailable, ComponentFormRecovery.UriUserinfoExcluded)) {
            val value = Bundle().also { marker.save(it) }
            assertSame(marker, ComponentFormRecovery.read(parcel(value)))
        }
    }
}
