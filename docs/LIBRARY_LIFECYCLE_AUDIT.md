# Local library and navigation criterion audit

This is a scoped engineering audit of [Android #86](https://github.com/Pick-Your-AI/pick-your-ai-android/issues/86)
and [Android #76](https://github.com/Pick-Your-AI/pick-your-ai-android/issues/76),
read on 2026-10-18 against main
`8162a5571147874635d3139e73a171a1077a6d2b` and the incoming-entry correction described
in [NAVIGATION_BOUNDARY.md](NAVIGATION_BOUNDARY.md). It records implementation and
observed evidence without marking either work package complete.

The canonical receiving paths remain `docs/delivery/android/work-packages/11.01.md`
and `docs/delivery/android/work-packages/02.03.md` in `Pick-Your-AI/pickyour.ai`.
Their contents were not returned by the exact GitHub paths at this intake (404).
This audit is a supporting artifact and does not relocate those records.
[ADR 0001](decisions/0001-android-23-acceptance-record.md) applies only to #23's
14.01 path. The live assignee and receiving-review records remain authoritative;
role descriptions are not another person's accepted assignment.

## Accepted source and actual main evidence

[PR #277](https://github.com/Pick-Your-AI/pick-your-ai-android/pull/277) delivered
canceled-dialog guards, retryable selected deletion and the storage-operation
identity fence. Its verified squash is the main commit above, tree
`71843091c1ddf3c6476106b137823a49d9628107`. Independent final source reviews
[5562512080](https://github.com/Pick-Your-AI/pick-your-ai-android/pull/277#pullrequestreview-5562512080)
and [5562512934](https://github.com/Pick-Your-AI/pick-your-ai-android/pull/277#pullrequestreview-5562512934)
cover its exact receiving composition; the [main readback](https://github.com/Pick-Your-AI/pick-your-ai-android/pull/277#issuecomment-6248734035)
records the resulting branch evidence.

The actual main [core/native workflow](https://github.com/Pick-Your-AI/pick-your-ai-android/actions/runs/39118599253)
executed 406 core cases and 121 Android simulations with no failures, errors or
skips, including all twelve named local-dialog authority cases. Its actual
unsigned APK SHA-256 is
`e7527c350d50d92f4b3a29cee4fa5b9086a4da83fb1e6c910cf67546f2594f2b`.
Lint reported no errors and fifteen warnings. Both inventory ignored-input guards
rejected real packaged contamination and withheld a fresh receipt. The separate
[main scanner](https://github.com/Pick-Your-AI/pick-your-ai-android/actions/runs/39118599311)
checked 116 reachable commits with zero findings and passed its failure controls.
These are exact-main build, storage/JVM and simulated Activity observations.

## Malformed private draft identity recovery

The 2026-10-19 source review at `517025db301785f8e3689abdeeb47fc8d09b9afc`
found that `onCreate` passed the saved draft and pending-deletion identity strings
directly to the strict `PreviewDraftId` constructor. An unsupported identity could
therefore throw before the recovery UI appeared. The private
`DraftIdentityCheckpoint` now checks the primitive types and identity bounds as one
pair. A malformed pair keeps visible pending input unbound, with no automatic
active-draft lookup, save, Reopen or deletion replay. The value-free unavailable
marker survives another recreation and ordinary launcher entry; only an explicit
successful local-library/new-draft choice clears it. Existing missing/null legacy
identity behavior is preserved. No stored draft or revision format changes.

`DraftIdentityRecoveryActivityTest` covers malformed draft and deletion identities,
wrong types and oversized values, repeated rejected recovery, explicit local
selection and the valid checkpoint control. The workflow requires all six methods
to execute successfully. Its observed execution belongs to the corresponding PR
or main run; the test source alone supplies no pass or installed-device claim.
Android's [saved-state guidance](https://developer.android.com/topic/libraries/architecture/saving-states)
was checked on 2026-10-19: small transient identities support restoration, while
the canonical local store remains the authority for persistent application data.
This correction remains partial #76 and preserves every criterion and receiving
path below.

## #86: Local build library

The canonical `PreviewDraftLibrary` orchestrates `PreviewDraftPersistence` and
`PreviewActiveDraftStore`; it does not maintain a second revision engine. The
native library lists saved, missing, corrupt and pending-deletion records. Editing
the title and explicitly saving renames the selected draft by appending a local
revision. Duplication uses a fresh local ID, independent initial history and
explicit source ID/revision/hash lineage. Deletion names one selected local ID
and preserves unconfirmed cleanup for retry. Local IDs and private lineage do
not grant a remote ownership transfer.

| Exact acceptance criterion | Implemented source and observed check | Failure/recovery result and remaining evidence |
| --- | --- | --- |
| Create, rename, duplicate, list, open and delete local drafts/revisions with durable storage and recovery. | Canonical library, immutable local history, active-pointer store and native library actions; `PreviewDraftLibraryTest`, `PreviewDraftDeletionTest` and the real positive create/duplicate/open/delete Activity case. | Atomic writes, CAS, bounded history, failures before acknowledgment and unconfirmed cleanup are covered in local/simulated tests. Installed durability, actual storage-full behavior and the complete receiving record remain open. |
| Library operations survive process recreation, and duplicate/delete actions affect only the selected local build. | New duplicate IDs retain explicit source lineage; stale/foreign baselines and canceled callbacks cannot change another draft. Library/active-pointer reopen and Activity recreation cases pass. | A JVM/Activity reconstruction is not Android process termination. The installed four-case chain has not passed; its later library operation sequence and actual process recreation remain unexecuted acceptance work. |
| Empty and partial drafts remain usable without satisfying published snapshot minimum-selection requirements through fabricated content. | Empty/partial local content saves and reopens; optional context/workload/selection values remain unanswered where absent. Existing frozen schemas and fixtures are unchanged. | The local editor does not declare a valid published BuildSnapshot. Full UI receiving acceptance still needs the supported device journey; conversion and publication remain their separate contract work. |
| A corrupt record does not prevent opening other builds or disappear silently from the user's recovery view. | Per-record load failures remain visible; valid neighbors can open. Future-format, symlink and missing rows, scan limits and root replacement have explicit fail-closed tests in library/upgrade suites. | The native error path preserves unsupported bytes; it does not claim a repair/export implementation. Installed corrupt-record presentation, actual storage-full recovery and accessibility review remain open. |

The implementation steps are retained in full: transactional operations for
Drafts and retained immutable revisions; new duplicate identity with lineage and
no remote ownership transfer; durable current-build selection plus explicit
unreadable/migration-incompatible recovery; and accepted retention/deletion with
clear confirmation and accurate status. Current source supplies bounded local
operations and explicit error/cleanup states. The accepted wider retention policy,
artifact obligations and installed receiving observations have not been supplied
by the modal fix.

The issue's requested checks still include an empty and partial create/rename/
duplicate/reopen/delete journey through process recreation, comparing IDs,
revisions and source state, plus a corrupt record and a storage-full failure among
valid records. Injected IO failures and separate-JVM tests support those checks
but do not establish a device's ENOSPC, filesystem or power-loss result.

### Directory enumeration failures

The library translates `DirectoryIteratorException` from a root or pending-deletion
scan into its existing `IO_FAILURE` result. A partially enumerated directory does
not become an available list or authorize creation of a new identity. The stream
and library locks close before the caller receives the failure; retry uses a new
ordinary operation. The public constructor still uses `Files.newDirectoryStream`.

`PreviewDraftLibraryIterationTest` injects an I/O failure after one entry from an
actual open directory stream. Its eight cases cover both `hasNext` and `next`,
both directories, and listing and creation. They check typed failure, stream
closure, unchanged retained file hashes, saved identity/content, pending-deletion
visibility, and successful retry. The unchanged cases fail with the escaping
wrapper before the correction and pass afterward. Local direct Kotlin/JUnit
validation also passes the existing library, deletion, upgrade, pending-recovery
and cache suites: 61 cases total, with no failures or skips. This focused local
result does not replace the configured Gradle/native or installed receiving gates.

The existing three-argument internal JVM constructor remains available through a
delegating overload. The first configured native run in
[PR #485](https://github.com/Pick-Your-AI/pick-your-ai-android/pull/485) exposed its
missing descriptor: four of 300 cases failed with `NoSuchMethodException`; that
original failure is retained. A direct invocation of the restored descriptor
preserves both checkpoint callbacks, typed cleanup failure and ordinary retry.
The existing native cleanup tests and their configured gates remain unchanged.

## #76: Navigation and lifecycle

The preview is one Activity containing catalog, editor, comparison and library
controls. The existing saved-state checkpoint separates pending editing fields
from the durable baseline and local draft identity. It refuses ordinary saving
after a changed/uncertain baseline until explicit reconciliation. Catalog and
comparison identities retain exact versions/hashes, and retired callbacks cannot
replace current editor state.

The new incoming-entry correction prevents an unsupported target from silently
opening the prior local draft. Its explicit recovery uses the existing local
selection transaction; warm entries preserve pending context. It is a concrete
negative/recovery path within the larger navigation requirement.

| Exact acceptance criterion | Implemented source and observed check | Failure/recovery result and remaining evidence |
| --- | --- | --- |
| Stable navigation/routes, deep-link entry and back handling; restore correct screen and draft across rotation, backgrounding and process death. | Existing bounded editing/catalog/comparison saved state, retired callback guards and explicit unsupported incoming-entry handling. | Stable supported routes for all five named destinations are not implemented. Default platform Back and per-task `singleTop` are documented; actual external-entry/back/task-stack and installed process-death checks remain open. |
| Rotation and process restart restore the correct build and recoverable editing context. | Draft ID, pending title/context/workload, exact catalog selections and baseline checkpoint restore in actual Activity simulations; stale/uncertain state does not acquire save authority. | No actual installed rotation/process-kill success is inferred. Pending Bundle state is not an acknowledged durable revision and is not promised to survive arbitrary task removal. |
| External entry/back follows the documented path without duplicate editors; invalid targets cannot overwrite another build. | Seven incoming-entry cases cover cold rejection, warm preservation, envelope variants, blocked recreation and deliberate local recovery. Existing source-navigation tests require explicit reviewed outgoing-URL confirmation. | The manifest's `singleTop` applies only when the Activity is already atop the target task. Installed cold/warm/back behavior across tasks/caller flags still needs observation. Shared-build/deep-link routing, missing/deleted supported-route targets and remote authority remain unimplemented. |

All four implementation steps remain in scope: stable route identifiers for
catalog, builder, library, comparison and shared-entry states; back/up and external
entry separating transient screen state from durable draft identity; route/build/
editing restoration; and explicit missing/deleted/unsupported target behavior.
The requested catalog-picker-builder and shared-entry/back journeys, recreation,
termination and relaunch during editing must be exercised on their implemented
route model. An unsupported-entry fence does not stand in for that model.

## Prerequisites and installed chain

Both issues name [#67](https://github.com/Pick-Your-AI/pick-your-ai-android/issues/67)
as a prerequisite. Its named outputs include atomic durable draft/head storage,
all supported format migrations, preserved pins/settings and retention that does
not evict required retained/pending artifacts. Local formats 1–4, transactional
storage and explicit bounded failures provide substantial implementation evidence;
the full Android/storage/retention receiving gate remains open. The adopted
preview decisions in [REPOSITORY_SCOPE.md](REPOSITORY_SCOPE.md) permit this bounded
work without asserting product architecture, production package or device approval.

The existing installed controller is [#255](https://github.com/Pick-Your-AI/pick-your-ai-android/pull/255),
with its canonical #237/#206 chain. At this intake its head was
`83bb5c1491b8b08589b5cdc142e74ae9f6720dd2`. The latest
[run 39117620126](https://github.com/Pick-Your-AI/pick-your-ai-android/actions/runs/39117620126)
failed its first installed case; the other three were not run. The launch command
returned `Status: timeout`. Later framework diagnostics showed the preview
Activity drawn after 18.642 seconds and an independent `uiautomator` process
failing to connect `UiAutomation`. That later draw does not turn the failed launch
predicate into a pass. The [exact artifact review](https://github.com/Pick-Your-AI/pick-your-ai-android/pull/255#issuecomment-6248718583)
preserves the original launch failure, process IDs and bounded diagnostic scope.
No unchanged rerun or second controller was started by this audit.

The next receiving work must preserve that controller's canonical owner and exact
APK/run lineage while resolving the observed failure. After the installed harness
passes, the broader issue-specific library/recreation, route/back, storage-full
and accessibility cases still require their own receipts. Neither issue is
closed by this source audit or by the #277 merge.
