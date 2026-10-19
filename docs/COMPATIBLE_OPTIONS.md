# Local compatible options and native picker

This is a bounded Core and native-preview contribution to
[Android #96](https://github.com/Pick-Your-AI/pick-your-ai-android/issues/96).
It compares explicit candidates using the existing frozen diagnostic rule loop.
The original frozen-Build API from #527, delivered through #507, remains available.
A separate typed private-draft route now supplies the actual native add and
replacement picker. Neither route alters frozen v1, admits a historical report,
or establishes whole-work-package or installed-device acceptance.

## Frozen-Build inputs and comparison

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
optional pricebook ID. Schema-permitted omission remains typed absence in the
binding; explicit JSON null still fails frozen validation. The coordinator uses
private presence/absence tags only for currentness, without adding a Build lock,
pricebook document or cost evidence. Large revisions are never narrowed to a
machine-sized counter. Input mutation after capture cannot change the request or
its result.

The baseline removes only the specified replacement selection and its incident
links. It leaves every other role, selection, setting, order and input lock
unchanged. An add request requires the specified slot and new ID to be empty;
its baseline is the original build. Each hypothetical replaces the original
selection at its original position, preserving its ID and existing links, or
appends the explicit new selection. No other selection becomes primary.

Both baseline and hypothetical builds must still satisfy frozen Build admission.
In particular, frozen `selections.minItems = 2` is unchanged. Removing one of two
selections returns `BASELINE_UNAVAILABLE`; an empty private draft cannot be
disguised as a valid frozen Build. This original API still returns an unavailable
state in that case. The separate typed private-draft route below does not pass
an incomplete local context through frozen Build or archived-report admission.

## Candidate verdicts and rule outcomes

The same `FrozenDiagnosticEvaluator` rule loop evaluates the full original ruleset
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

The original Core API contains no scheduler, Activity observer, add command, disk write,
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

The original #527 source, #507 reviewed integration, actual receiving run and
distinct post-main execution retain their original identities and limitations.
Later local or receiving runs do not relabel those historical results.

## Stored incomplete drafts

[`DraftCompatibleOptionsRequest`](../core/src/main/kotlin/ai/pickyour/core/evaluation/DraftCompatibleOptions.kt)
accepts an actual immutable `StoredDraft`, explicit original catalog and ruleset
bytes with their ID/version/hash pins, the exact slot, and complete candidate
selections. Its private profile is
`local-stored-draft-slot-empty-diagnostics/1`. It is a diagnostic view of entered
typed editing fields, not a new shared Build format or a projected archive.

The context contains the existing `DraftSelections` selections/links and only
non-null `DraftWorkload` fields. Region, currency, data classification, decimal
budget text and mathematical workload counters retain their original types and
values. Unanswered fields stay absent. An empty private draft has known empty
selections and links. It does not acquire a Build ID, shared revision, owner,
status, visibility, policy, catalog lock, graph lock, pricebook lock or timestamp.
Opaque objective/context text, legacy primary-model input, optional tier intent,
metadata and history remain retained-only groups exposed explicitly by the
request. No free text is silently interpreted as v1 policy or external evidence.

Optional activation is an availability precondition, not a new rule or shared
activation policy. Any `UNKNOWN` tier withholds the whole batch. An enabled
recorded selection, or a proposed enabled candidate, in an explicitly `DISABLED`
tier also withholds it, using the unchanged frozen component registry's kind/tier
mapping. An `ENABLED` tier with an empty slot remains inspectable so the user can
fill it. Disabled selections do not silently activate a tier. The picker exposes
the reason and requires explicit saved activation changes before recalculation;
it never changes the activation itself. Complete archival projection retains
its existing stricter admission requirements.

The request digest binds its profile, private store identity, local revision and
whole stored-source hash, local format, all original artifact bytes and pins,
exact kind/role/selection/replacement, baseline context, and candidate bytes/order.
A local store counter remains a local counter. It cannot impersonate a shared
Build revision. Same-content drafts in different stores have different bindings;
same-byte recaptures and retries still require different opaque attempt authority.

The baseline removes only the exact replacement selection and its incident links.
Every other selection retains its original ID, role, settings, enabled state and
position. A secondary selection never becomes the primary when the primary slot
is empty. Each hypothetical preserves the original replacement position and
links, or adds the explicitly requested new ID. `DraftSelections` continues to
enforce duplicate IDs, occupied roles, link endpoints and active cardinality.

`FrozenDiagnosticEvaluator` now factors its existing rule loop into a shared
internal findings operation. The private typed caller returns immutable local
findings only. The frozen caller still requires complete frozen inputs, constructs
the same report metadata/readiness/counts and re-admits the full report through
`ReferenceReportAdapter`. The archive reader and `DraftSnapshotProjection` remain
unchanged. The private context still fails their full Build/report admission.

Because the Kotlin interpreter source changed, the retained-snapshot profile's
explicit interpreter SHA is updated and checked by its existing input-pin gate.
The unchanged frozen import, Python reference, original 26 full diagnostic goldens,
17 configuration goldens and retained admission tests remain compatibility checks.
Old source hashes and executions are historical records, not current-source pins.

## Native form and picker flow

[`CompatibleOptionsPicker`](../preview/src/main/kotlin/ai/pickyour/android/preview/CompatibleOptionsPicker.kt)
is called by the actual `PreviewActivity` and existing `SelectionEditSection`.
The user enters a role, settings and enabled state in the existing add form,
then selects **Compare choices for this slot**. A disabled proposal stays
unresolved until explicitly enabled. Existing enabled selections have the same
action in their management menu; replacement preserves their exact role and ID.
The picker does not invent settings or choose a role for the user.
The Add form places comparison inside its scrolling content, preserving the
original two fixed dialog actions at narrow widths and large text sizes.

The private catalog container retains a defensive copy of the exact bytes admitted
by its existing reader. Each calculation binds that container and its hash to the
current browser and canonical selection snapshot. The bundled ruleset is read
with a strict byte limit on the worker and admitted through the typed Core bridge.
The preview acquires no direct JSON-library dependency or new network source.
The hypothetical replacement and typed candidate bridge use the existing bounded
numeric-type-preserving encoder; an integral-valued exponent cannot become an
integer-supported rule input merely by reconstruction.

Candidates use the existing catalog order and ten-item page size. Each page is a
bounded complete attempt, with counts explicitly labeled as counts for that page.
Previous/next controls preserve the main browser's search, sort, tier, kind and
page state. A recalculation clears every old row and count before showing the
unresolved message; no earlier result remains selectable while the new page runs.

Only compatible choices are initially visible. Independent checkboxes expose
incompatible and unknown choices. Every attributed reason has a local rule button
that displays the actual rule ID, separate before/candidate outcomes, severity,
code, explanation, suggested action and selection IDs. Existing findings from
the empty-slot baseline are separately inspectable, including an inherited
required-foundation failure. A compatible label therefore does not imply a
complete valid draft, deployment permission, known cost or supplied resource
evidence. The bundled fixture and omitted input limits stay visible in the picker.

Using a choice requires a current explicit confirmation. Add returns through the
original form's exact selection ID and captured settings; replacement returns to
the original explicit replacement confirmation. Both use the existing Activity
callback, current snapshot check and canonical local store compare-and-swap.
The candidate result is revoked before this one-use callback. Neither a reason
inspection, a filter, a declined confirmation nor a retained compatible result
can acknowledge a save or bypass an actual newer stored revision.

## Native currentness and recovery

The Activity captures the exact saved record object, current draft ID, storage
authority, pending form fields/context, workload-input object, catalog container
and current catalog hash. The picker also captures the exact component-form
epoch, slot and immutable settings. It checks those inputs before presentation,
after preparation, on result delivery, for rule inspections and immediately
before use. The Core coordinator additionally requires the exact request object
and opaque attempt ticket; a same-value foreign result is insufficient.

An edit followed by a revert retires the old session. Changing stored records,
starting a storage operation, catalog reload/replacement, opening a competing
component form, closing the origin, backgrounding or destruction revokes its
callbacks. Each page has its own cancellation token, so queued retired work can
stop before either preparation or evaluation. A running bounded calculation may
finish, but its old result cannot return to a new session. Dispatch failure,
invalid input, missing inputs, unsupported operations and work exhaustion expose
an unavailable whole attempt and retain an explicit recalculation path.

The picker is transient. It is never saved as a compatibility receipt or as
authority to mutate after recreation. The existing component-form recovery
checkpoint keeps pending user input and selected item identity; it still requires
the original draft/baseline/catalog checks. No recovery dialog or canonical
storage policy is replaced by this contribution.

## Additional validation and remaining acceptance

`CompatibleOptionsAbsentLockCausalReplayTest` validates all four captured frozen
documents before preparation. Its present, omitted and explicit-null controls
exercise real candidate evaluation and currentness delivery, preserve caller
bytes and prove that omitting the optional lock does not create a pricebook. The
normal Core verification requires these three cases to execute and pass.

`DraftCompatibleOptionsTest` uses actual private stores and full frozen fixtures
with explicitly synthetic counterexamples. It covers blank/single-selection
drafts, all three candidate buckets, independent new conflicts, no primary
promotion, exact secondary replacement, absent shared fields, exact decimals,
complete findings equality with the frozen interpreter, raw floating operands,
wrong artifact pins, cardinality, bounded work, immutable capture, same-hash
different stores, real saved revisions, retry/ABA/foreign attempts, typed native
bridges, all three optional tiers' actual saved activation/contradiction cases,
and actual canonical command conflicts.

`CompatibleOptionsActivityTest` drives the real Activity, native form/picker,
worker, bundled rule interpreter and canonical private store. It checks actual
blank-draft add, separately visible unknown/incompatible reasons, secondary
replacement, queued role/settings ABA, pending draft edit/revert, a newer durable
store head, catalog reload, pause/recreation, paged recalculation, invalid and
disabled proposals, explicit saved optional activation, and narrow RTL/large-text scrolling targets. Schema-admitted
catalog variants in these tests are synthetic inputs, not a live catalog or a
new rule policy. Original failed development runs remain separately attributed.

Android #96 remains open for exact current-source receiving evidence, native
prerequisite and affected-consumer acceptance, installed/device qualification and
the original canonical `07.02.md` acceptance record. Its website record path is
not relocated by this code. This document makes no new shared-service or v1
compatibility decision and claims no whole-issue closure.
