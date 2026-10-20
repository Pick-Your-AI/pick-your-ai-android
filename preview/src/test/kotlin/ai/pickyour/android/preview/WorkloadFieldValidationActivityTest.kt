package ai.pickyour.android.preview

import android.app.AlertDialog
import android.os.Bundle
import android.os.Looper
import android.view.KeyEvent
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
import org.robolectric.Shadows.shadowOf
import org.robolectric.android.controller.ActivityController
import org.robolectric.annotation.Config
import org.robolectric.annotation.LooperMode
import org.robolectric.shadows.ShadowAlertDialog
import java.math.BigInteger
import java.nio.file.Files
import java.nio.file.LinkOption.NOFOLLOW_LINKS
import java.security.MessageDigest
import java.util.concurrent.ExecutorService
import java.util.concurrent.TimeUnit

/** Real Activity/worker/store integration in a simulated API 28 runtime, not an installed device. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
@LooperMode(LooperMode.Mode.PAUSED)
class WorkloadFieldValidationActivityTest {
    private lateinit var controller: ActivityController<PreviewActivity>
    private val activity get() = controller.get()
    private val labels = listOf(R.string.workload_region, R.string.workload_currency, R.string.workload_budget,
        R.string.workload_requests, R.string.workload_input_tokens, R.string.workload_output_tokens,
        R.string.workload_concurrency, R.string.workload_latency)

    @Before fun launch() {
        controller = Robolectric.buildActivity(PreviewActivity::class.java).setup()
        await { field(R.string.draft_title).isEnabled && button(R.string.save_draft).isEnabled }
        finishActivityWork()
        assertTrue("The simulated sandbox must start without a saved guest draft", adapter().load() is PreviewDraftLoad.Missing)
    }

    @After fun close() { controller.pause().stop().destroy() }

    @Test fun oversizedRegionIdentifiesTheLocalTextLimitWithoutChangingSavedBytes() =
        assertRejectedField(R.string.workload_region, "r".repeat(16_385), "16,384")

    @Test fun invalidCurrencyIdentifiesItsUppercaseFormatWithoutEchoingInput() {
        assertRejectedField(R.string.workload_currency, "PRIVATE_CURRENCY_CANARY", "uppercase")
        val editor = field(R.string.workload_currency)
        val before = editor.text.toString()
        val error = editor.error.toString()
        editor.requestFocus()
        editor.setSelection(editor.length())
        editor.dispatchKeyEvent(KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_SPACE))
        editor.dispatchKeyEvent(KeyEvent(KeyEvent.ACTION_UP, KeyEvent.KEYCODE_SPACE))
        assertEquals(before + " ", editor.text.toString())
        assertEquals("Another invalid key edit must retain the actionable error", error, editor.error?.toString())
        assertFalse(button(R.string.save_draft).isEnabled)
    }

    @Test fun localeCommaBudgetIdentifiesTheCanonicalDecimalPoint() =
        assertRejectedField(R.string.workload_budget, "12,34", "decimal point")

    @Test fun negativeRequestsIdentifyTheirNonnegativeDailyUnit() =
        assertRejectedField(R.string.workload_requests, "-1", "requests per day")

    @Test fun leadingZeroInputTokensIdentifyTheirWholeNumberFormat() =
        assertRejectedField(R.string.workload_input_tokens, "01", "whole")

    @Test fun oversizedOutputTokensIdentifyTheirExactDigitBound() =
        assertRejectedField(R.string.workload_output_tokens, "9".repeat(129), "128")

    @Test fun zeroConcurrencyIdentifiesItsPositiveLowerBound() =
        assertRejectedField(R.string.workload_concurrency, "0", "1")

    @Test fun fractionalLatencyIdentifiesItsWholeMillisecondUnit() =
        assertRejectedField(R.string.workload_latency, "1.5", "milliseconds")

    @Test fun recreatedInvalidFieldsRecoverFeedbackAndReopenKeepsTheSavedRevision() {
        field(R.string.workload_currency).setText("EUR")
        field(R.string.workload_budget).setText("12.3400")
        save()
        val baseline = loaded().baselineIdentity
        val bytes = savedBytes()
        field(R.string.workload_currency).setText("PRIVATE_CURRENCY_CANARY")
        field(R.string.workload_budget).setText("12,34")
        recreate()
        await { field(R.string.draft_title).isEnabled && hasText(R.string.draft_unsaved) }
        assertEquals("PRIVATE_CURRENCY_CANARY", field(R.string.workload_currency).text.toString())
        assertEquals("12,34", field(R.string.workload_budget).text.toString())
        assertFalse(button(R.string.save_draft).isEnabled)
        assertSavedUnchanged(baseline, bytes)
        assertNotNull("Recreated currency must explain its invalid field", field(R.string.workload_currency).error)
        assertNotNull("Recreated budget must explain its invalid field", field(R.string.workload_budget).error)

        button(R.string.reopen_draft).performClick()
        dialog().getButton(AlertDialog.BUTTON_NEGATIVE).performClick()
        assertEquals("12,34", field(R.string.workload_budget).text.toString())
        assertNotNull(field(R.string.workload_budget).error)
        assertSavedUnchanged(baseline, bytes)
        reopen()
        assertEquals("EUR", field(R.string.workload_currency).text.toString())
        assertEquals("12.3400", field(R.string.workload_budget).text.toString())
        assertNoFieldErrors()
        assertSavedUnchanged(baseline, bytes)
    }

    @Test fun correctingOneFieldKeepsOtherFeedbackUntilTheExactTypedSave() {
        save()
        val baseline = loaded().baselineIdentity
        val bytes = savedBytes()
        field(R.string.workload_budget).setText("12,34")
        field(R.string.workload_latency).setText("0")
        assertFalse(button(R.string.save_draft).isEnabled)
        assertSavedUnchanged(baseline, bytes)
        assertNotNull("Budget feedback must identify the first invalid field", field(R.string.workload_budget).error)
        assertNotNull("Latency feedback must independently identify the second invalid field", field(R.string.workload_latency).error)
        field(R.string.workload_budget).setText("0.000000000001")
        assertNull(field(R.string.workload_budget).error)
        assertNotNull(field(R.string.workload_latency).error)
        assertFalse(button(R.string.save_draft).isEnabled)
        assertSavedUnchanged(baseline, bytes)
        field(R.string.workload_latency).setText("9")
        assertNoFieldErrors()
        save()
        assertNotEquals(baseline, loaded().baselineIdentity)
        assertEquals("0.000000000001", loaded().workloadInput.monthlyBudgetAmount!!.encoded)
        assertEquals(BigInteger.valueOf(9), loaded().workloadInput.maxLatencyMs)
        assertNull(loaded().workloadInput.currency)
    }

    @Test fun unansweredAndCanonicalWideValuesRoundTripWithoutFieldErrors() {
        val wide = "9".repeat(128)
        field(R.string.workload_region).setText("unknown") // Literal user assumption, never a measured numeric value.
        field(R.string.workload_budget).setText("0.0000")
        field(R.string.workload_requests).setText("0")
        field(R.string.workload_input_tokens).setText(wide)
        field(R.string.workload_output_tokens).setText("0")
        field(R.string.workload_concurrency).setText("1")
        field(R.string.workload_latency).setText("1")
        assertNoFieldErrors()
        save()
        val baseline = loaded().baselineIdentity
        assertEquals("unknown", loaded().workloadInput.region)
        assertNull(loaded().workloadInput.currency)
        assertNull(loaded().workloadInput.dataClassification)
        assertEquals("0.0000", loaded().workloadInput.monthlyBudgetAmount!!.encoded)
        assertEquals(BigInteger.ZERO, loaded().workloadInput.requestsPerDay)
        assertEquals(BigInteger(wide), loaded().workloadInput.avgInputTokens)
        assertEquals(BigInteger.ZERO, loaded().workloadInput.avgOutputTokens)
        recreate()
        await { field(R.string.draft_title).isEnabled && hasText(R.string.draft_saved) }
        assertEquals(wide, field(R.string.workload_input_tokens).text.toString())
        assertEquals("0.0000", field(R.string.workload_budget).text.toString())
        assertEquals("", field(R.string.workload_currency).text.toString())
        assertEquals(baseline, loaded().baselineIdentity)
        assertNoFieldErrors()
    }

    @Test fun rejectedOversizedReplacementDoesNotBlameRetainedValidFields() {
        field(R.string.workload_budget).setText("12.3400")
        save()
        val baseline = loaded().baselineIdentity
        val bytes = savedBytes()
        val text = field(R.string.workload_budget).text
        text.replace(0, text.length, "9".repeat(16_386))
        await { hasText(R.string.workload_rejected) }
        assertEquals("12.3400", field(R.string.workload_budget).text.toString())
        assertFalse(button(R.string.save_draft).isEnabled)
        assertNoFieldErrors()
        assertSavedUnchanged(baseline, bytes)
        recreate()
        await { field(R.string.draft_title).isEnabled && hasText(R.string.workload_rejected) }
        assertNoFieldErrors()
        assertSavedUnchanged(baseline, bytes)
        field(R.string.workload_budget).setText("13.00")
        save()
        assertEquals("13.00", loaded().workloadInput.monthlyBudgetAmount!!.encoded)
    }

    @Test fun explicitReopenOfValidPendingInputsDoesNotCreateARevision() {
        field(R.string.workload_budget).setText("12.3400")
        save()
        val baseline = loaded().baselineIdentity
        val bytes = savedBytes()
        field(R.string.workload_budget).setText("13.0000")
        assertTrue(button(R.string.save_draft).isEnabled)
        assertNoFieldErrors()
        button(R.string.reopen_draft).performClick()
        dialog().getButton(AlertDialog.BUTTON_NEGATIVE).performClick()
        assertEquals("13.0000", field(R.string.workload_budget).text.toString())
        assertSavedUnchanged(baseline, bytes)
        reopen()
        assertEquals("12.3400", field(R.string.workload_budget).text.toString())
        assertNoFieldErrors()
        assertSavedUnchanged(baseline, bytes)
    }

    private fun assertRejectedField(label: Int, invalid: String, guidance: String) {
        save()
        val baseline = loaded().baselineIdentity
        val bytes = savedBytes()
        val editor = field(label)
        editor.setText(invalid)
        assertEquals(invalid, editor.text.toString())
        assertFalse(button(R.string.save_draft).isEnabled)
        // Invoke even the disabled control: the actual save handler must reject this input too.
        button(R.string.save_draft).performClick()
        assertSavedUnchanged(baseline, bytes)
        val error = editor.error?.toString()
        assertNotNull("The rejected field must expose actionable feedback", error)
        assertTrue("The field error must describe its unit, range or format", error!!.contains(guidance))
        labels.filter { it != label }.forEach { assertNull("Valid fields must not be blamed", field(it).error) }
        if (label == R.string.workload_currency) {
            assertFalse("Static field feedback must not echo invalid input", error.contains(invalid))
            val info = editor.createAccessibilityNodeInfo()
            assertTrue("Native field metadata must mark invalid content", info.isContentInvalid)
            assertEquals(error, info.error.toString())
            info.recycle()
        }
    }

    private fun views(root: View = activity.window.decorView): List<View> = buildList {
        add(root)
        if (root is ViewGroup) for (index in 0 until root.childCount) addAll(views(root.getChildAt(index)))
    }
    private fun button(label: Int) = views().filterIsInstance<Button>().single { it.text.toString() == activity.getString(label) }
    private fun field(label: Int): EditText {
        val caption = views().filterIsInstance<TextView>().single { it.text.toString() == activity.getString(label) }
        assertNotEquals(View.NO_ID, caption.labelFor)
        return activity.findViewById(caption.labelFor)
    }
    private fun hasText(label: Int) = views().filterIsInstance<TextView>().any { it.text.toString() == activity.getString(label) }
    private fun assertNoFieldErrors() = labels.forEach { assertNull("A valid field must not have an error", field(it).error) }
    private fun adapter() = PreviewDraftLibrary(activity.noBackupFilesDir.toPath()).openLegacy()
    private fun loaded() = (readDraftAfterOwnedWork(activity, ::adapter) as PreviewDraftLoad.Available).record
    private fun dialog() = ShadowAlertDialog.getLatestAlertDialog()!!
    private fun save() {
        assertTrue(button(R.string.save_draft).isEnabled)
        button(R.string.save_draft).performClick()
        await { field(R.string.draft_title).isEnabled && hasText(R.string.draft_saved) && !button(R.string.save_draft).isEnabled }
        finishActivityWork()
    }
    private fun reopen() {
        button(R.string.reopen_draft).performClick()
        dialog().getButton(AlertDialog.BUTTON_POSITIVE).performClick()
        await { field(R.string.draft_title).isEnabled && hasText(R.string.draft_saved) }
        finishActivityWork()
    }
    private fun recreate() {
        val state = Bundle()
        controller.pause().saveInstanceState(state).stop().destroy()
        controller = Robolectric.buildActivity(PreviewActivity::class.java).create(state).start().resume().visible()
    }
    private fun await(condition: () -> Boolean) {
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(15)
        while (System.nanoTime() < deadline) {
            shadowOf(Looper.getMainLooper()).idle()
            if (condition()) return
            Thread.sleep(10)
        }
        fail("Timed out waiting for the explicit workload UI state")
    }
    private fun finishActivityWork() {
        shadowOf(Looper.getMainLooper()).idle()
        val field = PreviewActivity::class.java.getDeclaredField("worker").apply { isAccessible = true }
        (field.get(activity) as ExecutorService).submit {}.get(15, TimeUnit.SECONDS)
        shadowOf(Looper.getMainLooper()).idle()
    }
    private fun savedBytes(): Map<String, String> {
        val root = activity.noBackupFilesDir.toPath()
        return Files.walk(root).use { paths ->
            paths.iterator().asSequence().filter { Files.isRegularFile(it, NOFOLLOW_LINKS) }.associate { path ->
                root.relativize(path).toString() to MessageDigest.getInstance("SHA-256")
                    .digest(Files.readAllBytes(path)).joinToString("") { "%02x".format(it) }
            }.toSortedMap()
        }
    }
    private fun assertSavedUnchanged(baseline: String, bytes: Map<String, String>) {
        finishActivityWork()
        assertEquals(baseline, loaded().baselineIdentity)
        assertEquals("Rejected or discarded edits must not alter durable bytes", bytes, savedBytes())
    }
}
