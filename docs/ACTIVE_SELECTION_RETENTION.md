# Bounded last-opened selection metadata

This is a narrow retention correction for Android
[#67](https://github.com/Pick-Your-AI/pick-your-ai-android/issues/67), with related
bounded-work requirements in
[#87](https://github.com/Pick-Your-AI/pick-your-ai-android/issues/87). It applies only
to the private `.active-draft` directory used by `PreviewActiveDraftStore`.
Its v1 payload contains a local draft ID. It is never a saved revision, catalog
artifact, pending input or authority to write a draft.

## Retained values and bounds

An ordinary explicit selection retains the currently addressed payload and the
incoming payload. Once a distinct selection succeeds, these are the new current
value and its immediate predecessor: at most two payloads, each limited to 128
bytes. A later selection reclaims older managed payloads. Reselecting the same ID
can compact an ordinary directory to one payload. Repeated creation, selection
and deletion therefore do not exhaust an ever-growing metadata quota.

The current ID remains recorded even if its draft has been explicitly deleted.
Reading that ID still reports the selected ID; the library and editor keep their
existing unavailable-draft recovery behavior. Retention never silently chooses
the guest draft or another live entry. Reads do not reclaim or migrate files.

Legacy directories can contain more than two payloads. Each explicit selection
examines at most 256 directory entries, counting foreign files and wrong-type
entries in the same budget, and removes at most the obsolete regular payloads
found in that one batch. Normal legacy accumulation is reduced incrementally by
later selections, including same-ID reselection. An incomplete scan that cannot
remove anything refuses a new payload with `RETENTION_LIMIT`; it can still
reactivate an already retained verified payload. This avoids adding more files
when a foreign-filled directory prevents bounded progress.

Only exact lowercase SHA-256 `.bin` names are candidates. The current and incoming
names are protected. A candidate represented by a symlink or directory fails as
`CORRUPT` without following or removing it. Unrelated entries remain untouched;
this operation neither recurses into them nor imposes a whole-filesystem quota.
Legacy data and corrupted directories are not promised an immediate two-file
bound. Filesystem allocation overhead, foreign files and the cache's fixed
pointer, lock and staging names are separate from the payload bound.

## Recovery and durability order

The existing non-reentrant canonical-root guard and exclusive OS cache lock cover
verification, cleanup and publication. All cooperating selection writers use the
same private operation. The successful path is:

1. Stage and force the bounded incoming bytes, and verify their expected hash.
   An existing incoming payload must independently pass its hash check; damaged
   immutable bytes are never overwritten to repair them silently.
2. Read and verify the current pointer and payload. Force the cache directory
   **before any reclamation**. A pointer from an earlier uncertain activation may
   be visible without having been durably synchronized; this step establishes its
   authority before its predecessor can be removed.
3. Reclaim one bounded batch while protecting the current and incoming hashes,
   then force directory metadata for those removals before admitting a new file.
4. Publish and synchronize the incoming payload, stage and force its pointer,
   atomically replace the pointer and force the directory before acknowledgment.
   No payload reclamation runs after activation.

A pre-activation failure preserves the prior active pointer and its payload;
an earlier valid selection remains available.
Some already superseded payloads may have been reclaimed before a cleanup error;
they are not saved build history. A failure after activation was attempted remains
`Uncertain`, preserves the former active payload and requires verified reopening.
A later explicit selection must confirm the observed pointer's directory
durability before that predecessor becomes eligible for cleanup. Cancellation
keeps the thread's interrupt flag and the existing typed failure distinction.

If the existing pointer or its payload cannot be verified, it grants no cleanup
authority. The operation skips reclamation and preserves the pre-existing bytes,
while retaining the earlier supported recovery path: an explicit verified
selection may atomically replace the invalid pointer. After successful recovery,
a later selection can compact those old payloads in bounded batches. Reads never
repair malformed, future-version or unavailable values by inventing a selection.

## Scope and verification

The cache's internal `installCurrent` operation is used only for last-opened
metadata. Ordinary `install` and `pinned` calls keep their original retention
contract. [Draft revision budgets](CACHE_RETENTION_LIMITS.md), all retained draft
history and catalog/input pins remain intact. No stored format, lock name, public
cache API, network worker, UI recovery action or shared contract changes.

The public regression creates one retained draft, then executes 72 further
create/select/delete cycles. It checks the latest deleted selection after each
cycle and the retained draft's complete byte snapshot. On unchanged accepted
main `ee3f64f90b1da38bdbda1d4e8d5c561477881254`, the exact final test failed with
73 metadata payloads where the constant bound requires two. This reproduced an
actual storage-growth defect even though the live library held only one draft.

Focused recovery cases cover 1,024 legacy payloads, read-only reopen, same-ID
compaction, bounded refusal around foreign entries, interrupted and cancelled
cleanup, failures before activation, chained uncertain activations, invalid
pointer and payload recovery, immutable incoming corruption, symlink/directory
rejection and lock contention within the same JVM and a separate JVM. Existing cache and revision
tests retain their pinned-artifact obligations.

```sh
./gradlew --no-daemon --dependency-verification strict :core:test \
  --tests ai.pickyour.core.preview.PreviewActiveMetadataRetentionTest \
  --tests ai.pickyour.core.preview.PreviewActiveMetadataRecoveryTest
./gradlew --no-daemon --dependency-verification strict :core:check :core:assemble
```

These are JVM filesystem and injected-failure checks. They do not establish
installed Android process termination, filesystem power-loss behavior, device
qualification, production catalog refresh or all storage/synchronization
acceptance. Current hosted preview build/lint/simulation and independent review
remain required before delivery. The canonical receiving records remain
`docs/delivery/android/work-packages/12.01.md` and `12.03.md` in
`Pick-Your-AI/pickyour.ai`; this scoped engineering record does not relocate or
close those work packages.
