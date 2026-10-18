package ai.pickyour.core.contracts

import com.fasterxml.jackson.databind.JsonNode
import java.math.BigDecimal

/** Frozen-v1 boundary failure; never includes the rejected value or source content. */
enum class ContractProblem { INVALID_MONEY, INVALID_DECIMAL, UNSUPPORTED_VERSION }
class ContractRejected(val problem: ContractProblem) : IllegalArgumentException(problem.name)

/** Preserve the original contract lexeme, including trailing zeroes. No binary floats. */
class ContractDecimal private constructor(val encoded: String) {
    fun exactValue(): BigDecimal = BigDecimal(encoded)
    override fun equals(other: Any?): Boolean = other is ContractDecimal && encoded == other.encoded
    override fun hashCode(): Int = encoded.hashCode()
    // Do not include user-provided amounts in accidental diagnostic output.
    override fun toString(): String = "ContractDecimal"

    companion object {
        private val grammar = Regex("^(0|[1-9][0-9]*)(\\.[0-9]{1,12})?$")
        fun parse(encoded: String): ContractDecimal {
            if (!grammar.matches(encoded)) throw ContractRejected(ContractProblem.INVALID_DECIMAL)
            return ContractDecimal(encoded)
        }
    }
}

/** This is a money value, not a quote, rate-selection policy or complete cost estimate. */
class ContractMoney private constructor(val amount: ContractDecimal, val currency: String) {
    companion object {
        fun fromJson(node: JsonNode): ContractMoney {
            if (!node.isObject || node.size() != 2 || !node.has("amount") || !node.has("currency") ||
                !node["amount"].isTextual || !node["currency"].isTextual ||
                !Regex("^[A-Z]{3}$").matches(node["currency"].textValue())) {
                throw ContractRejected(ContractProblem.INVALID_MONEY)
            }
            return ContractMoney(ContractDecimal.parse(node["amount"].textValue()), node["currency"].textValue())
        }
    }
}

/** Version discrimination only. Call the document-specific validator after this check. */
fun requireFrozenV1(node: JsonNode) {
    if (!node.isObject || !node.path("schema_version").isTextual ||
        node.path("schema_version").textValue() != "1.0.0") {
        throw ContractRejected(ContractProblem.UNSUPPORTED_VERSION)
    }
}
