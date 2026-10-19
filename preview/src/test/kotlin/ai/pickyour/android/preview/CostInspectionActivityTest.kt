package ai.pickyour.android.preview

import android.app.AlertDialog
import android.graphics.Rect
import android.os.Bundle
import android.os.Looper
import android.os.SystemClock
import android.text.Spanned
import android.text.style.URLSpan
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import ai.pickyour.core.preview.PreviewDraftLibrary
import ai.pickyour.core.preview.PreviewDraftLoad
import ai.pickyour.core.pricing.CostEstimateAdapter
import ai.pickyour.core.pricing.CostReception
import org.json.JSONArray
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.android.controller.ActivityController
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import org.robolectric.annotation.LooperMode
import org.robolectric.RobolectricTestRunner
import org.robolectric.shadows.ShadowAlertDialog
import org.robolectric.shadows.ShadowDialog
import java.io.ByteArrayInputStream
import java.io.IOException
import java.io.InputStream
import java.nio.file.Files
import java.security.MessageDigest
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executor
import java.util.concurrent.ExecutorService
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.TimeUnit

/** Real Views, frozen readers and storage in a simulated Activity; no installed-pricing claim. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
@LooperMode(LooperMode.Mode.PAUSED)
class CostInspectionActivityTest {
    private lateinit var controller: ActivityController<PreviewActivity>
    private val activity get() = controller.get()
    private val harnesses = mutableListOf<Harness>()

    @Before fun launch() {
        AutosaveUiClock.pause()
        controller = Robolectric.buildActivity(PreviewActivity::class.java).setup()
        await { button(R.string.save_draft).isEnabled && button(R.string.retry_catalog).isEnabled }
        finishWork()
        assertTrue(adapter().load() is PreviewDraftLoad.Missing)
    }
    @After fun close() {
        harnesses.forEach { it.section.close() }
        controller.pause().stop().destroy()
    }

    private fun views(root: View = activity.window.decorView): List<View> = buildList {
        add(root)
        if (root is ViewGroup) repeat(root.childCount) { addAll(views(root.getChildAt(it))) }
    }
    private fun button(label: Int, root: View = activity.window.decorView) = views(root)
        .filterIsInstance<Button>().single { it.text.toString() == activity.getString(label) }
    private fun field(label: Int): EditText {
        val caption = views().filterIsInstance<TextView>().single { it.text.toString() == activity.getString(label) }
        return activity.findViewById(caption.labelFor)
    }
    private fun idle() = AutosaveUiClock.idle()
    private fun await(predicate: () -> Boolean) {
        val end = System.nanoTime() + TimeUnit.SECONDS.toNanos(15)
        while (System.nanoTime() < end) { idle(); if (predicate()) return; Thread.sleep(10) }
        fail("Expected current cost inspection state was not reached")
    }
    private fun worker() = PreviewActivity::class.java.getDeclaredField("worker").apply { isAccessible = true }
        .get(activity) as ExecutorService
    private fun finishWork() { repeat(2) { idle(); worker().submit {}.get(15, TimeUnit.SECONDS); idle() } }
    private fun adapter() = PreviewDraftLibrary(activity.noBackupFilesDir.toPath()).openLegacy()
    private fun loaded() = (adapter().load() as PreviewDraftLoad.Available).record
    private fun disk() = Files.walk(activity.noBackupFilesDir.toPath()).use { paths -> paths
        .filter(Files::isRegularFile).toList().associate {
            activity.noBackupFilesDir.toPath().relativize(it).toString() to sha(Files.readAllBytes(it))
        } }
    private fun sha(bytes: ByteArray) = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
    private fun fixture(name: String) = activity.assets.open(name).use { it.readBytes() }
    private fun save() {
        button(R.string.save_draft).performClick()
        await { !button(R.string.save_draft).isEnabled && views().filterIsInstance<TextView>().any {
            it.text.toString() == activity.getString(R.string.draft_saved)
        } }
        finishWork()
    }
    private fun state() = Bundle().also { controller.saveInstanceState(it) }
    private fun recreate(saved: Bundle = state()) {
        controller.pause().stop().destroy()
        controller = Robolectric.buildActivity(PreviewActivity::class.java).create(saved).start().resume().visible()
        await { button(R.string.retry_catalog).isEnabled && button(R.string.reopen_draft).isEnabled }
        finishWork()
    }
    private fun dialog() = requireNotNull(ShadowAlertDialog.getLatestAlertDialog())
    private fun rows(window: AlertDialog = dialog()) = (0 until window.listView.adapter.count)
        .map { window.listView.adapter.getItem(it).toString() }
    private fun choose(index: Int, window: AlertDialog = dialog()) {
        shadowOf(window).clickOnItem(index); idle(); discardDismissedShadowHistory()
    }
    private fun click(which: Int, window: AlertDialog = dialog()) {
        window.getButton(which).performClick(); idle(); discardDismissedShadowHistory()
    }
    private fun discardDismissedShadowHistory() {
        // Robolectric 4.17 retains every shown Dialog in a static strong history after dismiss.
        // Exhaustive value traversal must not accumulate that test-observer history. Preserve
        // every live window, the unchanged latest-dialog pointer and explicitly held stale controls.
        val observed = ShadowDialog.getShownDialogs()
        assertTrue("Cost navigation must leave at most one showing window", observed.count { it.isShowing } <= 1)
        observed.removeAll { !it.isShowing }
    }
    private fun message() = shadowOf(dialog()).message.toString()
    private fun findField(name: String) {
        while (dialog().getButton(AlertDialog.BUTTON_NEUTRAL).visibility == View.VISIBLE) click(AlertDialog.BUTTON_NEUTRAL)
        repeat(500) {
            val at = rows().indexOf(name)
            if (at >= 0) { choose(at); assertEquals(name, shadowOf(dialog()).title.toString()); return }
            assertEquals("Field must remain reachable: $name", View.VISIBLE, dialog().getButton(AlertDialog.BUTTON_POSITIVE).visibility)
            click(AlertDialog.BUTTON_POSITIVE)
        }
        fail("Field was omitted: $name")
    }
    private fun completeValue(): String? {
        val text = StringBuilder()
        var parts = 0
        do {
            val fullMessage = message()
            val partsOfMessage = fullMessage.split("\n\n")
            assertTrue("Expected the complete literal body, actual=[$fullMessage]", partsOfMessage.size >= 3)
            val literal = partsOfMessage[1]
            assertFalse(literal.contains("[truncated]"))
            if (literal == activity.getString(R.string.catalog_detail_missing)) return null
            text.append(JSONObject("{\"v\":$literal}").getString("v"))
            assertTrue(++parts < 10_000)
            if (dialog().getButton(AlertDialog.BUTTON_POSITIVE).visibility != View.VISIBLE) break
            click(AlertDialog.BUTTON_POSITIVE)
        } while (true)
        return text.toString()
    }
    private fun assertField(name: String, value: String?) {
        findField(name); assertEquals(name, value, completeValue()); click(AlertDialog.BUTTON_NEGATIVE)
    }
    private fun actualOpen() {
        button(R.string.cost_inspect).performClick()
        await { button(R.string.cost_inspection_browse).isEnabled }
        button(R.string.cost_inspection_browse).performClick(); idle()
    }
    private fun actualRecord() = (CostEstimateAdapter().inspect(
        fixture("build.json").inputStream(), fixture("cost-estimate.json").inputStream()) as CostReception.Inspected).estimate

    private class QueueWorker : Executor {
        val pending = mutableListOf<Runnable>()
        var reject = false
        override fun execute(command: Runnable) {
            if (reject) throw RejectedExecutionException("private worker detail")
            pending.add(command)
        }
        fun run(index: Int = 0) {
            val command = pending.removeAt(index)
            var failed: Throwable? = null
            val thread = Thread { try { command.run() } catch (failure: Throwable) { failed = failure } }
            thread.start(); thread.join(15_000)
            assertFalse("Bounded original input read must finish", thread.isAlive)
            failed?.let { throw AssertionError("Worker failed", it) }
        }
    }
    private inner class Harness {
        val root = LinearLayout(activity).apply { orientation = LinearLayout.VERTICAL }
        val worker = QueueWorker()
        var build = fixture("build.json")
        var estimate = fixture("cost-estimate.json")
        var sourceFailure = false
        var openCount = 0
        var closedCount = 0
        var navigation = 0
        val section = CostInspectionSection(activity, worker, { true }, { navigation++ },
            { source(build) }, { source(estimate) })
        private fun source(bytes: ByteArray): InputStream {
            openCount++
            if (sourceFailure) throw IOException("private provider credential and path")
            return object : ByteArrayInputStream(bytes) {
                override fun close() { closedCount++; super.close() }
            }
        }
        init {
            val content = (activity.findViewById<ViewGroup>(android.R.id.content).getChildAt(0) as ScrollView)
                .getChildAt(0) as LinearLayout
            content.addView(root)
            section.attach(root)
            harnesses.add(this)
        }
        fun control(label: Int) = button(label, root)
        fun start() { control(R.string.cost_inspect).performClick() }
        fun finish(index: Int = 0) { worker.run(index); idle() }
        fun open() {
            start(); finish()
            assertTrue("Expected an inspectable frozen record; actual=${text()}", control(R.string.cost_inspection_browse).isEnabled)
            control(R.string.cost_inspection_browse).performClick(); idle()
        }
        fun text() = views(root).filterIsInstance<TextView>().joinToString("\n") { it.text.toString() }
    }


    @Test fun packagedInputsExposeEveryRecordedFieldAndBothDocumentsWithoutSaving() {
        val before = disk()
        val record = actualRecord()
        actualOpen()
        assertEquals(5, rows().size)
        assertTrue(views().filterIsInstance<TextView>().any {
            it.text.toString() == CostInspectionText.describe(activity, record)
        })
        choose(0)
        for ((name, raw) in listOf(
            "cost_estimate.id" to record.estimateId,
            "cost_estimate.build_id" to record.buildId,
            "cost_estimate.build_revision" to record.buildRevision.toString(),
            "cost_estimate.pricebook_id" to record.pricebookId,
            "cost_estimate.period_days" to record.periodDays.toString(),
            "cost_estimate.estimated_at" to record.estimatedAt,
            "cost_estimate.currency" to record.currency,
            "cost_estimate.coverage" to record.declaredCoverage.name.lowercase(java.util.Locale.ROOT),
            "subtotal.amount" to record.reportedSubtotal.amount.encoded,
            "subtotal.currency" to record.reportedSubtotal.currency,
            "tax.amount" to record.reportedTax.amount.encoded,
            "tax.currency" to record.reportedTax.currency,
            "total.amount" to record.reportedTotal.amount.encoded,
            "total.currency" to record.reportedTotal.currency,
            "freshness" to record.freshness.name,
            "free_or_included_treatment" to record.freeOrIncludedTreatment.name,
            "budget_pass" to record.budgetPass.name,
        )) assertField(name, raw)
        click(AlertDialog.BUTTON_NEGATIVE)
        choose(1)
        record.lines.forEachIndexed { index, line ->
            choose(index)
            for ((name, raw) in listOf(
                "id" to line.id, "selection_id" to line.selectionId, "price_rate_id" to line.rateId,
                "meter" to line.meter, "quantity" to line.quantity,
                "unit_price.amount" to line.unitPrice.amount.encoded, "unit_price.currency" to line.unitPrice.currency,
                "amount.amount" to line.amount.amount.encoded, "amount.currency" to line.amount.currency,
                "method" to line.method, "confidence" to line.confidence,
                "assumptions.length" to line.assumptions.size.toString(),
            )) assertField(name, raw)
            line.assumptions.forEachIndexed { at, raw -> assertField("assumptions[$at]", raw) }
            click(AlertDialog.BUTTON_NEGATIVE)
        }
        click(AlertDialog.BUTTON_NEGATIVE)
        choose(4)
        assertField("cost_estimate", record.recordedJson)
        assertField("build", record.recordedBuildJson)
        assertEquals(before, disk())
        assertTrue(adapter().load() is PreviewDraftLoad.Missing)
        assertNull(shadowOf(activity).nextStartedActivity)
    }

    @Test fun everyLineAssumptionWarningAndSelectionReferenceSurvivesSummaryLimits() {
        val h = Harness()
        val build = JSONObject(String(h.build, Charsets.UTF_8))
        val selections = build.getJSONArray("selections")
        val unpriced = JSONArray()
        repeat(10) { unpriced.put(selections.getJSONObject(it).getString("id")) }
        val estimate = JSONObject(String(h.estimate, Charsets.UTF_8))
        val template = estimate.getJSONArray("line_items").getJSONObject(0)
        val entries = JSONArray()
        repeat(11) { at ->
            val line = JSONObject(template.toString()).put("id", "line.complete.$at").put("quantity", "1")
            line.getJSONObject("amount").put("amount", "0.00")
            line.getJSONObject("unit_price").put("amount", "0.0000")
            val assumptions = JSONArray()
            repeat(13) { assumptions.put("line $at assumption $it") }
            line.put("assumptions", assumptions)
            entries.put(line)
        }
        val warnings = JSONArray()
        repeat(17) { warnings.put("recorded warning $it") }
        estimate.put("line_items", entries).put("coverage", "partial")
            .put("unpriced_selection_ids", unpriced).put("warnings", warnings)
        for (name in listOf("subtotal", "tax", "total")) estimate.getJSONObject(name).put("amount", "0.00")
        h.estimate = estimate.toString().toByteArray(Charsets.UTF_8)
        val record = (CostEstimateAdapter().inspect(h.build.inputStream(), h.estimate.inputStream()) as CostReception.Inspected).estimate
        val before = disk()
        h.open()
        assertFalse(h.text().contains("line.complete.10"))
        assertFalse(h.text().contains("recorded warning 16"))
        choose(1)
        for (at in 0 until entries.length()) {
            if (at > 0 && at % 8 == 0) click(AlertDialog.BUTTON_POSITIVE)
            assertTrue(rows().size <= 8)
            choose(at % 8)
            assertField("id", "line.complete.$at")
            repeat(13) { assertField("assumptions[$it]", "line $at assumption $it") }
            click(AlertDialog.BUTTON_NEGATIVE)
        }
        click(AlertDialog.BUTTON_NEGATIVE)
        choose(2)
        assertField("recorded_line_selection_ids.length", "11")
        repeat(11) { assertField("recorded_line_selection_ids[$it]", template.getString("selection_id")) }
        assertField("unpriced_selection_ids.length", "10")
        repeat(10) { assertField("unpriced_selection_ids[$it]", unpriced.getString(it)) }
        assertField("unclassified_active_selection_ids.length", record.unclassifiedActiveSelectionIds.size.toString())
        record.unclassifiedActiveSelectionIds.forEachIndexed { at, raw ->
            assertField("unclassified_active_selection_ids[$at]", raw)
        }
        assertField("classification_limits", activity.getString(R.string.cost_browser_reference_limits))
        assertTrue(record.reportedUnpricedSelectionIds.contains(template.getString("selection_id")))
        click(AlertDialog.BUTTON_NEGATIVE)
        choose(3)
        repeat(17) { assertField("warnings[$it]", "recorded warning $it") }
        assertEquals(before, disk())
        assertEquals(h.openCount, h.closedCount)
    }

    @Test fun exactLongDecimalsQuantitiesUnicodeAndInertReferencesAreCompletelyPaged() {
        val h = Harness()
        val build = JSONObject(String(h.build, Charsets.UTF_8))
        // Keep the reader's unchanged 128-character JSON-number budget. The original
        // 240-digit fixture is a rejected control, not a reason to relax that boundary.
        build.put("revision", java.math.BigInteger("9".repeat(240)))
        assertEquals(CostReception.Rejected(ai.pickyour.core.pricing.CostRejection.INVALID_BUILD),
            CostEstimateAdapter().inspect(build.toString().byteInputStream(), h.estimate.inputStream()))
        val revision = java.math.BigInteger("9".repeat(128))
        build.put("revision", revision)
        h.build = build.toString().toByteArray(Charsets.UTF_8)
        val estimate = JSONObject(String(h.estimate, Charsets.UTF_8)).put("build_revision", revision)
        val line = estimate.getJSONArray("line_items").getJSONObject(0)
        val amount = "9".repeat(260) + ".1200000"
        val quantity = "7".repeat(420) + ".123456789012\n"
        val assumption = "x".repeat(159) + "😀\u202e\n" + "tail".repeat(60)
        val uri = "https://user:literal@example.invalid/" + "p".repeat(350) + "?x=1"
        line.put("quantity", quantity).put("assumptions", JSONArray().put(assumption).put("").put(uri))
        line.getJSONObject("unit_price").put("amount", "0.000012300000")
        line.getJSONObject("amount").put("amount", amount)
        estimate.put("line_items", JSONArray().put(line)).put("warnings", JSONArray().put(uri))
        estimate.getJSONObject("subtotal").put("amount", amount)
        estimate.getJSONObject("tax").put("amount", "0.0000000")
        estimate.getJSONObject("total").put("amount", amount)
        h.estimate = estimate.toString().toByteArray(Charsets.UTF_8)
        h.open()
        val record = (CostEstimateAdapter().inspect(h.build.inputStream(), h.estimate.inputStream()) as CostReception.Inspected).estimate
        assertTrue(h.text().contains(CostInspectionText.describe(activity, record)))
        choose(0)
        assertField("cost_estimate.build_revision", revision.toString())
        assertField("subtotal.amount", amount)
        assertField("total.amount", amount)
        click(AlertDialog.BUTTON_NEGATIVE)
        choose(1); choose(0)
        assertField("quantity", quantity)
        assertField("unit_price.amount", "0.000012300000")
        assertField("amount.amount", amount)
        assertField("assumptions[0]", assumption)
        assertField("assumptions[1]", "")
        findField("assumptions[2]")
        val text = dialog().window!!.decorView.findViewById<TextView>(android.R.id.message)
        assertEquals(0, text.autoLinkMask)
        assertFalse(text.linksClickable)
        assertNull(text.movementMethod)
        assertTrue((text.text as? Spanned)?.getSpans(0, text.text.length, URLSpan::class.java).isNullOrEmpty())
        assertEquals(uri, completeValue())
        assertNull(shadowOf(activity).nextStartedActivity)
        assertEquals(h.openCount, h.closedCount)
    }

    @Test fun declaredCoverageZeroAmountsOldTimesAndProducerClaimsNeverBecomeBudgetOrFreeApproval() {
        val h = Harness()
        val original = h.estimate
        for (coverage in listOf("complete", "partial", "unknown")) {
            val estimate = JSONObject(String(original, Charsets.UTF_8))
                .put("coverage", coverage).put("estimated_at", "2000-01-01T00:00:00Z")
                .put("warnings", JSONArray().put("Producer says included; this remains an unverified declaration."))
            val lines = estimate.getJSONArray("line_items")
            repeat(lines.length()) {
                lines.getJSONObject(it).getJSONObject("amount").put("amount", "0.00")
                lines.getJSONObject(it).getJSONObject("unit_price").put("amount", "0.00")
            }
            for (name in listOf("subtotal", "tax", "total")) estimate.getJSONObject(name).put("amount", "0.00")
            h.estimate = estimate.toString().toByteArray(Charsets.UTF_8)
            h.open()
            assertTrue(h.text().contains(activity.getString(R.string.cost_evidence_limits)))
            choose(0)
            assertField("cost_estimate.coverage", coverage)
            assertField("cost_estimate.estimated_at", "2000-01-01T00:00:00Z")
            assertField("subtotal.amount", "0.00")
            for (name in listOf("freshness", "free_or_included_treatment", "budget_pass"))
                assertField(name, "NOT_ESTABLISHED")
            h.control(R.string.cost_inspection_cancel).performClick(); idle()
        }
        assertTrue(adapter().load() is PreviewDraftLoad.Missing)
    }

    @Test fun emptyCollectionsAndUnclassifiedSelectionsRemainExplicitRatherThanACompleteZeroTotal() {
        val h = Harness()
        val estimate = JSONObject(String(h.estimate, Charsets.UTF_8))
            .put("line_items", JSONArray()).put("warnings", JSONArray()).put("coverage", "unknown")
        for (name in listOf("subtotal", "tax", "total")) estimate.getJSONObject(name).put("amount", "0.00")
        h.estimate = estimate.toString().toByteArray(Charsets.UTF_8)
        val record = (CostEstimateAdapter().inspect(h.build.inputStream(), h.estimate.inputStream()) as CostReception.Inspected).estimate
        h.open(); choose(1)
        assertEquals(activity.getString(R.string.cost_none_declared), message())
        click(AlertDialog.BUTTON_NEGATIVE); choose(3)
        assertEquals(activity.getString(R.string.cost_none_declared), message())
        click(AlertDialog.BUTTON_NEGATIVE); choose(2)
        assertField("recorded_line_selection_ids.length", "0")
        assertField("unpriced_selection_ids.length", "0")
        assertTrue(record.unclassifiedActiveSelectionIds.isNotEmpty())
        record.unclassifiedActiveSelectionIds.forEachIndexed { at, raw ->
            assertField("unclassified_active_selection_ids[$at]", raw)
        }
        assertTrue(h.text().contains("Missing classification never means zero cost."))
        assertTrue(h.text().contains("not a complete stack price or a proven lower bound"))
    }

    @Test fun rejectedUnavailableAndFutureInputsExposeNoPartialRecordOrProviderTextAndCanRetry() {
        val h = Harness()
        val original = h.estimate
        val invalid = listOf(
            JSONObject(String(original, Charsets.UTF_8)).put("build_revision", 987654).toString() to "BINDING_MISMATCH",
            JSONObject(String(original, Charsets.UTF_8)).put("schema_version", "future").toString() to "UNSUPPORTED_ESTIMATE_SCHEMA_VERSION",
            JSONObject(String(original, Charsets.UTF_8)).apply { getJSONObject("total").put("currency", "EUR") }.toString() to "MIXED_CURRENCY",
            JSONObject(String(original, Charsets.UTF_8)).apply { getJSONObject("total").put("amount", "1.00") }.toString() to "INCONSISTENT_AMOUNTS",
            "{private malformed" to "INVALID_ESTIMATE",
        )
        val before = disk()
        for ((input, reason) in invalid) {
            h.estimate = input.toByteArray(Charsets.UTF_8); h.start(); h.finish()
            assertFalse(h.control(R.string.cost_inspection_browse).isEnabled)
            assertTrue(h.text().contains(activity.getString(R.string.cost_rejected, reason)))
            assertFalse(h.text().contains("private malformed"))
            assertEquals(h.openCount, h.closedCount)
        }
        h.sourceFailure = true
        h.start(); h.finish()
        assertTrue(h.text().contains(activity.getString(R.string.cost_unavailable)))
        assertFalse(h.text().contains("private provider"))
        assertFalse(h.control(R.string.cost_inspection_browse).isEnabled)
        h.sourceFailure = false; h.estimate = original; h.open()
        assertEquals(5, rows().size)
        assertEquals(h.openCount - 1, h.closedCount) // The failed open returned no stream to close.
        assertEquals(before, disk())
    }

    @Test fun canceledAndReversedReadsCannotReplaceTheCurrentSummaryOrDetails() {
        val h = Harness()
        val original = h.estimate
        h.start()
        h.control(R.string.cost_inspect).performClick()
        assertEquals(1, h.worker.pending.size)
        h.control(R.string.cost_inspection_cancel).performClick()
        h.start()
        h.estimate = JSONObject(String(original, Charsets.UTF_8)).put("id", "estimate.current").toString().toByteArray()
        h.finish(1)
        val currentSummary = h.text()
        h.control(R.string.cost_inspection_browse).performClick(); idle(); choose(0)
        assertField("cost_estimate.id", "estimate.current")
        val currentDialog = dialog()
        h.estimate = original
        h.finish(0)
        assertEquals(currentSummary, h.text())
        assertSame(currentDialog, dialog())
        assertField("cost_estimate.id", "estimate.current")
        assertEquals(h.openCount, h.closedCount)
        assertTrue(adapter().load() is PreviewDraftLoad.Missing)
    }

    @Test fun equalBuildRevisionReinspectionUsesANewIdentityAndRetiresAllFormerValueControls() {
        val h = Harness()
        h.open(); choose(0)
        val revision = actualRecord().buildRevision.toString()
        assertField("cost_estimate.build_revision", revision)
        findField("cost_estimate.id")
        val old = dialog()
        h.control(R.string.cost_inspection_cancel).performClick(); idle()
        h.estimate = JSONObject(String(h.estimate, Charsets.UTF_8)).put("id", "estimate.replacement").toString().toByteArray()
        h.open(); choose(0)
        assertField("cost_estimate.id", "estimate.replacement")
        assertField("cost_estimate.build_revision", revision)
        val current = dialog()
        old.getButton(AlertDialog.BUTTON_NEGATIVE).performClick(); idle()
        assertSame(current, dialog())
        assertFalse(old.isShowing)
        assertTrue(h.text().contains("estimate.replacement"))
    }

    @Test fun closedAndReplacedMenusRowsAndPageButtonsCannotReopenAnOldInspection() {
        val h = Harness()
        h.open(); val menu = dialog(); choose(0)
        val fields = dialog(); findField("cost_estimate.id"); val oldValue = dialog()
        click(AlertDialog.BUTTON_NEGATIVE); val currentFields = dialog()
        oldValue.getButton(AlertDialog.BUTTON_NEGATIVE).performClick()
        shadowOf(fields).clickOnItem(0); shadowOf(menu).clickOnItem(4); idle()
        assertSame(currentFields, dialog())
        click(AlertDialog.BUTTON_NEGATIVE)
        val root = dialog()
        click(AlertDialog.BUTTON_NEGATIVE)
        assertFalse(root.isShowing)
        shadowOf(root).clickOnItem(1); idle()
        assertFalse(root.isShowing)
        h.control(R.string.cost_inspection_browse).performClick(); idle()
        val replacement = dialog()
        oldValue.getButton(AlertDialog.BUTTON_NEGATIVE).performClick()
        currentFields.getButton(AlertDialog.BUTTON_POSITIVE).performClick()
        shadowOf(menu).clickOnItem(0); idle()
        assertSame(replacement, dialog())
        assertEquals(5, rows().size)
    }

    @Test fun pauseRetiresAPendingActualReadBeforeResumeAndRequiresExplicitReinspection() {
        val entered = CountDownLatch(1); val release = CountDownLatch(1)
        worker().submit { entered.countDown(); release.await(15, TimeUnit.SECONDS) }
        assertTrue(entered.await(15, TimeUnit.SECONDS))
        try {
            button(R.string.cost_inspect).performClick()
            assertFalse(button(R.string.cost_inspect).isEnabled)
            controller.pause()
            assertFalse(button(R.string.cost_inspection_browse).isEnabled)
            release.countDown(); worker().submit {}.get(15, TimeUnit.SECONDS); idle()
            controller.resume(); idle()
            assertFalse(button(R.string.cost_inspection_browse).isEnabled)
            assertTrue(views().filterIsInstance<TextView>().any { it.text.toString() == activity.getString(R.string.cost_inspection_idle) })
            actualOpen(); assertEquals(5, rows().size)
        } finally { release.countDown() }
    }

    @Test fun recreationPreservesPendingEditorDataAndDiskButRetiresAllInspectionWindowsAndControls() {
        save()
        val baseline = loaded().baselineIdentity
        field(R.string.draft_title).setText("pending cost inspection")
        actualOpen(); choose(1); choose(0); findField("quantity")
        val priorDialog = dialog()
        val priorInspect = button(R.string.cost_inspect)
        val priorBrowse = button(R.string.cost_inspection_browse)
        val before = disk()
        recreate()
        assertFalse(priorDialog.isShowing)
        assertEquals("pending cost inspection", field(R.string.draft_title).text.toString())
        assertEquals(baseline, loaded().baselineIdentity)
        assertEquals(before, disk())
        assertFalse(button(R.string.cost_inspection_browse).isEnabled)
        priorInspect.performClick(); priorBrowse.performClick()
        priorDialog.getButton(AlertDialog.BUTTON_NEGATIVE).performClick(); idle()
        assertFalse(priorDialog.isShowing)
        assertFalse(button(R.string.cost_inspection_browse).isEnabled)
        actualOpen(); assertTrue(dialog().isShowing)
        assertEquals(before, disk())
    }

    @Test fun costNavigationPreservesTheActualAutosaveDeadlineAndCreatesNoExtraRevision() {
        save()
        val beforeRecord = loaded()
        val baseline = beforeRecord.baselineIdentity
        val original = disk()
        field(R.string.draft_title).setText("cost inspection pending")
        val started = SystemClock.uptimeMillis()
        actualOpen(); choose(0); assertField("cost_estimate.build_id", actualRecord().buildId)
        assertEquals(started, SystemClock.uptimeMillis())
        assertEquals(baseline, loaded().baselineIdentity); assertEquals(original, disk())
        shadowOf(Looper.getMainLooper()).idleFor(749, TimeUnit.MILLISECONDS); finishWork()
        assertEquals(baseline, loaded().baselineIdentity); assertEquals(original, disk())
        shadowOf(Looper.getMainLooper()).idleFor(1, TimeUnit.MILLISECONDS)
        await { !button(R.string.save_draft).isEnabled && views().filterIsInstance<TextView>().any {
            it.text.toString() == activity.getString(R.string.draft_saved)
        } }
        finishWork()
        val saved = loaded()
        assertEquals("cost inspection pending", saved.fields.title)
        assertEquals(beforeRecord.revision + 1, saved.revision)
        val acknowledged = disk()
        click(AlertDialog.BUTTON_NEGATIVE)
        button(R.string.cost_inspection_cancel).performClick(); idle()
        shadowOf(Looper.getMainLooper()).idleFor(1000, TimeUnit.MILLISECONDS); finishWork()
        assertEquals(saved.baselineIdentity, loaded().baselineIdentity)
        assertEquals(acknowledged, disk())
    }

    @Test fun executorRejectionIsExplicitAndClosedSectionsDiscardQueuedCompletion() {
        val h = Harness()
        val before = disk()
        h.worker.reject = true
        h.start(); idle()
        assertEquals(0, h.openCount)
        assertTrue(h.text().contains(activity.getString(R.string.cost_unavailable)))
        assertFalse(h.text().contains("private worker"))
        assertFalse(h.control(R.string.cost_inspection_browse).isEnabled)
        assertTrue(h.control(R.string.cost_inspect).isEnabled)
        h.worker.reject = false
        h.start()
        h.section.close()
        val closed = h.text()
        h.finish()
        assertEquals(closed, h.text())
        assertFalse(h.control(R.string.cost_inspect).isEnabled)
        assertFalse(h.control(R.string.cost_inspection_browse).isEnabled)
        h.control(R.string.cost_inspect).performClick()
        assertTrue(h.worker.pending.isEmpty())
        assertEquals(before, disk())
        assertEquals(h.openCount, h.closedCount)
    }

    @Test
    @Config(qualifiers = "ar-rEG-ldrtl-w320dp-h640dp")
    @GraphicsMode(GraphicsMode.Mode.NATIVE)
    fun narrowRtlLargeTextKeepsEntryControlsAndEveryCompleteValueLineReachable() {
        RuntimeEnvironment.setFontScale(2f)
        recreate()
        val root = activity.window.decorView
        fun layout(root: View) {
            val density = activity.resources.displayMetrics.density
            val width = (320 * density).toInt(); val height = (640 * density).toInt()
            root.measure(View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(height, View.MeasureSpec.EXACTLY))
            root.layout(0, 0, width, height)
        }
        fun reachable(root: View, control: View, target: Rect = Rect(0, 0, control.width, control.height)) {
            control.requestRectangleOnScreen(Rect(target), true)
            val bounds = Rect(target)
            var child = control
            while (child !== root) {
                val parent = child.parent as ViewGroup
                parent.offsetDescendantRectToMyCoords(child, bounds)
                assertTrue("Cost target must be reachable: $bounds in " + parent.javaClass.simpleName,
                    Rect().also(parent::getDrawingRect).contains(bounds))
                child = parent
            }
        }
        layout(root)
        assertEquals(View.LAYOUT_DIRECTION_RTL, root.layoutDirection)
        val inspect = button(R.string.cost_inspect)
        assertTrue(inspect.height >= (48 * activity.resources.displayMetrics.density).toInt())
        reachable(root, inspect)
        actualOpen(); choose(1); choose(0); findField("quantity")
        val windowRoot = dialog().window!!.decorView
        layout(windowRoot)
        assertEquals(View.LAYOUT_DIRECTION_RTL, windowRoot.layoutDirection)
        val message = windowRoot.findViewById<TextView>(android.R.id.message)
        val lines = requireNotNull(message.layout)
        assertEquals(message.text.length, lines.getLineEnd(lines.lineCount - 1))
        for (line in 0 until lines.lineCount) {
            assertEquals(0, lines.getEllipsisCount(line))
            reachable(windowRoot, message, Rect(0, message.totalPaddingTop + lines.getLineTop(line), message.width,
                message.totalPaddingTop + lines.getLineBottom(line)))
        }
        val back = dialog().getButton(AlertDialog.BUTTON_NEGATIVE)
        assertTrue(back.height >= (48 * activity.resources.displayMetrics.density).toInt())
        reachable(windowRoot, back)
        assertNull(shadowOf(activity).nextStartedActivity)
        assertTrue(adapter().load() is PreviewDraftLoad.Missing)
    }
}
