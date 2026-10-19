package ai.pickyour.core.preview

import ai.pickyour.core.importing.BoundedJsonReader
import ai.pickyour.core.importing.JsonInputResult
import com.fasterxml.jackson.databind.JsonNode
import java.math.BigDecimal
import java.util.Collections

enum class PreviewSettingType { INTEGER, NUMBER, STRING, BOOLEAN, STRING_ARRAY }

/** Frozen constraint metadata only. It grants neither admission nor answer-state semantics. */
class PreviewSettingField internal constructor(
    val key: String,
    val type: PreviewSettingType,
    val required: Boolean,
    val minimum: BigDecimal?,
    val exclusiveMinimum: BigDecimal?,
    val maximum: BigDecimal?,
    enumValues: List<String>,
    val minimumLength: Int?,
    val pattern: String?,
    val format: String?,
    val description: String?,
    val uniqueItems: Boolean,
) {
    val enumValues: List<String> = Collections.unmodifiableList(enumValues.toList())
}

/**
 * Reads the bundled Selection schema, never user-selected metadata or a second field registry.
 * Missing or unfamiliar bundled structure is a packaging error, not an unrestricted form.
 */
object PreviewSettingsMetadata {
    private val definitions: Map<String, List<PreviewSettingField>> by lazy {
        val schema = checkNotNull(javaClass.getResourceAsStream("/contracts/v1/schemas/selection.schema.json")) {
            "Frozen settings schema missing"
        }.use { stream ->
            (BoundedJsonReader().read(stream) as? JsonInputResult.Parsed)?.value
                ?: error("Frozen settings schema unreadable")
        }
        val expectedKinds = strings(schema.path("properties").path("kind").path("enum")).toSet()
        val branches = schema.path("allOf")
        check(branches.isArray) { "Frozen settings mapping unavailable" }
        val result = linkedMapOf<String, List<PreviewSettingField>>()
        for (branch in branches) {
            val kind = text(branch.path("if").path("properties").path("kind").path("const"))
            val reference = text(branch.path("then").path("properties").path("settings").path("\$ref"))
            check(reference.startsWith("#/\$defs/")) { "Frozen settings reference unavailable" }
            val definition = schema.path("\$defs").path(reference.removePrefix("#/\$defs/"))
            check(definition.path("type").textValue() == "object" &&
                definition.path("additionalProperties").isBoolean &&
                !definition.path("additionalProperties").booleanValue()) { "Frozen settings object is not closed" }
            val properties = definition.path("properties")
            check(properties.isObject) { "Frozen settings properties unavailable" }
            val required = definition.get("required")?.let(::strings).orEmpty().toSet()
            check(required.all(properties::has)) { "Frozen required setting is undeclared" }
            val fields = properties.properties().map { (key, value) -> field(key, value, key in required) }
            check(result.put(kind, Collections.unmodifiableList(fields.toList())) == null) { "Repeated frozen settings kind" }
        }
        check(result.keys == expectedKinds) { "Frozen settings kind mapping differs" }
        Collections.unmodifiableMap(result)
    }

    /** An unknown kind has no field metadata. Callers must not interpret absence as permission. */
    fun fields(kind: String): List<PreviewSettingField>? = definitions[kind]

    private fun field(key: String, node: JsonNode, required: Boolean): PreviewSettingField {
        val supported = setOf("type", "minimum", "exclusiveMinimum", "maximum", "enum", "minLength",
            "pattern", "format", "description", "items", "uniqueItems")
        check(node.isObject && node.properties().all { it.key in supported }) { "Unrecognized frozen setting constraint" }
        val type = when (text(node.path("type"))) {
            "integer" -> PreviewSettingType.INTEGER
            "number" -> PreviewSettingType.NUMBER
            "string" -> PreviewSettingType.STRING
            "boolean" -> PreviewSettingType.BOOLEAN
            "array" -> {
                check(node.path("items").isObject && node.path("items").size() == 1 &&
                    node.path("items").path("type").textValue() == "string") { "Unrecognized frozen setting array" }
                PreviewSettingType.STRING_ARRAY
            }
            else -> error("Unrecognized frozen setting type")
        }
        fun number(name: String): BigDecimal? = node.get(name)?.let {
            check(it.isNumber) { "Invalid frozen numeric constraint" }; it.decimalValue()
        }
        fun optionalText(name: String) = node.get(name)?.let(::text)
        val enumValues = node.get("enum")?.let(::strings).orEmpty()
        val minimumLength = node.get("minLength")?.let {
            check(it.isIntegralNumber && it.canConvertToInt() && it.intValue() >= 0) { "Invalid frozen string limit" }
            it.intValue()
        }
        val unique = node.get("uniqueItems")?.let {
            check(it.isBoolean) { "Invalid frozen uniqueness constraint" }; it.booleanValue()
        } ?: false
        return PreviewSettingField(key, type, required, number("minimum"), number("exclusiveMinimum"),
            number("maximum"), enumValues, minimumLength, optionalText("pattern"), optionalText("format"),
            optionalText("description"), unique)
    }

    private fun text(node: JsonNode): String {
        check(node.isTextual) { "Frozen settings text unavailable" }
        return node.textValue()
    }

    private fun strings(node: JsonNode): List<String> {
        check(node.isArray) { "Frozen settings list unavailable" }
        val values = node.map(::text)
        check(values.distinct().size == values.size) { "Repeated frozen settings value" }
        return values
    }
}
