package ai.pickyour.android.preview

import android.app.AlertDialog
import android.graphics.Rect
import android.os.Bundle
import android.os.Looper
import android.os.Parcel
import android.os.Parcelable
import android.util.SparseArray
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.EditText
import android.widget.TextView
import ai.pickyour.core.drafts.*
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
import java.nio.file.Files
import java.security.MessageDigest
import java.util.concurrent.CountDownLatch
import java.util.concurrent.ExecutorService
import java.util.concurrent.TimeUnit

/** Simulated reference navigation and real local storage; no serving-target or installed-device claim. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
@LooperMode(LooperMode.Mode.PAUSED)
class DeploymentPlanningGuidesActivityTest {
    private lateinit var controller: ActivityController<PreviewActivity>
    private val activity get() = controller.get()
    private val modes = listOf(R.string.deployment_guide_api, R.string.deployment_guide_managed,
        R.string.deployment_guide_self_hosted, R.string.deployment_guide_hybrid)
    private val meanings = listOf(R.string.deployment_guide_api_meaning, R.string.deployment_guide_managed_meaning,
        R.string.deployment_guide_self_hosted_meaning, R.string.deployment_guide_hybrid_meaning)

    @Before fun launch() {
        controller = Robolectric.buildActivity(PreviewActivity::class.java).setup()
        await { button(R.string.save_draft).isEnabled && button(R.string.retry_catalog).isEnabled }
        finishWork()
        assertTrue(adapter().load() is PreviewDraftLoad.Missing)
    }
    @After fun close() { controller.pause().stop().destroy() }

    private fun views(root: View = activity.window.decorView): List<View> = buildList {
        add(root)
        if (root is ViewGroup) repeat(root.childCount) { addAll(views(root.getChildAt(it))) }
    }
    private fun button(label: Int) = views().filterIsInstance<Button>().single { it.text.toString() == activity.getString(label) }
    private fun field(label: Int): EditText {
        val caption = views().filterIsInstance<TextView>().single { it.text.toString() == activity.getString(label) }
        return activity.findViewById(caption.labelFor)
    }
    private fun guide() = views().filterIsInstance<DeploymentPlanningGuides>().single()
    private fun planning() = views().filterIsInstance<T6PlanningSection>().single()
    private fun action(mode: Int) = views(guide()).filterIsInstance<Button>().single {
        it.text.toString() in listOf(activity.getString(R.string.deployment_guide_open, activity.getString(mode)),
            activity.getString(R.string.deployment_guide_hide, activity.getString(mode)))
    }
    private fun visibleGuideText() = views(guide()).filterIsInstance<TextView>().filter { it.isShown }.map { it.text.toString() }
    private fun planningText() = views(planning()).filterIsInstance<TextView>().filter { it !in views(guide()) }.map { it.text.toString() }
    private fun assertExpanded(mode: Int?) {
        for ((title, meaning) in modes.zip(meanings)) {
            assertEquals(title == mode, action(title).isSelected)
            assertEquals(title == mode, visibleGuideText().contains(activity.getString(meaning)))
            assertEquals(activity.getString(if (title == mode) R.string.deployment_guide_hide else R.string.deployment_guide_open,
                activity.getString(title)), action(title).text.toString())
        }
        assertEquals(if (mode == null) 0 else 1, visibleGuideText().count { it == activity.getString(R.string.deployment_guide_shared_review) })
    }
    private fun idle() = shadowOf(Looper.getMainLooper()).idle()
    private fun await(condition: () -> Boolean) {
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(15)
        while (System.nanoTime() < deadline) {
            idle()
            if (condition()) return
            Thread.sleep(10)
        }
        fail("Timed out waiting for reference-guide UI state")
    }
    private fun worker() = PreviewActivity::class.java.getDeclaredField("worker").apply { isAccessible = true }.get(activity) as ExecutorService
    private fun finishWork() { repeat(2) { idle(); worker().submit {}.get(15, TimeUnit.SECONDS); idle() } }
    private fun adapter() = PreviewDraftLibrary(activity.noBackupFilesDir.toPath()).openLegacy()
    private fun loaded() = (adapter().load() as PreviewDraftLoad.Available).record
    private fun disk() = Files.walk(activity.noBackupFilesDir.toPath()).use { paths ->
        paths.filter { Files.isRegularFile(it) }.toList().associate {
            activity.noBackupFilesDir.toPath().relativize(it).toString() to MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(it)).toList()
        }
    }
    private fun save() {
        button(R.string.save_draft).performClick()
        await { !button(R.string.save_draft).isEnabled && views().filterIsInstance<TextView>().any {
            it.text.toString() == activity.getString(R.string.draft_saved)
        } }
        finishWork()
    }
    private fun reopen() {
        button(R.string.reopen_draft).performClick()
        requireNotNull(ShadowAlertDialog.getLatestAlertDialog()).getButton(AlertDialog.BUTTON_POSITIVE).performClick()
    }
    private fun roundTrip(state: Bundle): Bundle {
        val written = Parcel.obtain()
        val read = Parcel.obtain()
        return try {
            written.writeBundle(state)
            val bytes = written.marshall()
            read.unmarshall(bytes, 0, bytes.size)
            read.setDataPosition(0)
            requireNotNull(read.readBundle(javaClass.classLoader))
        } finally { written.recycle(); read.recycle() }
    }
    private fun recreate() {
        val state = Bundle()
        controller.pause().saveInstanceState(state).stop().destroy()
        val restored = roundTrip(state)
        controller = Robolectric.buildActivity(PreviewActivity::class.java).create(restored).start().restoreInstanceState(restored).resume().visible()
        finishWork()
    }
    @Suppress("DEPRECATION")
    private fun referenceState(): Bundle {
        val hierarchy = SparseArray<Parcelable>().also { guide().saveHierarchyState(it) }
        assertEquals(1, hierarchy.size())
        val state = requireNotNull(hierarchy.get(R.id.deployment_planning_guides) as? Bundle)
        assertEquals(setOf("parent", "format", "reference"), state.keySet())
        return roundTrip(state)
    }
    private fun restoreReference(state: Bundle) {
        guide().restoreHierarchyState(SparseArray<Parcelable>().apply { put(R.id.deployment_planning_guides, roundTrip(state)) })
    }

    @Test fun allFourReferencesOpenBeforeFirstSaveAndWithEveryOptionalTierDisabled() {
        assertExpanded(null)
        val unsaved = disk()
        assertTrue(planningText().contains(activity.getString(R.string.planning_unsaved)))
        for (mode in modes) {
            action(mode).performClick()
            assertExpanded(mode)
            assertTrue(adapter().load() is PreviewDraftLoad.Missing)
            assertEquals(unsaved, disk())
            assertEquals(5, planningText().count { it == activity.getString(R.string.planning_choices_unavailable) })
        }
        save()
        val saved = loaded()
        val retained = disk()
        assertTrue(saved.optionalTiers.values.all { it.activation == Activation.DISABLED })
        for (mode in modes.reversed()) {
            if (action(mode).isSelected) action(mode).performClick()
            action(mode).performClick()
            assertExpanded(mode)
            assertTrue(planningText().contains(activity.getString(R.string.planning_saved, saved.revision.toString())))
            assertEquals(5, planningText().count { it == activity.getString(R.string.planning_choices_missing) })
            assertEquals(saved.baselineIdentity, loaded().baselineIdentity)
            assertEquals(saved.optionalTiers, loaded().optionalTiers)
            assertEquals(retained, disk())
            assertNull(shadowOf(activity).nextStartedActivity)
        }
        action(modes.first()).performClick()
        assertExpanded(null)
    }

    @Test fun referencesPreserveExactSelectionsPrivateInputsCatalogContextAndStoredHistory() {
        field(R.string.draft_title).setText("Private title 52")
        field(R.string.context_objective).setText("private objective\nلا تنشر")
        field(R.string.context_context).setText("synthetic-private-context-52")
        save()
        val store = LocalDraftStore(activity.noBackupFilesDir.toPath().resolve("guest-draft"))
        val before = (store.load() as DraftLoad.Available).draft
        val entries = activity.assets.open("build.json").bufferedReader().use { JSONObject(it.readText()).getJSONArray("selections") }
        val selected = (0 until entries.length()).map { entries.getJSONObject(it) }
            .filter { it.getString("kind") in setOf("deployment_mode", "monitoring_stack") }
            .map { node ->
                if (node.getString("kind") == "monitoring_stack")
                    node.put("settings", JSONObject("""{"credential_ref":"secret://synthetic/private:52"}"""))
                LocalSelection.fromJson(node.toString().toByteArray())
            }
        assertEquals(2, selected.size)
        assertTrue(store.save(before.version, before.content.withComponents(DraftSelections(selected))) is DraftSave.Saved)
        reopen()
        finishWork()
        val record = loaded()
        val retained = disk()
        val catalog = views().filterIsInstance<CatalogBrowseSection>().single()
        val catalogBefore = Bundle().also { catalog.saveState(it) }
        for (mode in modes) {
            action(mode).performClick()
            assertExpanded(mode)
            val entireReference = views(guide()).filterIsInstance<TextView>().joinToString("\n") { it.text.toString() }
            for (privateText in listOf("Private title 52", "private objective", "لا تنشر", "synthetic-private-context-52", "secret://synthetic/private:52"))
                assertFalse(entireReference.contains(privateText))
            assertEquals(record.baselineIdentity, loaded().baselineIdentity)
            assertEquals(selected, loaded().components.selections)
            assertEquals(record.fields, loaded().fields)
            assertEquals(record.contextInput, loaded().contextInput)
            assertEquals(record.workloadInput, loaded().workloadInput)
            assertEquals(record.optionalTiers, loaded().optionalTiers)
            assertEquals(record.history.map { it.revision }, loaded().history.map { it.revision })
            assertEquals(retained, disk())
            val catalogAfter = Bundle().also { catalog.saveState(it) }
            assertEquals(catalogBefore.keySet(), catalogAfter.keySet())
            for (key in catalogBefore.keySet()) assertEquals(catalogBefore.get(key), catalogAfter.get(key))
            assertNull(shadowOf(activity).nextStartedActivity)
        }
        assertEquals("Private title 52", field(R.string.draft_title).text.toString())
        assertEquals("private objective\nلا تنشر", field(R.string.context_objective).text.toString())
        assertTrue(visibleGuideText().contains(activity.getString(R.string.deployment_guide_hybrid_steps)))
        assertTrue(visibleGuideText().contains(activity.getString(R.string.deployment_guide_hybrid_limits)))
    }

    @Test fun openingReferencesNeverReplacesAcknowledgedCountsWithPendingEditorValues() {
        save()
        val before = loaded()
        val retained = disk()
        field(R.string.draft_title).setText("pending reference review")
        field(R.string.context_context).setText("pending context remains private")
        val source = planningText()
        assertTrue(source.contains(activity.getString(R.string.planning_pending, before.revision.toString())))
        for (mode in modes) {
            action(mode).performClick()
            assertExpanded(mode)
            assertEquals(source, planningText())
            assertTrue(button(R.string.save_draft).isEnabled)
            assertEquals(before.baselineIdentity, loaded().baselineIdentity)
            assertEquals(retained, disk())
        }
        assertEquals("pending reference review", field(R.string.draft_title).text.toString())
        assertEquals("pending context remains private", field(R.string.context_context).text.toString())
    }

    @Test fun aBlockedDraftReadWithholdsCountsWhileReferenceNavigationStillWorks() {
        save()
        val before = loaded()
        val retained = disk()
        val started = CountDownLatch(1)
        val release = CountDownLatch(1)
        worker().submit { started.countDown(); release.await(15, TimeUnit.SECONDS) }
        assertTrue(started.await(15, TimeUnit.SECONDS))
        try {
            reopen()
            idle()
            val pending = planningText()
            assertTrue(pending.contains(activity.getString(R.string.planning_loading)))
            assertEquals(5, pending.count { it == activity.getString(R.string.planning_choices_unavailable) })
            action(R.string.deployment_guide_self_hosted).performClick()
            assertExpanded(R.string.deployment_guide_self_hosted)
            assertEquals(pending, planningText())
            assertEquals(before.baselineIdentity, loaded().baselineIdentity)
            assertEquals(retained, disk())
        } finally { release.countDown() }
        finishWork()
        assertExpanded(R.string.deployment_guide_self_hosted)
        assertTrue(planningText().contains(activity.getString(R.string.planning_saved, before.revision.toString())))
        assertEquals(retained, disk())
    }

    @Test fun parcelRecreationRestoresOnlyTheOpenReferenceAndRetiresOldButtons() {
        save()
        field(R.string.draft_title).setText("pending survives independently")
        action(R.string.deployment_guide_managed).performClick()
        val state = referenceState()
        assertEquals(1, state.getInt("format"))
        assertEquals("managed", state.getString("reference"))
        val oldButton = action(R.string.deployment_guide_api)
        val oldGuide = guide()
        val oldTexts = views(oldGuide).filterIsInstance<TextView>().map { it.text.toString() to it.visibility }
        val before = loaded()
        val retained = disk()
        recreate()
        assertNotSame(oldGuide, guide())
        assertExpanded(R.string.deployment_guide_managed)
        assertEquals("pending survives independently", field(R.string.draft_title).text.toString())
        assertTrue(planningText().contains(activity.getString(R.string.planning_pending, before.revision.toString())))
        oldButton.callOnClick()
        finishWork()
        assertExpanded(R.string.deployment_guide_managed)
        assertEquals(oldTexts, views(oldGuide).filterIsInstance<TextView>().map { it.text.toString() to it.visibility })
        assertEquals(before.baselineIdentity, loaded().baselineIdentity)
        assertEquals(retained, disk())
        assertNull(shadowOf(activity).nextStartedActivity)
    }

    @Test fun aNewerDiskHeadDoesNotTurnTheRestoredGuideIntoAcknowledgedPlanning() {
        save()
        val before = loaded()
        field(R.string.draft_title).setText("unreconciled pending input")
        action(R.string.deployment_guide_hybrid).performClick()
        val newer = (adapter().save(before, before.fields.copy(title = "separate writer")) as PreviewDraftSave.Saved).record
        val retained = disk()
        recreate()
        val unreconciled = planningText()
        assertTrue(unreconciled.contains(activity.getString(R.string.planning_unavailable)))
        assertEquals(5, unreconciled.count { it == activity.getString(R.string.planning_choices_unavailable) })
        assertExpanded(R.string.deployment_guide_hybrid)
        action(R.string.deployment_guide_api).performClick()
        assertExpanded(R.string.deployment_guide_api)
        assertEquals(unreconciled, planningText())
        assertEquals("unreconciled pending input", field(R.string.draft_title).text.toString())
        assertEquals(newer.baselineIdentity, loaded().baselineIdentity)
        assertEquals(retained, disk())
    }

    @Test fun futureReferenceKeysOrFormatsRestoreCollapsedWithoutChoosingADraftMode() {
        save()
        val before = loaded()
        val retained = disk()
        action(R.string.deployment_guide_managed).performClick()
        val valid = referenceState()
        for (state in listOf(Bundle(valid).apply { putString("reference", "future-serving-reference") },
            Bundle(valid).apply { putInt("format", 2) },
            Bundle(valid).apply { remove("reference") })) {
            restoreReference(state)
            assertExpanded(null)
            assertEquals(before.baselineIdentity, loaded().baselineIdentity)
            assertEquals(retained, disk())
            action(R.string.deployment_guide_hybrid).performClick()
            assertExpanded(R.string.deployment_guide_hybrid)
        }
        restoreReference(valid)
        assertExpanded(R.string.deployment_guide_managed)
        assertEquals(retained, disk())
    }

    @Test fun detachedReferenceButtonsCannotMutateTheirRetiredViewOrTheDraft() {
        save()
        action(R.string.deployment_guide_api).performClick()
        val reference = guide()
        val stale = action(R.string.deployment_guide_self_hosted)
        val parent = reference.parent as ViewGroup
        val retained = disk()
        val source = planningText()
        val before = views(reference).filterIsInstance<TextView>().map { Triple(it.text.toString(), it.visibility, it.isSelected) }
        parent.removeView(reference)
        try {
            assertFalse(reference.isAttachedToWindow)
            stale.callOnClick()
            assertEquals(before, views(reference).filterIsInstance<TextView>().map { Triple(it.text.toString(), it.visibility, it.isSelected) })
            assertEquals(retained, disk())
            assertNull(shadowOf(activity).nextStartedActivity)
        } finally { parent.addView(reference) }
        assertExpanded(R.string.deployment_guide_api)
        assertEquals(source, planningText())
        stale.performClick()
        assertExpanded(R.string.deployment_guide_self_hosted)
        assertEquals(retained, disk())
    }


    @Test fun explicitReferenceActionsCancelPendingCatalogRestorationWithoutChangingItsFilters() {
        save()
        val record = loaded()
        val retained = disk()
        for (accessibility in listOf(false, true)) {
            val extra = Robolectric.buildActivity(android.app.Activity::class.java).setup()
            val owner = extra.get()
            try {
                val hash = "a".repeat(64)
                val state = Bundle().apply {
                    putInt("catalogViewportFormat", 1)
                    putString("catalogViewportHash", hash)
                    putString("catalogViewportId", "guide-row-2")
                    putString("catalogViewportVersion", "v1")
                    putInt("catalogViewportOffset", -17)
                }
                val items = (0..5).map { PreviewCatalogItem("guide-row-$it", "Synthetic guide row $it", "model", "v1", "T1") }
                val catalog = CatalogBrowseSection(owner, state) { _, _ -> fail("Reference navigation must not select a row") }
                val scroll = CatalogViewportScrollView(owner).apply { onUserNavigation = catalog::cancelViewportRestore }
                val content = android.widget.LinearLayout(owner).apply { orientation = android.widget.LinearLayout.VERTICAL }
                content.addView(View(owner), android.widget.LinearLayout.LayoutParams(-1, 600))
                val section = T6PlanningSection(owner, { !owner.isDestroyed }, catalog::cancelViewportRestore) { kind ->
                    assertTrue(catalog.browseKind(kind.catalogKind))
                }
                content.addView(section)
                content.addView(catalog)
                content.addView(View(owner), android.widget.LinearLayout.LayoutParams(-1, 900))
                scroll.addView(content)
                owner.setContentView(scroll)
                catalog.attachViewport(scroll)
                catalog.show(PreviewCatalogBrowser(hash, items))
                catalog.selectionEnabled(false)
                fun layout() {
                    scroll.measure(View.MeasureSpec.makeMeasureSpec(480, View.MeasureSpec.EXACTLY),
                        View.MeasureSpec.makeMeasureSpec(320, View.MeasureSpec.EXACTLY))
                    scroll.layout(0, 0, 480, 320)
                }
                layout()
                scroll.scrollTo(0, 111)
                val reference = views(section).filterIsInstance<DeploymentPlanningGuides>().single()
                val control = views(reference).filterIsInstance<Button>().single {
                    it.text.toString() == owner.getString(R.string.deployment_guide_open, owner.getString(R.string.deployment_guide_api))
                }
                assertTrue(if (accessibility)
                    control.performAccessibilityAction(android.view.accessibility.AccessibilityNodeInfo.ACTION_CLICK, null)
                    else control.performClick())
                assertTrue(control.isSelected)
                layout()
                val position = scroll.scrollY
                val row = views(catalog).filterIsInstance<Button>().single {
                    it.text.toString() == owner.getString(R.string.item_button, items[2].name, items[2].kind)
                }
                val rowPoint = IntArray(2)
                val viewPoint = IntArray(2)
                row.getLocationInWindow(rowPoint)
                scroll.getLocationInWindow(viewPoint)
                assertNotEquals("The old catalog anchor must differ from explicit reference navigation",
                    position, position + rowPoint[1] - viewPoint[1] + 17)
                catalog.selectionEnabled(true)
                scroll.viewTreeObserver.dispatchOnPreDraw()
                assertEquals("Reference action accessibility=$accessibility supersedes delayed catalog restoration", position, scroll.scrollY)
                val after = Bundle().also(catalog::saveState)
                assertEquals("", after.getString("catalogQuery"))
                assertNull(after.getString("catalogKind"))
                assertNull(after.getString("catalogTier"))
                assertEquals(0, after.getInt("catalogPage"))
                assertEquals(record.baselineIdentity, loaded().baselineIdentity)
                assertEquals(retained, disk())
                assertNull(shadowOf(owner).nextStartedActivity)
                catalog.close()
            } finally { extra.pause().stop().destroy() }
        }
    }

    @Test
    @Config(qualifiers = "ar-rEG-ldrtl-w320dp-h640dp")
    @GraphicsMode(GraphicsMode.Mode.NATIVE)
    fun narrowRtlLargeTextKeepsAllGuideButtonsAndEveryExpandedTextLineReachable() {
        RuntimeEnvironment.setFontScale(2f)
        recreate()
        val root = activity.window.decorView
        fun measure() {
            val density = activity.resources.displayMetrics.density
            root.measure(View.MeasureSpec.makeMeasureSpec((320 * density).toInt(), View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec((640 * density).toInt(), View.MeasureSpec.EXACTLY))
            root.layout(0, 0, (320 * density).toInt(), (640 * density).toInt())
        }
        fun reachable(control: View, target: Rect = Rect(0, 0, control.width, control.height)) {
            control.requestRectangleOnScreen(Rect(target), true)
            val bounds = Rect(target)
            var child = control
            while (child !== root) {
                val parent = child.parent as ViewGroup
                parent.offsetDescendantRectToMyCoords(child, bounds)
                val viewport = Rect().also(parent::getDrawingRect)
                assertTrue("Guide " + control.javaClass.simpleName + " target=$target must be reachable: bounds=$bounds viewport=$viewport", viewport.contains(bounds))
                child = parent
            }
        }
        measure()
        assertEquals(View.LAYOUT_DIRECTION_RTL, root.layoutDirection)
        val retained = disk()
        for (mode in modes) {
            val control = action(mode)
            val minimum = (48 * activity.resources.displayMetrics.density).toInt()
            assertTrue(control.width >= minimum)
            assertTrue(control.height >= minimum)
            reachable(control)
            control.performClick()
            measure()
            assertExpanded(mode)
            val visible = views(guide()).filterIsInstance<TextView>().filter { it.isShown }
            for (text in visible) {
                val layout = requireNotNull(text.layout)
                assertEquals(text.text.length, layout.getLineEnd(layout.lineCount - 1))
                for (line in 0 until layout.lineCount) {
                    assertEquals(0, layout.getEllipsisCount(line))
                    reachable(text, Rect(0, text.totalPaddingTop + layout.getLineTop(line), text.width,
                        text.totalPaddingTop + layout.getLineBottom(line)))
                }
            }
            for (label in listOf(R.string.deployment_guide_steps, R.string.deployment_guide_observation, R.string.deployment_guide_limits)) {
                val heading = visible.single { it.text.toString() == activity.getString(label) }
                assertTrue(heading.isAccessibilityHeading)
            }
            assertEquals(retained, disk())
            assertNull(shadowOf(activity).nextStartedActivity)
        }
    }
}
