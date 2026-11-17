package ai.pickyour.android.preview

import android.app.AlertDialog
import android.graphics.Rect
import android.os.Bundle
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.EditText
import android.widget.ScrollView
import android.widget.TextView
import ai.pickyour.core.drafts.*
import ai.pickyour.core.evaluation.DraftCurrentInputs
import ai.pickyour.core.preview.*
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
import java.security.MessageDigest
import java.util.concurrent.CountDownLatch
import java.util.concurrent.ExecutorService
import java.util.concurrent.TimeUnit

/** Real admitted saved inputs and rule execution; these SDK28 simulations are not installed/AT evidence. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], qualifiers = "en +ldltr")
@LooperMode(LooperMode.Mode.PAUSED)
class CurrentDraftFindingContextActivityTest {
    private lateinit var controller: ActivityController<PreviewActivity>
    private val activity get() = controller.get()
    private val ruleId = "rule.required.foundation"
    private val version = "V".repeat(159) + "\uD83D\uDE00\u202e\n\\\"" + "T".repeat(210) + " VERSION END"
    private val privateNote = "PRIVATE_CONTEXT_NOTES_MUST_NOT_BE_RENDERED"
    private lateinit var installedCatalog: JSONObject

    @Before fun launch() {
        AutosaveUiClock.pause()
        controller = Robolectric.buildActivity(PreviewActivity::class.java).setup()
        await { button(R.string.save_draft).isEnabled && button(R.string.retry_catalog).isEnabled }
        button(R.string.save_draft).performClick()
        await { state() is PreviewCurrentDraftState.Current }
        finishWork()
    }
    @After fun close() { if (::controller.isInitialized) controller.pause().stop().destroy() }
    @Suppress("UNCHECKED_CAST")
    private fun <T> field(name: String): T = PreviewActivity::class.java.getDeclaredField(name)
        .apply { isAccessible = true }.get(activity) as T
    private fun setField(name: String, value: Any) { PreviewActivity::class.java.getDeclaredField(name)
        .apply { isAccessible = true }.set(activity, value) }
    private fun worker() = field<ExecutorService>("worker")
    private fun state(): PreviewCurrentDraftState {
        val section = field<CurrentDraftCheckSection>("currentChecks")
        return (CurrentDraftCheckSection::class.java.getDeclaredField("analysis").apply { isAccessible = true }
            .get(section) as PreviewCurrentDraftAnalysis).state
    }
    private fun current() = state() as PreviewCurrentDraftState.Current
    private fun views(root: View = activity.window.decorView): List<View> = buildList {
        add(root); if (root is ViewGroup) repeat(root.childCount) { addAll(views(root.getChildAt(it))) }
    }
    private fun button(label: Int) = views().filterIsInstance<Button>().single { it.text.toString() == activity.getString(label) }
    private fun dialog() = requireNotNull(ShadowAlertDialog.getLatestAlertDialog())
    private fun idle() = AutosaveUiClock.idle()
    private fun await(condition: () -> Boolean) {
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(25)
        while (System.nanoTime() < deadline) { idle(); if (condition()) return; Thread.sleep(10) }
        fail("Timed out waiting for actual current selection context")
    }
    private fun finishWork() { repeat(5) { idle(); worker().submit {}.get(25, TimeUnit.SECONDS); idle() } }
    private fun document(name: String) = activity.assets.open("$name.json").bufferedReader().use { JSONObject(it.readText()) }
    private fun loaded() = (PreviewDraftLibrary(activity.noBackupFilesDir.toPath()).openLegacy().load() as PreviewDraftLoad.Available).record
    private fun store() = LocalDraftStore(activity.noBackupFilesDir.toPath().resolve("guest-draft"))
    private fun disk() = Files.walk(activity.noBackupFilesDir.toPath()).use { paths ->
        paths.filter(Files::isRegularFile).toList().associate {
            activity.noBackupFilesDir.toPath().relativize(it).toString() to
                MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(it)).toList()
        }
    }
    private fun reopen() {
        button(R.string.reopen_draft).performClick(); idle()
        dialog().getButton(AlertDialog.BUTTON_POSITIVE).performClick(); idle()
        await { !field<Boolean>("storageBusy") && field<Boolean>("storageReady") }
        finishWork(); await { state() is PreviewCurrentDraftState.Current }
    }

    /** Same validated catalog bytes feed all actual Activity authorities; no Current/outcome is fabricated. */
    private fun installInputs(catalog: JSONObject, rules: JSONObject = document("ruleset")): DraftCurrentInputs {
        val bytes = catalog.toString().toByteArray(Charsets.UTF_8)
        val fixture = CatalogFixtureData.read({ bytes }) { activity.assets.open("pricebook.json") }
        val browser = requireNotNull(fixture.catalog)
        val inputs = requireNotNull(DraftCurrentInputs.read(bytes,
            document("dependency-graph").toString().toByteArray(Charsets.UTF_8), rules.toString().toByteArray(Charsets.UTF_8)))
        val catalogSnapshot = SelectionCatalogSnapshot.fromJson(bytes)
        assertEquals(inputs.catalogHash, catalogSnapshot.hash)
        setField("catalogFixture", fixture); setField("componentCatalog", catalogSnapshot); setField("currentCheckInputs", inputs)
        field<SelectionEditSection>("componentSection").catalog(browser.items, browser.hash)
        field<CatalogBrowseSection>("catalog").show(browser)
        finishWork()
        return inputs
    }

    /**
     * Model has frozen cardinality many. Each secondary has its own catalog ID and exact matching
     * version; the existing primary stays on its original item/version. Stored order intentionally
     * differs from the evaluator's sorted affected-ID order. Optional-tier intent is not involved.
     */
    private fun installModels(count: Int = 2, secondaryVersion: String = version,
        disableSecondary: Boolean = false): PreviewCurrentDraftState.Current {
        val prior = state()
        val selections = document("build").getJSONArray("selections")
        val template = (0 until selections.length()).map(selections::getJSONObject).single { it.getString("kind") == "model" }
        val catalog = document("catalog")
        val items = catalog.getJSONArray("items")
        val model = (0 until items.length()).map(items::getJSONObject).single { it.getString("id") == "cat.model.fixture" }
        val rows = (0 until count).map { index ->
            val selection = JSONObject(template.toString()).put("notes", privateNote)
            if (index == 0) selection.put("id", "sel_model_z_primary")
            else {
                val id = "cat.model.context_$index"
                val exactVersion = if (index == 1) secondaryVersion else "context-$index-v1"
                items.put(JSONObject(model.toString()).put("id", id).put("version", exactVersion))
                selection.put("id", "sel_model_a_" + index.toString().padStart(2, '0'))
                    .put("role", "secondary_$index").put("catalog_item_id", id).put("catalog_item_version", exactVersion)
                    .put("enabled", !(disableSecondary && index == 1))
            }
            LocalSelection.fromJson(selection.toString().toByteArray(Charsets.UTF_8))
        }
        installedCatalog = catalog
        val inputs = installInputs(catalog)
        val durable = (store().load() as DraftLoad.Available).draft
        assertTrue(store().save(durable.version, durable.content.withComponents(DraftSelections(rows)), durable.metadata) is DraftSave.Saved)
        reopen()
        val result = current()
        assertNotSame(prior, result)
        val saved = field<PreviewStoredDraft>("savedRecord")
        assertEquals(rows, saved.components.selections)
        assertEquals(saved.baselineIdentity, loaded().baselineIdentity)
        assertEquals(saved.revision, result.request.binding.sourceRevision)
        assertEquals(saved.baselineIdentity.substringAfter(':'), result.request.binding.sourceHash.hex)
        assertEquals(inputs.catalogHash, result.request.binding.catalogHash)
        assertEquals(inputs.rulesetHash, result.request.binding.rulesetHash)
        val finding = result.outcome.findings.single { it.ruleId == ruleId }
        assertEquals("Missing foundation choices remain an actual failure", "fail", finding.outcome)
        assertEquals("error", finding.severity)
        assertEquals(rows.filter { it.enabled }.map { it.id }.sorted(), finding.selectionIds)
        if (count > 1 && !disableSecondary) {
            assertEquals(secondaryVersion, saved.components.selections[1].catalogItemVersion)
            assertEquals("sel_model_a_01", finding.selectionIds.first())
        }
        return result
    }

    private fun body(window: AlertDialog) = window.findViewById<TextView>(android.R.id.message).text.toString()
    private fun detail(result: PreviewCurrentDraftState.Current): AlertDialog {
        assertSame(result, state())
        val index = result.outcome.findings.indexOfFirst { it.ruleId == ruleId }
        assertTrue(index >= 0)
        activity.findViewById<Button>(R.id.current_draft_check_inspect).also { assertTrue(it.isEnabled); it.performClick() }
        idle(); var page = dialog(); var offset = 0
        while (index >= offset + 10) {
            page.getButton(AlertDialog.BUTTON_POSITIVE).performClick(); idle(); page = dialog(); offset += 10
        }
        assertTrue(page.listView.adapter.getItem(index - offset).toString().contains(ruleId))
        shadowOf(page).clickOnItem(index - offset); idle()
        return dialog().also { assertTrue(it.isShowing); assertTrue(body(it).contains(ruleId)) }
    }
    private fun entry(window: AlertDialog): Button {
        val found = views(window.window!!.decorView).filterIsInstance<Button>()
            .singleOrNull { it.text.toString() == "Inspect affected selections" }
        assertNotNull("An admitted current finding needs its read-only affected-selection destination", found)
        return requireNotNull(found).also { assertTrue(it.isShown); assertTrue(it.isEnabled) }
    }
    private fun context(window: AlertDialog): AlertDialog {
        entry(window).performClick(); idle()
        return dialog().also { assertNotSame(window, it); assertFalse(window.isShowing); assertTrue(it.isShowing) }
    }
    private fun select(window: AlertDialog, index: Int): AlertDialog {
        shadowOf(window).clickOnItem(index); idle()
        return dialog().also { assertNotSame(window, it); assertTrue(it.isShowing) }
    }
    private fun back(window: AlertDialog, which: Int = AlertDialog.BUTTON_NEGATIVE): AlertDialog {
        window.getButton(which).performClick(); idle()
        return dialog().also { assertNotSame(window, it); assertFalse(window.isShowing); assertTrue(it.isShowing) }
    }
    private fun fullVersion(window: AlertDialog): AlertDialog {
        val button = window.getButton(AlertDialog.BUTTON_NEUTRAL)
        assertEquals("Complete version", button.text.toString())
        return back(window, AlertDialog.BUTTON_NEUTRAL)
    }
    private fun visibleButton(window: AlertDialog, which: Int) = window.getButton(which)?.takeIf { it.visibility == View.VISIBLE }
    private fun quoted(window: AlertDialog): String {
        val text = body(window); val first = text.indexOf('"'); val last = text.lastIndexOf('"')
        assertTrue(first >= 0 && last > first)
        assertTrue(text.substring(0, first).all { it == '\u200f' || it == '\u202a' })
        assertTrue(text.substring(last + 1).all { it == '\u202c' || it == '\u200f' })
        return text.substring(first, last + 1)
    }
    private fun readVersion(first: AlertDialog): Pair<String, AlertDialog> {
        var page = first; val text = StringBuilder(); var parts = 0
        while (true) {
            assertTrue(++parts <= 26) // Finite fixture guard, never a production page limit.
            val json = quoted(page)
            assertFalse(json.contains('\n')); assertFalse(json.contains('\u202e'))
            val decoded = JSONArray("[$json]").getString(0)
            assertTrue(decoded.length <= 160); text.append(decoded)
            val next = visibleButton(page, AlertDialog.BUTTON_POSITIVE) ?: break
            next.performClick(); idle(); val replacement = dialog()
            assertNotSame(page, replacement); assertFalse(page.isShowing); page = replacement
        }
        return text.toString() to page
    }
    private data class Retained(val windows: List<AlertDialog>, val replay: () -> Unit)
    private fun retained(result: PreviewCurrentDraftState.Current, stage: Int = 3): Retained {
        val finding = detail(result); val open = entry(finding); val list = context(finding)
        val windows = mutableListOf(finding, list)
        if (stage > 0) {
            windows += select(list, 0)
            if (stage > 1) {
                windows += fullVersion(windows.last())
                if (stage > 2) {
                    windows.last().getButton(AlertDialog.BUTTON_POSITIVE).performClick(); idle(); windows += dialog()
                }
            }
        }
        val actions = windows.flatMap { window -> listOf(AlertDialog.BUTTON_POSITIVE, AlertDialog.BUTTON_NEGATIVE,
            AlertDialog.BUTTON_NEUTRAL).mapNotNull { visibleButton(window, it) } }
        return Retained(windows.toList()) { open.performClick(); actions.forEach { it.performClick() }; shadowOf(list).clickOnItem(0); idle() }
    }

    @Test fun actualCurrentContextResolvesReportedRolesAndVersionsWithoutWrites() {
        val result = installModels(secondaryVersion = "secondary-version-2")
        val before = disk(); val baseline = loaded().baselineIdentity
        val finding = detail(result); val originalText = body(finding)
        assertEquals("Close", finding.getButton(AlertDialog.BUTTON_NEGATIVE).text.toString())
        assertEquals("Back to observations", finding.getButton(AlertDialog.BUTTON_POSITIVE).text.toString())
        assertEquals("Full text", finding.getButton(AlertDialog.BUTTON_NEUTRAL).text.toString())
        val list = context(finding)
        assertEquals(2, list.listView.adapter.count)
        assertTrue(list.listView.adapter.getItem(0).toString().contains("sel_model_a_01"))
        var selected = select(list, 0)
        assertTrue(body(selected).contains("Selection ID: \"sel_model_a_01\""))
        assertTrue(body(selected).contains("Kind: \"model\"")); assertTrue(body(selected).contains("Role: \"secondary_1\""))
        assertTrue(body(selected).contains("Catalog item: \"cat.model.context_1\""))
        assertTrue(body(selected).contains("Captured version: \"secondary-version-2\""))
        assertTrue(body(selected).contains("Enabled: true")); assertFalse(body(selected).contains(privateNote))
        assertFalse(body(selected).contains("temperature"))
        selected = select(back(selected, AlertDialog.BUTTON_POSITIVE), 1)
        assertTrue(body(selected).contains("Role: \"primary\"")); assertTrue(body(selected).contains("\"cat.model.fixture\""))
        assertEquals("2026.10-demo", readVersion(fullVersion(selected)).first)
        val restored = back(back(back(dialog()), AlertDialog.BUTTON_POSITIVE))
        assertEquals(originalText, body(restored))
        restored.cancel(); idle(); finishWork()
        assertSame(result, state()); assertEquals(baseline, loaded().baselineIdentity); assertEquals(before, disk())
    }

    @Test @Config(qualifiers = "ar +ldrtl")
    fun completeVersionPreservesEveryEscapedUtf16PartAndTail() {
        assertEquals(View.LAYOUT_DIRECTION_RTL, activity.resources.configuration.layoutDirection)
        val result = installModels(); val before = disk()
        val selected = select(context(detail(result)), 0)
        assertFalse(body(selected).contains('\u202e')); assertFalse(body(selected).contains(privateNote))
        val first = fullVersion(selected)
        assertEquals("\"" + "V".repeat(159) + "\\ud83d\"", quoted(first))
        first.getButton(AlertDialog.BUTTON_POSITIVE).performClick(); idle()
        assertTrue(quoted(dialog()).startsWith("\"\\ude00\\u202e\\n\\\\\\\""))
        val all = readVersion(back(dialog(), AlertDialog.BUTTON_NEUTRAL))
        assertEquals(version, all.first); assertTrue(all.first.endsWith("VERSION END"))
        val restored = back(all.second)
        val role = body(restored).lineSequence().single { it.startsWith("Role: ") }.removePrefix("Role: ")
        val firstQuote = role.indexOf('"'); val lastQuote = role.lastIndexOf('"')
        assertTrue(firstQuote >= 0 && lastQuote > firstQuote)
        assertTrue(role.substring(0, firstQuote).all { it == '\u200f' || it == '\u202a' })
        assertTrue(role.substring(lastQuote + 1).all { it == '\u202c' || it == '\u200f' })
        assertEquals("secondary_1", JSONArray("[${role.substring(firstQuote, lastQuote + 1)}]").getString(0))
        restored.cancel(); idle(); assertEquals(before, disk()); assertSame(result, state())
    }

    @Test fun noAffectedIdsRemainExplicitWithoutInventingMissingChoices() {
        val result = current(); val row = result.outcome.findings.single { it.ruleId == ruleId }
        assertEquals("fail", row.outcome); assertTrue(row.selectionIds.isEmpty())
        assertTrue(field<PreviewStoredDraft>("savedRecord").components.selections.isEmpty())
        val before = disk(); val finding = detail(result); val original = body(finding)
        val empty = context(finding)
        assertTrue(body(empty).contains("This finding reports no affected selection IDs."))
        assertTrue(body(empty).contains("does not identify a missing choice or establish readiness"))
        assertEquals(original, body(back(empty)))
        dialog().cancel(); idle(); assertSame(result, state()); assertEquals(before, disk())
    }

    @Test fun contextListPagingAndBackKeepTheSameFindingAndSelection() {
        val result = installModels(12); val before = disk()
        val finding = detail(result); val original = body(finding)
        val page = context(finding); assertEquals(10, page.listView.adapter.count)
        val last = back(page, AlertDialog.BUTTON_POSITIVE); assertEquals(2, last.listView.adapter.count)
        val primary = select(last, 1); assertTrue(body(primary).contains("Role: \"primary\""))
        val restoredLast = back(primary, AlertDialog.BUTTON_POSITIVE); assertEquals(2, restoredLast.listView.adapter.count)
        val first = back(restoredLast, AlertDialog.BUTTON_NEUTRAL); assertEquals(10, first.listView.adapter.count)
        assertEquals(original, body(back(first)))
        dialog().cancel(); idle(); assertEquals(before, disk()); assertSame(result, state())
    }

    @Test fun dismissedAndSupersededContextControlsNeverRevive() {
        val result = installModels(); val before = disk()
        val old = retained(result); old.windows.last().cancel(); idle(); old.replay()
        assertFalse(dialog().isShowing)
        val fresh = retained(result); val live = fresh.windows.last()
        old.replay(); assertSame(live, dialog()); assertTrue(live.isShowing)
        live.cancel(); idle(); old.replay(); fresh.replay(); assertFalse(dialog().isShowing)
        assertEquals(version, readVersion(fullVersion(select(context(detail(result)), 0))).first)
        dialog().cancel(); idle(); assertEquals(before, disk()); assertSame(result, state())
    }

    @Test fun siblingEntryRetiresContextRowsAndVersionCallbacks() {
        val result = installModels(); val before = disk()
        for (stage in 0..3) {
            val old = retained(result, stage)
            button(R.string.draft_history).performClick(); idle(); val history = dialog()
            assertTrue(history.isShowing); old.replay(); assertSame(history, dialog())
            old.windows.forEach { assertFalse(it.isShowing) }
            history.cancel(); idle(); old.replay(); assertFalse(history.isShowing); assertSame(history, dialog())
            val fresh = select(context(detail(result)), 0)
            assertEquals(version, readVersion(fullVersion(fresh)).first)
            dialog().cancel(); idle(); assertSame(result, state()); assertEquals(before, disk())
        }
    }

    @Test fun retryAndInputReplacementRetireContextBeforeFreshPublication() {
        val result = installModels(); val before = disk(); val old = retained(result)
        val entered = CountDownLatch(1); val release = CountDownLatch(1)
        worker().execute { entered.countDown(); check(release.await(25, TimeUnit.SECONDS)) }
        assertTrue(entered.await(10, TimeUnit.SECONDS))
        try {
            activity.findViewById<Button>(R.id.current_draft_check_retry).performClick()
            assertTrue(state() is PreviewCurrentDraftState.Rechecking); old.replay()
            old.windows.forEach { assertFalse(it.isShowing) }
            assertFalse(activity.findViewById<Button>(R.id.current_draft_check_inspect).isEnabled)
        } finally { release.countDown(); finishWork() }
        await { state() is PreviewCurrentDraftState.Current }
        val retried = current(); assertNotSame(result.request, retried.request)
        assertEquals(result.request.binding, retried.request.binding)
        val held = retained(retried)
        val rules = document("ruleset"); rules.getJSONArray("rules").getJSONObject(0).put("remediation", "Synthetic replacement guidance")
        installInputs(installedCatalog, rules)
        PreviewActivity::class.java.getDeclaredMethod("updateCurrentChecks").apply { isAccessible = true }.invoke(activity)
        await { state() is PreviewCurrentDraftState.Current && state() !== retried }; finishWork()
        held.replay(); old.replay(); assertFalse(dialog().isShowing)
        val next = current(); assertNotEquals(retried.request.binding.rulesetHash, next.request.binding.rulesetHash)
        assertEquals(version, readVersion(fullVersion(select(context(detail(next)), 0))).first)
        dialog().cancel(); idle(); assertEquals(before, disk())
    }

    @Test fun savedReplacementAndDisableNeverRedirectOldContext() {
        val original = installModels(); val old = retained(original)
        val title = field<EditText>("title"); val beforeEdit = disk()
        title.setText("pending context edit")
        assertTrue(state() is PreviewCurrentDraftState.Unavailable); old.replay(); assertFalse(dialog().isShowing)
        assertEquals(beforeEdit, disk())
        button(R.string.save_draft).performClick(); finishWork(); await { state() is PreviewCurrentDraftState.Current }
        val changed = installModels(secondaryVersion = "exact-new-version")
        old.replay(); assertFalse(dialog().isShowing)
        assertTrue(changed.request.binding.sourceRevision > original.request.binding.sourceRevision)
        val selected = select(context(detail(changed)), 0)
        assertEquals("exact-new-version", readVersion(fullVersion(selected)).first)
        val beforeDisable = retained(changed, 1)
        val disabled = installModels(secondaryVersion = "exact-new-version", disableSecondary = true)
        beforeDisable.replay(); old.replay(); assertFalse(dialog().isShowing)
        val row = disabled.outcome.findings.single { it.ruleId == ruleId }
        assertEquals(listOf("sel_model_z_primary"), row.selectionIds)
        assertTrue(disabled.request.recheck.selectionIds.contains("sel_model_a_01"))
        val list = context(detail(disabled)); assertEquals(1, list.listView.adapter.count)
        assertTrue(list.listView.adapter.getItem(0).toString().contains("sel_model_z_primary"))
        list.cancel(); idle(); val after = disk(); old.replay(); beforeDisable.replay(); assertEquals(after, disk())
    }

    @Test fun storeSwitchPauseAndRecreationCannotRestoreContextAuthority() {
        val result = installModels(); val old = retained(result)
        button(R.string.library_new).performClick(); idle(); val confirmation = dialog()
        old.replay(); assertSame(confirmation, dialog())
        confirmation.getButton(AlertDialog.BUTTON_POSITIVE).performClick(); idle(); finishWork()
        await { state() is PreviewCurrentDraftState.Current }
        val switched = current(); assertNotEquals(result.request.binding.inputDigest, switched.request.binding.inputDigest)
        assertTrue(switched.outcome.findings.single { it.ruleId == ruleId }.selectionIds.isEmpty())
        val empty = context(detail(switched)); val afterSwitch = disk()
        controller.pause(); old.replay(); assertFalse(empty.isShowing); assertTrue(state() is PreviewCurrentDraftState.Unavailable)
        controller.resume(); finishWork(); await { state() is PreviewCurrentDraftState.Current }
        val resumed = current(); val live = context(detail(resumed))
        val savedState = Bundle()
        controller.pause().stop().saveInstanceState(savedState).destroy()
        controller = Robolectric.buildActivity(PreviewActivity::class.java).setup(savedState)
        finishWork(); assertTrue(activity.window.decorView.isAttachedToWindow)
        await { state() is PreviewCurrentDraftState.Current }
        live.getButton(AlertDialog.BUTTON_NEGATIVE).performClick(); old.replay(); assertFalse(dialog().isShowing)
        assertNotSame(resumed.request, current().request)
        val fresh = context(detail(current())); assertTrue(body(fresh).contains("no affected selection IDs"))
        fresh.cancel(); idle(); assertEquals(afterSwitch, disk())
    }

    @Test
    @Config(qualifiers = "ar-rEG-ldrtl-w320dp-h480dp")
    @GraphicsMode(GraphicsMode.Mode.NATIVE)
    fun narrowLargeTextKeepsContextActionMessageAndExistingButtonsReachable() {
        RuntimeEnvironment.setFontScale(1.6f)
        assertEquals(1.6f, activity.resources.configuration.fontScale, 0.001f)
        assertEquals(View.LAYOUT_DIRECTION_RTL, activity.resources.configuration.layoutDirection)
        val result = installModels(secondaryVersion = "geometry-version")
        val before = disk(); val finding = detail(result); val original = body(finding)
        val root = finding.window!!.decorView
        val density = activity.resources.displayMetrics.density
        val width = (320 * density).toInt(); val height = (440 * density).toInt()
        val targets = listOf(entry(finding), finding.getButton(AlertDialog.BUTTON_NEGATIVE),
            finding.getButton(AlertDialog.BUTTON_POSITIVE), finding.getButton(AlertDialog.BUTTON_NEUTRAL))
        fun geometry(label: String) {
            println("CONTEXT_GEOMETRY $label density=$density decor=${root.width}x${root.height}")
            targets.forEachIndexed { index, target ->
                println("CONTEXT_GEOMETRY action=$index text=${target.text} size=${target.width}x${target.height} " +
                    "measured=${target.measuredWidth}x${target.measuredHeight} minHeight=${target.minimumHeight} " +
                    "bounds=${target.left},${target.top},${target.right},${target.bottom}")
                generateSequence(target.parent as? View) { it.parent as? View }.forEach { parent ->
                    val params = parent.layoutParams
                    println("CONTEXT_GEOMETRY parent=${parent.javaClass.simpleName} id=${parent.id} " +
                        "size=${parent.width}x${parent.height} measured=${parent.measuredWidth}x${parent.measuredHeight} " +
                        "bounds=${parent.left},${parent.top},${parent.right},${parent.bottom} " +
                        "params=${params?.width}x${params?.height} weight=${(params as? android.widget.LinearLayout.LayoutParams)?.weight}")
                }
            }
        }
        root.measure(View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(height, View.MeasureSpec.EXACTLY))
        root.layout(0, 0, width, height); geometry("after explicit layout"); idle(); geometry("after idle")
        assertEquals(width, root.width); assertEquals(height, root.height)
        val visibleWindow = Rect(); assertTrue(root.getGlobalVisibleRect(visibleWindow))
        val heading = views(root).filterIsInstance<TextView>().single {
            it.text.toString() == activity.getString(R.string.current_check_inspect)
        }
        assertTrue("The original observation heading remains accessible", heading.isAccessibilityHeading)
        val visibleHeading = Rect(); assertTrue(heading.getGlobalVisibleRect(visibleHeading))
        assertEquals(heading.width, visibleHeading.width()); assertEquals(heading.height, visibleHeading.height())
        assertTrue(visibleWindow.contains(visibleHeading))
        val headingLayout = requireNotNull(heading.layout)
        assertTrue(headingLayout.lineCount > 0)
        assertTrue(headingLayout.height <= heading.height - heading.compoundPaddingTop - heading.compoundPaddingBottom)
        assertEquals(heading.text.length, headingLayout.getLineEnd(headingLayout.lineCount - 1))
        repeat(headingLayout.lineCount) { line ->
            assertEquals(0, headingLayout.getEllipsisCount(line))
            assertTrue(headingLayout.getLineLeft(line) >= heading.scrollX &&
                headingLayout.getLineRight(line) <= heading.scrollX + heading.width -
                    heading.compoundPaddingLeft - heading.compoundPaddingRight)
        }
        var nativeActionBarScrolled = false
        targets.forEach { target ->
            val actionScroll = generateSequence(target.parent as? View) { it.parent as? View }
                .filterIsInstance<ScrollView>().firstOrNull()
            val priorScroll = actionScroll?.scrollY
            target.requestRectangleOnScreen(Rect(0, 0, target.width, target.height), true); idle()
            if (actionScroll != null && actionScroll.scrollY != priorScroll) nativeActionBarScrolled = true
            println("CONTEXT_GEOMETRY reachable=${target.text} nativeScroll=$priorScroll->${actionScroll?.scrollY}")
            assertEquals("Revealing actions must not enlarge the viewport", width, root.width)
            assertEquals("Revealing actions must not enlarge the viewport", height, root.height)
            assertTrue("Each action retains a 48dp touch width", target.width >= (48 * density).toInt())
            assertTrue("Each action retains a 48dp touch height: ${target.text}, actual=${target.height}, density=$density",
                target.height >= (48 * density).toInt())
            val visible = Rect()
            assertTrue("Each action is inside the actual window viewport", target.getGlobalVisibleRect(visible))
            assertEquals("Action width is not clipped", target.width, visible.width())
            assertEquals("Action height is not clipped", target.height, visible.height())
            assertTrue("The entire action lies inside the visible decor", visibleWindow.contains(visible))
            val textLayout = requireNotNull(target.layout)
            assertTrue(textLayout.lineCount > 0)
            repeat(textLayout.lineCount) { assertEquals("Action labels are not ellipsized", 0, textLayout.getEllipsisCount(it)) }
            assertEquals("Every action label reaches its final character", target.text.length,
                textLayout.getLineEnd(textLayout.lineCount - 1))
            assertTrue("The complete label fits vertically inside its action",
                textLayout.height > 0 && textLayout.height <= target.height - target.compoundPaddingTop - target.compoundPaddingBottom)
            val left = target.scrollX.toFloat()
            val right = left + target.width - target.compoundPaddingLeft - target.compoundPaddingRight
            assertTrue(right > left)
            repeat(textLayout.lineCount) { line ->
                assertTrue("The complete label fits horizontally inside its action",
                    textLayout.getLineLeft(line) >= left && textLayout.getLineRight(line) <= right &&
                        textLayout.getLineLeft(line) <= textLayout.getLineRight(line))
            }
        }
        assertTrue("The constrained native action bar actually scrolls to reveal its actions", nativeActionBarScrolled)
        val message = finding.findViewById<TextView>(android.R.id.message)
        val scroll = generateSequence(message.parent as? View) { it.parent as? View }
            .filterIsInstance<ScrollView>().first()
        assertTrue("The original message retains a positive scrolling viewport", scroll.width > 0 && scroll.height > 0)
        val visibleScroll = Rect(); assertTrue(scroll.getGlobalVisibleRect(visibleScroll))
        assertTrue(visibleScroll.width() > 0 && visibleScroll.height() > 0)
        scroll.isSmoothScrollingEnabled = false
        scroll.fullScroll(View.FOCUS_DOWN); idle()
        assertTrue("The large-text fixture actually overflows its message viewport", scroll.scrollY > 0)
        val visibleMessage = Rect()
        assertTrue(message.getLocalVisibleRect(visibleMessage))
        assertEquals("The original message tail remains reachable", message.height, visibleMessage.bottom)
        assertEquals(original, body(finding))
        val selected = select(context(finding), 0)
        assertEquals("geometry-version", readVersion(fullVersion(selected)).first)
        val returned = back(back(back(dialog()), AlertDialog.BUTTON_POSITIVE))
        assertEquals(original, body(returned))
        dialog().cancel(); idle(); assertSame(result, state()); assertEquals(before, disk())
    }
}
