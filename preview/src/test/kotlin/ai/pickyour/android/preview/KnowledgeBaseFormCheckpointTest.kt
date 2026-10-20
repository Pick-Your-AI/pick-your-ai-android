package ai.pickyour.android.preview

import android.os.Bundle
import android.os.Parcel
import ai.pickyour.core.preview.PreviewKnowledgeBaseInput
import ai.pickyour.core.preview.PreviewContextPolicyInput
import ai.pickyour.core.preview.PreviewModelInput
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], manifest = Config.NONE)
class KnowledgeBaseFormCheckpointTest {
    private fun checkpoint(input: PreviewKnowledgeBaseInput = PreviewKnowledgeBaseInput(), saving: Boolean = false) =
        KnowledgeBaseFormCheckpoint("draft_fixture", "baseline_fixture", "selection_fixture", "knowledge.fixture", "1", input, saving)
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
    private fun captured(input: PreviewKnowledgeBaseInput): Bundle = Bundle().also { checkpoint(input).recovery().save(it) }.let(::parcel)

    @Test fun absentEmptyInvalidAndExactNumericInputSurviveParcelWithOriginalIdentity() {
        for (input in listOf(PreviewKnowledgeBaseInput(), PreviewKnowledgeBaseInput("", "", ""),
            PreviewKnowledgeBaseInput("unfinished", "1,5", " \t\r\n", false),
            PreviewKnowledgeBaseInput("9007199254740993", "0", "1e3", true))) {
            val restored = (ComponentFormRecovery.read(captured(input)) as ComponentFormRecovery.TypedKnowledgeBasePending).form
            assertEquals(input, restored.input)
            assertEquals("draft_fixture", restored.draftId)
            assertEquals("baseline_fixture", restored.baseline)
            assertEquals("selection_fixture", restored.selectionId)
            assertEquals("knowledge.fixture", restored.catalogItemId)
            assertEquals("1", restored.catalogItemVersion)
            assertFalse(restored.wasSaving)
        }
    }

    @Test fun booleanPresenceAndFalseRemainDistinctAndMalformedPairsAreRejected() {
        for (acl in listOf(null, false, true)) {
            val input = PreviewKnowledgeBaseInput(aclEnforced = acl)
            val state = captured(input)
            val value = state.getBundle(ComponentFormRecovery.KEY)!!
            assertEquals(acl != null, value.getBoolean("aclSupplied"))
            assertEquals(acl ?: false, value.getBoolean("aclEnforced"))
            assertEquals(acl, (ComponentFormRecovery.read(state) as ComponentFormRecovery.TypedKnowledgeBasePending).form.input.aclEnforced)
        }
        val malformed = checkpoint().bundle()!!.apply { putBoolean("aclEnforced", true) }
        assertNull(KnowledgeBaseFormCheckpoint.read(malformed))
        assertSame(ComponentFormRecovery.Unavailable, ComponentFormRecovery.read(parcel(outer(malformed))))
    }

    @Test fun totalUtf16BudgetIncludesAllThreeRawFieldsAndRejectsPrefixFallback() {
        val exact = PreviewKnowledgeBaseInput("x".repeat(ComponentFormCheckpoint.MAX_RAW_CHARACTERS - 2), "0", " ", false)
        assertNotNull(checkpoint(exact).bundle())
        assertEquals(exact, (ComponentFormRecovery.read(captured(exact)) as ComponentFormRecovery.TypedKnowledgeBasePending).form.input)
        val oversized = exact.copy(refreshMinutes = "  ")
        assertSame(ComponentFormRecovery.Unavailable, checkpoint(oversized).recovery())
        assertNull(checkpoint(oversized).bundle())
        val value = captured(oversized).getBundle(ComponentFormRecovery.KEY)!!
        assertEquals(setOf("version", "unavailable"), value.keySet())
        assertTrue(value.getBoolean("unavailable"))
        val unicode = PreviewKnowledgeBaseInput(refreshMinutes = "\uD83D\uDE00".repeat(10_000))
        assertNotNull(checkpoint(unicode).bundle())
        assertSame(ComponentFormRecovery.Unavailable, checkpoint(unicode.copy(chunkSizeTokens = "1")).recovery())
    }

    @Test fun exactPrimitiveShapeNeverCoercesMissingNumbersOrBooleanValues() {
        val original = requireNotNull(checkpoint().bundle())
        val mutations: List<(Bundle) -> Unit> = listOf(
            { it.putInt("version", 1) }, { it.putString("wasSaving", "false") }, { it.remove("wasSaving") },
            { it.remove("chunkSizeTokens") }, { it.putInt("chunkSizeTokens", 1) },
            { it.putBoolean("chunkOverlapTokens", false) }, { it.putDouble("chunkOverlapTokens", 0.0) },
            { it.remove("chunkOverlapTokens") }, { it.putBundle("refreshMinutes", Bundle()) }, { it.remove("refreshMinutes") },
            { it.putString("aclSupplied", "true") }, { it.putInt("aclEnforced", 0) },
            { it.putString("aclEnforced", null) }, { it.remove("aclEnforced") }, { it.remove("aclSupplied") },
            { it.putString("extra", "not interpreted") },
        )
        for (mutate in mutations) {
            val value = Bundle(original).also(mutate)
            assertNull(KnowledgeBaseFormCheckpoint.read(value))
            assertSame(ComponentFormRecovery.Unavailable, ComponentFormRecovery.read(parcel(outer(value))))
        }
        for (key in listOf("chunkSizeTokens", "chunkOverlapTokens", "refreshMinutes")) {
            assertNotNull(KnowledgeBaseFormCheckpoint.read(Bundle(original).apply { putString(key, null) }))
            assertNotNull(KnowledgeBaseFormCheckpoint.read(Bundle(original).apply { putString(key, "") }))
        }
    }

    @Test fun descriptiveVersionFormAndKindCannotSelectAnotherRecoveryProtocol() {
        val original = requireNotNull(checkpoint().bundle())
        assertEquals("typed-knowledge-base/1", original.getString("version"))
        for (version in 0..8) {
            val changed = Bundle(original).apply { putInt("version", version) }
            assertNull(KnowledgeBaseFormCheckpoint.read(changed))
            assertSame(ComponentFormRecovery.Unavailable, ComponentFormRecovery.read(outer(changed)))
        }
        for (version in listOf("1", "typed-knowledge-base/2", "typed-hosting-platform/1", "typed-storage/1", "typed-accelerator/1")) {
            val changed = Bundle(original).apply { putString("version", version) }
            assertNull(KnowledgeBaseFormCheckpoint.read(changed))
            assertSame(ComponentFormRecovery.Unavailable, ComponentFormRecovery.read(outer(changed)))
        }
        for (form in listOf("", "configure", "add", "configure_model")) {
            assertNull(KnowledgeBaseFormCheckpoint.read(Bundle(original).apply { putString("form", form) }))
        }
        for (kind in listOf("", "model", "context_policy", "KNOWLEDGE_BASE")) {
            assertNull(KnowledgeBaseFormCheckpoint.read(Bundle(original).apply { putString("kind", kind) }))
        }
    }

    @Test fun everyCapturedIdentityIsRequiredAndBoundedWithoutCurrentStateSubstitution() {
        val original = requireNotNull(checkpoint().bundle())
        for (key in listOf("draftId", "baseline", "selectionId", "catalogItemId", "catalogItemVersion")) {
            val max = if (key == "catalogItemId") 256 else 128
            for (value in listOf("", "x".repeat(max + 1))) {
                assertNull(KnowledgeBaseFormCheckpoint.read(Bundle(original).apply { putString(key, value) }))
            }
            assertNull(KnowledgeBaseFormCheckpoint.read(Bundle(original).apply { putString(key, null) }))
            assertNull(KnowledgeBaseFormCheckpoint.read(Bundle(original).apply { remove(key) }))
            assertNull(KnowledgeBaseFormCheckpoint.read(Bundle(original).apply { putInt(key, 1) }))
            assertNotNull(KnowledgeBaseFormCheckpoint.read(Bundle(original).apply { putString(key, "x".repeat(max)) }))
        }
    }

    @Test fun legacyRawAndTypedContextAndModelReadersKeepTheirOriginalBoundaries() {
        val raw = """{"chunk_size_tokens":"""
        val legacy = ComponentFormCheckpoint(ComponentFormCheckpoint.CONFIGURE, "draft_fixture", "baseline_fixture",
            "selection_fixture", null, null, null, null, "", raw, false, false)
        for (version in 1..2) {
            val value = requireNotNull(legacy.bundle()).apply { putInt("version", version) }
            assertEquals(raw, (ComponentFormRecovery.read(parcel(outer(value))) as ComponentFormRecovery.Pending).form.settings)
            assertNull(KnowledgeBaseFormCheckpoint.read(value))
        }
        val context = ContextPolicyFormCheckpoint("draft_fixture", "baseline_fixture", "selection_context",
            PreviewContextPolicyInput("unfinished", "0", null, "fp16"), false)
        val contextState = Bundle().also { context.recovery().save(it) }
        assertEquals(context.input, (ComponentFormRecovery.read(parcel(contextState)) as ComponentFormRecovery.TypedContextPending).form.input)
        val model = ModelFormCheckpoint("draft_fixture", "baseline_fixture", "selection_model", "model.fixture", "1",
            PreviewModelInput(temperature = "1,5", credentialRef = "secret://fixture/key"), false)
        val modelState = Bundle().also { model.recovery().save(it) }
        assertEquals(model.input, (ComponentFormRecovery.read(parcel(modelState)) as ComponentFormRecovery.TypedModelPending).form.input)
        assertEquals(3, contextState.getBundle(ComponentFormRecovery.KEY)!!.getInt("version"))
        assertEquals(4, modelState.getBundle(ComponentFormRecovery.KEY)!!.getInt("version"))
        assertNull(KnowledgeBaseFormCheckpoint.read(context.bundle()!!))
        assertNull(KnowledgeBaseFormCheckpoint.read(model.bundle()!!))
    }

    @Test fun savingFlagRetainsInputOnlyAndDescriptionsNeverEchoPrivateValues() {
        val input = PreviewKnowledgeBaseInput("synthetic-private-correction", "1,5", "", false)
        val state = Bundle().also { checkpoint(input, saving = true).recovery().save(it) }
        val restored = (ComponentFormRecovery.read(parcel(state)) as ComponentFormRecovery.TypedKnowledgeBasePending).form
        assertTrue(restored.wasSaving)
        assertEquals(input, restored.input)
        assertEquals("KnowledgeBaseFormCheckpoint", restored.toString())
        assertEquals("PreviewKnowledgeBaseInput", restored.input.toString())
    }
}
