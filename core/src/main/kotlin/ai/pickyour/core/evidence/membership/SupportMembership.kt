package ai.pickyour.core.evidence.membership

/**
 * Pure CD-3 membership mechanics over normalized inputs. This reducer supplies no
 * source qualification, binding, frozen-rule activation, joint-fit or severity policy.
 */
object SupportMembership {
    fun evaluate(
        requested: Set<String>?,
        evidence: SupportEvidence,
        requirement: MembershipRequirement = MembershipRequirement.ANY,
        applicable: Boolean? = true,
        requestedComplete: Boolean? = null,
    ): MembershipDecision {
        // Reject malformed requested values before applicability or eligibility shortcuts.
        val requestedValues = requested?.let(::snapshotMembershipStrings)
        require(requestedValues == null || requestedValues.none(::isMembershipBlank)) {
            "Requested membership values must be nonblank"
        }
        fun early(outcome: MembershipOutcome, reason: MembershipDecisionReason) =
            MembershipDecision(outcome, reason, evidence.sourceId)

        if (applicable == false)
            return early(MembershipOutcome.SKIPPED, MembershipDecisionReason.NOT_APPLICABLE)
        if (applicable == null)
            return early(MembershipOutcome.UNKNOWN, MembershipDecisionReason.APPLICABILITY_UNKNOWN)
        if (requestedValues == null)
            return early(MembershipOutcome.UNKNOWN, MembershipDecisionReason.REQUIREMENT_UNKNOWN)
        if (requestedValues.isEmpty())
            return early(MembershipOutcome.UNKNOWN, MembershipDecisionReason.REQUIREMENT_EMPTY)
        if (evidence.eligible != true)
            return early(MembershipOutcome.UNKNOWN, MembershipDecisionReason.NO_ELIGIBLE_EVIDENCE)

        val findings = requestedValues.sortedWith(membershipTextOrder).map { value -> member(value, evidence) }
        val outcomes = findings.mapTo(mutableSetOf()) { it.outcome }
        var outcome = when (requirement) {
            MembershipRequirement.ANY -> when {
                MembershipOutcome.PASS in outcomes -> MembershipOutcome.PASS
                MembershipOutcome.UNKNOWN in outcomes -> MembershipOutcome.UNKNOWN
                else -> MembershipOutcome.FAIL
            }
            MembershipRequirement.ALL -> when {
                MembershipOutcome.FAIL in outcomes -> MembershipOutcome.FAIL
                MembershipOutcome.UNKNOWN in outcomes -> MembershipOutcome.UNKNOWN
                else -> MembershipOutcome.PASS
            }
        }
        var reason = when (requirement) {
            MembershipRequirement.ANY -> MembershipDecisionReason.ANY_MEMBERSHIP
            MembershipRequirement.ALL -> MembershipDecisionReason.ALL_MEMBERSHIP
        }
        val needsComplete =
            (requirement == MembershipRequirement.ANY && outcome == MembershipOutcome.FAIL) ||
                (requirement == MembershipRequirement.ALL && outcome == MembershipOutcome.PASS)
        if (needsComplete && requestedComplete != true) {
            outcome = MembershipOutcome.UNKNOWN
            reason = if (requestedComplete == false) MembershipDecisionReason.REQUESTED_SET_INCOMPLETE
                else MembershipDecisionReason.REQUESTED_COMPLETENESS_UNKNOWN
        }
        val usedEvidence = findings.any { it.reason != MembershipFindingReason.SUPPORT_LIST_MISSING }
        return MembershipDecision(
            outcome, reason, evidence.sourceId, findings, findings.any { it.disputed },
            if (evidence.checkedOnly == true && usedEvidence) MembershipEvidenceLabel.CHECKED_ONLY_UNVERIFIED else null,
        )
    }

    private fun member(value: String, evidence: SupportEvidence): MembershipFinding {
        val positive = evidence.supported?.contains(value) == true
        val negative = value in evidence.unsupported
        val (outcome, reason) = when {
            positive && negative -> MembershipOutcome.UNKNOWN to MembershipFindingReason.OPPOSITE_EQUAL_RANK_CLAIMS
            positive -> MembershipOutcome.PASS to MembershipFindingReason.DOCUMENTED_MEMBER
            negative -> MembershipOutcome.FAIL to MembershipFindingReason.EXPLICIT_UNSUPPORTED
            evidence.supported == null -> MembershipOutcome.UNKNOWN to MembershipFindingReason.SUPPORT_LIST_MISSING
            evidence.exhaustive == true -> MembershipOutcome.FAIL to MembershipFindingReason.EXHAUSTIVE_ABSENCE
            else -> MembershipOutcome.UNKNOWN to MembershipFindingReason.UNMARKED_ABSENCE
        }
        return MembershipFinding(value, outcome, reason, positive && negative)
    }
}
