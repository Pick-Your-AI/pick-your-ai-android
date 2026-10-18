package ai.pickyour.core.preview

import ai.pickyour.core.drafts.*
import ai.pickyour.core.cache.ArtifactHash
import ai.pickyour.core.importing.BoundedJsonReader
import ai.pickyour.core.importing.JsonInputLimits
import ai.pickyour.core.importing.JsonInputResult
import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper

/** Transient explicit form input. Parsing never writes, chooses a role, or supplies missing settings. */
class PreviewSelectionForm(val role: String, val enabled: Boolean, val settingsJson: String) {
    override fun toString() = "PreviewSelectionForm"
}

sealed interface PreviewSelectionPreparation {
    class Ready(val command: SelectionCommand) : PreviewSelectionPreparation
    data object Invalid : PreviewSelectionPreparation
}

/** Runs off the UI thread; these local editor budgets do not change frozen schema semantics. */
object PreviewSelectionForms {
    const val MAX_SETTINGS_TEXT = 16_384
    const val MAX_ROLE_TEXT = 80
    private val mapper = ObjectMapper()
    private fun settings(text: String): com.fasterxml.jackson.databind.node.ObjectNode? {
        if (text.length > MAX_SETTINGS_TEXT) return null
        val bytes = try {
            val encoded = Charsets.UTF_8.newEncoder().onMalformedInput(java.nio.charset.CodingErrorAction.REPORT)
                .onUnmappableCharacter(java.nio.charset.CodingErrorAction.REPORT).encode(java.nio.CharBuffer.wrap(text))
            ByteArray(encoded.remaining()).also(encoded::get)
        } catch (_: java.nio.charset.CharacterCodingException) { return null }
        return (BoundedJsonReader(JsonInputLimits(maxBytes = 65_536)).read(bytes.inputStream()) as? JsonInputResult.Parsed)?.value
    }

    fun add(selectionId: String, item: PreviewCatalogItem, hash: String, form: PreviewSelectionForm): PreviewSelectionPreparation =
        prepare {
            require(supported(item.kind) && form.role.length <= MAX_ROLE_TEXT)
            val node = mapper.createObjectNode().put("id", selectionId).put("kind", item.kind)
                .put("catalog_item_id", item.id).put("catalog_item_version", item.version)
                .put("role", form.role).put("enabled", form.enabled)
            node.set<JsonNode>("settings", requireNotNull(settings(form.settingsJson)))
            SelectionCommand.Add(LocalSelection.fromJson(node), CatalogSelectionChoice(ArtifactHash(hash), item.kind, item.id, item.version))
        }

    fun configure(selection: LocalSelection, settingsJson: String, role: String? = null): PreviewSelectionPreparation = prepare {
        require(supported(selection.kind) && (role == null || role.length <= MAX_ROLE_TEXT))
        val value = requireNotNull(settings(settingsJson))
        // Validate the exact prospective payload before dispatch; the canonical command still checks live CAS.
        LocalSelection.fromJson(selection.toJson().also {
            it.set<JsonNode>("settings", value)
            if (role != null) it.put("role", role)
        })
        SelectionCommand.Configure(selection.id, value, role)
    }

    /** Role input is bounded separately; retained settings never pass through the small JSON editor. */
    fun configureRole(selection: LocalSelection, role: String): PreviewSelectionPreparation = prepare {
        require(supported(selection.kind) && role.length <= MAX_ROLE_TEXT)
        LocalSelection.fromJson(selection.toJson().put("role", role))
        SelectionCommand.Configure(selection.id, selection.settings, role)
    }

    fun replace(selection: LocalSelection, item: PreviewCatalogItem, hash: String): PreviewSelectionPreparation = prepare {
        require(supported(selection.kind) && item.kind == selection.kind)
        SelectionCommand.Replace(selection.id, CatalogSelectionChoice(ArtifactHash(hash), item.kind, item.id, item.version))
    }

    fun supported(kind: String) = PreviewCatalogKinds.all.any { it.id == kind && it.tier in setOf("T1", "T2") }
    fun settingsText(selection: LocalSelection): String = selection.settings.toString()
    private fun prepare(action: () -> SelectionCommand): PreviewSelectionPreparation = try {
        PreviewSelectionPreparation.Ready(action())
    } catch (_: IllegalArgumentException) { PreviewSelectionPreparation.Invalid }
}
