# Captured unknown-input observations

This source addendum describes local observations produced during an actual unknown evaluation. It supplies neither a complete cause nor a verified remedy.

## Captured API and vocabulary

`DraftCurrentOutcome.Completed.unknownInputObservations(ruleId)` and `SnapshotEvaluationOutcome.Evaluated.unknownInputObservations(ruleId)` return a copied, immutable list for an actual unknown finding. The producer binds it to the exact request and original result. Bare, copied, foreign or reissued results cannot borrow these observations; recorded or imported report-only findings remain generic.

| Observation | Limited meaning |
| --- | --- |
| `NO_MATCHING_SELECTION` | No enabled selection matched the evaluated local read. |
| `AMBIGUOUS_SELECTION` | More than one enabled selection matched that read. |
| `MISSING_CAPTURED_VALUE` | A referenced value was absent from the captured inputs. |
| `UNRESOLVABLE_REFERENCE` | An evaluated reference could not be traversed. |
| `CATALOG_ENTRY_UNAVAILABLE` | A referenced catalog entry was unavailable. |
| `OPERAND_DOMAIN_UNAVAILABLE` | A supplied operand was outside the supported local domain. |
| `METRIC_NOT_SUPPLIED` | The local profile does not supply the evaluated metric. |

Only reached branches contribute. Present JSON null remains distinct from an absent value. A determined `exists` result carries no unknown observation. Boolean evaluation stays eager: a known final result masks tentative observations, while an unknown result retains only contributing categories. Evaluation order, work charging, verdicts and report construction are unchanged. A rejected or cancelled attempt exposes no partial observations.

The seven categories are deduplicated and bounded. Current detail and fresh retained-result descriptions use fixed text; no raw input path or value is exposed by this metadata. Recorded message/remediation text, full-text paging, selection authority, alternative verdicts and explicit replacement remain unchanged. The existing alternative inspector does not acquire these observations merely by displaying equal rule IDs.

## Alternative inspector observations

The existing inspector still evaluates Current and Candidate once each, in that order. Each pass now captures observations under the exact inspection request, its fixed `DraftAlternativeSide`, internally selected context and original produced findings. Metadata is attached only after the complete inspection passes its existing validation and cancellation checks.

`DraftAlternativeOutcome.Completed.unknownPhase(side, row)` and `unknownInputObservations(side, row)` require a row owned by that completed result. Copied findings, relabelled rows, swapped sides, another evaluation or a reissued request cannot borrow the trace. Bare outcomes remain generic, and a failed or cancelled inspection publishes neither side's partial observations.

The browser adds separately labelled local phase/input fields for actual unknown Current and Candidate findings. Slot-empty and candidate-reason findings remain generic. Original field values, relative raw-field order and value-part boundaries are preserved. Verdicts, filters, ranking, explicit choice/replacement and retired-window authority are unchanged; these observations do not establish a feasible remedy.

## Retained evaluator descriptor

The current `RetainedEvaluationProfile.EVALUATOR_SHA256` is:

```text
3b9716f552df7d5be0ef49ba0adac207eaab5e986f1deb06321248bf298e35d0
```

It identifies the current `FrozenDiagnosticEvaluator.kt` bytes. The retained request digest includes this descriptor, so newly prepared request identities reflect the source change. The profile ID, frozen schemas/reference/receiver and stored report bytes are unchanged; no stored-report migration is introduced.

## Current source method counts

| Test class | Methods |
| --- | ---: |
| `UnknownInputObservationTest` | 11 |
| `CurrentDraftFindingOutcomeActivityTest` | 6 |
| `CurrentDraftProvisionalGuidanceActivityTest` | 8 |
| `SnapshotUnknownPhaseActivityTest` | 5 |
| `DraftAlternativeInspectionTest` | 17 |
| `AlternativeInspectionActivityTest` | 15 |

These are source method counts, not execution results. For the current implementation, this addendum supersedes only the descriptive evaluator hash and affected method counts in [Current finding outcomes](CURRENT_FINDING_OUTCOMES.md) and [Retained snapshot evaluation](RETAINED_SNAPSHOT_EVALUATION.md). Their other scope and authority boundaries remain unchanged.
