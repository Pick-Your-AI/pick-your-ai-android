package ai.pickyour.android.preview

import android.os.Bundle
import ai.pickyour.core.preview.PreviewSelectionForms
import ai.pickyour.core.preview.PreviewSelectionRecoveryPolicy

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
                ROLE -> itemId == null && itemVersion == null && catalogHash == null && kind == null && settings.isEmpty() && !enabled
                else -> false
            }

    /** Only an open add/configure form supplies its originally captured kind; legacy readers stay raw. */
    fun captureNewSettings(kind: String): ComponentFormRecovery = when {
        !bounded() -> ComponentFormRecovery.Unavailable
        PreviewSelectionRecoveryPolicy.hasRecognizedUserinfo(kind, settings) -> ComponentFormRecovery.UriUserinfoExcluded
        else -> ComponentFormRecovery.Pending(this)
    }

    fun bundle(): Bundle? = if (!bounded()) null else Bundle().apply {
        putInt("version", 2)
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
        const val ROLE = "role"
        // Includes invalid input beyond the 16,384-character parser limit without copying an unbounded form.
        const val MAX_RAW_CHARACTERS = PreviewSelectionRecoveryPolicy.MAX_RAW_CHARACTERS

        @Suppress("DEPRECATION") // Primitive type checks also work on the preview's API 26 minimum.
        fun read(value: Bundle): ComponentFormCheckpoint? {
            val version = value.get("version") as? Int ?: return null
            if (version !in 1..2) return null
            val mode = value.get("mode") as? String ?: return null
            if (version == 1 && mode == ROLE) return null
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
    data object UriUserinfoExcluded : ComponentFormRecovery
    class Pending(val form: ComponentFormCheckpoint) : ComponentFormRecovery
    class TypedContextPending(val form: ContextPolicyFormCheckpoint) : ComponentFormRecovery
    class TypedModelPending(val form: ModelFormCheckpoint) : ComponentFormRecovery
    class TypedModelProviderPending(val form: ModelProviderFormCheckpoint) : ComponentFormRecovery

    fun save(outState: Bundle) {
        outState.remove(KEY)
        when (this) {
            None -> Unit
            Unavailable -> outState.putBundle(KEY, unavailable())
            UriUserinfoExcluded -> outState.putBundle(KEY, unavailable().apply { putString("reason", URI_USERINFO) })
            is Pending -> outState.putBundle(KEY, form.bundle() ?: unavailable())
            is TypedContextPending -> outState.putBundle(KEY, form.bundle() ?: unavailable())
            is TypedModelPending -> outState.putBundle(KEY, form.bundle() ?: unavailable())
            is TypedModelProviderPending -> outState.putBundle(KEY, form.bundle() ?: unavailable())
        }
    }

    companion object {
        const val KEY = "componentFormRecovery"
        private const val URI_USERINFO = "uri_userinfo"
        private fun unavailable() = Bundle().apply { putInt("version", 1); putBoolean("unavailable", true) }

        @Suppress("DEPRECATION")
        fun read(state: Bundle?): ComponentFormRecovery = try {
            if (state == null || !state.containsKey(KEY)) None
            else {
                val value = state.get(KEY) as? Bundle
                if (value == null) Unavailable
                else if (value.get("unavailable") == true) {
                    if (value.get("version") == 1 && value.get("reason") == URI_USERINFO) UriUserinfoExcluded else Unavailable
                }
                else if (value.get("version") == ContextPolicyFormCheckpoint.VERSION) {
                    ContextPolicyFormCheckpoint.read(value)?.let(::TypedContextPending) ?: Unavailable
                } else if (value.get("version") == ModelFormCheckpoint.VERSION) {
                    ModelFormCheckpoint.read(value)?.let(::TypedModelPending) ?: Unavailable
                } else if (value.get("version") == ModelProviderFormCheckpoint.VERSION) {
                    ModelProviderFormCheckpoint.read(value)?.let(::TypedModelProviderPending) ?: Unavailable
                } else ComponentFormCheckpoint.read(value)?.let(::Pending) ?: Unavailable
            }
        } catch (_: RuntimeException) {
            // No saved input is logged and malformed UI state never supplies a replacement identity.
            Unavailable
        }
    }
}
