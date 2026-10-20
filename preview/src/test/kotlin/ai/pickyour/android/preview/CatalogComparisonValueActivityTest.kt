package ai.pickyour.android.preview

import android.app.AlertDialog
import android.graphics.Rect
import android.os.Bundle
import android.os.SystemClock
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.CheckBox
import android.widget.EditText
import android.widget.TextView
import ai.pickyour.core.preview.PreviewCatalogBrowser
import ai.pickyour.core.preview.PreviewCatalogComparison
import ai.pickyour.core.preview.PreviewCatalogDetails
import ai.pickyour.core.preview.PreviewDraftLibrary
import ai.pickyour.core.preview.PreviewDraftLoad
import ai.pickyour.core.preview.PreviewDraftId
import ai.pickyour.core.preview.PreviewLibraryCreate
import ai.pickyour.core.preview.PreviewLibraryRead
import java.io.IOException
import java.nio.file.Path
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

/** Real preview comparison controls in a simulation; no installed-device or source verification claim. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
@LooperMode(LooperMode.Mode.PAUSED)
class CatalogComparisonValueActivityTest {
    private lateinit var controller: ActivityController<PreviewActivity>
    private val activity get() = controller.get()
    private lateinit var baseline: String
    private var pendingSince = 0L

    @Before fun launch() {
        controller = Robolectric.buildActivity(PreviewActivity::class.java).setup()
        await { button(R.string.save_draft).isEnabled && button(R.string.catalog_next).isEnabled }
        field(R.string.draft_title).setText("acknowledged complete comparison")
        button(R.string.save_draft).performClick()
        await { !button(R.string.save_draft).isEnabled && views().filterIsInstance<TextView>()
            .any { it.text.toString() == activity.getString(R.string.draft_saved) } }
        baseline = loaded().baselineIdentity
        AutosaveUiClock.pause()
        pendingSince = SystemClock.uptimeMillis()
        field(R.string.context_objective).setText("pending comparison objective")
        field(R.string.workload_budget).setText("12.00")
    }

    @After fun close() { controller.pause().stop().destroy() }

    @Test fun longValuesRoundTripBothVersionsAndReturnToTheDifferencesFilter() {
        val prefix = "same recorded prefix ".repeat(25)
        val pair = preparePair { first, second ->
            first.getJSONObject("license").put("spdx_id", prefix + " first exact tail")
            second.getJSONObject("license").put("spdx_id", prefix + " second exact tail")
        }
        val comparison = openComparison()
        assertFalse(texts().any { it.contains("first exact tail") || it.contains("second exact tail") })
        assertTrue(texts().any { it.contains(activity.getString(R.string.catalog_detail_truncated, "")) })
        val oldToggle = views(comparison.window!!.decorView).filterIsInstance<CheckBox>().single()
        oldToggle.performClick(); idle()
        val before = state()
        val expected = pair.second.map { detail -> detail.license.single { it.name == "spdx_id" }.valueJson!! }
        assertNotEquals(expected[0], expected[1])
        assertTrue(expected.all { it.length > 320 })
        inspect(1, "spdx_id")
        assertCaptured(pair.second[0], "spdx_id")
        assertTrue(texts().contains(activity.getString(R.string.catalog_compare_different)))
        assertEquals(expected[0], completeValue())
        clickText(activity.getString(R.string.catalog_compare_other_value, 2))
        assertCaptured(pair.second[1], "spdx_id")
        assertEquals(expected[1], completeValue())
        click(R.string.catalog_compare_back_values)
        val returned = dialog()
        assertNotSame(comparison, returned)
        assertTrue(views(returned.window!!.decorView).filterIsInstance<CheckBox>().single().isChecked)
        oldToggle.isChecked = false; idle()
        assertTrue(state().getBoolean("comparisonDifferencesOnly"))
        assertStable(before)
        assertNull(shadowOf(activity).nextStartedActivity)
    }

    @Test fun completeValuePartsPreserveUnicodeEscapesAndCollectionOrderWithoutLinks() {
        val raw = "a".repeat(157) + "\uD83D\uDE00" + "\n\r\t\\\"\u2028\u2029\u202e" +
            "https://example.invalid/" + "z".repeat(450)
        val pair = preparePair("model_provider") { first, second ->
            first.getJSONObject("spec").put("supported_regions", JSONArray().put(raw).put("last"))
            second.getJSONObject("spec").put("supported_regions", JSONArray().put("last").put(raw))
        }
        openComparison()
        val before = state()
        inspect(1, "supported_regions")
        val parts = mutableListOf<String>()
        val first = completeValue(parts)
        assertEquals(pair.second[0].specs.single { it.name == "supported_regions" }.valueJson, first)
        assertTrue(parts.size > 3)
        assertTrue(parts.joinToString("").contains("\\u2028"))
        assertTrue(parts.joinToString("").contains("\\u2029"))
        assertTrue(parts.joinToString("").contains("\\u202e"))
        assertTrue(parts.joinToString("").contains("\\ud83d"))
        assertTrue(parts.joinToString("").contains("\\ude00"))
        clickText(activity.getString(R.string.catalog_compare_other_value, 2))
        val second = completeValue()
        assertEquals(pair.second[1].specs.single { it.name == "supported_regions" }.valueJson, second)
        assertNotEquals(first, second)
        assertStable(before)
        assertNull(shadowOf(activity).nextStartedActivity)
    }

    @Test fun missingUnknownEmptyFalseAndZeroKeepTheirRecordedStates() {
        preparePair("model_provider") { first, second ->
            first.getJSONObject("spec").put("supports_fine_tuning", false).put("supported_regions", JSONArray())
            second.getJSONObject("spec").remove("supports_fine_tuning")
            second.getJSONObject("spec").put("supported_regions", JSONArray().put("declared").put("unknown"))
        }
        openComparison()
        val before = state()
        inspect(1, "supports_fine_tuning")
        assertEquals("false", completeValue())
        assertTrue(texts().contains(activity.getString(R.string.catalog_detail_browser_supplied)))
        clickText(activity.getString(R.string.catalog_compare_other_value, 2))
        assertEquals(activity.getString(R.string.catalog_detail_missing), literal())
        assertTrue(texts().contains(activity.getString(R.string.catalog_compare_unknown)))
        click(R.string.catalog_compare_back_values)
        inspect(1, "supported_regions")
        assertEquals("[]", completeValue())
        assertTrue(texts().contains(activity.getString(R.string.catalog_detail_empty_collection)))
        clickText(activity.getString(R.string.catalog_compare_other_value, 2))
        assertEquals("[\"declared\",\"unknown\"]", completeValue())
        assertTrue(texts().contains(activity.getString(R.string.catalog_detail_browser_contains_unknown)))
        assertStable(before)

        clearPins()
        preparePair("workload") { first, second ->
            first.getJSONObject("spec").put("requests_per_day", 0)
            second.getJSONObject("spec").put("requests_per_day", 1)
        }
        openComparison()
        val numericBefore = state()
        inspect(1, "requests_per_day")
        assertEquals("0", completeValue())
        assertTrue(texts().contains(activity.getString(R.string.catalog_detail_browser_supplied)))
        clickText(activity.getString(R.string.catalog_compare_other_value, 2))
        assertEquals("1", completeValue())
        assertTrue(texts().contains(activity.getString(R.string.catalog_compare_different)))
        assertStable(numericBefore)

        clearPins()
        preparePair("compute_profile") { first, second ->
            first.getJSONObject("spec").put("region", "unknown")
            second.getJSONObject("spec").put("region", "declared-region")
        }
        openComparison()
        val unknownBefore = state()
        inspect(1, "region")
        assertEquals("\"unknown\"", completeValue())
        assertTrue(texts().contains(activity.getString(R.string.catalog_detail_explicit_unknown)))
        assertStable(unknownBefore)
    }

    @Test fun differentKindsDoNotBorrowValuesForAnUndefinedField() {
        pinKind("model")
        pinKind("model_provider")
        openComparison()
        val before = state()
        inspect(2, "min_weights_vram_gb")
        assertTrue(texts().contains(activity.getString(R.string.catalog_compare_not_defined)))
        assertTrue(texts().contains(activity.getString(R.string.catalog_compare_not_comparable)))
        assertEquals(activity.getString(R.string.catalog_detail_missing), literal())
        clickText(activity.getString(R.string.catalog_compare_other_value, 1))
        assertEquals("12", completeValue())
        assertStable(before)
    }

    @Test fun replacedAndCancelledValueButtonsCannotReplaceTheirCurrentOwner() {
        prepareLongPair()
        openComparison()
        val before = state()
        val parent = dialog()
        val oldEntry = inspectButton(1, "spdx_id")
        oldEntry.performClick(); idle()
        val first = dialog()
        val oldNext = button(R.string.catalog_detail_browser_next_part, first.window!!.decorView)
        val oldBack = button(R.string.catalog_compare_back_values, first.window!!.decorView)
        val oldOther = buttonText(activity.getString(R.string.catalog_compare_other_value, 2), first.window!!.decorView)
        oldNext.performClick(); idle()
        val current = dialog()
        assertNotSame(first, current)
        listOf(oldEntry, oldNext, oldBack, oldOther).forEach { it.performClick(); idle() }
        parent.getButton(AlertDialog.BUTTON_POSITIVE).performClick(); idle()
        assertSame(current, dialog())
        assertTrue(current.isShowing)
        current.cancel(); idle()
        listOf(oldNext, oldBack, oldOther).forEach { it.performClick(); idle() }
        assertSame(current, dialog())
        assertFalse(current.isShowing)
        openComparison()
        inspect(1, "spdx_id")
        assertTrue(dialog().isShowing)
        assertNotSame(current, dialog())
        assertStable(before)
    }

    @Test fun catalogReplacementAndPinRemovalRetireCompleteValueNavigation() {
        val pair = prepareLongPair()
        openComparison()
        inspect(1, "spdx_id")
        val old = dialog()
        val oldBack = button(R.string.catalog_compare_back_values, old.window!!.decorView)
        val root = document().put("revision", 99)
        showCatalog(root)
        assertFalse(old.isShowing)
        assertFalse(button(R.string.catalog_compare_open).isEnabled)
        val before = state()
        oldBack.performClick(); idle()
        assertSame(old, dialog())
        assertStable(before)
        showCatalog(pair.first)
        openComparison()
        inspect(1, "spdx_id")
        val restored = dialog()
        val restoredBack = button(R.string.catalog_compare_back_values, restored.window!!.decorView)
        removeFirstPin()
        assertFalse(restored.isShowing)
        assertFalse(button(R.string.catalog_compare_open).isEnabled)
        val afterRemoval = state()
        oldBack.performClick(); restoredBack.performClick(); idle()
        assertSame(restored, dialog())
        assertStable(afterRemoval)
    }

    @Test fun detailAndStorageEntryRetireCompleteValueCallbacks() {
        prepareLongPair()
        openComparison()
        inspect(1, "spdx_id")
        val value = dialog()
        val back = button(R.string.catalog_compare_back_values, value.window!!.decorView)
        samples().first().performClick(); idle()
        val detail = dialog()
        assertFalse(value.isShowing)
        val before = state()
        back.performClick(); idle()
        assertSame(detail, dialog())
        assertStable(before)
        openComparison()
        inspect(2, "spdx_id")
        val storageValue = dialog()
        val storageBack = button(R.string.catalog_compare_back_values, storageValue.window!!.decorView)
        button(R.string.reopen_draft).performClick(); idle()
        val confirmation = dialog()
        assertFalse(storageValue.isShowing)
        storageBack.performClick(); back.performClick(); idle()
        assertSame(confirmation, dialog())
        assertTrue(confirmation.isShowing)
        assertStable(before)
    }

    @Test fun privacyDisclosureRetiresCompleteValueCallbacks() {
        assertStoragePresentationRetiresValue {
            activity.findViewById<Button>(R.id.privacy_open).performClick()
            assertNotNull(dialog().findViewById<View>(R.id.privacy_scroll))
        }
    }

    @Test fun historyDetailRetiresCompleteValueCallbacks() {
        val picker = historyPicker()
        assertStoragePresentationRetiresValue {
            assertTrue(picker.isShowing)
            shadowOf(picker).clickOnItem(0)
        }
    }

    @Test fun domainRestoreConfirmationRetiresCompleteValueCallbacks() {
        val picker = historyPicker()
        shadowOf(picker).clickOnItem(0); idle()
        val detail = dialog()
        assertNotSame(picker, detail)
        assertTrue(detail.isShowing)
        val restore = detail.getButton(AlertDialog.BUTTON_NEUTRAL)
        assertEquals(activity.getString(R.string.domain_restore_preview), restore.text.toString())
        assertStoragePresentationRetiresValue {
            assertTrue(detail.isShowing)
            restore.performClick(); idle()
            assertEquals(activity.getString(R.string.domain_restore_commit),
                dialog().getButton(AlertDialog.BUTTON_POSITIVE).text.toString())
        }
    }

    @Test fun newDraftConfirmationRetiresCompleteValueCallbacks() {
        assertStoragePresentationRetiresValue { button(R.string.library_new).performClick() }
    }

    @Test fun duplicateConfirmationRetiresCompleteValueCallbacks() {
        assertStoragePresentationRetiresValue { button(R.string.library_duplicate).performClick() }
    }

    @Test fun recoveryCopyConfirmationRetiresCompleteValueCallbacks() {
        assertStoragePresentationRetiresValue { button(R.string.recovery_copy).performClick() }
    }

    @Test fun libraryEntryRetiresCompleteValueCallbacks() {
        val picker = libraryPicker()
        val index = libraryIndex(picker, PreviewDraftId("guest-draft"))
        assertStoragePresentationRetiresValue {
            assertFalse(picker.isShowing)
            val value = dialog()
            shadowOf(picker).clickOnItem(index); idle()
            assertSame(value, dialog())
            assertTrue(value.isShowing)
            val fresh = libraryPicker()
            assertNotSame(picker, fresh)
            shadowOf(fresh).clickOnItem(libraryIndex(fresh, PreviewDraftId("guest-draft")))
        }
    }

    @Test fun deleteConfirmationRetiresCompleteValueCallbacks() {
        val entry = libraryEntry(PreviewDraftId("guest-draft"))
        assertStoragePresentationRetiresValue {
            assertFalse(entry.isShowing)
            val value = dialog()
            entry.getButton(AlertDialog.BUTTON_NEUTRAL).performClick(); idle()
            assertSame(value, dialog())
            assertTrue(value.isShowing)
            val fresh = libraryEntry(PreviewDraftId("guest-draft"))
            assertNotSame(entry, fresh)
            fresh.getButton(AlertDialog.BUTTON_NEUTRAL).performClick()
        }
    }

    @Test fun pendingDeletionRetryRetiresCompleteValueCallbacks() {
        val root = activity.noBackupFilesDir.toPath()
        val second = (PreviewDraftLibrary(root).create() as PreviewLibraryCreate.Saved).id
        var faultReached = false
        failFinalCleanup { faultReached = true }
        val entry = libraryEntry(second)
        entry.getButton(AlertDialog.BUTTON_NEUTRAL).performClick(); idle()
        val confirmation = dialog()
        assertNotSame(entry, confirmation)
        assertTrue(confirmation.isShowing)
        assertEquals(activity.getString(R.string.library_delete_warning, second.value),
            shadowOf(confirmation).message.toString())
        confirmation.getButton(AlertDialog.BUTTON_POSITIVE).performClick()
        await { button(R.string.library_open).isEnabled &&
            shadowOf(dialog()).message.toString() == activity.getString(R.string.library_delete_unconfirmed) }
        assertTrue("The actual deletion must reach the injected final acknowledgement failure", faultReached)
        assertFalse((PreviewDraftLibrary(root).list() as PreviewLibraryRead.Available).entries.any { it.id == second })
        assertEquals(second.value, state().getString("pendingDeleteId"))
        assertEquals(baseline, loaded().baselineIdentity)
        dialog().getButton(AlertDialog.BUTTON_POSITIVE).performClick(); idle()
        assertStoragePresentationRetiresValue {
            assertTrue(button(R.string.library_retry_delete).isEnabled)
            button(R.string.library_retry_delete).performClick()
            assertEquals(activity.getString(R.string.library_retry_warning, second.value),
                shadowOf(dialog()).message.toString())
        }
        assertEquals(second.value, state().getString("pendingDeleteId"))
    }

    @Test fun recreationKeepsTheExactTrayButRejectsAllOldValueControls() {
        val pair = prepareLongPair()
        openComparison()
        inspect(1, "spdx_id")
        val old = dialog()
        val back = button(R.string.catalog_compare_back_values, old.window!!.decorView)
        val next = button(R.string.catalog_detail_browser_next_part, old.window!!.decorView)
        val before = state()
        controller.pause().stop().destroy()
        assertFalse(old.isShowing)
        controller = Robolectric.buildActivity(PreviewActivity::class.java).create(before).start().resume().visible()
        await { button(R.string.reopen_draft).isEnabled && samples().isNotEmpty() }
        back.performClick(); next.performClick(); idle()
        assertSame(old, dialog())
        for (key in listOf("comparisonIds", "comparisonVersions", "comparisonHashes")) {
            assertEquals(key, before.getStringArrayList(key), state().getStringArrayList(key))
        }
        assertEquals(baseline, loaded().baselineIdentity)
        showCatalog(pair.first)
        openComparison()
        inspect(1, "spdx_id")
        assertTrue(dialog().isShowing)
        assertEquals(pair.second[0].license.single { it.name == "spdx_id" }.valueJson, completeValue())
        assertStable(before)
    }

    @Test
    @Config(qualifiers = "ar-rEG-ldrtl-w320dp-h640dp")
    @GraphicsMode(GraphicsMode.Mode.NATIVE)
    fun completeValueHeadingsAndActionsRemainReachableAtNarrowRtlLargeText() {
        RuntimeEnvironment.setFontScale(2f)
        val pair = prepareLongPair()
        openComparison()
        val before = state()
        inspect(1, "spdx_id")
        assertCaptured(pair.second[0], "spdx_id")
        val root = dialog().window!!.decorView
        val density = activity.resources.displayMetrics.density
        val width = (320 * density).toInt()
        val height = (640 * density).toInt()
        fun layout() {
            root.measure(View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(height, View.MeasureSpec.EXACTLY))
            root.layout(0, 0, width, height)
        }
        layout()
        assertEquals(View.LAYOUT_DIRECTION_RTL, root.layoutDirection)
        val headings = views(root).filterIsInstance<TextView>().filter { it.isAccessibilityHeading }
        assertEquals(2, headings.size)
        headings.forEach { heading ->
            assertTrue(heading.width in 1..width)
            assertNotNull(heading.layout)
            assertTrue(heading.layout.height <= heading.height - heading.compoundPaddingTop - heading.compoundPaddingBottom)
            repeat(heading.layout.lineCount) { assertEquals(0, heading.layout.getEllipsisCount(it)) }
        }
        val next = button(R.string.catalog_detail_browser_next_part, root)
        val back = button(R.string.catalog_compare_back_values, root)
        val other = buttonText(activity.getString(R.string.catalog_compare_other_value, 2), root)
        for (control in listOf(next, other, back)) {
            assertTrue(control.isFocusable)
            assertTrue(control.width >= (48 * density).toInt())
            assertTrue(control.height >= (48 * density).toInt())
            assertNotNull(control.layout)
            repeat(control.layout.lineCount) { assertEquals(0, control.layout.getEllipsisCount(it)) }
            control.requestRectangleOnScreen(Rect(0, 0, control.width, control.height), true)
            idle(); layout()
            val visible = Rect()
            assertTrue("The actual scroll must expose each complete value action", control.getGlobalVisibleRect(visible))
            assertTrue(visible.width() >= control.width - 1)
            assertTrue(visible.height() >= control.height - 1)
        }
        next.performClick(); idle()
        assertNotSame(root, dialog().window!!.decorView)
        assertCaptured(pair.second[0], "spdx_id")
        assertStable(before)
    }

    private fun assertStoragePresentationRetiresValue(presentStorage: () -> Unit) {
        prepareLongPair()
        openComparison()
        inspect(1, "spdx_id")
        val value = dialog()
        val back = button(R.string.catalog_compare_back_values, value.window!!.decorView)
        val next = button(R.string.catalog_detail_browser_next_part, value.window!!.decorView)
        val before = state()
        val beforeLibrary = librarySnapshot()
        presentStorage(); idle()
        val storage = dialog()
        assertNotSame(value, storage)
        assertTrue(storage.isShowing)
        assertFalse("A new storage dialog must retire the captured complete-value owner", value.isShowing)
        listOf(back, next).forEach { it.performClick(); idle() }
        assertSame(storage, dialog())
        assertTrue(storage.isShowing)
        assertStable(before)
        assertEquals(beforeLibrary, librarySnapshot())
        storage.getButton(AlertDialog.BUTTON_NEGATIVE).performClick(); idle()
        assertFalse(storage.isShowing)
        listOf(back, next).forEach { it.performClick(); idle() }
        assertSame(storage, dialog())
        assertFalse(storage.isShowing)
        assertStable(before)
        assertEquals(beforeLibrary, librarySnapshot())
        openComparison()
        inspect(1, "spdx_id")
        assertNotSame(value, dialog())
        assertTrue(dialog().isShowing)
        assertStable(before)
    }

    private fun historyPicker(): AlertDialog {
        val previous = ShadowAlertDialog.getLatestAlertDialog()
        button(R.string.draft_history).performClick()
        val picker = dialog()
        assertNotSame(previous, picker)
        assertTrue(picker.isShowing)
        assertTrue(picker.listView.adapter.count > 0)
        return picker
    }

    private fun libraryPicker(): AlertDialog {
        val previous = ShadowAlertDialog.getLatestAlertDialog()
        button(R.string.library_open).performClick()
        await { ShadowAlertDialog.getLatestAlertDialog()?.let { it !== previous && it.isShowing } == true &&
            button(R.string.library_open).isEnabled }
        return dialog()
    }
    private fun libraryIndex(picker: AlertDialog, id: PreviewDraftId): Int {
        val rows = picker.listView.adapter
        return (0 until rows.count).single { rows.getItem(it).toString().contains(id.value) }
    }
    private fun libraryEntry(id: PreviewDraftId): AlertDialog {
        val picker = libraryPicker()
        shadowOf(picker).clickOnItem(libraryIndex(picker, id))
        await { dialog() !== picker && dialog().isShowing }
        return dialog()
    }
    private fun librarySnapshot() = (PreviewDraftLibrary(activity.noBackupFilesDir.toPath()).list()
        as PreviewLibraryRead.Available).entries.associate { entry ->
        entry.id to Triple((entry.state as? PreviewDraftLoad.Available)?.record?.baselineIdentity,
            entry.state.javaClass.name, entry.deletionPending)
    }
    private fun failFinalCleanup(onReached: () -> Unit) {
        val constructor = PreviewDraftLibrary::class.java.getDeclaredConstructor(
            Path::class.java, kotlin.jvm.functions.Function1::class.java, kotlin.jvm.functions.Function1::class.java)
        val creation: (Any) -> Unit = {}
        val removal: (Any) -> Unit = { point ->
            if (point.toString() == "TOMBSTONE_REMOVED") {
                onReached()
                throw IOException("injected final acknowledgement failure")
            }
        }
        val faulty = constructor.newInstance(activity.noBackupFilesDir.toPath(), creation, removal) as PreviewDraftLibrary
        PreviewActivity::class.java.getDeclaredField("draftLibrary").apply { isAccessible = true }.set(activity, faulty)
    }

    private fun prepareLongPair() = preparePair { first, second ->
        first.getJSONObject("license").put("spdx_id", "recorded ".repeat(70) + " first tail")
        second.getJSONObject("license").put("spdx_id", "recorded ".repeat(70) + " second tail")
    }

    private fun preparePair(kind: String = "model", edit: (JSONObject, JSONObject) -> Unit):
        Pair<PreviewCatalogBrowser, List<PreviewCatalogDetails>> {
        val root = document()
        val items = root.getJSONArray("items")
        val first = (0 until items.length()).map { items.getJSONObject(it) }.single { it.getString("kind") == kind }
        val second = JSONObject(first.toString()).put("version", "successor")
        edit(first, second)
        items.put(second)
        val browser = showCatalog(root)
        field(R.string.catalog_search).setText(first.getString("id"))
        await { samples().size == 2 }
        samples().toList().forEach { sample ->
            sample.performClick(); idle()
            val parent = dialog()
            button(R.string.catalog_compare_add, parent.window!!.decorView).performClick(); idle()
            assertFalse(parent.isShowing)
        }
        val versions = state().getStringArrayList("comparisonVersions")!!
        assertEquals(listOf(first.getString("version"), second.getString("version")), versions)
        return browser to versions.map { version ->
            val item = browser.items.single { it.kind == kind && it.version == version }
            requireNotNull(browser.details(item, browser.hash))
        }
    }

    private fun pinKind(kind: String) {
        field(R.string.catalog_search).setText("cat.$kind.fixture")
        await { samples().size == 1 }
        samples().single().performClick(); idle()
        val parent = dialog()
        button(R.string.catalog_compare_add, parent.window!!.decorView).performClick(); idle()
        assertFalse(parent.isShowing)
    }
    private fun clearPins() {
        while (!state().getStringArrayList("comparisonIds").isNullOrEmpty()) removeFirstPin()
    }
    private fun removeFirstPin() {
        val version = state().getStringArrayList("comparisonVersions")!!.first()
        buttonText(activity.getString(R.string.catalog_compare_remove, 1, CatalogDetailText.literal(activity, version)))
            .performClick()
        idle()
    }
    private fun document() = activity.assets.open("catalog.json").bufferedReader(Charsets.UTF_8).use { JSONObject(it.readText()) }
    private fun showCatalog(root: JSONObject) = showCatalog(requireNotNull(
        PreviewCatalogBrowser.read(root.toString().toByteArray(Charsets.UTF_8))))
    private fun showCatalog(browser: PreviewCatalogBrowser): PreviewCatalogBrowser {
        views().filterIsInstance<CatalogBrowseSection>().single().show(browser)
        views().filterIsInstance<CatalogComparisonSection>().single().show(browser)
        return browser
    }
    private fun openComparison(): AlertDialog {
        assertTrue(button(R.string.catalog_compare_open).isEnabled)
        button(R.string.catalog_compare_open).performClick(); idle()
        return dialog().also { assertTrue(it.isShowing) }
    }
    private fun inspectButton(side: Int, name: String) =
        buttonText(activity.getString(R.string.catalog_compare_inspect_value, side, name), dialog().window!!.decorView)
    private fun inspect(side: Int, name: String) { inspectButton(side, name).performClick(); idle() }
    private fun assertCaptured(detail: PreviewCatalogDetails, name: String) {
        val text = texts().joinToString("\n")
        assertTrue(text.contains(CatalogDetailText.literal(activity, detail.item.id)))
        assertTrue(text.contains(CatalogDetailText.literal(activity, detail.item.version)))
        assertTrue(text.contains(detail.snapshotHash))
        assertTrue(text.contains(name))
        assertTrue(texts().any { it.contains(activity.getString(R.string.catalog_detail_browser_json, "string")) ||
            it.contains(activity.getString(R.string.catalog_detail_browser_json, "array")) })
    }
    private fun completeValue(parts: MutableList<String> = mutableListOf()): String {
        val joined = StringBuilder()
        repeat(100) {
            val part = literal()
            assertFalse(part.contains(activity.getString(R.string.catalog_detail_truncated, "")))
            assertTrue(part.length >= 2 && part.startsWith('"') && part.endsWith('"'))
            parts.add(part)
            joined.append(JSONObject("{\"value\":$part}").getString("value"))
            assertTrue(views(dialog().window!!.decorView).filterIsInstance<TextView>().all { it.urls.isEmpty() })
            val next = views(dialog().window!!.decorView).filterIsInstance<Button>()
                .singleOrNull { it.text.toString() == activity.getString(R.string.catalog_detail_browser_next_part) }
            if (next == null) return joined.toString()
            next.performClick(); idle()
        }
        fail("The bounded fixture must terminate through its actual page controls")
        return ""
    }
    private fun literal() = dialog().findViewById<TextView>(R.id.catalog_compare_value_literal).text.toString()
    private fun texts() = views(dialog().window!!.decorView).filterIsInstance<TextView>().map { it.text.toString() }
    private fun click(label: Int) { button(label, dialog().window!!.decorView).performClick(); idle() }
    private fun clickText(label: String) { buttonText(label, dialog().window!!.decorView).performClick(); idle() }
    private fun assertStable(before: Bundle) {
        val after = state()
        val keys = listOf("draftId", "draftBaseline", "title", "objective", "contextInput", "selectedId",
            "selectedVersion", "selectedCatalogHash", "pendingDeleteId", "catalogQuery", "catalogTier", "catalogKind",
            "catalogSort", "catalogSnapshot", "workload.classification", "entryBlocked", "unsupportedEntry",
            "selectionResolved", "draftRequiresReopen", "contextInputsPresent", "workloadInputsPresent",
            "workload.rejectedEdit", "comparisonDifferencesOnly", "comparisonIds", "comparisonVersions",
            "comparisonHashes", "catalogPage") + (0..7).map { "workload.$it" }
        @Suppress("DEPRECATION")
        keys.forEach { assertEquals(it, before.get(it), after.get(it)) }
        assertEquals(baseline, loaded().baselineIdentity)
        assertTrue(button(R.string.save_draft).isEnabled)
        assertTrue("Inspection must stay before the real autosave deadline",
            SystemClock.uptimeMillis() in pendingSince until pendingSince + 750)
        assertNull(shadowOf(activity).nextStartedActivity)
    }
    private fun state() = Bundle().also { controller.saveInstanceState(it) }
    private fun loaded() = (readDraftAfterOwnedWork(activity) {
        PreviewDraftLibrary(activity.noBackupFilesDir.toPath()).openLegacy()
    } as PreviewDraftLoad.Available).record
    private fun dialog() = requireNotNull(ShadowAlertDialog.getLatestAlertDialog())
    private fun samples() = views().filterIsInstance<CatalogBrowseSection>().single().let(::views)
        .filterIsInstance<Button>().filter { it.text.toString().contains("FICTIONAL") }
    private fun button(label: Int, root: View = activity.window.decorView) = buttonText(activity.getString(label), root)
    private fun buttonText(label: String, root: View = activity.window.decorView) =
        views(root).filterIsInstance<Button>().single { it.text.toString() == label }
    private fun field(label: Int): EditText {
        val caption = views().filterIsInstance<TextView>().single { it.text.toString() == activity.getString(label) }
        return activity.findViewById(caption.labelFor)
    }
    private fun views(root: View = activity.window.decorView): List<View> = buildList {
        add(root)
        if (root is ViewGroup) repeat(root.childCount) { addAll(views(root.getChildAt(it))) }
    }
    private fun idle() = AutosaveUiClock.idle()
    private fun await(condition: () -> Boolean) {
        val deadline = System.nanoTime() + 15_000_000_000L
        while (System.nanoTime() < deadline) {
            idle()
            if (condition()) return
            Thread.sleep(10)
        }
        fail("Timed out waiting for the actual preview controls")
    }
}

