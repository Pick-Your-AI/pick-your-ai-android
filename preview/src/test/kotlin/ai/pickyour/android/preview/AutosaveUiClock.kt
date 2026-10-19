package ai.pickyour.android.preview

import android.os.Looper
import android.os.SystemClock
import org.robolectric.Shadows.shadowOf
import org.robolectric.shadows.ShadowChoreographer
import org.robolectric.shadows.ShadowSystem
import org.robolectric.shadows.ShadowSystemClock
import java.time.Duration

/**
 * Delivers a requested traversal frame without spending a millisecond of a Handler deadline.
 *
 * Robolectric 4.17 supports nanosecond frames on the SDK 28 used by these suites. Merely
 * pausing frames leaves ViewRoot traversal barriers installed, blocking synchronous UI
 * replies from the real persistence worker. Each idle call pumps at most one requested
 * frame; it does not run an animation to completion or advance to the next Handler task.
 */
internal object AutosaveUiClock {
    private val frameTick = Duration.ofNanos(1)

    fun pause() {
        ShadowChoreographer.setFrameDelay(frameTick)
        ShadowChoreographer.setPaused(true)
    }

    fun idle() {
        val main = shadowOf(Looper.getMainLooper())
        val paused = ShadowChoreographer.isPaused()
        val beforeMillis = SystemClock.uptimeMillis()
        main.idle()
        if (!paused) return // Preserve ordinary clock behavior outside the selected cases.

        val nextFrame = ShadowChoreographer.getNextVsyncTimeNanos()
        if (nextFrame != 0L) {
            check(nextFrame - ShadowSystem.nanoTime() == frameTick.toNanos()) {
                "The controlled frame must be the next one-nanosecond tick"
            }
            ShadowSystemClock.advanceBy(frameTick)
            main.idle()
        }
        check(SystemClock.uptimeMillis() == beforeMillis) {
            "UI frame delivery must not cross a millisecond autosave deadline"
        }
    }
}
