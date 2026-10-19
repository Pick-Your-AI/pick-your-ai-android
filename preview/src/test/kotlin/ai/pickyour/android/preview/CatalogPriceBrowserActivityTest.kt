package ai.pickyour.android.preview

import android.app.AlertDialog
import android.os.Bundle
import android.os.SystemClock
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.EditText
import android.widget.TextView
import ai.pickyour.core.preview.PreviewCatalogBrowser
import ai.pickyour.core.preview.PreviewCatalogDetails
import ai.pickyour.core.preview.PreviewDraftLibrary
import ai.pickyour.core.preview.PreviewDraftLoad
import ai.pickyour.core.pricing.RecordedPricebook
import ai.pickyour.core.pricing.RecordedPricebookResult
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

/** Real dialog controls in a simulated Activity; no network, source-rights or installed-device claim. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
@LooperMode(LooperMode.Mode.PAUSED)
class CatalogPriceBrowserActivityTest {
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
        assertNotEquals(View.NO_ID, caption.labelFor)
        return activity.findViewById(caption.labelFor)
    }
    private fun samples() = views().filterIsInstance<Button>().filter { it.text.toString().startsWith("Open FICTIONAL") }
    private fun adapter() = PreviewDraftLibrary(activity.noBackupFilesDir.toPath()).openLegacy()
    private fun loaded() = (adapter().load() as PreviewDraftLoad.Available).record
    private fun dialog() = requireNotNull(ShadowAlertDialog.getLatestAlertDialog())
    private fun rows(window: AlertDialog = dialog()) =
        (0 until window.listView.adapter.count).map { window.listView.adapter.getItem(it).toString() }
    private fun idle() = AutosaveUiClock.idle()
    private fun click(which: Int, window: AlertDialog = dialog()) { window.getButton(which).performClick(); idle() }
    private fun choose(index: Int, window: AlertDialog = dialog()) { shadowOf(window).clickOnItem(index); idle() }
    private fun await(condition: () -> Boolean) {
        val deadline = System.nanoTime() + 15_000_000_000L
        while (System.nanoTime() < deadline) {
            idle()
            if (condition()) return
            Thread.sleep(10)
        }
        fail("Timed out waiting for the explicit UI state")
    }
    private fun save() {
        button(R.string.save_draft).performClick()
        await { !button(R.string.save_draft).isEnabled &&
            views().filterIsInstance<TextView>().any { it.text.toString() == activity.getString(R.string.draft_saved) } }
    }
    private fun recreate() {
        val state = Bundle()
        controller.pause().saveInstanceState(state).stop().destroy()
        controller = Robolectric.buildActivity(PreviewActivity::class.java).create(state).start().resume().visible()
        // Catalog rows can arrive before durable draft recovery finishes.
        await { field(R.string.draft_title).isEnabled &&
            button(R.string.retry_catalog).isEnabled && samples().isNotEmpty() }
    }
    private fun state() = Bundle().also { controller.saveInstanceState(it) }
    private fun assertDraftState(before: Bundle) {
        val after = state()
        val keys = listOf("title", "objective", "contextInput", "selectedId", "selectedVersion",
            "selectedCatalogHash", "draftId", "draftBaseline", "catalogSnapshot", "selectionResolved",
            "contextInputsPresent", "workloadInputsPresent", "workload.rejectedEdit", "workload.classification",
            "comparisonIds", "comparisonVersions", "comparisonHashes", "comparisonDifferencesOnly") +
            (0..7).map { "workload.$it" }
        @Suppress("DEPRECATION")
        keys.forEach { assertEquals(it, before.get(it), after.get(it)) }
    }
    private fun openActualDetail(): AlertDialog {
        field(R.string.catalog_search).setText("cat.compute_profile.fixture")
        await { samples().size == 1 }
        samples().single().performClick()
        return dialog()
    }
    private fun openActualBrowser(): AlertDialog {
        val detail = openActualDetail()
        button(R.string.catalog_price_browse, detail.window!!.decorView).performClick()
        idle()
        assertFalse(detail.isShowing)
        return dialog()
    }
    private fun details(): PreviewCatalogDetails {
        val catalog = activity.assets.open("catalog.json").use { requireNotNull(PreviewCatalogBrowser.read(it.readBytes())) }
        return requireNotNull(catalog.details(catalog.items.single { it.kind == "compute_profile" }, catalog.hash))
    }
    private fun priceJson() = activity.assets.open("pricebook.json").bufferedReader(Charsets.UTF_8).use { JSONObject(it.readText()) }
    private fun prices(input: JSONObject) =
        (RecordedPricebook.read(input.toString().byteInputStream()) as RecordedPricebookResult.Parsed).pricebook
    private fun alternatives(count: Int): JSONObject {
        val input = priceJson()
        val prototype = input.getJSONArray("rates").getJSONObject(0)
        input.put("rates", JSONArray().also { rates ->
            repeat(count) { rates.put(JSONObject(prototype.toString()).put("id", "rate.alternative.$it")) }
        })
        return input
    }
    private fun show(input: JSONObject, current: () -> Boolean = { true }): RecordedPricebook {
        val book = prices(input)
        CatalogPriceBrowser(activity, details(), book, current).show()
        idle()
        return book
    }
    private fun inspect(name: String) {
        // Exercise the actual current page controls; field indices never bypass the adapter.
        while (dialog().getButton(AlertDialog.BUTTON_NEUTRAL).visibility == View.VISIBLE) click(AlertDialog.BUTTON_NEUTRAL)
        repeat(20) {
            val index = rows().indexOfFirst { it.startsWith("$name\n") }
            if (index >= 0) {
                choose(index)
                assertEquals(name, shadowOf(dialog()).title.toString())
                return
            }
            assertEquals("Requested field must be reachable", View.VISIBLE, dialog().getButton(AlertDialog.BUTTON_POSITIVE).visibility)
            click(AlertDialog.BUTTON_POSITIVE)
        }
        fail("Field was not reachable through bounded pages: $name")
    }
    private fun value() = shadowOf(dialog()).message.toString().split("\n\n")[1]

    @Test fun actualPriceBrowserRetiresParentActionsAndPreservesPendingDomainAndSavedBaseline() {
        field(R.string.draft_title).setText("saved price browser")
        save()
        val baseline = loaded().baselineIdentity
        field(R.string.draft_title).setText("pending title")
        field(R.string.context_objective).setText("pending objective")
        field(R.string.context_context).setText("")
        field(R.string.workload_region).setText("pending-region")
        val detail = openActualDetail()
        val before = state()
        val entry = button(R.string.catalog_price_browse, detail.window!!.decorView)
        val compare = button(R.string.catalog_compare_add, detail.window!!.decorView)
        entry.performClick(); idle()
        val current = dialog()
        assertFalse(detail.isShowing)
        assertEquals(1, rows().size)
        assertTrue(rows().single().contains("rate.compute_profile.fixture"))
        detail.getButton(AlertDialog.BUTTON_POSITIVE).performClick()
        detail.getButton(AlertDialog.BUTTON_NEUTRAL).performClick()
        compare.performClick()
        entry.performClick(); idle()
        assertSame(current, dialog())
        assertTrue(current.isShowing)
        choose(0)
        inspect("rate.unit_price.amount")
        assertEquals("\"0.80\"", value())
        assertDraftState(before)
        assertEquals(baseline, loaded().baselineIdentity)
        assertNull(loaded().fields.selectedId)
        assertNull(shadowOf(activity).nextStartedActivity)
    }

    @Test fun everyExactRateAndSnapshotFieldIsReachableWithoutSelectingAnAlternative() {
        val input = alternatives(10)
        val all = input.getJSONArray("rates")
        all.put(JSONObject(all.getJSONObject(0).toString()).put("id", "rate.wrong-version").put("catalog_item_version", "different"))
        all.put(JSONObject(all.getJSONObject(0).toString()).put("id", "rate.wrong-item").put("catalog_item_id", "cat.other"))
        val book = show(input)
        val first = rows()
        assertEquals(8, first.size)
        assertTrue(first.first().startsWith("Rate 1:"))
        assertTrue(first.last().contains("rate.alternative.7"))
        click(AlertDialog.BUTTON_POSITIVE)
        assertEquals(2, rows().size)
        assertTrue(rows().last().startsWith("Rate 10:"))
        assertFalse((first + rows()).any { it.contains("rate.wrong-") })
        click(AlertDialog.BUTTON_NEUTRAL)
        assertEquals(first, rows())
        click(AlertDialog.BUTTON_POSITIVE)
        choose(1)
        val detail = details()
        for ((name, expected) in listOf(
            "pricebook.id" to "\"${book.id}\"", "pricebook.revision" to book.revision.toString(),
            "pricebook.captured_at" to "\"${book.capturedAt}\"", "pricebook.sha256" to "\"${book.hash}\"",
            "catalog_snapshot.id" to "\"${detail.snapshotId}\"", "catalog_snapshot.revision" to detail.snapshotRevision.toString(),
            "catalog_snapshot.captured_at" to "\"${detail.snapshotCapturedAt}\"", "catalog_snapshot.sha256" to "\"${detail.snapshotHash}\"",
            "rate.id" to "\"rate.alternative.9\"", "rate.catalog_item_id" to "\"${detail.item.id}\"",
            "rate.catalog_item_version" to "\"${detail.item.version}\"", "rate.meter" to "\"gpu_hour\"",
        )) {
            inspect(name); assertEquals(name, expected, value()); click(AlertDialog.BUTTON_NEGATIVE)
        }
        click(AlertDialog.BUTTON_NEGATIVE)
        assertEquals(activity.resources.getQuantityString(R.plurals.catalog_price_browser_page, 10, 2, 2, 10), shadowOf(dialog()).title.toString())
        assertEquals(2, rows().size)
        assertTrue(adapter().load() is PreviewDraftLoad.Missing)
        assertNull(shadowOf(activity).nextStartedActivity)
    }

    @Test fun numericRevisionLimitsBoundEveryAcceptedCatalogAndPricebookDigit() {
        val catalogInput = activity.assets.open("catalog.json").bufferedReader(Charsets.UTF_8).use { JSONObject(it.readText()).toString() }
        val priceInput = priceJson().toString()
        fun withRevision(input: String, digits: String): ByteArray {
            val original = "\"revision\":1,"
            assertTrue(input.contains(original))
            assertEquals(input.indexOf(original), input.lastIndexOf(original))
            // Construct the actual JSON numeric lexeme; JSONObject.put first converts very wide
            // Number values to Double and can reject them before the production reader is reached.
            return input.replace(original, "\"revision\":$digits,").toByteArray()
        }
        for (length in listOf(129, 1000)) {
            val digits = "9".repeat(length)
            assertNull("Catalog rejects a $length-digit revision before projecting it",
                PreviewCatalogBrowser.read(withRevision(catalogInput, digits)))
            assertFalse("Pricebook rejects a $length-digit revision before projecting it",
                RecordedPricebook.read(withRevision(priceInput, digits).inputStream()) is RecordedPricebookResult.Parsed)
        }
        for (number in listOf("1e128", "1e159", "1e308", "1e309", "1e1000", "9e999")) {
            val accepted = listOf(
                PreviewCatalogBrowser.read(withRevision(catalogInput, number)) != null,
                RecordedPricebook.read(withRevision(priceInput, number).inputStream()) is RecordedPricebookResult.Parsed,
            )
            assertEquals("Short exponent revision $number: catalog/pricebook acceptance", listOf(false, false), accepted)
        }
        val digits = "9".repeat(128)
        val catalog = requireNotNull(PreviewCatalogBrowser.read(withRevision(catalogInput, digits)))
        val detail = requireNotNull(catalog.details(catalog.items.single { it.kind == "compute_profile" }, catalog.hash))
        val book = (RecordedPricebook.read(withRevision(priceInput, digits).inputStream()) as RecordedPricebookResult.Parsed).pricebook
        CatalogPriceBrowser(activity, detail, book) { true }.show()
        choose(0)
        assertTrue("Accepted numeric labels remain within the existing literal preview budget", rows().all { it.length < 200 })
        for (name in listOf("pricebook.revision", "catalog_snapshot.revision")) {
            assertEquals("$name\n$digits", rows().single { it.startsWith("$name\n") })
            inspect(name)
            assertEquals(digits, value())
            assertEquals(View.GONE, dialog().getButton(AlertDialog.BUTTON_POSITIVE).visibility)
            click(AlertDialog.BUTTON_NEGATIVE)
        }
        assertTrue(adapter().load() is PreviewDraftLoad.Missing)
    }

    @Test fun allConditionsRetainStoredOrderAndRemainReachableAcrossBoundedFieldPages() {
        val input = alternatives(1)
        val conditions = (0 until 20).map { "literal condition $it" }
        input.getJSONArray("rates").getJSONObject(0).put("conditions", JSONArray(conditions))
        show(input); choose(0)
        val seen = mutableListOf<String>()
        do {
            assertTrue(rows().size in 1..8)
            seen += rows().filter { it.startsWith("rate.conditions[") }
            val hasNext = dialog().getButton(AlertDialog.BUTTON_POSITIVE).visibility == View.VISIBLE
            if (hasNext) click(AlertDialog.BUTTON_POSITIVE)
        } while (hasNext)
        assertEquals(conditions.mapIndexed { index, literal -> "rate.conditions[$index]\n\"$literal\"" }, seen)
        choose(3)
        assertEquals("rate.conditions[19]", shadowOf(dialog()).title.toString())
        assertEquals("\"literal condition 19\"", value())
        assertTrue(shadowOf(dialog()).message.toString().contains(activity.getString(R.string.catalog_price_browser_limits)))
        click(AlertDialog.BUTTON_NEGATIVE)
        assertEquals(4, rows().size)
        assertTrue(rows().last().startsWith("rate.conditions[19]"))
        assertTrue(adapter().load() is PreviewDraftLoad.Missing)
    }

    @Test fun completeLongLiteralPartsPreserveDecimalsEscapesAndPlainSourceUris() {
        val input = alternatives(1)
        val rate = input.getJSONArray("rates").getJSONObject(0)
        val notes = "A".repeat(159) + "\uD83D\uDE80\n\u202e\"\\\u2028\u2029" + "z".repeat(180)
        val amount = "9".repeat(161) + ".0000"
        val uri = "https://example.invalid/" + "q".repeat(200)
        rate.getJSONObject("unit_price").put("amount", amount)
        rate.getJSONObject("provenance").put("notes", notes).put("source_uri", uri)
        val book = show(input); choose(0)
        inspect("rate.provenance.notes")
        val first = dialog()
        assertEquals("\"" + "A".repeat(159) + "\\ud83d\"", value())
        assertTrue(shadowOf(dialog()).message.toString().startsWith("Recorded rate 1 of 1 · value part 1 of 3"))
        click(AlertDialog.BUTTON_POSITIVE)
        val second = value()
        assertEquals("\"\\ude80\\n\\u202e\\\"\\\\\\u2028\\u2029" + "z".repeat(153) + "\"", second)
        click(AlertDialog.BUTTON_NEUTRAL)
        assertEquals("\"" + "A".repeat(159) + "\\ud83d\"", value())
        click(AlertDialog.BUTTON_POSITIVE)
        assertEquals(second, value())
        click(AlertDialog.BUTTON_POSITIVE)
        assertEquals("\"" + "z".repeat(27) + "\"", value())
        assertEquals(View.GONE, dialog().getButton(AlertDialog.BUTTON_POSITIVE).visibility)
        click(AlertDialog.BUTTON_POSITIVE, first)
        assertEquals("\"" + "z".repeat(27) + "\"", value())
        assertFalse(shadowOf(dialog()).message.toString().contains("[display truncated]"))
        click(AlertDialog.BUTTON_NEGATIVE)
        inspect("rate.unit_price.amount")
        assertEquals("\"" + "9".repeat(160) + "\"", value())
        click(AlertDialog.BUTTON_POSITIVE)
        assertEquals("\"9.0000\"", value())
        click(AlertDialog.BUTTON_NEGATIVE)
        inspect("rate.provenance.source_uri")
        assertEquals("\"${uri.take(160)}\"", value())
        click(AlertDialog.BUTTON_POSITIVE)
        assertEquals("\"${uri.drop(160)}\"", value())
        val message = dialog().findViewById<TextView>(android.R.id.message)
        assertEquals(0, message.autoLinkMask)
        assertFalse(message.movementMethod is android.text.method.LinkMovementMethod)
        assertNull(shadowOf(activity).nextStartedActivity)
        assertEquals(notes, book.rates.single().provenance.notes)
        assertEquals(amount, book.rates.single().unitPrice.amount.encoded)
        assertTrue(adapter().load() is PreviewDraftLoad.Missing)
    }

    @Test fun missingPricebookAndExactRateUseDistinctUnknownRecoveryWithoutFreeFallback() {
        CatalogPriceBrowser(activity, details(), null) { true }.show()
        assertEquals(activity.getString(R.string.catalog_price_unavailable), shadowOf(dialog()).message.toString())
        assertEquals(View.GONE, dialog().getButton(AlertDialog.BUTTON_POSITIVE).visibility)
        click(AlertDialog.BUTTON_NEGATIVE)
        for (mismatch in listOf("catalog_item_id", "catalog_item_version")) {
            val input = alternatives(1)
            input.getJSONArray("rates").getJSONObject(0).put(mismatch, "different")
            show(input)
            assertEquals(activity.getString(R.string.catalog_price_no_record), shadowOf(dialog()).message.toString())
            assertEquals(View.GONE, dialog().getButton(AlertDialog.BUTTON_POSITIVE).visibility)
            click(AlertDialog.BUTTON_NEGATIVE)
        }
        openActualBrowser()
        assertEquals(1, rows().size)
        assertTrue(rows().single().contains("0.80"))
        assertTrue(adapter().load() is PreviewDraftLoad.Missing)
    }

    @Test fun absentEmptyUnknownAndZeroStayDistinctFromEligibilityAndVerification() {
        val input = alternatives(3)
        val rates = input.getJSONArray("rates")
        rates.getJSONObject(1).put("conditions", JSONArray())
        rates.getJSONObject(2).put("conditions", JSONArray().put("").put("unknown"))
        val zero = rates.getJSONObject(2).put("region", "unknown").put("price_type", "list")
        zero.getJSONObject("unit_price").put("amount", "0.0000")
        zero.getJSONObject("provenance").put("verification_status", "verified").put("notes", "unknown")
        show(input)
        for ((index, expected) in listOf(
            0 to activity.getString(R.string.catalog_price_conditions_missing),
            1 to activity.getString(R.string.catalog_price_conditions_empty),
        )) {
            choose(index)
            inspect(activity.getString(R.string.catalog_price_conditions_state))
            assertEquals(expected, value())
            click(AlertDialog.BUTTON_NEGATIVE); click(AlertDialog.BUTTON_NEGATIVE)
        }
        choose(2)
        for ((name, expected) in listOf(
            "rate.conditions[0]" to "\"\"", "rate.conditions[1]" to "\"unknown\"",
            "rate.unit_price.amount" to "\"0.0000\"", "rate.region" to "\"unknown\"",
            "rate.valid_until" to activity.getString(R.string.catalog_detail_missing),
            "rate.provenance.source_uri" to activity.getString(R.string.catalog_detail_missing),
            "rate.provenance.notes" to "\"unknown\"", "rate.price_type" to "\"list\"",
            "rate.provenance.verification_status" to "\"verified\"",
            activity.getString(R.string.catalog_price_unassessed_label) to activity.getString(R.string.label_verification_unestablished),
        )) {
            inspect(name); assertEquals(name, expected, value())
            assertTrue(shadowOf(dialog()).message.toString().contains("Eligibility, source checks and current pricing are not established"))
            click(AlertDialog.BUTTON_NEGATIVE)
        }
        assertTrue(adapter().load() is PreviewDraftLoad.Missing)
        assertNull(shadowOf(activity).nextStartedActivity)
    }

    @Test fun cancelledAndReplacedRateFieldAndValueCallbacksCannotReopenOrReplaceCurrentDialog() {
        var authority = true
        val input = alternatives(10)
        input.getJSONArray("rates").getJSONObject(9).getJSONObject("provenance").put("notes", "n".repeat(400))
        show(input) { authority }
        val retiredRates = dialog()
        click(AlertDialog.BUTTON_POSITIVE)
        val currentRates = dialog()
        choose(0, retiredRates); click(AlertDialog.BUTTON_POSITIVE, retiredRates)
        assertSame(currentRates, dialog())
        assertTrue(currentRates.isShowing)
        choose(1)
        val retiredFields = dialog()
        click(AlertDialog.BUTTON_POSITIVE)
        val currentFields = dialog()
        choose(0, retiredFields); click(AlertDialog.BUTTON_POSITIVE, retiredFields); click(AlertDialog.BUTTON_NEGATIVE, retiredFields)
        assertSame(currentFields, dialog())
        assertTrue(currentFields.isShowing)
        inspect("rate.provenance.notes")
        val retiredValue = dialog()
        click(AlertDialog.BUTTON_POSITIVE)
        val currentValue = dialog()
        click(AlertDialog.BUTTON_POSITIVE, retiredValue); click(AlertDialog.BUTTON_NEGATIVE, retiredValue)
        assertSame(currentValue, dialog())
        assertTrue(currentValue.isShowing)
        authority = false
        click(AlertDialog.BUTTON_NEUTRAL, currentValue)
        assertSame(currentValue, dialog())
        assertFalse(currentValue.isShowing)
        authority = true
        click(AlertDialog.BUTTON_POSITIVE, currentValue); click(AlertDialog.BUTTON_NEGATIVE, currentValue)
        assertSame(currentValue, dialog())
        show(input)
        val cancelled = dialog()
        cancelled.cancel(); idle()
        choose(0, cancelled); click(AlertDialog.BUTTON_POSITIVE, cancelled)
        assertSame(cancelled, dialog())
        assertFalse(cancelled.isShowing)
        assertTrue(adapter().load() is PreviewDraftLoad.Missing)
        assertNull(shadowOf(activity).nextStartedActivity)
    }

    @Test fun actualReloadSnapshotReplacementAndRecreationRetireTheCapturedBrowser() {
        field(R.string.draft_title).setText("price lifecycle baseline")
        save()
        val baseline = loaded().baselineIdentity
        // Dialog frames must not advance this read-only case beyond the autosave deadline.
        AutosaveUiClock.pause()
        val pendingSince = SystemClock.uptimeMillis()
        field(R.string.context_objective).setText("pending lifecycle objective")
        val oldRates = openActualBrowser()
        val before = state()
        button(R.string.retry_catalog).performClick()
        await { button(R.string.retry_catalog).isEnabled && samples().size == 1 }
        choose(0, oldRates)
        assertSame("Identical bytes in a newly loaded book retire the old context", oldRates, dialog())
        val freshRates = openActualBrowser()
        choose(0)
        val oldFields = dialog()
        val root = activity.assets.open("catalog.json").bufferedReader(Charsets.UTF_8).use { JSONObject(it.readText()) }
        root.put("revision", root.getInt("revision") + 1)
        val replacement = requireNotNull(PreviewCatalogBrowser.read(root.toString().toByteArray()))
        views().filterIsInstance<CatalogBrowseSection>().single().show(replacement)
        click(AlertDialog.BUTTON_POSITIVE, oldFields)
        assertSame(oldFields, dialog())
        assertFalse(oldFields.isShowing)
        val recreatedActivity = activity
        recreate()
        assertTrue(recreatedActivity.isDestroyed)
        choose(0, freshRates); click(AlertDialog.BUTTON_NEGATIVE, oldFields)
        idle()
        assertDraftState(before)
        assertEquals(baseline, loaded().baselineIdentity)
        assertNull(loaded().fields.selectedId)
        openActualBrowser(); choose(0); inspect("rate.unit_price.amount")
        assertEquals("\"0.80\"", value())
        assertNull(shadowOf(activity).nextStartedActivity)
        assertTrue("Read-only actions stay before autosave", SystemClock.uptimeMillis() in pendingSince until pendingSince + 750)
    }

    @Test fun actualSavedBaselineChangeRetiresBrowserWithoutDiscardingPendingInputs() {
        save()
        val oldBaseline = loaded().baselineIdentity
        field(R.string.draft_title).setText("new saved baseline")
        openActualBrowser(); choose(0)
        val oldFields = dialog()
        // Saving through the actual editor replaces its acknowledged storage record while the
        // retained browser still holds the previous immutable baseline.
        save()
        assertNotEquals(oldBaseline, loaded().baselineIdentity)
        val currentBaseline = loaded().baselineIdentity
        field(R.string.context_context).setText("new pending context")
        val before = state()
        click(AlertDialog.BUTTON_POSITIVE, oldFields)
        assertSame(oldFields, dialog())
        assertFalse(oldFields.isShowing)
        click(AlertDialog.BUTTON_NEGATIVE, oldFields)
        assertSame(oldFields, dialog())
        assertDraftState(before)
        assertEquals(currentBaseline, loaded().baselineIdentity)
        openActualBrowser(); choose(0); inspect("rate.unit_price.amount")
        assertEquals("\"0.80\"", value())
        assertEquals(currentBaseline, loaded().baselineIdentity)
        assertNull(shadowOf(activity).nextStartedActivity)
    }

    @Test fun actualNewDetailRetiresOldBrowserEvenWhenItsCapturedDataIsUnchanged() {
        save()
        val baseline = loaded().baselineIdentity
        val priorDetail = openActualDetail()
        val priorEntry = button(R.string.catalog_price_browse, priorDetail.window!!.decorView)
        val priorCompare = button(R.string.catalog_compare_add, priorDetail.window!!.decorView)
        val sourceDetail = openActualDetail()
        click(AlertDialog.BUTTON_NEUTRAL, sourceDetail)
        val oldSources = dialog()
        assertNotSame(sourceDetail, oldSources)
        openActualBrowser(); choose(0)
        assertFalse("A retained earlier parent detail must also be dismissed", priorDetail.isShowing)
        val oldFields = dialog()
        val before = state()
        val newDetail = openActualDetail()
        assertFalse("Opening a new actual detail retires the previous browser instance", oldFields.isShowing)
        choose(0, oldFields); click(AlertDialog.BUTTON_POSITIVE, oldFields); click(AlertDialog.BUTTON_NEGATIVE, oldFields)
        assertSame(newDetail, dialog())
        assertTrue(newDetail.isShowing)
        button(R.string.catalog_price_browse, newDetail.window!!.decorView).performClick(); idle()
        val newRates = dialog()
        choose(0, oldFields)
        priorDetail.getButton(AlertDialog.BUTTON_POSITIVE).performClick()
        priorDetail.getButton(AlertDialog.BUTTON_NEUTRAL).performClick()
        priorCompare.performClick()
        priorEntry.performClick(); idle()
        choose(0, oldSources)
        assertSame(newRates, dialog())
        assertTrue(newRates.isShowing)
        assertDraftState(before)
        assertEquals(baseline, loaded().baselineIdentity)
        assertNull(shadowOf(activity).nextStartedActivity)
    }

    @Test
    @Config(qualifiers = "ar-rEG-ldrtl-w320dp-h640dp")
    @GraphicsMode(GraphicsMode.Mode.NATIVE)
    fun narrowRtlLargeTextPriceControlsRetainVisibleTargetsAndCompleteLabels() {
        RuntimeEnvironment.setFontScale(2f)
        recreate()
        assertEquals(2f, activity.resources.configuration.fontScale, 0f)
        val detail = openActualDetail()
        assertMeasuredButtons(detail, listOf(button(R.string.catalog_price_browse, detail.window!!.decorView),
            button(R.string.catalog_compare_add, detail.window!!.decorView)))
        button(R.string.catalog_price_browse, detail.window!!.decorView).performClick(); idle()
        choose(0)
        assertMeasuredButtons(dialog(), listOf(dialog().getButton(AlertDialog.BUTTON_POSITIVE), dialog().getButton(AlertDialog.BUTTON_NEGATIVE)))
        click(AlertDialog.BUTTON_POSITIVE)
        assertMeasuredButtons(dialog(), listOf(dialog().getButton(AlertDialog.BUTTON_POSITIVE), dialog().getButton(AlertDialog.BUTTON_NEUTRAL),
            dialog().getButton(AlertDialog.BUTTON_NEGATIVE)))
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
            assertTrue("48dp measured target height ${control.height}: ${control.text}", control.height >= (48 * density).toInt())
            assertTrue("48dp measured target width: ${control.text}", control.width >= (48 * density).toInt())
            // Native dialog buttons may stack into a scrollable bar at this text size. Use the
            // public scroll request that focus/accessibility navigation also uses, without a click.
            control.requestRectangleOnScreen(android.graphics.Rect(0, 0, control.width, control.height), true)
            // Verify each measured native ancestor rather than treating a simulated global
            // WindowManager frame as a physical screen visibility receipt.
            val bounds = android.graphics.Rect(0, 0, control.width, control.height)
            var child: View = control
            while (child !== root) {
                val parent = child.parent as ViewGroup
                parent.offsetDescendantRectToMyCoords(child, bounds)
                val viewport = android.graphics.Rect().also(parent::getDrawingRect)
                assertTrue("Action is not clipped by ${parent.javaClass.simpleName}: ${control.text}", viewport.contains(bounds))
                child = parent
            }
            val label = requireNotNull(control.layout)
            assertTrue(label.lineCount > 0)
            assertTrue("Complete label height: ${control.text}", label.height > 0 &&
                label.height <= control.height - control.compoundPaddingTop - control.compoundPaddingBottom)
            val textLeft = control.scrollX.toFloat()
            val textRight = textLeft + control.width - control.compoundPaddingLeft - control.compoundPaddingRight
            assertTrue("Positive text viewport width: ${control.text}", textRight > textLeft)
            repeat(label.lineCount) { line ->
                assertEquals("No ellipsized action label: ${control.text}", 0, label.getEllipsisCount(line))
                val left = label.getLineLeft(line)
                val right = label.getLineRight(line)
                assertTrue("Complete horizontal label line $line [$left, $right] in [$textLeft, $textRight]: ${control.text}",
                    left >= textLeft && right <= textRight && left <= right)
            }
            assertEquals("The layout reaches every action-label character: ${control.text}",
                label.text.length, label.getLineEnd(label.lineCount - 1))
        }
    }
}
