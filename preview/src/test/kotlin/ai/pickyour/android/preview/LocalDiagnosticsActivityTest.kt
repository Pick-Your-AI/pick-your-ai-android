package ai.pickyour.android.preview

import android.app.AlertDialog
import android.content.ActivityNotFoundException
import android.content.Intent
import android.os.Bundle
import android.os.Looper
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.EditText
import android.widget.TextView
import ai.pickyour.core.preview.PreviewDraftLibrary
import ai.pickyour.core.preview.PreviewDraftLoad
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

/** Actual native controls in a JVM simulation; no chooser recipient or support delivery is exercised. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
@LooperMode(LooperMode.Mode.PAUSED)
class LocalDiagnosticsActivityTest {
    private lateinit var controller: ActivityController<PreviewActivity>
    private val activity get() = controller.get()

    @Before fun launch() {
        controller = Robolectric.buildActivity(PreviewActivity::class.java).setup()
        await { button(R.string.save_draft).isEnabled && button(R.string.retry_catalog).isEnabled }
        assertTrue(PreviewDraftLibrary(activity.noBackupFilesDir.toPath()).openLegacy().load() is PreviewDraftLoad.Missing)
    }

    @After fun close() { controller.pause().stop().destroy() }

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
    private fun report(dialog: AlertDialog = dialog()) = dialog.findViewById<TextView>(R.id.diagnostic_report).text.toString()
    private fun share(dialog: AlertDialog = dialog()) = dialog.findViewById<Button>(R.id.diagnostic_share)
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
    private fun storedBytes(): Map<String, String> {
        val root = activity.noBackupFilesDir.toPath()
        return Files.walk(root).use { paths ->
            paths.filter(Files::isRegularFile).toList().associate { path ->
                root.relativize(path).toString() to MessageDigest.getInstance("SHA-256")
                    .digest(Files.readAllBytes(path)).joinToString("") { "%02x".format(it) }
            }
        }
    }
    private fun assertChooser(intent: Intent, expected: String) {
        assertEquals(Intent.ACTION_CHOOSER, intent.action)
        assertEquals(setOf(Intent.EXTRA_INTENT, Intent.EXTRA_TITLE), intent.extras!!.keySet())
        @Suppress("DEPRECATION") val send = intent.getParcelableExtra<Intent>(Intent.EXTRA_INTENT)!!
        assertEquals(Intent.ACTION_SEND, send.action)
        assertEquals("text/plain", send.type)
        assertEquals(setOf(Intent.EXTRA_TEXT), send.extras!!.keySet())
        assertEquals(expected, send.getStringExtra(Intent.EXTRA_TEXT))
        for (value in listOf(intent, send)) {
            assertNull(value.data)
            // Android may migrate EXTRA_TEXT to a plain ClipData item during Activity dispatch.
            // It must still contain exactly the reviewed text, with no URI, HTML or nested Intent.
            value.clipData?.let { clip ->
                assertEquals(1, clip.itemCount)
                assertNull(clip.description.label)
                assertEquals(1, clip.description.mimeTypeCount)
                assertEquals("text/plain", clip.description.getMimeType(0))
                val item = clip.getItemAt(0)
                assertEquals(expected, item.text.toString())
                assertNull(item.uri)
                assertNull(item.intent)
                assertNull(item.htmlText)
            }
            assertNull(value.component)
            assertNull(value.`package`)
            // The platform's text migration may add this flag; there is no URI to grant.
            assertEquals(0, value.flags and Intent.FLAG_GRANT_READ_URI_PERMISSION.inv())
        }
    }

    @Test fun everyFiniteReportStateKeepsOnlyAllowlistedNonidentifyingFields() {
        val expectedKeys = setOf("diagnostic_format", "application_id", "application_version", "application_version_code",
            "android_api", "client_mode", "storage_state", "catalog_state", "private_content", "support_submission")
        for (storage in DiagnosticStorageState.entries) for (catalog in DiagnosticCatalogState.entries) {
            val value = LocalDiagnosticReport(storage, catalog)
            val fields = value.text.lines().associate { it.substringBefore('=') to it.substringAfter('=') }
            assertEquals(expectedKeys.size, value.text.lines().size)
            assertEquals(expectedKeys, fields.keys)
            assertEquals(storage.name, fields["storage_state"])
            assertEquals(catalog.name, fields["catalog_state"])
            assertEquals(BuildConfig.VERSION_NAME, fields["application_version"])
            assertEquals("28", fields["android_api"])
            assertEquals("excluded", fields["private_content"])
            assertEquals("unavailable", fields["support_submission"])
            assertTrue(value.text.length < 1024)
            assertEquals("LocalDiagnosticReport", value.toString())
        }
    }

    @Test fun actualReviewExcludesSeededPrivateDataAndSharesOnlyTheExactPreviewAfterConsent() {
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
        assertTrue(views().filterIsInstance<TextView>().any { it.text == activity.getString(R.string.diagnostics_support_unavailable) })

        button(R.string.diagnostics_review).performClick()
        val opened = dialog()
        val displayed = report(opened)
        assertTrue(opened.isShowing)
        assertNull(shadowOf(activity).nextStartedActivity)
        sentinels.forEach { assertFalse("Private value leaked into report", displayed.contains(it)) }
        assertFalse(displayed.contains(activity.noBackupFilesDir.absolutePath))
        val retained = share(opened)
        retained.performClick(); idle()
        assertChooser(requireNotNull(shadowOf(activity).nextStartedActivity), displayed)
        assertFalse(opened.isShowing)
        retained.performClick(); idle()
        assertNull(shadowOf(activity).nextStartedActivity)
        assertEquals(before, storedBytes())
        assertEquals(sentinels[3], field(R.string.context_objective).text.toString())
        assertTrue(button(R.string.save_draft).isEnabled)
    }

    @Test fun cancelledDismissedAndReplacedReportsCannotDispatchRetainedControls() {
        val launches = mutableListOf<Intent>()
        val diagnostics = LocalDiagnostics(activity,
            { LocalDiagnosticReport(DiagnosticStorageState.READY, DiagnosticCatalogState.SYNTHETIC_LOADED) },
            { true }, launch = launches::add)
        diagnostics.show()
        val cancelled = dialog(); val cancelledShare = share(cancelled)
        cancelled.cancel(); idle(); cancelledShare.performClick(); idle()
        assertTrue(launches.isEmpty())
        diagnostics.show()
        val dismissed = dialog(); val dismissedShare = share(dismissed)
        dismissed.dismiss(); idle(); dismissedShare.performClick(); idle()
        assertTrue(launches.isEmpty())
        diagnostics.show()
        val replaced = dialog(); val replacedShare = share(replaced)
        diagnostics.show()
        val current = dialog()
        assertNotSame(replaced, current)
        replacedShare.performClick(); idle()
        assertTrue(current.isShowing)
        assertTrue(launches.isEmpty())
        val expected = report(current)
        share(current).performClick(); idle()
        assertEquals(1, launches.size)
        assertChooser(launches.single(), expected)
        diagnostics.close()
    }

    @Test fun reportSnapshotStaysImmutableAndEveryNewShareRequiresFreshConsent() {
        val launches = mutableListOf<Intent>()
        var captures = 0
        var storage = DiagnosticStorageState.BUSY
        val diagnostics = LocalDiagnostics(activity,
            { captures++; LocalDiagnosticReport(storage, DiagnosticCatalogState.UNAVAILABLE_OR_LOADING) },
            { true }, launch = launches::add)
        diagnostics.show()
        val opened = dialog(); val displayed = report(opened); val retained = share(opened)
        storage = DiagnosticStorageState.READY
        assertEquals(1, captures)
        assertEquals(displayed, report(opened))
        retained.performClick(); idle(); retained.performClick(); idle()
        assertEquals(1, captures)
        assertEquals(1, launches.size)
        assertChooser(launches.single(), displayed)
        diagnostics.show()
        assertEquals(2, captures)
        assertNotEquals(displayed, report())
        assertTrue(report().contains("storage_state=READY"))
        assertEquals(1, launches.size)
        diagnostics.close()
    }

    @Test fun unavailableChooserKeepsSafeFailureAndRequiresAnotherReviewedAttempt() {
        for (failure in listOf(ActivityNotFoundException("private-sentinel-handler"), SecurityException("private-sentinel-security"))) {
            var attempts = 0
            var failLaunch = true
            val diagnostics = LocalDiagnostics(activity,
                { LocalDiagnosticReport(DiagnosticStorageState.UNAVAILABLE, DiagnosticCatalogState.UNAVAILABLE_OR_LOADING) },
                { true }) { attempts++; if (failLaunch) throw failure }
            diagnostics.show()
            val opened = dialog(); val displayed = report(opened); val retained = share(opened)
            retained.performClick(); idle()
            assertEquals(1, attempts)
            assertTrue(opened.isShowing)
            assertEquals(displayed, report(opened))
            assertFalse(retained.isEnabled)
            val text = views(opened.window!!.decorView).filterIsInstance<TextView>().joinToString("\n") { it.text }
            assertTrue(text.contains(activity.getString(R.string.diagnostics_share_unavailable)))
            assertFalse(text.contains("private-sentinel"))
            retained.performClick(); idle()
            assertEquals(1, attempts)
            failLaunch = false
            diagnostics.show()
            assertEquals(1, attempts)
            share().performClick(); idle()
            assertEquals(2, attempts)
            diagnostics.close()
        }
    }

    @Test fun recreationAndClosedOwnersRetireReviewAndShareCallbacks() {
        val review = button(R.string.diagnostics_review)
        review.performClick()
        val opened = dialog(); val retainedShare = share(opened)
        val previous = activity
        recreate()
        assertFalse(opened.isShowing)
        retainedShare.performClick(); review.performClick(); idle()
        assertNull(shadowOf(previous).nextStartedActivity)
        assertNull(shadowOf(activity).nextStartedActivity)
        button(R.string.diagnostics_review).performClick()
        assertTrue(dialog().isShowing)
        assertNotSame(opened, dialog())
        dialog().dismiss(); idle()

        var captured = 0
        val diagnostics = LocalDiagnostics(activity,
            { captured++; LocalDiagnosticReport(DiagnosticStorageState.READY, DiagnosticCatalogState.SYNTHETIC_LOADED) },
            { true }) { fail("Closed diagnostic owner dispatched") }
        diagnostics.show()
        val retired = share()
        diagnostics.close()
        retired.performClick(); diagnostics.show(); idle()
        assertEquals(1, captured)
    }

    @Test fun pausedActivityRetiresReportAndCannotReviewUntilResumed() {
        val review = button(R.string.diagnostics_review)
        review.performClick()
        val opened = dialog(); val retained = share(opened)
        controller.pause()
        assertFalse(opened.isShowing)
        retained.performClick(); review.performClick(); idle()
        assertFalse(dialog().isShowing)
        assertNull(shadowOf(activity).nextStartedActivity)
        controller.resume()
        review.performClick()
        val fresh = dialog()
        assertTrue(fresh.isShowing)
        assertNotSame(opened, fresh)
        assertNull(shadowOf(activity).nextStartedActivity)
        val expected = report(fresh)
        share(fresh).performClick(); idle()
        assertChooser(requireNotNull(shadowOf(activity).nextStartedActivity), expected)
    }

    @Test
    @GraphicsMode(GraphicsMode.Mode.NATIVE)
    @Config(qualifiers = "ar-rEG-ldrtl-w320dp-h640dp")
    fun narrowRtlLargeTextReportKeepsCompleteTextAndReachableShareControl() {
        RuntimeEnvironment.setFontScale(2f)
        recreate()
        button(R.string.diagnostics_review).performClick()
        val opened = dialog()
        val root = opened.window!!.decorView
        val density = activity.resources.displayMetrics.density
        val width = (320 * density).toInt(); val height = (640 * density).toInt()
        root.measure(View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(height, View.MeasureSpec.EXACTLY))
        root.layout(0, 0, width, height)
        assertEquals(View.LAYOUT_DIRECTION_RTL, root.layoutDirection)
        assertEquals(2f, activity.resources.configuration.fontScale, 0f)
        val report = opened.findViewById<TextView>(R.id.diagnostic_report)
        assertEquals(View.TEXT_DIRECTION_LTR, report.textDirection)
        assertEquals(report.text.length, report.layout.getLineEnd(report.layout.lineCount - 1))
        for (control in listOf(share(opened), opened.getButton(AlertDialog.BUTTON_NEGATIVE))) {
            assertTrue(control.isShown)
            assertTrue(control.isFocusable)
            assertTrue(control.height >= (48 * density).toInt())
            assertTrue(control.width >= (48 * density).toInt())
            control.requestRectangleOnScreen(android.graphics.Rect(0, 0, control.width, control.height), true)
            val bounds = android.graphics.Rect(0, 0, control.width, control.height)
            var child: View = control
            while (child !== root) {
                val parent = child.parent as ViewGroup
                parent.offsetDescendantRectToMyCoords(child, bounds)
                val viewport = android.graphics.Rect().also(parent::getDrawingRect)
                assertTrue("Action clipped by ${parent.javaClass.simpleName}", viewport.contains(bounds))
                child = parent
            }
            val label = requireNotNull(control.layout)
            assertTrue(label.height <= control.height - control.compoundPaddingTop - control.compoundPaddingBottom)
            assertEquals(control.text.length, label.getLineEnd(label.lineCount - 1))
            repeat(label.lineCount) { line ->
                assertEquals(0, label.getEllipsisCount(line))
                assertTrue(label.getLineLeft(line) >= control.scrollX)
                assertTrue(label.getLineRight(line) <= control.scrollX + control.width - control.compoundPaddingLeft - control.compoundPaddingRight)
            }
        }
        assertNull(shadowOf(activity).nextStartedActivity)
    }
}
