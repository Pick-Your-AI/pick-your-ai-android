package ai.pickyour.android.preview

import android.os.Handler
import android.os.Looper
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import ai.pickyour.core.drafts.DraftStorageFailure
import ai.pickyour.core.preview.PreviewDraftId
import ai.pickyour.core.preview.PreviewDraftLibrary
import ai.pickyour.core.preview.PreviewDraftLoad
import ai.pickyour.core.preview.PreviewLibraryRead
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.Shadows.shadowOf
import org.robolectric.android.controller.ActivityController
import org.robolectric.annotation.Config
import org.robolectric.annotation.LooperMode
import java.nio.channels.FileChannel
import java.nio.file.Files
import java.nio.file.StandardOpenOption.CREATE
import java.nio.file.StandardOpenOption.WRITE
import java.util.concurrent.CountDownLatch
import java.util.concurrent.ExecutorService
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/** Real nonblocking library observations; no product retry or main-looper dispatch is introduced. */
@RunWith(org.robolectric.RobolectricTestRunner::class)
@Config(sdk = [28])
@LooperMode(LooperMode.Mode.PAUSED)
class LibraryReadObservationTest {
    private lateinit var controller: ActivityController<PreviewActivity>
    private val activity get() = controller.get()
    private val root get() = activity.noBackupFilesDir.toPath()
    private val library get() = PreviewDraftLibrary(root)
    private val worker get() = PreviewActivity::class.java.getDeclaredField("worker")
        .apply { isAccessible = true }.get(activity) as ExecutorService

    @Before fun start() {
        controller = Robolectric.buildActivity(PreviewActivity::class.java).setup()
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(15)
        while (System.nanoTime() < deadline) {
            shadowOf(Looper.getMainLooper()).idle()
            if (views(activity.window.decorView).filterIsInstance<Button>()
                    .any { it.text.toString() == activity.getString(R.string.save_draft) && it.isEnabled }) break
            Thread.sleep(10)
        }
        repeat(3) {
            shadowOf(Looper.getMainLooper()).idle()
            worker.submit {}.get(15, TimeUnit.SECONDS)
            shadowOf(Looper.getMainLooper()).idle()
        }
        assertEquals(PreviewDraftLoad.Missing, readDraftAfterOwnedWork(activity) { library.openLegacy() })
    }

    @After fun close() { controller.pause().stop().destroy() }

    private fun views(view: View): List<View> = buildList {
        add(view)
        if (view is ViewGroup) for (i in 0 until view.childCount) addAll(views(view.getChildAt(i)))
    }

    private fun bytes() = Files.walk(root).use { paths ->
        paths.filter { Files.isRegularFile(it) }.toList().associate {
            root.relativize(it).toString() to Files.readAllBytes(it).toList()
        }
    }

    @Test fun busyListWaitsOnceForOwnedWorkerWithoutDispatchingTheMainLooperOrChangingFiles() {
        val before = bytes()
        val held = CountDownLatch(1)
        val release = CountDownLatch(1)
        val pendingUi = AtomicBoolean(false)
        Handler(Looper.getMainLooper()).post { pendingUi.set(true) }
        val holder = worker.submit {
            FileChannel.open(root.resolve(".preview-library.lock"), CREATE, WRITE).use { channel ->
                channel.lock().use {
                    held.countDown()
                    check(release.await(15, TimeUnit.SECONDS))
                }
            }
        }
        val observations = mutableListOf<PreviewLibraryRead>()
        try {
            assertTrue(held.await(15, TimeUnit.SECONDS))
            val result = readLibraryAfterOwnedWork(activity) {
                library.list().also {
                    observations += it
                    if (observations.size == 1) {
                        assertEquals(PreviewLibraryRead.Failed(DraftStorageFailure.BUSY), it)
                        release.countDown()
                    }
                }
            }
            assertTrue("A real BUSY observation must settle the queued owner before the second read: $result",
                result is PreviewLibraryRead.Available)
            assertEquals(2, observations.size)
            assertSame(observations[1], result)
            val entries = (result as PreviewLibraryRead.Available).entries
            assertEquals(listOf(PreviewDraftId("guest-draft")), entries.map { it.id })
            assertEquals(PreviewDraftLoad.Missing, entries.single().state)
            assertFalse(entries.single().deletionPending)
            assertFalse("The observer must not pump pending UI callbacks", pendingUi.get())
            assertEquals(before, bytes())
        } finally {
            release.countDown()
            holder.get(15, TimeUnit.SECONDS)
        }
    }

    @Test fun availableListReturnsItsExactResultWhileUnrelatedWorkRemainsQueued() {
        val before = bytes()
        val held = CountDownLatch(1)
        val release = CountDownLatch(1)
        val holder = worker.submit { held.countDown(); check(release.await(15, TimeUnit.SECONDS)) }
        val observations = mutableListOf<PreviewLibraryRead>()
        try {
            assertTrue(held.await(15, TimeUnit.SECONDS))
            val result = readLibraryAfterOwnedWork(activity) { library.list().also { observations += it } }
            assertTrue(result is PreviewLibraryRead.Available)
            assertEquals(1, observations.size)
            assertSame(observations.single(), result)
            assertFalse("A successful read must not wait for unrelated queued work", holder.isDone)
            assertEquals(before, bytes())
        } finally {
            release.countDown()
            holder.get(15, TimeUnit.SECONDS)
        }
    }

    @Test fun persistentBusyReturnsTheSecondActualFailureWithoutLoopingOrOpeningUi() {
        val before = bytes()
        val pendingUi = AtomicBoolean(false)
        Handler(Looper.getMainLooper()).post { pendingUi.set(true) }
        FileChannel.open(root.resolve(".preview-library.lock"), CREATE, WRITE).use { channel ->
            channel.lock().use {
                val observations = mutableListOf<PreviewLibraryRead>()
                val result = readLibraryAfterOwnedWork(activity) { library.list().also { observations += it } }
                assertEquals(PreviewLibraryRead.Failed(DraftStorageFailure.BUSY), result)
                assertEquals("Only one re-observation is permitted", 2, observations.size)
                assertSame(observations[1], result)
                assertFalse(pendingUi.get())
                assertEquals(before, bytes())
            }
        }
    }

    @Test fun corruptLibraryRootIsReturnedOnceWithoutAdoptingDifferentContents() {
        val corruptRoot = activity.filesDir.toPath().resolve("library-observation-corrupt")
        Files.createDirectory(corruptRoot)
        Files.createDirectory(corruptRoot.resolve(".preview-library.lock"))
        val corrupted = PreviewDraftLibrary(corruptRoot)
        val before = bytes()
        val held = CountDownLatch(1)
        val release = CountDownLatch(1)
        val holder = worker.submit { held.countDown(); check(release.await(15, TimeUnit.SECONDS)) }
        val observations = mutableListOf<PreviewLibraryRead>()
        try {
            assertTrue(held.await(15, TimeUnit.SECONDS))
            val result = readLibraryAfterOwnedWork(activity) { corrupted.list().also { observations += it } }
            assertEquals(PreviewLibraryRead.Failed(DraftStorageFailure.CORRUPT), result)
            assertEquals(1, observations.size)
            assertSame(observations.single(), result)
            assertFalse(holder.isDone)
            assertTrue(Files.isDirectory(corruptRoot.resolve(".preview-library.lock")))
            assertEquals(before, bytes())
        } finally {
            release.countDown()
            holder.get(15, TimeUnit.SECONDS)
        }
    }
}
