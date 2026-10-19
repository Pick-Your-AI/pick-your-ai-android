package ai.pickyour.android.preview

import android.os.Bundle
import android.os.SystemClock
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.EditText
import android.widget.TextView
import ai.pickyour.core.cache.*
import ai.pickyour.core.preview.*
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.android.controller.ActivityController
import org.robolectric.annotation.Config
import org.robolectric.annotation.LooperMode
import java.nio.file.Files
import java.nio.file.Path
import java.security.MessageDigest

/** Actual bundled loader, private cache and Activity controls in JVM simulation, not an installed app. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
@LooperMode(LooperMode.Mode.PAUSED)
class CatalogRetentionActivityTest {
    private lateinit var controller: ActivityController<PreviewActivity>
    private val activity get() = controller.get()
    private val privateRoot get() = RuntimeEnvironment.getApplication().noBackupFilesDir.toPath()
    private val directory get() = privateRoot.resolve(CatalogRetainedSnapshots.DIRECTORY)

    @After fun close() { if (::controller.isInitialized) controller.pause().stop().destroy() }

    private fun launch(saved: Bundle? = null) {
        controller = Robolectric.buildActivity(PreviewActivity::class.java)
            .create(saved).start().resume().visible()
        await { button(R.string.save_draft).isEnabled && button(R.string.retry_catalog).isEnabled &&
            views().filterIsInstance<TextView>().any { it.text.toString() == activity.getString(R.string.catalog_ready) } }
    }
    private fun views(root: View = activity.window.decorView): List<View> = buildList {
        add(root)
        if (root is ViewGroup) repeat(root.childCount) { addAll(views(root.getChildAt(it))) }
    }
    private fun button(label: Int) = views().filterIsInstance<Button>()
        .single { it.text.toString() == activity.getString(label) }
    private fun field(label: Int): EditText {
        val caption = views().filterIsInstance<TextView>().single { it.text.toString() == activity.getString(label) }
        return activity.findViewById(caption.labelFor)
    }
    private fun warning() = views().filterIsInstance<TextView>().single {
        it.text.toString() in setOf(activity.getString(R.string.catalog_retention_unconfirmed),
            activity.getString(R.string.catalog_retention_limit))
    }
    private fun await(condition: () -> Boolean) {
        val deadline = System.nanoTime() + 15_000_000_000L
        while (System.nanoTime() < deadline) {
            AutosaveUiClock.idle()
            if (condition()) return
            Thread.sleep(10)
        }
        fail("Catalog retention did not reach the explicit expected UI state")
    }
    private fun bundled() = RuntimeEnvironment.getApplication().assets.open("catalog.json").use { it.readBytes() }
    private fun hash(bytes: ByteArray) = ArtifactHash(MessageDigest.getInstance("SHA-256").digest(bytes)
        .joinToString("") { "%02x".format(it.toInt() and 255) })
    private fun files(root: Path = directory) = Files.list(root).use { paths ->
        paths.toList().associate { it.fileName.toString() to Files.readAllBytes(it).toList() }
    }
    private fun assertNoDraft() = assertTrue(PreviewDraftLibrary(privateRoot).openLegacy().load() is PreviewDraftLoad.Missing)
    private fun assertRetained(bytes: ByteArray) {
        val result = CatalogRetainedSnapshots.read(privateRoot, hash(bytes))
        assertTrue("The actual bundled loader must retain its exact original bytes", result is CacheRead.Available)
        assertArrayEquals(bytes, (result as CacheRead.Available).bytes)
    }

    @Test fun bundledLoaderRetainsExactBytesAcrossRecreationWithoutSavingPendingDraft() {
        val bytes = bundled()
        assertFalse(Files.exists(directory))
        launch()
        assertRetained(bytes)
        assertEquals(View.GONE, warning().visibility)
        assertNoDraft()
        AutosaveUiClock.pause()
        val inputStartedAt = SystemClock.uptimeMillis()
        field(R.string.draft_title).setText("pending across catalog retention")
        field(R.string.context_objective).setText("unsaved objective")
        val before = files()
        val state = Bundle().also { controller.saveInstanceState(it) }
        controller.pause().stop().destroy()
        launch(state)
        assertRetained(bytes)
        assertEquals(before, files())
        assertEquals(View.GONE, warning().visibility)
        assertEquals("pending across catalog retention", field(R.string.draft_title).text.toString())
        assertEquals("unsaved objective", field(R.string.context_objective).text.toString())
        assertNoDraft()
        assertTrue("Retention observation must precede the ordinary autosave deadline",
            SystemClock.uptimeMillis() - inputStartedAt < 750L)
    }

    @Test fun failedRetentionKeepsCatalogUsableAndExplicitReloadRecoversWithoutSaving() {
        val sentinel = byteArrayOf(9, 8, 7, 6)
        Files.write(directory, sentinel)
        launch()
        assertEquals(View.VISIBLE, warning().visibility)
        assertEquals(activity.getString(R.string.catalog_retention_unconfirmed), warning().text.toString())
        assertArrayEquals(sentinel, Files.readAllBytes(directory))
        assertTrue(button(R.string.catalog_next).isEnabled)
        AutosaveUiClock.pause()
        val inputStartedAt = SystemClock.uptimeMillis()
        field(R.string.draft_title).setText("pending while retention failed")
        field(R.string.context_objective).setText("pending explicit retry")
        assertNoDraft()
        // Remove only this test's deliberately foreign sentinel; the product does not repair it.
        Files.delete(directory)
        button(R.string.retry_catalog).performClick()
        await { button(R.string.retry_catalog).isEnabled && warning().visibility == View.GONE }
        assertRetained(bundled())
        assertEquals("pending while retention failed", field(R.string.draft_title).text.toString())
        assertEquals("pending explicit retry", field(R.string.context_objective).text.toString())
        assertNoDraft()
        assertTrue("Retention observation must precede the ordinary autosave deadline",
            SystemClock.uptimeMillis() - inputStartedAt < 750L)
    }

    @Test fun fullHistoryPreservesEveryPinAndExplainsUnretainedCatalogWithoutBlockingBrowse() {
        Files.createDirectory(directory)
        val cache = VerifiedArtifactCache(directory, PreviewCatalogPinResolver.MAX_BYTES,
            CacheRetentionLimits(PreviewCatalogRetention.MAX_SNAPSHOTS, PreviewCatalogRetention.MAX_RETAINED_BYTES))
        val original = bundled()
        val retained = (1..PreviewCatalogRetention.MAX_SNAPSHOTS).map { revision ->
            JSONObject(String(original, Charsets.UTF_8)).put("revision", revision + 100).toString().toByteArray(Charsets.UTF_8)
        }
        for (bytes in retained) assertEquals(CacheInstall.Activated(hash(bytes)), cache.install(hash(bytes), bytes.inputStream()))
        val before = files()
        val previousHead = (cache.active() as CacheRead.Available).hash
        launch()
        assertEquals(View.VISIBLE, warning().visibility)
        assertEquals(activity.getString(R.string.catalog_retention_limit), warning().text.toString())
        assertTrue(button(R.string.catalog_next).isEnabled)
        assertEquals(before, files())
        assertEquals(CacheRead.Missing, cache.pinned(hash(original)))
        AutosaveUiClock.pause()
        val inputStartedAt = SystemClock.uptimeMillis()
        field(R.string.draft_title).setText("pending at catalog quota")
        button(R.string.retry_catalog).performClick()
        await { button(R.string.retry_catalog).isEnabled && warning().visibility == View.VISIBLE }
        assertEquals(activity.getString(R.string.catalog_retention_limit), warning().text.toString())
        assertEquals(before, files())
        assertEquals(previousHead, (cache.active() as CacheRead.Available).hash)
        for (bytes in retained) assertArrayEquals(bytes, (cache.pinned(hash(bytes)) as CacheRead.Available).bytes)
        assertEquals("pending at catalog quota", field(R.string.draft_title).text.toString())
        assertNoDraft()
        assertTrue("Retention observation must precede the ordinary autosave deadline",
            SystemClock.uptimeMillis() - inputStartedAt < 750L)
    }
}
