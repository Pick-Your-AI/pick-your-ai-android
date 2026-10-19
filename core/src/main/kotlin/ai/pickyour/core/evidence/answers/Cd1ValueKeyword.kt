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
import java.math.BigDecimal

/**
 * The selected jsonschema 4.25.1 reference compares integer/binary64 values
 * without rounding the integer. Its multipleOf algorithm deliberately uses a
 * floating quotient for a float divisor (with an exact overflow fallback).
 * Keep those compatibility semantics private to this opt-in answer profile;
 * networknt's normal exact-decimal frozen-contract validation stays unchanged.
 */
internal class Cd1ValueKeyword(private val name: String) : Keyword {
    override fun getValue(): String = name

    override fun newValidator(location: SchemaLocation, path: JsonNodePath, schemaNode: JsonNode,
                              parentSchema: JsonSchema, validationContext: ValidationContext): JsonValidator {
        val constant = if (name == "const") key(schemaNode) else null
        val choices = if (name == "enum") schemaNode.elements().asSequence().map(::key).toSet() else null
        return object : AbstractJsonValidator(location, path, this, schemaNode) {
            override fun validate(executionContext: ExecutionContext, value: JsonNode, rootNode: JsonNode,
                                  instanceLocation: JsonNodePath): Set<ValidationMessage> {
                val accepted = when (name) {
                    "const" -> key(value) == constant
                    "enum" -> key(value) in checkNotNull(choices)
                    "uniqueItems" -> {
                        if (!schemaNode.booleanValue() || !value.isArray) true
                        else {
                            val seen = HashSet<ValueKey>()
                            value.all { seen.add(key(it)) }
                        }
                    }
                    else -> !value.isNumber || when (name) {
                        "minimum" -> exact(value) >= exact(schemaNode)
                        "maximum" -> exact(value) <= exact(schemaNode)
                        "exclusiveMinimum" -> exact(value) > exact(schemaNode)
                        "exclusiveMaximum" -> exact(value) < exact(schemaNode)
                        "multipleOf" -> multiple(value, schemaNode)
                        else -> error("Unexpected numeric keyword")
                    }
                }
                if (accepted) return emptySet()
                return setOf(ValidationMessage.builder().type(name).code(name)
                    .schemaLocation(location).evaluationPath(path).instanceLocation(instanceLocation)
                    .message("Provided value does not satisfy the field schema").build())
            }
        }
    }

    companion object {
        val names = setOf("minimum", "maximum", "exclusiveMinimum", "exclusiveMaximum",
            "multipleOf", "const", "enum", "uniqueItems")

        private fun exact(value: JsonNode): BigDecimal = if (value.isIntegralNumber)
            BigDecimal(value.bigIntegerValue()) else BigDecimal(value.doubleValue())

        private fun multiple(value: JsonNode, divisor: JsonNode): Boolean {
            if (divisor.isFloatingPointNumber) {
                val quotient = value.doubleValue() / divisor.doubleValue()
                return if (quotient.isFinite()) quotient % 1.0 == 0.0
                    else exact(value).remainder(exact(divisor)).signum() == 0
            }
            return if (value.isIntegralNumber) value.bigIntegerValue().remainder(divisor.bigIntegerValue()).signum() == 0
                else value.doubleValue() % divisor.doubleValue() == 0.0
        }

        /** Type-tagged structural equality keeps bool distinct from number and
         * makes integer/float aliases equal only at the reference's exact value.
         * Hashing also avoids a quadratic uniqueItems scan of bounded arrays.
         */
        private data class ValueKey(val kind: Int, val value: Any?)

        private fun key(node: JsonNode): ValueKey = when {
            node.isNull -> ValueKey(0, null)
            node.isBoolean -> ValueKey(1, node.booleanValue())
            node.isNumber -> ValueKey(2, exact(node).stripTrailingZeros())
            node.isTextual -> ValueKey(3, node.textValue())
            node.isArray -> ValueKey(4, node.map(::key))
            node.isObject -> ValueKey(5, node.properties().associate { it.key to key(it.value) })
            else -> throw Cd1Rejected(Cd1Problem.INVALID_FIELD_SCHEMA)
        }
    }
}
