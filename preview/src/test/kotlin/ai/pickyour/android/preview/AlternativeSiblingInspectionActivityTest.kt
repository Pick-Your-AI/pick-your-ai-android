package ai.pickyour.android.preview

import android.app.AlertDialog
import android.os.Looper
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.CheckBox
import android.widget.EditText
import android.widget.TextView
import ai.pickyour.core.cache.ArtifactHash
import ai.pickyour.core.contracts.ContractDecimal
import ai.pickyour.core.drafts.*
import ai.pickyour.core.preview.PreviewDraftLibrary
import ai.pickyour.core.preview.PreviewDraftLoad
import ai.pickyour.core.preview.PreviewStoredDraft
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.android.controller.ActivityController
import org.robolectric.annotation.Config
import org.robolectric.annotation.LooperMode
import org.robolectric.shadows.ShadowAlertDialog
import org.json.JSONObject
import java.math.BigInteger
import java.nio.file.Files
import java.security.MessageDigest
import java.time.Instant
import java.util.concurrent.CountDownLatch
import java.util.concurrent.ExecutorService
import java.util.concurrent.TimeUnit

/** Actual admitted parent and sibling controls; no installed-device or broader profile claim. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
@LooperMode(LooperMode.Mode.PAUSED)
class AlternativeSiblingInspectionActivityTest {
    private lateinit var controller: ActivityController<PreviewActivity>
    private val activity get() = controller.get()

    @Before fun launch() {
        AutosaveUiClock.pause()
        controller = Robolectric.buildActivity(PreviewActivity::class.java).setup()
        await { button(R.string.save_draft).isEnabled && button(R.string.retry_catalog).isEnabled }
        button(R.string.save_draft).performClick()
        await { !button(R.string.save_draft).isEnabled && text().contains(activity.getString(R.string.draft_saved)) }
        finishWork()
        assertTrue(saved().components.selections.isEmpty())
    }

    @After fun close() { controller.pause().stop().destroy() }

    @Suppress("UNCHECKED_CAST")
    private fun <T> field(name: String): T = PreviewActivity::class.java.getDeclaredField(name)
        .apply { isAccessible = true }.get(activity) as T
    private fun member(owner: Any, name: String): Any? = owner.javaClass.getDeclaredField(name)
        .apply { isAccessible = true }.get(owner)
    private fun worker() = field<ExecutorService>("worker")
    private fun idle() = AutosaveUiClock.idle()
    private fun finishWork() { repeat(4) { idle(); worker().submit {}.get(20, TimeUnit.SECONDS); idle() } }
    private fun await(condition: () -> Boolean) {
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(20)
        while (true) {
            idle()
            if (condition()) return
            val remaining = deadline - System.nanoTime()
            assertTrue("The actual admitted inspection must arrive within its worker bound", remaining > 0)
            worker().submit {}.get(remaining, TimeUnit.NANOSECONDS)
        }
    }
    private fun views(root: View = activity.window.decorView): List<View> = buildList {
        add(root)
        if (root is ViewGroup) repeat(root.childCount) { addAll(views(root.getChildAt(it))) }
    }
    private fun text(root: View = activity.window.decorView) =
        views(root).filterIsInstance<TextView>().joinToString("\n") { it.text.toString() }
    private fun button(label: Int, root: View = activity.window.decorView) =
        views(root).filterIsInstance<Button>().single { it.text.toString() == activity.getString(label) }
    private fun formField(form: AlertDialog, label: Int): EditText {
        val caption = views(form.window!!.decorView).filterIsInstance<TextView>()
            .single { it.text.toString() == activity.getString(label) }
        assertNotEquals(View.NO_ID, caption.labelFor)
        return form.findViewById(caption.labelFor)
    }
    private fun latest() = requireNotNull(ShadowAlertDialog.getLatestAlertDialog())
    private fun dialog() = latest().also { assertTrue("Current inspection must be showing", it.isShowing) }
    private fun choose(window: AlertDialog = dialog(), matches: (String) -> Boolean): AlertDialog {
        val row = (0 until window.listView.adapter.count).first { matches(window.listView.adapter.getItem(it).toString()) }
        shadowOf(window).clickOnItem(row); idle(); return dialog()
    }
    private fun disk() = listOf(activity.noBackupFilesDir, activity.filesDir).flatMap { directory ->
        Files.walk(directory.toPath()).use { paths ->
            paths.filter(Files::isRegularFile).toList().map {
                directory.name + "/" + directory.toPath().relativize(it).toString() to
                    MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(it)).toList()
            }
        }
    }.toMap()
    private fun saved(): PreviewStoredDraft {
        finishWork()
        val result = PreviewDraftLibrary(activity.noBackupFilesDir.toPath()).openLegacy().load()
        assertTrue("An acknowledged source must remain readable", result is PreviewDraftLoad.Available)
        return (result as PreviewDraftLoad.Available).record
    }

    private class Parent(val form: AlertDialog, val picker: AlertDialog, val session: Any,
        val request: Any, val result: Any, val inspect: Button, val choose: Button, val status: String)

    private fun parent(): Parent {
        button(R.string.component_choose_catalog).performClick()
        val actions = choose { it.startsWith(componentTokenForLookup(activity, "workload") + " ·") }
        val form = choose(actions) { it == activity.getString(R.string.component_add) }
        formField(form, R.string.component_role).setText("primary")
        formField(form, R.string.component_settings).setText("{}")
        views(form.window!!.decorView).filterIsInstance<CheckBox>().single().isChecked = true
        form.findViewById<Button>(R.id.options_form_compare).performClick()
        val picker = dialog()
        assertNotSame(form, picker)
        await { picker.findViewById<TextView>(R.id.options_status).text.toString() != activity.getString(R.string.options_evaluating) }
        val rows = views(picker.findViewById(R.id.options_rows)).filterIsInstance<Button>()
        val owner = field<CompatibleOptionsPicker>("compatibleOptions")
        val session = requireNotNull(member(owner, "session"))
        return Parent(form, picker, session, requireNotNull(member(session, "request")),
            requireNotNull(member(session, "result")), rows.first { it.id == R.id.alternative_inspect },
            rows.first { it.text.toString().startsWith("Use ") }, picker.findViewById<TextView>(R.id.options_status).text.toString())
    }

    private fun parentUnchanged(parent: Parent) {
        assertTrue(parent.form.isShowing); assertTrue(parent.picker.isShowing)
        assertSame(parent.session, member(field<CompatibleOptionsPicker>("compatibleOptions"), "session"))
        assertSame(parent.request, member(parent.session, "request"))
        assertSame(parent.result, member(parent.session, "result"))
        assertEquals(parent.status, parent.picker.findViewById<TextView>(R.id.options_status).text.toString())
        assertEquals("primary", formField(parent.form, R.string.component_role).text.toString())
        assertEquals("{}", formField(parent.form, R.string.component_settings).text.toString())
        assertTrue(views(parent.form.window!!.decorView).filterIsInstance<CheckBox>().single().isChecked)
        assertTrue(parent.inspect.isEnabled); assertTrue(parent.choose.isEnabled)
    }

    private fun preserve(parent: Parent, action: () -> Unit) {
        finishWork()
        val record = field<PreviewStoredDraft>("savedRecord")
        val bytes = disk()
        action(); finishWork()
        parentUnchanged(parent)
        assertSame(record, field<PreviewStoredDraft>("savedRecord"))
        assertEquals(record.baselineIdentity, saved().baselineIdentity)
        assertEquals(bytes, disk())
        assertNull(shadowOf(activity).nextStartedActivity)
    }

    private fun openAlternative(parent: Parent): AlertDialog {
        parent.inspect.performClick()
        await { latest().findViewById<View>(R.id.alternative_rules) != null }
        return dialog()
    }
    private class Captured(val window: AlertDialog, val replay: () -> Unit)
    private fun value(parent: Parent): Captured {
        openAlternative(parent).findViewById<Button>(R.id.alternative_records).performClick(); idle()
        choose { it == "candidate_selection" }
        val page = dialog()
        val back = page.getButton(AlertDialog.BUTTON_NEGATIVE)
        val next = page.getButton(AlertDialog.BUTTON_POSITIVE)
        assertEquals(activity.getString(R.string.alternative_back), back.text.toString())
        assertEquals(View.VISIBLE, next.visibility)
        assertTrue(next.isEnabled)
        return Captured(page) { back.callOnClick(); next.callOnClick(); idle() }
    }
    private fun retiredAcrossClose(old: Captured, current: AlertDialog) {
        assertFalse("A new sibling must retire the previous inspection child", old.window.isShowing)
        old.replay()
        assertSame(current, dialog())
        current.dismiss(); idle()
        old.replay()
        assertSame(current, latest()); assertFalse(current.isShowing)
    }
    private fun help(): AlertDialog {
        button(R.string.help_open).performClick(); idle()
        return dialog().also { assertNotNull(it.findViewById<Button>(R.id.help_bug)) }
    }
    private fun helpDestination(): AlertDialog {
        help().findViewById<Button>(R.id.help_bug).performClick(); idle()
        return dialog().also { assertNotNull(it.findViewById<Button>(R.id.help_external_open)) }
    }
    private fun diagnostics(): AlertDialog {
        button(R.string.diagnostics_review).performClick(); idle()
        return dialog().also { assertNotNull(it.findViewById<Button>(R.id.diagnostic_share)) }
    }
    private fun prepareResource() {
        button(R.string.resource_inspection_open).performClick()
        await { button(R.string.resource_inspection_browse).isEnabled }
    }
    private fun resource(): AlertDialog {
        if (!button(R.string.resource_inspection_browse).isEnabled) prepareResource()
        button(R.string.resource_inspection_browse).performClick(); idle()
        return dialog().also { assertEquals(4, it.listView.adapter.count) }
    }
    private class Block(val entered: CountDownLatch = CountDownLatch(1), val release: CountDownLatch = CountDownLatch(1))
    private fun enqueueBlock(): Block = Block().also { block ->
        worker().execute { block.entered.countDown(); check(block.release.await(20, TimeUnit.SECONDS)) }
    }

    /** Explicit immutable fixture inputs, admitted and archived through the real private boundary. */
    private fun saveSnapshot() {
        fun document(name: String) = activity.assets.open("$name.json").bufferedReader(Charsets.UTF_8)
            .use { JSONObject(it.readText()) }
        fun artifact(name: String): DraftProjectionArtifact {
            val value = document(name)
            val bytes = value.toString().toByteArray(Charsets.UTF_8)
            val hash = MessageDigest.getInstance("SHA-256").digest(bytes)
                .joinToString("") { "%02x".format(it) }
            return DraftProjectionArtifact(value.getString("id"),
                value.get(if (name == "ruleset") "version" else "revision").toString(), ArtifactHash(hash), bytes)
        }
        val build = document("build")
        val objective = build.getJSONObject("objectives")
        fun integer(name: String) = if (objective.has(name)) BigInteger(objective.get(name).toString()) else null
        val workload = DraftWorkload(objective.getString("region"), objective.getString("currency"),
            ContractDecimal.parse(objective.getString("monthly_budget_amount")), integer("requests_per_day"), integer("avg_input_tokens"),
            integer("avg_output_tokens"), integer("max_concurrency"), integer("max_latency_ms"), DraftDataClassification.INTERNAL)
        val selections = build.getJSONArray("selections")
        val links = build.getJSONArray("links")
        val content = DraftContent("private alternative sibling fixture", "", "cat.model.fixture",
            OptionalTier.entries.associateWith { TierDraft(Activation.ENABLED) }, workload,
            DraftSelections((0 until selections.length()).map { LocalSelection.fromJson(selections.getJSONObject(it).toString().toByteArray(Charsets.UTF_8)) },
                (0 until links.length()).map { LocalSelectionLink.fromJson(links.getJSONObject(it).toString().toByteArray(Charsets.UTF_8)) }))
        val source = LocalDraftStore(Files.createTempDirectory(activity.filesDir.toPath(), "alternative-sibling-source-"))
        val stored = (source.save(null, content) as DraftSave.Saved).draft
        val identity = DraftSnapshotIdentity("build.alternative.sibling", BigInteger.ONE, "Explicit alternative sibling snapshot", "explicit.test.owner",
            Instant.parse("2026-10-17T10:00:00Z"), Instant.parse("2026-10-18T10:00:00Z"))
        val inputs = DraftSnapshotInputs(1, identity, artifact("catalog"), artifact("ruleset"), artifact("dependency-graph"), artifact("pricebook"))
        val review = source.prepareSnapshotCandidate(stored.version, inputs) as DraftProjectionPreparation.Review
        val archive = LocalSnapshotArchive(activity.filesDir.toPath().resolve("retained-snapshots").also(Files::createDirectories))
        val version = (archive.load() as SnapshotArchiveLoad.Available).state.version
        assertTrue(source.archiveSnapshotCandidate(review, review.acknowledgeRetainedOnly(review.retainedOnlyPaths.toSet()), archive, version)
            is DraftSnapshotArchival.Archived)
        assertEquals(1, (archive.load() as SnapshotArchiveLoad.Available).state.entries.size)
    }

    @Test fun helpEntryRetiresAlternativeValueBackAndNextWithoutRevokingParentChoice() {
        val parent = parent()
        preserve(parent) {
            retiredAcrossClose(value(parent), helpDestination())
            assertNotNull(openAlternative(parent).findViewById<Button>(R.id.alternative_records))
            dialog().dismiss()
        }
    }

    @Test fun privacyEntryRetiresAlternativeRuleRowsAndPagesWithoutPrivateMutation() {
        val parent = parent()
        preserve(parent) {
            openAlternative(parent).findViewById<Button>(R.id.alternative_rules).performClick(); idle()
            val rules = dialog()
            assertEquals(8, rules.listView.adapter.count)
            val next = rules.getButton(AlertDialog.BUTTON_POSITIVE)
            val back = rules.getButton(AlertDialog.BUTTON_NEGATIVE)
            assertEquals(View.VISIBLE, next.visibility)
            activity.findViewById<Button>(R.id.privacy_open).performClick(); idle()
            val privacy = dialog()
            assertNotNull(privacy.findViewById<View>(R.id.privacy_scroll))
            retiredAcrossClose(Captured(rules) { shadowOf(rules).clickOnItem(0); next.callOnClick(); back.callOnClick(); idle() }, privacy)
            openAlternative(parent).dismiss()
        }
    }

    @Test fun resourceEntryRetiresQueuedAlternativeCompletionAndKeepsItsAdmittedParent() {
        prepareResource()
        val parent = parent()
        preserve(parent) {
            val block = enqueueBlock()
            assertTrue(block.entered.await(5, TimeUnit.SECONDS))
            try {
                parent.inspect.performClick()
                val loading = dialog()
                assertEquals(activity.getString(R.string.alternative_loading), loading.findViewById<TextView>(android.R.id.message).text.toString())
                val sibling = resource()
                block.release.countDown(); finishWork()
                assertSame("Queued alternative completion must not replace a newer sibling", sibling, dialog())
                assertFalse(loading.isShowing)
                loading.getButton(AlertDialog.BUTTON_NEGATIVE).callOnClick(); idle()
                assertSame(sibling, dialog())
                sibling.dismiss(); idle()
                parentUnchanged(parent)
                openAlternative(parent).dismiss()
            } finally { block.release.countDown() }
        }
    }

    @Test fun alternativeEntryRetiresResourceRowsAndBackWithoutDiscardingItsInlineRecord() {
        val parent = parent()
        preserve(parent) {
            val owner = field<ResourceInspectionSection>("resourceInspection")
            for (detail in listOf(false, true)) {
                val menu = resource()
                if (detail) { shadowOf(menu).clickOnItem(0); idle() }
                val previous = dialog()
                val back = previous.getButton(AlertDialog.BUTTON_NEGATIVE)
                val record = member(owner, "record")
                val output = (member(owner, "output") as TextView).text.toString()
                assertNotNull(record)
                retiredAcrossClose(Captured(previous) { shadowOf(menu).clickOnItem(1); back.callOnClick(); idle() }, openAlternative(parent))
                assertSame(record, member(owner, "record"))
                assertEquals(output, (member(owner, "output") as TextView).text.toString())
            }
        }
    }

    @Test fun alternativeEntryRetiresHelpAndDiagnosticsExternalActionsBeforeAndAfterClose() {
        val parent = parent()
        preserve(parent) {
            for (diagnostic in listOf(false, true)) {
                val previous = if (diagnostic) diagnostics() else helpDestination()
                val external = previous.findViewById<Button>(if (diagnostic) R.id.diagnostic_share else R.id.help_external_open)
                assertTrue(external.isEnabled)
                retiredAcrossClose(Captured(previous) { external.callOnClick(); idle() }, openAlternative(parent))
                assertNull(shadowOf(activity).nextStartedActivity)
                val fresh = if (diagnostic) diagnostics() else helpDestination()
                external.callOnClick(); idle()
                assertSame(fresh, dialog()); assertNull(shadowOf(activity).nextStartedActivity)
                fresh.dismiss(); idle()
            }
        }
    }

    @Test fun alternativeEntryRetiresFixedFindingSelectionControlsAndKeepsTheAcceptedReport() {
        val parent = parent()
        preserve(parent) {
            button(R.string.findings_inspect).performClick()
            await { button(R.string.findings_browse).isEnabled }
            button(R.string.findings_browse).performClick(); idle()
            val picker = dialog()
            shadowOf(picker).clickOnItem(0); idle()
            dialog().getButton(AlertDialog.BUTTON_NEUTRAL).performClick(); idle()
            val context = dialog()
            val row = views(context.window!!.decorView).filterIsInstance<Button>().first {
                it.text.toString().startsWith("Inspect selection ") }
            assertTrue(row.isEnabled)
            val owner = field<FindingsInspectionSection>("findings")
            val report = member(owner, "accepted")
            assertNotNull(report)
            retiredAcrossClose(Captured(context) { shadowOf(picker).clickOnItem(0); row.callOnClick(); idle() }, openAlternative(parent))
            assertSame(report, member(owner, "accepted"))
            assertTrue(button(R.string.findings_browse).isEnabled)
        }
    }

    @Test fun alternativeEntryCancelsLateSnapshotInventoryBeforeItsWorkerCompletes() {
        saveSnapshot()
        val parent = parent()
        preserve(parent) {
            val first = enqueueBlock()
            assertTrue(first.entered.await(5, TimeUnit.SECONDS))
            var second: Block? = null
            try {
                activity.findViewById<Button>(R.id.snapshot_open).performClick()
                assertEquals(activity.getString(R.string.snapshot_inventory_loading),
                    activity.findViewById<TextView>(R.id.snapshot_status).text.toString())
                val between = enqueueBlock().also { second = it }
                parent.inspect.performClick()
                val loading = dialog()
                assertEquals(activity.getString(R.string.alternative_loading), loading.findViewById<TextView>(android.R.id.message).text.toString())
                first.release.countDown()
                assertTrue(between.entered.await(10, TimeUnit.SECONDS))
                idle()
                assertSame("A previously queued inventory must not replace the admitted alternative loading window", loading, dialog())
                assertEquals(activity.getString(R.string.snapshot_notice),
                    activity.findViewById<TextView>(R.id.snapshot_status).text.toString())
                between.release.countDown(); finishWork()
                assertNotNull(dialog().findViewById<Button>(R.id.alternative_rules))
                dialog().dismiss()
            } finally { first.release.countDown(); second?.release?.countDown() }
        }
    }

    @Test fun retiringAlternativeChildrenPreservesFreshInspectionAndTheSeparateOneUseChoose() {
        val parent = parent()
        val before = saved()
        val bytes = disk()
        retiredAcrossClose(value(parent), help())
        parentUnchanged(parent)
        assertEquals(before.baselineIdentity, saved().baselineIdentity)
        assertEquals(bytes, disk())
        openAlternative(parent).dismiss(); idle()
        parentUnchanged(parent)
        parent.choose.performClick(); idle()
        val confirmation = dialog()
        assertEquals(activity.getString(R.string.options_confirm), shadowOf(confirmation).title.toString())
        val apply = confirmation.getButton(AlertDialog.BUTTON_POSITIVE)
        assertTrue(apply.isEnabled)
        apply.performClick()
        await { !parent.form.isShowing }
        val after = saved()
        assertNotEquals(before.baselineIdentity, after.baselineIdentity)
        val selected = after.components.selections.single()
        assertEquals("workload", selected.kind); assertEquals("primary", selected.role); assertTrue(selected.enabled)
        assertEquals(before.fields, after.fields)
        val finalBytes = disk()
        apply.callOnClick(); parent.choose.callOnClick(); parent.inspect.callOnClick(); idle(); finishWork()
        assertEquals(after.baselineIdentity, saved().baselineIdentity)
        assertEquals(finalBytes, disk())
        assertFalse(parent.picker.isShowing)
        assertNull(shadowOf(activity).nextStartedActivity)
    }
}
