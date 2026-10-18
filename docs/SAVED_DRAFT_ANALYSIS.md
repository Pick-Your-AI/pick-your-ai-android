# Analysis of acknowledged local drafts

This is the local persistence and request-ordering adapter slice of
[Android #115 — 09.06](https://github.com/Pick-Your-AI/pick-your-ai-android/issues/115).
`PreviewDraftAnalysis` joins the existing
`PreviewDraftPersistence` and `AnalysisCoordinator`. It performs the real
load/save calls, requires an acknowledged durable baseline before accepting
qualified inputs, and dispatches the caller's evaluator with that exact baseline.
It introduces no frozen-v1 fields, network service, evaluator policy or alternate
draft/history store.

The accepted architecture and canonical persistence remain those described in
[the core foundation](CORE_FOUNDATION.md),
[the Android architecture decision](proposals/02-native-architecture.md) and
[the saved-draft projection](DRAFT_SNAPSHOT_PROJECTION.md).

## Durable authority and complete identity

Only an actual `PreviewDraftSave.Saved` or a fresh
`PreviewDraftLoad.Available` from this session creates a
`PreviewAnalysisSaved` receipt. Merely possessing a `PreviewStoredDraft`,
matching its revision, restoring a lifecycle bundle, or receiving an uncertain
write does not create analysis authority.

The receipt is an opaque capability. A new save or reopen revokes its predecessor,
including when an unchanged canonical save reuses the same revision and hash.
Every dispatched analysis additionally rereads the current durable head through
the original persistence adapter. A different store/revision/hash, unreadable
head, or managed-library deletion prevents evaluator dispatch and requires
explicit recovery.

`PreviewAnalysisIdentity` compares the exact canonical local store, durable
revision, source-envelope hash, and all six input keys: catalog, graph, ruleset,
pricebook, cost evidence and resource evidence. Private filesystem paths remain
inside the coordinator/identity implementation; diagnostic `toString` methods
do not render draft content or these keys. Each attempt also carries a fresh
opaque coordinator ticket, so identical identities and an A-to-B-to-A input
change cannot revive an old callback.

These are local client identities. The local revision counter and store receipt
are not a shared BuildSnapshot ID/revision or a remote persistence acknowledgment.
A remote consumer still needs the accepted projection, complete remote identity,
authorization and remote durable-write boundary.

## Consumer integration

| Step | Required caller behavior |
| --- | --- |
| Open | Create one session for the selected actual persistence adapter. Supply caller-owned worker and delivery executors, the evaluator and a change signal. Call `load()`; construction performs no IO. |
| Save | Preserve the editor's pending inputs. Call `save` with the original session receipt, or null only after an acknowledged missing load. Conflict, failure and uncertainty require explicit reopen; the adapter never retries using a newer token or guesses the next revision. |
| Qualify inputs | After the session exposes `Storage.Saved`, validate every locked source/evidence key against that receipt's immutable record using the accepted producer contracts. Call `setQualifiedInputs(binding, keys)` only for this exact receipt. |
| Evidence arrives | An accepted cost/resource producer event calls that same method with a new complete key set. A changed key automatically starts evaluation at the same saved revision; no edit or manual refresh is needed. An unchanged event is idempotent. |
| Evidence becomes unavailable | Keep unavailable cost/resource evidence null. Passing null for the whole qualified input set withdraws qualification and cancels current work. Do not manufacture placeholder source keys, estimates or a pass state. |
| Complete work | The evaluator receives the immutable saved record and complete identity. A real producer/transport adapter must validate its response against all retained inputs before completing with that identity. Mismatched identities, old attempts, duplicate callbacks and delayed cancellation cannot replace the current result. |
| Observe | The change callback is a signal to read the latest `snapshot()` on the intended UI execution context. It supplies no old captured state that could overwrite a newer view. Reports supplied as `R` must be immutable snapshots. |
| End/recover | `cancel()` cancels analysis and rejects late completion; `retry(binding)` creates a fresh attempt. `close()` retires the session and its callbacks, without stopping caller executors or undoing an in-flight durable write. A new session must reopen storage. |

A saved response delivered late is insufficient to restore authority after a
newer operation. Each storage operation has its own opaque token. A superseded
operation that has not reached the worker is skipped; a dispatched operation may
already have changed disk and its obsolete acknowledgment is ignored. Reversed
saves retain the actual store's original compare-and-swap result, even when that
means the newest pending edit needs explicit recovery.

Worker rejection before dispatch is a known storage failure. Losing result
delivery after a save is uncertain, because its durable effect may already
exist. Cancellation invalidates state before invoking the transport's best-effort
cancellation handle. A handle returned after cancellation is also cancelled.

The session does not own an editor's unsaved field buffer: the caller must retain
the captured `PreviewPendingDraft` on conflict/failure/uncertainty and present
the existing explicit reopen/recovery choices. The adapter performs no automatic
overwrite or recovery copy.

## State and historical meaning

| Storage / analysis state | Meaning |
| --- | --- |
| Idle, Loading, Missing or Saving / AwaitingInputs | No current analysis can be dispatched from this state. |
| Conflict, Failed or Uncertain / AwaitingInputs | Persistence has not established current authority. Reopen explicitly; never label the previous report as a current server result. |
| Saved / AwaitingInputs | A durable local receipt exists, but complete qualified inputs are unavailable. |
| Saved / Pending | A new complete-identity attempt is being checked/dispatched or evaluated. |
| Saved / Current | The latest accepted callback belongs to this session's exact receipt, complete identity and attempt. |
| Saved / Failed or Cancelled | The named current attempt failed or was cancelled; any previous report remains explicitly stale. |
| Closed / AwaitingInputs | The session retains no binding or report. Dispatched writes may still need reconciliation by a new session. |

The most recent successful immutable report is exposed separately as `stale`
whenever it is no longer current. This preserves its original identity and
meaning during saves, evidence replacement and failed reevaluation. It is an
in-memory reference, not a persistent report archive. The adapter never deletes,
rewrites or relabels canonical draft history, retained source pins or
[immutable snapshot archives](LOCAL_SNAPSHOT_ARCHIVE.md).

“Current” describes the selected session binding; the pre-dispatch read is not a
permanent claim that no other writer can later change storage. A consumer must
feed selection/edit/source invalidations into the session and reopen on external
changes or process recreation. This slice adds no file watcher, process-death
native journey or remote synchronization service.

## Bounded validation and remaining acceptance

`PreviewDraftAnalysisTest` uses the actual canonical local store and explicit
worker/delivery queues. It exercises durable-save ordering; reversed save/load
acknowledgments; all source/evidence dimensions; same-revision capacity replacement;
identity/hash/store separation; transport cancellation and retries; failures
before and after activation; delivery rejection; library deletion; lifecycle
retirement; and exact retained bytes across evaluation-only changes. Synthetic
immutable report strings exercise ordering without asserting compatibility or
capacity facts.

| Literal #115 criterion | Evidence supplied by this slice | Remaining acceptance |
| --- | --- | --- |
| Cancel/ignore outdated requests; saved reports retain historical meaning while new evaluation is explicitly distinguished. Bind evaluation invalidation to build ID/revision, all locked inputs, cost identity and asynchronously arriving resource-estimate identity; capacity evidence arrival must trigger reevaluation. | Local complete identity, opaque attempts/receipts, cancellation, distinct state and a preserved stale report; real-store regressions. | Accepted current-build producer wiring, governed compatibility/capacity receiving, durable historical report consumption and the native UI journey. |
| Resource evidence arriving after an initial unknown report causes a new evaluation for that same revision without a manual refresh or edit. | Explicit qualified producer update dispatches a new same-revision request; delayed and replaced capacity results run in reverse order. | Wire the accepted ResourceEstimate consumer and real qualified evidence events, preserving ranges and limitations. |
| An older response cannot overwrite a newer report after rapid edits, evidence replacement or a catalog/ruleset change. | Every key participates in response admission; stale saves, callbacks, retries and equal-value ABA transitions are rejected. | Integrated current-build UI, cross-tier invalidation and lifecycle/device receiving evidence. |
| Remote evaluation is not issued for an unsaved revision, and failure to save cannot be disguised as a current server evaluation. | Local evaluator dispatch is withheld until acknowledged storage and a matching fresh head read; failed/uncertain saves expose no current result. | If remote evaluation is selected, establish its separately authorized durable remote BuildSnapshot boundary and exact response validation. |

[Graph/invalidation #348](https://github.com/Pick-Your-AI/pick-your-ai-android/pull/348)
and [ResourceEstimate #393](https://github.com/Pick-Your-AI/pick-your-ai-android/pull/393)
retain their canonical owners. This slice does not wire fabricated current-build
inputs into the fixed-fixture preview report inspector.

The full acceptance record remains
`docs/delivery/android/work-packages/09.06.md` in
`Pick-Your-AI/pickyour.ai`. This local contract note does not relocate that
record, assign receiving reviewers, alter Project fields, or complete #115.
Exact source/test/hosted receipts and independent review belong to the scoped PR.
Local JVM results do not establish installed Android, physical-device, release
or production acceptance.
