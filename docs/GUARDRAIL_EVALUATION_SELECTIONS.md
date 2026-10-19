# Explicit guardrail and evaluation inputs with persistent core guidance

Partial [Android #41](https://github.com/Pick-Your-AI/pick-your-ai-android/issues/41),
using the accepted Kotlin core, native Views preview and canonical managed-library
commands. This continuation admits exactly frozen `guardrail` and
`evaluation_suite` selections. The shared five-area `T6PlanningSection` from
[signed guidance #440](https://github.com/Pick-Your-AI/pick-your-ai-android/pull/440)
keeps core guidance present before save and when all optional tiers are disabled;
the two editors add exact field and evidence-condition explanations in their
forms. One shared summary reports the acknowledged choices. There is no
executable readiness evaluator or measured-result view.

The intended acceptance record remains
`Pick-Your-AI/pickyour.ai:docs/delivery/android/work-packages/13.04.md`; this local
engineering record neither relocates it nor asserts that the website file exists.
All three #41 criteria remain incomplete. The relevant T3–T6 research owners and
Android domain reviewer retain their receiving roles, and
[#27](https://github.com/Pick-Your-AI/pick-your-ai-android/issues/27) still gates
the accepted unknown, unanswered, unsupported and evidence semantics.

## Exact frozen input boundary

The unchanged [Selection schema](../contracts/v1/schemas/selection.schema.json)
and [kind registry](../contracts/v1/schemas/component-registry.json) define both
kinds. Their five settings are all optional; an empty object is a visible
unanswered input, and omitted members stay absent. Neither kind gains a default
policy, score threshold, minimum dataset or enabled state.

| Kind | Setting | Frozen accepted value |
| --- | --- | --- |
| `guardrail` | `enforcement` | `audit`, `block`, `redact` or `escalate` |
| `guardrail` | `failure_action` | `fail_closed` or `fail_open` |
| `evaluation_suite` | `minimum_score` | Number, with no frozen minimum or maximum |
| `evaluation_suite` | `gate_required` | Boolean |
| `evaluation_suite` | `dataset_size` | Integer of 1 or greater |

The UI explains these rules before entry and in settings editing. A threshold is
the user's requested input, with no recommendation or implicit 0–1 range. The
existing bounded JSON reader preserves exact large integers and decimal values;
the editor imposes no new numeric normalization. Invalid types, `null` or the
string `unknown` in these typed positions reject the complete form. Duplicate
keys, additional members and proposed evidence fields reject as well. The exact
invalid text remains available for correction and never replaces the saved draft.

Both kinds use the existing many-selection cardinality. Each selection has an
explicit identity, role, enabled checkbox and exact catalog item/version/hash.
The initially unchecked checkbox does not imply a default deployment choice.
Distinct roles can coexist, including disabled alternatives; a duplicate
`(kind, role)` is rejected without displacing either selection. Exact catalog
replacement retains the selection identity, role, state, settings, notes,
extensions and relationships. Removing a selection removes only that identity
and its incident links, with no promotion of a remaining control or evaluation.

The original two-kind continuation left `deployment_target`, `monitoring_stack`
and `cost_policy` outside its mutation subset. The subsequent
[operational continuation](OPERATIONAL_SELECTIONS.md) admits those three frozen
kinds separately for partial #52 and preserves this same shared panel. Frozen schemas, examples,
registry and source pins are unchanged. Proposed guardrail target categories,
policy references, evaluation metrics, dataset references, evidence/report
references, benchmark tiers and new rule revisions are not adopted as settings.

## Core guidance and evidence conditions

The shared T6 planning section always contains all five planning areas and an
explanation that this review remains part of core work. This text does not depend
on catalog availability, a selected guardrail, a saved draft, or T3/T4/T5 intent.
Only those three existing optional tiers have tier controls; no T6 tier switch is
introduced. Counts belong to one acknowledged saved revision; loading and
unavailable storage withhold them, and pending guest edits do not replace that
revision. A selection's enabled state changes that selection alone and cannot
silence the core guidance or establish coverage, enforcement or a passed gate.

The shared panel keeps measured guardrail behavior and evaluation results
explicitly unavailable. The two forms also explain that applicable requirements
and measured coverage are unavailable here and that no safety, quality or
readiness verdict is supplied. They identify the model, runtime, task or dataset,
harness and measurement conditions needed for a comparable result. The field guidance
distinguishes a requested gate, score and dataset size from a measured result or
completed evaluation. A recorded `fail_open` value is retained as input without
being presented as an approved policy; no service is called and no control is
applied when any of these values are saved.

The consumed research context includes the pinned
[Android epic-13 prompt](https://github.com/Pick-Your-AI/pick-your-ai-research/blob/da3c6045f84260506bd326bb24ddfa8a7e6a23e8/prompts/mobile/android/epic-13.md),
the exact #41 acceptance entry at that mobile-guidance revision, and the
[T6 guidance](https://github.com/Pick-Your-AI/pick-your-ai-research/blob/08bdd5ef016a1033a00d8a6f6161b0f605712082/research/T6-deployment-operations/README.md),
[guardrail research](https://github.com/Pick-Your-AI/pick-your-ai-research/blob/08bdd5ef016a1033a00d8a6f6161b0f605712082/research/T6-deployment-operations/guardrails.md)
and [evaluation research](https://github.com/Pick-Your-AI/pick-your-ai-research/blob/08bdd5ef016a1033a00d8a6f6161b0f605712082/research/T6-deployment-operations/evaluation.md)
at the observed current Research revision. Their evidence, execution and
versioned-extension boundaries remain distinct from frozen v1 input acceptance.
No benchmark/moderation workload, rule promotion, rights approval or live
measurement follows from editing this local draft.

No EvaluationReport is created, imported or displayed by these controls.
Publisher assertions, independently checked evidence and reproduced results do
not collapse into one status. An absent or incomparable result cannot become a
general badge because this slice supplies no such result or verdict. A future
result surface still needs accepted condition fields, absent/incomparable
fixtures and the #27/domain receiving decisions; this static explanation is
only the presently available scope disclosure.

## Persistence, optional intent and lifecycle

The existing pure commands and managed library adapter perform add, replace,
settings/role edits, enable/disable and removal. Admission is the only core
production change. There is no new storage version, second history or alternate
write path. Format-4 settings history and format-5 explicit role changes keep
their existing behavior, including older exact bytes and restored revisions.
Successful changed commands append one acknowledged revision. Unchanged
settings, roles and exact catalog replacement reuse the acknowledged revision.
Tier, context, workload, guest metadata and unrelated components remain intact.

Core-only structural preparation retains the two entered T6 selections when its
existing foundation and relationship prerequisites are satisfied. It does not
produce an EvaluationReport, policy action, cost estimate or readiness result.
Missing explicit catalog relationships remain existing projection findings.
No target, dataset, gate or supported runtime is filled in from a selected label.

Native forms use the original record object, managed draft identity, revision/hash
and immutable catalog snapshot. Invalid input can be corrected in place. A
changed catalog, newer saved head, deleted or foreign draft, canceled dialog or
retired Activity cannot acquire replacement authority. The bounded primitive
checkpoint retains raw input and explicit selection identity through parcel
recreation; recovery requires a fresh explicit review. A changed baseline or an
in-flight checkpoint remains read-only, and an acknowledged original write
cannot be replayed by its retained controls. Existing conflict and uncertain
acknowledgment messages still require reopen and do not assert that nothing was
committed. Generic input, choice-dialog and recovery helpers are unchanged. The
shared guidance contribution's explicit browse actions retain their original
catalog-only behavior and never submit the new forms or change a saved selection.

## Validation and remaining acceptance

The original test-only checkpoint `11ff2b8561238e27ea464d9494c614e93b3e7237`
compiled and actually executed all nine new core cases against the earlier
admission rules, producing nine failures and zero errors or skips. Its source,
original XML, build log and failure receipt are retained. Corrected core
`39289961843d7acf00161ef7b223adc855357e8a` then executed 2,952 unique cases in
72 original XML suites with zero failures, errors or skips. Those results belong
to those exact sources. Native checkpoint
`06452c4d33be0a5994187f4239b048dc0e50fa4e` actually passed 286 cases in 22 original
XML suites, unsigned assembly and lint with 14 warnings and no errors. All 19
then-committed attendance programs executed against those original reports.
Those exact results and the two independent source reviews are historical to
that checkpoint's now-retired additional persistent explanation.

The shared-guidance reconciliation consumes exact signed
`507b263cbd834c60886ff5dfdee5c2ec0df6e0cf` / tree
`391848c1892b9f587204d199a0c8957da38f825b` from #440. Its original four-failure and
one-failure attempts, the subsequent 2,937-core/270-native stage result and its
source ownership remain separately attributed. The ordinary local union
`6094fb14d1220c03d647f30ff59dca9a4adacb3a` preserves the complete nine-path
contribution before this editor removes its own duplicate heading/block. The
unchanged detailed editor explanation moves into each two-kind form. Tests now
check the single shared panel's exact saved revision and all five labelled
counts after real editor operations, all optional-tier states and recreation.
All five original guidance cases and eleven editor cases remain mandatory.
The combined source requires its own actual execution and receiving review;
neither earlier result is assigned to this later union.

The first combined checkpoint `025863ef0ec216184e2752a97b1307e29955dd05`
compiled its native production and tests, then reported two recovery-helper
assertion failures before its test worker exited 137. The incomplete run reported
173 tests, two failures and one skip; it supplies no full-suite or new assembly
qualification. The helper incorrectly treated an independently readable disk
head as acknowledged UI authority. The corrected two recovery callers explicitly
require the unavailable source message and all five unavailable counts until
reconciliation. Their newer-head, retained-input, read-only and retired-action
assertions remain intact; the shared production authority is unchanged. The
original failed log and source receipt are retained separately from the retry.

Nine dedicated core cases cover both empty inputs and all five fields, complete
enum combinations, exact numbers and proposed-field rejection; every optional
tier state; multiple roles and incident-link removal; exact catalog pins and
unchanged replacements; format-4/5 history/restore; immutable caller snapshots;
core-only projection without invented results; and managed stale/foreign/deleted
write authority. At this two-kind source checkpoint, existing unsupported-kind
tests kept every rejection assertion for the three remaining deployment,
monitoring and cost-policy kinds. The
foundation method is retitled from `retained core tier six kinds stay outside
this command subset` to `retained deployment monitoring and cost policy kinds
stay outside this command subset`. The two newly admitted kinds have their own
full positive and negative coverage.
The later [operational continuation](OPERATIONAL_SELECTIONS.md) records their
subsequent admission, dedicated cases and explicit foundation-test retitle;
neither that later source nor its execution is attributed to this checkpoint.

Eleven paused-looper Activity cases cover guidance before save or catalog
availability, both-kind explicit entry/reopen, complete invalid-input retention,
every optional-tier state, several roles and selective removal, all unchanged
edits, parcel recovery, canceled/stale controls, concurrent-head read-only
recovery, acknowledged in-flight recovery and narrow RTL layout at twice the
normal font scale. The latter checks complete text, scroll access to both ends
of the shared notice and each form explanation, and reachable input/confirmation controls. The
workflow requires all eleven actual method names and rejects absent, wrong,
duplicate, skipped, failed or errored reports while allowing future additions.
Authored tests do not establish a native pass; actual logs, original reports,
artifact hashes and any failed attempts remain bound to their execution source.

| Original #41 criterion | Delivered local scope and remaining acceptance |
| --- | --- |
| Core T6 readiness and relevant guardrail/evaluation guidance are not silently optional; communicate scope, measured conditions and limits. Specific catalog depth follows launch-scope decisions; no guarantee of safety/quality. | Core guidance persists and the five frozen settings can be entered explicitly. The accepted launch-specific readiness baseline, dynamic findings, catalog depth and named receiving review remain unresolved. |
| Every supported core build exposes the accepted T6 readiness and limitation fields even when T3-T5 are disabled. | The preview keeps its explicit unavailable-coverage and conditions disclosure with all optional tiers disabled. Complete accepted readiness fields and an integrated core builder summary still depend on the upstream/domain decisions. |
| Evaluation results identify their model/runtime/task or harness conditions where available; missing or incomparable evidence cannot appear as a general quality or safety guarantee. | Configuration never becomes a measured result. The shared panel keeps results unavailable; the forms explain comparison conditions. This slice supplies no evaluation-result renderer; comparable, incomparable and absent-evidence display fixtures and report integration remain outstanding. |

All three criteria and the four original implementation subtasks stay open.
The original core-only and comparable/incomparable/absent-fixture requirements
are retained. Canonical receiving acceptance, accepted successor contracts,
saved/shared evidence outputs, installed lifecycle and accessibility testing,
real deployment/evaluation work and release qualification need their own
reviewed scope and evidence.
