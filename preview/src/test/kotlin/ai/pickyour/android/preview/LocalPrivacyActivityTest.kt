package ai.pickyour.android.preview

import android.app.AlertDialog
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.graphics.Rect
import android.os.Bundle
import android.os.SystemClock
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
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

/** Actual preview controls in a JVM simulation; no installed-device or store-declaration claim. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
@LooperMode(LooperMode.Mode.PAUSED)
class LocalPrivacyActivityTest {
    private lateinit var controller: ActivityController<PreviewActivity>
    private val activity get() = controller.get()
    private var pendingSince = 0L
    private val sentinels = listOf("private-privacy-title", "private-privacy-saved-objective",
        "https://private.example/?token=private-privacy-credential", "private-privacy-pending-objective")

    @Before fun launch() {
        controller = Robolectric.buildActivity(PreviewActivity::class.java).setup()
        await { button(R.string.save_draft).isEnabled && button(R.string.retry_catalog).isEnabled }
        field(R.string.draft_title).setText(sentinels[0])
        field(R.string.context_objective).setText(sentinels[1])
        field(R.string.context_context).setText(sentinels[2])
        button(R.string.save_draft).performClick()
        await { !button(R.string.save_draft).isEnabled && button(R.string.draft_history).isEnabled }
        AutosaveUiClock.pause()
        pendingSince = SystemClock.uptimeMillis()
        field(R.string.context_objective).setText(sentinels[3])
        field(R.string.workload_budget).setText("12.00")
    }

    @After fun close() { controller.pause().stop().destroy() }

    @Test fun disclosureCoversEnabledStorageAndExternalHandoffsWithoutPrivateContent() {
        val opened = open()
        val labels = views(opened.window!!.decorView).filterIsInstance<TextView>()
        val headings = listOf(R.string.privacy_drafts_title, R.string.privacy_samples_title,
            R.string.privacy_diagnostics_title, R.string.privacy_browser_title,
            R.string.privacy_delete_title, R.string.privacy_recovery_title)
        assertEquals(headings.map(activity::getString), labels.filter { it.isAccessibilityHeading }.map { it.text.toString() })
        assertEquals(activity.getString(R.string.privacy_title),
            opened.findViewById<ScrollView>(R.id.privacy_scroll).accessibilityPaneTitle)
        val bodies = listOf(R.string.privacy_scope, R.string.privacy_drafts, R.string.privacy_samples,
            R.string.privacy_diagnostics, R.string.privacy_browser, R.string.privacy_delete, R.string.privacy_recovery)
        bodies.forEach { resource -> assertEquals(1, labels.count { it.text.toString() == activity.getString(resource) }) }
        val disclosed = labels.joinToString("\n") { it.text }
        sentinels.forEach { assertFalse("Private content entered fixed disclosure", disclosed.contains(it)) }
        assertFalse(disclosed.contains(activity.noBackupFilesDir.absolutePath))
        assertTrue(labels.all { it.urls.isEmpty() })
        assertEquals(listOf(opened.getButton(AlertDialog.BUTTON_NEGATIVE)), labels.filterIsInstance<Button>().filter { it.isShown })
        assertNull(shadowOf(activity).nextStartedActivity)
    }

    @Test fun openingClosingAndReplacingDisclosurePreservesPendingInputsAndStoredBytes() {
        val before = state()
        val bytes = storedBytes()
        assertTrue(bytes.isNotEmpty())
        val first = open()
        val oldClose = first.getButton(AlertDialog.BUTTON_NEGATIVE)
        val second = open()
        assertFalse(first.isShowing)
        oldClose.performClick(); idle()
        assertTrue(second.isShowing)
        assertSame(second, dialog())
        second.cancel(); idle()
        assertFalse(second.isShowing)
        val third = open()
        third.getButton(AlertDialog.BUTTON_NEGATIVE).performClick(); idle()
        assertFalse(third.isShowing)
        assertStable(before, bytes)
    }

    @Test fun pausedActivityDismissesDisclosureAndReopensOnlyAfterResume() {
        val entry = entry()
        val opened = open()
        val before = state()
        val bytes = storedBytes()
        controller.pause()
        entry.performClick(); idle()
        assertFalse(opened.isShowing)
        assertSame(opened, dialog())
        controller.resume()
        entry.performClick(); idle()
        assertNotSame(opened, dialog())
        assertTrue(dialog().isShowing)
        assertStable(before, bytes)
    }

    @Test fun recreationRetiresOldEntryAndPreservesPendingDraft() {
        val oldEntry = entry()
        val opened = open()
        val previous = activity
        val before = state()
        val bytes = storedBytes()
        recreate()
        oldEntry.performClick(); idle()
        assertFalse(opened.isShowing)
        assertSame(opened, dialog())
        assertNull(shadowOf(previous).nextStartedActivity)
        assertTrue(open().isShowing)
        assertNotSame(opened, dialog())
        assertStable(before, bytes)
    }

    @Test fun closedDisclosureOwnerCannotReopenFromItsRetainedEntry() {
        var admitted = true
        val owner = LocalPrivacy(activity) { admitted }
        val container = LinearLayout(activity)
        owner.attach(container)
        val retained = container.findViewById<Button>(R.id.privacy_open)
        retained.performClick(); idle()
        val first = dialog()
        admitted = false
        owner.dismiss()
        retained.performClick(); idle()
        assertFalse(first.isShowing)
        assertSame(first, dialog())
        admitted = true
        retained.performClick(); idle()
        val second = dialog()
        assertNotSame(first, second)
        owner.close()
        retained.performClick(); idle()
        assertFalse(second.isShowing)
        assertSame(second, dialog())
        assertNull(shadowOf(activity).nextStartedActivity)
    }

    @Test fun declaredPreviewCapabilitiesMatchTheOfflineDisclosure() {
        // A new permission or background SDK entry point requires revisiting the displayed scope.
        @Suppress("DEPRECATION")
        val declared = activity.packageManager.getPackageInfo(activity.packageName,
            PackageManager.GET_PERMISSIONS or PackageManager.GET_SERVICES or
                PackageManager.GET_RECEIVERS or PackageManager.GET_PROVIDERS)
        assertTrue(declared.requestedPermissions.isNullOrEmpty())
        assertTrue(declared.services.isNullOrEmpty())
        assertTrue(declared.receivers.isNullOrEmpty())
        assertTrue(declared.providers.isNullOrEmpty())
        assertEquals(0, activity.applicationInfo.flags and ApplicationInfo.FLAG_ALLOW_BACKUP)
        assertEquals("preview", BuildConfig.PYAI_ENVIRONMENT)
        assertTrue(open().isShowing)
        assertNull(shadowOf(activity).nextStartedActivity)
    }

    @Test
    @GraphicsMode(GraphicsMode.Mode.NATIVE)
    @Config(qualifiers = "ar-rEG-ldrtl-w320dp-h640dp")
    fun narrowRtlLargeTextKeepsEveryDisclosureLineAndCloseReachable() {
        RuntimeEnvironment.setFontScale(2f)
        recreate()
        val before = state()
        val bytes = storedBytes()
        val opened = open()
        val root = opened.window!!.decorView
        val density = activity.resources.displayMetrics.density
        val width = (320 * density).toInt(); val height = (640 * density).toInt()
        root.measure(View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(height, View.MeasureSpec.EXACTLY))
        root.layout(0, 0, width, height)
        assertEquals(View.LAYOUT_DIRECTION_RTL, root.layoutDirection)
        assertEquals(2f, activity.resources.configuration.fontScale, 0f)
        val scroll = opened.findViewById<ScrollView>(R.id.privacy_scroll)
        for (text in views(scroll).filterIsInstance<TextView>()) {
            val layout = requireNotNull(text.layout)
            assertEquals(text.text.length, layout.getLineEnd(layout.lineCount - 1))
            repeat(layout.lineCount) { line ->
                assertEquals(0, layout.getEllipsisCount(line))
                // getLineWidth also counts trailing wrap whitespace outside the visible line.
                assertTrue("Visible disclosure text exceeds its horizontal bounds",
                    layout.getLineMax(line) <= text.width - text.compoundPaddingLeft - text.compoundPaddingRight)
                assertReachable(root, text, Rect(0, text.totalPaddingTop + layout.getLineTop(line),
                    text.width, text.totalPaddingTop + layout.getLineBottom(line)))
            }
        }
        assertTrue("Long disclosure must exercise scrolling", scroll.scrollY > 0)
        val close = opened.getButton(AlertDialog.BUTTON_NEGATIVE)
        assertTrue(close.isShown && close.isFocusable)
        assertTrue(close.height >= (48 * density).toInt())
        assertTrue(close.width >= (48 * density).toInt())
        assertReachable(root, close, Rect(0, 0, close.width, close.height))
        close.performClick(); idle()
        assertFalse(opened.isShowing)
        assertStable(before, bytes)
    }

    private fun assertReachable(root: View, view: View, local: Rect) {
        view.requestRectangleOnScreen(Rect(local), true)
        val bounds = Rect(local)
        var child = view
        while (child !== root) {
            val parent = child.parent as ViewGroup
            parent.offsetDescendantRectToMyCoords(child, bounds)
            val viewport = Rect().also(parent::getDrawingRect)
            assertTrue("Disclosure clipped by ${parent.javaClass.simpleName}", viewport.contains(bounds))
            child = parent
        }
    }

    private fun assertStable(before: Bundle, bytes: Map<String, String>) {
        val after = state()
        val keys = listOf("draftId", "draftBaseline", "title", "objective", "contextInput", "selectedId",
            "selectedVersion", "selectedCatalogHash", "pendingDeleteId", "catalogQuery", "catalogTier", "catalogKind",
            "catalogSort", "catalogSnapshot", "workload.classification", "entryBlocked", "unsupportedEntry",
            "selectionResolved", "draftRequiresReopen", "contextInputsPresent", "workloadInputsPresent",
            "workload.rejectedEdit") + (0..7).map { "workload.$it" }
        @Suppress("DEPRECATION")
        keys.forEach { assertEquals(it, before.get(it), after.get(it)) }
        assertEquals(bytes, storedBytes())
        assertTrue(button(R.string.save_draft).isEnabled)
        assertTrue("Privacy inspection must stay before the real autosave deadline",
            SystemClock.uptimeMillis() in pendingSince until pendingSince + 750)
        assertNull(shadowOf(activity).nextStartedActivity)
    }

    private fun storedBytes(): Map<String, String> = listOf(activity.noBackupFilesDir, activity.filesDir)
        .flatMapIndexed { index, directory ->
            val root = directory.toPath()
            Files.walk(root).use { paths ->
                paths.filter(Files::isRegularFile).toList().map { path ->
                    "$index/${root.relativize(path)}" to MessageDigest.getInstance("SHA-256")
                        .digest(Files.readAllBytes(path)).joinToString("") { "%02x".format(it) }
                }
            }
        }.toMap()

    private fun open(): AlertDialog {
        entry().performClick(); idle()
        return dialog().also { assertTrue(it.isShowing) }
    }
    private fun entry() = activity.findViewById<Button>(R.id.privacy_open)
    private fun dialog() = requireNotNull(ShadowAlertDialog.getLatestAlertDialog())
    private fun state() = Bundle().also { controller.saveInstanceState(it) }
    private fun recreate() {
        val saved = state()
        controller.pause().stop().destroy()
        controller = Robolectric.buildActivity(PreviewActivity::class.java).create(saved).start().resume().visible()
        await { button(R.string.retry_catalog).isEnabled && button(R.string.save_draft).isEnabled }
    }
    private fun button(label: Int) = views().filterIsInstance<Button>().single { it.text.toString() == activity.getString(label) }
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
