package ai.pickyour.core.evidence.answers

import com.fasterxml.jackson.databind.JsonNode
import com.networknt.schema.ExecutionContext
import com.networknt.schema.JsonNodePath
import com.networknt.schema.JsonSchema
import com.networknt.schema.JsonValidator
import com.networknt.schema.Keyword
import com.networknt.schema.SchemaLocation
import com.networknt.schema.ValidationContext
import com.networknt.schema.ValidationMessage

/** Local work bound, independent of the original schema's byte budget or shared wire profile. */
internal object Cd1ReferenceBudget {
    private const val KEY = "ai.pickyour.core.evidence.answers.reference-budget"
    private const val MAX_APPLICATIONS = 4096
    private class Budget(var remaining: Int = MAX_APPLICATIONS)

    /** A fresh counter belongs to one validation context, never the cached schema or a thread. */
    fun begin(context: ExecutionContext) {
        context.collectorContext.add<Any>(KEY, Budget())
    }

    fun wrap(delegate: Keyword): Keyword = object : Keyword {
        override fun getValue(): String = delegate.value
        override fun newValidator(location: SchemaLocation, path: JsonNodePath, schemaNode: JsonNode,
                                  parentSchema: JsonSchema, validationContext: ValidationContext): JsonValidator {
            val original = delegate.newValidator(location, path, schemaNode, parentSchema, validationContext)
            return object : JsonValidator by original {
                override fun validate(context: ExecutionContext, value: JsonNode, rootNode: JsonNode,
                                      instanceLocation: JsonNodePath): Set<ValidationMessage> {
                    consume(context)
                    return original.validate(context, value, rootNode, instanceLocation)
                }

                override fun walk(context: ExecutionContext, value: JsonNode, rootNode: JsonNode,
                                  instanceLocation: JsonNodePath, shouldValidate: Boolean): Set<ValidationMessage> {
                    consume(context)
                    return original.walk(context, value, rootNode, instanceLocation, shouldValidate)
                }
            }
        }
    }

    private fun consume(context: ExecutionContext) {
        // The bundled meta-schema is validated without a caller-value budget.
        // Only requireValid's fresh execution context installs this private key.
        val budget = context.collectorContext.get(KEY) as? Budget ?: return
        cd1Require(budget.remaining > 0, Cd1Problem.INVALID_FIELD_SCHEMA)
        budget.remaining--
    }
}
