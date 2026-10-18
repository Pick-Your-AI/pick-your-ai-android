package ai.pickyour.android.preview

import android.app.AlertDialog
import android.content.ActivityNotFoundException
import android.content.Intent
import android.graphics.Rect
import android.os.Bundle
import android.os.Looper
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.EditText
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

/** Actual native controls in a JVM simulation; no browser, GitHub submission or support receipt. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
@LooperMode(LooperMode.Mode.PAUSED)
class LocalHelpActivityTest {
    private lateinit var controller: ActivityController<PreviewActivity>
    private val activity get() = controller.get()
    private val helpers = mutableListOf<LocalHelp>()

    @Before fun launch() {
        controller = Robolectric.buildActivity(PreviewActivity::class.java).setup()
        await { button(R.string.save_draft).isEnabled && button(R.string.retry_catalog).isEnabled }
    }

    @After fun close() {
        helpers.forEach(LocalHelp::close)
        controller.pause().stop().destroy()
    }

    private fun views(root: View = activity.window.decorView): List<View> = buildList {
        add(root)
        if (root is ViewGroup) for (index in 0 until root.childCount) addAll(views(root.getChildAt(index)))
    }
    private fun button(label: Int) = views().filterIsInstance<Button>().single { it.text.toString() == activity.getString(label) }
    private fun field(label: Int): EditText {
        val caption = views().filterIsInstance<TextView>().single { it.text.toString() == activity.getString(label) }
        return activity.findViewById(caption.labelFor)
    }
    private fun dialog() = requireNotNull(ShadowAlertDialog.getLatestAlertDialog())
    private fun control(id: Int, dialog: AlertDialog = dialog()) = dialog.findViewById<Button>(id)
    private fun text(dialog: AlertDialog = dialog()) = views(dialog.window!!.decorView)
        .filterIsInstance<TextView>().joinToString("\n") { it.text }
    private fun idle() = shadowOf(Looper.getMainLooper()).idle()
    private fun await(condition: () -> Boolean) {
        val deadline = System.nanoTime() + 15_000_000_000L
        while (System.nanoTime() < deadline) {
            idle()
            if (condition()) return
            Thread.sleep(10)
        }
        fail("Timed out waiting for the explicit UI state")
    }
    private fun recreate() {
        val state = Bundle()
        controller.pause().saveInstanceState(state).stop().destroy()
        controller = Robolectric.buildActivity(PreviewActivity::class.java).create(state).start().resume().visible()
        await { button(R.string.retry_catalog).isEnabled }
    }
    private fun helper(review: () -> Unit = {}, launch: (Intent) -> Unit): LocalHelp =
        LocalHelp(activity, { true }, review, launch).also(helpers::add)
    private fun storedBytes(): Map<String, String> {
        val root = activity.noBackupFilesDir.toPath()
        return Files.walk(root).use { paths ->
            paths.filter(Files::isRegularFile).toList().associate { path ->
                root.relativize(path).toString() to MessageDigest.getInstance("SHA-256")
                    .digest(Files.readAllBytes(path)).joinToString("") { "%02x".format(it) }
            }
        }
    }
    private fun assertPublicIntent(intent: Intent, destination: HelpDestination) {
        assertEquals(Intent.ACTION_VIEW, intent.action)
        assertEquals(destination.url, intent.dataString)
        assertEquals("https", intent.data!!.scheme)
        assertEquals("github.com", intent.data!!.host)
        assertEquals(setOf(Intent.CATEGORY_BROWSABLE), intent.categories)
        assertNull(intent.extras)
        assertNull(intent.type)
        assertNull(intent.clipData)
        assertNull(intent.component)
        assertNull(intent.`package`)
        assertNull(intent.selector)
        assertEquals(0, intent.flags)
    }

    @Test fun localHelpAndActualBrowserIntentExcludeSavedAndPendingPrivateContent() {
        val sentinels = listOf("private-sentinel-title", "private-sentinel-objective",
            "https://private.example/?token=private-sentinel-credential", "private-sentinel-pending", "private-sentinel-query")
        field(R.string.draft_title).setText(sentinels[0])
        field(R.string.context_objective).setText(sentinels[1])
        field(R.string.context_context).setText(sentinels[2])
        button(R.string.save_draft).performClick()
        await { !button(R.string.save_draft).isEnabled && button(R.string.draft_history).isEnabled }
        field(R.string.context_objective).setText(sentinels[3])
        field(R.string.catalog_search).setText(sentinels[4])
        val before = storedBytes()
        assertTrue(before.isNotEmpty())
        assertNull(shadowOf(activity).nextStartedActivity)

        button(R.string.help_open).performClick()
        val guidance = text()
        assertTrue(dialog().isShowing)
        assertTrue(guidance.contains(BuildConfig.VERSION_NAME))
        assertTrue(guidance.contains(activity.getString(R.string.help_recovery)))
        assertTrue(guidance.contains(activity.getString(R.string.help_private)))
        sentinels.forEach { assertFalse(guidance.contains(it)) }
        assertFalse(guidance.contains(activity.noBackupFilesDir.absolutePath))
        assertNull(shadowOf(activity).nextStartedActivity)

        control(R.id.help_bug).performClick()
        sentinels.forEach { assertFalse(text().contains(it)) }
        assertEquals(HelpDestination.BUG.url, dialog().findViewById<TextView>(R.id.help_destination).text.toString())
        assertNull(shadowOf(activity).nextStartedActivity)
        control(R.id.help_external_open).performClick(); idle()
        assertPublicIntent(requireNotNull(shadowOf(activity).nextStartedActivity), HelpDestination.BUG)
        assertNull(shadowOf(activity).nextStartedActivity)
        assertEquals(before, storedBytes())
        assertEquals(sentinels[0], field(R.string.draft_title).text.toString())
        assertEquals(sentinels[2], field(R.string.context_context).text.toString())
        assertEquals(sentinels[3], field(R.string.context_objective).text.toString())
        assertEquals(sentinels[4], field(R.string.catalog_search).text.toString())
        assertTrue(button(R.string.save_draft).isEnabled)
    }

    @Test fun eachPublicDestinationRequiresReviewAndOnlyOneExplicitFixedUriDispatch() {
        val launches = mutableListOf<Intent>()
        val help = helper(launch = launches::add)
        val expectedUris = listOf(
            "https://github.com/Pick-Your-AI/pick-your-ai-android/issues/new?template=android-bug.yml",
            "https://github.com/Pick-Your-AI/pick-your-ai-android/issues/new?template=android-evidence-correction.yml",
            "https://github.com/Pick-Your-AI/pick-your-ai-android/issues",
        )
        assertEquals(expectedUris, HelpDestination.entries.map { it.url })
        for ((index, destination) in HelpDestination.entries.withIndex()) {
            help.show()
            control(destination.control).performClick()
            val review = dialog()
            assertTrue(review.isShowing)
            assertEquals(index, launches.size)
            assertEquals(expectedUris[index], review.findViewById<TextView>(R.id.help_destination).text.toString())
            assertTrue(text(review).contains(activity.getString(R.string.help_external_disclosure)))
            val retained = control(R.id.help_external_open, review)
            retained.performClick(); idle(); retained.performClick(); idle()
            assertEquals(index + 1, launches.size)
            assertPublicIntent(launches.last(), destination)
            assertFalse(review.isShowing)
        }
    }

    @Test fun cancelledDismissedAndReplacedHelpAndDestinationCallbacksCannotNavigate() {
        val launches = mutableListOf<Intent>()
        var diagnosticReviews = 0
        val help = helper({ diagnosticReviews++ }, launches::add)
        for (cancel in listOf(true, false)) {
            help.show()
            val retired = dialog()
            val oldBug = control(R.id.help_bug, retired)
            val oldDiagnostics = control(R.id.help_diagnostics, retired)
            if (cancel) retired.cancel() else retired.dismiss()
            oldBug.performClick(); oldDiagnostics.performClick(); idle()
            assertSame(retired, dialog())
            assertFalse(retired.isShowing)
            assertTrue(launches.isEmpty())
            assertEquals(0, diagnosticReviews)

            help.show(); control(R.id.help_bug).performClick()
            val review = dialog(); val oldOpen = control(R.id.help_external_open, review)
            if (cancel) review.cancel() else review.dismiss()
            oldOpen.performClick(); idle()
            assertTrue(launches.isEmpty())
        }
        help.show()
        val oldParent = dialog(); val oldBug = control(R.id.help_bug)
        help.show()
        val currentParent = dialog()
        oldBug.performClick(); idle()
        assertSame(currentParent, dialog())
        assertFalse(oldParent.isShowing)
        assertTrue(currentParent.isShowing)
        control(R.id.help_bug).performClick()
        val oldReview = dialog(); val oldOpen = control(R.id.help_external_open)
        help.show(); control(R.id.help_evidence).performClick()
        val currentReview = dialog()
        oldOpen.performClick(); idle()
        assertSame(currentReview, dialog())
        assertFalse(oldReview.isShowing)
        assertTrue(currentReview.isShowing)
        assertTrue(launches.isEmpty())
        control(R.id.help_external_open).performClick(); idle()
        assertPublicIntent(launches.single(), HelpDestination.EVIDENCE)
    }

    @Test fun missingOrForbiddenBrowserRetainsSafeDestinationAndNeedsFreshReview() {
        for (failure in listOf(ActivityNotFoundException("private-sentinel-handler"), SecurityException("private-sentinel-security"))) {
            var attempts = 0
            var failing = true
            val help = helper { attempts++; if (failing) throw failure }
            help.show(); control(R.id.help_bug).performClick()
            val review = dialog(); val retained = control(R.id.help_external_open)
            retained.performClick(); idle()
            assertEquals(1, attempts)
            assertTrue(review.isShowing)
            assertFalse(retained.isEnabled)
            assertEquals(HelpDestination.BUG.url, review.findViewById<TextView>(R.id.help_destination).text.toString())
            val outcome = review.findViewById<TextView>(R.id.help_external_outcome)
            assertEquals(View.VISIBLE, outcome.visibility)
            assertEquals(View.ACCESSIBILITY_LIVE_REGION_POLITE, outcome.accessibilityLiveRegion)
            assertEquals(activity.getString(R.string.help_external_unavailable), outcome.text.toString())
            assertFalse(text(review).contains("private-sentinel"))
            retained.performClick(); idle()
            assertEquals(1, attempts)
            failing = false
            help.show(); control(R.id.help_bug).performClick()
            assertEquals(1, attempts)
            control(R.id.help_external_open).performClick(); idle()
            assertEquals(2, attempts)
            help.close()
        }
    }

    @Test fun helpDiagnosticsReusesTheExistingPreviewWithoutSharingOrRetainedNavigation() {
        button(R.string.help_open).performClick()
        val helpDialog = dialog()
        val oldBug = control(R.id.help_bug)
        val retained = control(R.id.help_diagnostics)
        retained.performClick(); idle()
        val report = dialog()
        assertFalse(helpDialog.isShowing)
        assertTrue(report.isShowing)
        assertEquals(LocalDiagnosticReport(DiagnosticStorageState.READY, DiagnosticCatalogState.SYNTHETIC_LOADED).text,
            report.findViewById<TextView>(R.id.diagnostic_report).text.toString())
        assertNotNull(report.findViewById<Button>(R.id.diagnostic_share))
        assertNull(shadowOf(activity).nextStartedActivity)
        retained.performClick(); oldBug.performClick(); idle()
        assertSame(report, dialog())
        assertTrue(report.isShowing)
        assertNull(shadowOf(activity).nextStartedActivity)
    }

    @Test fun pauseAndRecreationRetireBothHelpReviewLevelsAndOldEntryButtons() {
        val entry = button(R.string.help_open)
        entry.performClick()
        val parent = dialog(); val oldBug = control(R.id.help_bug)
        controller.pause()
        oldBug.performClick(); entry.performClick(); idle()
        assertFalse(parent.isShowing)
        assertNull(shadowOf(activity).nextStartedActivity)
        controller.resume()
        entry.performClick(); control(R.id.help_bug).performClick()
        val pausedReview = dialog(); val pausedOpen = control(R.id.help_external_open)
        controller.pause()
        pausedOpen.performClick(); entry.performClick(); idle()
        assertFalse(pausedReview.isShowing)
        assertNull(shadowOf(activity).nextStartedActivity)
        controller.resume()
        entry.performClick(); control(R.id.help_bug).performClick()
        val retiredReview = dialog(); val retiredOpen = control(R.id.help_external_open)
        val previous = activity
        recreate()
        retiredOpen.performClick(); entry.performClick(); idle()
        assertFalse(retiredReview.isShowing)
        assertNull(shadowOf(previous).nextStartedActivity)
        assertNull(shadowOf(activity).nextStartedActivity)
        button(R.string.help_open).performClick()
        assertTrue(dialog().isShowing)
        assertNotSame(retiredReview, dialog())
        assertNull(shadowOf(activity).nextStartedActivity)
    }

    @Test fun closedHelpOwnerCannotOpenDiagnosticsOrDispatchRetainedPublicControls() {
        var reviews = 0
        var launches = 0
        val help = helper({ reviews++ }) { launches++ }
        help.show()
        val retainedDiagnostics = control(R.id.help_diagnostics)
        control(R.id.help_bug).performClick()
        val retired = dialog(); val retainedOpen = control(R.id.help_external_open)
        help.close()
        retainedOpen.performClick(); retainedDiagnostics.performClick(); help.show(); idle()
        assertSame(retired, dialog())
        assertFalse(retired.isShowing)
        assertEquals(0, reviews)
        assertEquals(0, launches)
    }

    @Test
    @GraphicsMode(GraphicsMode.Mode.NATIVE)
    @Config(qualifiers = "ar-rEG-ldrtl-w320dp-h640dp")
    fun narrowRtlLargeTextHelpAndDestinationKeepCompleteReachableActions() {
        RuntimeEnvironment.setFontScale(2f)
        recreate()
        button(R.string.help_open).performClick()
        assertReadable(dialog())
        control(R.id.help_evidence).performClick()
        val review = dialog()
        assertReadable(review)
        assertEquals(View.TEXT_DIRECTION_LTR, review.findViewById<TextView>(R.id.help_destination).textDirection)
        assertNull(shadowOf(activity).nextStartedActivity)
    }

    private fun assertReadable(dialog: AlertDialog) {
        val root = dialog.window!!.decorView
        val density = activity.resources.displayMetrics.density
        val width = (320 * density).toInt(); val height = (640 * density).toInt()
        root.measure(View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(height, View.MeasureSpec.EXACTLY))
        root.layout(0, 0, width, height)
        assertEquals(View.LAYOUT_DIRECTION_RTL, root.layoutDirection)
        assertEquals(2f, activity.resources.configuration.fontScale, 0f)
        for (text in views(root).filterIsInstance<TextView>().filter { it.visibility == View.VISIBLE && it.text.isNotEmpty() }) {
            val label = requireNotNull(text.layout)
            assertEquals(text.text.length, label.getLineEnd(label.lineCount - 1))
            repeat(label.lineCount) { assertEquals(0, label.getEllipsisCount(it)) }
        }
        val actionIds = if (dialog.findViewById<TextView>(R.id.help_destination) != null)
            listOf(R.id.help_external_open)
        else HelpDestination.entries.map { it.control } + R.id.help_diagnostics
        val actions = actionIds.map { requireNotNull(dialog.findViewById<Button>(it)) } +
            dialog.getButton(AlertDialog.BUTTON_NEGATIVE)
        // AlertDialog also contains unused GONE positive/neutral buttons. Require every advertised action.
        assertEquals(actions.size, views(root).filterIsInstance<Button>().count { it.isShown })
        for (control in actions) {
            assertTrue(control.isShown)
            assertTrue(control.isFocusable)
            assertTrue(control.height >= (48 * density).toInt())
            assertTrue(control.width >= (48 * density).toInt())
            control.requestRectangleOnScreen(Rect(0, 0, control.width, control.height), true)
            val bounds = Rect(0, 0, control.width, control.height)
            var child: View = control
            while (child !== root) {
                val parent = child.parent as ViewGroup
                parent.offsetDescendantRectToMyCoords(child, bounds)
                val viewport = Rect().also(parent::getDrawingRect)
                assertTrue("Action clipped by ${parent.javaClass.simpleName}", viewport.contains(bounds))
                child = parent
            }
            val label = requireNotNull(control.layout)
            assertTrue(label.height <= control.height - control.compoundPaddingTop - control.compoundPaddingBottom)
        }
    }
}
