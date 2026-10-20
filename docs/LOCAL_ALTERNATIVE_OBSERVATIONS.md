# Local current and alternative observations

Android #114 has a bounded local inspection entry in the existing compatible-choice picker.
After an explicitly opened picker has completed its current calculation, each visible option
offers **Inspect current and alternative findings**. Opening, paging, closing, filtering or
retrying this inspection does not select a component or write a draft. The existing separate
choice confirmation and canonical store command remain the only acceptance path.

## Captured basis

`local-stored-draft-alternative-observations/1` consumes one exact option from one completed
`local-stored-draft-slot-empty-diagnostics/1` request. Equal-looking requests, options or results
do not substitute for those captured objects. The local source revision and content hash,
catalog ID/revision/hash, ruleset ID/version/hash, slot kind/role/selection ID and framed input
digest are available in the basis view. These local identities are not shared Build identifiers,
release revisions, approval receipts or fresh remote evidence.

The inspection keeps three contexts distinct:

| Context | Meaning |
| --- | --- |
| Current | Every captured saved selection and link, with the saved typed workload values. The exact current selection in this slot can be absent. |
| Alternative | The same captured context with only the selected candidate inserted or substituted in the exact slot. Replacement retains the existing slot identity, role and links. |
| Slot empty | The original picker basis: this exact selection and its incident links are removed. Other captured selections and workload values remain. |

The current and alternative contexts each run through the unchanged bounded local evaluator.
The existing slot-empty findings and candidate reasons are retained from the original completed
picker result. At most two additional evaluations run for this one explicit inspection. There
is no background comparison across an entire catalog, new ranking formula or hidden save.

The full rule order must match the original complete slot-empty result. Duplicate rule IDs,
unexpected outcomes, missing rows, inconsistent candidate reasons, rejected operands, work
limits or cancellation withhold the entire observation result. A successful slot-empty basis
cannot substitute for a current context that the evaluator cannot inspect.

## What the pages show

Every rule remains available, including unchanged failures, unknown and skipped outcomes.
For the current, alternative and slot-empty contexts, the pages expose each complete rule ID,
outcome, severity, code, message, remediation and every selection association. Local flags state
whether the current-to-alternative outcome or any displayed finding detail changed. The original
candidate reason is included when one exists; absence of such a reason does not hide the full
findings. An outcome transition is an observation and does not establish cause, measured
improvement, whole-draft compatibility or readiness.

The contexts and both selection records are available as complete decoded local JSON. The view
does not claim to preserve original on-disk whitespace or the spelling of numbers that the
existing store has already decoded and encoded. It preserves the captured values it actually
receives, including arbitrary-size integral values, contract-decimal strings, unanswered fields,
selection settings and literal strings. No field is reconstructed through a binary floating
point display conversion.

Lists contain at most eight rows per page. Every text or JSON value can be traversed in complete
160-UTF-16-unit parts. Each part uses the existing literal renderer, including escaping controls,
directional formatting characters and split surrogate units. Empty text and absent values remain
distinct. Part controls and the entire value remain available; titles and counts do not stand in
for a silently truncated tail. Literal URIs are not linkified, resolved, fetched or launched.

## Limits kept visible

The same captured private source binds both choices, including free-text objective and context.
Those fields are not interpreted as frozen policy or evidence. Unrepresented private groups
remain with their owners: objective, context, primary-model metadata, optional-tier intent,
metadata and history. Optional activation must already satisfy the existing picker admission;
inspection never activates a tier or changes enabled state.

The interface explicitly leaves external evidence applicability, comparable cost, comparable
resources, measured quality and runtime readiness unestablished. Missing estimates are not zero
cost or sufficient capacity. This local profile emits no frozen Build, EvaluationReport,
ResourceEstimate, CostEstimate, graph, evidence-equivalence decision, deployment operation,
promotion, permission or approval. It does not reinterpret unknown as pass or skipped as a
successful measurement.

## Interaction and storage authority

The native picker retains its existing editor, acknowledged-record, artifact-container,
storage, page, lifecycle and one-use command checks. An alternative browser additionally owns
its own cancellation token and exact visible window. The parent option row has an identity
that changes on every render. Filtering, page recalculation and retry retire detached row
controls and child confirmations before exposing new controls.

Within this picker, inherited-findings windows, choice confirmations and the alternative browser
retire one another before presentation. Window replacement installs the next identity before
dismissing the previous window. Old list, Back, part, retry or confirmation callbacks cannot
reopen an inspection or adopt a result. Form edits, same-byte edit/revert cycles, catalog reload,
observed saved-source changes, pause and recreation use the existing parent invalidation path.
Worker code sees immutable captures and cancellation flags; UI delivery rechecks current parent,
request and window identity before adoption. Rejected dispatch has an explicit unavailable
explanation and an explicit retry. Cancelled or failed work supplies no partial rows.

The view identifies a captured saved revision. It does not introduce a continuously running
external-store watcher or claim that an independently changed store is already acknowledged by
the UI. The canonical command still performs its actual optimistic-concurrency check; reopening
or an observed source change retires older UI authority. Inspection does not acknowledge a save,
restart autosave, alter its deadline, append history, mutate optional intent or update private
metadata. Closing it returns to the existing separate choice workflow.

## Verification scope

The Core suite uses real private stores and the unchanged evaluator. It compares all 45 rule
triples with direct evaluations, exercises both conflict directions, keeps inherited failures
and unknowns visible, preserves secondary identity/links and exact workload values, rejects
foreign object membership and mutable outputs, and checks cancellation, unsupported current
operands, work limits and actual stale store commands.

The native suite exercises the actual Activity/form/picker path, every rule page, full finding
and context values, literal tails, detached rows, mutual child retirement, real autosave timing,
catalog reload, queued work, edit/revert, pause/recreation and the separate canonical save. A
bounded manual executor additionally establishes rejected and reversed delivery behavior with
real immutable Core requests. A 320 dp RTL test with 2x font scale and native graphics checks
complete rendered lines and part controls. These are simulated Android cases, not installed
device, physical keyboard, TalkBack or release qualification. Configured hosted and installed
lanes keep their own exact source and artifact attribution.

The initial Core test source had two incorrect fixture expectations: JSON node implementations
were compared despite equal complete JSON text, and a canonical stored integral number was
expected to remain unsupported. The corrected tests preserve exact string equality, explicitly
retain that supported integral case, and add a genuinely fractional reached operand. The
production Core source was unchanged. Original failing reports and environment dependency/TLS
preflight failures remain separate from the later 12 new plus 19 existing passing cases.

The first complete native run compiled and executed 24 cases: all 11 existing picker cases and
12 of the 13 new inspection cases passed. The RTL helper reused a mutable local line rectangle
after the scroll request translated it, then added the screen position a second time. A separate
one-case diagnostic retained the failing assertion and recorded the same 256-pixel text layout,
the translated rectangle and every ancestor viewport. The correction passes a rectangle copy to
the scroll request; all complete-line, no-ellipsis, whole-control and literal-value assertions
remain. Both original failures are retained. No production source changed for this correction;
fresh execution of the corrected source remains a distinct gate.

## Remaining #114 acceptance

All four canonical issue criteria remain open pending their full receiving decisions. This
contribution supplies the local observation and explicit-selection boundaries described above;
it does not close the broader objective/evidence, pricing, resource or runtime prerequisites.
Canonical receiving placement remains governed by the pinned mobile Android epic 09 and the
project's recorded native-profile decisions. No delivery ledger item, upstream dependency or
criterion is weakened or marked complete by this document.

## Receiving correction: shared inspection windows

Independent review [5579198517](https://github.com/Pick-Your-AI/pick-your-ai-android/pull/650#pullrequestreview-5579198517) identified a cross-inspection lifetime gap. An admitted parent could remain current while Help, Privacy, Resource or fixed Findings presented a newer window. The old alternative child still had a showing dialog and valid parent identity; its Back/Next controls or queued completion could therefore replace that sibling. The reverse entry also left other inspection callbacks live.

The receiving correction connects alternative entry to the existing shared Activity boundary. `CompatibleOptionsPicker.dismissInspection` clears only its child windows and alternative worker flags. It retains the parent request, original verdict, rows, form inputs and separate one-use choose/CAS callback. A newly admitted alternative invokes the shared entry before capturing its child and rechecks the exact row/result after that callback. Full form/catalog/storage/lifecycle dismissal keeps its previous behavior. The actual Activity supplies the existing `prepareInspection` callback and includes window-only picker retirement in `retireInspectionWindows`; it adds no competing global handler.

The new `AlternativeSiblingInspectionActivityTest` uses real native parent/editor controls, the immutable evaluator and actual local stores. Its eight cases cover both directions, retained Back/Next and row controls before and after sibling close, old Help/Diagnostics external actions, preserved Resource/Findings inline records, queued alternative delivery and a genuinely queued nonempty retained-Snapshot inventory. The inventory case inserts a second explicit worker barrier so its late callback is observed before the alternative worker can hide an intermediate replacement. Private draft/archive bytes and admitted parent identities remain checked. Fresh inspection and the separate explicit choose commit remain usable; replaying retired choose controls cannot add another revision.

The actual test-only baseline `24e8d4957c3d4eec024f5d7de8a8476c6c2f6fc9` compiled all Preview sources and executed all eight cases. All eight failed at causal window/interleaving assertions: six surviving child windows, one late alternative result replacing Resource, and one late retained inventory replacing alternative loading. Its original source, 17,293-byte log and full XML are retained separately. No later preservation assertion is claimed as executed in that failed run. All eight test methods remain unchanged in the production correction, and the original thirteen alternative and eleven picker cases remain intact. A literal attendance step requires the eight actual class-qualified methods, consistent report totals, unique identities and no failure/error/skip.

Original source-stage `7937f832f527150b00d13f47e581030ef60dc939` separately passed 6,322 Core and 660 native cases, assembly/lint/scanning and the four generic installed-emulator journeys. Those original results do not validate this later receiving correction or the shared-window interaction. Current corrected execution, any later main contribution, installed accessibility and the complete #114 criteria retain their own receiving records.

### Full-suite library observation follow-up (2026-10-20)

The corrected current source at API `0f8113cf` / signed preparation `50e32fc7` / tree `fac238f5` executed 6,364 passing Core cases across 125 original XML suites. Preview executed 704 cases across 69 suites, with 703 passes and one `ClassCastException` in the existing `LibraryDialogAuthorityTest.confirmedCreateDuplicateOpenAndDeleteKeepTheirSelectedIdentities`. Its entry helper cast a typed failed `library.list()` observation to `Available`. The original exception did not retain the reason; the record does not retroactively call that original observation `BUSY`. All 97 previously selected native identities, including all eight new sibling methods, pass in these full originals. Preview failure prevented lint, packaged-policy qualification and the installed job. The complete four original archives, 439 members and literal checkout logs remain retained; no whole Preview success is claimed for this source.

The actual library intentionally returns `BUSY` for a contending nonblocking lock. The pre-existing `readDraftAfterOwnedWork` test observer already handles this boundary for individual drafts. A separate identity-only list-observer baseline at `ec44f11f` leaves the previous direct read behavior unchanged. Its four real-lock/read controls compile and execute: the transient and persistent `BUSY` boundary assertions fail, while unchanged availability and a corrupt library return pass. The transient case records an actual `Failed(reason=BUSY)` while the Activity's queued worker owns the real library lock; that causal control remains separate from the unrecorded reason in the earlier full-suite failure.

The list observer now waits once for already queued Activity work only after an actual outer `BUSY` result. It does not advance the main looper, repeat a write, reinterpret per-entry states, replace a successful result, or hide a second `BUSY` or any other failure. The existing library-dialog assertions use that observer and explicitly report an unavailable result before their original successful-read casts. All twelve original method identities and their selected draft, durable contents, pending form and retired callback assertions remain. The four real-lock tests are byte-identical to their failing baseline, and an additive attendance guard requires all four actual cases without relaxing any existing guard. This is a test-observation correction; production library locks, Activity behavior, saving/CAS and user-visible acknowledgment are unchanged. Later corrected executions retain their own source identities.
