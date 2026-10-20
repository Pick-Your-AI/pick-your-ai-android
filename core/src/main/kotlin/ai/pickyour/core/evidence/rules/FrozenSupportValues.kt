package ai.pickyour.core.evidence.rules

import ai.pickyour.core.contracts.FrozenCatalogKind
import ai.pickyour.core.evidence.membership.MembershipEvidenceLabel
import ai.pickyour.core.evidence.membership.MembershipFinding
import ai.pickyour.core.evidence.membership.MembershipOutcome
import ai.pickyour.core.evidence.membership.isMembershipBlank
import ai.pickyour.core.evidence.membership.requireMembershipText
import ai.pickyour.core.evidence.membership.snapshotMembershipStrings
import com.fasterxml.jackson.databind.node.ObjectNode
import java.util.Collections

/** The only baseline assertions admitted by this opt-in mechanics adapter. */
enum class FrozenSupportRule(val id: String) {
    MODEL_FORMAT("rule.engine.model.format"),
    ENGINE_PRECISION("rule.precision.engine"),
}

/**
 * Exact observed or caller-proposed scope. Frozen-v1 text may be wider than the
 * normalized evidence profile; only [RuleEvidence] validates a caller proposal.
 */
data class RuleSupportScope(
    val selectionId: String,
    val catalogSha256: String,
    val itemId: String,
    val itemVersion: String,
    val itemKind: String,
    val property: String,
    val conditionsSha256: String,
) {
    override fun toString(): String = "RuleSupportScope"
}

/**
 * Caller-established evidence for one exact cohort. Null unsupported claims are
 * unresolved; an explicitly empty set is recorded absence of negative claims.
 * Exhaustiveness and requested completeness remain independently qualified.
 */
class RuleEvidence(
    val cohortId: String,
    val cohortVersion: String,
    val scope: RuleSupportScope?,
    unsupported: Set<String>? = null,
    val exhaustive: Boolean? = null,
    val requestedComplete: Boolean? = null,
) {
    val unsupported: Set<String>? = unsupported?.let(::snapshotMembershipStrings)

    init {
        requireRuleId(cohortId)
        requireRuleVersion(cohortVersion)
        scope?.let(::requireRuleScope)
        require(this.unsupported == null || this.unsupported.none(::isMembershipBlank)) {
            "Rule unsupported values must be nonblank strings"
        }
    }

    override fun toString(): String = "RuleEvidence"
}

/** No readiness, source admission, severity or independent verification is inferred. */
class FrozenSupportDecision internal constructor(
    val outcome: MembershipOutcome,
    val reason: String,
    val sourceId: String,
    findings: List<MembershipFinding> = emptyList(),
    val disputed: Boolean = false,
    val evidenceLabel: MembershipEvidenceLabel? = null,
) {
    val findings: List<MembershipFinding> = Collections.unmodifiableList(ArrayList(findings))
    override fun toString(): String = "FrozenSupportDecision"
}

/**
 * A detached per-rule observation. [details] returns a fresh copy in the pinned
 * prototype's comparison vocabulary; it is not a new transport or frozen schema.
 */
class FrozenSupportResult internal constructor(
    val decision: FrozenSupportDecision,
    val supportScope: RuleSupportScope?,
    private val observation: ObjectNode,
) {
    fun details(): ObjectNode = observation.deepCopy()
    override fun toString(): String = "FrozenSupportResult"
}

internal fun requireRuleId(value: String) {
    require(value.length in 3..120 && value[0] in "abcdefghijklmnopqrstuvwxyz0123456789" &&
        value.all { it in "abcdefghijklmnopqrstuvwxyz0123456789._:-" }) { "Invalid rule identity" }
}

internal fun requireRuleVersion(value: String) {
    requireMembershipText(value)
    require(value.codePointCount(0, value.length) in 1..200 && !isMembershipBlank(value) &&
        value.none { it.code < 0x20 || it.code == 0x7f }) { "Invalid rule version" }
}

internal fun requireRuleDigest(value: String) {
    require(value.length == 64 && value.all { it in "0123456789abcdef" }) { "Invalid rule scope digest" }
}

private fun requireRuleScope(value: RuleSupportScope) {
    requireRuleId(value.selectionId)
    requireRuleDigest(value.catalogSha256)
    requireRuleId(value.itemId)
    requireRuleVersion(value.itemVersion)
    require(value.itemKind in FrozenCatalogKind.entries.map { it.encoded }) { "Invalid rule scope kind" }
    requireMembershipText(value.property)
    require(value.property.startsWith("/") && value.property.none { it.code < 0x20 || it.code == 0x7f }) {
        "Invalid rule scope pointer"
    }
    var index = 0
    while (index < value.property.length) {
        if (value.property[index++] == '~') {
            require(index < value.property.length && value.property[index++] in "01") { "Invalid rule scope pointer" }
        }
    }
    requireRuleDigest(value.conditionsSha256)
}
