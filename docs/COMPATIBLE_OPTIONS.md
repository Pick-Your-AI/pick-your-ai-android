# Local compatible-options boundary

This is a bounded Core contribution to
[Android #96](https://github.com/Pick-Your-AI/pick-your-ai-android/issues/96).
It evaluates explicit candidate selections against one captured build using the
existing frozen diagnostic interpreter. It does not add a picker to the Activity,
alter frozen v1, admit a historical report, or complete the work package.

## Inputs and comparison

[`CompatibleOptionsRequest`](../core/src/main/kotlin/ai/pickyour/core/evaluation/CompatibleOptions.kt)
captures full Build, CatalogSnapshot and Ruleset bytes, an explicit kind, role,
selection ID and add/replace intent, and up to 32 complete candidate Selection
documents. Candidate settings are supplied by the caller, never inferred from a
catalog item. Each candidate must be enabled and match that exact slot plus an
item's kind, ID and version in the captured catalog. Duplicate candidates,
ambiguous occupied slots, missing replacement IDs, mixed locks and malformed
input return a typed unavailable result before a verdict is produced.

All three inputs use the existing strict bounded JSON reader and frozen schema
validator. Each document has a 262,144-byte and 20,000-token bound; existing depth,
Unicode, duplicate-key, trailing-content and other parser limits still apply.
The immutable request's digest includes original bytes, candidate settings and
order, and explicit slot intent. Its binding also exposes the build ID and full
integer revision, catalog ID/revision, ruleset ID/version, graph ID/revision and
pricebook ID. Large revisions are never narrowed to a machine-sized counter.
Input mutation after capture cannot change the request or its result.

The baseline removes only the specified replacement selection and its incident
links. It leaves every other role, selection, setting, order and input lock
unchanged. An add request requires the specified slot and new ID to be empty;
its baseline is the original build. Each hypothetical replaces the original
selection at its original position, preserving its ID and existing links, or
appends the explicit new selection. No other selection becomes primary.

Both baseline and hypothetical builds must still satisfy frozen Build admission.
In particular, frozen `selections.minItems = 2` is unchanged. Removing one of two
selections returns `BASELINE_UNAVAILABLE`; an empty private draft cannot be
disguised as a valid frozen Build. The requested whole empty-build picker outcome
still needs an accepted incomplete-build evaluation route. This API returns an
unavailable state instead of a made-up compatibility result.

## Candidate verdicts and rule outcomes

The unchanged `FrozenDiagnosticEvaluator` evaluates the full original ruleset
against the baseline and each hypothetical. The wrapper compares findings only
for rules whose `affected_kinds` include the requested kind, following the
accepted reference's candidate-attribution algorithm:

| Rule outcome | Comparison rank |
| --- | ---: |
| pass or skipped | 0 |
| unknown | 1 |
| fail | 2 |

Only a higher rank than that rule's baseline is attributed to the candidate.
A worsened failure produces **incompatible**. Otherwise a worsened unknown
produces **unknown**. Otherwise the candidate is **compatible** under this local
diagnostic comparison. Each attributed finding retains its rule ID, separate
baseline and candidate outcomes, severity, explanation, remediation and evidence.
An inherited foundation failure does not make every alternative incompatible.
A compatible candidate does not certify that the whole build passes or that
unexamined roles and external evidence are compatible.

Completed results expose immutable buckets and counts. `visibleOptions()` shows
only compatible entries by default. A caller can explicitly include either
incompatible or unknown choices for inspection; the full result and its reasons
remain available. An empty candidate page contains zero options and zero counts.

Missing external cost and resource evidence stays unavailable to the frozen
interpreter. No metric overrides or dependency-graph execution are introduced.
Graph and pricebook locks are bound as identities, not claims that graph or price
artifacts were executed. Unsupported numeric/operator behavior and exhausted
work budgets produce an unavailable **whole attempt**, never a partially
compatible batch. Reconstruction preserves floating node types, including an
integer-valued exponent such as `1e0`; it cannot silently widen the interpreter's
supported numeric profile. Each attempt makes at most 33 bounded interpreter
calls and must run off the UI thread. Interruption is checked before and between
calls; logical cancellation does not forcibly terminate a running interpreter.

## Currentness and editing

[`CompatibleOptionsCoordinator`](../core/src/main/kotlin/ai/pickyour/core/evaluation/CompatibleOptionsCoordinator.kt)
uses the existing `AnalysisCoordinator` ticket mechanism. The adapter must bind
the newly captured request on an editor/context change, and clear the coordinator
when new inputs are incomplete, invalid, detached or otherwise unavailable.
Binding first retires the previous visible result and any in-flight work.

`start(request)` succeeds only for that same currently bound object. A queued
start for an older editor cannot rebind the current editor. Every retry receives
a fresh opaque ticket. Completion requires both the current ticket and an
outcome produced for that same request object; matching field values or digest
alone are insufficient. Changing away and back, cancellation and clearing also
reject late completion. `isCurrent(outcome)` is a point-in-time check before
display/use, not a durable receipt or permission to write a selection.

The API contains no scheduler, Activity observer, add command, disk write,
automatic selection, network or archive publication. UI integration must combine
this result binding with the existing current draft/baseline/catalog CAS and
explicit editing authority. Retaining a compatible result does not bypass them.

## Validation and remaining work

`CompatibleOptionsTest` executes real strict preparation and the frozen
interpreter on synthetic variants of unchanged bundled examples. It covers the
primary/secondary conflict counterexample and a promotion control, inherited
foundation failures, all three candidate buckets with rule explanations,
secondary-scoped rules, exact add/replacement preservation, unavailable minimum
selection count, malformed/foreign inputs, immutable byte capture, large
revisions, floating operands, whole-batch work/failure handling, cancellation,
same-value foreign results, retry, ABA, delayed starts, and build/catalog/rules/
role edits while results are in flight. The ordinary Core check discovers these
tests; receiving qualification must inspect their actual XML identities.

The separate
[accepted website source finding](https://github.com/Pick-Your-AI/pick-your-ai-android/issues/96#issuecomment-6260070257)
retains its exact original TypeScript execution and six validated Build inputs.
The JVM tests reproduce its causal pattern using Android fixtures; they are not
the website Vitest suite or an Android installed-device replay. No website source
is changed by this contribution.

Android #96 remains open for the real picker/filter presentation and accessible
inspection flow, add-action integration, accepted incomplete-build handling,
native prerequisite and affected-consumer review, installed/device evidence and
the original canonical `07.02.md` acceptance record. This document moves no
acceptance path and makes no new shared-service or v1 compatibility decision.
