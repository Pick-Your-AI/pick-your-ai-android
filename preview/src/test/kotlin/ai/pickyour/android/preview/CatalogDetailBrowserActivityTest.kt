package ai.pickyour.android.preview

import android.app.AlertDialog
import android.os.Bundle
import android.os.Looper
import android.os.SystemClock
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.CheckBox
import android.widget.EditText
import android.widget.TextView
import ai.pickyour.core.preview.PreviewCatalogBrowser
import ai.pickyour.core.preview.PreviewCatalogPinResolver
import ai.pickyour.core.preview.PreviewDraftLibrary
import ai.pickyour.core.preview.PreviewDraftLoad
import ai.pickyour.core.cache.ArtifactHash
import ai.pickyour.core.cache.CacheInstall
import ai.pickyour.core.cache.CacheRead
import ai.pickyour.core.cache.CacheRetentionLimits
import ai.pickyour.core.cache.VerifiedArtifactCache
import ai.pickyour.core.navigation.ExternalLinkPolicy
import org.json.JSONArray
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
import java.nio.file.Files
import java.nio.file.Path
import java.security.MessageDigest
import java.util.concurrent.CountDownLatch
import java.util.concurrent.ExecutorService
import java.util.concurrent.TimeUnit

/** Actual recorded-detail controls in a simulated Activity, with no source or installed-device claim. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
@LooperMode(LooperMode.Mode.PAUSED)
class CatalogDetailBrowserActivityTest {
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
    private fun field(label: Int): EditText {
        val caption = views().filterIsInstance<TextView>().single { it.text.toString() == activity.getString(label) }
        return activity.findViewById(caption.labelFor)
    }
    private fun samples() = views().filterIsInstance<CatalogBrowseSection>().single().let(::views)
        .filterIsInstance<Button>().filter { it.text.toString().contains("FICTIONAL") }
    private fun adapter() = PreviewDraftLibrary(activity.noBackupFilesDir.toPath()).openLegacy()
    private fun loaded() = (adapter().load() as PreviewDraftLoad.Available).record
    private fun dialog() = requireNotNull(ShadowAlertDialog.getLatestAlertDialog())
    private fun rows(window: AlertDialog = dialog()) =
        (0 until window.listView.adapter.count).map { window.listView.adapter.getItem(it).toString() }
    private fun click(which: Int, window: AlertDialog = dialog()) { window.getButton(which).performClick(); idle() }
    private fun choose(index: Int, window: AlertDialog = dialog()) { shadowOf(window).clickOnItem(index); idle() }
    private fun message(window: AlertDialog = dialog()) = shadowOf(window).message.toString()
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
    private fun document() = activity.assets.open("catalog.json").bufferedReader(Charsets.UTF_8).use { JSONObject(it.readText()) }
    private fun item(root: JSONObject, kind: String = "model"): JSONObject {
        val items = root.getJSONArray("items")
        return (0 until items.length()).map { items.getJSONObject(it) }.single { it.getString("kind") == kind }
    }
    private fun showCatalog(root: JSONObject): PreviewCatalogBrowser {
        val browser = requireNotNull(PreviewCatalogBrowser.read(root.toString().toByteArray(Charsets.UTF_8)))
        views().filterIsInstance<CatalogBrowseSection>().single().show(browser)
        views().filterIsInstance<CatalogComparisonSection>().single().show(browser)
        return browser
    }
    private fun openActualDetail(kind: String = "model"): AlertDialog {
        field(R.string.catalog_search).setText("cat.$kind.fixture")
        await { samples().size == 1 }
        samples().single().performClick()
        return dialog()
    }
    private fun openActualBrowser(kind: String = "model"): AlertDialog {
        val parent = openActualDetail(kind)
        button(R.string.catalog_detail_browser_open, parent.window!!.decorView).performClick(); idle()
        assertFalse(parent.isShowing)
        return dialog()
    }
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
    private fun save() {
        button(R.string.save_draft).performClick()
        await { !button(R.string.save_draft).isEnabled && views().filterIsInstance<TextView>()
            .any { it.text.toString() == activity.getString(R.string.draft_saved) } }
    }
    private fun recreate(saved: Bundle = state()) {
        controller.pause().stop().destroy()
        controller = Robolectric.buildActivity(PreviewActivity::class.java).create(saved).start().resume().visible()
        await { button(R.string.retry_catalog).isEnabled && samples().isNotEmpty() && button(R.string.reopen_draft).isEnabled }
    }
    private fun inspectField(name: String) {
        while (dialog().getButton(AlertDialog.BUTTON_NEUTRAL).visibility == View.VISIBLE) click(AlertDialog.BUTTON_NEUTRAL)
        repeat(100) {
            val index = rows().indexOfFirst { it.startsWith("$name\n") }
            if (index >= 0) {
                choose(index)
                assertEquals(name, shadowOf(dialog()).title.toString())
                return
            }
            assertEquals("Requested field must be reachable", View.VISIBLE, dialog().getButton(AlertDialog.BUTTON_POSITIVE).visibility)
            click(AlertDialog.BUTTON_POSITIVE)
        }
        fail("Field was not reachable through the actual page controls: $name")
    }
    private fun literal() = message().split("\n\n")[1]
    private fun completeValue(): String {
        val value = StringBuilder()
        var parts = 0
        while (true) {
            val part = literal()
            assertFalse(part.contains("[truncated]"))
            value.append(JSONObject("{\"value\":$part}").getString("value"))
            parts++
            assertTrue("Bounded fixture value must finish", parts < 100)
            if (dialog().getButton(AlertDialog.BUTTON_POSITIVE).visibility != View.VISIBLE) break
            click(AlertDialog.BUTTON_POSITIVE)
        }
        return value.toString()
    }
    private fun exactDetail(browser: PreviewCatalogBrowser, kind: String = "model") =
        requireNotNull(browser.details(browser.items.single { it.kind == kind }, browser.hash))
    private fun addPin(kind: String = "model") {
        val parent = openActualDetail(kind)
        button(R.string.catalog_compare_add, parent.window!!.decorView).performClick()
        await { !parent.isShowing }
    }
    private fun openPin(index: Int = 1, expected: Int = R.string.catalog_pin_captured): AlertDialog {
        views().filterIsInstance<Button>().single { it.text.toString() == activity.getString(R.string.catalog_pin_inspect, index) }.performClick()
        await { dialog().isShowing && message().contains(activity.getString(expected)) }
        return dialog()
    }
    private fun bytes(root: JSONObject) = root.toString().toByteArray(Charsets.UTF_8)
    private fun hash(bytes: ByteArray) = ArtifactHash(MessageDigest.getInstance("SHA-256").digest(bytes)
        .joinToString("") { "%02x".format(it.toInt() and 255) })
    private fun cacheRoot() = activity.noBackupFilesDir.toPath().resolve(CatalogRetainedSnapshots.DIRECTORY)
    private fun cache(): VerifiedArtifactCache {
        Files.createDirectories(cacheRoot())
        return VerifiedArtifactCache(cacheRoot(), PreviewCatalogPinResolver.MAX_BYTES, CacheRetentionLimits(16, 16_777_216))
    }
    private fun install(cache: VerifiedArtifactCache, payload: ByteArray) =
        assertEquals(CacheInstall.Activated(hash(payload)), cache.install(hash(payload), payload.inputStream()))
    private fun files(path: Path) = Files.list(path).use { stream ->
        stream.toList().associate { it.fileName.toString() to Files.readAllBytes(it).toList() }
    }
    private fun privateFiles() = Files.walk(activity.noBackupFilesDir.toPath()).use { paths ->
        paths.filter(Files::isRegularFile).toList().associate { path ->
            activity.noBackupFilesDir.toPath().relativize(path).toString() to
                MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(path)).toList()
        }
    }
    private fun worker() = PreviewActivity::class.java.getDeclaredField("worker").apply { isAccessible = true }
        .get(activity) as ExecutorService
    private fun finishWorker() { repeat(3) { idle(); worker().submit {}.get(15, TimeUnit.SECONDS); idle() } }
    private fun blockWorker(): CountDownLatch {
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        worker().submit { entered.countDown(); release.await(15, TimeUnit.SECONDS) }
        assertTrue(entered.await(15, TimeUnit.SECONDS))
        return release
    }
    private fun libraryAction(label: String) {
        views(dialog().window!!.decorView).filterIsInstance<Button>().single { it.text.toString() == label }
            .performClick()
        idle()
    }
    private fun awaitLibraryChoices() = await {
        dialog().isShowing && views(dialog().window!!.decorView).filterIsInstance<TextView>()
            .any { it.text.toString() == activity.getString(R.string.library_compare_choose_before) }
    }

    @Test fun actualDetailExposesCompleteRecordedValuesWithoutChangingDraft() {
        save()
        val baseline = loaded().baselineIdentity
        field(R.string.draft_title).setText("pending full detail")
        field(R.string.context_objective).setText("pending objective")
        field(R.string.workload_region).setText("pending-region")
        val root = document()
        val raw = "recorded description " + "v".repeat(420) + " complete-tail"
        item(root).put("description", raw)
        val browser = showCatalog(root)
        val record = requireNotNull(browser.details(browser.items.single { it.kind == "model" }, browser.hash))
        assertEquals(raw, record.description)
        val detail = openActualDetail()
        val before = state()
        assertFalse(shadowOf(detail).message.toString().contains("complete-tail"))
        assertTrue("A truncated detail must expose an explicit complete-value inspection action",
            views(detail.window!!.decorView).filterIsInstance<Button>().any { it.text.toString() == "Review all recorded details" })
        val entry = button(R.string.catalog_detail_browser_open, detail.window!!.decorView)
        val prices = button(R.string.catalog_price_browse, detail.window!!.decorView)
        val compare = button(R.string.catalog_compare_add, detail.window!!.decorView)
        entry.performClick(); idle()
        val current = dialog()
        assertFalse(detail.isShowing)
        detail.getButton(AlertDialog.BUTTON_POSITIVE).performClick()
        detail.getButton(AlertDialog.BUTTON_NEUTRAL).performClick()
        prices.performClick(); compare.performClick(); entry.performClick(); idle()
        assertSame(current, dialog())
        inspectField("catalog_item.description")
        assertEquals(raw, completeValue())
        assertDraftState(before)
        assertEquals(baseline, loaded().baselineIdentity)
        assertNull(loaded().fields.selectedId)
        assertNull(shadowOf(activity).nextStartedActivity)
    }

    @Test fun everyRecordedFieldAndProvenanceIndexRemainsReachableInStoredOrder() {
        val root = document()
        val entries = JSONArray()
        repeat(11) { index -> entries.put(JSONObject().put("verification_status", "verified")
            .put("checked_at", "2026-10-16T09:00:00+00:00").put("source_uri", "https://example.invalid/source-$index")
            .put("notes", "recorded notes $index")) }
        item(root).put("provenance", entries)
        val detail = exactDetail(showCatalog(root))
        openActualBrowser()
        val before = state()
        val seen = mutableListOf<String>()
        while (true) {
            val page = rows()
            assertTrue(page.size in 1..8)
            assertTrue(page.all { it.length < 240 })
            seen += page.map { it.substringBefore('\n') }
            if (dialog().getButton(AlertDialog.BUTTON_POSITIVE).visibility != View.VISIBLE) break
            click(AlertDialog.BUTTON_POSITIVE)
        }
        val expected = listOf("catalog_item.id", "catalog_item.name", "catalog_item.kind", "catalog_item.tier", "catalog_item.version",
            "catalog_snapshot.id", "catalog_snapshot.revision", "catalog_snapshot.captured_at", "catalog_snapshot.sha256",
            "catalog_item.updated_at", "catalog_item.status", "catalog_item.billing_class", "catalog_item.description") +
            detail.specs.map { "catalog_item.spec.${it.name}" } + detail.license.map { "catalog_item.license.${it.name}" } +
            (0..10).flatMap { index -> listOf("catalog_item.provenance[$index].source_uri", "catalog_item.provenance[$index].checked_at",
                "catalog_item.provenance[$index].verification_status", activity.getString(R.string.catalog_detail_browser_assessment, index + 1),
                "catalog_item.provenance[$index].notes") }
        assertEquals(expected, seen)
        assertEquals(seen.size, seen.toSet().size)
        inspectField("catalog_item.provenance[10].notes")
        assertEquals("recorded notes 10", completeValue())
        click(AlertDialog.BUTTON_NEGATIVE)
        inspectField("catalog_item.provenance[10].verification_status")
        assertEquals("verified", completeValue())
        click(AlertDialog.BUTTON_NEGATIVE)
        inspectField(activity.getString(R.string.catalog_detail_browser_assessment, 11))
        assertEquals(activity.getString(R.string.label_verification_unestablished), completeValue())
        assertTrue(message().contains(activity.getString(R.string.catalog_detail_browser_presentation)))
        assertDraftState(before)
        assertTrue(adapter().load() is PreviewDraftLoad.Missing)
        assertNull(shadowOf(activity).nextStartedActivity)
    }

    @Test fun completeLiteralPartsPreserveUnicodeJsonCollectionsAndRestrictiveLicenseText() {
        val root = document()
        val raw = "a".repeat(159) + "\uD83D\uDE00" + "\n\r\t\\\"\u2028\u2029\u202e" + "z".repeat(400) + " tail"
        val license = "License exact conditions " + "restricted ".repeat(40)
        val uri = "https://example.invalid/" + "path".repeat(100)
        item(root).put("description", raw).getJSONObject("license").put("spdx_id", license).put("commercial_use_allowed", false)
        item(root).getJSONArray("provenance").getJSONObject(0).put("source_uri", uri).put("notes", raw)
        item(root, "model_provider").getJSONObject("spec").put("supported_regions",
            JSONArray().also { values -> repeat(80) { values.put("region-$it") } })
        val accepted = showCatalog(root)
        openActualBrowser()
        val before = state()
        val expected = linkedMapOf("catalog_item.description" to raw, "catalog_item.license.spdx_id" to JSONObject.quote(license),
            "catalog_item.license.commercial_use_allowed" to "false", "catalog_item.provenance[0].source_uri" to uri,
            "catalog_item.provenance[0].notes" to raw)
        expected.forEach { (name, value) ->
            inspectField(name)
            if (name == "catalog_item.description") {
                assertTrue(literal().endsWith("\\ud83d\""))
                click(AlertDialog.BUTTON_POSITIVE)
                assertTrue(literal().startsWith("\"\\ude00"))
                click(AlertDialog.BUTTON_NEUTRAL)
            }
            assertEquals(name, value, completeValue())
            assertTrue(views(dialog().window!!.decorView).filterIsInstance<TextView>().all { it.urls.isEmpty() })
            click(AlertDialog.BUTTON_NEGATIVE)
        }
        openActualBrowser("model_provider")
        inspectField("catalog_item.spec.supported_regions")
        assertEquals(exactDetail(accepted, "model_provider").specs.single { it.name == "supported_regions" }.valueJson, completeValue())
        field(R.string.catalog_search).setText(before.getString("catalogQuery"))
        assertDraftState(before)
        assertTrue(adapter().load() is PreviewDraftLoad.Missing)
        assertNull(shadowOf(activity).nextStartedActivity)
    }

    @Test fun missingEmptyUnknownFalseAndZeroKeepTheirRecordedStateAndRepresentation() {
        val root = document()
        item(root, "workload").getJSONObject("spec").put("requests_per_day", 0)
        item(root, "workload").put("description", "")
        item(root, "compute_profile").getJSONObject("spec").put("region", "unknown")
        item(root, "compute_profile").remove("description")
        item(root, "model_provider").getJSONObject("spec").put("supports_fine_tuning", false)
            .put("supported_regions", JSONArray().put("declared").put("unknown"))
        item(root, "model_provider").getJSONArray("provenance").getJSONObject(0).put("notes", "unknown")
        showCatalog(root)
        openActualBrowser("workload")
        inspectField("catalog_item.spec.requests_per_day")
        assertEquals("0", completeValue())
        assertTrue(message().contains(activity.getString(R.string.catalog_detail_browser_supplied)))
        click(AlertDialog.BUTTON_NEGATIVE)
        inspectField("catalog_item.description")
        assertEquals("\"\"", literal())
        assertTrue(message().contains(activity.getString(R.string.catalog_detail_browser_supplied)))
        openActualBrowser("compute_profile")
        inspectField("catalog_item.description")
        assertEquals(activity.getString(R.string.catalog_detail_missing), literal())
        click(AlertDialog.BUTTON_NEGATIVE)
        inspectField("catalog_item.spec.region")
        assertTrue(message().contains(activity.getString(R.string.catalog_detail_explicit_unknown)))
        assertEquals("\"unknown\"", completeValue())
        openActualBrowser("model_provider")
        inspectField("catalog_item.spec.supports_fine_tuning")
        assertEquals("false", completeValue())
        click(AlertDialog.BUTTON_NEGATIVE)
        inspectField("catalog_item.spec.supported_regions")
        assertTrue(message().contains(activity.getString(R.string.catalog_detail_browser_contains_unknown)))
        assertEquals("[\"declared\",\"unknown\"]", completeValue())
        click(AlertDialog.BUTTON_NEGATIVE)
        inspectField("catalog_item.provenance[0].notes")
        assertEquals("unknown", completeValue())
        assertTrue(message().contains(activity.getString(R.string.catalog_detail_browser_supplied)))
        item(root, "model_provider").getJSONObject("spec").put("supported_regions", JSONArray())
        showCatalog(root)
        openActualBrowser("model_provider")
        inspectField("catalog_item.spec.supported_regions")
        assertTrue(message().contains(activity.getString(R.string.catalog_detail_empty_collection)))
        assertEquals("[]", completeValue())
        assertTrue(adapter().load() is PreviewDraftLoad.Missing)
    }

    @Test fun cancelledAndReplacedFieldAndValueControlsCannotReopenTheirRetiredOwner() {
        val root = document()
        item(root).put("description", "v".repeat(500))
        val detail = exactDetail(showCatalog(root))
        var authority = true
        var closed = 0
        val browser = CatalogDetailBrowser(activity, detail, { authority }, { closed++ })
        browser.show()
        val first = dialog()
        click(AlertDialog.BUTTON_POSITIVE)
        val second = dialog()
        choose(0, first); click(AlertDialog.BUTTON_POSITIVE, first)
        assertSame(second, dialog())
        inspectField("catalog_item.description")
        val oldValue = dialog()
        click(AlertDialog.BUTTON_POSITIVE)
        val currentValue = dialog()
        click(AlertDialog.BUTTON_POSITIVE, oldValue); click(AlertDialog.BUTTON_NEGATIVE, oldValue)
        assertSame(currentValue, dialog())
        authority = false
        click(AlertDialog.BUTTON_POSITIVE, currentValue)
        assertFalse(currentValue.isShowing)
        assertEquals(1, closed)
        authority = true
        browser.show(); click(AlertDialog.BUTTON_NEGATIVE, oldValue); choose(0, second)
        assertSame(currentValue, dialog())
        assertEquals(1, closed)
        val cancelled = CatalogDetailBrowser(activity, detail, { true }, { closed++ })
        cancelled.show()
        val window = dialog()
        window.cancel(); idle()
        assertFalse(window.isShowing)
        cancelled.show(); choose(0, window); click(AlertDialog.BUTTON_POSITIVE, window)
        assertSame(window, dialog())
        assertEquals(2, closed)
        assertTrue(adapter().load() is PreviewDraftLoad.Missing)
    }

    @Test fun actualNewDetailAndSiblingInspectionRetireAllEarlierDetailControls() {
        save()
        val baseline = loaded().baselineIdentity
        // Dialog frames must not advance this read-only case beyond the autosave deadline.
        AutosaveUiClock.pause()
        val pendingSince = SystemClock.uptimeMillis()
        field(R.string.draft_title).setText("pending sibling navigation")
        val previous = openActualDetail()
        val oldEntry = button(R.string.catalog_detail_browser_open, previous.window!!.decorView)
        oldEntry.performClick(); idle()
        val firstFields = dialog()
        choose(0)
        val oldValue = dialog()
        val before = state()
        val nextDetail = openActualDetail()
        assertFalse(oldValue.isShowing)
        button(R.string.catalog_price_browse, nextDetail.window!!.decorView).performClick(); idle()
        val currentPrice = dialog()
        choose(0, firstFields); click(AlertDialog.BUTTON_NEGATIVE, oldValue)
        oldEntry.performClick(); previous.getButton(AlertDialog.BUTTON_POSITIVE).performClick(); idle()
        assertSame(currentPrice, dialog())
        openActualBrowser()
        val fields = dialog()
        addPin()
        val pin = openPin()
        assertFalse(fields.isShowing)
        choose(0, fields)
        assertSame(pin, dialog())
        // The deliberate tray add is the only state transition in this sequence.
        assertDraftState(before, includeTray = false)
        assertEquals(listOf("cat.model.fixture"), state().getStringArrayList("comparisonIds"))
        assertEquals(baseline, loaded().baselineIdentity)
        assertNull(shadowOf(activity).nextStartedActivity)
        assertTrue("Read-only actions stay before autosave", SystemClock.uptimeMillis() in pendingSince until pendingSince + 750)
    }

    @Test fun actualPauseRetiresCurrentDetailActionsWithoutChangingPendingOrSavedState() {
        save()
        val baseline = loaded().baselineIdentity
        AutosaveUiClock.pause()
        field(R.string.draft_title).setText("pending catalog detail pause")
        field(R.string.context_objective).setText("pending objective across backgrounding")
        val parent = openActualDetail()
        val row = samples().single()
        val select = parent.getButton(AlertDialog.BUTTON_POSITIVE)
        val sources = parent.getButton(AlertDialog.BUTTON_NEUTRAL)
        val retained = listOf(R.string.catalog_detail_browser_open, R.string.catalog_price_browse,
            R.string.catalog_compare_add, R.string.catalog_use_in_draft)
            .map { button(it, parent.window!!.decorView) }
        val before = state()
        val disk = privateFiles()
        try {
            controller.pause()
            select.performClick(); idle()
            assertDraftState(before)
            assertFalse("Backgrounding retires the current detail window", parent.isShowing)
            sources.performClick()
            retained.forEach { it.performClick() }
            row.performClick(); idle()
            assertSame("A paused Activity cannot open a detail from its still-rendered row", parent, dialog())
            assertDraftState(before)
            assertEquals(baseline, loaded().baselineIdentity)
            assertEquals(disk, privateFiles())
            assertNull(shadowOf(activity).nextStartedActivity)
        } finally {
            controller.resume().visible()
        }
        select.performClick(); sources.performClick()
        retained.forEach { it.performClick() }; idle()
        assertSame(parent, dialog())
        assertFalse(parent.isShowing)
        assertDraftState(before)
        val fresh = openActualDetail()
        assertTrue("A new detail remains usable after resume", fresh.isShowing)
        click(AlertDialog.BUTTON_POSITIVE, fresh)
        assertEquals("cat.model.fixture", state().getString("selectedId"))
        assertEquals(item(document()).getString("version"), state().getString("selectedVersion"))
        assertEquals(before.getString("catalogSnapshot"), state().getString("selectedCatalogHash"))
        assertEquals(before.getString("title"), state().getString("title"))
        assertEquals(before.getString("objective"), state().getString("objective"))
        assertEquals(baseline, loaded().baselineIdentity)
        assertEquals(disk, privateFiles())
    }

    @Test fun actualPauseRetiresEveryCurrentDetailChildBeforeItsCallbacksCanReopen() {
        save()
        val baseline = loaded().baselineIdentity
        AutosaveUiClock.pause()
        field(R.string.context_objective).setText("pending child inspection pause")
        val disk = privateFiles()
        for (stage in listOf("fields", "field value", "rates", "rate value", "sources", "source recovery")) {
            val kind = if (stage.startsWith("rate")) "compute_profile" else "model"
            val parent = openActualDetail(kind)
            when (stage) {
                "fields", "field value" -> {
                    button(R.string.catalog_detail_browser_open, parent.window!!.decorView).performClick(); idle()
                    if (stage == "field value") choose(0)
                }
                "rates", "rate value" -> {
                    button(R.string.catalog_price_browse, parent.window!!.decorView).performClick(); idle()
                    if (stage == "rate value") { choose(0); choose(0) }
                }
                else -> {
                    click(AlertDialog.BUTTON_NEUTRAL, parent)
                    if (stage == "source recovery") choose(0)
                }
            }
            val child = dialog()
            assertTrue("The $stage child must actually open before pause", child.isShowing)
            assertFalse(parent.isShowing)
            val buttons = views(child.window!!.decorView).filterIsInstance<Button>()
                .filter { it.visibility == View.VISIBLE }
            val hasRows = child.listView?.adapter?.count?.let { it > 0 } == true
            fun replay() {
                buttons.forEach { it.performClick() }
                if (hasRows) shadowOf(child).clickOnItem(0)
                idle()
            }
            val before = state()
            try {
                controller.pause()
                assertFalse("Backgrounding must retire the $stage child", child.isShowing)
                replay()
                assertSame(child, dialog())
                assertDraftState(before)
                assertEquals(baseline, loaded().baselineIdentity)
                assertEquals(disk, privateFiles())
                assertNull(shadowOf(activity).nextStartedActivity)
            } finally {
                controller.resume().visible()
            }
            replay()
            assertSame(child, dialog())
            assertFalse(child.isShowing)
            val fresh = openActualDetail(kind)
            replay()
            assertSame("Retired $stage controls cannot replace a fresh inspector", fresh, dialog())
            assertTrue(fresh.isShowing)
            assertDraftState(before)
            assertEquals(baseline, loaded().baselineIdentity)
            assertEquals(disk, privateFiles())
            click(AlertDialog.BUTTON_NEGATIVE, fresh)
        }
    }

    @Test fun actualRecreationRetiresCurrentSourceRecoveryWithoutLosingDraftState() {
        save()
        val baseline = loaded().baselineIdentity
        AutosaveUiClock.pause()
        field(R.string.context_context).setText("pending source recreation")
        val parent = openActualDetail()
        click(AlertDialog.BUTTON_NEUTRAL, parent)
        val chooser = dialog()
        choose(0)
        val recovery = dialog()
        val back = recovery.getButton(AlertDialog.BUTTON_POSITIVE)
        assertTrue(recovery.isShowing)
        val before = state()
        val disk = privateFiles()
        recreate()
        assertFalse(recovery.isShowing)
        back.performClick(); choose(0, chooser); idle()
        assertSame(recovery, dialog())
        assertDraftState(before)
        assertEquals(baseline, loaded().baselineIdentity)
        assertEquals(disk, privateFiles())
        val fresh = openActualDetail()
        click(AlertDialog.BUTTON_NEUTRAL, fresh)
        val current = dialog()
        assertTrue(current.isShowing)
        back.performClick(); choose(0, chooser); idle()
        assertSame(current, dialog())
        assertDraftState(before)
        assertNull(shadowOf(activity).nextStartedActivity)
    }

    @Test fun explicitlyRetiredSourceConfirmationCannotDispatchOrReopen() {
        save()
        val baseline = loaded().baselineIdentity
        AutosaveUiClock.pause()
        field(R.string.context_objective).setText("pending explicit source retirement")
        val uri = "https://docs.example.com/recorded-source"
        val root = document()
        item(root).getJSONArray("provenance").getJSONObject(0).put("source_uri", uri)
        val detail = exactDetail(showCatalog(root))
        val before = state()
        val disk = privateFiles()
        val launches = mutableListOf<String?>()
        fun navigation() = CatalogSourceNavigation(activity, detail,
            ExternalLinkPolicy(setOf("docs.example.com")), isCurrent = { true },
            launch = { launches += it.dataString })
        val retired = navigation()
        retired.show()
        val chooser = dialog()
        choose(0)
        val confirmation = dialog()
        val open = confirmation.getButton(AlertDialog.BUTTON_POSITIVE)
        assertTrue(confirmation.isShowing)
        retired.retire()
        assertFalse(confirmation.isShowing)
        open.performClick(); choose(0, chooser); retired.show(); idle()
        assertSame(confirmation, dialog())
        assertTrue(launches.isEmpty())
        navigation().show(); choose(0)
        val current = dialog()
        open.performClick(); retired.show(); idle()
        assertSame(current, dialog())
        click(AlertDialog.BUTTON_POSITIVE, current)
        click(AlertDialog.BUTTON_POSITIVE, current)
        assertEquals(listOf(uri), launches)
        assertDraftState(before)
        assertEquals(baseline, loaded().baselineIdentity)
        assertEquals(disk, privateFiles())
        assertNull(shadowOf(activity).nextStartedActivity)
    }

    @Test fun actualReloadSnapshotReplacementAndRecreationRetireCapturedFullValues() {
        save()
        val baseline = loaded().baselineIdentity
        // Dialog frames must not advance this read-only case beyond the autosave deadline.
        AutosaveUiClock.pause()
        val pendingSince = SystemClock.uptimeMillis()
        field(R.string.context_objective).setText("pending recorded detail")
        val first = openActualBrowser()
        val before = state()
        button(R.string.retry_catalog).performClick()
        assertFalse(first.isShowing)
        await { button(R.string.retry_catalog).isEnabled && samples().size == 1 }
        val second = openActualBrowser()
        val replacement = document().put("revision", 2)
        item(replacement).put("description", "new recorded snapshot")
        showCatalog(replacement)
        choose(0, second)
        assertFalse(second.isShowing)
        openActualBrowser()
        inspectField("catalog_item.description")
        val priorValue = dialog()
        assertEquals("new recorded snapshot", completeValue())
        recreate()
        assertFalse(priorValue.isShowing)
        choose(0, first); choose(0, second); click(AlertDialog.BUTTON_NEGATIVE, priorValue)
        assertSame(priorValue, dialog())
        assertDraftState(before)
        assertEquals(baseline, loaded().baselineIdentity)
        openActualBrowser(); inspectField("catalog_snapshot.revision")
        assertEquals("1", completeValue())
        assertNull(shadowOf(activity).nextStartedActivity)
        assertTrue("Read-only actions stay before autosave", SystemClock.uptimeMillis() in pendingSince until pendingSince + 750)
    }

    @Test fun storageReconciliationPermanentlyRetiresFullValuesWithAnUnchangedMissingBaseline() {
        val restored = state()
        restored.putString("pendingDeleteId", "draft-11111111-1111-1111-1111-111111111111")
        recreate(restored)
        val oldFields = openActualBrowser()
        assertNull(state().getString("draftBaseline"))
        button(R.string.reopen_draft).performClick()
        val confirmation = dialog()
        click(AlertDialog.BUTTON_POSITIVE)
        await { button(R.string.reopen_draft).isEnabled && !confirmation.isShowing }
        assertNull(state().getString("draftBaseline"))
        assertTrue(adapter().load() is PreviewDraftLoad.Missing)
        assertFalse(oldFields.isShowing)
        field(R.string.context_objective).setText("new input after unchanged missing baseline")
        val before = state()
        choose(0, oldFields); click(AlertDialog.BUTTON_POSITIVE, oldFields)
        assertSame(confirmation, dialog())
        openActualBrowser()
        inspectField("catalog_item.version")
        assertEquals("2026.10-demo", completeValue())
        assertDraftState(before)
    }

    @Test fun exactRetainedDetailOpensEveryOldValueWithoutActivatingOrSubstitutingCurrentData() {
        save()
        val baseline = loaded().baselineIdentity
        // Dialog frames must not advance this read-only case beyond the autosave deadline.
        AutosaveUiClock.pause()
        val pendingSince = SystemClock.uptimeMillis()
        field(R.string.context_context).setText("pending retained fields")
        val older = document().put("revision", 3)
        val raw = "retired historical " + "o".repeat(450) + " exact older tail"
        item(older).put("description", raw).put("status", "retired")
        showCatalog(older)
        addPin()
        val newer = document().put("revision", 4)
        item(newer).put("description", "newer description must never substitute")
        showCatalog(newer)
        val cache = cache()
        install(cache, bytes(older)); install(cache, bytes(newer))
        val disk = files(cacheRoot())
        val parent = openPin(expected = R.string.catalog_pin_retained)
        val retry = button(R.string.catalog_pin_retry, parent.window!!.decorView)
        val browse = button(R.string.catalog_pin_browse, parent.window!!.decorView)
        val entry = button(R.string.catalog_detail_browser_open, parent.window!!.decorView)
        val before = state()
        entry.performClick(); idle()
        val current = dialog()
        assertFalse(parent.isShowing)
        retry.performClick(); browse.performClick(); entry.performClick(); idle()
        assertSame(current, dialog())
        val expected = linkedMapOf("catalog_item.description" to raw, "catalog_item.status" to "retired",
            "catalog_snapshot.revision" to "3", "catalog_snapshot.sha256" to hash(bytes(older)).hex,
            "catalog_item.version" to item(older).getString("version"))
        expected.forEach { (name, value) ->
            inspectField(name); assertEquals(name, value, completeValue()); click(AlertDialog.BUTTON_NEGATIVE)
        }
        assertEquals(hash(bytes(newer)), (cache.active() as CacheRead.Available).hash)
        assertEquals(disk, files(cacheRoot()))
        assertDraftState(before)
        assertEquals(baseline, loaded().baselineIdentity)
        assertNull(shadowOf(activity).nextStartedActivity)
        assertTrue("Read-only actions stay before autosave", SystemClock.uptimeMillis() in pendingSince until pendingSince + 750)
    }

    @Test fun retiredPinFullValueCallbacksCannotReplaceNewPinsOrSurviveRemovalAndStorage() {
        addPin()
        addPin("compute_profile")
        var parent = openPin()
        button(R.string.catalog_detail_browser_open, parent.window!!.decorView).performClick(); idle()
        val firstFields = dialog()
        parent = openPin(2)
        assertFalse(firstFields.isShowing)
        button(R.string.catalog_detail_browser_open, parent.window!!.decorView).performClick(); idle()
        val secondFields = dialog()
        choose(0, firstFields)
        assertSame(secondFields, dialog())
        inspectField("catalog_item.id")
        assertEquals("cat.compute_profile.fixture", completeValue())
        val secondValue = dialog()
        views().filterIsInstance<Button>().single { it.text.toString().startsWith("Remove component 2 ") }.performClick(); idle()
        assertFalse(secondValue.isShowing)
        val before = state()
        click(AlertDialog.BUTTON_NEGATIVE, secondValue); choose(0, secondFields)
        assertDraftState(before)
        parent = openPin()
        button(R.string.catalog_detail_browser_open, parent.window!!.decorView).performClick(); idle()
        val storageFields = dialog()
        button(R.string.reopen_draft).performClick()
        val confirmation = dialog()
        click(AlertDialog.BUTTON_POSITIVE)
        await { !confirmation.isShowing && button(R.string.reopen_draft).isEnabled }
        assertFalse(storageFields.isShowing)
        choose(0, storageFields)
        assertSame(confirmation, dialog())
        assertTrue(adapter().load() is PreviewDraftLoad.Missing)
    }

    @Test fun unavailablePinAndRowOnlyCatalogNeverOfferBorrowedCompleteValues() {
        val root = document()
        showCatalog(root)
        addPin()
        showCatalog(document().put("revision", 5))
        val missing = openPin(expected = R.string.catalog_pin_missing)
        assertFalse(views(missing.window!!.decorView).filterIsInstance<Button>()
            .any { it.text.toString() == activity.getString(R.string.catalog_detail_browser_open) })
        val browser = showCatalog(root)
        views().filterIsInstance<CatalogBrowseSection>().single().show(PreviewCatalogBrowser(browser.hash, browser.items))
        val rowOnly = openActualDetail()
        assertFalse(views(rowOnly.window!!.decorView).filterIsInstance<Button>()
            .any { it.text.toString() == activity.getString(R.string.catalog_detail_browser_open) })
        assertTrue(message().contains(activity.getString(R.string.catalog_detail_unavailable)))
        assertTrue(adapter().load() is PreviewDraftLoad.Missing)
    }

    @Test fun comparisonTransitionsRetireFullDetailControlsAndOldDifferenceToggles() {
        addPin()
        addPin("compute_profile")
        button(R.string.catalog_compare_open).performClick(); idle()
        val comparison = dialog()
        val toggle = views(comparison.window!!.decorView).filterIsInstance<CheckBox>().single()
        val detail = openActualDetail()
        button(R.string.catalog_detail_browser_open, detail.window!!.decorView).performClick(); idle()
        val fields = dialog()
        assertFalse("The preceding comparison must retire when complete detail inspection starts", comparison.isShowing)
        val before = state()
        toggle.isChecked = !toggle.isChecked; idle()
        assertDraftState(before)
        button(R.string.catalog_compare_open).performClick(); idle()
        val nextComparison = dialog()
        assertFalse("The next comparison must retire its preceding detail inspection", fields.isShowing)
        choose(0, fields)
        assertSame(nextComparison, dialog())
        assertDraftState(before)
    }

    @Test fun savedLibraryComparisonRetiresCurrentAndPinnedCompleteDetailCallbacks() {
        save()
        addPin()
        PreviewDraftLibrary(activity.noBackupFilesDir.toPath()).list()
        // Dialog frames must not advance this read-only case beyond the autosave deadline.
        AutosaveUiClock.pause()
        val pendingSince = SystemClock.uptimeMillis()
        field(R.string.context_objective).setText("pending library transition")
        val baseline = loaded().baselineIdentity
        val disk = privateFiles()
        for (pinned in listOf(false, true)) {
            val parent = if (pinned) openPin() else openActualDetail()
            button(R.string.catalog_detail_browser_open, parent.window!!.decorView).performClick(); idle()
            val oldFields = dialog()
            inspectField("catalog_item.id")
            val oldValue = dialog()
            val before = state()
            button(R.string.library_compare_open).performClick(); idle()
            assertFalse("Starting library comparison must retire full recorded values", oldValue.isShowing)
            awaitLibraryChoices()
            val library = dialog()
            choose(0, oldFields)
            click(AlertDialog.BUTTON_NEGATIVE, oldValue)
            button(R.string.catalog_detail_browser_open, parent.window!!.decorView).performClick(); idle()
            assertSame(library, dialog())
            assertDraftState(before)
            assertEquals(baseline, loaded().baselineIdentity)
            assertEquals(disk, privateFiles())
            library.cancel(); idle()
        }
        assertNull(shadowOf(activity).nextStartedActivity)
        assertTrue("Read-only actions stay before autosave", SystemClock.uptimeMillis() in pendingSince until pendingSince + 750)
    }

    @Test fun newCatalogDetailsAndPinsRetireQueuedAndVisibleLibraryComparisons() {
        save()
        field(R.string.context_objective).setText("recorded library comparison objective")
        save()
        addPin()
        PreviewDraftLibrary(activity.noBackupFilesDir.toPath()).list()
        // Dialog frames must not advance this read-only case beyond the autosave deadline.
        AutosaveUiClock.pause()
        val pendingSince = SystemClock.uptimeMillis()
        field(R.string.context_objective).setText("pending incoming catalog inspection")
        val baseline = loaded().baselineIdentity
        val disk = privateFiles()
        for (pinned in listOf(false, true)) for (stage in 0..3) {
            var release: CountDownLatch? = if (stage == 0) blockWorker() else null
            try {
                button(R.string.library_compare_open).performClick(); idle()
                if (stage > 0) awaitLibraryChoices()
                if (stage >= 2) {
                    libraryAction(activity.getString(R.string.library_compare_pick_draft, "guest-draft"))
                    libraryAction(activity.getString(R.string.library_compare_pick_revision, "0"))
                    libraryAction(activity.getString(R.string.library_compare_pick_draft, "guest-draft"))
                    if (stage == 2) release = blockWorker()
                    libraryAction(activity.getString(R.string.library_compare_pick_revision, "1"))
                }
                if (stage == 3) {
                    await { views(dialog().window!!.decorView).filterIsInstance<TextView>()
                        .any { it.text.toString() == activity.getString(R.string.library_compare_scope) } }
                    libraryAction(activity.getString(R.string.revision_compare_inspect, 1, "objective"))
                    libraryAction(activity.getString(R.string.revision_compare_inspect_after))
                }
                val obsolete = dialog()
                val retained = views(obsolete.window!!.decorView).filterIsInstance<Button>()
                    .filter { it.visibility == View.VISIBLE }
                val before = state()
                val parent = if (pinned) {
                    views().filterIsInstance<Button>().single {
                        it.text.toString() == activity.getString(R.string.catalog_pin_inspect, 1)
                    }.performClick(); idle()
                    dialog()
                } else openActualDetail()
                assertFalse("A new catalog inspector must retire the current library request", obsolete.isShowing)
                release?.countDown(); release = null
                finishWorker()
                val readyParent = if (pinned) {
                    await { dialog().isShowing && message().contains(activity.getString(R.string.catalog_pin_captured)) }
                    dialog()
                } else {
                    assertSame(parent, dialog())
                    parent
                }
                button(R.string.catalog_detail_browser_open, readyParent.window!!.decorView).performClick(); idle()
                val fields = dialog()
                retained.forEach { it.performClick() }
                finishWorker()
                assertSame(fields, dialog())
                assertTrue(fields.isShowing)
                assertDraftState(before)
                assertEquals(baseline, loaded().baselineIdentity)
                assertEquals(disk, privateFiles())
                click(AlertDialog.BUTTON_NEGATIVE)
            } finally { release?.countDown() }
        }
        assertNull(shadowOf(activity).nextStartedActivity)
        assertTrue("Read-only actions stay before autosave", SystemClock.uptimeMillis() in pendingSince until pendingSince + 750)
    }

    @Test fun savedLibraryAndCatalogComparisonsRetireOpposingCallbacksWithoutChangingTheTray() {
        save()
        addPin()
        addPin("compute_profile")
        PreviewDraftLibrary(activity.noBackupFilesDir.toPath()).list()
        field(R.string.context_objective).setText("pending comparison transition")
        button(R.string.catalog_compare_open).performClick(); idle()
        val catalogComparison = dialog()
        val retainedToggle = views(catalogComparison.window!!.decorView).filterIsInstance<CheckBox>().single()
        val before = state()
        val baseline = loaded().baselineIdentity
        val disk = privateFiles()
        button(R.string.library_compare_open).performClick(); idle()
        assertFalse("Library comparison must retire the preceding catalog inspection", catalogComparison.isShowing)
        awaitLibraryChoices()
        val libraryComparison = dialog()
        val retainedLibrary = views(libraryComparison.window!!.decorView).filterIsInstance<Button>()
            .filter { it.visibility == View.VISIBLE }
        retainedToggle.isChecked = !retainedToggle.isChecked; idle()
        assertSame(libraryComparison, dialog())
        assertDraftState(before)
        button(R.string.catalog_compare_open).performClick(); idle()
        val current = dialog()
        assertFalse("Catalog comparison must retire the preceding library request", libraryComparison.isShowing)
        retainedLibrary.forEach { it.performClick() }; finishWorker()
        assertSame(current, dialog())
        assertTrue(current.isShowing)
        assertDraftState(before)
        assertEquals(baseline, loaded().baselineIdentity)
        assertEquals(disk, privateFiles())
        assertNull(shadowOf(activity).nextStartedActivity)
    }

    @Test
    @Config(qualifiers = "ar-rEG-ldrtl-w320dp-h640dp")
    @GraphicsMode(GraphicsMode.Mode.NATIVE)
    fun narrowRtlLargeTextKeepsCompleteDetailAndRetainedEntryActionsReachable() {
        RuntimeEnvironment.setFontScale(2f)
        recreate()
        val root = document()
        item(root).put("description", "v".repeat(500))
        showCatalog(root)
        val parent = openActualDetail()
        assertMeasuredButtons(parent, listOf(R.string.catalog_detail_browser_open, R.string.catalog_price_browse,
            R.string.catalog_compare_add).map { button(it, parent.window!!.decorView) })
        button(R.string.catalog_detail_browser_open, parent.window!!.decorView).performClick(); idle()
        assertMeasuredButtons(dialog(), listOf(dialog().getButton(AlertDialog.BUTTON_POSITIVE), dialog().getButton(AlertDialog.BUTTON_NEGATIVE)))
        inspectField("catalog_item.description")
        click(AlertDialog.BUTTON_POSITIVE)
        assertMeasuredButtons(dialog(), listOf(AlertDialog.BUTTON_POSITIVE, AlertDialog.BUTTON_NEUTRAL, AlertDialog.BUTTON_NEGATIVE)
            .map { dialog().getButton(it) })
        click(AlertDialog.BUTTON_NEGATIVE); click(AlertDialog.BUTTON_NEGATIVE)
        addPin()
        val retained = openPin()
        assertMeasuredButtons(retained, listOf(R.string.catalog_pin_retry, R.string.catalog_pin_browse,
            R.string.catalog_detail_browser_open).map { button(it, retained.window!!.decorView) })
        button(R.string.catalog_detail_browser_open, retained.window!!.decorView).performClick(); idle()
        inspectField("catalog_item.description")
        assertEquals("v".repeat(500), completeValue())
        assertTrue(adapter().load() is PreviewDraftLoad.Missing)
    }

    private fun assertMeasuredButtons(window: AlertDialog, controls: List<Button>) {
        val density = activity.resources.displayMetrics.density
        val root = window.window!!.decorView
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
                val viewport = android.graphics.Rect().also(parent::getDrawingRect)
                assertTrue("Control is clipped by ${parent.javaClass.simpleName}: ${control.text}", viewport.contains(bounds))
                child = parent
            }
            val label = requireNotNull(control.layout)
            assertTrue(label.lineCount > 0)
            assertTrue("Complete label height: ${control.text}", label.height > 0 &&
                label.height <= control.height - control.compoundPaddingTop - control.compoundPaddingBottom)
            val textLeft = control.scrollX.toFloat()
            val textRight = textLeft + control.width - control.compoundPaddingLeft - control.compoundPaddingRight
            assertTrue(textRight > textLeft)
            repeat(label.lineCount) { line ->
                assertEquals("No ellipsis: ${control.text}", 0, label.getEllipsisCount(line))
                val left = label.getLineLeft(line)
                val right = label.getLineRight(line)
                assertTrue("Complete horizontal label: ${control.text}", left >= textLeft && right <= textRight && left <= right)
            }
            assertEquals(label.text.length, label.getLineEnd(label.lineCount - 1))
        }
    }
}
