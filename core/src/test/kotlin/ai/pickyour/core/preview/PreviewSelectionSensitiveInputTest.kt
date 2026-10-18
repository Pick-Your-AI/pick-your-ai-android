package ai.pickyour.core.preview

import ai.pickyour.core.drafts.*
import com.fasterxml.jackson.databind.node.ObjectNode
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path
import java.security.MessageDigest

/** Synthetic URI inputs only; no URI is fetched and no credential is supplied to an external service. */
class PreviewSelectionSensitiveInputTest {
    @TempDir lateinit var root: Path
    private val mapper = SelectionFixtures.mapper
    private val catalog = SelectionFixtures.catalog()
    private val fields = linkedMapOf("model_provider" to "api_base_url", "model" to "weights_uri",
        "mcp_server" to "endpoint", "integration" to "endpoint")
    private val ordinary = "custom+local://example.invalid:9443/a/%2F?q=exact#retained"
    private val entered = "https://local-user:synthetic-password@example.invalid/v1"

    private fun item(kind: String): PreviewCatalogItem {
        val selection = SelectionFixtures.selection(kind)
        return PreviewCatalogItem(selection.catalogItemId, "synthetic privacy input", kind,
            selection.catalogItemVersion, PreviewCatalogKinds.all.single { it.id == kind }.tier)
    }
    private fun settings(kind: String, uri: String): ObjectNode = mapper.createObjectNode().put(fields.getValue(kind), uri)
    private fun selection(kind: String, value: ObjectNode): LocalSelection = LocalSelection.fromJson(
        SelectionFixtures.node(kind).also { it.set<ObjectNode>("settings", value) })
    private fun add(kind: String, value: ObjectNode) = PreviewSelectionForms.add("privacy_selection", item(kind), catalog.hash.hex,
        PreviewSelectionForm("entered", false, value.toString()))
    private fun command(value: PreviewSelectionPreparation) = (value as PreviewSelectionPreparation.Ready).command
    private fun stored(kind: String, value: ObjectNode): Triple<PreviewDraftLibrary, PreviewLibraryCreate.Saved, PreviewStoredDraft> {
        val library = PreviewDraftLibrary(Files.createDirectory(root.resolve(kind)))
        val created = library.create() as PreviewLibraryCreate.Saved
        val record = (library.open(created.id).applySelection(created.record, command(add(kind, value)), catalog)
            as PreviewSelectionSave.Saved).record
        return Triple(library, created, record)
    }
    private fun bytes(): Map<String, String> = Files.walk(root).use { paths ->
        paths.filter(Files::isRegularFile).toList().associate { path ->
            root.relativize(path).toString() to MessageDigest.getInstance("SHA-256")
                .digest(Files.readAllBytes(path)).joinToString("") { "%02x".format(it) }
        }
    }
    private fun rejected(kind: String, uri: String = entered) {
        val value = settings(kind, uri)
        val original = selection(kind, settings(kind, ordinary))
        val added = add(kind, value)
        val configured = PreviewSelectionForms.configure(original, value.toString())
        assertAll(kind,
            { assertEquals(PreviewSelectionPreparation.Invalid, added, "New input must not produce an add command") },
            { assertEquals(PreviewSelectionPreparation.Invalid, configured, "New input must not produce a configure command") },
            { assertEquals(ordinary, original.settings[fields.getValue(kind)].textValue()) },
            { assertFalse(listOf(added, configured).joinToString().contains("synthetic-password")) })
    }

    @Test fun `new provider settings reject URI authority userinfo`() = rejected("model_provider")
    @Test fun `new model settings reject URI authority userinfo`() = rejected("model")
    @Test fun `new MCP settings reject URI authority userinfo`() = rejected("mcp_server")
    @Test fun `new integration settings reject URI authority userinfo`() = rejected("integration")

    @Test fun `encoded empty and non-server authority userinfo is rejected`() {
        val values = listOf(
            "https://local-user:synthetic%2Dpassword@example.invalid/v1",
            "https://local%40user:synthetic%3Apassword@example.invalid/v1",
            "https://local-user@example.invalid/v1",
            "https://@example.invalid/v1",
            "https://local-user:synthetic-password@[::1]:9443/v1",
            "custom+local://local-user:synthetic-password@host_name/v1",
        )
        assertAll(values.mapIndexed { index, value ->
            org.junit.jupiter.api.function.Executable { assertAll("authority case $index", { rejected("mcp_server", value) }) }
        })
    }

    @Test fun `legitimate URI spelling and at signs outside authority round trip through the private store`() {
        val values = listOf(ordinary, "https://example.invalid/path@name?contact=local@example.invalid#tag@name",
            "urn:example:local@name", "custom+local:opaque@reference")
        for ((kind, field) in fields) {
            val initial = settings(kind, values.first()).put("credential_ref", "secret://local/opaque:1")
            val (library, created, first) = stored(kind, initial)
            var record = first
            for (uri in values) {
                val value = settings(kind, uri).put("credential_ref", "secret://local/opaque:1")
                val prepared = PreviewSelectionForms.configure(record.components.selections.single(), value.toString())
                record = (library.open(created.id).applySelection(record, command(prepared)) as PreviewSelectionSave.Saved).record
                val reopened = (PreviewDraftLibrary(root.resolve(kind)).open(created.id).load() as PreviewDraftLoad.Available).record
                assertEquals(uri, reopened.components.selections.single().settings[field].textValue())
                assertEquals("secret://local/opaque:1", reopened.components.selections.single().settings["credential_ref"].textValue())
                assertEquals(record.baselineIdentity, reopened.baselineIdentity)
                assertEquals("entered", reopened.components.selections.single().role)
                assertFalse(reopened.components.selections.single().enabled)
            }
        }
    }

    @Test fun `absent URI and allowed opaque references do not create defaults`() {
        for (kind in fields.keys) {
            for (value in listOf(mapper.createObjectNode(), mapper.createObjectNode().put("credential_ref", "secret://local/opaque:1"))) {
                val result = DraftSelectionCommands.apply(DraftContent(), command(add(kind, value)), catalog) as SelectionEdit.Ready
                assertEquals(value, result.content.components.selections.single().settings)
                val configured = command(PreviewSelectionForms.configure(result.content.components.selections.single(), value.toString()))
                    as SelectionCommand.Configure
                assertEquals(value, configured.settings)
                assertFalse(configured.settings.has(fields.getValue(kind)))
            }
        }
    }

    @Test fun `malformed wrong typed duplicate oversized and invalid Unicode inputs stay value-free failures`() {
        for ((kind, field) in fields) {
            val original = selection(kind, settings(kind, ordinary))
            val values = listOf("{\"$field\":\"not a URI\"}", "{\"$field\":null}", "{\"$field\":1}",
                "{\"$field\":\"https://example.invalid\",\"$field\":\"https://other.invalid\"}",
                "{\"credential_ref\":\"synthetic-password\"}",
                settings(kind, "https://example.invalid/" + "a".repeat(PreviewSelectionForms.MAX_SETTINGS_TEXT)).toString(),
                "{\"$field\":\"https://example.invalid/" + '\uD800' + "\"}")
            for (text in values) {
                val form = PreviewSelectionForm("entered", false, text)
                val added = PreviewSelectionForms.add("privacy_selection", item(kind), catalog.hash.hex, form)
                val configured = PreviewSelectionForms.configure(original, text)
                assertEquals(PreviewSelectionPreparation.Invalid, added)
                assertEquals(PreviewSelectionPreparation.Invalid, configured)
                assertFalse(listOf(form, added, configured).joinToString().contains("synthetic-password"))
            }
        }
    }

    @Test fun `rejected settings cannot dispatch a save or replace acknowledged revision and file bytes`() {
        val kind = "mcp_server"
        val (library, created, before) = stored(kind, settings(kind, ordinary))
        val originalBytes = bytes()
        val prepared = PreviewSelectionForms.configure(before.components.selections.single(), settings(kind, entered).toString())
        // Match the actual caller boundary: only a prepared command can reach the canonical store.
        val dispatched = if (prepared is PreviewSelectionPreparation.Ready) {
            library.open(created.id).applySelection(before, prepared.command)
            true
        } else false
        val reopened = (PreviewDraftLibrary(root.resolve(kind)).open(created.id).load() as PreviewDraftLoad.Available).record
        assertAll(
            { assertEquals(PreviewSelectionPreparation.Invalid, prepared) },
            { assertFalse(dispatched, "Rejected input must supply no command to dispatch") },
            { assertEquals(before.revision, reopened.revision) },
            { assertEquals(before.baselineIdentity, reopened.baselineIdentity) },
            { assertEquals(before.components, reopened.components) },
            { assertEquals(originalBytes, bytes(), "Private managed file bytes must stay unchanged") })
    }

    @Test fun `correcting rejected input saves only the corrected URI and reopens its exact revision`() {
        val kind = "integration"
        val (library, created, before) = stored(kind, settings(kind, ordinary))
        val originalBytes = bytes()
        assertEquals(PreviewSelectionPreparation.Invalid,
            PreviewSelectionForms.configure(before.components.selections.single(), settings(kind, entered).toString()))
        assertEquals(originalBytes, bytes())
        val corrected = "custom+local://example.invalid/corrected@path?q=exact@value"
        val prepared = PreviewSelectionForms.configure(before.components.selections.single(), settings(kind, corrected).toString())
        val saved = (library.open(created.id).applySelection(before, command(prepared)) as PreviewSelectionSave.Saved).record
        val reopened = (PreviewDraftLibrary(root.resolve(kind)).open(created.id).load() as PreviewDraftLoad.Available).record
        assertEquals(before.revision + 1, saved.revision)
        assertEquals(saved.baselineIdentity, reopened.baselineIdentity)
        assertEquals(corrected, reopened.components.selections.single().settings["endpoint"].textValue())
        assertEquals(before.draft.content, reopened.history[before.revision.toInt()].content)
        for (path in Files.walk(root).use { it.filter(Files::isRegularFile).toList() })
            assertFalse(Files.readString(path).contains("synthetic-password"), "Rejected value must not enter a managed file")
    }

    @Test fun `frozen historical URI values remain readable without rewriting retained records`() {
        val allFields = fields + ("fine_tuned_model" to "artifact_uri")
        for ((kind, field) in allFields) {
            val value = mapper.createObjectNode().put(field, entered)
            val legacy = selection(kind, value)
            assertEquals(entered, LocalSelection.fromJson(legacy.toJson()).settings[field].textValue())
            val path = Files.createDirectory(root.resolve(kind))
            val stored = (LocalDraftStore(path).save(null, DraftContent(components = DraftSelections(listOf(legacy)))) as DraftSave.Saved).draft
            val originalBytes = bytes()
            val reopened = (LocalDraftStore(path).load() as DraftLoad.Available).draft
            assertEquals(stored.version.number, reopened.version.number)
            assertEquals(stored.version.hash, reopened.version.hash)
            assertEquals(legacy, reopened.content.components.selections.single())
            assertEquals(originalBytes, bytes())
        }
    }

    @Test fun `role-only and catalog replacement preserve legacy settings without new settings input`() {
        for (kind in fields.keys) {
            val legacy = selection(kind, settings(kind, entered))
            val content = DraftContent(components = DraftSelections(listOf(legacy)))
            val role = command(PreviewSelectionForms.configureRole(legacy, "separate_role"))
            val changed = (DraftSelectionCommands.apply(content, role) as SelectionEdit.Ready).content
            assertEquals("separate_role", changed.components.selections.single().role)
            assertEquals(legacy.settings, changed.components.selections.single().settings)
            val replacement = command(PreviewSelectionForms.replace(legacy, item(kind), catalog.hash.hex))
            val retained = (DraftSelectionCommands.apply(content, replacement, catalog) as SelectionEdit.Ready).content
            assertEquals(content, retained)
            assertEquals(entered, legacy.settings[fields.getValue(kind)].textValue())
        }
    }

    @Test fun `form command and retained state diagnostics never include entered values`() {
        for (kind in fields.keys) {
            val form = PreviewSelectionForm("entered", false, settings(kind, entered).toString())
            val prepared = PreviewSelectionForms.add("privacy_selection", item(kind), catalog.hash.hex, form)
            val legacy = selection(kind, settings(kind, entered))
            val retained = command(PreviewSelectionForms.configureRole(legacy, "separate_role"))
            assertFalse(listOf(form, prepared, legacy, retained).joinToString().contains("synthetic-password"))
            assertFalse(listOf(form, prepared, legacy, retained).joinToString().contains("example.invalid"))
        }
    }

    @Test fun `all five frozen URI fields have the scoped policy without enabling another editor kind`() {
        val allFields = fields + ("fine_tuned_model" to "artifact_uri")
        val schema = mapper.readTree(SelectionFixtures.resource("/contracts/v1/schemas/selection.schema.json"))
        val inventory = schema["\$defs"].properties().flatMap { (name, definition) ->
            definition["properties"]?.properties().orEmpty().filter { (_, value) -> value["format"]?.textValue() == "uri" }
                .map { (field, _) -> name.removePrefix("settings_") to field }
        }.toMap()
        assertEquals(allFields, inventory)
        for ((kind, field) in allFields) {
            assertFalse(PreviewSelectionSensitiveInput.allows(kind, mapper.createObjectNode().put(field, entered)))
            assertTrue(PreviewSelectionSensitiveInput.allows(kind, mapper.createObjectNode().put(field, ordinary)))
            assertTrue(PreviewSelectionSensitiveInput.allows(kind, mapper.createObjectNode()))
            assertFalse(PreviewSelectionSensitiveInput.allows(kind, mapper.createObjectNode().put(field, "https://bad authority")))
            assertFalse(PreviewSelectionSensitiveInput.allows(kind, mapper.createObjectNode().putNull(field)))
        }
        assertTrue(PreviewSelectionSensitiveInput.allows("model_provider", mapper.createObjectNode().put("region", entered)))
        assertTrue(PreviewSelectionSensitiveInput.allows("workload", mapper.createObjectNode().put("endpoint", entered)))
    }
}
