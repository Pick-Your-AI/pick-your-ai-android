package ai.pickyour.android.preview

import android.os.Bundle
import ai.pickyour.core.preview.PreviewSelectionForms

/** Small primitive UI state. It carries input and identity, never a command or save authority. */
internal class ComponentFormCheckpoint(
    val mode: String,
    val draftId: String,
    val baseline: String,
    val selectionId: String,
    val itemId: String?,
    val itemVersion: String?,
    val catalogHash: String?,
    val kind: String?,
    val role: String,
    val settings: String,
    val enabled: Boolean,
    val wasSaving: Boolean,
) {
    private fun bounded(): Boolean =
        draftId.length in 1..128 && baseline.length in 1..128 && selectionId.length in 1..128 &&
            role.length.toLong() + settings.length <= MAX_RAW_CHARACTERS && when (mode) {
                ADD -> itemId != null && itemId.length in 1..256 && itemVersion != null &&
                    itemVersion.length in 1..128 && catalogHash?.matches(Regex("[a-f0-9]{64}")) == true &&
                    kind != null && PreviewSelectionForms.supported(kind)
                CONFIGURE -> itemId == null && itemVersion == null && catalogHash == null && kind == null && role.isEmpty()
                else -> false
            }

    fun bundle(): Bundle? = if (!bounded()) null else Bundle().apply {
        putInt("version", 1)
        putString("mode", mode)
        putString("draftId", draftId)
        putString("baseline", baseline)
        putString("selectionId", selectionId)
        putString("itemId", itemId)
        putString("itemVersion", itemVersion)
        putString("catalogHash", catalogHash)
        putString("kind", kind)
        putString("role", role)
        putString("settings", settings)
        putBoolean("enabled", enabled)
        putBoolean("wasSaving", wasSaving)
    }

    override fun toString() = "ComponentFormCheckpoint"

    companion object {
        const val ADD = "add"
        const val CONFIGURE = "configure"
        // Includes invalid input beyond the 16,384-character parser limit without copying an unbounded form.
        const val MAX_RAW_CHARACTERS = 20_000

        @Suppress("DEPRECATION") // Primitive type checks also work on the preview's API 26 minimum.
        fun read(value: Bundle): ComponentFormCheckpoint? {
            if (value.get("version") != 1) return null
            val mode = value.get("mode") as? String ?: return null
            val draftId = value.get("draftId") as? String ?: return null
            val baseline = value.get("baseline") as? String ?: return null
            val selectionId = value.get("selectionId") as? String ?: return null
            val role = value.get("role") as? String ?: return null
            val settings = value.get("settings") as? String ?: return null
            val enabled = value.get("enabled") as? Boolean ?: return null
            val wasSaving = value.get("wasSaving") as? Boolean ?: return null
            fun optionalString(key: String): String? {
                val raw = value.get(key)
                require(raw == null || raw is String)
                return raw as? String
            }
            return ComponentFormCheckpoint(mode, draftId, baseline, selectionId,
                optionalString("itemId"), optionalString("itemVersion"), optionalString("catalogHash"),
                optionalString("kind"), role, settings, enabled, wasSaving).takeIf { it.bounded() }
        }
    }
}

internal sealed interface ComponentFormRecovery {
    data object None : ComponentFormRecovery
    data object Unavailable : ComponentFormRecovery
    class Pending(val form: ComponentFormCheckpoint) : ComponentFormRecovery

    fun save(outState: Bundle) {
        outState.remove(KEY)
        when (this) {
            None -> Unit
            Unavailable -> outState.putBundle(KEY, unavailable())
            is Pending -> outState.putBundle(KEY, form.bundle() ?: unavailable())
        }
    }

    companion object {
        const val KEY = "componentFormRecovery"
        private fun unavailable() = Bundle().apply { putInt("version", 1); putBoolean("unavailable", true) }

        @Suppress("DEPRECATION")
        fun read(state: Bundle?): ComponentFormRecovery = try {
            if (state == null || !state.containsKey(KEY)) None
            else {
                val value = state.get(KEY) as? Bundle
                if (value == null || value.get("unavailable") == true) Unavailable
                else ComponentFormCheckpoint.read(value)?.let(::Pending) ?: Unavailable
            }
        } catch (_: RuntimeException) {
            // No saved input is logged and malformed UI state never supplies a replacement identity.
            Unavailable
        }
    }
}
