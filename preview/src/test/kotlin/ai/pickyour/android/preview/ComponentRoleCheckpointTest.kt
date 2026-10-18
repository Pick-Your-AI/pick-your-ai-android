package ai.pickyour.android.preview

import android.os.Bundle
import android.os.Parcel
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class ComponentRoleCheckpointTest {
    private fun role(raw: String = "candidate", saving: Boolean = false) = ComponentFormCheckpoint(
        ComponentFormCheckpoint.ROLE, "guest-draft", "3:" + "a".repeat(64), "selection_existing",
        null, null, null, null, raw, "", false, saving)
    private fun state(checkpoint: ComponentFormCheckpoint = role()) = Bundle().also { ComponentFormRecovery.Pending(checkpoint).save(it) }

    @Test fun roleRoundTripRetainsOnlyExactInputAndOriginalIdentityWithinTheParcelBound() {
        val raw = "\uD83D\uDE00\u2028\u0000".repeat(5_000)
        val original = state(role(raw, true))
        assertEquals(2, original.getBundle(ComponentFormRecovery.KEY)!!.getInt("version"))
        val parcel = Parcel.obtain()
        val read = Parcel.obtain()
        try {
            parcel.writeBundle(original)
            assertTrue(parcel.dataSize() < 48 * 1024)
            val bytes = parcel.marshall()
            read.unmarshall(bytes, 0, bytes.size); read.setDataPosition(0)
            val pending = ComponentFormRecovery.read(read.readBundle(javaClass.classLoader)) as ComponentFormRecovery.Pending
            assertEquals(ComponentFormCheckpoint.ROLE, pending.form.mode)
            assertEquals(raw, pending.form.role)
            assertEquals("", pending.form.settings)
            assertEquals("guest-draft", pending.form.draftId)
            assertEquals("3:" + "a".repeat(64), pending.form.baseline)
            assertEquals("selection_existing", pending.form.selectionId)
            assertTrue(pending.form.wasSaving)
            assertNull(pending.form.catalogHash)
            assertFalse(pending.form.toString().contains(raw))
        } finally { parcel.recycle(); read.recycle() }
    }

    @Test fun versionOneAddAndSettingsKeepTheirMeaningAndCannotIntroduceRoleMode() {
        val add = ComponentFormCheckpoint(ComponentFormCheckpoint.ADD, "guest-draft", "3:" + "a".repeat(64), "selection_pending",
            "fixture.model", "1.0", "b".repeat(64), "model", "invalid pending role", "{ unfinished", false, false)
        val configure = ComponentFormCheckpoint(ComponentFormCheckpoint.CONFIGURE, "guest-draft", "3:" + "a".repeat(64),
            "selection_existing", null, null, null, null, "", "{ unfinished", false, false)
        for (checkpoint in listOf(add, configure)) {
            val old = state(checkpoint)
            old.getBundle(ComponentFormRecovery.KEY)!!.putInt("version", 1)
            val restored = ComponentFormRecovery.read(old) as ComponentFormRecovery.Pending
            assertEquals(checkpoint.mode, restored.form.mode)
            assertEquals(checkpoint.role, restored.form.role)
            assertEquals(checkpoint.settings, restored.form.settings)
            val resaved = Bundle().also(restored::save)
            assertEquals(2, resaved.getBundle(ComponentFormRecovery.KEY)!!.getInt("version"))
            assertEquals(checkpoint.mode, (ComponentFormRecovery.read(resaved) as ComponentFormRecovery.Pending).form.mode)
        }
        val smuggled = state()
        smuggled.getBundle(ComponentFormRecovery.KEY)!!.putInt("version", 1)
        assertSame(ComponentFormRecovery.Unavailable, ComponentFormRecovery.read(smuggled))
        val legacySettings = state(configure)
        legacySettings.getBundle(ComponentFormRecovery.KEY)!!.putString("role", "candidate")
        assertSame(ComponentFormRecovery.Unavailable, ComponentFormRecovery.read(legacySettings))
    }

    @Test fun roleModeRejectsBorrowedSettingsCatalogActivationAndMalformedPrimitiveState() {
        val mutations: List<(Bundle) -> Unit> = listOf(
            { it.putString("settings", "{}") }, { it.putBoolean("enabled", true) },
            { it.putString("itemId", "fixture.model") }, { it.putString("itemVersion", "1.0") },
            { it.putString("catalogHash", "b".repeat(64)) }, { it.putString("kind", "model") },
            { it.putInt("role", 1) }, { it.remove("role") }, { it.putString("version", "2") },
            { it.putInt("version", 3) }, { it.putString("mode", "replace_role") },
            { it.putString("selectionId", "") }, { it.putString("wasSaving", "true") },
        )
        for (mutate in mutations) {
            val checkpoint = state()
            mutate(checkpoint.getBundle(ComponentFormRecovery.KEY)!!)
            assertSame(ComponentFormRecovery.Unavailable, ComponentFormRecovery.read(checkpoint))
        }
    }

    @Test fun oversizedRoleProducesOnlyUnavailableStateWithoutAValidPrefix() {
        val raw = "candidate" + " ".repeat(ComponentFormCheckpoint.MAX_RAW_CHARACTERS)
        val saved = state(role(raw))
        assertEquals(setOf("version", "unavailable"), saved.getBundle(ComponentFormRecovery.KEY)!!.keySet())
        assertSame(ComponentFormRecovery.Unavailable, ComponentFormRecovery.read(saved))
        ComponentFormRecovery.None.save(saved)
        assertFalse(saved.containsKey(ComponentFormRecovery.KEY))
    }
}
