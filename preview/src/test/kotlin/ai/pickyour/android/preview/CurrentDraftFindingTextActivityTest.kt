package ai.pickyour.android.preview

import android.app.AlertDialog
import android.os.Bundle
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.EditText
import android.widget.TextView
import ai.pickyour.core.evaluation.DraftCurrentInputs
import ai.pickyour.core.preview.PreviewCurrentDraftAnalysis
import ai.pickyour.core.preview.PreviewCurrentDraftState
import ai.pickyour.core.preview.PreviewDraftLibrary
import ai.pickyour.core.preview.PreviewDraftLoad
import ai.pickyour.core.preview.PreviewStoredDraft
import org.json.JSONArray
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
import java.nio.file.Files
import java.security.MessageDigest
import java.util.concurrent.CountDownLatch
import java.util.concurrent.ExecutorService
import java.util.concurrent.TimeUnit

/**
 * Actual saved-draft input admission and evaluation, followed by native View callbacks.
 * Rules are explicitly synthetic. These SDK28 simulations are not installed or TalkBack evidence.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], qualifiers = "en +ldltr")
@LooperMode(LooperMode.Mode.PAUSED)
class CurrentDraftFindingTextActivityTest {
    private lateinit var controller: ActivityController<PreviewActivity>
    private val activity get() = controller.get()
    private val ruleId = "rule.model.serving.mode.selfhost"
    private val message = "A".repeat(159) + "\uD83D\uDE00\u202e\n\\\"" + "B".repeat(330) + " MESSAGE END"
    private val guidance = "Observed guidance: " + "C".repeat(155) + "\t\u2066\\\"" + "D".repeat(210) + " GUIDANCE END"

    @Before fun launch() {
        AutosaveUiClock.pause()
        controller = Robolectric.buildActivity(PreviewActivity::class.java).setup()
        await { button(R.string.save_draft).isEnabled && button(R.string.retry_catalog).isEnabled }
        button(R.string.save_draft).performClick()
        await { state() is PreviewCurrentDraftState.Current }
        finishWork()
    }

    @After fun close() {
        if (::controller.isInitialized) controller.pause().stop().destroy()
    }

    @Suppress("UNCHECKED_CAST")
    private fun <T> field(name: String): T =
        PreviewActivity::class.java.getDeclaredField(name).apply { isAccessible = true }.get(activity) as T
    private fun worker() = field<ExecutorService>("worker")
    private fun state(): PreviewCurrentDraftState {
        val section = field<CurrentDraftCheckSection>("currentChecks")
        return (CurrentDraftCheckSection::class.java.getDeclaredField("analysis").apply { isAccessible = true }
            .get(section) as PreviewCurrentDraftAnalysis).state
    }
    private fun views(root: View = activity.window.decorView): List<View> = buildList {
        add(root)
        if (root is ViewGroup) repeat(root.childCount) { addAll(views(root.getChildAt(it))) }
    }
    private fun button(label: Int) = views().filterIsInstance<Button>()
        .single { it.text.toString() == activity.getString(label) }
    private fun dialog() = requireNotNull(ShadowAlertDialog.getLatestAlertDialog())
    private fun idle() = AutosaveUiClock.idle()
    private fun await(condition: () -> Boolean) {
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(25)
        while (System.nanoTime() < deadline) {
            idle()
            if (condition()) return
            Thread.sleep(10)
        }
        fail("Timed out waiting for actual current finding state")
    }
    private fun finishWork() {
        repeat(5) { idle(); worker().submit {}.get(25, TimeUnit.SECONDS); idle() }
    }
    private fun loaded(): PreviewStoredDraft {
        val result = PreviewDraftLibrary(activity.noBackupFilesDir.toPath()).openLegacy().load()
        assertTrue("The actual canonical read must succeed", result is PreviewDraftLoad.Available)
        return (result as PreviewDraftLoad.Available).record
    }
    private fun disk() = Files.walk(activity.noBackupFilesDir.toPath()).use { paths ->
        paths.filter(Files::isRegularFile).toList().associate {
            activity.noBackupFilesDir.toPath().relativize(it).toString() to
                MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(it)).toList()
        }
    }
    private fun fixture(name: String) = activity.assets.open("$name.json").use { it.readBytes() }

    /**
     * Replace only two Strings in one labelled synthetic rule, through the actual validated
     * input seam. Invoke the Activity's real refresh and let its real worker/canonical reads
     * produce Current; never construct a request/outcome or replace the analysis state.
     */
    private fun installText(observation: String = message, remediation: String = guidance): PreviewCurrentDraftState.Current {
        val prior = state()
        val saved = field<PreviewStoredDraft>("savedRecord")
        assertTrue("The actual saved draft has no deployment-mode selection", saved.components.selections.isEmpty())
        val before = disk()
        val rules = JSONObject(String(fixture("ruleset"), Charsets.UTF_8))
        val rows = rules.getJSONArray("rules")
        val target = (0 until rows.length()).map(rows::getJSONObject).single { it.getString("id") == ruleId }
        target.put("message", observation).put("remediation", remediation)
        val bytes = rules.toString().toByteArray(Charsets.UTF_8)
        assertTrue(bytes.size <= DraftCurrentInputs.MAX_BYTES)
        val inputs = requireNotNull(DraftCurrentInputs.read(fixture("catalog"), fixture("dependency-graph"), bytes))
        PreviewActivity::class.java.getDeclaredField("currentCheckInputs").apply { isAccessible = true }.set(activity, inputs)
        PreviewActivity::class.java.getDeclaredMethod("updateCurrentChecks").apply { isAccessible = true }.invoke(activity)
        await { state() is PreviewCurrentDraftState.Current && state() !== prior }
        finishWork()
        val current = state() as PreviewCurrentDraftState.Current
        val finding = current.outcome.findings.single { it.ruleId == ruleId }
        assertEquals("A missing deployment-mode selection remains unknown", "unknown", finding.outcome)
        assertEquals("The evaluator must retain the admitted raw explanation", observation, finding.message)
        assertEquals("The evaluator must retain the admitted raw guidance", remediation, finding.remediation)
        assertEquals(inputs.rulesetHash, current.request.binding.rulesetHash)
        assertEquals(saved.revision, current.request.binding.sourceRevision)
        assertEquals(saved.baselineIdentity, field<PreviewStoredDraft>("savedRecord").baselineIdentity)
        assertEquals(before, disk())
        return current
    }

    private fun detail(current: PreviewCurrentDraftState.Current, index: Int =
        current.outcome.findings.indexOfFirst { it.ruleId == ruleId }): AlertDialog {
        assertSame(current, state())
        assertTrue(index in current.outcome.findings.indices)
        val inspect = activity.findViewById<Button>(R.id.current_draft_check_inspect)
        assertTrue(inspect.isEnabled)
        inspect.performClick(); idle()
        var page = dialog()
        var offset = 0
        while (index >= offset + 10) {
            page.getButton(AlertDialog.BUTTON_POSITIVE).performClick(); idle()
            val next = dialog()
            assertNotSame(page, next)
            page = next; offset += 10
        }
        assertTrue(page.isShowing)
        val label = page.listView.adapter.getItem(index - offset).toString()
        assertTrue(label.contains(current.outcome.findings[index].ruleId))
        shadowOf(page).clickOnItem(index - offset); idle()
        return dialog().also {
            assertNotSame(page, it)
            assertTrue(it.isShowing)
            assertTrue(body(it).contains(current.outcome.findings[index].ruleId))
        }
    }
    private fun body(window: AlertDialog) = window.findViewById<TextView>(android.R.id.message).text.toString()
    private fun fullText(detail: AlertDialog): AlertDialog {
        val entry = detail.getButton(AlertDialog.BUTTON_NEUTRAL)
        assertNotNull("A current finding needs a full-text entry", entry)
        assertEquals("A current finding needs a visible full-text entry", View.VISIBLE, entry.visibility)
        assertEquals("Full text", entry.text.toString())
        entry.performClick(); idle()
        return dialog().also {
            assertNotSame(detail, it); assertTrue(it.isShowing)
            assertEquals(listOf("Explanation", "Rule guidance"),
                (0 until it.listView.adapter.count).map { index -> it.listView.adapter.getItem(index).toString() })
        }
    }
    private fun part(chooser: AlertDialog, field: Int): AlertDialog {
        shadowOf(chooser).clickOnItem(field); idle()
        return dialog().also { assertNotSame(chooser, it); assertTrue(it.isShowing) }
    }
    private fun visibleButton(window: AlertDialog, which: Int) =
        window.getButton(which)?.takeIf { it.visibility == View.VISIBLE }
    private fun quoted(window: AlertDialog): String {
        val text = body(window)
        val first = text.indexOf('"'); val last = text.lastIndexOf('"')
        assertTrue("A full-text part must remain a quoted literal", first >= 0 && last > first)
        // Only the existing directional wrapper may surround the independently parsed literal.
        assertTrue(text.substring(0, first).all { it == '\u200f' || it == '\u202a' })
        assertTrue(text.substring(last + 1).all { it == '\u202c' || it == '\u200f' })
        return text.substring(first, last + 1)
    }
    private fun decode(window: AlertDialog): String {
        val json = quoted(window)
        assertFalse(json.contains('\n'))
        assertFalse(json.contains('\u202e'))
        assertFalse(json.contains('\u2066'))
        return JSONArray("[$json]").getString(0).also { assertTrue(it.length <= 160) }
    }
    private fun readAll(first: AlertDialog): Pair<String, AlertDialog> {
        var page = first
        val result = StringBuilder()
        var count = 0
        while (true) {
            assertTrue(++count <= 26) // Finite fixture guard; production must derive every part from the captured String.
            result.append(decode(page))
            val next = visibleButton(page, AlertDialog.BUTTON_POSITIVE) ?: break
            next.performClick(); idle()
            val replacement = dialog()
            assertNotSame(page, replacement); assertFalse(page.isShowing); assertTrue(replacement.isShowing)
            page = replacement
        }
        return result.toString() to page
    }
    private fun back(window: AlertDialog, which: Int = AlertDialog.BUTTON_NEGATIVE): AlertDialog {
        window.getButton(which).performClick(); idle()
        return dialog().also { assertNotSame(window, it); assertFalse(window.isShowing); assertTrue(it.isShowing) }
    }
    private data class Retained(val windows: List<AlertDialog>, val replay: () -> Unit)
    private fun retained(current: PreviewCurrentDraftState.Current, stage: Int = 2): Retained {
        val detail = detail(current)
        val chooser = fullText(detail)
        val windows = mutableListOf(detail, chooser)
        if (stage > 0) {
            windows += part(chooser, 0)
            if (stage > 1) {
                windows.last().getButton(AlertDialog.BUTTON_POSITIVE).performClick(); idle()
                windows += dialog().also { assertTrue(it.isShowing) }
            }
        }
        val buttons = windows.flatMap { window ->
            listOf(AlertDialog.BUTTON_POSITIVE, AlertDialog.BUTTON_NEGATIVE, AlertDialog.BUTTON_NEUTRAL)
                .mapNotNull { visibleButton(window, it) }
        }
        return Retained(windows.toList()) {
            buttons.forEach { it.performClick() }
            shadowOf(chooser).clickOnItem(0)
            shadowOf(chooser).clickOnItem(1)
            idle()
        }
    }

    @Test fun ordinaryCurrentDetailKeepsOutcomeSeverityAndSavedBytes() {
        val current = state() as PreviewCurrentDraftState.Current
        val saved = loaded().baselineIdentity
        val before = disk()
        val finding = current.outcome.findings.first()
        val window = detail(current, 0)
        val text = body(window)
        assertTrue(text.contains("\nOutcome: \"" + finding.outcome + "\""))
        assertTrue(text.contains("\nSeverity: \"" + finding.severity + "\""))
        assertTrue(text.contains("\nObservation: "))
        assertTrue(text.contains("\nRecorded guidance: "))
        val page = back(window, AlertDialog.BUTTON_POSITIVE)
        assertEquals(10, page.listView.adapter.count)
        page.cancel(); idle(); finishWork()
        assertSame(current, state())
        assertEquals(saved, loaded().baselineIdentity)
        assertEquals(before, disk())
    }

    @Test fun longCurrentFieldsRoundTripAllEscapedUtf16Parts() {
        val current = installText()
        val before = disk(); val saved = loaded().baselineIdentity
        val chooser = fullText(detail(current))
        val first = part(chooser, 0)
        // Independently specified: the valid supplementary pair crosses the raw-unit boundary.
        assertEquals("\"" + "A".repeat(159) + "\\ud83d\"", quoted(first))
        first.getButton(AlertDialog.BUTTON_POSITIVE).performClick(); idle()
        val second = dialog()
        assertTrue(quoted(second).startsWith("\"\\ude00\\u202e\\n\\\\\\\""))
        val again = back(second, AlertDialog.BUTTON_NEUTRAL)
        val explanation = readAll(again)
        assertEquals(message, explanation.first)
        assertTrue(explanation.first.endsWith("MESSAGE END"))
        val fields = back(explanation.second)
        val recorded = readAll(part(fields, 1))
        assertEquals(guidance, recorded.first)
        assertTrue(recorded.first.endsWith("GUIDANCE END"))
        recorded.second.cancel(); idle(); finishWork()
        assertSame(current, state())
        assertEquals(saved, loaded().baselineIdentity)
        assertEquals(before, disk())
    }

    @Test @Config(qualifiers = "ar +ldrtl")
    fun exactBoundariesSynthesizedEmptyGuidanceAndRtlRemainDistinguished() {
        assertEquals(View.LAYOUT_DIRECTION_RTL, activity.resources.configuration.layoutDirection)
        for (text in listOf("ordinary observation", "x".repeat(160), "x".repeat(160) + "Y")) {
            val current = installText(text, "ordinary guidance")
            val before = disk()
            val chooser = fullText(detail(current))
            val first = part(chooser, 0)
            val hasNext = visibleButton(first, AlertDialog.BUTTON_POSITIVE) != null
            assertEquals(text.length > 160, hasNext)
            val explanation = readAll(first)
            assertEquals(text, explanation.first)
            val fields = back(explanation.second)
            val recorded = part(fields, 1)
            assertEquals("ordinary guidance", decode(recorded))
            assertNull(visibleButton(recorded, AlertDialog.BUTTON_POSITIVE))
            recorded.cancel(); idle(); finishWork()
            assertEquals(before, disk())
        }
        // Supplied rules require nonempty Strings. An actual skipped result, however,
        // synthesizes empty guidance; keep that distinct without bypassing admission.
        val current = state() as PreviewCurrentDraftState.Current
        val index = current.outcome.findings.indexOfFirst { it.ruleId == "rule.model.provider.match" }
        assertTrue(index >= 0)
        val skipped = current.outcome.findings[index]
        assertEquals("skipped", skipped.outcome)
        assertEquals("rule.model.provider.match: skipped", skipped.message)
        assertEquals("", skipped.remediation)
        val before = disk()
        val empty = part(fullText(detail(current, index)), 1)
        assertEquals("\"\"", quoted(empty))
        assertEquals("", decode(empty))
        assertNull(visibleButton(empty, AlertDialog.BUTTON_POSITIVE))
        empty.cancel(); idle(); finishWork()
        assertEquals(before, disk())
    }

    @Test fun fieldAndRowBackNavigationRetainsCapturedFinding() {
        val current = installText()
        val index = current.outcome.findings.indexOfFirst { it.ruleId == ruleId }
        assertTrue("The selected real unknown rule exercises a later row page", index >= 20)
        val original = detail(current)
        val originalText = body(original)
        val chooser = fullText(original)
        val first = part(chooser, 0)
        first.getButton(AlertDialog.BUTTON_POSITIVE).performClick(); idle()
        val fields = back(dialog())
        val guidancePart = part(fields, 1)
        val restoredFields = back(guidancePart)
        val restoredDetail = back(restoredFields, AlertDialog.BUTTON_POSITIVE)
        assertEquals(originalText, body(restoredDetail))
        val rows = back(restoredDetail, AlertDialog.BUTTON_POSITIVE)
        assertEquals(current.outcome.findings[(index / 10) * 10].ruleId,
            rows.listView.adapter.getItem(0).toString().substringAfter('"').substringBefore('"'))
        assertSame(current, state())
        rows.cancel(); idle()
    }

    @Test fun dismissedAndSupersededCallbacksRemainInert() {
        val current = installText()
        val before = disk()
        val old = retained(current)
        old.windows.last().cancel(); idle()
        old.replay()
        assertFalse(dialog().isShowing)
        val fresh = retained(current)
        val active = fresh.windows.last()
        old.replay()
        assertSame(active, dialog()); assertTrue(active.isShowing)
        active.cancel(); idle(); old.replay(); fresh.replay()
        assertFalse(dialog().isShowing)
        assertSame(current, state()); assertEquals(before, disk())
        val recovery = fullText(detail(current))
        assertEquals(message, readAll(part(recovery, 0)).first)
    }

    @Test fun siblingEntryRetiresFieldChooserAndAllPartActions() {
        val current = installText()
        val before = disk(); val saved = loaded().baselineIdentity
        for (stage in 0..2) {
            val old = retained(current, stage)
            button(R.string.draft_history).performClick(); idle()
            val history = dialog()
            assertTrue(history.isShowing)
            old.replay()
            assertSame(history, dialog()); assertTrue(history.isShowing)
            old.windows.forEach { assertFalse(it.isShowing) }
            history.cancel(); idle(); old.replay()
            assertSame(history, dialog()); assertFalse(history.isShowing)
            val fresh = fullText(detail(current))
            val value = readAll(part(fresh, 0))
            assertEquals(message, value.first)
            value.second.cancel(); idle()
            assertSame(current, state())
            assertEquals(saved, loaded().baselineIdentity); assertEquals(before, disk())
        }
    }

    @Test fun retryRetiresTextBeforeQueuedResultAndFreshReentryWorks() {
        val current = installText()
        val old = retained(current)
        val before = disk()
        val entered = CountDownLatch(1); val release = CountDownLatch(1)
        worker().execute { entered.countDown(); check(release.await(25, TimeUnit.SECONDS)) }
        assertTrue(entered.await(10, TimeUnit.SECONDS))
        try {
            activity.findViewById<Button>(R.id.current_draft_check_retry).performClick()
            assertTrue(state() is PreviewCurrentDraftState.Rechecking)
            old.replay()
            old.windows.forEach { assertFalse(it.isShowing) }
            assertFalse(activity.findViewById<Button>(R.id.current_draft_check_inspect).isEnabled)
            assertEquals(before, disk())
        } finally { release.countDown(); finishWork() }
        await { state() is PreviewCurrentDraftState.Current }
        val next = state() as PreviewCurrentDraftState.Current
        assertNotSame(current.request, next.request)
        assertEquals(current.request.binding, next.request.binding)
        val fresh = fullText(detail(next))
        old.replay(); assertSame(fresh, dialog())
        assertEquals(message, readAll(part(fresh, 0)).first)
        assertEquals(before, disk())
    }

    @Test fun pendingEditSaveAndInputReplacementNeverReviveOldText() {
        val current = installText()
        val old = retained(current)
        val before = disk()
        val title = field<EditText>("title")
        title.setText("A new acknowledged title")
        assertTrue(state() is PreviewCurrentDraftState.Unavailable)
        old.replay()
        assertFalse(dialog().isShowing)
        assertEquals(before, disk()) // Controlled UI frames have not spent the autosave deadline.
        button(R.string.save_draft).performClick(); finishWork()
        await { state() is PreviewCurrentDraftState.Current }
        val saved = state() as PreviewCurrentDraftState.Current
        assertTrue(saved.request.binding.sourceRevision > current.request.binding.sourceRevision)
        val afterSave = disk()
        old.replay(); assertEquals(afterSave, disk())
        val currentPages = retained(saved)
        val replacement = installText(message + " replacement", guidance + " replacement")
        currentPages.replay()
        assertFalse(dialog().isShowing)
        assertNotEquals(saved.request.binding.rulesetHash, replacement.request.binding.rulesetHash)
        assertEquals(saved.request.binding.sourceRevision, replacement.request.binding.sourceRevision)
        assertEquals(afterSave, disk())
        val fresh = fullText(detail(replacement))
        assertEquals(message + " replacement", readAll(part(fresh, 0)).first)
    }

    @Test fun storeSwitchPauseAndRecreationNeverRestoreOldFieldAuthority() {
        val current = installText()
        val old = retained(current)
        button(R.string.library_new).performClick(); idle()
        val confirmation = dialog()
        old.replay(); assertSame(confirmation, dialog())
        confirmation.getButton(AlertDialog.BUTTON_POSITIVE).performClick(); idle(); finishWork()
        await { state() is PreviewCurrentDraftState.Current }
        val switched = state() as PreviewCurrentDraftState.Current
        assertNotEquals(current.request.binding.inputDigest, switched.request.binding.inputDigest)
        val afterSwitch = disk()
        old.replay(); assertEquals(afterSwitch, disk())
        val paused = retained(switched)
        controller.pause()
        paused.replay()
        assertTrue(state() is PreviewCurrentDraftState.Unavailable)
        assertFalse(dialog().isShowing)
        controller.resume(); finishWork()
        await { state() is PreviewCurrentDraftState.Current }
        val resumed = state() as PreviewCurrentDraftState.Current
        val recreated = retained(resumed)
        val savedState = Bundle()
        controller.pause().stop().saveInstanceState(savedState).destroy()
        controller = Robolectric.buildActivity(PreviewActivity::class.java).setup(savedState)
        // Deliver controlled traversal frames before requiring the new window to be attached.
        finishWork()
        assertTrue(activity.window.decorView.isAttachedToWindow)
        await { state() is PreviewCurrentDraftState.Current }
        recreated.replay()
        assertFalse(dialog().isShowing)
        assertNotSame(resumed.request, (state() as PreviewCurrentDraftState.Current).request)
        assertEquals(afterSwitch, disk())
        // Synthetic replacement inputs are not restored from the Bundle; admit them afresh.
        val fresh = installText()
        assertEquals(message, readAll(part(fullText(detail(fresh)), 0)).first)
        assertEquals(afterSwitch, disk())
    }
}
