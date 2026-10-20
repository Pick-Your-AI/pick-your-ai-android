package ai.pickyour.android.preview

import android.app.AlertDialog
import android.content.Intent
import android.os.Looper
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.EditText
import android.widget.TextView
import ai.pickyour.core.cache.ArtifactHash
import ai.pickyour.core.contracts.ContractDecimal
import ai.pickyour.core.drafts.*
import ai.pickyour.core.preview.PreviewCurrentDraftAnalysis
import ai.pickyour.core.preview.PreviewCurrentDraftState
import ai.pickyour.core.preview.PreviewStoredDraft
import org.json.JSONObject
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
import java.math.BigInteger
import java.nio.file.Files
import java.nio.file.Path
import java.security.MessageDigest
import java.time.Instant
import java.util.concurrent.CountDownLatch
import java.util.concurrent.ExecutorService
import java.util.concurrent.TimeUnit

/** Real Activity controls, saved draft and retained archive in simulation; no installed-device claim. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
@LooperMode(LooperMode.Mode.PAUSED)
class SiblingInspectionActivityTest {
    private lateinit var controller: ActivityController<PreviewActivity>
    private val activity get() = controller.get()
    private val archivePath get() = activity.filesDir.toPath().resolve("retained-snapshots")

    @Before fun launch() {
        controller = Robolectric.buildActivity(PreviewActivity::class.java).setup()
        await { button(R.string.save_draft).isEnabled && button(R.string.retry_catalog).isEnabled }
        button(R.string.save_draft).performClick()
        await { state() is PreviewCurrentDraftState.Current }
        finishWork()
    }

    @After fun close() { controller.pause().stop().destroy() }

    @Suppress("UNCHECKED_CAST")
    private fun <T> field(name: String): T = PreviewActivity::class.java.getDeclaredField(name)
        .apply { isAccessible = true }.get(activity) as T
    private fun worker() = field<ExecutorService>("worker")
    private fun state(): PreviewCurrentDraftState {
        val section = field<CurrentDraftCheckSection>("currentChecks")
        return (CurrentDraftCheckSection::class.java.getDeclaredField("analysis")
            .apply { isAccessible = true }.get(section) as PreviewCurrentDraftAnalysis).state
    }
    private fun idle() = shadowOf(Looper.getMainLooper()).idle()
    private fun finishWork() { idle(); worker().submit {}.get(15, TimeUnit.SECONDS); idle() }
    private fun await(condition: () -> Boolean) {
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(20)
        while (true) {
            idle()
            if (condition()) return
            val remaining = deadline - System.nanoTime()
            assertTrue("The explicit Activity state must arrive within the owned-work bound", remaining > 0)
            worker().submit {}.get(remaining, TimeUnit.NANOSECONDS)
        }
    }
    private fun views(root: View = activity.window.decorView): List<View> = buildList {
        add(root)
        if (root is ViewGroup) repeat(root.childCount) { addAll(views(root.getChildAt(it))) }
    }
    private fun button(label: Int, root: View = activity.window.decorView): Button =
        button(activity.getString(label), root)
    private fun button(text: String, root: View) = views(root).filterIsInstance<Button>()
        .single { it.text.toString() == text }
    private fun dialog() = requireNotNull(ShadowAlertDialog.getLatestAlertDialog())
        .also { assertTrue("The current inspection must be showing", it.isShowing) }
    private fun choose(window: AlertDialog, index: Int = 0) {
        shadowOf(window).clickOnItem(index); idle()
    }
    private fun openCurrent(): AlertDialog {
        val inspect = activity.findViewById<Button>(R.id.current_draft_check_inspect)
        assertTrue(inspect.isEnabled)
        inspect.performClick(); idle()
        return dialog().also { assertTrue(it.listView.adapter.count in 1..10) }
    }
    private fun openSnapshot(): AlertDialog {
        val before = ShadowAlertDialog.getLatestAlertDialog()
        activity.findViewById<Button>(R.id.snapshot_open).performClick()
        await { ShadowAlertDialog.getLatestAlertDialog()?.let { it !== before && it.isShowing } == true }
        return dialog().also { assertEquals(1, it.listView.adapter.count) }
    }
    private fun snapshotReport(inventory: AlertDialog = openSnapshot()): AlertDialog {
        choose(inventory)
        val report = dialog()
        await { report.findViewById<TextView>(R.id.snapshot_report).text.contains("Result in this profile:") }
        assertTrue(report.findViewById<TextView>(R.id.snapshot_report).text.contains("build.sibling.inspection"))
        return report
    }
    private fun openResource(): AlertDialog {
        if (!button(R.string.resource_inspection_browse).isEnabled) {
            button(R.string.resource_inspection_open).performClick()
            await { button(R.string.resource_inspection_browse).isEnabled }
        }
        button(R.string.resource_inspection_browse).performClick(); idle()
        return dialog().also { assertEquals(4, it.listView.adapter.count) }
    }
    private fun resourceValue(): AlertDialog {
        choose(openResource()); choose(dialog())
        return dialog().also { assertEquals("resource_estimate.schema_version", shadowOf(it).title.toString()) }
    }
    private fun openFixedFindings(): AlertDialog {
        if (!button(R.string.findings_browse).isEnabled) {
            button(R.string.findings_inspect).performClick()
            await { button(R.string.findings_browse).isEnabled }
        }
        button(R.string.findings_browse).performClick(); idle()
        return dialog().also { assertTrue(it.listView.adapter.count > 0) }
    }
    private fun fixedContext(): AlertDialog {
        choose(openFixedFindings())
        dialog().getButton(AlertDialog.BUTTON_NEUTRAL).performClick(); idle()
        return dialog().also {
            assertTrue(button(activity.getString(R.string.findings_context_inspect, 1), it.window!!.decorView).isEnabled)
        }
    }
    private fun openHelp(): AlertDialog {
        button(R.string.help_open).performClick(); idle()
        return dialog().also { assertNotNull(it.findViewById<Button>(R.id.help_bug)) }
    }
    private fun helpDestination(destination: HelpDestination = HelpDestination.BUG): AlertDialog {
        openHelp().findViewById<Button>(destination.control).performClick(); idle()
        return dialog().also {
            assertEquals(destination.url, it.findViewById<TextView>(R.id.help_destination).text.toString())
        }
    }
    private fun openDiagnostics(): AlertDialog {
        button(R.string.diagnostics_review).performClick(); idle()
        return dialog().also { assertNotNull(it.findViewById<TextView>(R.id.diagnostic_report)) }
    }
    private fun unchangedWindow(expected: AlertDialog) {
        idle(); assertSame(expected, dialog()); assertTrue(expected.isShowing)
    }

    /** Exercise the old page row and the old detail Back separately through actual native controls. */
    private fun replaceCurrent(open: () -> AlertDialog): AlertDialog {
        val page = openCurrent()
        val replacement = open()
        assertFalse("A newer sibling must retire the current-findings page", page.isShowing)
        choose(page); unchangedWindow(replacement)
        val nextPage = openCurrent()
        choose(nextPage)
        val detail = dialog()
        val back = detail.getButton(AlertDialog.BUTTON_POSITIVE)
        assertEquals(activity.getString(R.string.current_check_back), back.text.toString())
        val next = open()
        assertFalse("A newer sibling must retire the current-findings detail", detail.isShowing)
        back.performClick(); unchangedWindow(next)
        return next
    }
    private fun digest(bytes: ByteArray) = MessageDigest.getInstance("SHA-256").digest(bytes)
        .joinToString("") { "%02x".format(it) }
    private fun files(root: Path): Map<String, String> = if (!Files.exists(root)) emptyMap() else Files.walk(root).use { paths ->
        paths.filter(Files::isRegularFile).toList().associate { root.relativize(it).toString() to digest(Files.readAllBytes(it)) }
    }
    private fun preservingSavedState(inspect: () -> Unit) {
        finishWork()
        val original = state() as PreviewCurrentDraftState.Current
        val saved = field<PreviewStoredDraft>("savedRecord")
        val draftBytes = files(activity.noBackupFilesDir.toPath())
        val archiveBytes = files(archivePath)
        val status = activity.findViewById<TextView>(R.id.current_draft_check_status).text.toString()
        val affected = activity.findViewById<TextView>(R.id.current_draft_check_affected).text.toString()
        inspect(); finishWork()
        assertSame(original, state())
        assertSame(original.request.binding, (state() as PreviewCurrentDraftState.Current).request.binding)
        assertSame(saved, field<PreviewStoredDraft>("savedRecord"))
        assertEquals(status, activity.findViewById<TextView>(R.id.current_draft_check_status).text.toString())
        assertEquals(affected, activity.findViewById<TextView>(R.id.current_draft_check_affected).text.toString())
        assertEquals(draftBytes, files(activity.noBackupFilesDir.toPath()))
        assertEquals(archiveBytes, files(archivePath))
        assertNull(shadowOf(activity).nextStartedActivity)
    }
    private fun document(name: String) = activity.assets.open("$name.json").bufferedReader(Charsets.UTF_8)
        .use { JSONObject(it.readText()) }
    private fun artifact(name: String): DraftProjectionArtifact {
        val document = document(name)
        val bytes = document.toString().toByteArray(Charsets.UTF_8)
        return DraftProjectionArtifact(document.getString("id"), document.get(if (name == "ruleset") "version" else "revision").toString(),
            ArtifactHash(digest(bytes)), bytes)
    }
    /** Explicit synthetic caller inputs archived through the real candidate/acknowledgement boundary. */
    private fun saveSnapshot() {
        val build = document("build")
        val objective = build.getJSONObject("objectives")
        fun integer(name: String) = if (objective.has(name)) BigInteger(objective.get(name).toString()) else null
        val workload = DraftWorkload(objective.getString("region"), objective.getString("currency"),
            ContractDecimal.parse(objective.getString("monthly_budget_amount")), integer("requests_per_day"), integer("avg_input_tokens"),
            integer("avg_output_tokens"), integer("max_concurrency"), integer("max_latency_ms"), DraftDataClassification.INTERNAL)
        val selections = build.getJSONArray("selections")
        val links = build.getJSONArray("links")
        val content = DraftContent("private sibling fixture", "", "cat.model.fixture",
            OptionalTier.entries.associateWith { TierDraft(Activation.ENABLED) }, workload,
            DraftSelections((0 until selections.length()).map { LocalSelection.fromJson(selections.getJSONObject(it).toString().toByteArray(Charsets.UTF_8)) },
                (0 until links.length()).map { LocalSelectionLink.fromJson(links.getJSONObject(it).toString().toByteArray(Charsets.UTF_8)) }))
        val source = LocalDraftStore(Files.createTempDirectory(activity.filesDir.toPath(), "sibling-snapshot-source-"))
        val stored = (source.save(null, content) as DraftSave.Saved).draft
        val identity = DraftSnapshotIdentity("build.sibling.inspection", BigInteger.ONE, "Explicit sibling snapshot", "explicit.test.owner",
            Instant.parse("2026-10-17T10:00:00Z"), Instant.parse("2026-10-18T10:00:00Z"))
        val inputs = DraftSnapshotInputs(1, identity, artifact("catalog"), artifact("ruleset"), artifact("dependency-graph"), artifact("pricebook"))
        val review = source.prepareSnapshotCandidate(stored.version, inputs) as DraftProjectionPreparation.Review
        val archive = LocalSnapshotArchive(archivePath.also(Files::createDirectories))
        val version = (archive.load() as SnapshotArchiveLoad.Available).state.version
        assertTrue(source.archiveSnapshotCandidate(review, review.acknowledgeRetainedOnly(review.retainedOnlyPaths.toSet()), archive, version)
            is DraftSnapshotArchival.Archived)
    }

    @Test fun snapshotEntryRetiresCurrentRowsAndBackWithoutChangingSavedAnalysis() {
        saveSnapshot()
        preservingSavedState { snapshotReport(replaceCurrent(::openSnapshot)) }
    }

    @Test fun currentFindingsRetireSnapshotRowsAndReportRetryWhileFreshInspectionWorks() {
        saveSnapshot()
        preservingSavedState {
            val inventory = openSnapshot()
            val current = openCurrent()
            assertFalse("Current findings must retire the saved-snapshot inventory", inventory.isShowing)
            choose(inventory); unchangedWindow(current)
            val report = snapshotReport()
            val retry = report.getButton(AlertDialog.BUTTON_NEUTRAL)
            assertTrue(retry.isEnabled)
            val next = openCurrent()
            assertFalse("Current findings must retire the retained report", report.isShowing)
            retry.performClick(); finishWork(); unchangedWindow(next)
            snapshotReport()
        }
    }

    @Test fun lateSnapshotInventoryCannotReopenOverNewCurrentFindings() {
        saveSnapshot()
        preservingSavedState {
            val entered = CountDownLatch(1)
            val release = CountDownLatch(1)
            worker().execute { entered.countDown(); check(release.await(15, TimeUnit.SECONDS)) }
            assertTrue(entered.await(5, TimeUnit.SECONDS))
            try {
                activity.findViewById<Button>(R.id.snapshot_open).performClick()
                assertEquals(activity.getString(R.string.snapshot_inventory_loading),
                    activity.findViewById<TextView>(R.id.snapshot_status).text.toString())
                val current = openCurrent()
                release.countDown(); finishWork()
                unchangedWindow(current)
                assertEquals(activity.getString(R.string.snapshot_notice),
                    activity.findViewById<TextView>(R.id.snapshot_status).text.toString())
                snapshotReport()
            } finally { release.countDown() }
        }
    }

    @Test fun resourceInspectionRetiresSiblingControlsButPreservesAcceptedInlineRecord() {
        preservingSavedState {
            replaceCurrent(::openResource)
            val expected = activity.getString(R.string.resource_inspection_ready,
                document("resource-estimate").getJSONArray("estimates").length(), document("build").getJSONArray("selections").length())
            val inline = views().filterIsInstance<TextView>().single { it.text.toString() == expected }
            val value = resourceValue()
            val back = value.getButton(AlertDialog.BUTTON_NEGATIVE)
            assertEquals(activity.getString(R.string.resource_browser_back), back.text.toString())
            val current = openCurrent()
            assertFalse(value.isShowing)
            back.performClick(); unchangedWindow(current)
            assertTrue(button(R.string.resource_inspection_browse).isEnabled)
            assertEquals(expected, inline.text.toString())
            val fresh = resourceValue()
            fresh.getButton(AlertDialog.BUTTON_NEGATIVE).performClick(); idle()
            assertNotSame(fresh, dialog())
            assertTrue(dialog().listView.adapter.count > 0)
        }
    }

    @Test fun fixedFindingsRetireSiblingControlsButPreserveAcceptedReportAndChildNavigation() {
        preservingSavedState {
            val picker = replaceCurrent(::openFixedFindings)
            val inline = views().filterIsInstance<TextView>().single {
                it.text.startsWith(activity.getString(R.string.findings_notice)) && it.text.contains("\n")
            }
            val acceptedText = inline.text.toString()
            choose(picker)
            val finding = dialog()
            val context = finding.getButton(AlertDialog.BUTTON_NEUTRAL)
            val current = openCurrent()
            assertFalse(finding.isShowing)
            context.performClick(); unchangedWindow(current)
            assertTrue(button(R.string.findings_browse).isEnabled)
            assertEquals(acceptedText, inline.text.toString())
            val selections = fixedContext()
            button(activity.getString(R.string.findings_context_inspect, 1), selections.window!!.decorView).performClick(); idle()
            val detail = dialog()
            val back = button(R.string.findings_context_back, detail.window!!.decorView)
            back.performClick(); idle()
            assertNotSame(detail, dialog())
            val fresh = dialog()
            button(activity.getString(R.string.findings_context_inspect, 1), fresh.window!!.decorView).performClick(); idle()
            val oldDetail = dialog()
            val oldBack = button(R.string.findings_context_back, oldDetail.window!!.decorView)
            val replacement = openCurrent()
            assertFalse(oldDetail.isShowing)
            oldBack.performClick(); unchangedWindow(replacement)
            assertEquals(acceptedText, inline.text.toString())
            fixedContext()
        }
    }

    @Test fun helpDestinationsRetireSiblingControlsAndOldExternalActionsStayRevoked() {
        preservingSavedState {
            replaceCurrent(::openHelp)
            for (destination in HelpDestination.entries) {
                val confirmation = helpDestination(destination)
                val oldOpen = confirmation.findViewById<Button>(R.id.help_external_open)
                val current = openCurrent()
                assertFalse(confirmation.isShowing)
                oldOpen.performClick(); unchangedWindow(current)
                assertNull(shadowOf(activity).nextStartedActivity)
            }
            helpDestination().findViewById<Button>(R.id.help_external_open).performClick(); idle()
            val intent = requireNotNull(shadowOf(activity).nextStartedActivity)
            assertEquals(Intent.ACTION_VIEW, intent.action)
            assertEquals(HelpDestination.BUG.url, intent.dataString)
            assertEquals(setOf(Intent.CATEGORY_BROWSABLE), intent.categories)
            assertNull(intent.extras)
        }
    }

    @Test fun diagnosticsRetireSiblingControlsAndOnlyFreshReviewedTextCanReachTheChooser() {
        preservingSavedState {
            val reviewed = replaceCurrent(::openDiagnostics)
            val oldShare = reviewed.findViewById<Button>(R.id.diagnostic_share)
            val current = openCurrent()
            assertFalse(reviewed.isShowing)
            oldShare.performClick(); unchangedWindow(current)
            assertNull(shadowOf(activity).nextStartedActivity)
            openHelp().findViewById<Button>(R.id.help_diagnostics).performClick(); idle()
            val fresh = dialog()
            val text = fresh.findViewById<TextView>(R.id.diagnostic_report).text.toString()
            assertEquals(LocalDiagnosticReport(DiagnosticStorageState.READY, DiagnosticCatalogState.SYNTHETIC_LOADED).text, text)
            val share = fresh.findViewById<Button>(R.id.diagnostic_share)
            share.performClick(); idle()
            val chooser = requireNotNull(shadowOf(activity).nextStartedActivity)
            assertEquals(Intent.ACTION_CHOOSER, chooser.action)
            assertEquals(setOf(Intent.EXTRA_INTENT, Intent.EXTRA_TITLE), chooser.extras!!.keySet())
            val send = requireNotNull(chooser.getParcelableExtra<Intent>(Intent.EXTRA_INTENT))
            assertEquals(Intent.ACTION_SEND, send.action)
            assertEquals("text/plain", send.type)
            assertEquals(setOf(Intent.EXTRA_TEXT), send.extras!!.keySet())
            assertEquals(text, send.getStringExtra(Intent.EXTRA_TEXT))
            for (intent in listOf(chooser, send)) {
                assertNull(intent.data)
                // Android can migrate EXTRA_TEXT into ClipData while dispatching the chooser.
                intent.clipData?.let { clip ->
                    assertEquals(1, clip.itemCount)
                    assertNull(clip.description.label)
                    assertEquals(1, clip.description.mimeTypeCount)
                    assertEquals("text/plain", clip.description.getMimeType(0))
                    val item = clip.getItemAt(0)
                    assertEquals(text, item.text.toString())
                    assertNull(item.uri)
                    assertNull(item.intent)
                    assertNull(item.htmlText)
                }
                assertNull(intent.component)
                assertNull(intent.`package`)
                assertEquals(0, intent.flags and Intent.FLAG_GRANT_READ_URI_PERMISSION.inv())
            }
            share.performClick(); idle()
        }
    }

    @Test fun explicitCatalogComparisonRetiresSiblingActionsButKeepsTheAcceptedHistoryRoute() {
        preservingSavedState {
            for (kind in listOf("model", "compute_profile")) {
                field<EditText>("catalogSearch").setText("cat.$kind.fixture")
                fun samples() = views(field<CatalogBrowseSection>("catalog")).filterIsInstance<Button>()
                    .filter { it.text.toString().contains("FICTIONAL") }
                await { samples().size == 1 }
                samples().single().performClick(); idle()
                val detail = dialog()
                button(R.string.catalog_compare_add, detail.window!!.decorView).performClick(); idle()
                assertFalse(detail.isShowing)
            }
            val compare = button(R.string.catalog_compare_open)
            assertTrue(compare.isEnabled)
            val help = helpDestination()
            val oldOpen = help.findViewById<Button>(R.id.help_external_open)
            compare.performClick(); idle()
            val comparison = dialog()
            assertFalse(help.isShowing)
            oldOpen.performClick(); unchangedWindow(comparison)
            val diagnostics = openDiagnostics()
            val oldShare = diagnostics.findViewById<Button>(R.id.diagnostic_share)
            compare.performClick(); idle()
            val next = dialog()
            assertFalse(diagnostics.isShowing)
            oldShare.performClick(); unchangedWindow(next)
            button(R.string.draft_history).performClick(); idle()
            val history = dialog()
            assertTrue(history.listView.adapter.count > 0)
            compare.performClick(); idle()
            val historicalComparison = dialog()
            assertTrue("An accepted History route remains live across catalog comparison", history.isShowing)
            choose(history)
            assertFalse("The live History child retires the newer comparison", historicalComparison.isShowing)
            assertNotSame(historicalComparison, dialog())
        }
    }

    @Test fun privacyEntryRetiresSnapshotResourceFindingHelpAndDiagnosticsCallbacks() {
        saveSnapshot()
        preservingSavedState {
            val inspections: List<() -> Pair<AlertDialog, () -> Unit>> = listOf(
                { val window = openSnapshot(); window to { choose(window) } },
                { val window = resourceValue(); val back = window.getButton(AlertDialog.BUTTON_NEGATIVE)
                    window to { back.performClick(); Unit } },
                { val window = fixedContext(); val inspect = button(activity.getString(R.string.findings_context_inspect, 1), window.window!!.decorView)
                    window to { inspect.performClick(); Unit } },
                { val window = helpDestination(); val open = window.findViewById<Button>(R.id.help_external_open)
                    window to { open.performClick(); Unit } },
                { val window = openDiagnostics(); val share = window.findViewById<Button>(R.id.diagnostic_share)
                    window to { share.performClick(); Unit } },
            )
            for (open in inspections) {
                val (old, replay) = open()
                activity.findViewById<Button>(R.id.privacy_open).performClick(); idle()
                val privacy = dialog()
                assertFalse("Privacy must retire the older sibling inspection", old.isShowing)
                replay(); unchangedWindow(privacy)
                assertNull(shadowOf(activity).nextStartedActivity)
                privacy.dismiss(); idle()
            }
            openCurrent()
        }
    }
}
