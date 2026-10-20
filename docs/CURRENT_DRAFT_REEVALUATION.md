# Current saved-draft reevaluation

This Android implementation contributes to [#100](https://github.com/Pick-Your-AI/pick-your-ai-android/issues/100).
It connects the accepted [direct dependency helper](INVALIDATION.md) to the actual
acknowledged local editor and its existing private diagnostic rule loop. The
[stored-draft compatible-options profile](COMPATIBLE_OPTIONS.md) and
[retained snapshot evaluator](RETAINED_SNAPSHOT_EVALUATION.md) keep their own request
and report authority. The accepted implementation baseline is
`0f5fea42c5cd149472269da2e47559a24689ba71`; source preparation is not execution evidence.

## Source and input authority

`PreviewActivity` supplies its actual acknowledged `PreviewStoredDraft`, exact
loaded catalog, dependency graph and rules bytes, and its current storage/input
and lifecycle authority. `DraftCurrentInputs` copies all three byte arrays before
parsing, validates the unchanged frozen schemas and applies the existing private
rule reader's 262,144-byte/20,000-token limits. The graph decoder preserves every
supplied edge, including duplicate IDs, cycles and self edges. Catalog identities
and rules must be unambiguous under the existing interpreter profile.

The request binds the private store identity, local revision/hash and format,
all three original artifact hashes, and the exact catalog/graph/rules IDs and
versions. The private store path is included only in the digest; it is not shown
or logged. A local revision is never substituted for a shared Build revision.
Schema admission does not establish publisher rights, current vendor evidence,
authenticity or a production catalog. The installed preview continues to load
explicitly synthetic bundled inputs; no real-data provider is added.

The before basis is the previous **acknowledged** source, even when its work was
superseded or withheld. It is not the last successful result. Each before/after
context retains its own catalog and graph binding when those inputs are available.
A temporary input-read gap for the same acknowledged record retains its previous
bound inputs privately. A newly acknowledged record without admitted inputs stays
unbound and requires a conservative full check; it is not assigned an earlier
catalog. A different private store starts a fresh basis and cannot display another draft's affected IDs. Unconfirmed,
pending and absent sources expose no old affected set as current.

The Activity still owns canonical autosave, save acknowledgements, conflict and
uncertain-save handling, library selection, restoration and reconciliation. The
new session performs no write, migration, promotion or editor reload. Bundle
restoration does not restore a result or confer current authority.

## Dependency planning and finite work

Selection addition/removal/configuration/enable changes include both old and new
kinds and IDs. Link changes include their actual endpoints. Optional-tier intent
changes include the frozen registry's tier kinds even when the canonical
`setActivation` command preserves every selection byte. Deployment mode is
resolved from a unique enabled primary selection and the matching item/version
in that basis's catalog; absent or ambiguous mode stays unknown. Production is
derived only from the frozen deployment-target environment field, with absent
or ambiguous targets unknown. Free text supplies neither predicate.

For a complete same-input selection transition, the existing helper selects
direct forward/reverse neighbours and changed activation endpoints. It does not
compute transitive closure or reinterpret relationship labels as compatibility
requirements. Unknown predicates remain conservative. Disabled and removed IDs
remain in the sorted, deduplicated rechecking set.

First load, a new store, graph/catalog/rules replacement, unrepresented source or
workload changes, unresolved optional intent, or unavailable predecessor inputs
uses conservative full invalidation. Graph replacement includes old edges from
their original basis, so a removed edge is not silently forgotten. An affected
set is an invalidation explanation, not a graph-derived verdict or evidence of
execution. An empty selection-ID list is only that observation.

Each current attempt that passes the explicit optional-intent guard invokes
`FrozenDiagnosticEvaluator.evaluateDraft` once for the complete bounded rule set.
Unresolved or contradictory optional intent returns Withheld before that call;
a Rechecking state alone is not evidence that any rule ran. The graph does not
prune the eligible rule loop. The
existing finite interpreter work budget remains unchanged. Repeated rendering
of the same captured source/input/owner does not enqueue another attempt. A
queued attempt starts once; superseded work can terminate without reading or
evaluating. Cycles cannot recursively schedule more evaluations.

## Currentness and the native view

Main-form changes revoke current diagnostics and the existing compatible picker
synchronously, before any save acknowledgement. Component editing uses the
existing form retirement hook. Storage operations, catalog refresh start, pause,
destruction, explicit retry and source replacement revoke their old opaque
attempts. Returning to equal bytes after an edit, reload or recreation creates
fresh authority rather than reviving a prior result.

The read-only session reads the canonical durable head before evaluation and
again after the complete work, immediately before admitting a result. Both reads
must match the captured private store/revision/hash/format. Publication also
requires the same live one-shot attempt and an outcome belonging to that exact
request. The Activity rechecks saved-form, selected-draft, storage, lifecycle and
catalog/input instance/hash authority before rendering. These are boundary reads
and event guards, not a claim to continuously monitor arbitrary external disk
writes or atomically lock disk state together with a future UI frame.

The view exposes Rechecking before completion, with deterministic affected IDs.
A confirmed result shows pass/fail/unknown/skipped counts and independent severity
on its paged observations. It makes no whole-draft compatible, readiness or deploy
badge. A failed read, changed head, unsupported operation, input failure or work
failure withholds current results and requires a deliberate retry or a new
source event. Retry rechecks its enabled and non-running state at execution.

Opening History, catalog details or revision comparison retires the findings
page and its retained row, paging and Back callbacks. Opening findings retires
older catalog and revision inspections, plus the current History picker, record
detail or restore confirmation. Retained History controls cannot reopen a child,
copy fields or commit a restore after findings opens. This changes presentation
ownership only:
the acknowledged analysis, binding and saved bytes remain available, and a fresh
inspection can be opened after closing the newer view.

Explicit entry into saved-snapshot inspection, recorded resource details, fixed
Findings, Help or Diagnostics shares this window retirement. Retained sibling
rows, Back, retry and external-action controls cannot replace the newer view.
Retiring a snapshot inventory also clears its request identity before a queued
result can arrive. Resource and fixed-Findings window retirement preserves the
accepted inline record or report and its read state; normal parent-to-child
navigation remains available within a fresh inspection.

Automatic storage or catalog refresh keeps the existing current/revision cleanup
separate from explicit presentation entry. In particular, an open Help report
keeps the catalog identity captured when it opened; only a fresh Help request
captures a completed catalog load. Opening an admitted catalog comparison retires
sibling inspection windows while preserving the accepted History route. A live
History child can then retire that comparison through its existing navigation.
Pause and destruction retain their separate lifecycle retirement, including
fixed Findings and exact catalog-pin recovery.

The existing private context represents typed selections, links and workload
fields. Free-text objective/context, opaque primary-model choice, optional intent,
metadata and history are bound for invalidation but are not silently interpreted
as new frozen policy. UNKNOWN optional intent, or an enabled optional component
inside DISABLED intent, withholds evaluation without rewriting selection.enabled.
The affected set remains visible for that acknowledged transition.

## Existing producer and history limits

Current cost and resource estimates remain **unavailable**: no qualified current
producer exists in this path. All real current diagnostic and compatible-picker
authority is retired on relevant changes; no fake quote, capacity result or cache
success is added to satisfy an invalidation label. The diagnostic rule loop keeps
missing external metrics unknown. Future qualified producer arrival retains its
own #102/#115 binding and receiving requirements.

Recorded cost/resource inspections, fixture findings and explicitly selected
retained snapshots remain read-only historical records. Their identities and
bytes do not change when the current editor changes, and they cannot supply
missing current metrics or evidence. Rule messages/guidance are observations from
the supplied rules, not newly qualified feasible remediation or evidence under
#101/#114. The inspection uses the existing bounded literal renderer and pages
at most ten rows; raw private context/settings are not displayed.

## Literal #100 criteria and review evidence

| Original criterion | Concrete implementation and meaningful proof | Limits and receiving boundary |
| --- | --- | --- |
| Before/after activation, conservative unknowns, optional disable and cycles reevaluate affected results | Actual stored sources and paired artifact inputs feed the accepted helper; core and Activity cases observe real saved edits and new Rechecking/current/withheld transitions | Local synthetic diagnostic profile; no invented production facts or new graph policy |
| Model, accelerator or deployment-mode edits invalidate required forward/reverse results | Canonical selection-command cases retain disabled/removed identities; actual Activity model configuration and deployment-mode replacement expose the remote accelerator/model edge before the new evaluation completes | Full rule reevaluation is a conservative fallback; affected sets do not confer a compatibility verdict |
| Tier disable retains previously active edges and unknown does not suppress reevaluation | Real `setActivation` DISABLED/UNKNOWN changes the saved revision while preserving component bytes; affected tier/selection identities appear before an explicit unresolved-intent result | Contradictory intent is withheld, not silently projected into different enabled flags |
| Cycles terminate with finite identity processing and no duplicate unbounded work | Duplicate/cycle/self-edge graph fixtures preserve input edges and finite sorted IDs; one-shot session tests check duplicate dispatch, repeated rendering, A→B→C reversal, retry and lifecycle retirement | Bounded local work, not provider timing, installed responsiveness or device qualification |

The focused tests also check actual durable mutation between the two reads,
pre/post read failures and recovery, foreign outcomes, equal revision/hash in
different stores, caller input-byte mutation, changed-artifact A→B→A, existing
picker retirement, failed save/reopen and immutable historical bytes. The new
workflow guards require every named new core and simulated Activity case, with
exact class/name attendance and zero failed/error/skipped records. Test source and
expected attendance are not passing execution; exact runs and source hashes are
reported in the PR and retained original receipts.

The nine required `SiblingInspectionActivityTest` methods exercise actual current
and archived findings, retained callback replay, late inventory delivery, inline
record preservation, reviewed text/public-destination actions, explicit catalog
comparison and Privacy entry. They keep the acknowledged analysis, binding,
baseline and private draft/archive bytes unchanged. The original eight callback
regressions, later comparison/History control and intermediate failed executions
retain their distinct source-bound receipts; their expectations are not a claim
of configured receiving or installed success.

The unchanged-source baseline has no current caller of `DependencyInvalidation`
and no saved-draft check receiver. This is implementation of that missing consumer,
not a claim that its absent UI previously returned stale reports. No compile-error
baseline is used as behavioral evidence.

Use the existing [core](CORE_FOUNDATION.md#reproduce) and
[preview](ANDROID_PREVIEW.md#reproduce) commands, including strict dependency
verification, frozen-import/reference checks, build, JVM/simulated tests and lint.
Local or simulated results remain distinct from configured hosted execution,
installed-emulator, physical-device, accessibility and release qualification.
The accepted #467 viewport/inset and installed-job behavior is preserved.

The canonical acceptance record remains `docs/delivery/android/work-packages/09.02.md`
in `Pick-Your-AI/pickyour.ai`. This engineering guide does not relocate it or close
#100. Exact-version compatibility/domain receiving review, accepted limitations
and the complete criterion mapping are required before whole-issue reconciliation.
Other source/ledger/report/producer controllers retain their existing ownership.
