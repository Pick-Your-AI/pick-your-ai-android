package ai.pickyour.android.preview

import android.os.Bundle
import ai.pickyour.core.preview.PreviewDraftId

/** Private navigation state, never authority to save or replay deletion. */
internal class DraftIdentityCheckpoint(val draftId: PreviewDraftId, val pendingDeleteId: PreviewDraftId?) {
    companion object {
        const val UNAVAILABLE_KEY = "draftIdentityUnavailable"

        @Suppress("DEPRECATION") // Inspect primitive types without Bundle's default-value coercion on API 26.
        fun read(state: Bundle?): DraftIdentityCheckpoint? = try {
            val unavailable = state?.get(UNAVAILABLE_KEY)
            require(unavailable == null || unavailable is Boolean && !unavailable)
            fun identity(value: Any?): PreviewDraftId? {
                if (value == null) return null
                require(value is String && value.length <= 42)
                return PreviewDraftId(value)
            }
            // Missing/null values retain the existing legacy guest-state behavior.
            DraftIdentityCheckpoint(identity(state?.get("draftId")) ?: PreviewDraftId("guest-draft"),
                identity(state?.get("pendingDeleteId")))
        } catch (_: IllegalArgumentException) {
            // Reject the complete identity pair. Do not retain or display the rejected values.
            null
        }
    }
}
