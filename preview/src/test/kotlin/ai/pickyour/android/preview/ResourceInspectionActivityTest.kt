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
import ai.pickyour.core.resources.RecordedResourceInspector
import ai.pickyour.core.resources.ResourceInspection
import ai.pickyour.core.resources.ResourceInspectionGap
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

/** Real Views, frozen readers and storage in a simulated Activity; no installed-capacity claim. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
@LooperMode(LooperMode.Mode.PAUSED)
class ResourceInspectionActivityTest {
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
        fail("Expected current resource inspection state was not reached")
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
        assertTrue("Resource navigation must leave at most one showing window", observed.count { it.isShowing } <= 1)
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
        button(R.string.resource_inspection_open).performClick()
        await { button(R.string.resource_inspection_browse).isEnabled }
        button(R.string.resource_inspection_browse).performClick(); idle()
    }
    private fun actualRecord() = (RecordedResourceInspector().inspect(
        fixture("build.json").inputStream(), fixture("resource-estimate.json").inputStream()) as ResourceInspection.Inspected).record

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
        var estimate = fixture("resource-estimate.json")
        var sourceFailure = false
        var openCount = 0
        var closedCount = 0
        var navigation = 0
        val section = ResourceInspectionSection(activity, worker, { true }, { navigation++ },
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
        fun start() { control(R.string.resource_inspection_open).performClick() }
        fun finish(index: Int = 0) { worker.run(index); idle() }
        fun open() {
            start(); finish()
            assertTrue(control(R.string.resource_inspection_browse).isEnabled)
            control(R.string.resource_inspection_browse).performClick(); idle()
        }
        fun text() = views(root).filterIsInstance<TextView>().joinToString("\n") { it.text.toString() }
    }

    @Test fun packagedInputsExposeCompleteRecordedFieldsAndExactBuildConditionsWithoutSaving() {
        val before = disk()
        val record = actualRecord()
        actualOpen()
        assertEquals(4, rows().size)
        choose(0)
        assertField("resource_estimate.schema_version", record.estimate.schemaVersion)
        assertField("resource_estimate.id", record.estimate.id)
        assertField("resource_estimate.build_id", record.buildId)
        assertField("resource_estimate.build_revision", record.buildRevision.toString())
        assertField("resource_estimate.generated_at", record.estimate.generatedAt)
        assertField("build.id", record.buildId)
        assertField("build.revision", record.buildRevision.toString())
        assertField("build.name", record.buildName)
        assertField("build.policy.min_resource_confidence", record.declaredMinimumConfidence)
        assertField("consumed_build_sha256", sha(fixture("build.json")))
        assertField("consumed_resource_estimate_sha256", sha(fixture("resource-estimate.json")))
        ResourceInspectionGap.entries.forEach { findField(it.name); assertFalse(completeValue().isNullOrEmpty()); click(AlertDialog.BUTTON_NEGATIVE) }
        click(AlertDialog.BUTTON_NEGATIVE)
        choose(1)
        for ((index, entry) in record.estimate.estimates.withIndex()) {
            choose(index)
            for ((name, value) in listOf("metric" to entry.metric.encoded, "value" to entry.value, "unit" to entry.unit.encoded,
                "estimator_id" to entry.estimatorId, "estimator_version" to entry.estimatorVersion,
                "confidence" to entry.confidence.encoded, "source_uri" to entry.sourceUri,
                "assumptions.length" to entry.assumptions.size.toString(), "selection_ids.length" to entry.selectionIds.size.toString()))
                assertField(name, value)
            entry.assumptions.forEachIndexed { at, raw -> assertField("assumptions[$at]", raw) }
            entry.selectionIds.forEachIndexed { at, raw -> assertField("selection_ids[$at]", raw) }
            click(AlertDialog.BUTTON_NEGATIVE)
        }
        click(AlertDialog.BUTTON_NEGATIVE)
        choose(2)
        record.selections.forEachIndexed { index, selection ->
            if (index > 0 && index % 8 == 0) click(AlertDialog.BUTTON_POSITIVE)
            choose(index % 8)
            for ((name, raw) in listOf("id" to selection.id, "kind" to selection.kind,
                "catalog_item_id" to selection.catalogItemId, "catalog_item_version" to selection.catalogItemVersion,
                "role" to selection.role, "enabled" to selection.enabled.toString(),
                "settings" to selection.settingsJson, "complete_selection" to selection.recordedJson)) assertField(name, raw)
            click(AlertDialog.BUTTON_NEGATIVE)
        }
        click(AlertDialog.BUTTON_NEGATIVE)
        choose(3)
        assertField("resource_estimate", record.estimate.json)
        assertField("build", record.recordedBuildJson)
        assertEquals(before, disk())
        assertTrue(adapter().load() is PreviewDraftLoad.Missing)
        assertNull(shadowOf(activity).nextStartedActivity)
    }

    @Test fun exactLongValuesControlsUnicodeAndSourceUrisArePagedWithoutAutomaticNavigation() {
        val h = Harness()
        val json = JSONObject(String(h.estimate, Charsets.UTF_8))
        val entry = json.getJSONArray("estimates").getJSONObject(0)
        val value = "9".repeat(420) + ".123456789012\n"
        val assumption = "prefix " + "x".repeat(158) + "😀\u202e\n" + "tail".repeat(60)
        val uri = "https://user:literal@example.invalid/" + "p".repeat(350) + "?x=1"
        entry.put("value", value).put("assumptions", JSONArray().put(assumption).put("")).put("source_uri", uri)
        h.estimate = json.toString().toByteArray(Charsets.UTF_8)
        h.open(); choose(1); choose(0)
        assertField("value", value)
        assertField("assumptions[0]", assumption)
        assertField("assumptions[1]", "")
        findField("source_uri")
        val message = views(dialog().window!!.decorView).filterIsInstance<TextView>().single {
            it.id == android.R.id.message
        }
        assertEquals(0, message.autoLinkMask)
        assertFalse(message.linksClickable)
        assertTrue((message.text as? Spanned)?.getSpans(0, message.text.length, URLSpan::class.java).isNullOrEmpty())
        assertEquals(uri, completeValue())
        assertNull(shadowOf(activity).nextStartedActivity)
        assertEquals(h.openCount, h.closedCount)
    }

    @Test fun missingConfidencePolicyOldClaimsAndMetricUnitMismatchNeverBecomeCapacityApproval() {
        val h = Harness()
        val build = JSONObject(String(h.build, Charsets.UTF_8))
        build.getJSONObject("policy").remove("min_resource_confidence")
        h.build = build.toString().toByteArray(Charsets.UTF_8)
        val estimate = JSONObject(String(h.estimate, Charsets.UTF_8)).put("generated_at", "2000-01-01T00:00:00Z")
        estimate.getJSONArray("estimates").getJSONObject(0).put("confidence", "unverified").put("unit", "watt")
        h.estimate = estimate.toString().toByteArray(Charsets.UTF_8)
        h.open(); choose(0)
        assertField("build.policy.min_resource_confidence", null)
        assertField("resource_estimate.generated_at", "2000-01-01T00:00:00Z")
        ResourceInspectionGap.entries.forEach { findField(it.name); assertFalse(completeValue().isNullOrEmpty()); click(AlertDialog.BUTTON_NEGATIVE) }
        click(AlertDialog.BUTTON_NEGATIVE); choose(1); choose(0)
        assertField("confidence", "unverified"); assertField("unit", "watt")
        assertTrue(h.text().contains("memory fit remain unestablished"))
        assertTrue(adapter().load() is PreviewDraftLoad.Missing)
    }

    @Test fun rejectedAndUnavailableInputsExposeNoPartialRecordOrProviderTextAndCanRetry() {
        val h = Harness()
        val original = h.estimate
        h.estimate = JSONObject(String(original, Charsets.UTF_8)).put("build_revision", 987654).toString().toByteArray()
        h.start(); h.finish()
        assertFalse(h.control(R.string.resource_inspection_browse).isEnabled)
        assertTrue(h.text().contains("BUILD_BINDING_MISMATCH"))
        assertEquals(h.openCount, h.closedCount)
        h.sourceFailure = true
        h.start(); h.finish()
        assertTrue(h.text().contains(activity.getString(R.string.resource_inspection_unavailable)))
        assertFalse(h.text().contains("private provider"))
        assertFalse(h.control(R.string.resource_inspection_browse).isEnabled)
        h.sourceFailure = false
        h.estimate = original
        h.open()
        assertEquals(4, rows().size)
        assertTrue(adapter().load() is PreviewDraftLoad.Missing)
    }

    @Test fun canceledAndReversedActualReadsCannotReplaceTheCurrentInspection() {
        val h = Harness()
        val original = h.estimate
        h.start()
        h.control(R.string.resource_inspection_open).performClick()
        assertEquals("Disabled start cannot dispatch another read", 1, h.worker.pending.size)
        h.control(R.string.resource_inspection_cancel).performClick()
        assertFalse(h.control(R.string.resource_inspection_browse).isEnabled)
        h.start()
        h.estimate = JSONObject(String(original, Charsets.UTF_8)).put("id", "resource.current").toString().toByteArray()
        h.finish(1)
        h.control(R.string.resource_inspection_browse).performClick(); idle(); choose(0)
        assertField("resource_estimate.id", "resource.current")
        val current = dialog()
        h.estimate = original
        h.finish(0)
        assertSame(current, dialog())
        assertField("resource_estimate.id", "resource.current")
        assertEquals(h.openCount, h.closedCount)
        assertTrue(adapter().load() is PreviewDraftLoad.Missing)
    }

    @Test fun identicalBuildRevisionWithChangedEstimateBytesHasASeparateExactInspectionIdentity() {
        val h = Harness()
        val original = h.estimate
        h.open(); choose(0)
        val revision = actualRecord().buildRevision.toString()
        assertField("build.revision", revision)
        assertField("consumed_resource_estimate_sha256", sha(original))
        h.control(R.string.resource_inspection_cancel).performClick(); idle()
        assertFalse(dialog().isShowing)
        h.estimate = " \n".toByteArray() + original + "\n ".toByteArray()
        h.open(); choose(0)
        assertField("build.revision", revision)
        assertField("consumed_resource_estimate_sha256", sha(h.estimate))
        assertNotEquals(sha(original), sha(h.estimate))
        assertTrue(adapter().load() is PreviewDraftLoad.Missing)
    }

    @Test fun retiredRowsAndValueButtonsCannotReopenOrAlterAReplacementInspection() {
        val h = Harness()
        h.open(); val menu = dialog(); choose(0)
        val fields = dialog(); findField("build.name"); val oldValue = dialog()
        click(AlertDialog.BUTTON_NEGATIVE); val currentFields = dialog()
        oldValue.getButton(AlertDialog.BUTTON_NEGATIVE).performClick()
        shadowOf(fields).clickOnItem(0); shadowOf(menu).clickOnItem(3); idle()
        assertSame(currentFields, dialog())
        h.control(R.string.resource_inspection_cancel).performClick(); idle()
        assertFalse(currentFields.isShowing)
        h.open(); val replacement = dialog()
        oldValue.getButton(AlertDialog.BUTTON_NEGATIVE).performClick()
        currentFields.getButton(AlertDialog.BUTTON_POSITIVE).performClick()
        shadowOf(menu).clickOnItem(0); idle()
        assertSame(replacement, dialog())
        assertEquals(4, rows().size)
    }

    @Test fun pauseRetiresAnActualPendingReadBeforeResumeAndRequiresAnExplicitNewInspection() {
        val entered = CountDownLatch(1); val release = CountDownLatch(1)
        worker().submit { entered.countDown(); release.await(15, TimeUnit.SECONDS) }
        assertTrue(entered.await(15, TimeUnit.SECONDS))
        try {
            button(R.string.resource_inspection_open).performClick()
            assertFalse(button(R.string.resource_inspection_open).isEnabled)
            controller.pause()
            assertFalse(button(R.string.resource_inspection_browse).isEnabled)
            release.countDown(); worker().submit {}.get(15, TimeUnit.SECONDS); idle()
            controller.resume(); idle()
            assertFalse(button(R.string.resource_inspection_browse).isEnabled)
            assertTrue(views().filterIsInstance<TextView>().any { it.text.toString() == activity.getString(R.string.resource_inspection_idle) })
            actualOpen(); assertEquals(4, rows().size)
        } finally { release.countDown() }
    }

    @Test fun recreationKeepsPendingEditorStateButRetiresInspectionAndDetachedControls() {
        save()
        val baseline = loaded().baselineIdentity
        field(R.string.draft_title).setText("pending resource inspection")
        actualOpen(); choose(1); choose(0); findField("value")
        val priorDialog = dialog()
        val priorInspect = button(R.string.resource_inspection_open)
        val priorBrowse = button(R.string.resource_inspection_browse)
        val before = disk()
        recreate()
        assertFalse(priorDialog.isShowing)
        assertEquals("pending resource inspection", field(R.string.draft_title).text.toString())
        assertEquals(baseline, loaded().baselineIdentity)
        assertEquals(before, disk())
        assertFalse(button(R.string.resource_inspection_browse).isEnabled)
        priorInspect.performClick(); priorBrowse.performClick()
        priorDialog.getButton(AlertDialog.BUTTON_NEGATIVE).performClick(); idle()
        assertFalse(priorDialog.isShowing)
        assertFalse(button(R.string.resource_inspection_browse).isEnabled)
        actualOpen(); assertTrue(dialog().isShowing)
        assertEquals(before, disk())
    }

    @Test fun inspectionPreservesTheActualAutosaveDeadlineAndProducesNoAdditionalRevision() {
        save()
        val beforeRecord = loaded()
        val baseline = beforeRecord.baselineIdentity
        val original = disk()
        field(R.string.draft_title).setText("resource inspection pending")
        val started = SystemClock.uptimeMillis()
        actualOpen(); choose(0); assertField("build.id", actualRecord().buildId)
        assertEquals(started, SystemClock.uptimeMillis())
        assertEquals(baseline, loaded().baselineIdentity); assertEquals(original, disk())
        shadowOf(Looper.getMainLooper()).idleFor(749, TimeUnit.MILLISECONDS); finishWork()
        assertEquals(baseline, loaded().baselineIdentity); assertEquals(original, disk())
        shadowOf(Looper.getMainLooper()).idleFor(1, TimeUnit.MILLISECONDS)
        // Observe the actual UI acknowledgement before opening a second library reader;
        // a read during the canonical write correctly reports BUSY and is not an acknowledgement.
        await { !button(R.string.save_draft).isEnabled && views().filterIsInstance<TextView>().any {
            it.text.toString() == activity.getString(R.string.draft_saved)
        } }
        finishWork()
        val saved = loaded()
        assertEquals("resource inspection pending", saved.fields.title)
        assertEquals(beforeRecord.revision + 1, saved.revision)
        val acknowledged = disk()
        click(AlertDialog.BUTTON_NEGATIVE)
        button(R.string.resource_inspection_cancel).performClick(); idle()
        shadowOf(Looper.getMainLooper()).idleFor(1000, TimeUnit.MILLISECONDS); finishWork()
        assertEquals(saved.baselineIdentity, loaded().baselineIdentity)
        assertEquals(acknowledged, disk())
    }

    @Test fun executorRejectionRemainsExplicitAndDoesNotReadOrAdoptAnyInput() {
        val h = Harness()
        val before = disk()
        h.worker.reject = true
        h.start(); idle()
        assertEquals(0, h.openCount)
        assertTrue(h.text().contains(activity.getString(R.string.resource_inspection_unavailable)))
        assertFalse(h.text().contains("private worker"))
        assertFalse(h.control(R.string.resource_inspection_browse).isEnabled)
        assertTrue(h.control(R.string.resource_inspection_open).isEnabled)
        h.worker.reject = false; h.open()
        assertEquals(before, disk())
        assertEquals(h.openCount, h.closedCount)
    }

    @Test
    @Config(qualifiers = "ar-rEG-ldrtl-w320dp-h640dp")
    @GraphicsMode(GraphicsMode.Mode.NATIVE)
    fun narrowRtlLargeTextKeepsTheEntryControlsAndCompletePagedValueLinesReachable() {
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
                assertTrue("Resource target must be reachable: $bounds in ${parent.javaClass.simpleName}",
                    Rect().also(parent::getDrawingRect).contains(bounds))
                child = parent
            }
        }
        layout(root)
        assertEquals(View.LAYOUT_DIRECTION_RTL, root.layoutDirection)
        val inspect = button(R.string.resource_inspection_open)
        assertTrue(inspect.height >= (48 * activity.resources.displayMetrics.density).toInt())
        reachable(root, inspect)
        actualOpen(); choose(1); choose(0); findField("estimator_id")
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
