package ai.pickyour.android.preview

import ai.pickyour.core.drafts.DraftStorageFailure

/** Guidance only; the original typed result still controls storage readiness and retry authority. */
internal fun draftLoadRecoveryMessage(reason: DraftStorageFailure?): Int = when (reason) {
    DraftStorageFailure.UNSUPPORTED_VERSION -> R.string.draft_load_unsupported
    DraftStorageFailure.CORRUPT -> R.string.draft_load_corrupt
    DraftStorageFailure.LIMIT_EXCEEDED -> R.string.draft_load_limit
    DraftStorageFailure.BUSY, DraftStorageFailure.IO_FAILURE, DraftStorageFailure.CANCELLED, null ->
        R.string.draft_load_failed
}
