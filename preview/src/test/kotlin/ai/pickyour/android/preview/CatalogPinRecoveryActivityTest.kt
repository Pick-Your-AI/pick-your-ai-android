package ai.pickyour.android.preview

import android.app.AlertDialog
import android.os.Bundle
import android.os.Looper
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
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.android.controller.ActivityController
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import org.robolectric.annotation.LooperMode
import org.robolectric.shadows.ShadowAlertDialog
import java.nio.channels.FileChannel
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardOpenOption.WRITE
import java.security.MessageDigest
import java.util.concurrent.CountDownLatch
import java.util.concurrent.ExecutorService
import java.util.concurrent.TimeUnit

/** Actual preview controls and real private cache files in a simulated Activity, not an installed archive. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
@LooperMode(LooperMode.Mode.PAUSED)
class CatalogPinRecoveryActivityTest {
    private lateinit var controller: ActivityController<PreviewActivity>
    private val activity get() = controller.get()

    @Before fun launch() {
        controller = Robolectric.buildActivity(PreviewActivity::class.java).setup()
        await { button(R.string.save_draft).isEnabled && button(R.string.catalog_next).isEnabled }
        assertTrue(adapter().load() is PreviewDraftLoad.Missing)
    }
    @After fun close() { controller.pause().stop().destroy() }

    private fun views(root: View = activity.window.decorView): List<View> = buildList {
        add(root)
        if (root is ViewGroup) repeat(root.childCount) { addAll(views(root.getChildAt(it))) }
    }
    private fun button(label: Int, root: View = activity.window.decorView) =
        views(root).filterIsInstance<Button>().single { it.text.toString() == activity.getString(label) }
    private fun inspect(index: Int = 1) = views().filterIsInstance<Button>()
        .single { it.text.toString() == activity.getString(R.string.catalog_pin_inspect, index) }
    private fun field(label: Int): EditText {
        val caption = views().filterIsInstance<TextView>().single { it.text.toString() == activity.getString(label) }
        return activity.findViewById(caption.labelFor)
    }
    private fun samples() = views().filterIsInstance<CatalogBrowseSection>().single().let(::views)
        .filterIsInstance<Button>().filter { it.text.toString().contains("FICTIONAL") }
    private fun comparison() = views().filterIsInstance<CatalogComparisonSection>().single()
    private fun catalog() = views().filterIsInstance<CatalogBrowseSection>().single()
    private fun removals() = views().filterIsInstance<Button>().filter { it.text.toString().startsWith("Remove component ") }
    private fun dialog() = requireNotNull(ShadowAlertDialog.getLatestAlertDialog())
    private fun message(window: AlertDialog = dialog()) = shadowOf(window).message.toString()
    private fun dialogButton(label: Int, window: AlertDialog = dialog()) = button(label, window.window!!.decorView)
    private fun idle() = AutosaveUiClock.idle()
    private fun await(condition: () -> Boolean) {
        val deadline = System.nanoTime() + 15_000_000_000L
        while (System.nanoTime() < deadline) {
            idle()
            if (condition()) return
            Thread.sleep(10)
        }
        fail("Timed out waiting for the explicit UI state")
    }
    private fun awaitResult(label: Int) = await { dialog().isShowing && message().contains(activity.getString(label)) }
    private fun state() = Bundle().also { controller.saveInstanceState(it) }
    private fun assertDraftState(before: Bundle, includeTray: Boolean = true) {
        val after = state()
        val keys = listOf("title", "objective", "contextInput", "selectedId", "selectedVersion", "selectedCatalogHash",
            "draftId", "draftBaseline", "pendingDeleteId", "selectionResolved", "contextInputsPresent", "workloadInputsPresent",
            "workload.rejectedEdit", "workload.classification", "catalogQuery", "catalogTier", "catalogKind",
            "catalogSort", "catalogPage", "catalogSnapshot") + (0..7).map { "workload.$it" } +
            if (includeTray) listOf("comparisonIds", "comparisonVersions", "comparisonHashes", "comparisonDifferencesOnly") else emptyList()
        @Suppress("DEPRECATION")
        keys.forEach { assertEquals(it, before.get(it), after.get(it)) }
    }
    private fun adapter() = PreviewDraftLibrary(activity.noBackupFilesDir.toPath()).openLegacy()
    private fun loaded() = (readDraftAfterOwnedWork(activity, ::adapter) as PreviewDraftLoad.Available).record
    private fun save() {
        button(R.string.save_draft).performClick()
        await { !button(R.string.save_draft).isEnabled && views().filterIsInstance<TextView>()
            .any { it.text.toString() == activity.getString(R.string.draft_saved) } }
    }
    private fun recreate(saved: Bundle = state()) {
        controller.pause().stop().destroy()
        controller = Robolectric.buildActivity(PreviewActivity::class.java).create(saved).start().resume().visible()
        // Catalog and draft storage complete independently; inspection deliberately rejects a busy draft.
        await { button(R.string.retry_catalog).isEnabled && samples().isNotEmpty() && button(R.string.reopen_draft).isEnabled }
    }
    private fun document() = activity.assets.open("catalog.json").bufferedReader(Charsets.UTF_8).use { JSONObject(it.readText()) }
    private fun bytes(root: JSONObject) = root.toString().toByteArray(Charsets.UTF_8)
    private fun item(root: JSONObject, kind: String = "model"): JSONObject {
        val items = root.getJSONArray("items")
        return (0 until items.length()).map { items.getJSONObject(it) }.single { it.getString("kind") == kind }
    }
    private fun hash(payload: ByteArray) = ArtifactHash(MessageDigest.getInstance("SHA-256").digest(payload)
        .joinToString("") { "%02x".format(it.toInt() and 255) })
    private fun pin(root: JSONObject) = PreviewCatalogPin(item(root).getString("id"), item(root).getString("version"), hash(bytes(root)).hex)
    private fun showCatalog(root: JSONObject): PreviewCatalogBrowser {
        val browser = requireNotNull(PreviewCatalogBrowser.read(bytes(root)))
        catalog().show(browser)
        comparison().show(browser)
        return browser
    }
    private fun addPin(kind: String = "model") {
        field(R.string.catalog_search).setText("cat.$kind.fixture")
        await { samples().size == 1 }
        samples().single().performClick()
        val detail = dialog()
        dialogButton(R.string.catalog_compare_add, detail).performClick()
        await { !detail.isShowing }
    }
    private fun restorePin(value: PreviewCatalogPin) {
        val saved = state()
        saved.putStringArrayList("comparisonIds", arrayListOf(value.id))
        saved.putStringArrayList("comparisonVersions", arrayListOf(value.version))
        saved.putStringArrayList("comparisonHashes", arrayListOf(value.snapshotHash))
        recreate(saved)
    }
    private fun cacheRoot() = activity.noBackupFilesDir.toPath().resolve(CatalogRetainedSnapshots.DIRECTORY)
    private fun cache(): VerifiedArtifactCache {
        Files.createDirectories(cacheRoot())
        return VerifiedArtifactCache(cacheRoot(), PreviewCatalogPinResolver.MAX_BYTES, CacheRetentionLimits(16, 16_777_216))
    }
    private fun install(cache: VerifiedArtifactCache, payload: ByteArray) =
        assertEquals(CacheInstall.Activated(hash(payload)), cache.install(hash(payload), payload.inputStream()))
    private fun files(root: Path) = Files.list(root).use { paths ->
        paths.toList().associate { it.fileName.toString() to Files.readAllBytes(it).toList() }
    }
    private fun privateContents() = activity.noBackupFilesDir.walkTopDown().filter { it.isFile }
        .associate { it.relativeTo(activity.noBackupFilesDir).path to it.readBytes().toList() }
    private fun worker() = PreviewActivity::class.java.getDeclaredField("worker").apply { isAccessible = true }.get(activity) as ExecutorService

    @Test fun currentPinnedInspectionUsesCapturedDetailsWithoutChangingRetainedBytesOrSaving() {
        save()
        val baseline = loaded().baselineIdentity
        field(R.string.draft_title).setText("pending pinned inspection")
        field(R.string.context_context).setText("")
        field(R.string.workload_budget).setText("12.00")
        addPin("compute_profile")
        val before = state()
        val retainedBefore = files(cacheRoot())
        inspect().performClick()
        awaitResult(R.string.catalog_pin_captured)
        assertTrue(message().contains("cat.compute_profile.fixture"))
        assertTrue(message().contains("\"2026.10-demo\""))
        assertTrue(message().contains(before.getStringArrayList("comparisonHashes")!!.single()))
        assertTrue(message().contains(activity.getString(R.string.catalog_pin_prices)))
        assertTrue(message().contains(activity.getString(R.string.catalog_price_unavailable)))
        assertFalse(message().contains("Recorded unit_price.amount:"))
        assertEquals(retainedBefore, files(cacheRoot()))
        dialogButton(R.string.catalog_pin_browse).performClick()
        idle()
        assertFalse(dialog().isShowing)
        assertTrue(field(R.string.catalog_search).hasFocus())
        assertEquals("cat.compute_profile.fixture", field(R.string.catalog_search).text.toString())
        assertDraftState(before)
        assertEquals(baseline, loaded().baselineIdentity)
        assertNull(shadowOf(activity).nextStartedActivity)
    }

    @Test fun retainedRetiredVersionDisplaysExactOlderBytesWithoutActivatingOrReplacingComparisonPins() {
        save()
        val baseline = loaded().baselineIdentity
        field(R.string.context_objective).setText("pending historical objective")
        val old = document().put("revision", 3)
        item(old).put("status", "retired")
        showCatalog(old)
        addPin()
        val current = document().put("revision", 4)
        item(current).put("name", "FICTIONAL current replacement")
        showCatalog(current)
        val cache = cache()
        install(cache, bytes(old))
        install(cache, bytes(current))
        val disk = files(cacheRoot())
        val before = state()
        inspect().performClick()
        awaitResult(R.string.catalog_pin_retained)
        assertTrue(message().contains(pin(old).snapshotHash))
        assertTrue(message().contains("Revision: 3"))
        assertTrue(message().contains("retired"))
        assertTrue(message().contains(activity.getString(R.string.catalog_pin_recorded_status)))
        assertFalse(message().contains("FICTIONAL current replacement"))
        assertFalse(message().contains(hash(bytes(current)).hex))
        assertEquals(hash(bytes(current)), (cache.active() as CacheRead.Available).hash)
        assertEquals(disk, files(cacheRoot()))
        assertFalse(button(R.string.catalog_compare_open).isEnabled)
        assertDraftState(before)
        assertEquals(baseline, loaded().baselineIdentity)
        assertNull(shadowOf(activity).nextStartedActivity)
    }

    @Test fun missingSnapshotRetryRecoversOnlyTheSamePinAfterExactBytesBecomeAvailable() {
        val old = document().put("revision", 5)
        showCatalog(old)
        addPin()
        val current = document().put("revision", 6)
        showCatalog(current)
        val before = state()
        val retainedBefore = files(cacheRoot())
        inspect().performClick()
        awaitResult(R.string.catalog_pin_missing)
        val failed = dialog()
        assertTrue(message().contains(pin(old).snapshotHash))
        assertEquals(retainedBefore, files(cacheRoot()))
        val cache = cache()
        assertEquals(CacheRead.Missing, cache.pinned(hash(bytes(old))))
        install(cache, bytes(old))
        install(cache, bytes(current))
        val disk = files(cacheRoot())
        dialogButton(R.string.catalog_pin_retry, failed).performClick()
        awaitResult(R.string.catalog_pin_retained)
        assertNotSame(failed, dialog())
        assertFalse(failed.isShowing)
        assertTrue(message().contains(pin(old).snapshotHash))
        assertFalse(message().contains(hash(bytes(current)).hex))
        assertEquals(disk, files(cacheRoot()))
        assertDraftState(before)
        assertTrue(adapter().load() is PreviewDraftLoad.Missing)
    }

    @Test fun unsupportedInvalidAndAbsentVersionsHaveDistinctRecoverableMessagesAndPreserveBytes() {
        save()
        val baseline = loaded().baselineIdentity
        field(R.string.context_objective).setText("pending unsupported recovery")
        val original = document()
        val unsupported = document().put("schema_version", "2.0.0")
        val invalid = document().also { item(it).put("status", "withdrawn") }
        val missing = document().also { item(it).put("version", "another-opaque-version") }
        val limited = document()
        val many = limited.getJSONArray("items")
        val prototype = item(limited)
        while (many.length() <= 256) many.put(JSONObject(prototype.toString()).put("version", "alternative-${many.length()}"))
        val cases = listOf(unsupported to R.string.catalog_pin_unsupported, invalid to R.string.catalog_pin_invalid,
            missing to R.string.catalog_pin_version_missing, limited to R.string.catalog_pin_limit)
        val cache = cache()
        for ((payload, _) in cases) install(cache, bytes(payload))
        install(cache, bytes(original))
        val disk = files(cacheRoot())
        for ((payload, label) in cases) {
            val exact = pin(original).copy(snapshotHash = hash(bytes(payload)).hex)
            restorePin(exact)
            val before = state()
            inspect().performClick()
            awaitResult(label)
            assertTrue(message().contains(exact.snapshotHash))
            assertFalse(message().contains("Recorded item status:"))
            assertTrue(dialogButton(R.string.catalog_pin_retry).isEnabled)
            assertTrue(dialogButton(R.string.catalog_pin_browse).isEnabled)
            assertEquals(disk, files(cacheRoot()))
            assertDraftState(before)
            assertEquals(baseline, loaded().baselineIdentity)
            dialog().onBackPressed()
        }
    }

    @Test fun corruptAndBusyRetainedCachesNeverFallBackAndBusyRecoveryUsesTheExactPin() {
        val old = document().put("revision", 7)
        val current = document().put("revision", 8)
        val cache = cache()
        install(cache, bytes(old))
        install(cache, bytes(current))
        restorePin(pin(old))
        val before = state()
        FileChannel.open(cacheRoot().resolve("lock"), WRITE).use { channel ->
            channel.lock().use {
                inspect().performClick()
                awaitResult(R.string.catalog_pin_busy)
                assertFalse(message().contains("Recorded item status:"))
            }
        }
        dialogButton(R.string.catalog_pin_retry).performClick()
        awaitResult(R.string.catalog_pin_retained)
        assertTrue(message().contains(pin(old).snapshotHash))
        Files.write(cacheRoot().resolve("${pin(old).snapshotHash}.bin"), bytes(current))
        val disk = files(cacheRoot())
        dialogButton(R.string.catalog_pin_retry).performClick()
        awaitResult(R.string.catalog_pin_corrupt)
        assertFalse(message().contains("Recorded item status:"))
        assertEquals(disk, files(cacheRoot()))
        assertEquals(hash(bytes(current)), (cache.active() as CacheRead.Available).hash)
        assertDraftState(before)
    }

    @Test fun cancelledLoadingAndResultCallbacksCannotReopenOrNavigateAfterDismissal() {
        addPin()
        val before = state()
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val queued = worker().submit { entered.countDown(); assertTrue(release.await(10, TimeUnit.SECONDS)) }
        try {
            assertTrue(entered.await(5, TimeUnit.SECONDS))
            inspect().performClick()
            val loading = dialog()
            val retry = dialogButton(R.string.catalog_pin_retry, loading)
            val browse = dialogButton(R.string.catalog_pin_browse, loading)
            assertFalse(retry.isEnabled)
            retry.performClick()
            assertSame(loading, dialog())
            loading.onBackPressed()
            assertFalse(loading.isShowing)
            field(R.string.draft_title).requestFocus()
            retry.performClick(); browse.performClick()
            release.countDown()
            queued.get(5, TimeUnit.SECONDS)
            worker().submit {}.get(5, TimeUnit.SECONDS)
            idle()
            assertSame(loading, dialog())
            assertFalse(loading.isShowing)
            assertTrue(field(R.string.draft_title).hasFocus())
        } finally { release.countDown() }
        inspect().performClick()
        awaitResult(R.string.catalog_pin_captured)
        val result = dialog()
        val retry = dialogButton(R.string.catalog_pin_retry, result)
        val browse = dialogButton(R.string.catalog_pin_browse, result)
        result.onBackPressed()
        retry.performClick(); browse.performClick(); idle()
        assertSame(result, dialog())
        assertFalse(result.isShowing)
        assertTrue(field(R.string.draft_title).hasFocus())
        assertDraftState(before)
    }

    @Test fun actualPauseRetiresExactPinRecoveryAndCapturedActions() {
        AutosaveUiClock.pause()
        save()
        val baseline = loaded().baselineIdentity
        field(R.string.draft_title).setText("pending across exact pin pause")
        field(R.string.context_objective).setText("preserved recovery objective")
        addPin()
        val before = state()
        val retainedBytes = files(cacheRoot())
        val entry = inspect()
        entry.performClick()
        awaitResult(R.string.catalog_pin_captured)
        val previous = dialog()
        val retry = dialogButton(R.string.catalog_pin_retry, previous)
        val browse = dialogButton(R.string.catalog_pin_browse, previous)
        val details = dialogButton(R.string.catalog_detail_browser_open, previous)
        field(R.string.draft_title).requestFocus()
        controller.pause()
        try {
            assertFalse("Pause must retire the exact pin recovery window", previous.isShowing)
            retry.performClick(); browse.performClick(); details.performClick(); entry.performClick()
            idle()
            assertSame(previous, dialog())
            assertFalse(previous.isShowing)
            assertTrue(field(R.string.draft_title).hasFocus())
        } finally { controller.resume() }
        retry.performClick(); browse.performClick(); details.performClick(); idle()
        assertSame(previous, dialog())
        assertFalse(previous.isShowing)
        assertTrue(field(R.string.draft_title).hasFocus())
        assertDraftState(before)
        assertEquals(retainedBytes, files(cacheRoot()))
        assertEquals(baseline, loaded().baselineIdentity)
        inspect().performClick()
        awaitResult(R.string.catalog_pin_captured)
        assertNotSame(previous, dialog())
        assertTrue(message().contains(before.getStringArrayList("comparisonHashes")!!.single()))
        assertTrue(message().contains("Requested item ID: \"cat.model.fixture\""))
        assertDraftState(before)
        assertEquals(retainedBytes, files(cacheRoot()))
        assertEquals(baseline, loaded().baselineIdentity)
        assertNull(shadowOf(activity).nextStartedActivity)
    }

    @Test fun actualPauseRetiresPinnedCompleteFieldAndValueCallbacks() {
        AutosaveUiClock.pause()
        save()
        val baseline = loaded().baselineIdentity
        field(R.string.context_objective).setText("pending during full pin inspection")
        addPin()
        val before = state()
        val disk = privateContents()
        for (openValue in listOf(false, true)) {
            inspect().performClick()
            awaitResult(R.string.catalog_pin_captured)
            val parent = dialog()
            val oldOpen = dialogButton(R.string.catalog_detail_browser_open, parent)
            oldOpen.performClick(); idle()
            val fields = dialog()
            val index = (0 until fields.listView.adapter.count).single {
                fields.listView.adapter.getItem(it).toString().startsWith("catalog_item.version") }
            if (openValue) { shadowOf(fields).clickOnItem(index); idle() }
            val previous = dialog()
            val oldAction = previous.getButton(if (openValue) AlertDialog.BUTTON_NEGATIVE else AlertDialog.BUTTON_POSITIVE)
            controller.pause()
            try {
                assertFalse("Pause retires the pin's complete field or value window", previous.isShowing)
                shadowOf(fields).clickOnItem(index)
                oldAction.performClick(); oldOpen.performClick(); idle()
                assertSame(previous, dialog())
                assertFalse(previous.isShowing)
            } finally { controller.resume() }
            shadowOf(fields).clickOnItem(index)
            oldAction.performClick(); oldOpen.performClick(); idle()
            assertSame(previous, dialog())
            assertFalse(previous.isShowing)

            inspect().performClick()
            awaitResult(R.string.catalog_pin_captured)
            dialogButton(R.string.catalog_detail_browser_open).performClick(); idle()
            val freshFields = dialog()
            shadowOf(freshFields).clickOnItem(index); idle()
            val freshValue = dialog()
            assertTrue(message().contains("\"2026.10-demo\""))
            shadowOf(fields).clickOnItem(index)
            oldAction.performClick(); oldOpen.performClick(); idle()
            assertSame(freshValue, dialog())
            assertTrue(freshValue.isShowing)
            assertDraftState(before)
            assertEquals(disk, privateContents())
            assertEquals(baseline, loaded().baselineIdentity)
        }
        assertNull(shadowOf(activity).nextStartedActivity)
    }

    @Test fun pausedPinReadsAndPostedCompletionsCannotReviveTheirOrigin() {
        AutosaveUiClock.pause()
        save()
        val baseline = loaded().baselineIdentity
        field(R.string.draft_title).setText("pending while exact pin completion waits")
        val old = document().put("revision", 41)
        item(old).put("version", "pause.recorded.v1").put("name", "FICTIONAL retained pause version")
        showCatalog(old)
        addPin()
        val current = document().put("revision", 42)
        item(current).put("version", "pause.current.v2").put("name", "FICTIONAL current substitute")
        showCatalog(current)
        val cache = cache()
        install(cache, bytes(old)); install(cache, bytes(current))
        val before = state()
        val disk = privateContents()
        for (completionPosted in listOf(false, true)) for (resumeBeforeDelivery in listOf(false, true)) {
            val entered = CountDownLatch(1)
            val release = CountDownLatch(1)
            val held = if (completionPosted) null else worker().submit {
                entered.countDown(); release.await(15, TimeUnit.SECONDS)
            }
            var resumed = true
            try {
                if (held != null) assertTrue(entered.await(15, TimeUnit.SECONDS))
                inspect().performClick()
                val loading = dialog()
                val retry = dialogButton(R.string.catalog_pin_retry, loading)
                val browse = dialogButton(R.string.catalog_pin_browse, loading)
                assertTrue(message().contains(activity.getString(R.string.catalog_pin_loading)))
                // The worker has posted its real read result, but the paused main looper has not adopted it.
                if (completionPosted) worker().submit {}.get(15, TimeUnit.SECONDS)
                controller.pause()
                resumed = false
                assertFalse(loading.isShowing)
                if (resumeBeforeDelivery) { controller.resume(); resumed = true }
                release.countDown()
                held?.get(15, TimeUnit.SECONDS)
                worker().submit {}.get(15, TimeUnit.SECONDS)
                idle()
                retry.performClick(); browse.performClick(); idle()
                assertSame(loading, dialog())
                assertFalse(loading.isShowing)
                if (!resumed) { controller.resume(); resumed = true }
                retry.performClick(); browse.performClick(); idle()
                assertSame(loading, dialog())
                assertFalse(loading.isShowing)

                inspect().performClick()
                awaitResult(R.string.catalog_pin_retained)
                val fresh = dialog()
                assertTrue(message().contains(pin(old).snapshotHash))
                assertTrue(message().contains("\"pause.recorded.v1\""))
                assertTrue(message().contains("Revision: 41"))
                assertFalse(message().contains("pause.current.v2"))
                assertFalse(message().contains("FICTIONAL current substitute"))
                assertFalse(message().contains(hash(bytes(current)).hex))
                retry.performClick(); browse.performClick(); idle()
                assertSame(fresh, dialog())
                assertTrue(fresh.isShowing)
                assertDraftState(before)
                assertEquals(disk, privateContents())
                assertEquals(baseline, loaded().baselineIdentity)
                fresh.cancel(); idle()
            } finally {
                release.countDown()
                if (!resumed) controller.resume()
            }
        }
        assertNull(shadowOf(activity).nextStartedActivity)
    }

    @Test fun newerInspectionReplacesQueuedOriginAndRetainedControlsCannotReplaceItsResult() {
        addPin("model")
        addPin("accelerator")
        val before = state()
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val queued = worker().submit { entered.countDown(); assertTrue(release.await(10, TimeUnit.SECONDS)) }
        try {
            assertTrue(entered.await(5, TimeUnit.SECONDS))
            inspect(1).performClick()
            val first = dialog()
            val priorRetry = dialogButton(R.string.catalog_pin_retry, first)
            val priorBrowse = dialogButton(R.string.catalog_pin_browse, first)
            inspect(2).performClick()
            val second = dialog()
            assertNotSame(first, second)
            assertFalse(first.isShowing)
            release.countDown()
            queued.get(5, TimeUnit.SECONDS)
            awaitResult(R.string.catalog_pin_captured)
            val result = dialog()
            assertTrue(message().contains("Requested item ID: \"cat.accelerator.fixture\""))
            assertFalse(message().contains("Requested item ID: \"cat.model.fixture\""))
            field(R.string.draft_title).requestFocus()
            priorRetry.performClick(); priorBrowse.performClick(); idle()
            assertSame(result, dialog())
            assertTrue(result.isShowing)
            assertTrue(field(R.string.draft_title).hasFocus())
            assertDraftState(before)
        } finally { release.countDown() }
    }

    @Test fun actualDetailAndPinInspectionRetireEachOthersRetainedActionsWithoutChangingPendingFields() {
        save()
        val baseline = loaded().baselineIdentity
        field(R.string.context_objective).setText("pending guarded history")
        addPin("compute_profile")
        samples().single().performClick()
        val parent = dialog()
        val oldPrice = dialogButton(R.string.catalog_price_browse, parent)
        val oldCompare = dialogButton(R.string.catalog_compare_add, parent)
        val before = state()
        inspect().performClick()
        awaitResult(R.string.catalog_pin_captured)
        val pinned = dialog()
        assertFalse(parent.isShowing)
        parent.getButton(AlertDialog.BUTTON_POSITIVE).performClick()
        parent.getButton(AlertDialog.BUTTON_NEUTRAL).performClick()
        oldPrice.performClick(); oldCompare.performClick(); idle()
        assertSame(pinned, dialog())
        assertTrue(pinned.isShowing)
        val retiredRetry = dialogButton(R.string.catalog_pin_retry, pinned)
        val retiredBrowse = dialogButton(R.string.catalog_pin_browse, pinned)
        samples().single().performClick()
        val newDetail = dialog()
        assertFalse(pinned.isShowing)
        field(R.string.draft_title).requestFocus()
        retiredRetry.performClick(); retiredBrowse.performClick(); idle()
        assertSame(newDetail, dialog())
        assertTrue(newDetail.isShowing)
        assertTrue(field(R.string.draft_title).hasFocus())
        assertDraftState(before)
        assertEquals(baseline, loaded().baselineIdentity)
        assertNull(shadowOf(activity).nextStartedActivity)
    }

    @Test fun comparisonAndPinInspectionRetireEachOthersRetainedActionsAndPreserveTrayPreferences() {
        save()
        val baseline = loaded().baselineIdentity
        // Dialog frames must not advance this read-only case beyond the autosave deadline.
        AutosaveUiClock.pause()
        val pendingSince = SystemClock.uptimeMillis()
        field(R.string.context_objective).setText("pending comparison origin")
        addPin("model")
        addPin("accelerator")
        val before = state()
        button(R.string.catalog_compare_open).performClick()
        val comparison = dialog()
        val retainedToggle = views(comparison.window!!.decorView).filterIsInstance<android.widget.CheckBox>().single()
        assertFalse(retainedToggle.isChecked)
        inspect(1).performClick()
        awaitResult(R.string.catalog_pin_captured)
        val pinned = dialog()
        retainedToggle.performClick()
        assertDraftState(before)
        assertFalse(comparison.isShowing)
        val retainedRetry = dialogButton(R.string.catalog_pin_retry, pinned)
        button(R.string.catalog_compare_open).performClick()
        val currentComparison = dialog()
        assertFalse(pinned.isShowing)
        retainedRetry.performClick(); idle()
        assertSame(currentComparison, dialog())
        assertTrue(currentComparison.isShowing)
        assertFalse(views(currentComparison.window!!.decorView).filterIsInstance<android.widget.CheckBox>().single().isChecked)
        assertDraftState(before)
        assertEquals(baseline, loaded().baselineIdentity)
        assertTrue("Read-only actions stay before autosave", SystemClock.uptimeMillis() in pendingSince until pendingSince + 750)
    }

    @Test fun snapshotReplacementRemovalReloadAndRecreationRetireTheActualInspectionOrigin() {
        save()
        val baseline = loaded().baselineIdentity
        // Dialog frames must not advance this read-only case beyond the autosave deadline.
        AutosaveUiClock.pause()
        val pendingSince = SystemClock.uptimeMillis()
        field(R.string.draft_title).setText("pending lifecycle recovery")
        addPin()
        val before = state()
        val oldEntry = inspect()
        oldEntry.performClick()
        awaitResult(R.string.catalog_pin_captured)
        val oldDialog = dialog()
        val oldRetry = dialogButton(R.string.catalog_pin_retry)
        showCatalog(document().put("revision", 9))
        assertFalse(oldDialog.isShowing)
        oldEntry.performClick(); oldRetry.performClick(); idle()
        assertSame(oldDialog, dialog())
        inspect().performClick()
        awaitResult(R.string.catalog_pin_retained)
        assertTrue(message().contains(before.getStringArrayList("comparisonHashes")!!.single()))
        val retainedSnapshot = dialog()
        val retainedSnapshotRetry = dialogButton(R.string.catalog_pin_retry)
        button(R.string.retry_catalog).performClick()
        await { button(R.string.retry_catalog).isEnabled }
        assertFalse(retainedSnapshot.isShowing)
        retainedSnapshotRetry.performClick(); idle()
        assertSame(retainedSnapshot, dialog())
        inspect().performClick()
        awaitResult(R.string.catalog_pin_captured)
        val beforeRecreation = dialog()
        val retainedRetry = dialogButton(R.string.catalog_pin_retry)
        val retainedBrowse = dialogButton(R.string.catalog_pin_browse)
        val retainedEntry = inspect()
        recreate()
        await { button(R.string.save_draft).isEnabled }
        field(R.string.draft_title).requestFocus()
        retainedRetry.performClick(); retainedBrowse.performClick(); retainedEntry.performClick(); idle()
        assertSame(beforeRecreation, dialog())
        assertFalse(beforeRecreation.isShowing)
        assertTrue(field(R.string.draft_title).hasFocus())
        assertDraftState(before)
        inspect().performClick()
        awaitResult(R.string.catalog_pin_captured)
        val removed = dialog()
        val removedRetry = dialogButton(R.string.catalog_pin_retry)
        removals().single().performClick()
        assertFalse(removed.isShowing)
        removedRetry.performClick(); idle()
        assertSame(removed, dialog())
        assertTrue(state().getStringArrayList("comparisonIds")!!.isEmpty())
        assertDraftState(before, includeTray = false)
        assertEquals(baseline, loaded().baselineIdentity)
        assertTrue("Read-only actions stay before autosave", SystemClock.uptimeMillis() in pendingSince until pendingSince + 750)
    }

    @Test fun changedSavedBaselineRejectsRetainedRetryAndBrowseWithoutReplacingPendingInputs() {
        save()
        val original = loaded().baselineIdentity
        addPin()
        inspect().performClick()
        awaitResult(R.string.catalog_pin_captured)
        val retained = dialog()
        val retry = dialogButton(R.string.catalog_pin_retry)
        val browse = dialogButton(R.string.catalog_pin_browse)
        field(R.string.draft_title).setText("new acknowledged baseline")
        save()
        val baseline = loaded().baselineIdentity
        assertNotEquals(original, baseline)
        field(R.string.context_objective).setText("new pending objective")
        field(R.string.draft_title).requestFocus()
        val before = state()
        retry.performClick(); browse.performClick(); idle()
        assertSame(retained, dialog())
        assertTrue(field(R.string.draft_title).hasFocus())
        assertDraftState(before)
        assertEquals(baseline, loaded().baselineIdentity)
        retained.onBackPressed()
        inspect().performClick()
        awaitResult(R.string.catalog_pin_captured)
        assertNotSame(retained, dialog())
    }

    @Test fun storageOperationRetiresInspectionEvenWhenTheMissingDraftBaselineIsUnchanged() {
        val restored = state()
        restored.putString("pendingDeleteId", "draft-11111111-1111-1111-1111-111111111111")
        recreate(restored)
        assertTrue(button(R.string.library_retry_delete).isEnabled)
        addPin()
        inspect().performClick()
        awaitResult(R.string.catalog_pin_captured)
        val inspection = dialog()
        val retry = dialogButton(R.string.catalog_pin_retry)
        val browse = dialogButton(R.string.catalog_pin_browse)
        assertNull(state().getString("draftBaseline"))
        button(R.string.reopen_draft).performClick()
        val confirmation = dialog()
        confirmation.getButton(AlertDialog.BUTTON_POSITIVE).performClick()
        await { button(R.string.reopen_draft).isEnabled && !confirmation.isShowing }
        assertTrue(adapter().load() is PreviewDraftLoad.Missing)
        assertNull(state().getString("draftBaseline"))
        assertFalse("Starting another storage operation must retire the earlier inspection", inspection.isShowing)
        field(R.string.draft_title).setText("pending after explicit missing-draft reconciliation")
        field(R.string.context_objective).setText("new unsaved objective")
        field(R.string.draft_title).requestFocus()
        val before = state()
        retry.performClick(); browse.performClick(); idle()
        assertSame(confirmation, dialog())
        assertTrue(field(R.string.draft_title).hasFocus())
        assertDraftState(before)
        inspect().performClick()
        awaitResult(R.string.catalog_pin_captured)
        assertDraftState(before)
        assertTrue(adapter().load() is PreviewDraftLoad.Missing)
    }

    @Test fun retainedDirectorySymlinkIsRejectedWithoutOpeningTheTargetOrChangingThePin() {
        val old = document().put("revision", 10)
        val target = Files.createDirectory(activity.noBackupFilesDir.toPath().resolve("synthetic-other-cache"))
        val cache = VerifiedArtifactCache(target, PreviewCatalogPinResolver.MAX_BYTES)
        install(cache, bytes(old))
        val beforeFiles = files(target)
        // Replace only the known initial bundled-cache fixture with this test's foreign symlink.
        val bundled = activity.assets.open("catalog.json").use { it.readBytes() }
        val initial = files(cacheRoot())
        assertEquals(setOf("active", "lock", "${hash(bundled).hex}.bin"), initial.keys)
        initial.keys.forEach { Files.delete(cacheRoot().resolve(it)) }
        Files.delete(cacheRoot())
        Files.createSymbolicLink(cacheRoot(), target)
        restorePin(pin(old))
        val before = state()
        inspect().performClick()
        awaitResult(R.string.catalog_pin_corrupt)
        assertFalse(message().contains("Recorded item status:"))
        assertTrue(Files.isSymbolicLink(cacheRoot()))
        assertEquals(beforeFiles, files(target))
        assertDraftState(before)
    }

    @Test
    @Config(qualifiers = "ar-rEG-ldrtl-w320dp-h640dp")
    @GraphicsMode(GraphicsMode.Mode.NATIVE)
    fun narrowRtlLargeTextRecoveryActionsKeepMeasuredTargetsAndCompleteLabels() {
        RuntimeEnvironment.setFontScale(2f)
        recreate()
        addPin()
        val root = activity.window.decorView
        assertMeasuredButtons(root, listOf(inspect()))
        inspect().performClick()
        awaitResult(R.string.catalog_pin_captured)
        assertMeasuredButtons(dialog().window!!.decorView, listOf(dialogButton(R.string.catalog_pin_retry),
            dialogButton(R.string.catalog_pin_browse), dialog().getButton(AlertDialog.BUTTON_NEGATIVE)))
    }

    private fun assertMeasuredButtons(root: View, controls: List<Button>) {
        val density = activity.resources.displayMetrics.density
        val width = (320 * density).toInt()
        val height = (640 * density).toInt()
        root.measure(View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(height, View.MeasureSpec.EXACTLY))
        root.layout(0, 0, width, height)
        assertEquals(View.LAYOUT_DIRECTION_RTL, root.layoutDirection)
        controls.forEach { control ->
            assertTrue(control.isShown)
            assertTrue(control.text.isNotBlank())
            assertTrue("48dp target height: ${control.text}", control.height >= (48 * density).toInt())
            assertTrue("48dp target width: ${control.text}", control.width >= (48 * density).toInt())
            control.requestRectangleOnScreen(android.graphics.Rect(0, 0, control.width, control.height), true)
            val bounds = android.graphics.Rect(0, 0, control.width, control.height)
            var child: View = control
            while (child !== root) {
                val parent = child.parent as ViewGroup
                parent.offsetDescendantRectToMyCoords(child, bounds)
                assertTrue("Action is clipped by ${parent.javaClass.simpleName}: ${control.text}",
                    android.graphics.Rect().also(parent::getDrawingRect).contains(bounds))
                child = parent
            }
            val label = requireNotNull(control.layout)
            assertTrue(label.lineCount > 0)
            assertTrue("Complete label height: ${control.text}", label.height > 0 &&
                label.height <= control.height - control.compoundPaddingTop - control.compoundPaddingBottom)
            val left = control.scrollX.toFloat()
            val right = left + control.width - control.compoundPaddingLeft - control.compoundPaddingRight
            assertTrue(right > left)
            repeat(label.lineCount) { line ->
                assertEquals("No action ellipsis: ${control.text}", 0, label.getEllipsisCount(line))
                assertTrue("Complete horizontal label: ${control.text}", label.getLineLeft(line) >= left &&
                    label.getLineRight(line) <= right && label.getLineLeft(line) <= label.getLineRight(line))
            }
            assertEquals("Full action text: ${control.text}", label.text.length, label.getLineEnd(label.lineCount - 1))
        }
    }
}
