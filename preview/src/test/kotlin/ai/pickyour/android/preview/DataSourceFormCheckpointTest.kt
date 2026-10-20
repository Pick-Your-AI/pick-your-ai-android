package ai.pickyour.android.preview

import android.os.Bundle
import android.os.Parcel
import ai.pickyour.core.preview.PreviewComputeInput
import ai.pickyour.core.preview.PreviewDataSourceInput
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], manifest = Config.NONE)
class DataSourceFormCheckpointTest {
    private fun checkpoint(input: PreviewDataSourceInput = PreviewDataSourceInput(), saving: Boolean = false) =
        DataSourceFormCheckpoint("draft_fixture", "baseline_fixture", "selection_fixture", "data.fixture", "1", input, saving)
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
    private fun captured(input: PreviewDataSourceInput): Bundle = Bundle().also { checkpoint(input).recovery().save(it) }.let(::parcel)

    @Test fun optionalRawMinutesAndThreeBooleanStatesSurviveParcelWithoutCoercion() {
        for (enabled in listOf<Boolean?>(null, false, true)) for (minutes in listOf(null, "", "1,5", "9007199254740993")) {
            val input = PreviewDataSourceInput("secret://fixture/key\n", minutes, enabled)
            val restored = (ComponentFormRecovery.read(captured(input)) as ComponentFormRecovery.TypedDataSourcePending).form
            assertEquals(input, restored.input)
            assertEquals("draft_fixture", restored.draftId)
            assertEquals("baseline_fixture", restored.baseline)
            assertEquals("selection_fixture", restored.selectionId)
            assertEquals("data.fixture", restored.catalogItemId)
            assertEquals("1", restored.catalogItemVersion)
            assertFalse(restored.wasSaving)
        }
        assertEquals(PreviewDataSourceInput(), (ComponentFormRecovery.read(captured(PreviewDataSourceInput()))
            as ComponentFormRecovery.TypedDataSourcePending).form.input)
    }

    @Test fun malformedOrCredentialLikeReferenceIsExcludedWithoutRetainingOtherInput() {
        for (raw in listOf("", "synthetic-private-value", "https://fixture.invalid", "secret://", "secret://a@b", "secret://a\uD800")) {
            val input = PreviewDataSourceInput(raw, "pending numeric correction", true)
            assertSame(ComponentFormRecovery.Unavailable, checkpoint(input).recovery())
            assertNull(checkpoint(input).bundle())
            val saved = captured(input).getBundle(ComponentFormRecovery.KEY)!!
            assertEquals(setOf("version", "unavailable"), saved.keySet())
            assertTrue(saved.getBoolean("unavailable"))
        }
    }

    @Test fun aggregateUtf16LimitRejectsWholeInputWithoutAPrefixFallback() {
        val reference = "secret://fixture/key"
        val exact = PreviewDataSourceInput(reference, "x".repeat(ComponentFormCheckpoint.MAX_RAW_CHARACTERS - reference.length), false)
        assertEquals(exact, (ComponentFormRecovery.read(captured(exact)) as ComponentFormRecovery.TypedDataSourcePending).form.input)
        assertNull(checkpoint(exact.copy(refreshMinutes = exact.refreshMinutes + "x")).bundle())
        val symbols = PreviewDataSourceInput(refreshMinutes = "\uD83D\uDE00".repeat(10_000))
        assertNotNull(checkpoint(symbols).bundle())
        assertSame(ComponentFormRecovery.Unavailable, checkpoint(symbols.copy(refreshMinutes = symbols.refreshMinutes + "x")).recovery())
    }

    @Test fun exactPrimitiveShapeRejectsMissingFieldsBooleanAliasesAndExtraAuthority() {
        val original = requireNotNull(checkpoint().bundle())
        val mutations: List<(Bundle) -> Unit> = listOf(
            { it.putInt("version", 6) }, { it.putString("version", "typed-data-source/2") },
            { it.remove("connectorSecretRef") }, { it.putInt("connectorSecretRef", 1) },
            { it.remove("refreshMinutes") }, { it.putInt("refreshMinutes", 1) },
            { it.remove("enabledSupplied") }, { it.putString("enabledSupplied", "false") },
            { it.remove("enabled") }, { it.putString("enabled", "false") }, { it.putInt("enabled", 0) },
            { it.putBoolean("enabled", true) },
            { it.putString("wasSaving", "false") }, { it.remove("wasSaving") },
            { it.putString("kind", "model") }, { it.putString("command", "save") },
        )
        for (mutate in mutations) {
            val value = Bundle(original).also(mutate)
            assertNull(DataSourceFormCheckpoint.read(value))
            assertSame(ComponentFormRecovery.Unavailable, ComponentFormRecovery.read(parcel(outer(value))))
        }
        assertNotNull(DataSourceFormCheckpoint.read(Bundle(original).apply { putString("refreshMinutes", "") }))
        assertNotNull(DataSourceFormCheckpoint.read(Bundle(original).apply { putBoolean("enabledSupplied", true) }))
    }

    @Test fun everyIdentityRemainsRequiredAndBoundedWithoutCurrentStateSubstitution() {
        val original = requireNotNull(checkpoint().bundle())
        for (key in listOf("draftId", "baseline", "selectionId", "catalogItemId", "catalogItemVersion")) {
            val max = if (key == "catalogItemId") 256 else 128
            for (value in listOf("", "x".repeat(max + 1))) {
                assertNull(DataSourceFormCheckpoint.read(Bundle(original).apply { putString(key, value) }))
            }
            assertNull(DataSourceFormCheckpoint.read(Bundle(original).apply { putString(key, null) }))
            assertNull(DataSourceFormCheckpoint.read(Bundle(original).apply { remove(key) }))
            assertNull(DataSourceFormCheckpoint.read(Bundle(original).apply { putInt(key, 1) }))
            assertNotNull(DataSourceFormCheckpoint.read(Bundle(original).apply { putString(key, "x".repeat(max)) }))
        }
    }

    @Test fun legacyRawDataSourceAndNumericTypedComputeReadersRemainIndependent() {
        val raw = """{"connector_secret_ref":"unfinished"""
        val legacy = ComponentFormCheckpoint(ComponentFormCheckpoint.CONFIGURE, "draft_fixture", "baseline_fixture",
            "selection_fixture", null, null, null, null, "", raw, false, false)
        for (version in 1..2) {
            val value = requireNotNull(legacy.bundle()).apply { putInt("version", version) }
            assertEquals(raw, (ComponentFormRecovery.read(parcel(outer(value))) as ComponentFormRecovery.Pending).form.settings)
            assertNull(DataSourceFormCheckpoint.read(value))
        }
        val compute = ComputeFormCheckpoint("draft_fixture", "baseline_fixture", "selection_compute", "compute.fixture", "1",
            PreviewComputeInput("unfinished", "", ""), false)
        val state = Bundle().also { compute.recovery().save(it) }
        assertEquals(compute.input, (ComponentFormRecovery.read(parcel(state)) as ComponentFormRecovery.TypedComputePending).form.input)
        assertNull(DataSourceFormCheckpoint.read(requireNotNull(compute.bundle())))
    }

    @Test fun savingFlagAndDescriptionsCarryInputWithoutClaimingAcknowledgement() {
        val input = PreviewDataSourceInput("secret://fixture/key", "synthetic-private-correction", false)
        val state = Bundle().also { checkpoint(input, saving = true).recovery().save(it) }
        val restored = (ComponentFormRecovery.read(parcel(state)) as ComponentFormRecovery.TypedDataSourcePending).form
        assertTrue(restored.wasSaving)
        assertEquals(input, restored.input)
        assertEquals("DataSourceFormCheckpoint", restored.toString())
        assertEquals("PreviewDataSourceInput", restored.input.toString())
    }
}
