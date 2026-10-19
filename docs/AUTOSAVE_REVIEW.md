# Captured form autosave — Android #122 continuation

This bounded source preparation adds automatic saves to the guest form for
[Android #122](https://github.com/Pick-Your-AI/pick-your-ai-android/issues/122).
Its starting point is accepted signed main
`2f1abddcc49d24ac4df9bceca59d65a59ae6ccb0`, including the Help and versioned
synthetic fixture contribution. It preserves that source, the frozen contracts,
the existing persistence engine and all prior test declarations. It is not a
complete #122 acceptance record or an installed Android result.

## Input, authority and visible state

The SDK-independent
[PreviewDraftAutosave](../core/src/main/kotlin/ai/pickyour/core/preview/PreviewDraftAutosave.kt)
holds at most one delayed request. Each request includes the local draft identity,
the exact `PreviewStoredDraft` receipt (or an established missing draft), the
editor's current storage-operation token, and complete immutable title/sample,
objective/context and parsed workload inputs. An identical refresh preserves the
deadline. Changed inputs replace the request; canceled, replaced and closed
callbacks remain inert even if a scheduler retains and invokes them.
Each scheduling attempt has its own opaque token, so reusing the same request
after cancel or pause/resume cannot revive an earlier callback or deadline.

The actual
[PreviewActivity](../preview/src/main/kotlin/ai/pickyour/android/preview/PreviewActivity.kt)
uses the main-thread Handler for the 750-millisecond delay and the existing serial
worker for `PreviewDraftPersistence.save`. It rechecks all captured authority and
inputs immediately before dispatch. Initial load of an unedited missing draft
creates no revision. Invalid workload text cancels the pending request and stays
visible. Correction schedules the complete valid form; no valid prefix or guessed
default is written.

During a form write, the text and workload editors remain available. The captured
input stays immutable; later typing changes only the current pending form. The UI
shows that newer edits are unconfirmed. After durable success, only the matching
draft/baseline/operation callback can adopt the returned receipt. If the complete
visible form matches it, the UI displays **Saved locally**. Otherwise it schedules
the newest complete form using that acknowledged receipt. Navigation, restore and
typed component commands retain their existing in-flight restrictions.

The coordinator changes no canonical storage rules. Changed successful saves
append history; exact-current unchanged saves retain the store's byte-preserving
no-op behavior. Restore remains its separate explicit new-revision command. The
managed library still verifies liveness under its transaction lock.

## Failure and lifecycle boundaries

| Boundary | Behavior |
| --- | --- |
| Known pre-write failure or history/storage limit | Keep all pending text and the prior acknowledged receipt; pause automatic retries. The existing failure message retains an explicit Save/reopen recovery path. |
| Rejected worker or scheduler | No saved acknowledgement; pause automatic attempts. A rejected worker is a known undispatched failure. |
| Conflict or uncertain write | Keep visible inputs and revoke save readiness. No automatic or manual retry can adopt a newer baseline; explicit successful reopen is required. |
| Newer typing during a write | Keep it visible and unconfirmed; only a successful matching receipt can authorize its next write. |
| Explicitly choose another draft | Retire the prior timer before storage work; hydrate the selected draft and clear the prior pending-save request. Late callbacks cannot update the new session. |
| Recreation before dispatch | Cancel the old Activity timer. Existing Bundle input/baseline reconciliation must succeed before the replacement editor can schedule the retained pending edit. |
| Recreation while writing or before UI acknowledgement | Preserve the existing reconciliation-required checkpoint. A later disk commit cannot turn the old Bundle into write authority or a saved acknowledgement. |
| Recreation after a known failed save | Preserve the automatic-retry pause as UI state; fresh disk load does not silently retry pending values. |

The two new Bundle booleans record pending scheduling intent and failure pause,
not write authority. Existing `PreviewDraftRestoration` still qualifies the actual
reloaded baseline. `onSaveInstanceState` and `onPause` do not write the draft.
Destroyed Activities retire timers and ignore results; the existing worker
shutdown and canonical uncertain-write recovery behavior remain unchanged.
An unacknowledged edit can still be lost when Android supplies no saved-instance
state. No background service, WorkManager job, remote outbox or automatic retry
loop is introduced.

Android's [saved UI state guidance](https://developer.android.com/topic/libraries/architecture/saving-states),
checked on 2026-10-18, distinguishes temporary state from persistent storage.
The Handler delay is scheduling, not durable persistence or a deadline guaranteed
across suspension. Those platform contracts do not establish actual process-kill,
power-loss, device or assistive-technology evidence.

## Focused validation source

The new
[core tests](../core/src/test/kotlin/ai/pickyour/core/preview/PreviewDraftAutosaveTest.kt)
declare eight methods. They use a controlled event clock and the real canonical
store for complete-input coalescing, unchanged bytes, retired callbacks, exact
request identity, reused-request deadline isolation, injected pre-activation failure, actual post-activation
uncertainty and scheduler rejection. The event clock is a test control, not an
Android process or filesystem simulation.

The separate
[Activity tests](../preview/src/test/kotlin/ai/pickyour/android/preview/PreviewAutosaveActivityTest.kt)
declare eleven methods without rewriting any previously accepted test body.
They exercise the actual Activity and canonical files under Robolectric's paused
main looper:

| Method focus | Required observation |
| --- | --- |
| Initial load; rapid complete edits | No unsolicited empty revision; only the latest complete form commits after the pause, including exact budget spelling. |
| Explicit Save while a timer is pending | A single durable revision; the retired timer cannot duplicate it. |
| Typing during a held worker write | Earlier captured text commits first; newer text stays visible and unsaved until its separate matching commit. |
| Invalid workload then correction | No automatic write of invalid text or a valid prefix; correction commits the entire form. |
| Real library-lock failure and recreation | Original head remains intact; the pause survives recreation until explicit retry. |
| Concurrent canonical writer and recreation | Pending local text cannot adopt the other writer's baseline; explicit reopen is required. |
| Recreation before dispatch | The replacement Activity alone can save the retained pending input; old callbacks remain inert. |
| Actual committed write before UI delivery | The Bundle remains reconciliation-required; a later load cannot silently acknowledge or duplicate the commit. |
| New draft selection | The old delayed input cannot modify either the old or newly selected draft. |
| Rejected worker | No disk write, false saved state or automatic retry. |

The existing `Core verification` workflow adds one strict eleven-identity XML gate
for this new Activity class. It rejects missing, duplicate, unexpected, failed,
errored or skipped cases and inconsistent suite counts. All earlier workflow
steps and test inventories remain intact.

Local portable checks verified the unchanged 43 frozen files and both pinned
manifests, the additive workflow reconstruction, all eleven Activity method names,
one accepted and 42 rejected report fixtures, unique default string resources,
resource references and relative documentation links. The report fixtures check
the new XML gate only; they are not Activity executions. The full reference
command then stopped before its tests because the local Python environment lacks
the pinned `referencing` dependency. A separate existing validation environment
was checked read-only: seven Android pins match, but required
`rfc3339-validator==0.1.4`, `rfc3986-validator==0.1.1` and `six==1.17.0` are absent.
Its packages were not changed; the full pinned reference suite remains for CI.

### Later portable validation — 2026-10-19

The initial environment failures above remain recorded. After source froze at
`1f3255cfc73b331c946a80807472e1e5fe66e445`, tree
`4da24b71f5cfba02ffe9c751dfa3a42a0e9a5480`, the existing
`/tmp/android-pattern-venv-c19/bin/python` was verified read-only against all ten
exact `requirements-contracts.txt` pins. With `PYTHONDONTWRITEBYTECODE=1`, that
interpreter ran `-B scripts/check_contracts.py` successfully: all 43 frozen files
and both manifests, all 21 reference tests (0.291 seconds), and all ten retained
fixture drivers passed with exit zero. Those drivers covered 4 report, 57 quality,
160 pattern, 75 event, 68 resource, 26 diagnostic, 145 ruleset, 117 deployment,
224 cost and 188 pricebook cases. No packages or frozen source were changed; the
checkout remained clean at the same commit and tree. This resolves the local
Python-only blocker. Ordinary receiving CI must still qualify its actual composed
source; Kotlin/JVM, simulated Android and installed execution remain separate.

The local authoring environment has Java 17 runtime only; JDK 21, a Kotlin compiler
and the Android SDK are unavailable. No Kotlin compilation, JVM execution,
Robolectric result or installed run is claimed by this source record. Required
ordinary receiving commands on the pinned toolchain include:

```sh
./gradlew --no-daemon --dependency-verification=strict :core:check :core:assemble
./gradlew --no-daemon --dependency-verification=strict -PandroidPreview=true \
  :preview:assembleDebug :preview:lintDebug :preview:testDebugUnitTest
git diff --check
```

Exact source/checkout identities, full original outcomes, independent review and
actual main/post-main qualification belong in the receiving receipt. Source and
declared method inventory do not substitute for those executions. The existing
installed-preview owner must reconcile any older explicit-save timing assumptions
when selecting this continuation; no old native result is attributed to autosave.

## Remaining whole-issue acceptance

The seven accepted model criteria and historical explicit-caller statement in
the [#26 record](delivery/android/work-packages/03.02.md) are unchanged. This source
adds the previously separate scheduler/caller behavior; it does not rewrite that
earlier acceptance as an autosave result.

All four literal #122 criteria remain open until complete interaction/duplicate/
restore behavior, original storage-failure and installed process-termination
evidence, the named #86/#112 outputs and Android domain/UX receiving acceptance
are qualified together. The canonical record remains
`Pick-Your-AI/pickyour.ai/docs/delivery/android/work-packages/08.06.md`.
This local review document relocates no acceptance path, changes no issue checkbox
and supplies no release authority.

## First hosted run and focused correction — 2026-10-19

The local-execution limits above describe the original preparation. The first
hosted run subsequently compiled the actual valid-signed prepared checkout
`c12b9b1f155040a6c730e0861df09fbd3bfadc69`, exact reviewed tree
`ba59b5381a20bc2958825ceacc75941110d2a626`, for public source
`135e3fcfaac002091a868d60fda00451582dc19a`. The original
[run39181633314](https://github.com/Pick-Your-AI/pick-your-ai-android/actions/runs/39181633314)
failed: its 77 core XML suites contain 3,644 cases with one failure, and its 24
preview XML suites contain 303 cases with nine failures. Both sets have zero
errors and skips. The new autosave suites account for eight core cases (one
failure) and eleven Activity cases (one failure); the other eight failures are
existing catalog/pin/choice/comparison cases. No failed result is relabeled as a
successful receiving or installed result.

The original Core artifact12156835995 is 1,206,450 bytes, SHA-256
`5977727758080ff2fd00d1564fdd27a7ed98bd8e9e10f51e91ee12afa65bb1e3`.
The original preview artifact12156816432 is 3,007,394 bytes, SHA-256
`87c0a72ff3a9c47f1bd315a496f0083b162220f25e62347ce4edc1a6de816228`.
Both downloaded archives matched GitHub's digests. All 24 original preview XML
members remain byte-exact in a smaller local archive after removing only the
reproducible full download for workspace capacity; the full GitHub artifact and
its original digest remain authoritative. Original failed XML, logs and source
identities are retained. The same-source scanner run39181633318 passed; its
original artifact12156746017 (941 bytes, SHA-256
`209055f00a567b4c01866f350063081b59edbc72f4de11ecd4ff941380fb7592`)
binds `c12b9b1`/`ba59b538`, 179 reachable commits, pinned scanner8.30.1 and zero
findings. That scoped scanner success does not qualify the failed application.

The correction preserves all production, resource and canonical-store bytes:

- The core identity test supplied `other-draft`, which the existing
  `PreviewDraftId` constructor rejects before the assertion. It now supplies a
  valid, distinct deterministic `draft-UUID` identity and retains every
  identity/receipt/token/complete-input assertion.
- The known-failure Activity fixture held the outer library lock. Opening a
  library-bound adapter therefore threw before `save` was called; the existing
  conservative caller correctly presented uncertainty and disabled saving.
  Holding `guest-draft/transaction.lock` instead lets opening succeed and makes
  the canonical save return known `BUSY` before its transaction body. The full
  unchanged-head, paused-retry, recreation and explicit-retry assertions remain.
  A separate added case retains the original outer-lock condition and requires
  uncertainty, preserved pending input, no automatic retry through recreation,
  and explicit reopen before a later edit can autosave.
- Eight existing read-only cases edited a valid pending form while expecting
  unchanged stored bytes and inspection authority. In exact Robolectric4.17,
  [ShadowChoreographer](https://github.com/robolectric/robolectric/blob/robolectric-4.17/shadows/framework/src/main/java/org/robolectric/shadows/ShadowChoreographer.java)
  defaults to unpaused frame scheduling independently of `LooperMode.PAUSED`.
  [ShadowDisplayEventReceiver](https://github.com/robolectric/robolectric/blob/robolectric-4.17/shadows/framework/src/main/java/org/robolectric/shadows/ShadowDisplayEventReceiver.java)
  advances virtual time by the default 15 ms for each scheduled frame. Rich
  dialog operations can therefore cross the real 750 ms deadline during plain
  looper idling. Those eight cases now pause that implicit frame clock before
  pending edits and assert that virtual elapsed time stays below 750 ms. Every
  original baseline, private-file, field, checked-choice and retired-control
  assertion remains. The autosave suite also uses an explicit frame clock.
- An added interaction uses the actual Handler scheduler and canonical file
  writes: an inspector remains visible at 749 ms, retires when saving starts at
  750 ms, and cannot be reopened through captured stale controls. A held real
  worker allows newer input to arrive while the earlier immutable input saves.
  Each input receives its own single durable revision; fresh explicit inspection
  works afterward, and later idle time creates no duplicate save. This preserves
  the existing rule that any storage operation retires baseline-bound inspection;
  it introduces no modal deferral policy or persistence bypass.

The exact Activity admission set is now the original eleven plus the two added
methods, thirteen total. All previous admission logic and other inventories stay
intact. Local portable checks parsed YAML and the exact admission program,
matched all thirteen declared identities, accepted one valid report and rejected
84 malformed report fixtures. Removing only the documented additions reconstructed
the prior workflow and all four legacy test files exactly; the original eleven
autosave methods reconstructed exactly after reversing the single lock-fixture
correction. The core delta is one ID substitution and this note is append-only.
These checks executed the report qualifier and source comparisons, not Kotlin.
The first failed run cannot qualify this corrected source: a new actual
configured run, complete original-result review and guarded receiving delivery
are still required. Source inspection and portable admission checks remain
distinct from Kotlin execution and installed Android UX acceptance. All four
whole-issue criteria and the canonical acceptance path above remain unchanged.

### 2026-10-19: qualify corrected Core and preserve the paused-frame failure

The next ordinary workflow, [39184187910](https://github.com/Pick-Your-AI/pick-your-ai-android/actions/runs/39184187910),
checked published source `b2aec41535a94ad73ec59c41f33ece176506de56` as the validly
signed prepared commit `40e2bbf241722936584d7a8b558e45d0fe988219`, exact tree
`67245b53dbe43424994e9ef370f4b918fab5094e`.

- Core job `117843021289` passed. Its original 77 XML suites contain 3,644
  cases and zero failures, errors, or skips, including all eight autosave cases.
  Artifact `12157731194` is 1,210,849 bytes, original ZIP SHA-256
  `173054215fe89aaafddec0397b9f1a8cde2f05294ab415f58857ba92eff7710b`.
  The original JAR agrees with its inventory, all eight build/input pins agree
  with the exact source, the ignored-schema negative control is source-bound,
  and the original 260-case conformance receipt was reproduced from its XML
  and frozen inputs (223 wire and 34 typed migration observations).
- The separate [scanner run 39184187823](https://github.com/Pick-Your-AI/pick-your-ai-android/actions/runs/39184187823)
  passed against that same prepared commit/tree: 180 reachable commits,
  zero findings, and completed scanner fixtures. Artifact `12157665543` is
  943 bytes, original ZIP SHA-256
  `c4648d43aef09557f968f68b4b17a35dfc39db76a5472eb91da5b5651f14e446`.
- Preview job `117843021110` compiled production and test Kotlin but failed
  its simulated tests: 24 original XML suites, 305 cases, 21 failures, zero
  errors or skips. All 13 autosave methods failed in `@Before launch` while
  awaiting the initial UI state, before their test bodies. The same eight
  legacy cases stalled on later dialog/UI delivery; the revision picker
  failed its existing different-dialog assertion. Artifact `12156993812`
  is 3,009,736 bytes, original ZIP SHA-256
  `f929e816f9013cdec4106ce350e3cbc01ad077138731b6301f05ab833af885c1`.
  Its strict post-test admission and packaged-policy steps did not execute.
  These failures remain failures; the Core and scanner results do not qualify
  the preview or establish installed Android acceptance.

Every original XML member was preserved byte-for-byte separately from the full
download. The retained XML archive is a derived container; the ZIP hashes above
identify the original GitHub artifacts, whose bytes were checked before extraction.

The correction is limited to test frame delivery. In
[Android 9 ViewRootImpl](https://github.com/aosp-mirror/platform_frameworks_base/blob/android-9.0.0_r1/core/java/android/view/ViewRootImpl.java),
`scheduleTraversals` installs a synchronization barrier and `doTraversal`
removes it. Pausing the frame receiver without delivering a frame therefore
blocks synchronous UI acknowledgements. Robolectric 4.17's
[own Choreographer tests](https://github.com/robolectric/robolectric/blob/robolectric-4.17/robolectric/src/test/java/org/robolectric/shadows/ShadowChoreographerTest.java)
explicitly exercise paused nanosecond frame delivery on the selected SDK range;
[ShadowPausedSystemClock](https://github.com/robolectric/robolectric/blob/robolectric-4.17/shadows/framework/src/main/java/org/robolectric/shadows/ShadowPausedSystemClock.java)
and [ShadowDisplayEventReceiver](https://github.com/robolectric/robolectric/blob/robolectric-4.17/shadows/framework/src/main/java/org/robolectric/shadows/ShadowDisplayEventReceiver.java)
implement the positive clock advance and requested vsync dispatch.

The shared test helper now pauses automatic frames and requests a one-nanosecond
frame interval. Each explicit UI-idle call delivers at most one requested frame,
then checks that millisecond uptime did not change. It never drains an animation
or advances to a pending Handler deadline. Ordinary unpaused cases retain their
prior idle behavior. All existing 749/750 ms boundaries, eight pre-deadline
checks, durable baselines, private-file assertions, failure/reopen controls,
13 Activity identities and workflow admissions remain unchanged. Production,
persistence, debounce timing and inspection retirement policy remain unchanged.

This continuation has source and whitespace checks only at preparation time.
It requires fresh compiled test execution after independent review and ordinary
publication; the failed prepared checkout is not relabeled as corrected evidence.

### 2026-10-19 — accepted-main timing reconciliation after the actual 835 run

The isolated autosave source b7 / prepared 71c passed its original Core and preview checks
and merged through #484 to valid-signed b7405ba1999fb7c608d595276d1cb48bfce859bf.
Its complete source/output qualification is preserved in #484 comment 6257980035.

The first accepted-main composition d498883df4f569fccfcaea99fc29b6a29330e14d
prepared valid-signed 835834fe69778da293ec668de7422a827a98ca7d at tree
933c5ce43ae9898b2940a5f01f540307d70b9b8e, ordered b740 + d498.
The ordinary run 39187852770 is not a passing preview result:

- Core job 117853974042 succeeded. The original 81 suites contain 4,025 unique
  class-qualified cases with zero failures/errors/skips, including all eight autosave
  cases. A second in-session reader independently qualified the complete conformance
  receipt, exact eight source pins and JAR bytes, the actual ignored-schema negative,
  all 29 source-use plus four integrity Python identities, and the original SPDX
  mapping/encoded bytes, pinned schema, namespace and 23-package/44-relationship graph.
  Core artifact 12158049198 is 1,268,105 bytes, SHA-256
  8531fd7b0ed71780bab4f200144c90d049f65ad5904774f226b0d25bc32d45ba.
- Preview job 117853973778 failed in the compiled simulated-test step. Its original 29
  suites contain 339 unique cases with six failures and zero errors/skips. All thirteen
  autosave cases passed. The failures are all four CatalogComparisonAuthorityActivityTest
  cases, CatalogRowAuthorityActivityTest.pagingFacetsAndSortRetirePreviousResultControls,
  and CatalogPinRecoveryActivityTest.comparisonAndPinInspectionRetireEachOthersRetainedActionsAndPreserveTrayPreferences.
  Three original stacks lack the expected current-dialog ListView and three expose the
  canonical library BUSY result while a valid pending form crosses the automatic deadline.
  Preview artifact 12158524291 is 3,033,406 bytes, SHA-256
  2c4f9b5edda0fc9585e92c33ca639f83fe1305ca502092ce57272e17fbbd1bcc.
  Subsequent preview guard/SPDX/policy/attendance steps did not execute.
- Scanner run 39187852759/job 117853973807 passed on that exact 835/933 source:
  189 reachable commits, zero findings, pinned scanner identity and real fixture controls.
  Artifact 12157884420 is 943 bytes, SHA-256
  9bdccb581b57043e547f49cae3f826c1b0f83ed37ef26c7de0c77d37143aa675.

All three original archives matched API byte counts and SHA-256, unique safe member
names and CRCs. Their complete original logs/XML and the six failure stacks are retained;
the public source-specific receipt is #498 comment 6258234684. The earlier failed c12
and 40e attempts and successful 71c attempt keep their own source attribution.

Accepted main then advanced through #488 to valid-signed
44b4c7e3e882301a45a232b3ee18523023739bb1. The separate clean automatic union
964c06da3a26b63a44e86d358febf08dd3babfb0 / tree 9e51e659db56dcd5a44dd1800bc1c12dae9c9d4b,
ordered 44b4 + d498, preserves the accepted retained-snapshot inspector and its historical
read authority. Its two pending-editor/private-file Activity cases were not in the failed
835 run; source review identified the same before-deadline boundary for their integration.

The bounded fixture continuation uses the existing, actually exercised AutosaveUiClock
helper. The comparison and row suites pause frame-driven time immediately before their
shared pending-edit setup; every case checks elapsed uptime stays within the 750 ms window.
The additional pin case and two incoming snapshot cases do the same locally. Their idle
calls still deliver one requested positive-nanosecond frame and verify no millisecond was
spent. Every existing field/baseline/private-file/archive/report/accessibility/retired-control
assertion and method identity remains. All original autosave deadline, failure, uncertainty,
newer-input and lifecycle cases, production code and workflow guards remain unchanged.

This is a source correction awaiting independent review and actual combined-source CI.
No locally executed Kotlin/Android, installed-process result, source/export authority or
whole #122 acceptance is inferred from original-result inspection or clock source review.
