package ai.pickyour.android.preview

import android.app.AlertDialog
import android.graphics.Rect
import android.os.Bundle
import android.os.Looper
import android.os.Parcel
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.CheckBox
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
import java.math.BigInteger
import java.nio.file.Files
import java.security.MessageDigest
import java.util.concurrent.CountDownLatch
import java.util.concurrent.ExecutorService
import java.util.concurrent.TimeUnit

/** Native controls and canonical storage in simulation; no installed-process qualification. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
@LooperMode(LooperMode.Mode.PAUSED)
class GuardrailSelectionActivityTest {
    private lateinit var controller: ActivityController<PreviewActivity>
    private val activity get() = controller.get()
    private val kinds = listOf("guardrail", "evaluation_suite")
    private val entered = mapOf(
        "guardrail" to """{"enforcement":"audit","failure_action":"fail_open"}""",
        "evaluation_suite" to """{"minimum_score":-9007199254740993.123456789,"gate_required":false,"dataset_size":999999999999999999999999}""",
    )

    @Before fun launch() {
        controller = Robolectric.buildActivity(PreviewActivity::class.java).setup()
        await { button(R.string.save_draft).isEnabled && button(R.string.retry_catalog).isEnabled }
        assertTrue(adapter().load() is PreviewDraftLoad.Missing)
    }
    @After fun close() { controller.pause().stop().destroy() }

    private fun views(root: View = activity.window.decorView): List<View> = buildList {
        add(root)
        if (root is ViewGroup) repeat(root.childCount) { addAll(views(root.getChildAt(it))) }
    }
    private fun button(label: Int) = views().filterIsInstance<Button>().single { it.text.toString() == activity.getString(label) }
    private fun field(root: View, label: Int): EditText {
        val caption = views(root).filterIsInstance<TextView>().single { it.text.toString() == activity.getString(label) }
        assertNotEquals(View.NO_ID, caption.labelFor)
        return root.findViewById(caption.labelFor)
    }
    private fun field(dialog: AlertDialog, label: Int) = field(dialog.window!!.decorView, label)
    private fun checkbox(dialog: AlertDialog) = views(dialog.window!!.decorView).filterIsInstance<CheckBox>().single()
    private fun dialog() = requireNotNull(ShadowAlertDialog.getLatestAlertDialog())
    private fun idle() = shadowOf(Looper.getMainLooper()).idle()
    private fun await(condition: () -> Boolean) {
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(15)
        while (System.nanoTime() < deadline) {
            idle()
            if (condition()) return
            Thread.sleep(10)
        }
        fail("Timed out waiting for explicit guardrail/evaluation selection state")
    }
    private fun worker() = PreviewActivity::class.java.getDeclaredField("worker").apply { isAccessible = true }.get(activity) as ExecutorService
    private fun finishWork() { repeat(3) { idle(); worker().submit {}.get(15, TimeUnit.SECONDS); idle() } }
    private fun currentId() = PreviewActivity::class.java.getDeclaredField("currentDraftId").apply { isAccessible = true }.get(activity) as PreviewDraftId
    private fun library() = PreviewDraftLibrary(activity.noBackupFilesDir.toPath())
    private fun adapter() = library().open(currentId())
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
    private fun createEmpty(): PreviewStoredDraft {
        val previous = currentId()
        button(R.string.library_new).performClick()
        dialog().getButton(AlertDialog.BUTTON_POSITIVE).performClick()
        await { currentId() != previous && button(R.string.component_choose_catalog).isEnabled }
        finishWork()
        return loaded().also { assertTrue(it.components.selections.isEmpty()) }
    }
    private fun select(picker: AlertDialog, matches: (String) -> Boolean): AlertDialog {
        val index = (0 until picker.listView.adapter.count).single { matches(picker.listView.adapter.getItem(it).toString()) }
        shadowOf(picker).clickOnItem(index)
        await { dialog() !== picker && dialog().isShowing }
        return dialog()
    }
    private fun choices(kind: String): AlertDialog {
        await { button(R.string.component_choose_catalog).isEnabled }
        button(R.string.component_choose_catalog).performClick()
        return select(dialog()) { it.startsWith("${componentTokenForLookup(activity, kind)} ·") }
    }
    private fun addForm(kind: String) = select(choices(kind)) { it == activity.getString(R.string.component_add) }
    private fun action(selection: LocalSelection, label: Int): AlertDialog {
        button(R.string.component_manage).performClick()
        val actions = select(dialog()) { it.endsWith(" · ${componentTokenForLookup(activity, selection.id)}") }
        return select(actions) { it == activity.getString(label) }
    }
    private fun commit(form: AlertDialog, previous: PreviewStoredDraft = loaded()): PreviewStoredDraft {
        form.getButton(AlertDialog.BUTTON_POSITIVE).performClick()
        await { !form.isShowing && loaded().revision == previous.revision + 1 }
        finishWork()
        return loaded()
    }
    private fun add(kind: String, role: String = "primary", enabled: Boolean = false, settings: String = "{}"): PreviewStoredDraft {
        val form = addForm(kind)
        field(form, R.string.component_role).setText(role)
        field(form, R.string.component_settings).setText(settings)
        checkbox(form).isChecked = enabled
        return commit(form)
    }
    private fun closeDialog(form: AlertDialog) {
        form.getButton(AlertDialog.BUTTON_NEGATIVE).performClick()
        await { !form.isShowing }
    }
    private fun roundTrip(state: Bundle): Bundle {
        val written = Parcel.obtain()
        val read = Parcel.obtain()
        return try {
            written.writeBundle(state)
            val bytes = written.marshall()
            read.unmarshall(bytes, 0, bytes.size); read.setDataPosition(0)
            requireNotNull(read.readBundle(javaClass.classLoader))
        } finally { written.recycle(); read.recycle() }
    }
    private fun recreate(afterCheckpoint: (Bundle) -> Unit = {}) {
        val state = Bundle()
        controller.pause().saveInstanceState(state)
        afterCheckpoint(state)
        controller.stop().destroy()
        controller = Robolectric.buildActivity(PreviewActivity::class.java).create(roundTrip(state)).start().resume().visible()
        finishWork()
    }
    private fun reviewPending(): AlertDialog {
        assertEquals(View.VISIBLE, button(R.string.component_recovery_review).visibility)
        button(R.string.component_recovery_review).performClick()
        return dialog()
    }

    private fun coreGuidance(reconciliationRequired: Boolean = false): TextView {
        val section = views().filterIsInstance<T6PlanningSection>().single()
        val heading = views(section).filterIsInstance<TextView>().single {
            it.text.toString() == activity.getString(R.string.planning_heading)
        }
        assertTrue(heading.isShown)
        assertTrue(heading.isAccessibilityHeading)
        val text = views(section).filterIsInstance<TextView>().single {
            it.text.toString() == activity.getString(R.string.planning_notice)
        }
        assertTrue(text.isShown)
        val labels = listOf("guardrail" to R.string.planning_guardrail,
            "evaluation_suite" to R.string.planning_evaluation,
            "deployment_target" to R.string.planning_deployment,
            "monitoring_stack" to R.string.planning_monitoring,
            "cost_policy" to R.string.planning_cost)
        val texts = views(section).filterIsInstance<TextView>()
        // A disk head is not acknowledged UI authority while recovered input requires reconciliation.
        val record = (adapter().load() as? PreviewDraftLoad.Available)?.record?.takeUnless { reconciliationRequired }
        val expectedSource = when {
            reconciliationRequired -> activity.getString(R.string.planning_unavailable)
            record == null -> activity.getString(R.string.planning_unsaved)
            else -> activity.getString(R.string.planning_saved, record.revision.toString())
        }
        assertTrue("Expected shared planning source: $expectedSource", texts.any { it.text.toString() == expectedSource })
        for ((kind, label) in labels) {
            val index = texts.indexOfFirst { it.text.toString() == activity.getString(label) }
            assertTrue(index >= 0 && index + 1 < texts.size)
            val selected = record?.components?.selections?.filter { it.kind == kind }
            val expected = when {
                selected == null -> activity.getString(R.string.planning_choices_unavailable)
                selected.isEmpty() -> activity.getString(R.string.planning_choices_missing)
                else -> activity.getString(R.string.planning_choices_recorded,
                    selected.count { it.enabled }, selected.count { !it.enabled })
            }
            assertEquals("Only acknowledged $kind choices belong to the shared summary", expected, texts[index + 1].text.toString())
        }
        for (label in listOf(R.string.planning_guardrail_evidence, R.string.planning_evaluation_evidence,
            R.string.planning_deployment_evidence, R.string.planning_monitoring_evidence, R.string.planning_cost_evidence))
            assertTrue(texts.any { it.isShown && it.text.toString().contains(activity.getString(label)) })
        val tierControls = views(views().filterIsInstance<OptionalTierSection>().single()).filterIsInstance<Button>()
        assertEquals(setOf("T3", "T4", "T5"), tierControls.map { it.text.toString().substringBefore(':') }.toSet())
        return text
    }

    private fun setTier(tier: OptionalTier, label: Int): PreviewStoredDraft {
        val before = loaded()
        val control = views(views().filterIsInstance<OptionalTierSection>().single()).filterIsInstance<Button>()
            .single { it.text.toString().startsWith("${tier.name}:") }
        assertTrue(control.isEnabled)
        control.performClick()
        val confirmation = select(dialog()) { it == activity.getString(label) }
        confirmation.getButton(AlertDialog.BUTTON_POSITIVE).performClick()
        await { control.isEnabled && control.text.toString().startsWith("${tier.name}: ${activity.getString(label)};") &&
            views().filterIsInstance<TextView>().any { it.text.toString() == activity.getString(R.string.draft_saved) } }
        finishWork()
        return loaded().also { assertEquals(before.revision + 1, it.revision) }
    }

    @Test fun coreGuidanceRemainsPresentBeforeSavingAndWhenCatalogChoicesAreUnavailable() {
        coreGuidance()
        val bytes = disk()
        assertTrue(adapter().load() is PreviewDraftLoad.Missing)
        views().filterIsInstance<SelectionEditSection>().single().catalog(emptyList(), null)
        assertFalse(button(R.string.component_choose_catalog).isEnabled)
        coreGuidance()
        assertTrue(views().filterIsInstance<TextView>().any { it.text.toString() == activity.getString(R.string.unknown) })
        assertEquals(bytes, disk())
        assertNull(shadowOf(activity).nextStartedActivity)
    }

    @Test fun bothCoreKindsRequireExplicitInputsAndReopenWithoutAnotherSelectionOrGate() {
        val empty = createEmpty()
        val id = currentId()
        val pointer = PreviewActiveDraftStore(activity.noBackupFilesDir.toPath()).read()
        val catalog = activity.assets.open("catalog.json").use { requireNotNull(PreviewCatalogBrowser.read(it.readBytes())) }
        for ((index, kind) in kinds.withIndex()) {
            val before = loaded()
            val form = addForm(kind)
            assertEquals("", field(form, R.string.component_role).text.toString())
            assertEquals("{}", field(form, R.string.component_settings).text.toString())
            assertFalse(checkbox(form).isChecked)
            assertEquals(before.baselineIdentity, loaded().baselineIdentity)
            field(form, R.string.component_role).setText("entered")
            val saved = commit(form)
            val selection = saved.components.selections.single { it.kind == kind }
            val item = catalog.items.single { it.kind == kind }
            assertEquals(item.id, selection.catalogItemId)
            assertEquals(item.version, selection.catalogItemVersion)
            assertEquals("entered", selection.role)
            assertFalse(selection.enabled)
            assertEquals("{}", PreviewSelectionForms.settingsText(selection))
            assertEquals(index + 1, saved.components.selections.size)
            assertEquals(empty.fields, saved.fields)
            assertEquals(empty.contextInput, saved.contextInput)
            assertEquals(empty.workloadInput, saved.workloadInput)
            assertEquals(empty.optionalTiers, saved.optionalTiers)
            assertTrue(saved.components.links.isEmpty())
            coreGuidance()
        }
        val saved = loaded()
        val bytes = disk()
        recreate()
        assertEquals(id, currentId())
        assertEquals(pointer, PreviewActiveDraftStore(activity.noBackupFilesDir.toPath()).read())
        assertEquals(saved.baselineIdentity, loaded().baselineIdentity)
        assertEquals(saved.components, loaded().components)
        assertEquals(bytes, disk())
        coreGuidance()
        assertNull(shadowOf(activity).nextStartedActivity)
    }

    @Test fun invalidEnumsUnknownsAndProposedEvidenceKeepExactInputWithoutSaving() {
        val invalid = mapOf(
            "guardrail" to listOf("""{"enforcement":"unknown"}""", """{"enforcement":"BLOCK"}""",
                """{"enforcement":"flag"}""", """{"failure_action":"fail-closed"}""", """{"failure_action":null}""",
                """{"targets":[]}""", """{"safety_approved":true}""", """{"enforcement":"audit","enforcement":"block"}"""),
            "evaluation_suite" to listOf("""{"minimum_score":"unknown"}""", """{"minimum_score":null}""",
                """{"gate_required":"false"}""", """{"dataset_size":0}""", """{"dataset_size":1.5}""",
                """{"dataset_refs":[]}""", """{"measured_score":1}""", """{"evaluation_report_ref":"entered"}""",
                """{"minimum_score":1,"minimum_score":2}"""),
        )
        for ((kind, attempts) in invalid) {
            val before = createEmpty()
            val bytes = disk()
            val form = addForm(kind)
            field(form, R.string.component_role).setText("entered")
            for (raw in attempts) {
                field(form, R.string.component_settings).setText(raw)
                form.getButton(AlertDialog.BUTTON_POSITIVE).performClick()
                await { form.getButton(AlertDialog.BUTTON_POSITIVE).isEnabled && views(form.window!!.decorView).filterIsInstance<TextView>().any {
                    it.text.toString() == activity.getString(R.string.component_invalid)
                } }
                assertEquals(raw, field(form, R.string.component_settings).text.toString())
                assertEquals(before.baselineIdentity, loaded().baselineIdentity)
                assertEquals(bytes, disk())
                assertNull(shadowOf(activity).nextStartedActivity)
            }
            field(form, R.string.component_settings).setText(entered.getValue(kind))
            val saved = commit(form)
            assertEquals(entered.getValue(kind), PreviewSelectionForms.settingsText(saved.components.selections.single()))
            assertEquals(before.optionalTiers, saved.optionalTiers)
            val configure = action(saved.components.selections.single(), R.string.component_configure)
            assertEquals(entered.getValue(kind), field(configure, R.string.component_settings).text.toString())
            closeDialog(configure)
            coreGuidance()
        }
    }

    @Test fun exactCoreInputsAndGuidanceSurviveEveryOptionalTierStateAndRecreation() {
        createEmpty()
        for (kind in kinds) add(kind, enabled = true, settings = entered.getValue(kind))
        val initial = loaded()
        assertTrue(initial.optionalTiers.values.all { it.activation == Activation.DISABLED })
        assertTrue(initial.components.selections.all { it.enabled })
        for (selection in initial.components.selections) assertEquals(entered.getValue(selection.kind), PreviewSelectionForms.settingsText(selection))
        assertFalse(JSONObject(PreviewSelectionForms.settingsText(initial.components.selections.last())).getBoolean("gate_required"))
        for (tier in OptionalTier.entries) {
            for (label in listOf(R.string.tier_enabled, R.string.tier_unknown, R.string.tier_disabled)) {
                val saved = setTier(tier, label)
                assertEquals(initial.components, saved.components)
                assertEquals(initial.fields, saved.fields)
                assertEquals(initial.contextInput, saved.contextInput)
                assertEquals(initial.workloadInput, saved.workloadInput)
                coreGuidance()
            }
        }
        val before = loaded()
        val bytes = disk()
        recreate()
        assertEquals(bytes, disk())
        assertEquals(before.baselineIdentity, loaded().baselineIdentity)
        assertTrue(loaded().optionalTiers.values.all { it.activation == Activation.DISABLED })
        coreGuidance()
        val guardrail = loaded().components.selections.first()
        val confirmation = action(guardrail, R.string.component_disable)
        assertTrue(views(confirmation.window!!.decorView).filterIsInstance<TextView>().any {
            it.text.toString().contains(activity.getString(R.string.component_core_input_guidance))
        })
        val changed = commit(confirmation)
        val disabled = changed.components.selections.single { it.id == guardrail.id }
        assertFalse(disabled.enabled)
        assertEquals(entered.getValue("guardrail"), PreviewSelectionForms.settingsText(disabled))
        assertEquals(before.components.selections.last(), changed.components.selections.last())
        assertEquals(before.optionalTiers, changed.optionalTiers)
        coreGuidance()
        assertNull(shadowOf(activity).nextStartedActivity)
    }

    @Test fun severalEnabledGuardrailRolesCoexistAndDuplicateRolesCannotDisplaceAnotherControl() {
        createEmpty()
        val first = add("guardrail", role = "source_a", enabled = true, settings = """{"enforcement":"audit"}""")
            .components.selections.single()
        val pair = add("guardrail", role = "source_b", enabled = true, settings = """{"enforcement":"block","failure_action":"fail_closed"}""")
        val second = pair.components.selections.last()
        assertEquals(2, pair.components.selections.count { it.enabled })
        val duplicate = action(second, R.string.component_edit_role)
        field(duplicate, R.string.component_role).setText("source_a")
        val bytes = disk()
        duplicate.getButton(AlertDialog.BUTTON_POSITIVE).performClick()
        await { duplicate.getButton(AlertDialog.BUTTON_POSITIVE).isEnabled && views(duplicate.window!!.decorView).filterIsInstance<TextView>().any {
            it.text.toString().contains(SelectionRejection.DUPLICATE_KIND_ROLE.name)
        } }
        assertEquals(pair.baselineIdentity, loaded().baselineIdentity)
        assertEquals(bytes, disk())
        closeDialog(duplicate)
        commit(action(first, R.string.component_disable))
        val removed = commit(action(first, R.string.component_remove))
        assertEquals(listOf(second), removed.components.selections)
        assertTrue(removed.components.selections.single().enabled)
        assertEquals(pair.optionalTiers, removed.optionalTiers)
        coreGuidance()
    }

    @Test fun unchangedCoreSettingsRoleAndCatalogReplacementKeepTheExistingRevision() {
        createEmpty()
        for (kind in kinds) add(kind, enabled = true, settings = entered.getValue(kind))
        val before = loaded()
        val bytes = disk()
        fun unchanged(form: AlertDialog) {
            form.getButton(AlertDialog.BUTTON_POSITIVE).performClick()
            await { !form.isShowing && views().filterIsInstance<TextView>().any { it.text.toString() == activity.getString(R.string.component_noop) } }
            finishWork()
            assertEquals(before.baselineIdentity, loaded().baselineIdentity)
            assertEquals(before.components, loaded().components)
            assertEquals(before.optionalTiers, loaded().optionalTiers)
            assertEquals(bytes, disk())
        }
        for (selection in before.components.selections) {
            val settings = action(selection, R.string.component_configure)
            assertEquals(entered.getValue(selection.kind), field(settings, R.string.component_settings).text.toString())
            unchanged(settings)
            val role = action(selection, R.string.component_edit_role)
            assertEquals(selection.role, field(role, R.string.component_role).text.toString())
            unchanged(role)
            unchanged(select(choices(selection.kind)) { it.endsWith(" · ${selection.id}") })
        }
        coreGuidance()
        assertNull(shadowOf(activity).nextStartedActivity)
    }

    @Test fun pendingCoreFormsKeepBothKindsAndExactInputAcrossParcelRecreationWithoutOldConsent() {
        for (kind in kinds) {
            val before = createEmpty()
            val form = addForm(kind)
            val raw = "{\"pending\":\"$kind"
            field(form, R.string.component_role).setText("Pending Role")
            field(form, R.string.component_settings).setText(raw)
            checkbox(form).isChecked = true
            val retired = form.getButton(AlertDialog.BUTTON_POSITIVE)
            var selectionId: String? = null
            recreate { state ->
                val checkpoint = (ComponentFormRecovery.read(roundTrip(state)) as ComponentFormRecovery.Pending).form
                assertEquals(kind, checkpoint.kind)
                assertEquals(raw, checkpoint.settings)
                selectionId = checkpoint.selectionId
            }
            val restored = reviewPending()
            assertEquals(raw, field(restored, R.string.component_settings).text.toString())
            assertEquals("Pending Role", field(restored, R.string.component_role).text.toString())
            assertTrue(checkbox(restored).isChecked)
            retired.performClick(); finishWork()
            assertEquals(before.baselineIdentity, loaded().baselineIdentity)
            field(restored, R.string.component_role).setText("entered")
            field(restored, R.string.component_settings).setText(entered.getValue(kind))
            val saved = commit(restored)
            assertEquals(selectionId, saved.components.selections.single().id)
            assertEquals(kind, saved.components.selections.single().kind)
            assertEquals(entered.getValue(kind), PreviewSelectionForms.settingsText(saved.components.selections.single()))
            assertEquals(before.optionalTiers, saved.optionalTiers)
            coreGuidance()
        }
    }

    @Test fun canceledOrCatalogStaleCoreControlsCannotSaveOrSubstituteAnotherSnapshot() {
        save()
        for (kind in kinds) {
            val form = addForm(kind)
            field(form, R.string.component_role).setText("entered")
            field(form, R.string.component_settings).setText(entered.getValue(kind))
            val retired = form.getButton(AlertDialog.BUTTON_POSITIVE)
            closeDialog(form)
            val bytes = disk()
            retired.performClick(); finishWork()
            assertEquals(bytes, disk())
        }
        val form = addForm("evaluation_suite")
        field(form, R.string.component_role).setText("entered")
        field(form, R.string.component_settings).setText(entered.getValue("evaluation_suite"))
        val before = disk()
        val document = activity.assets.open("catalog.json").bufferedReader(Charsets.UTF_8).use { JSONObject(it.readText()) }
        val old = requireNotNull(PreviewCatalogBrowser.read(document.toString().toByteArray()))
        document.put("revision", BigInteger(document.get("revision").toString()).add(BigInteger.ONE))
        val changed = requireNotNull(PreviewCatalogBrowser.read(document.toString().toByteArray()))
        assertNotEquals(old.hash, changed.hash)
        views().filterIsInstance<SelectionEditSection>().single().catalog(changed.items, changed.hash)
        form.getButton(AlertDialog.BUTTON_POSITIVE).performClick(); finishWork()
        assertTrue(form.isShowing)
        assertEquals(before, disk())
        assertTrue(loaded().components.selections.isEmpty())
        coreGuidance()
    }

    @Test fun aNewerSavedHeadKeepsRecoveredEvaluationInputReadOnlyWithoutRebasingIt() {
        save()
        val before = loaded()
        val form = addForm("evaluation_suite")
        val raw = entered.getValue("evaluation_suite")
        field(form, R.string.component_role).setText("entered")
        field(form, R.string.component_settings).setText(raw)
        finishWork()
        val newer = (adapter().save(before, before.fields.copy(title = "other writer")) as PreviewDraftSave.Saved).record
        val bytes = disk()
        recreate()
        val review = reviewPending()
        assertTrue(views(review.window!!.decorView).filterIsInstance<EditText>().isEmpty())
        assertTrue(views(review.window!!.decorView).filterIsInstance<TextView>().any { it.text.toString() == raw && it.isTextSelectable })
        assertNotEquals(View.VISIBLE, review.getButton(AlertDialog.BUTTON_POSITIVE).visibility)
        form.getButton(AlertDialog.BUTTON_POSITIVE).performClick(); finishWork()
        assertEquals(newer.baselineIdentity, loaded().baselineIdentity)
        assertEquals(bytes, disk())
        assertTrue(loaded().components.selections.isEmpty())
        coreGuidance(reconciliationRequired = true)
    }

    @Test fun aSavingGuardrailCheckpointCannotReplayAfterItsOriginalWriteIsAcknowledged() {
        save()
        val before = loaded()
        val form = addForm("guardrail")
        val raw = """{"enforcement":"redact","failure_action":"fail_closed"}"""
        field(form, R.string.component_role).setText("entered")
        field(form, R.string.component_settings).setText(raw)
        checkbox(form).isChecked = true
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        worker().execute { entered.countDown(); check(release.await(20, TimeUnit.SECONDS)) }
        try {
            assertTrue(entered.await(5, TimeUnit.SECONDS))
            form.getButton(AlertDialog.BUTTON_POSITIVE).performClick()
            assertFalse(form.getButton(AlertDialog.BUTTON_POSITIVE).isEnabled)
            recreate { state ->
                assertTrue((ComponentFormRecovery.read(roundTrip(state)) as ComponentFormRecovery.Pending).form.wasSaving)
                release.countDown()
                await { !form.isShowing && loaded().revision == before.revision + 1 }
                finishWork()
            }
        } finally { release.countDown() }
        val saved = loaded()
        val review = reviewPending()
        assertTrue(views(review.window!!.decorView).filterIsInstance<EditText>().isEmpty())
        assertTrue(views(review.window!!.decorView).filterIsInstance<TextView>().any { it.text.toString() == raw })
        assertTrue(saved.components.selections.single().enabled)
        assertEquals(raw, PreviewSelectionForms.settingsText(saved.components.selections.single()))
        assertEquals(before.revision + 1, saved.revision)
        assertEquals(before.optionalTiers, saved.optionalTiers)
        form.getButton(AlertDialog.BUTTON_POSITIVE).performClick(); finishWork()
        assertEquals(saved.baselineIdentity, loaded().baselineIdentity)
        coreGuidance(reconciliationRequired = true)
    }

    @Test
    @Config(qualifiers = "ar-rEG-ldrtl-w320dp-h640dp")
    @GraphicsMode(GraphicsMode.Mode.NATIVE)
    fun narrowRtlLargeTextKeepsCoreGuidanceAndBothFormsReachable() {
        RuntimeEnvironment.setFontScale(2f)
        controller.pause().stop().destroy()
        controller = Robolectric.buildActivity(PreviewActivity::class.java).setup()
        await { button(R.string.save_draft).isEnabled && button(R.string.retry_catalog).isEnabled }
        save()
        val appRoot = activity.window.decorView
        measure(appRoot)
        val explanation = coreGuidance()
        val layout = requireNotNull(explanation.layout)
        assertEquals(explanation.text.length, layout.getLineEnd(layout.lineCount - 1))
        // The shared notice and each form explanation must be reachable at both ends.
        for (line in listOf(0, layout.lineCount - 1)) reachable(explanation, appRoot,
            Rect(0, explanation.totalPaddingTop + layout.getLineTop(line), explanation.width,
                explanation.totalPaddingTop + layout.getLineBottom(line)))
        val guidance = mapOf("guardrail" to R.string.component_guardrail_guidance,
            "evaluation_suite" to R.string.component_evaluation_guidance)
        for (kind in kinds) {
            val form = addForm(kind)
            val role = field(form, R.string.component_role).apply { setText("entered") }
            val settings = field(form, R.string.component_settings).apply { setText(entered.getValue(kind)) }
            val root = form.window!!.decorView
            measure(root)
            val density = activity.resources.displayMetrics.density
            assertEquals(View.LAYOUT_DIRECTION_RTL, root.layoutDirection)
            for (control in views(root).filterIsInstance<Button>().filter { it.visibility == View.VISIBLE && it.text.isNotBlank() }) {
                assertTrue(control.height >= (48 * density).toInt())
                assertTrue(control.width >= (48 * density).toInt())
                reachable(control, root)
            }
            reachable(role, root); reachable(settings, root)
            for (label in listOf(guidance.getValue(kind), R.string.component_core_input_guidance,
                R.string.component_core_guidance_unavailable)) {
                val explanationView = views(root).filterIsInstance<TextView>().single { it.text.toString() == activity.getString(label) }
                assertTrue(explanationView.isShown)
                val explanationLayout = requireNotNull(explanationView.layout)
                for (line in listOf(0, explanationLayout.lineCount - 1)) reachable(explanationView, root,
                    Rect(0, explanationView.totalPaddingTop + explanationLayout.getLineTop(line), explanationView.width,
                        explanationView.totalPaddingTop + explanationLayout.getLineBottom(line)))
            }
            for (view in views(root).filterIsInstance<TextView>().filter { it.isShown && it.text.isNotBlank() }) {
                val lines = requireNotNull(view.layout)
                assertTrue(lines.lineCount > 0)
                assertEquals(view.text.length, lines.getLineEnd(lines.lineCount - 1))
                for (line in 0 until lines.lineCount) assertEquals(0, lines.getEllipsisCount(line))
            }
            assertEquals("entered", commit(form).components.selections.single { it.kind == kind }.role)
        }
    }

    private fun measure(root: View) {
        val density = activity.resources.displayMetrics.density
        val width = (320 * density).toInt()
        val height = (640 * density).toInt()
        root.layoutDirection = View.LAYOUT_DIRECTION_RTL
        root.measure(View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY), View.MeasureSpec.makeMeasureSpec(height, View.MeasureSpec.EXACTLY))
        root.layout(0, 0, width, height)
    }

    private fun reachable(control: View, root: View, target: Rect = Rect(0, 0, control.width, control.height)) {
        control.requestRectangleOnScreen(Rect(target), true)
        val bounds = Rect(target)
        var child = control
        while (child !== root) {
            val parent = child.parent as ViewGroup
            parent.offsetDescendantRectToMyCoords(child, bounds)
            assertTrue("Guardrail/evaluation control or text must be reachable", Rect().also(parent::getDrawingRect).contains(bounds))
            child = parent
        }
    }
}
