package ai.pickyour.android.preview

import ai.pickyour.core.drafts.DraftStorageFailure
import ai.pickyour.core.preview.PreviewDraftLoad
import ai.pickyour.core.preview.PreviewDraftPersistence
import ai.pickyour.core.preview.PreviewLibraryRead
import java.util.concurrent.ExecutorService
import java.util.concurrent.TimeUnit

/**
 * Observe the durable source without racing the Activity's newly owned background reader.
 * Only an actual BUSY observation waits for already queued worker tasks. The main looper is
 * not advanced, no write is repeated, and a second BUSY or any other failure remains visible.
 */
internal fun readDraftAfterOwnedWork(
    activity: PreviewActivity,
    open: () -> PreviewDraftPersistence,
): PreviewDraftLoad {
    fun read(): PreviewDraftLoad = try {
        open().load()
    } catch (failure: IllegalArgumentException) {
        if (failure.message != "Draft unavailable: BUSY") throw failure
        PreviewDraftLoad.Failed(DraftStorageFailure.BUSY)
    }

    val observed = read()
    if (observed != PreviewDraftLoad.Failed(DraftStorageFailure.BUSY)) return observed
    val worker = PreviewActivity::class.java.getDeclaredField("worker")
        .apply { isAccessible = true }.get(activity) as ExecutorService
    worker.submit {}.get(15, TimeUnit.SECONDS)
    return read()
}

/**
 * A list can contend with the same owned background reader as an individual draft.
 * Wait once only for an actual outer BUSY result. Do not advance the main looper,
 * retry a write, reinterpret entries, or hide a second BUSY or any other failure.
 */
internal fun readLibraryAfterOwnedWork(
    activity: PreviewActivity,
    read: () -> PreviewLibraryRead,
): PreviewLibraryRead {
    val observed = read()
    if (observed != PreviewLibraryRead.Failed(DraftStorageFailure.BUSY)) return observed
    val worker = PreviewActivity::class.java.getDeclaredField("worker")
        .apply { isAccessible = true }.get(activity) as ExecutorService
    worker.submit {}.get(15, TimeUnit.SECONDS)
    return read()
}
