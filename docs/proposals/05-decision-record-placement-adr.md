# ADR proposal: place three Android decision receipts with their client

**Status: proposed; not adopted.** This is a separate placement decision for
[Android #34](https://github.com/Pick-Your-AI/pick-your-ai-android/issues/34).
It proposes a repository change for exactly three acceptance records. Their
current website locations remain authoritative until this proposal receives
explicit independent adoption review and the published transition is verified.
No record, issue field, validator, instruction or remote repository is changed by
this proposal.

## Governing authority and current state

The accepted mobile migration deliberately left an explicit placement decision
available. At research
[`da3c6045f84260506bd326bb24ddfa8a7e6a23e8`](https://github.com/Pick-Your-AI/pick-your-ai-research/tree/da3c6045f84260506bd326bb24ddfa8a7e6a23e8):

- [MIGRATION.md, Placement and shared authority](https://github.com/Pick-Your-AI/pick-your-ai-research/blob/da3c6045f84260506bd326bb24ddfa8a7e6a23e8/prompts/mobile/MIGRATION.md#placement-and-shared-authority)
  states that planning belongs in the mobile repositories and preserves the
  Android website record locations until explicitly changed through the placement
  decision. Repository creation or issue transfer alone does not make that change.
- [SHARED.md, Reconcile prerequisites and architecture](https://github.com/Pick-Your-AI/pick-your-ai-research/blob/da3c6045f84260506bd326bb24ddfa8a7e6a23e8/prompts/mobile/SHARED.md#reconcile-prerequisites-and-architecture)
  identifies Android #34 as the placement decision. It requires the approved
  destination to be recorded and prohibits silently inventing a path or moving a
  research decision record.
- The live #20, #24 and #34 issue migration sections preserve the existing website
  acceptance paths pending an explicit placement decision. Their exact criteria,
  prerequisite outputs, review roles and historical issue identities remain the
  baseline; a new repository is not a new work package.
- [Android contributor instructions](../../AGENTS.md) require preservation of
  acceptance paths and shared authorities. The current
  [repository scope](../REPOSITORY_SCOPE.md) explicitly says its earlier
  engineering-state reconciliation did not migrate these records. Neither document
  claims to have already adopted the change proposed here.

Readback on 2026-10-18 found website main
`dc66a9e269917ab7241c7d08fd0e7f2a2f039872`. Its complete 229-entry tree contains no
`docs/delivery` directory, hence none of these three intended records. This is a
missing artifact, not a prohibition on creating it. The existing website route is
a normal documentation PR with `ci-required`; it also has a real automatic
production-deployment side effect on main. Website `CODEOWNERS` names
`@CompleteDotTech` by default. This proposal does not edit website operations,
disable its checks or request an exception to its merge/deployment policy.

## Proposed decision and exact mapping

Place the three initial Android product/architecture acceptance records in
`Pick-Your-AI/pick-your-ai-android`, retaining each existing relative filename.
The Android client already owns the corresponding issues, source, tests and
engineering decisions. Keeping each receipt beside those versioned artifacts
makes future receiving review traceable without coupling routine client decision
updates to the static website's release lifecycle.

| Same logical issue | Original website issue URL | Current authoritative acceptance record | Proposed acceptance record |
| --- | --- | --- | --- |
| [Android #20](https://github.com/Pick-Your-AI/pick-your-ai-android/issues/20) | [pickyour.ai #53](https://github.com/Pick-Your-AI/pickyour.ai/issues/53) | `Pick-Your-AI/pickyour.ai` — `docs/delivery/android/work-packages/01.01.md` | `Pick-Your-AI/pick-your-ai-android` — `docs/delivery/android/work-packages/01.01.md` |
| [Android #24](https://github.com/Pick-Your-AI/pick-your-ai-android/issues/24) | [pickyour.ai #57](https://github.com/Pick-Your-AI/pickyour.ai/issues/57) | `Pick-Your-AI/pickyour.ai` — `docs/delivery/android/work-packages/01.02.md` | `Pick-Your-AI/pick-your-ai-android` — `docs/delivery/android/work-packages/01.02.md` |
| [Android #34](https://github.com/Pick-Your-AI/pick-your-ai-android/issues/34) | [pickyour.ai #67](https://github.com/Pick-Your-AI/pickyour.ai/issues/67) | `Pick-Your-AI/pickyour.ai` — `docs/delivery/android/work-packages/01.03.md` | `Pick-Your-AI/pick-your-ai-android` — `docs/delivery/android/work-packages/01.03.md` |

The original issue URLs redirect to the transferred issues; do not recreate or
transfer issues again. Preserve the
[accepted old/new issue mapping](https://github.com/Pick-Your-AI/pick-your-ai-research/blob/da3c6045f84260506bd326bb24ddfa8a7e6a23e8/prompts/mobile/issue-migration.json)
and all existing native parent/prerequisite relationships.

Every other Android record keeps its existing destination. In particular, #48's
`02.01.md`, the remaining work packages, epic records and program record are
outside this proposal. No Apple output, research decision, website service record,
contract, evidence authority or source-rights record moves.

## Review and evidence requirements remain the same

Each new receipt must contain the complete live criterion text, immutable decision
and input references, actual check/observation results, negative/recovery results,
receiving review and remaining limits. All nine #20/#24/#34 criteria remain exact.
The [initial decision candidate](04-initial-decisions.md) is an input, not a receipt
of its own acceptance.

The required roles remain **product decision authority and Android technical
lead** for each of the three issues. Record the actual author, independent
reviewer, roles covered, exact reviewed version and decision. The user's delegated
project authority permits concrete project choices and in-session independent
review; it does not establish an unrelated person's agreement or turn an account
name into a review. Repository-required independent/CODEOWNERS reviews, if any,
must still be satisfied normally. Do not create an imaginary named owner to fill
a table, or use missing assignee metadata alone to stop authorized decision work.

Preserve the sequence of consumed decision outputs: #20's users/jobs decision →
#24's release/mode boundary → #34's technology/module/identity and placement
decision. These outputs can receive explicit review together. A placement proposal
does not accept the product decisions, and product review does not silently adopt
a different record repository.

The distinction between decision acceptance and runtime acceptance also remains:
#20's desk walkthrough, #24's mode review and #34's source/architecture inspection
are their stated validation methods. The complete edited-draft summary, actual
installed lifecycle behavior, real source evidence, supported-device matrix,
security, publisher and store outcomes remain with their implementation issues.
Moving a record supplies none of those outcomes and changes no acceptance gate.

## Explicit transition plan after adoption review

1. Refresh #20/#24/#34, comments, active work, accepted source and both repository
   locations. If any intended website receipt has appeared, preserve its exact
   content and provenance and reconcile with its owner before moving authority.
   Do not overwrite a concurrently published record or maintain two conflicting
   canonical versions.
2. Obtain independent review explicitly accepting or rejecting this #34 placement
   ADR and its exact three-row mapping. Record the reviewed artifact/tree and the
   actual decision authority. Change this ADR's status only after that decision;
   the proposal remains inactive while review is pending.
3. Prepare the three Android receipts with the actual reviewed #20/#24/#34
   decisions, required criterion tables and limitations. Each must retain the
   previous repository/path, original issue URL and adopting ADR reference, so a
   reader can follow the complete transition. Retain any prior failed evidence;
   never convert synthetic/reference checks into installed or production claims.
4. Reconcile Android's repository-scope and delivery-index descriptions and the
   exact three `canonical_acceptance_record` coordinates. Preserve all other
   coordinates, criteria/digests, issue identities, evidence and prerequisites.
   The three decision records must not be treated as authority for moving the
   other 126 issue records.
5. Review the necessary ledger support deliberately. The current
   [validator](../../scripts/check_delivery_ledger.py) requires every acceptance
   repository to be the website, and its
   [negative test](../../scripts/tests/test_delivery_ledger.py) rejects a moved
   repository. Adopt an explicit, exact three-issue repository/path mapping with
   the placement receipt as provenance; add a positive check for each approved
   coordinate and preserve rejection of any different issue, repository or path.
   Do not replace the guard with an unrestricted list of repositories.
6. Keep completion support separate from destination support. Ledger version 1
   deliberately permits only incomplete criteria and empty completion evidence.
   If updating that representation for accepted decisions, review an explicit
   evidence schema with immutable artifact and independent-review references;
   retain negative tests for missing, mismatched or unreviewed completion. Until
   that change is reviewed, the ledger may not claim a supported complete state.
   A directory or artifact link alone never justifies issue closure.
7. Publish the scoped Android change through ordinary signed, independently
   reviewed PR delivery and its actual required checks. Verify the final accepted
   tree and receipt contents. Then reconcile only #20/#24/#34's live record-location
   text and exact acceptance disposition under their existing issue identities,
   preserving unrelated body content, fields, labels and native relationships.
8. Coordinate the three destination updates with the canonical mobile prompt/index
   authority in research. Preserve the historical migration snapshot, frozen v1
   and all unaffected rows; add a precise accepted placement amendment and update
   the affected current index/coverage coordinates with their normal validation
   and independent review. Do not change broad Android/Apple program destinations
   or claim research acceptance through an Android PR.

The transition is complete only when the adopted destination, published records
and current handoff references agree, and each disposition has been checked
against its exact issue criteria. Any pending synchronization must be reported as
such, with the accepted ADR and authoritative record version linked explicitly.
Website files are untouched in this bounded proposal; an old-path link may be
added only through that repository's separately authorized normal contribution
route if needed. There is no operation that disables or bypasses deployment.

## Proposal validation and adoption boundary

Validate this proposal by comparing the mapping with the live issue bodies and
accepted migration, checking the nine preserved decision criteria and unchanged
prerequisite/reviewer requirements, inspecting the actual ledger guard, and checking
relative links and whitespace. No app or host configuration changes are involved.

The adopting review must explicitly state both whether the three destinations are
accepted and whether the corresponding decision evidence is sufficient. Until
then the website remains the current canonical location, no receipt is claimed
created, and no issue is complete because this ADR exists.
