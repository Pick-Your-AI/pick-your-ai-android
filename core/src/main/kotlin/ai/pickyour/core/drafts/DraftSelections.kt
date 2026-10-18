package ai.pickyour.core.drafts

import ai.pickyour.core.cache.ArtifactHash
import ai.pickyour.core.contracts.FrozenDocument
import ai.pickyour.core.contracts.FrozenSchemaValidator
import ai.pickyour.core.contracts.FrozenValidation
import ai.pickyour.core.importing.BoundedJsonReader
import ai.pickyour.core.importing.JsonInputLimits
import ai.pickyour.core.importing.JsonInputResult
import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.databind.node.ObjectNode
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.OutputStream
import java.security.MessageDigest
import java.util.ArrayDeque
import java.util.Collections

/** Frozen schema-valid payload only; this establishes no catalog existence or compatibility verdict. */
class LocalSelection private constructor(private val payload: ObjectNode) {
    val id: String get() = payload["id"].textValue()
    val kind: String get() = payload["kind"].textValue()
    val catalogItemId: String get() = payload["catalog_item_id"].textValue()
    val catalogItemVersion: String get() = payload["catalog_item_version"].textValue()
    val role: String get() = payload["role"].textValue()
    val enabled: Boolean get() = payload["enabled"].booleanValue()
    /** Defensive snapshots preserve JSON types, absent fields, notes and namespaced extensions. */
    val settings: ObjectNode get() = payload["settings"].deepCopy()
    fun toJson(): ObjectNode = payload.deepCopy()
    internal fun configured(settings: ObjectNode, role: String? = null): LocalSelection = fromJson(toJson().also {
        it.set<JsonNode>("settings", settings)
        if (role != null) it.put("role", role)
    })
    internal fun enabled(value: Boolean): LocalSelection = fromJson(toJson().put("enabled", value))
    internal fun replaced(choice: CatalogSelectionChoice, settings: ObjectNode): LocalSelection = fromJson(toJson()
        .put("catalog_item_id", choice.itemId).put("catalog_item_version", choice.itemVersion)
        .also { it.set<JsonNode>("settings", settings) })
    override fun equals(other: Any?) = other is LocalSelection && payload == other.payload
    override fun hashCode() = payload.hashCode()
    override fun toString() = "LocalSelection"
    companion object {
        /** Explicit identity, role, enabled and kind-specific required settings; no guessed defaults. */
        fun fromJson(bytes: ByteArray): LocalSelection {
            val frozen = SelectionJson.freeze(bytes)
            val node = SelectionJson.parse(frozen)
            require(SelectionJson.validator.validate(FrozenDocument.SELECTION, frozen.inputStream()) == FrozenValidation.Valid) { "INVALID_SELECTION" }
            return LocalSelection(node)
        }
        fun fromJson(value: ObjectNode): LocalSelection = fromJson(SelectionJson.encode(value))
    }
}

/** A frozen Build.links item, not a link to a catalog ID or a network resource. */
class LocalSelectionLink private constructor(private val payload: ObjectNode) {
    val id: String get() = payload["id"].textValue()
    val fromSelectionId: String get() = payload["from_selection_id"].textValue()
    val toSelectionId: String get() = payload["to_selection_id"].textValue()
    val relation: String get() = payload["relation"].textValue()
    val required: Boolean? get() = payload["required"]?.booleanValue()
    fun toJson(): ObjectNode = payload.deepCopy()
    override fun equals(other: Any?) = other is LocalSelectionLink && payload == other.payload
    override fun hashCode() = payload.hashCode()
    override fun toString() = "LocalSelectionLink"
    companion object {
        fun fromJson(bytes: ByteArray): LocalSelectionLink {
            val frozen = SelectionJson.freeze(bytes)
            val node = SelectionJson.parse(frozen)
            require(SelectionJson.validator.validateSelectionLink(frozen.inputStream()) == FrozenValidation.Valid) { "INVALID_SELECTION_LINK" }
            return LocalSelectionLink(node)
        }
        fun fromJson(value: ObjectNode): LocalSelectionLink = fromJson(SelectionJson.encode(value))
    }
}

/** Incomplete local aggregate: empty and single-selection drafts are valid, unlike BuildSnapshot. */
class DraftSelections(selections: List<LocalSelection> = emptyList(), links: List<LocalSelectionLink> = emptyList()) {
    val selections: List<LocalSelection> = Collections.unmodifiableList(ArrayList(selections))
    val links: List<LocalSelectionLink> = Collections.unmodifiableList(ArrayList(links))
    init {
        require(this.selections.size <= MAX_SELECTIONS && this.links.size <= MAX_LINKS) { "SELECTION_LIMIT_EXCEEDED" }
        require(this.selections.map { it.id }.toSet().size == this.selections.size) { "DUPLICATE_SELECTION_ID" }
        require(this.selections.map { it.kind to it.role }.toSet().size == this.selections.size) { "DUPLICATE_KIND_ROLE" }
        require(!SelectionRegistry.exceedsCardinality(this.selections)) { "ACTIVE_CARDINALITY_EXCEEDED" }
        require(this.links.map { it.id }.toSet().size == this.links.size) { "DUPLICATE_LINK_ID" }
        val ids = this.selections.map { it.id }.toSet()
        require(this.links.all { it.fromSelectionId in ids && it.toSelectionId in ids }) { "MISSING_LINK_ENDPOINT" }
    }
    override fun equals(other: Any?) = other is DraftSelections && selections == other.selections && links == other.links
    override fun hashCode() = 31 * selections.hashCode() + links.hashCode()
    override fun toString() = "DraftSelections"
    companion object {
        // Local editing budgets, not amendments to the frozen contract.
        const val MAX_SELECTIONS = 256
        const val MAX_LINKS = 1024
    }
}

/** Exact choice; the caller must provide its current captured accepted snapshot at application time. */
data class CatalogSelectionChoice(val snapshotHash: ArtifactHash, val kind: String, val itemId: String, val itemVersion: String) {
    override fun toString() = "CatalogSelectionChoice"
}

/** Schema validation and identity only: no publisher authenticity, rights, freshness or compatibility. */
class SelectionCatalogSnapshot private constructor(val hash: ArtifactHash, private val entries: Set<Triple<String, String, String>>) {
    fun contains(choice: CatalogSelectionChoice): Boolean = choice.snapshotHash == hash && Triple(choice.kind, choice.itemId, choice.itemVersion) in entries
    companion object {
        fun fromJson(bytes: ByteArray): SelectionCatalogSnapshot {
            val frozen = SelectionJson.freeze(bytes)
            val node = SelectionJson.parse(frozen)
            require(SelectionJson.validator.validate(FrozenDocument.CATALOG, frozen.inputStream()) == FrozenValidation.Valid) { "INVALID_SELECTION_CATALOG" }
            val items = node["items"].map { Triple(it["kind"].textValue(), it["id"].textValue(), it["version"].textValue()) }
            require(items.map { it.second to it.third }.toSet().size == items.size) { "AMBIGUOUS_CATALOG_ITEM" }
            val hash = ArtifactHash(MessageDigest.getInstance("SHA-256").digest(frozen).joinToString("") { "%02x".format(it.toInt() and 255) })
            return SelectionCatalogSnapshot(hash, items.toSet())
        }
    }
}

/** Inputs are immutable snapshots. Replace retains settings unless explicitly supplied. */
sealed interface SelectionCommand {
    class Add(val selection: LocalSelection, val choice: CatalogSelectionChoice) : SelectionCommand
    class Replace(val selectionId: String, val choice: CatalogSelectionChoice, settings: ObjectNode? = null) : SelectionCommand {
        private val payload = settings?.let(SelectionJson::copy)
        val settings: ObjectNode? get() = payload?.deepCopy()
    }
    /** A null role preserves the existing role; an explicit value is never trimmed or defaulted. */
    class Configure(val selectionId: String, settings: ObjectNode, val role: String? = null) : SelectionCommand {
        private val payload = SelectionJson.copy(settings)
        val settings: ObjectNode get() = payload.deepCopy()
    }
    class SetEnabled(val selectionId: String, val enabled: Boolean) : SelectionCommand
    class Remove(val selectionId: String) : SelectionCommand
}

enum class SelectionAction { ADD, REPLACE, CONFIGURE, CONFIGURE_ROLE, ENABLE, DISABLE, REMOVE }
/** Deterministic descriptions contain IDs only, never setting values or claims of compatible bindings. */
class SelectionChange internal constructor(val action: SelectionAction, val selectionId: String,
    removedLinkIds: List<String> = emptyList(), affectedLinkIds: List<String> = emptyList()) {
    val removedLinkIds: List<String> = Collections.unmodifiableList(ArrayList(removedLinkIds))
    /** Retained incident relationships need review after replacement/configuration/activation edits. */
    val affectedLinkIds: List<String> = Collections.unmodifiableList(ArrayList(affectedLinkIds))
    override fun equals(other: Any?) = other is SelectionChange && action == other.action && selectionId == other.selectionId &&
        removedLinkIds == other.removedLinkIds && affectedLinkIds == other.affectedLinkIds
    override fun hashCode() = 31 * (31 * (31 * action.hashCode() + selectionId.hashCode()) + removedLinkIds.hashCode()) + affectedLinkIds.hashCode()
    override fun toString() = "SelectionChange"
}
enum class SelectionRejection {
    UNSUPPORTED_KIND, MISSING_SELECTION, DUPLICATE_SELECTION_ID, DUPLICATE_KIND_ROLE, ACTIVE_CARDINALITY_EXCEEDED,
    CATALOG_PIN_MISMATCH, CATALOG_ITEM_UNAVAILABLE, INVALID_SETTINGS, SELECTION_LIMIT_EXCEEDED,
}
sealed interface SelectionEdit {
    class Ready(val content: DraftContent, val change: SelectionChange) : SelectionEdit
    data class Rejected(val reason: SelectionRejection) : SelectionEdit
}

/** Pure registry T0/T1/T2/T3/T4/T5 edits only. No tier activation, primary promotion, evaluation or IO. */
object DraftSelectionCommands {
    fun apply(content: DraftContent, command: SelectionCommand, catalog: SelectionCatalogSnapshot? = null): SelectionEdit {
        val state = content.components
        val id = when (command) {
            is SelectionCommand.Add -> command.selection.id
            is SelectionCommand.Replace -> command.selectionId
            is SelectionCommand.Configure -> command.selectionId
            is SelectionCommand.SetEnabled -> command.selectionId
            is SelectionCommand.Remove -> command.selectionId
        }
        val existing = state.selections.find { it.id == id }
        if (command !is SelectionCommand.Add && existing == null) return SelectionEdit.Rejected(SelectionRejection.MISSING_SELECTION)
        val kind = if (command is SelectionCommand.Add) command.selection.kind else requireNotNull(existing).kind
        if (!SelectionRegistry.supportsCommands(kind)) return SelectionEdit.Rejected(SelectionRejection.UNSUPPORTED_KIND)
        val choice = when (command) {
            is SelectionCommand.Add -> command.choice
            is SelectionCommand.Replace -> command.choice
            else -> null
        }
        if (choice != null) {
            if (catalog == null || choice.snapshotHash != catalog.hash || choice.kind != kind ||
                command is SelectionCommand.Add && (command.selection.catalogItemId != choice.itemId || command.selection.catalogItemVersion != choice.itemVersion))
                return SelectionEdit.Rejected(SelectionRejection.CATALOG_PIN_MISMATCH)
            if (!catalog.contains(choice)) return SelectionEdit.Rejected(SelectionRejection.CATALOG_ITEM_UNAVAILABLE)
        }
        if (command is SelectionCommand.Add && existing != null) return SelectionEdit.Rejected(SelectionRejection.DUPLICATE_SELECTION_ID)
        val changed = try { when (command) {
            is SelectionCommand.Add -> command.selection
            is SelectionCommand.Replace -> requireNotNull(existing).replaced(command.choice, command.settings ?: existing.settings)
            is SelectionCommand.Configure -> requireNotNull(existing).configured(command.settings, command.role)
            is SelectionCommand.SetEnabled -> requireNotNull(existing).enabled(command.enabled)
            is SelectionCommand.Remove -> null
        } } catch (_: IllegalArgumentException) { return SelectionEdit.Rejected(SelectionRejection.INVALID_SETTINGS) }
        val selections = if (command is SelectionCommand.Add) state.selections + requireNotNull(changed)
            else state.selections.mapNotNull { if (it.id == id) changed else it }
        if (selections.size > DraftSelections.MAX_SELECTIONS) return SelectionEdit.Rejected(SelectionRejection.SELECTION_LIMIT_EXCEEDED)
        if (selections.map { it.kind to it.role }.toSet().size != selections.size) return SelectionEdit.Rejected(SelectionRejection.DUPLICATE_KIND_ROLE)
        if (SelectionRegistry.exceedsCardinality(selections)) return SelectionEdit.Rejected(SelectionRejection.ACTIVE_CARDINALITY_EXCEEDED)
        val incident = state.links.filter { it.fromSelectionId == id || it.toSelectionId == id }
        val removed = if (command is SelectionCommand.Remove) incident else emptyList()
        val action = when (command) {
            is SelectionCommand.Add -> SelectionAction.ADD
            is SelectionCommand.Replace -> SelectionAction.REPLACE
            is SelectionCommand.Configure -> if (command.role != null && command.role != requireNotNull(existing).role)
                SelectionAction.CONFIGURE_ROLE else SelectionAction.CONFIGURE
            is SelectionCommand.SetEnabled -> if (command.enabled) SelectionAction.ENABLE else SelectionAction.DISABLE
            is SelectionCommand.Remove -> SelectionAction.REMOVE
        }
        return SelectionEdit.Ready(content.withComponents(DraftSelections(selections, state.links - removed.toSet())),
            SelectionChange(action, id, removed.map { it.id }.sorted(), incident.map { it.id }.sorted()))
    }
}

internal object SelectionJson {
    private val mapper = ObjectMapper()
    val validator by lazy { FrozenSchemaValidator() }
    private val limits = JsonInputLimits()
    fun freeze(bytes: ByteArray): ByteArray {
        require(bytes.size <= limits.maxBytes) { "INVALID_SELECTION_JSON" }
        return bytes.copyOf()
    }
    /** Iterative preflight precedes all recursive copies/serialization of caller-supplied nodes. */
    fun encode(value: ObjectNode): ByteArray {
        val stack = ArrayDeque<Pair<JsonNode, Int>>()
        stack.add(value to 1)
        var tokens = 0
        var characters = 0L
        fun count(value: Int) {
            characters += value
            require(characters <= limits.maxBytes) { "INVALID_SELECTION_JSON" }
        }
        while (stack.isNotEmpty()) {
            val (node, depth) = stack.removeLast()
            require(++tokens <= limits.maxTokens && depth <= limits.maxDepth) { "INVALID_SELECTION_JSON" }
            when {
                node.isObject -> for ((key, child) in node.properties()) {
                    require(key.length <= limits.maxNameLength && tokens + stack.size < limits.maxTokens) { "INVALID_SELECTION_JSON" }
                    count(key.length)
                    stack.add(child to depth + 1)
                }
                node.isArray -> for (child in node) {
                    require(tokens + stack.size < limits.maxTokens) { "INVALID_SELECTION_JSON" }
                    stack.add(child to depth + 1)
                }
                node.isTextual -> {
                    require(node.textValue().length <= limits.maxStringLength) { "INVALID_SELECTION_JSON" }
                    count(node.textValue().length)
                }
                node.isIntegralNumber -> {
                    require(node.bigIntegerValue().bitLength() <= 426 && node.asText().length <= limits.maxNumberLength) { "INVALID_SELECTION_JSON" }
                }
                node.isFloatingPointNumber -> {
                    val number = try { node.decimalValue() } catch (_: NumberFormatException) { throw IllegalArgumentException("INVALID_SELECTION_JSON") }
                    require(number.precision() <= limits.maxNumberLength && number.scale().toLong() in -10_000L..10_000L &&
                        node.asText().length <= limits.maxNumberLength) { "INVALID_SELECTION_JSON" }
                }
                node.isBoolean || node.isNull -> Unit
                else -> throw IllegalArgumentException("INVALID_SELECTION_JSON")
            }
        }
        val output = ByteArrayOutputStream()
        val bounded = object : OutputStream() {
            override fun write(value: Int) {
                require(output.size() < limits.maxBytes) { "INVALID_SELECTION_JSON" }
                output.write(value)
            }
            override fun write(bytes: ByteArray, offset: Int, length: Int) {
                require(length <= limits.maxBytes - output.size()) { "INVALID_SELECTION_JSON" }
                output.write(bytes, offset, length)
            }
        }
        try { mapper.writeValue(bounded, value) } catch (_: IOException) { throw IllegalArgumentException("INVALID_SELECTION_JSON") }
        return output.toByteArray()
    }
    fun copy(value: ObjectNode): ObjectNode = parse(encode(value))
    fun parse(bytes: ByteArray): ObjectNode = (BoundedJsonReader().read(bytes.inputStream()) as? JsonInputResult.Parsed)?.value
        ?: throw IllegalArgumentException("INVALID_SELECTION_JSON")
}

private object SelectionRegistry {
    private val kinds by lazy {
        requireNotNull(javaClass.getResourceAsStream("/contracts/v1/schemas/component-registry.json")).use {
            (BoundedJsonReader().read(it) as JsonInputResult.Parsed).value["kinds"]
        }
    }
    fun exceedsCardinality(selections: List<LocalSelection>): Boolean = selections.filter { it.enabled }.groupBy { it.kind }
        .any { (kind, values) -> kinds[kind]["cardinality"].textValue() == "one" && values.size > 1 }
    fun supportsCommands(kind: String): Boolean = kinds[kind]["tier"].textValue() in setOf("T0", "T1", "T2", "T3", "T4", "T5")
}
