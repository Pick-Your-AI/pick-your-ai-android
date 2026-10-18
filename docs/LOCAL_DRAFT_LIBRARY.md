# Local guest draft library

The native preview now supports multiple local guest drafts through
`PreviewDraftLibrary`. This implements the local library workflow in Android
[#86](https://github.com/Pick-Your-AI/pick-your-ai-android/issues/86) and extends
the existing [durable revision store](LOCAL_DRAFT_STORAGE.md). The library calls
that store for every draft edit; it introduces no shared Draft contract, account,
remote ownership, catalog promotion or network service.

## User behavior

Save on an unselected form creates and selects a new local draft. Create new draft
asks before replacing pending edits and durably creates an empty record. The
library lists every saved, unreadable, recoverable and pending-deletion record
with its local ID. Open asks before replacing the form. Rename by changing the
opened title and saving. Every saved record retains its existing revision history.

Duplicate asks before copying the saved configuration and opening the new local
identity. Exact guest sample ID, version and catalog digest remain pinned. The
copy records its source local ID, revision and envelope hash, preserves local domain
content and starts a new history. It does not copy unknown metadata or remote
ownership. The source and its entire history remain unchanged. Duplicating never
turns an unavailable sample into a current one or creates a compatibility verdict.

Delete asks for explicit confirmation and removes the selected record's local
history. Deleting another row preserves the currently edited form. A stale token
cannot delete a newer saved record; an unreadable row can be explicitly deleted
only while it remains unreadable. Deletion does not revoke external copies and
makes no forensic-erasure promise. There is no cloud synchronization or account
deletion claim.

## Storage and recovery

The Android adapter provisions `noBackupFilesDir/guest-library`. A small private
versioned index stores only local IDs, selected ID, a migration marker and deletion
tombstones. Each UUID names its own existing `LocalDraftStore` under `records/`.
The index has strict fields, bounded input, duplicate-key rejection and explicit
unsupported-version failure. It never contains titles, workloads or component facts.

All cooperating library readers and writers take the same nonblocking process and
JVM lock before accessing records or the index. Edits retain the canonical store's
expected-directory/revision/hash comparison. A newer selected draft cannot silently
give an old screen save authority: recreation binds the local ID as well as the
revision/hash and reconciliation flag. Listing the library does not adopt a newer
edit token or replace pending fields.

New record bytes are durably saved first, then one atomic index update registers
and selects the record. Interrupted creation can therefore leave an unregistered
directory. It stays visible as recoverable, with no saved-library acknowledgement;
the user explicitly recovers or deletes it. Recovery requires a readable completed
record and never invents missing contents.

Deletion atomically removes the active ID and clears the selection when applicable,
while recording the ID as deletion-pending. Only then does it remove that record's
bytes and clear the tombstone. Failed/uncertain cleanup remains visible and
retryable. Stale writers cannot reopen or save a tombstoned record. A successful
deletion has removed its record directory; unrelated record bytes are untouched.

An interrupted index activation or post-write failure returns uncertain. The UI
keeps visible edits, disables further saving and asks the user to inspect/reopen
the actual disk state. Failures never silently retry with a newer save token.
One record's missing, corrupt or unsupported payload remains a visible unavailable
row and does not prevent other records from opening. A damaged library index fails
closed as a library error; it is retained for repair rather than silently rebuilt.

There are at most **32 local record directories**, including unregistered and
pending-deletion records. Index bytes are limited to 16 KiB. Each draft retains
the canonical 128-entry history and 1 MiB envelope limits. No artifact is evicted
to make room. This is a record-count bound, not a total disk-space quota; the
existing cache can retain failed/unacknowledged immutable artifacts. Deletion
preflights at most 512 filesystem entries and four levels, without following
symbolic links; unexpectedly larger/deeper data remains pending for repair.
Continuous production retention still requires its separate policy under #67.

## Migration of the earlier single draft

The first library access checks the prior `noBackupFilesDir/guest-draft`. It moves
the complete directory atomically to a reserved local ID and registers it without
rewriting any canonical envelope, catalog pin or immutable history bytes. Parent
directories are synchronized before success. Interruption after the move is
recognized using the reserved ID and completed on reopen. A collision, symlink,
unsupported record or damaged index is not reset. The migration marker prevents
an explicitly deleted legacy record from being recreated on a later access.

## Verification and remaining acceptance

Thirteen new JVM regressions exercise the actual filesystem and canonical store:
complete create/rename/duplicate/open/delete sequences across fresh library
instances; exact lineage/pin preservation; unknown-metadata separation; stale and
foreign token rejection; corruption isolation and explicit deletion; unregistered
create recovery; pre/post-index-activation failures; deletion tombstones and retry;
legacy history migration and interruption; 32-record bounds; malformed/future/large
index rejection; identity-bound screen restoration; and cross-instance contention.

At this change, the local JVM suite passes **169 tests** with zero failures/errors.
The frozen 43-file import and both manifests remain unchanged; its 21 reference
tests, four tamper tests and six delivery-ledger tests pass. Reproduce with the
documented pinned JDK21 and Gradle8.11.1 toolchain:

```sh
./gradlew --no-daemon :core:check :core:assemble
python scripts/check_contracts.py
python -m unittest discover -s tests -v
python scripts/check_delivery_ledger.py
python -m unittest discover -s scripts/tests -p 'test_delivery_ledger.py'
./gradlew --no-daemon -PandroidPreview=true :preview:assembleDebug :preview:lintDebug
```

Local unsigned native assembly and lint pass with the same pinned toolchain
(49 tasks; zero lint issues). The SDK archives match Google's published checksums.
Existing insets deprecation messages and absent platform-tools are environment/build
warnings, not installation evidence. Hosted final-head verification is recorded in the PR.
Emulator/physical-device installation, forced-process-death, power-loss and manual
TalkBack/large-text/RTL observations remain unexecuted. Passing JVM tests cannot
establish those results. Check new/open/rename/duplicate/delete, every confirmation
cancel, rotation during each worker operation, migration from the preceding APK,
and unreadable/pending-deletion rows on an installed build before receiving
acceptance. There is no `/dev/kvm` in the current workspace.

| Exact #86 criterion | Implemented evidence | Remaining acceptance |
| --- | --- | --- |
| Create, rename, duplicate, list, open and delete local drafts/revisions with durable storage and recovery. | Native controls and canonical store/index transactions; saved histories stay attached to each local ID | Installed end-to-end and receiving review; this is the current guest form, not full BuildSnapshot conversion |
| Library operations survive process recreation, and duplicate/delete actions affect only the selected local build. | Reopened JVM instances, lineage, CAS, tombstone recovery, identity-bound restoration and preserved source bytes | Actual Android process recreation/termination and filesystem qualification |
| Empty and partial drafts remain usable without satisfying published snapshot minimum-selection requirements through fabricated content. | Empty creation and opaque unavailable selections persist without adding catalog facts or selections | Installed UI validation and integration with the eventual full editor |
| A corrupt record does not prevent opening other builds or disappear silently from the user's recovery view. | Corrupt-row isolation, readable siblings, explicit recover/delete UI and adversarial tests | Installed recovery-view/accessibility evidence and receiving acceptance |

The canonical receiving record remains
`docs/delivery/android/work-packages/11.01.md` in `Pick-Your-AI/pickyour.ai`.
This implementation does not relocate it or close #86/#67/#76/#122 by assertion.

Primary API references checked 2026-10-18:
[Android app-specific storage](https://developer.android.com/training/data-storage/app-specific),
[Android storage overview](https://developer.android.com/training/data-storage/),
and [Java atomic move semantics](https://docs.oracle.com/javase/tutorial/essential/io/move.html).
The implementation uses these platform primitives but still requires observed
device/filesystem durability evidence.
