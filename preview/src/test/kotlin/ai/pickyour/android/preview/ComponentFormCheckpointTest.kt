package ai.pickyour.android.preview

import android.os.Bundle
import android.os.Parcel
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** Bounded platform Bundle parsing and serialization, not installed process-death evidence. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class ComponentFormCheckpointTest {
    private fun pending(role: String = "primary", settings: String = "{}") = ComponentFormCheckpoint(
        ComponentFormCheckpoint.ADD, "guest-draft", "1:" + "a".repeat(64), "selection_pending",
        "synthetic-model", "1.0.0", "b".repeat(64), "model", role, settings, true, false,
    )
    private fun state() = Bundle().also { ComponentFormRecovery.Pending(pending()).save(it) }

    @Test fun maximumPrimitiveCheckpointPreservesExactUnicodeAndRemainsBoundedWhenParcelled() {
        val raw = "\uD83D\uDE00\u2028\u0000".repeat(5_000)
        assertEquals(ComponentFormCheckpoint.MAX_RAW_CHARACTERS, raw.length)
        val original = Bundle().also { ComponentFormRecovery.Pending(pending("", raw)).save(it) }
        val parcel = Parcel.obtain()
        val read = Parcel.obtain()
        try {
            parcel.writeBundle(original)
            // A bound for this checkpoint alone, not a promise about the whole Activity or Binder budget.
            assertTrue("Bounded component checkpoint must remain below 48 KiB", parcel.dataSize() < 48 * 1024)
            val bytes = parcel.marshall()
            read.unmarshall(bytes, 0, bytes.size); read.setDataPosition(0)
            val restored = ComponentFormRecovery.read(read.readBundle(javaClass.classLoader)) as ComponentFormRecovery.Pending
            assertEquals(raw, restored.form.settings)
            assertEquals("", restored.form.role)
            assertEquals("selection_pending", restored.form.selectionId)
            assertTrue(restored.form.enabled)
            assertFalse(restored.form.wasSaving)
            assertFalse(restored.form.toString().contains(raw))
        } finally { parcel.recycle(); read.recycle() }
    }

    @Test fun malformedPrimitiveTypesVersionsAndModesNeverSupplyRecoveryAuthority() {
        val changes: List<(Bundle) -> Unit> = listOf(
            { it.putString("version", "1") }, { it.putInt("version", 3) },
            { it.remove("draftId") }, { it.putInt("baseline", 1) },
            { it.putString("selectionId", "") }, { it.putBoolean("itemId", true) },
            { it.putString("catalogHash", "b".repeat(63)) }, { it.putString("kind", "unrecognized") },
            { it.putString("mode", "remove") }, { it.putString("enabled", "true") },
            { it.remove("wasSaving") }, { it.putInt("role", 0) }, { it.putByteArray("settings", byteArrayOf(1)) },
        )
        for (change in changes) {
            val state = state()
            change(state.getBundle(ComponentFormRecovery.KEY)!!)
            assertSame(ComponentFormRecovery.Unavailable, ComponentFormRecovery.read(state))
        }
        val wrongContainer = Bundle().apply { putString(ComponentFormRecovery.KEY, "untrusted") }
        assertSame(ComponentFormRecovery.Unavailable, ComponentFormRecovery.read(wrongContainer))
        assertSame(ComponentFormRecovery.None, ComponentFormRecovery.read(null))
        assertSame(ComponentFormRecovery.None, ComponentFormRecovery.read(Bundle()))
    }

    @Test fun oversizedCombinedInputBecomesAnExplicitMarkerWithoutAnyTruncatedRawPrefix() {
        val raw = "x".repeat(ComponentFormCheckpoint.MAX_RAW_CHARACTERS)
        val state = Bundle().also { ComponentFormRecovery.Pending(pending("r", raw)).save(it) }
        val saved = state.getBundle(ComponentFormRecovery.KEY)!!
        assertEquals(setOf("version", "unavailable"), saved.keySet())
        assertSame(ComponentFormRecovery.Unavailable, ComponentFormRecovery.read(state))
        ComponentFormRecovery.None.save(state)
        assertFalse(state.containsKey(ComponentFormRecovery.KEY))
    }

    @Test fun configureCheckpointRequiresItsOwnShapeAndKeepsInFlightState() {
        val configure = ComponentFormCheckpoint(ComponentFormCheckpoint.CONFIGURE, "guest-draft",
            "1:" + "a".repeat(64), "selection_existing", null, null, null, null, "", "not yet JSON", false, true)
        val state = Bundle().also { ComponentFormRecovery.Pending(configure).save(it) }
        val read = ComponentFormRecovery.read(state) as ComponentFormRecovery.Pending
        assertEquals("not yet JSON", read.form.settings)
        assertEquals("selection_existing", read.form.selectionId)
        assertTrue(read.form.wasSaving)
        state.getBundle(ComponentFormRecovery.KEY)!!.putString("catalogHash", "b".repeat(64))
        assertSame(ComponentFormRecovery.Unavailable, ComponentFormRecovery.read(state))
    }
}
