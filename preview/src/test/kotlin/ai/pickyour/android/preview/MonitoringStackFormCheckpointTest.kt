package ai.pickyour.android.preview

import android.os.Bundle
import android.os.Parcel
import ai.pickyour.core.preview.PreviewContextPolicyInput
import ai.pickyour.core.preview.PreviewModelInput
import ai.pickyour.core.preview.PreviewMonitoringStackInput
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class MonitoringStackFormCheckpointTest {
    private fun checkpoint(input: PreviewMonitoringStackInput = PreviewMonitoringStackInput(), saving: Boolean = false) =
        MonitoringStackFormCheckpoint("draft", "baseline", "selection", "item", "version", input, saving)
    private fun state(value: Bundle) = Bundle().apply { putBundle(ComponentFormRecovery.KEY, value) }
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
    private fun restored(value: MonitoringStackFormCheckpoint): MonitoringStackFormCheckpoint {
        val saved = Bundle().also { value.recovery().save(it) }
        return (ComponentFormRecovery.read(parcel(saved)) as ComponentFormRecovery.TypedMonitoringStackPending).form
    }
    private val textKeys = listOf("sampleRate", "retentionDays", "credentialRef")

    @Test fun exactInvalidNumericInputReferenceAndCapturedPinsSurviveActualParcel() {
        val input = PreviewMonitoringStackInput("未知\n\uD800", "900719925474099312345678", "secret://fixture/path\n")
        val before = checkpoint(input)
        val after = restored(before)
        assertEquals(input, after.input)
        assertEquals(before.draftId, after.draftId)
        assertEquals(before.baseline, after.baseline)
        assertEquals(before.selectionId, after.selectionId)
        assertEquals(before.catalogItemId, after.catalogItemId)
        assertEquals(before.catalogItemVersion, after.catalogItemVersion)
        assertFalse(after.wasSaving)
        assertEquals("typed-monitoring-stack/1", after.bundle()!!.getString("version"))
    }

    @Test fun absentEmptyNumericAndSuppliedZeroRemainDistinctPrimitiveStates() {
        for (input in listOf(PreviewMonitoringStackInput(), PreviewMonitoringStackInput("", "", null),
            PreviewMonitoringStackInput("0", "0", "secret://fixture/reference"))) {
            val value = checkpoint(input)
            assertEquals(input, restored(value).input)
            val bundle = requireNotNull(value.bundle())
            assertTrue(bundle.keySet().containsAll(textKeys))
            if (input == PreviewMonitoringStackInput()) textKeys.forEach { assertNull(bundle.getString(it)) }
        }
    }

    @Test fun malformedCredentialInputCannotEnterParcelEvenWhenOtherFieldsAreInvalid() {
        for (raw in listOf("", "unknown", "synthetic-private-password", "Bearer synthetic", "https://fixture.invalid",
            "secret://", "secret://fixture?token=value", " secret://fixture", "secret://fixture\r\n", "secret://雪")) {
            val value = checkpoint(PreviewMonitoringStackInput("invalid", "1,5", raw))
            assertNull(value.bundle())
            assertSame(ComponentFormRecovery.Unavailable, value.recovery())
            val saved = parcel(Bundle().also { value.recovery().save(it) })
            val marker = requireNotNull(saved.getBundle(ComponentFormRecovery.KEY))
            assertEquals(setOf("version", "unavailable"), marker.keySet())
            assertSame(ComponentFormRecovery.Unavailable, ComponentFormRecovery.read(saved))
            val forged = requireNotNull(checkpoint().bundle()).apply { putString("credentialRef", raw) }
            assertNull(MonitoringStackFormCheckpoint.read(parcel(forged)))
            assertSame(ComponentFormRecovery.Unavailable, ComponentFormRecovery.read(parcel(state(forged))))
        }
    }

    @Test fun combinedUtf16BoundaryRejectsOneExtraWithoutRetainingPartialInput() {
        val part = "9".repeat(ComponentFormCheckpoint.MAX_RAW_CHARACTERS / 2)
        val exact = PreviewMonitoringStackInput(part, part, null)
        assertEquals(exact, restored(checkpoint(exact)).input)
        val excess = checkpoint(exact.copy(retentionDays = part + "0"))
        assertNull(excess.bundle())
        assertSame(ComponentFormRecovery.Unavailable, excess.recovery())
        val saved = Bundle().also { excess.recovery().save(it) }
        val marker = requireNotNull(parcel(saved).getBundle(ComponentFormRecovery.KEY))
        assertEquals(setOf("version", "unavailable"), marker.keySet())
    }

    @Test fun exactKeysAndNullableStringsRejectMissingExtrasAndPrimitiveCoercion() {
        val good = requireNotNull(checkpoint().bundle())
        for (key in good.keySet()) {
            val missing = Bundle(good).apply { remove(key) }
            assertNull(MonitoringStackFormCheckpoint.read(parcel(missing)))
            assertSame(ComponentFormRecovery.Unavailable, ComponentFormRecovery.read(parcel(state(missing))))
        }
        assertNull(MonitoringStackFormCheckpoint.read(parcel(Bundle(good).apply { putString("extra", "synthetic") })))
        for (key in textKeys) for (mutation in listOf<(Bundle) -> Unit>({ it.putInt(key, 0) },
            { it.putBoolean(key, false) }, { it.putDouble(key, 1.0) }, { it.putBundle(key, Bundle()) })) {
            assertNull(MonitoringStackFormCheckpoint.read(parcel(Bundle(good).also(mutation))))
        }
        assertNull(MonitoringStackFormCheckpoint.read(Bundle(good).apply { putString("wasSaving", "false") }))
    }

    @Test fun wrongVersionFormAndKindCannotSelectAnotherRecoveryProtocol() {
        val good = requireNotNull(checkpoint().bundle())
        for (key in listOf("version", "form", "kind")) {
            val bad = Bundle(good).apply { putString(key, "other_protocol") }
            assertSame(ComponentFormRecovery.Unavailable, ComponentFormRecovery.read(parcel(state(bad))))
        }
        for (version in listOf(1, 2, 3, 4, 5, 8, 9)) {
            val bad = Bundle(good).apply { putInt("version", version) }
            assertSame(ComponentFormRecovery.Unavailable, ComponentFormRecovery.read(parcel(state(bad))))
        }
        for (version in listOf("typed-monitoring-stack/2", "typed-workload/1", "typed-model-provider/1", "typed-deployment-target/1")) {
            assertSame(ComponentFormRecovery.Unavailable, ComponentFormRecovery.read(parcel(state(Bundle(good).apply {
                putString("version", version)
            }))))
        }
    }

    @Test fun capturedIdentityMustBePresentBoundedAndNeverBorrowedFromAnotherState() {
        val good = requireNotNull(checkpoint().bundle())
        for ((key, limit) in listOf("draftId" to 128, "baseline" to 128, "selectionId" to 128,
            "catalogItemId" to 256, "catalogItemVersion" to 128)) {
            for (bad in listOf("", "x".repeat(limit + 1))) {
                assertNull(MonitoringStackFormCheckpoint.read(parcel(Bundle(good).apply { putString(key, bad) })))
            }
            assertNull(MonitoringStackFormCheckpoint.read(Bundle(good).apply { putString(key, null) }))
            val exact = requireNotNull(MonitoringStackFormCheckpoint.read(parcel(Bundle(good).apply {
                putString(key, "x".repeat(limit))
            })))
            assertEquals("x".repeat(limit), requireNotNull(exact.bundle()).getString(key))
        }
    }

    @Test fun existingRawContextAndModelRecoveryRemainDisjoint() {
        val raw = ComponentFormCheckpoint(ComponentFormCheckpoint.CONFIGURE, "draft", "baseline", "selection",
            null, null, null, null, "", "{unfinished", false, false)
        for (version in 1..2) {
            val value = requireNotNull(raw.bundle()).apply { putInt("version", version) }
            val read = ComponentFormRecovery.read(parcel(state(value))) as ComponentFormRecovery.Pending
            assertEquals("{unfinished", read.form.settings)
        }
        val context = ContextPolicyFormCheckpoint("draft", "baseline", "selection", PreviewContextPolicyInput("1", "0", null, null), false)
        assertTrue(ComponentFormRecovery.read(parcel(Bundle().also { context.recovery().save(it) })) is ComponentFormRecovery.TypedContextPending)
        val model = ModelFormCheckpoint("draft", "baseline", "selection", "item", "version", PreviewModelInput(), false)
        assertTrue(ComponentFormRecovery.read(parcel(Bundle().also { model.recovery().save(it) })) is ComponentFormRecovery.TypedModelPending)
    }

    @Test fun savingFlagAndDescriptionsPreserveInputWithoutEchoingOrGrantingAuthority() {
        val input = PreviewMonitoringStackInput("synthetic-private-input", "unknown", "secret://fixture/reference")
        val before = checkpoint(input, true)
        val after = restored(before)
        assertTrue(after.wasSaving)
        assertEquals(input, after.input)
        for (text in listOf(before.toString(), after.toString(), input.toString(), before.recovery().toString())) {
            assertFalse(text.contains("synthetic-private-input"))
            assertFalse(text.contains("unknown"))
            assertFalse(text.contains("secret://fixture/reference"))
        }
        val empty = Bundle().also { ComponentFormRecovery.None.save(it) }
        assertFalse(empty.containsKey(ComponentFormRecovery.KEY))
    }
}
