# Dependency invalidation prototype

This pure Kotlin slice partially addresses Android [#100](https://github.com/Pick-Your-AI/pick-your-ai-android/issues/100)
and [#32](https://github.com/Pick-Your-AI/pick-your-ai-android/issues/32). It identifies
kinds and selection identities requiring rechecking. It does not evaluate
compatibility, persist changes, clear user choices, or rewrite historical reports.

## Inputs and semantics

The authority is the frozen [dependency contract](../contracts/v1/docs/DEPENDENCIES.md)
and graph schema. The website's
[invalidation reference](https://github.com/Pick-Your-AI/pickyour.ai/blob/dc66a9e269917ab7241c7d08fd0e7f2a2f039872/src/domain/invalidation.ts)
and [activation reference](https://github.com/Pick-Your-AI/pickyour.ai/blob/dc66a9e269917ab7241c7d08fd0e7f2a2f039872/src/domain/activation.ts)
were inspected on 2026-10-18. Its algorithm uses direct adjacency, not transitive
closure. Both ends of each relevant edge participate regardless of relationship
direction or category. Full ruleset evaluation remains the fallback when changed
kinds cannot be completely identified.

`DependencyInvalidation.affectedByChange` accepts typed projections of an already
schema/version-validated frozen graph, explicit changed kinds, and before/after
contexts. Wire decoding and pinned catalog resolution belong to the caller. A
missing or unresolved deployment mode or production-target state must be supplied
as `null`; it must not be guessed. Contexts copy selections, derive enabled kinds,
and preserve disabled selections for identity reporting. Fine-tuning activation
uses the frozen registry's four T5 kinds. No alternate wire schema is introduced.

An edge participates if it touches a changed kind and activation is not proven
false in both contexts. Disabling RAG, removing a selected endpoint, switching
from local to API mode, or losing a production-target fact therefore cannot hide
relationships active in the prior state. Every edge is considered once, so cycles
and self-loops cannot create recursive work. Results are sorted and deduplicated:

- `changedKinds`: caller's change set.
- `affectedKinds`: direct neighbours excluding changed kinds.
- `selectionIdsToRecheck`: all identities in either context belonging to changed
  or affected kinds, including removed and disabled selections.
- `viaEdgeIds`: explanation identities for participating edges.

The caller must supply every changed kind, including kind replacements, and bind
the resulting recheck work to the complete revision/evidence identity. These
results are a recheck plan, not a saved-state acknowledgement or a current report.
Unknown activation causes conservative invalidation, not a compatibility pass.

## Acceptance coverage

| Live #100 criterion | Implemented evidence | Remaining gap |
| --- | --- | --- |
| Use before/after dependency activation, conservative unknown edges, optional disable and cyclic relationships to reevaluate affected results. | Pure recheck plan and tests cover both contexts, unknown mode/production, disabled RAG and cycles. | Evaluator and Android UI integration; receiving review. |
| A model, accelerator or deployment-mode edit invalidates the required forward and reverse dependent results under the accepted graph. | Bidirectional direct adjacency matches the pinned website algorithm; frozen example graph checks exact model neighbours. | Application must supply the complete changed-kind set and schedule bound reevaluation. |
| Disabling a tier invalidates edges active before the change, and unknown activation does not suppress necessary reevaluation. | Before-active/after-inactive edge handling retains removed and disabled selection IDs. | Durable editing integration and visible rechecking state. |
| Cyclic dependency input terminates with each affected identity processed finitely and no duplicate unbounded work. | Single edge scan; cycle/self-loop/duplicate tests check deterministic unique outputs. | Installed-device observation and receiving acceptance. |

For #32, these tests add graph/unknown-state coverage only. They do not establish
all domain invariant coverage, full cross-client runtime parity, release evidence,
or issue completion. The canonical acceptance records remain
`docs/delivery/android/work-packages/09.02.md` and `15.01.md` in the website repository;
this document does not move them.

## Validation

On 2026-10-18, JDK 21.0.12.1+1 / Gradle 8.11.1:
`./gradlew --no-daemon :core:test --tests '*Invalidation*'`
passed: **6 tests, 0 failures, 0 errors**, build successful in 23 seconds.
The local invocation used a temporary JDK/Gradle cache and the environment’s existing
trust store; no machine-specific settings or credentials are committed.

Focused JVM tests cover direct versus transitive neighbours, inverse dependencies,
mode transitions, all activation predicates, optional disable, removed identities,
context snapshot isolation, cycles, self-loops, duplicate edges, empty change sets,
input-order determinism, and exact frozen-example neighbours. The test expectation
is hand-checked against the pinned reference; the TypeScript suite was not run.
No frozen contract files are modified. Android/emulator/physical-device behavior
and integration with persistence, evaluator cancellation and UI remain unexecuted.
