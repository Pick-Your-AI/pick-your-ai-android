package ai.pickyour.android.preview

import android.os.Bundle
import ai.pickyour.core.preview.PreviewHostingPlatformInput
import ai.pickyour.core.preview.PreviewHostingPlatformSettings

/** Private bounded input with original identity, never a Selection payload or a fresh save token. */
internal class HostingPlatformFormCheckpoint(
    val draftId: String,
    val baseline: String,
    val selectionId: String,
    val catalogItemId: String,
    val catalogItemVersion: String,
    val input: PreviewHostingPlatformInput,
    val wasSaving: Boolean,
) {
    private fun bounded(): Boolean {
        val total = listOf(input.region, input.accountRef, input.networkIsolation)
            .sumOf { it?.length?.toLong() ?: 0L }
        return draftId.length in 1..128 && baseline.length in 1..128 && selectionId.length in 1..128 &&
            catalogItemId.length in 1..256 && catalogItemVersion.length in 1..128 &&
            total <= ComponentFormCheckpoint.MAX_RAW_CHARACTERS &&
            (input.networkIsolation == null || input.networkIsolation in
                PreviewHostingPlatformSettings.fields.single { it.key == PreviewHostingPlatformSettings.NETWORK }.enumValues)
    }

    fun recovery(): ComponentFormRecovery = when {
        !bounded() -> ComponentFormRecovery.Unavailable
        !PreviewHostingPlatformSettings.canRetainAccountReference(input.accountRef) -> ComponentFormRecovery.Unavailable
        else -> ComponentFormRecovery.TypedHostingPlatformPending(this)
    }

    fun bundle(): Bundle? {
        if (recovery() !is ComponentFormRecovery.TypedHostingPlatformPending) return null
        return Bundle().apply {
            putString("version", VERSION)
            putString("form", FORM)
            putString("kind", PreviewHostingPlatformSettings.KIND)
            putString("draftId", draftId)
            putString("baseline", baseline)
            putString("selectionId", selectionId)
            putString("catalogItemId", catalogItemId)
            putString("catalogItemVersion", catalogItemVersion)
            putString("region", input.region)
            putString("accountRef", input.accountRef)
            putString("networkIsolation", input.networkIsolation)
            putBoolean("wasSaving", wasSaving)
        }
    }

    override fun toString() = "HostingPlatformFormCheckpoint"

    companion object {
        const val VERSION = "typed-hosting-platform/1"
        private const val FORM = "hostingPlatform"
        private val INPUT_KEYS = listOf("region", "accountRef", "networkIsolation")
        private val KEYS = setOf("version", "form", "kind", "draftId", "baseline", "selectionId",
            "catalogItemId", "catalogItemVersion", "wasSaving") + INPUT_KEYS

        @Suppress("DEPRECATION") // Exact primitive reads retain the API 26 minimum.
        fun read(value: Bundle): HostingPlatformFormCheckpoint? {
            if (value.keySet() != KEYS || value.get("version") != VERSION || value.get("form") != FORM ||
                value.get("kind") != PreviewHostingPlatformSettings.KIND) return null
            val draftId = value.get("draftId") as? String ?: return null
            val baseline = value.get("baseline") as? String ?: return null
            val selectionId = value.get("selectionId") as? String ?: return null
            val itemId = value.get("catalogItemId") as? String ?: return null
            val itemVersion = value.get("catalogItemVersion") as? String ?: return null
            val saving = value.get("wasSaving") as? Boolean ?: return null
            val raw = INPUT_KEYS.map { value.get(it) }
            if (raw.any { it != null && it !is String }) return null
            return HostingPlatformFormCheckpoint(draftId, baseline, selectionId, itemId, itemVersion,
                PreviewHostingPlatformInput(raw[0] as String?, raw[1] as String?, raw[2] as String?), saving)
                .takeIf { it.recovery() is ComponentFormRecovery.TypedHostingPlatformPending }
        }
    }
}
