# Opt-in synthetic Android diagnostic

The `preview` module is an engineering experiment using platform Android Views,
not an approved product UI/framework, production package registration, support
matrix or release policy. It browses the unmodified frozen-v1 `catalog.json` fixture and offers a guest title and one sample selection with explicit local save/reopen. A separate diagnostic validates the original
`build.json`, not the edited guest draft. All verdicts stay **unknown**. Fixtures
are bundled directly from the verified import, never copied into a real catalog.
There is no real-data mode or promotion path, account, network, import or telemetry.
Release variants are disabled. Explicit local save/reopen is described below.

The launcher is the only exported component. The manifest requests no permissions,
disables backup and cleartext traffic. Catalog and build structural validation run on a worker thread. A destroyed
Activity discards worker results. A small saved-state Bundle carries only the
bounded title, selected fixture ID/version and catalog hash; the catalog is reloaded
and validated after recreation. A missing exact selection is shown as unavailable,
never silently replaced. Bundle restoration does not acknowledge persistence. The
explicit local Save path now uses the canonical revision store; full library,
autosave and installed process-death qualification remain separate.

## Reproduce

First run the frozen-import checks in [CORE_FOUNDATION.md](CORE_FOUNDATION.md).
Install JDK21 and Android SDK platform35 and build-tools35.0.0 from Google's official
SDK tools, reviewing the SDK license. Set `JAVA_HOME` and `ANDROID_HOME` locally;
no machine-specific path or proxy belongs in source. Use the pinned Gradle8.11.1
wrapper and AGP8.9.2. Kotlin2.1.20 is inherited from the root plugin pin. Both the
core and preview emit Java17-compatible bytecode while running builds on JDK21.
The SDK numbers bound this experiment; they are not asserted to meet future Play
submission policy.

```sh
./gradlew --no-daemon :core:check :core:assemble
./gradlew --no-daemon -PandroidPreview=true :preview:assembleDebug :preview:lintDebug
```

Without the property the Android module is not included and no SDK is required.
The debug APK is `preview/build/outputs/apk/debug/preview-debug-unsigned.apk`, with the
unregistered experimental identity `ai.pickyour.android.preview`. Debug signing
is disabled: this unsigned build creates no key and cannot be installed directly. Do not
publish this diagnostic as the product.

After separately authorized signing with existing suitable debug material,
on an available emulator/device install using `adb install -r` and explicitly
launch the diagnostic. Verify the synthetic notice, validation result, font
scaling, TalkBack order/announcements, keyboard access, RTL, system insets,
rotation, backgrounding and repeated validation. A successful build or lint check
alone does not establish those results.

## Issue acceptance remains partial

| Exact criterion | This slice | Remaining prerequisite and why engineering discretion is insufficient |
| --- | --- | --- |
| #48: Pinned compatible JDK/Gradle/Android/Kotlin dependencies, package ID decision, modular build, documented developer setup and clean-checkout build | Pinned opt-in experimental module, setup, bytecode compatibility and build evidence | #34 receiving acceptance must select product framework/package/support policy; this diagnostic cannot claim that stakeholder decision |
| #48: A fresh checkout builds with the documented pinned toolchain and installs/launches under the approved package identity | Debug build can be independently reproduced with the documented SDK | Approved production identity is absent; actual emulator/device installation and launch evidence must be collected rather than inferred |
| #48: Existing website checks remain independently runnable; no developer-specific configuration is required in source | No website edits; SDK opt-in and environment-only local configuration | Website CI remains separate; no website checks are claimed by this Android run |
| #77: Each preview visibly identifies synthetic data and validates against its version or is marked as a negative test | Persistent synthetic notice, schema-validated catalog browse/detail, empty/incomplete guest draft and explicit unknown compatibility | Failed-rule and partial-quote product previews are not implemented; #58 product UI/repository integration remains pending |
| #77: Explicitly synthetic sample data, isolated test contracts, no accidental promotion of fictional prices/components into real-data mode | Only bundled fixture assets, no real-data or publication path | Production catalog/promotion adapter must enforce contamination rejection when implemented; absence of a path is not a tested rejection at a real boundary |
| #77: Real-data mode rejects fixture contamination rather than displaying it as vendor evidence | No real-data mode exists | Actual real-data publication contract/adapter and #58 integrated architecture are required for a meaningful promotion test |

Canonical receiving records remain `docs/delivery/android/work-packages/02.01.md`
and `02.06.md` in `pickyour.ai`. These partial artifacts may be linked there by the
coordinator; they do not move acceptance paths or close either issue.

Primary toolchain reference: [AGP8.9 release compatibility](https://developer.android.com/build/releases/agp-8-9-0-release-notes),
reviewed 2026-10-18: API35, Gradle8.11.1, build-tools35.0.0 and JDK17 minimum.
Google's [SDK repository metadata](https://dl.google.com/android/repository/repository2-1.xml)
provides archive checksums. Local setup verified commandlinetools build13114758,
platform35 revision2, and build-tools35 against that metadata. SDK manager source
list fetching failed behind the environment proxy; checksum-verified official ZIP
installation supplied the same build inputs. This workaround is environment-local.

A separate restricted-token CI job compiles the unsigned diagnostic and runs lint,
retaining APK/lint reports for seven days. It uses the hosted runner's SDK and
installs the named experimental SDK inputs only when missing. The independent
core job still runs without opting into Android. A hosted job result must be
observed on the exact PR commit; the workflow definition alone is not evidence.

## Observed local evidence (2026-10-18)

The pinned toolchain produced an unsigned debug APK. A subsequent strict
verification replay of `:preview:assembleDebug :preview:lintDebug :core:check`
passed (49 tasks, 23 seconds); lint reported **No issues found**. The JVM suite
passed 33 tests, and the frozen-import verifier plus 21 Python reference tests
passed. APK inspection confirmed all 11 synthetic example assets and all 14
schema resources match their frozen source bytes; license/notice resources are
retained. The merged manifest has no permission requests and only the launcher
exported. No debug signing key was created.

No emulator/device install, render, TalkBack, rotation, process-death, performance,
release signing or Play result was obtained. The unsigned artifact cannot prove
any of those outcomes. The local SDK lacks platform-tools (a build warning only);
local analytics initialization also warns because the home directory is read-only.
Neither warning prevented strict compilation or lint. No source-level proxy,
trust-store bypass or machine path was added.

## Guest UI scaffolding and acceptance limits

The incremental screen intentionally reuses platform Views and the existing toolchain;
it does not settle the eventual Compose decision. The catalog is read only from
bundled assets, capped at 1 MiB and schema-validated before display. Only identity,
name, kind and version are displayed. No fictional price, quality or capacity becomes
a real vendor claim. Details require explicit selection; selection does not trigger
analysis. A separate build diagnostic clearly labels that it checks the original
fixture rather than the current guest form.

The guest form bounds the title to 120 UTF-16 units and disables personalized IME
learning as a best-effort keyboard request, not a privacy guarantee. Users are told
not to enter sensitive data. Clear resets title and selection. Native controls provide
keyboard focus, scalable text, labelled editor, minimum button targets, scrollable
content, RTL/inset handling, and polite status announcements. Headings are marked on
API 28+. Actual TalkBack/font-scale/RTL/device validation remains outstanding.

Manual receiving cases, requiring a separately authorized installable build:

1. Browse each fictional item, inspect its ID/kind/version and cancel; draft stays unchanged.
2. Select one item; see its name and the incomplete/unknown state. No price or pass appears.
3. Enter a title, rotate/recreate, and confirm title/selection restore while catalog reloads.
4. Clear and recreate; no previous title/selection reappears. Finish the task and relaunch;
   no durable-save promise is made or inferred from Android task restoration.
5. Run build validation after editing; confirm the result describes only the original fixture.
6. Exercise retry with invalid/missing catalog assets in a separate test build: show unavailable,
   preserve the selection ID, allow clear/retry, never substitute an arbitrary component.
7. Check large text, narrow/landscape windows, TalkBack order/labels/announcements, keyboard,
   RTL and system bars. Device observations must be recorded separately from compile/lint.

This adds partial UI scaffolding for #58/#77 and browse/detail/draft journeys; it does
not close those issues or demonstrate durable editing, real catalog publication,
compatibility evaluation, pricing, installed-device behavior or delivery acceptance.

Guest UI verification on 2026-10-18: strict dependency verification with the pinned
JDK21/Gradle8.11.1/AGP8.9.2 and official checksum-verified platform35 revision2 plus
build-tools35 archives passed `:preview:assembleDebug :preview:lintDebug` (46 tasks).
The APK is unsigned. Catalog inspection confirmed 29 uniquely identified fixture
entries; resource XML and `git diff --check` passed. Android metrics initialization
and absent platform-tools produced environment warnings, not build failures. The
local Android user directory was isolated outside source; no signing key was created.
These checks did not install or run the UI. The manual cases above remain unexecuted.

## Synthetic evidence detail integration

The original-fixture diagnostic remains separate from the guest selection and
now also validates the bundled frozen-v1 catalog, selecting only
`catalog.fixture.1`, revision `1`, item `cat.workload.fixture`, version
`2026.10-demo`. It never selects a replacement version when that pin is absent.
The fixture's real stored provenance value is `user_supplied`; its display label
comes from `EvidencePresentationPolicy`. No `ReviewedEvidenceAssessment` is
created. Source checking and independent support therefore remain unestablished.
The displayed `checked_at` and `captured_at` timestamps come from the fixture,
not the device clock, and are explicitly described as synthetic metadata.
Version-pinned facts receive no time-to-live or invented freshness threshold.
All user-facing descriptions are Android string resources; identifiers and raw
contract status retain their exact encoding. This supplies localization-ready
English strings, not completed translations or manual accessibility evidence.

| Exact issue criterion | Implemented partial evidence | Remaining output required for acceptance |
| --- | --- | --- |
| #77: Each preview visibly identifies synthetic data and validates against its version or is marked as a negative test | Build and evidence details retain the synthetic banner; catalog schema and exact fixture pin are validated before displaying provenance | All required product preview states, latency/error/stale-response scenarios and rendered-device evidence remain separate |
| #97: Versioned specs, provider/offering identity, license conditions, source links, dates, price conditions and limitations visible | Selected fictional item identity, version, snapshot, provenance dates and limitations are displayed; missing license/link/rate information stays explicitly unknown | Actual component-detail UI, accepted real catalog and complete provider/spec/license/price projections are not supplied by this fixture diagnostic |
| #97: The displayed item version and source dates match the requested snapshot; a missing pinned version never silently resolves to the latest component | Exact pin lookup with tests rejecting replacement versions and wrong snapshot/revision; dates extracted from selected provenance/snapshot | Production repository integration, actual snapshot requests and receiving review remain required |
| #97: Every presented factual field uses accepted evidence or is visibly unknown, and source verification labels follow the accepted evidence semantics | All fields visibly synthetic; raw status preserved alongside policy label; source/support remain unestablished without an assessment | Actual reviewed evidence handoff and real-data receiving acceptance cannot be manufactured by an adapter |
| #97: Price context identifies currency, region, meter and effective period where supplied; absence of a usable rate is not shown as free | No price is displayed; missing usable rate is explicitly unknown | Accepted pricebook/rate projection and its full context are outside this item-provenance slice |
| #39: Differentiate pinned facts from current prices/availability; apply approved freshness rules, retractions, supersession and change notification | Explicit pinned-identity display; dates are metadata only with no invented expiry/current-state claim | Reviewed correction/retraction/supersession inputs and production notifications are still required |
| #39: A new component release does not silently rewrite facts about pinned historical versions | Exact-version adapter rejects replacement fixture version instead of falling forward | Actual historical catalog persistence and production request flow remain separate |
| #39: Current-state facts expose dates and approved freshness handling; corrections retain original and replacement dispositions | This diagnostic shows pinned fixture facts only and disclaims current availability and price accuracy | Current-state and correction receipt integration require their accepted inputs; this view makes no such evidence claim |

Five JVM adapter tests cover the actual bundled provenance, missing pinned
version, wrong snapshot/revision, malformed/oversized fixtures, and a negative
fixture with raw `verified` status that still cannot establish source checking or
independent support. These are automated projection checks, not UI/device results.
Canonical acceptance records stay in the website repository; this evidence is a
partial handoff for #77/#97/#39, not issue closure or a relocated receiving record.

Consumer handoff receipt: this slice consumes the research handoff at commit
[`74b8e6801e9b60a4c476b9ac64ae4114270ab2fd`](https://github.com/Pick-Your-AI/pick-your-ai-research/blob/74b8e6801e9b60a4c476b9ac64ae4114270ab2fd/docs/CONSUMER_HANDOFF.md),
which identifies immutable baseline
`4addb4d45d3eadb4598262532823634980b8a1b5`. Its frozen-v1 import manifest was
retrieved and compared byte-for-byte with this repository's existing manifest;
all 43 frozen files remain unchanged. This receipt consumes synthetic schemas,
fixtures and presentation boundaries only: no invented TTL, compatibility verdict,
source authenticity or reuse rights. Research #145's migration test adapter is
not a released v1.1 reader; #142's calculator is a proposal, not a main-branch
service implementation. Neither is consumed by this view. Receiving acceptance
and real-data integration remain separate.

Evidence-detail validation: strict unsigned assembly, lint and the JVM suite
passed locally (49 tasks; 66 JVM tests including the five new projection tests).
Lint reported no issues. The frozen hash verifier and 21 Python reference tests
also passed. No rendered-device, TalkBack or installation result is claimed.


## Explicit local guest save and reopen

The guest form now uses [PreviewDraftPersistence](../core/src/main/kotlin/ai/pickyour/core/preview/PreviewDraftPersistence.kt)
over the canonical `LocalDraftStore`; no second persistence engine, shared Draft
contract, database framework or account service is introduced. A dedicated
`noBackupFilesDir/guest-draft` directory holds private preview metadata: format,
title, opaque selected item ID, exact item version and SHA-256 of the selected
bundled catalog bytes. Domain content and unrelated metadata survive edits.
Missing version/hash stays unresolved, and catalog replacement never silently
selects a new version. The sample is displayed as unavailable until the user
explicitly selects a current sample or clears the form.

Save is explicit, not autosave. The UI displays saving only during the worker
transaction and saved only after `PreviewDraftSave.Saved`. All edits/selection
controls are temporarily disabled during load/save; duplicate saves are disabled.
The single worker serializes IO. Failure preserves visible fields; stale tokens
block saving until explicit reopen, and uncertain outcomes require reread before
retry. Reopen requires confirmation before replacing the visible unsaved form.
Clear modifies the form only; a later Save commits an empty form as a new revision
and retains old history. Unsupported/corrupt persisted formats are never reset.
History/size exhaustion has distinct feedback and never trims revisions silently.

On recreation the form's small Bundle preserves pending fields; disk load establishes
which fields are actually acknowledged. Bundle restoration is never itself a saved
acknowledgment. Destroyed activities ignore worker callbacks. Interrupted storage
may return failure/uncertainty; the next load reopens the canonical head. Unsaved
edits can still be lost if Android stops the app without saved-instance restoration;
this limitation is visible. Rapid editing, autosave and full draft-library workflow
remain separate acceptance work rather than a promised no-loss property.

Root initialization runs off the UI thread. It synchronizes the platform-managed
app-data parent after requesting the no-backup directory, creates the dedicated
child, then synchronizes its parent on every initialization/retry. The canonical
store subsequently creates/syncs its revisions child. Any unsupported directory
sync or filesystem error fails closed without a saved acknowledgment. The existing
manifest has backup disabled, no new permissions, and no new exported component.
The private metadata is neither a public export nor cloud synchronization.

Primary Android references checked 2026-10-18:
[Context.getNoBackupFilesDir](https://developer.android.com/reference/android/content/Context#getNoBackupFilesDir())
for app-private no-backup storage and
[FileChannel.force](https://developer.android.com/reference/java/nio/channels/FileChannel#force(boolean))
for local-device force limitations. These API contracts do not establish actual
power-loss recovery or directory-channel support on every supported device.

Eleven new JVM adapter/lifecycle-guard tests exercise empty/incomplete/pinned-selection round trips,
clear-as-revision, stale token conflicts, content/metadata preservation, unsupported
private formats, pre/postactivation failure and form limits. They reuse the real
canonical cache/store and injected failures. The lifecycle guard persists the exact
revision/hash baseline and a reconciliation-required marker through Bundle state.
A changed disk baseline, conflicted/uncertain state or in-flight I/O cannot silently
acquire newer save authority during recreation; pending fields remain visible and
explicit Reopen is required. Missing checkpoint metadata also fails closed. Four
regressions exercise these restoration decisions without claiming an Android run.
Core JVM tests are not Android
filesystem or UI lifecycle evidence. Hosted unsigned build/lint must pass the final
PR head; emulator/device, TalkBack, rotation and process-termination flows remain
unexecuted until an instrumented installation is actually tested. This change does
not generate a signing key or install/release an app.

Partial criteria: #86 local open/save and recovery; #67 canonical storage integration;
#122 acknowledgment and retained pending fields; #76 lifecycle restoration. Still
missing are recoverable draft deletion, autosave/domain revision UI,
retention UX, installed-process-death evidence and receiving acceptance. Canonical
records remain `11.01.md`, `12.01.md`, `08.06.md` and `02.03.md` under the existing
website Android work-package path. No issue closes.

## Retained guest form history

The saved-history button reads immutable entries from the canonical store. Each
entry retains its exact title and opaque selection/version/catalog digest. Missing
or unsupported private guest metadata is shown as unavailable; it is not replaced
with an empty or current form. Migrated missing earlier history remains unavailable.

Choosing a usable entry asks before replacing unsaved form edits. Confirmation
copies only its guest fields into the editor, with no storage side effect. The user
must explicitly Save locally to append a new EDIT using the current expected-head
token. Existing domain content and current unrelated metadata stay intact. This is
not canonical domain RESTORE, a report/price replay, or a complete revision editor.
A dialog whose captured baseline was superseded by another in-Activity operation
cannot copy into the editor; a different writer still fails the canonical save CAS.

Three JVM regressions cover exact pinned fields and immutable historical views,
unavailable metadata, no write while copying, new EDIT preservation, and concurrent
save rejection. Hosted unsigned Android build/lint is required on the final PR;
installed history-dialog accessibility and rotation behavior remain unexecuted.
This is partial #122 recovery presentation and closes no work-package acceptance.

## Synthetic cost inspection

The cost-inspection button loads the fixed frozen `build.json` and
`cost-estimate.json` together on the worker and passes them through the canonical
CostEstimateAdapter. This diagnostic is explicitly separate from the editable
guest form. It never submits a current-draft analysis ticket or attaches fixture
amounts to user selections. A current-draft consumer still requires coordinator
revision/ticket invalidation before it can display asynchronous estimates.

The view shows bound fixture build/revision/pricebook, declared period/currency,
recorded time (not freshness), producer-declared coverage, exact declared subtotal,
tax and sum, explicit unpriced selections and unclassified active selections. Each
line exposes exact quantity, unit/line amounts, rate ID, meter, method, confidence
and assumptions; warnings are preserved. Immutable line projections retain decimal
lexemes without deriving quantities or rounding. Empty lists say none declared,
which is not completeness evidence. Declared COMPLETE never becomes verified
complete coverage. No total is called a complete stack price or a proven lower bound.

Budget pass, freshness and free/included treatment remain unestablished, including
zero recorded amounts. Supported ranges are not invented. Rejection/error clears
old display content and establishes no amount/pass. Frozen adapter tests cover
partial/unknown/complete, zero, mixed currency and revision mismatch; two additional
projection tests cover immutable exact line evidence and zero-price non-attestation.
Hosted Android compilation/lint and installed rendering/accessibility remain
separate validation. This is partial #103 presentation, not approved pricing,
real-data acceptance or completion of its budget-gate/delayed-response criteria.


## Bounded local draft library

The preview now lists, creates, opens, duplicates and explicitly deletes local drafts through
PreviewDraftLibrary, a bounded directory catalog over the same LocalDraftStore.
Opaque UUID directory IDs are separate from user-editable titles. Renaming is an
ordinary title edit plus Save locally. The original `guest-draft` directory stays
in place with unchanged identity/history; there is no silent bulk migration.

Creation serializes through an app-private library lock, provisions/syncs the
child directory before canonical save, and acknowledges only its returned saved
snapshot. Any failure after directory creation returns the stable unconfirmed ID;
the UI retains its current form and asks the user to inspect that ID before another
creation. Incomplete/corrupt/unsupported entries remain visible, never automatically deleted or
reset; individual open failures do not prevent opening other valid entries. Known path IDs reject traversal, symlink entries fail closed, and catalogs
are bounded to 64 drafts including pending cleanup, and 256 scanned entries per
catalog directory. Capacity errors never trim data.

Duplicate explicitly copies the selected saved snapshot (content and metadata),
not pending fields, into a fresh initial revision with separate identity and save
authority. Local duplicate lineage records the source directory ID, revision and content hash.
Source history and source bytes are not changed. Open/new/duplicate
require confirmation before abandoning pending form edits; controls serialize IO
and existing lifecycle baseline checks remain in force. Current selected draft ID
survives Bundle recreation. Cold launch uses the durable last-opened pointer below;
only an absent pointer permits the original guest default.

Ten JVM tests cover independent create/open/rename, exact snapshot duplicate
without authority/history reuse, legacy and unavailable entries, creation faults
before/after commit, lock contention, capacity/path/scan bounds and symlink rejection.
Failed switching (including successful creation followed by failed reopen) preserves
the original ID, expected-head token, save readiness and pending form as one state;
only a successful target read commits the editor switch.
These are native filesystem tests, not installed Android filesystem/process-death
proof. Hosted Android build/lint is required on final head; dialog accessibility,
installed switching/rotation and device storage tests remain unexecuted. Partial
#86/#67 only: retention/export, autosave, installed active-pointer recovery,
installed qualification and receiving work-package acceptance remain outstanding.


## Durable last-opened selection

A tiny private v1 payload contains only the local directory ID. It is stored through
the existing VerifiedArtifactCache in a durably provisioned `.active-draft` child,
with exact content hash, atomic activation and parent synchronization. It is not a
save token, a shared contract or a second draft engine. Reads distinguish absent,
selected and failed state. Only absent selection defaults to the original guest;
malformed/future bytes and unavailable/corrupt selected records never silently
fall back to another draft. Manage local drafts remains the recovery path.

An explicit library switch first reads the target and then commits the selection
pointer on the worker. Only an acknowledged selection changes the editor. Failure
or uncertain activation retains the original ID, token, pending fields and save
readiness, warns that a later cold launch may see the selected target, and leaves
all draft records intact. A successfully created draft stays in the library even
if selection cannot be confirmed. Bundle recreation retains its editor identity
and baseline guards; it does not silently adopt another Activity's selected draft.
Unresolved startup selection also survives recreation without enabling Save or
Reopen against an invented active target.

Seven JVM tests cover fresh-instance selection, independent draft preservation,
malformed/future/path payload rejection, pre/postactivation failures, unavailable
or symlink targets, initialization sync retry, and non-committing editor switches
on pointer failure/uncertainty. These verify native storage and pure transition
behavior, not installed Android process termination or power loss. Hosted unsigned
Android build/lint is still required at final head. Full #86/#76 acceptance retains
its device, recovery, retention and receiving-review requirements.


## Objective and context editing

The local editor exposes the existing DraftContent objective/context fields without
converting an incomplete form into BuildSnapshot. Null (unanswered), explicit empty
text, and entered content stay distinct and have visible state labels. Both fields
use the canonical 16,384-character bound; accepted stored text is never silently
truncated. Clear draft explicitly resets these inputs to unanswered.

Save locally commits these two fields together with title/sample metadata using
the same exact expected token. Primary model, all optional-tier activations,
selections/settings and unrelated metadata remain unchanged. The adapter's older
save call without a context edit still preserves all existing domain content.
Pending objective/context values survive Bundle restoration under the same
baseline/reconciliation guards; older restoration bundles without these new keys
hydrate them from the loaded baseline rather than silently clearing saved inputs.
A failed library switch leaves them unchanged; a successful switch loads the target
values. Guest-history copy still copies title/sample fields only, preserving any
objective/context inputs currently in the editor.

Five JVM regressions cover unanswered/empty/entered round trips, preservation of
primary model/tiers/settings/metadata and history, stale rejection, old-call/history
copy preservation, canonical bounds, redacted diagnostics, and a pure restoration
checkpoint test distinguishing missing older keys from explicit pending null/empty
values. This is not an Android Bundle/device lifecycle execution. Hosted Android build/
lint and actual installed keyboard/accessibility/rotation tests remain separate.
This is partial #26/#122 editing, not rule evaluation, recommendations or shared
snapshot validation. No issue acceptance or device qualification is claimed.

## Explicit domain revision restore

Partial #122 integration now exposes a separate **Review domain restore** action
from a supported retained history entry. The existing **Copy form to editor**
operation still copies title/sample only and requires a later explicit save.
Domain restore instead previews retained objective/context, opaque primary-model
input and optional-tier activation/selection/setting counts. Its second explicit
confirmation commits `LocalDraftStore.restore` with the original loaded token.
It restores all domain content as a new `RESTORE` head and keeps current metadata,
including saved title/sample pins and lock metadata, exactly as the canonical
store specifies. Historical entries remain unchanged. The history list exposes
change codes and restore-source revision numbers.

On acknowledged success, restored objective/context replace those pending inputs;
unsaved title/sample changes remain visible and unsaved. On failure every pending
input remains visible. Conflict and uncertain activation require explicit reopen;
no retry adopts a newer disk token. A dialog tied to a superseded in-Activity
baseline cannot commit. Restoring while the Activity is recreated uses the existing
in-flight/changed-baseline reconciliation guard, and destroyed views ignore late
completion. No evaluation, price or report is replayed. A historical guest format
that cannot be displayed remains unavailable in this bounded UI.

Three new JVM adapter tests cover edit/restore/edit with opaque optional content,
metadata/history preservation, same-content restore, stale/foreign/unknown targets,
and pre/post-activation failures plus restoration-checkpoint rejection. These are
core/adapter tests, not installed dialog, rotation, accessibility or power-loss
acceptance. Verify those flows on the selected Android device matrix before claiming
full #122 completion. Existing 128-entry and record-size bounds remain fail-closed.


## Explicit local deletion and interrupted cleanup

The library's row actions now offer confirmed deletion for saved, incomplete and
unreadable local records. This extends the existing directory catalog and canonical
`LocalDraftStore`; it does not introduce another revision engine or an interoperable
Draft format. Deleting a readable record requires its exact directory identity,
revision and content hash from the confirmed list entry. A changed or foreign token
returns conflict without starting deletion. An unreadable record can be removed only
after explicit confirmation; transient busy, IO and cancellation failures do not
authorize treating a record as corrupt.

Every library read, creation, deletion and bound persistence operation, including
objective/context saves, durable domain restores and active-pointer writes, shares the
same nonblocking library lock. The canonical-root JVM guard is acquired before the
OS lock file is opened, retaining the process-lock protection from the prior fix.
The library checks each bound adapter's identity again under that lock. An editor
opened before deletion cannot recreate files, restore history or save into a tombstoned or absent
directory. All cooperating library writers must obtain their adapter through
`PreviewDraftLibrary.open` or `openLegacy`; the standalone store constructor remains
available for independent stores outside the library.

The existing active-pointer payload and deletion protocol remain unchanged.
Selection now rechecks directory and tombstone liveness under the same library
lock before writing the pointer. A pending deletion cannot acquire a new selected
acknowledgement, and a rejected same-process selector cannot release the deleting
operation's OS lock. Errors after a pointer write was attempted remain uncertain.
If the provisioned root disappears or becomes invalid before library initialization,
selection returns a typed corrupt/IO failure without attempting a pointer write.
Legacy bootstrap obeys the 64-entry capacity bound and requires a genuinely absent
pointer when its directory is missing; a missing explicitly selected guest never
becomes a fabricated empty replacement.

Upgrade regressions populate the preceding `guest-draft` and `draft-UUID` layout
with retained history, exact sample pins, objective/context, an unsupported record
and a selected draft. Reopening preserves those identities, paths, hashes, inputs
and the selected pointer. Independent regressions reproduce the prior 65th-entry
bootstrap and acknowledgement of pending-deletion targets, including a real
external-JVM lock probe before and after same-process selector contention.

Deletion first writes and forces a tiny intent marker in `.preview-deletions`,
then forces its parent before removing any record bytes. An interrupted operation
retains its local ID and appears as pending cleanup in the bounded list, including
when the record directory has already been removed. Reads and saves fail closed
while the marker exists. Retry resumes cleanup of that same ID. Only after record
removal and parent synchronization succeed is the pending marker removed and its
directory synchronized. UUID names are never reused, so acknowledged removal does
not accumulate permanent per-draft markers. The single reusable legacy name keeps
a small `.preview-guest-deleted` marker; cold launch cannot provision a replacement
`guest-draft` after deletion. The last-opened pointer is retained as an unavailable
selection until the user explicitly opens or creates another draft.

Cleanup removes only the selected local directory, including its complete retained
revision history and cached revision artifacts. Filesystem traversal does not follow
symbolic links, is limited to 4,096 entries and 16 descendant levels per attempt,
and preserves the pending marker on a limit or IO failure. Repeated cleanup can
finish an oversized flat directory without unbounded work in one transaction.
Unusual deeper corruption may require a future repair tool; this preview does not
silently broaden its traversal limits. Successful deletion acknowledges local file
removal, not secure erasure, remote deletion, shared-catalog eviction or a full cache
retention policy. The markers contain no title, selected catalog ID or draft body.
The Android [`Files.walkFileTree` reference](https://developer.android.com/reference/java/nio/file/Files#walkFileTree(java.nio.file.Path,%20java.nio.file.FileVisitor))
documents the default link behavior; the JVM symlink regression verifies this
implementation's selected-directory boundary. Installed filesystem results remain
separate from that API contract.

Deletion of another row preserves the current editor's identity, revision token,
pending fields and save readiness. Deletion or an uncertain result for the current
row retains its visible pending fields and disables saving into the affected ID.
The UI explains that outcome and offers library inspection/retry. A conflict or
failure before deletion starts leaves the editor state unchanged. Bundle restoration
retains the existing reconciliation marker; it cannot convert an interrupted delete
into a saved acknowledgment or resurrect the legacy default.

Local validation on 2026-10-18 passed 197 JVM tests, including 12 deletion/guard
regressions. These cover complete selected-history removal, stale opened adapters,
equal-byte foreign identities and stale tokens, faults at every deletion phase,
legacy default suppression with a retained active pointer, corrupt/incomplete rows,
symlink isolation, a separate JVM OS-lock probe, a busy canonical record, bounded
cleanup/retry, editor transitions and post-save outer-lock uncertainty. The pinned
JDK/Gradle/Android toolchain passed `:core:check :core:assemble
:preview:assembleDebug :preview:lintDebug` in 34 seconds (49 tasks), with zero lint
issues. All 43 frozen files and both manifests remained unchanged; 21 Python
reference tests, four reference-report fixtures, four integrity regressions and six
ledger tests passed. Hosted validation must still be observed on the exact PR head.

The updated source and tests address local deletion/recovery portions of
[Android #86](https://github.com/Pick-Your-AI/pick-your-ai-android/issues/86) and its
[storage prerequisite #67](https://github.com/Pick-Your-AI/pick-your-ai-android/issues/67).
Actual installed recreation/termination, storage-full and power-loss behavior,
dialog rendering/TalkBack, accepted shared export/retention contracts and receiving
work-package evidence remain separate required outcomes. These local JVM and
unsigned build results do not complete those criteria.

Required installed receiving cases include deleting the current versus another
draft with pending edits; killing and relaunching during each cleanup phase;
opening another valid draft beside a corrupt or pending row; retrying after storage
failure; confirming that a deleted legacy selection never creates an empty saved
replacement; and verifying readable confirmation/recovery controls with large text
and TalkBack. Canonical receiving records remain in the website repository at
`docs/delivery/android/work-packages/11.01.md` and `12.01.md`.
## Optional-tier activation editing

Partial #121 integration exposes independent T3/T4/T5 activation from the existing
local `DraftContent` model. Each saved tier displays ENABLED, DISABLED or UNKNOWN,
plus retained selection/setting counts. The user chooses a state and confirms an
immediate local commit. Controls require an acknowledged saved form with no pending
title/sample/objective/context changes; save or explicitly reopen those changes
first. A superseded dialog baseline cannot commit, and store CAS rejects another
writer. Changing one activation retains every tier's selections/settings, the other
activations, objective/context/primary model and all current metadata. Repeating
the same activation with current authority uses canonical no-op save deduplication.

Disabled inputs remain retained but are excluded from the core active optional
projection; UNKNOWN stays separately identified and does not imply zero cost or
compatibility. T6 is explicitly core and has no optional control. This is editing
of existing opaque local configuration, not a launch decision or implementation of
T3 knowledge, T4 integration or T5 training services. No rule evaluation or pricing
runs from these controls; the fixed fixture diagnostics remain independent. Dynamic
dependency-graph invalidation, accepted detailed settings and end-to-end evaluation
remain separate integration work.

The same worker, saved/saving/failure presentation, exact baseline and recreation
blockade apply as to other durable operations. Visible state changes only after
acknowledgement; pre-activation failure retains the previous state and uncertain
activation requires explicit reopen. Three new JVM adapter tests exercise all 27
activation combinations with reopened configuration/metadata preservation, active
and unknown projections, immutable views, current-only deduplication, stale CAS,
restore and injected pre/post-activation failures. A fourth regression verifies
that a held library adapter cannot activate a deleted or tombstoned draft; activation
uses the same library write/liveness guard as save and restore. Hosted Android compile/lint and
installed lifecycle, TalkBack/font-scale/RTL/keyboard checks remain distinct gates.
## Read-only local revision comparison

Partial #117 integration lets history compare a chosen retained local revision to
its captured saved head. The pure comparison preserves null versus empty versus
literal `unknown`, identifies objective/context/model changes, per-tier activation,
selection membership and settings-key changes, plus supported guest title/sample
pins. It flags metadata divergence without exposing arbitrary metadata values.
Missing historical guest metadata stays explicitly unavailable. Neither side is
mutated and no save token is adopted; pending editor values are excluded.

The UI computes on the shared worker and rejects display if the saved baseline has
changed. It displays at most 100 changed rows and the first 160 source UTF-16 units per value/key,
escaped for display (escaped text can be longer),
with explicit truncation/count disclosure; full exact values stay in the immutable
comparison result and durable source. A same-revision comparison has no changes.
Comparison is within one retained history only: report, cost, rules and evidence
comparison and cross-build semantics remain the separate accepted receiving work.
It supplies no new evaluation, price or recommendation and is not full #117 closure.

Four JVM cases cover exact missing/empty/opaque values, same-setting changes,
deterministic selection deltas, unchanged/unknown targets, unsupported guest
metadata, read-only head identity and immutable output. Typed display separates absent/empty
sentinels from quoted literals (also reused by domain-restore confirmation), escapes controls/format characters and bounds
truncated text explicitly. Hosted Android compile/lint
and installed dialog/accessibility/lifecycle behavior remain separate gates.

### Simulated Activity regression tests

The opt-in preview test task `./gradlew --no-daemon -PandroidPreview=true
:preview:testDebugUnitTest` runs JUnit 4 and Robolectric 4.17 against simulated
API 28 Android resources and Views. The existing unsigned CI lane retains its
JUnit XML and HTML reports and fails if that task fails. AndroidX is enabled for
the test infrastructure; the preview UI still uses platform Views.

The bounded tests cover acknowledged objective/context restoration, stale pending
forms after another writer advances the saved head, explicit tier confirmation,
and typed read-only revision comparison. They drive visible controls, wait for
explicit UI states with a fixed failure deadline, and assert an empty per-test
sandbox before using local draft storage. These JVM simulations do not establish
installed app, emulator, physical-device, accessibility, filesystem power-loss,
Play submission, or release qualification. The native SDK build and all those
receiving acceptance gates remain separate.

The simulated regression suite also inspects explicit editor label associations,
focusability, textual tier state and polite saved-status announcements, and drives
confirmed deletion of the current draft while unsaved title/objective inputs are
visible. The deletion case requires retained pending fields, revoked save/history
authority and an empty local library after acknowledged removal. These are bounded
partial automated checks for #64/#106 and local recovery; they do not establish
screen-reader traversal, keyboard usability, contrast, measured touch targets,
large-text/RTL rendering or physical filesystem durability.

A further simulated layout check uses explicit Robolectric native graphics (not
legacy shadow text metrics) and recreates the preview at 200% font scale in an
Arabic/RTL 320×640dp configuration, explicitly measures/layouts the root, and
requires visible buttons to retain measured 48dp width/height, nonempty text layouts,
no ellipsized labels and enough vertical space for those labels. A separate check
uses Android's forward-focus search through the three editors to the Clear action.
The report gate requires both cases. These are reproducible JVM layout/focus checks,
not a screenshot review, full keyboard journey, TalkBack traversal, contrast test,
complete localization, device render or acceptance of a support matrix.

## Synthetic catalog search, facets and local pages

Partial [#82](https://github.com/Pick-Your-AI/pick-your-ai-android/issues/82)
discovery extends the canonical `PreviewCatalogQuery` search from #192 with tier
and kind facets, optional stable sorting and bounded local pages. Its existing
120-character literal substring semantics across name, ID, version and canonical
kind are preserved; matching and name sorting use `Locale.ROOT`, independent of
the device locale. The tier and kind filters combine
without silently changing one another. All seven tiers and all 29 frozen kind IDs
remain reachable, including a kind with no matching row. These IDs are explicit
fallback labels while the shared display-label handoff remains unaccepted.

The default keeps fixture order. Optional name and tier/kind sorting use stable ID/version tie breakers, so duplicate labels
cannot reorder page membership. Local pages contain at most ten rows; previous and
next preserve the exact loaded snapshot. An empty result has a distinct message.
Query (up to 120 UTF-16 units), tier, kind, sort and page are small transient Bundle
values. Returning from details preserves them; recreation reapplies them after
loading the fixture. Changed snapshot bytes reset the page with visible notice.
Unsupported saved filters, sorting or page settings explicitly reset to the defaults;
the Activity retains the bounded saved query. Filtering never
rewrites the draft's original sample pin, and a hidden selected item still has its
exact identity available from the full loaded snapshot.

The core copies its input and returns immutable collections, rejects ambiguous ID/version pairs
and kind/tier mismatches, and verifies the original frozen schema and bounded JSON
before browsing, retaining #192's 256-row snapshot bound. Distinct versions with the
same item ID remain independently selectable. It bundles the byte-identical frozen component registry as a
resource; no schema, registry or example is changed. Only the existing packaged
synthetic fixture enters this UI. A detail confirmation captures the displayed
snapshot and current editor baseline; a superseded callback cannot apply a sample
to another loaded draft or silently choose a different version. Explicit selection
only changes pending fields; the existing guarded save still owns disk changes.

Nine new core regressions cover all canonical facets, preserved literal query semantics,
Turkish-locale independence, stable paging with duplicate labels, missing categories,
exact membership including distinct versions of one ID, immutable collections,
bounds and invalid input. The complete integrated core suite passed 227 tests, with
zero failures, errors or skips. Frozen reference,
fixture, tampering and ledger checks also passed. Three additional Activity tests
exercise browse restoration, empty facet combinations and selection baseline guards.
Their exact-head hosted compile/lint/Robolectric results are required before merge.

The Activity closes its catalog browser during destruction. Retained editor,
facet-dialog, paging and row callbacks cannot change the retired browse state or
open another dialog. An additional required Activity regression recreates the
screen and delivers callbacks from its destroyed predecessor, checking that the
current screen and draft remain unchanged. This is a simulated lifecycle check,
not installed-device or process-death evidence.

These are bounded in-memory fixture pages, not a remote pagination protocol. API
cursor contracts, cancellable remote search, next-page network failure/retry,
reverse-order transport responses, real catalog publication/labels, exact pixel
scroll restoration and installed navigation/accessibility/process-death evidence
remain separate #82 acceptance work. The generic catalog-loading worker applies
the current query state when a fixture read finishes, so an earlier query cannot
append rows into a newer query. No search request is sent to a service and no
compatibility, price, source-rights or production-catalog claim is inferred.

Android's [saved UI state guidance](https://developer.android.com/topic/libraries/architecture/saving-states)
(checked 2026-10-18) distinguishes small transient state from durable storage and
user-initiated dismissal. This preview uses that distinction; Bundle recreation
tests do not establish force-stop or physical-device recovery.
