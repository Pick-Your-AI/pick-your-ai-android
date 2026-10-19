package ai.pickyour.core.evidence.answers

import com.fasterxml.jackson.databind.JsonNode
import com.networknt.schema.AbstractJsonValidator
import com.networknt.schema.ExecutionContext
import com.networknt.schema.JsonNodePath
import com.networknt.schema.JsonSchema
import com.networknt.schema.JsonValidator
import com.networknt.schema.Keyword
import com.networknt.schema.SchemaLocation
import com.networknt.schema.ValidationContext
import com.networknt.schema.ValidationMessage

/**
 * JSON Schema Validation 2020-12 sections 6.4.4–6.4.5: minContains and maxContains
 * only qualify a sibling "contains" and are ignored without one. networknt's own validator
 * reports the minContains/maxContains consistency error (minContains defaults
 * to 1) whatever the schema and instance are, so a lone maxContains of 0, or
 * a "contains" schema applied to a non-array, rejected values the selected
 * reference admits. Delegate to networknt only when it can apply: the schema
 * has "contains" and the instance is an array.
 */
internal class Cd1ContainsCountKeyword(private val delegate: Keyword) : Keyword {
    override fun getValue(): String = delegate.value

    override fun newValidator(location: SchemaLocation, path: JsonNodePath, schemaNode: JsonNode,
                              parentSchema: JsonSchema, validationContext: ValidationContext): JsonValidator {
        val original = delegate.newValidator(location, path, schemaNode, parentSchema, validationContext)
        val applies = parentSchema.schemaNode.has("contains")
        return object : AbstractJsonValidator(location, path, this, schemaNode) {
            override fun validate(executionContext: ExecutionContext, value: JsonNode, rootNode: JsonNode,
                                  instanceLocation: JsonNodePath): Set<ValidationMessage> =
                if (applies && value.isArray) original.validate(executionContext, value, rootNode, instanceLocation)
                else emptySet()
        }
    }

    companion object {
        val names = setOf("minContains", "maxContains")
    }
}
