package ai.pickyour.core.evidence.membership

import java.util.Collections

/** Internal result vocabulary; these values do not extend a frozen wire schema. */
enum class MembershipOutcome(val encoded: String) {
    PASS("pass"), FAIL("fail"), UNKNOWN("unknown"), SKIPPED("skipped"),
}

enum class MembershipRequirement(val encoded: String) { ANY("any"), ALL("all") }

enum class MembershipFindingReason(val encoded: String) {
    DOCUMENTED_MEMBER("documented_member"),
    EXPLICIT_UNSUPPORTED("explicit_unsupported"),
    SUPPORT_LIST_MISSING("support_list_missing"),
    EXHAUSTIVE_ABSENCE("exhaustive_absence"),
    UNMARKED_ABSENCE("unmarked_absence"),
    OPPOSITE_EQUAL_RANK_CLAIMS("opposite_equal_rank_claims"),
}

enum class MembershipDecisionReason(val encoded: String) {
    NOT_APPLICABLE("not_applicable"),
    APPLICABILITY_UNKNOWN("applicability_unknown"),
    REQUIREMENT_UNKNOWN("requirement_unknown"),
    REQUIREMENT_EMPTY("requirement_empty"),
    NO_ELIGIBLE_EVIDENCE("no_eligible_evidence"),
    ANY_MEMBERSHIP("any_membership"),
    ALL_MEMBERSHIP("all_membership"),
    REQUESTED_SET_INCOMPLETE("requested_set_incomplete"),
    REQUESTED_COMPLETENESS_UNKNOWN("requested_completeness_unknown"),
}

enum class MembershipEvidenceLabel(val encoded: String) {
    CHECKED_ONLY_UNVERIFIED("unverified"),
}

/**
 * A caller-qualified, single scoped evidence cohort. The caller must resolve
 * source/version/condition matching and authority before constructing this value.
 * Presence, exhaustive inventory and checked provenance cannot establish eligibility.
 */
class SupportEvidence(
    val sourceId: String,
    val eligible: Boolean?,
    supported: Set<String>?,
    val exhaustive: Boolean? = null,
    unsupported: Set<String> = emptySet(),
    val checkedOnly: Boolean? = null,
) {
    val supported: Set<String>? = supported?.let(::snapshotMembershipStrings)
    val unsupported: Set<String> = snapshotMembershipStrings(unsupported)

    init { requireMembershipText(sourceId) }

    override fun toString(): String = "SupportEvidence"
}

class MembershipFinding internal constructor(
    val value: String,
    val outcome: MembershipOutcome,
    val reason: MembershipFindingReason,
    val disputed: Boolean = false,
) {
    override fun toString(): String = "MembershipFinding"
}

/** Immutable observations. Absence of an evidenceLabel never means verified. */
class MembershipDecision internal constructor(
    val outcome: MembershipOutcome,
    val reason: MembershipDecisionReason,
    val sourceId: String,
    findings: List<MembershipFinding> = emptyList(),
    val disputed: Boolean = false,
    val evidenceLabel: MembershipEvidenceLabel? = null,
) {
    val findings: List<MembershipFinding> = Collections.unmodifiableList(ArrayList(findings))
    override fun toString(): String = "MembershipDecision"
}

/** JVM text admission: accept Unicode scalar sequences, without normalizing them. */
internal fun requireMembershipText(value: String) {
    var offset = 0
    while (offset < value.length) {
        val next = value[offset++]
        when {
            Character.isHighSurrogate(next) -> {
                require(offset < value.length && Character.isLowSurrogate(value[offset])) {
                    "Membership text contains an unpaired surrogate"
                }
                offset++
            }
            Character.isLowSurrogate(next) ->
                throw IllegalArgumentException("Membership text contains an unpaired surrogate")
        }
    }
}

internal fun snapshotMembershipStrings(values: Set<*>): Set<String> {
    val copy = LinkedHashSet<String>()
    for (value in values) {
        require(value is String) { "Membership values must be strings" }
        requireMembershipText(value)
        copy.add(value)
    }
    return Collections.unmodifiableSet(copy)
}

/** Match the pinned Python 3.12 str.strip predicate, not Kotlin's different isBlank. */
internal fun isMembershipBlank(value: String): Boolean = value.all { character ->
    when (character.code) {
        in 0x0009..0x000d, in 0x001c..0x0020, 0x0085, 0x00a0, 0x1680,
        in 0x2000..0x200a, 0x2028, 0x2029, 0x202f, 0x205f, 0x3000 -> true
        else -> false
    }
}

/** Python orders scalar values; JVM String's natural ordering compares UTF-16 units. */
internal val membershipTextOrder = Comparator<String> { left, right ->
    var a = 0
    var b = 0
    var compared = 0
    while (a < left.length && b < right.length) {
        val ca = Character.codePointAt(left, a)
        val cb = Character.codePointAt(right, b)
        compared = ca.compareTo(cb)
        if (compared != 0) break
        a += Character.charCount(ca)
        b += Character.charCount(cb)
    }
    if (compared != 0) compared else (left.length - a).compareTo(right.length - b)
}
