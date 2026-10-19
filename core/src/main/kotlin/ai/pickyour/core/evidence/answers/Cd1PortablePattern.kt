package ai.pickyour.core.evidence.answers

import com.networknt.schema.regex.RegularExpression
import java.util.regex.Pattern

/**
 * Deliberately bounded portable subset, not arbitrary Python/Java regex parity.
 * ASCII literals, positive classes, dot and end anchors are supported. At most
 * one quantified atom is allowed, and it requires a start anchor; this avoids
 * nested or repeatedly restarted backtracking over caller-controlled values.
 */
internal object Cd1PortablePattern {
    private const val ESCAPED = ".^$|?*+()[]{}\\-"

    fun compile(expression: String): RegularExpression {
        requireSupported(expression)
        // Python's default dot/$ behavior treats only LF as the line ending.
        val pattern = Pattern.compile(expression, Pattern.UNIX_LINES)
        return RegularExpression { value -> pattern.matcher(value).find() }
    }

    fun requireSupported(expression: String) {
        cd1Require(expression.length <= 256 && expression.all { it.code in 0x20..0x7e },
            Cd1Problem.INVALID_FIELD_SCHEMA)
        var offset = 0
        var atom = false
        var quantifiers = 0
        while (offset < expression.length) {
            when (val token = expression[offset++]) {
                '^' -> { cd1Require(offset == 1, Cd1Problem.INVALID_FIELD_SCHEMA); atom = false }
                '$' -> { cd1Require(offset == expression.length, Cd1Problem.INVALID_FIELD_SCHEMA); atom = false }
                '\\' -> {
                    cd1Require(offset < expression.length && expression[offset++] in ESCAPED,
                        Cd1Problem.INVALID_FIELD_SCHEMA)
                    atom = true
                }
                '[' -> {
                    val start = offset
                    cd1Require(offset < expression.length && expression[offset] != '^', Cd1Problem.INVALID_FIELD_SCHEMA)
                    while (offset < expression.length && expression[offset] != ']') {
                        val item = expression[offset++]
                        cd1Require(item != '[' && item != '&', Cd1Problem.INVALID_FIELD_SCHEMA)
                        if (item == '\\') {
                            cd1Require(offset < expression.length && expression[offset++] in ESCAPED,
                                Cd1Problem.INVALID_FIELD_SCHEMA)
                        } else if (item in "-|~") {
                            cd1Require(offset == expression.length || expression[offset] != item,
                                Cd1Problem.INVALID_FIELD_SCHEMA)
                        }
                    }
                    cd1Require(offset > start && offset < expression.length, Cd1Problem.INVALID_FIELD_SCHEMA)
                    offset++
                    atom = true
                }
                '*', '+', '?', '{' -> {
                    cd1Require(atom && ++quantifiers == 1 && expression.startsWith('^'), Cd1Problem.INVALID_FIELD_SCHEMA)
                    if (token == '{') {
                        val end = expression.indexOf('}', offset)
                        cd1Require(end > offset, Cd1Problem.INVALID_FIELD_SCHEMA)
                        val bounds = expression.substring(offset, end).split(',')
                        cd1Require(bounds.size in 1..2 && bounds[0].matches(Regex("0|[1-9][0-9]{0,3}")),
                            Cd1Problem.INVALID_FIELD_SCHEMA)
                        val minimum = bounds[0].toInt()
                        cd1Require(minimum <= 1024, Cd1Problem.INVALID_FIELD_SCHEMA)
                        if (bounds.size == 2 && bounds[1].isNotEmpty()) {
                            cd1Require(bounds[1].matches(Regex("0|[1-9][0-9]{0,3}")), Cd1Problem.INVALID_FIELD_SCHEMA)
                            cd1Require(bounds[1].toInt() in minimum..1024, Cd1Problem.INVALID_FIELD_SCHEMA)
                        }
                        offset = end + 1
                    }
                    atom = false // Reluctant, possessive and repeated quantifiers are outside this subset.
                }
                '(', ')', '|', ']', '}' -> throw Cd1Rejected(Cd1Problem.INVALID_FIELD_SCHEMA)
                else -> atom = true
            }
        }
        // Reject malformed ASCII ranges and escapes before a predicate can run.
        Pattern.compile(expression, Pattern.UNIX_LINES)
    }
}
