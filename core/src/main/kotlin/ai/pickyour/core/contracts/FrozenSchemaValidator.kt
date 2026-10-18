package ai.pickyour.core.contracts

import ai.pickyour.core.importing.BoundedJsonReader
import ai.pickyour.core.importing.JsonInputResult
import ai.pickyour.core.importing.JsonRejection
import com.fasterxml.jackson.databind.JsonNode
import com.networknt.schema.JsonSchemaFactory
import com.networknt.schema.SchemaLocation
import com.networknt.schema.SchemaValidatorsConfig
import com.networknt.schema.SpecVersion.VersionFlag
import com.networknt.schema.resource.InputStreamSource
import java.io.InputStream

/** An explicit expected document type; untrusted input cannot select its own schema or dialect. */
enum class FrozenDocument(val schemaName: String) {
    BUILD("build"), CATALOG_ITEM("catalog-item"), CATALOG("catalog-snapshot"),
    COST_ESTIMATE("cost-estimate"), DEPENDENCY_GRAPH("dependency-graph"),
    DEPLOYMENT_MANIFEST("deployment-manifest"), EVALUATION_REPORT("evaluation-report"),
    EVENT("event"), PRICEBOOK("pricebook"), QUALITY_EVALUATION_RUN("quality-evaluation-run"),
    RESOURCE_ESTIMATE("resource-estimate"), RULESET("ruleset"), SELECTION("selection"),
}

sealed interface FrozenValidation {
    data object Valid : FrozenValidation
    data class InvalidInput(val reason: JsonRejection) : FrozenValidation
    /** Schema failure is intentionally sanitized: no input values or library exception text. */
    data object SchemaMismatch : FrozenValidation
}

/**
 * Frozen-v1 structural validation, including format assertions; not cross-document semantic approval.
 * Uses only bundled, unmodified schemas. Never resolves input-provided $ref/$schema or opens URLs.
 * Input streams remain caller-owned. No mutable parsed input escapes this validation boundary.
 * A missing/corrupt bundled schema is a packaging error and fails initialization, not a valid result.
 */
class FrozenSchemaValidator {
    private val reader = BoundedJsonReader()
    private val config = SchemaValidatorsConfig.builder().formatAssertionsEnabled(true).build()
    private val factory = JsonSchemaFactory.getInstance(VersionFlag.V202012) { builder ->
        builder.schemaLoaders { loaders ->
            loaders.add { iri -> bundledSchema(iri.toString()) }
        }
    }
    private val schemas = FrozenDocument.entries.associateWith {
        factory.getSchema(SchemaLocation.of("$BASE${it.schemaName}.schema.json"), config)
    }

    fun validate(document: FrozenDocument, input: InputStream): FrozenValidation =
        when (val parsed = reader.read(input)) {
            is JsonInputResult.Rejected -> FrozenValidation.InvalidInput(parsed.reason)
            is JsonInputResult.Parsed -> validateParsed(document, parsed.value)
        }

    /** Fixed frozen fragment; incomplete local drafts require no fabricated Build envelope. */
    fun validateSelectionLink(input: InputStream): FrozenValidation = when (val parsed = reader.read(input)) {
        is JsonInputResult.Rejected -> FrozenValidation.InvalidInput(parsed.reason)
        is JsonInputResult.Parsed -> if (selectionLinkSchema.validate(parsed.value).isEmpty()) FrozenValidation.Valid
            else FrozenValidation.SchemaMismatch
    }

    private val selectionLinkSchema by lazy {
        factory.getSchema(SchemaLocation.of("${BASE}build.schema.json#/properties/links/items"), config)
    }

    private fun validateParsed(document: FrozenDocument, value: JsonNode): FrozenValidation =
        if (schemas.getValue(document).validate(value).isEmpty()) FrozenValidation.Valid
        else FrozenValidation.SchemaMismatch

    companion object {
        private const val BASE = "https://schemas.ai-builder.example/v1/"
        private val names = FrozenDocument.entries.map { "${it.schemaName}.schema.json" }.toSet() + "common.schema.json"

        /** Throw rather than return null: null would permit networknt's default network fallback. */
        internal fun bundledSchema(uri: String): InputStreamSource {
            val name = uri.removePrefix(BASE)
            check(uri.startsWith(BASE) && name in names) { "Schema location is not bundled" }
            return InputStreamSource {
                checkNotNull(FrozenSchemaValidator::class.java.getResourceAsStream("/contracts/v1/schemas/$name")) {
                    "Frozen schema resource missing"
                }
            }
        }
    }
}
