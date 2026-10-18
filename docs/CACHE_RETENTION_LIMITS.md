# Explicit artifact retention budgets

`VerifiedArtifactCache` now accepts `CacheRetentionLimits(maxArtifacts,
maxRetainedBytes)`. `LocalDraftStore` accepts the same policy and maps a refused
new revision to its existing `LIMIT_EXCEEDED` outcome. These optional constructors
add enforcement for the configured retention portions of Android
[#67](https://github.com/Pick-Your-AI/pick-your-ai-android/issues/67) and
[#87](https://github.com/Pick-Your-AI/pick-your-ai-android/issues/87).

The limits count immutable files named by the cache's exact lowercase SHA-256
artifact convention, including payloads left after interrupted publication.
The cache reserves the incoming payload's bytes and identity while holding the
same process/JVM locks used for publication. A new artifact that would exceed
either budget returns `CacheInstall.Failed(RETENTION_LIMIT)` before its immutable
file or active pointer is published. The staged input is cleaned up through the
existing failure path. Exact-budget writes are allowed; empty payloads still
consume one identity. Size accounting uses subtraction before addition to avoid
overflow.

No active artifact or historical pin is evicted. Reads and reactivation of an
already retained, hash-verified artifact remain available at quota, including
when a newly configured budget is below an existing directory's usage. A changed
draft save or restore can fail at quota, preserving the current head and old
revisions. An unchanged draft save still returns its existing acknowledged
revision without allocating another artifact. Increasing a budget is an explicit
caller decision; it never happens automatically.

All cooperating writers to a directory must use the same agreed policy. The
original generic constructors retain their existing unbounded behavior. The
implementation does not persist a policy, rewrite existing artifacts, garbage
collect, choose which pins are disposable, or supply a cross-client retention
policy.

## Bounded preview adoption

The preview library now supplies a policy whenever it creates or opens a draft:
128 managed artifacts and 128 MiB of managed payloads per draft. These limits are
derived from `LocalDraftStore.MAX_HISTORY` (128 entries) and `MAX_BYTES` (1 MiB
per envelope), rather than a new product support commitment. The full supported
history can therefore retain one maximum-size envelope per entry. Unactivated
payloads consume the same capacity; no failed-publication file is silently freed.

Ordinary preview saves and restores return the existing `LIMIT_EXCEEDED` failure
when new payloads cannot fit. The visible status explicitly names the history,
record or storage limit, says that pending edits are unsaved, and preserves prior
revisions. Reads and unchanged saves still work. The existing explicit pending
recovery action can create a separate draft with its own budget while retaining
the full source. This is a limit on each draft's revision cache, not the entire
application directory or an assurance that the device has 128 MiB free.

This engineering policy applies to the bounded synthetic preview. Existing
over-budget directories remain readable and are never trimmed during an upgrade;
changed writes fail until an explicit recovery or policy decision is made. Final
product quotas, backup policy and physical-device qualification remain separate.

## Accounting and validation limits

The byte budget covers managed immutable payloads. It is not a total filesystem
quota: it excludes filesystem metadata, allocation-unit overhead, lock/pointer
files, unrelated files and at most one bounded staged candidate. Existing
over-budget data is preserved. Retention inspection neither verifies every
artifact hash nor proves publisher authenticity; active/pinned reads and repeated
activation retain their existing hash checks. A managed artifact represented by
a symlink or directory is rejected without following it. Unrelated files are
preserved and not charged to this cache's payload budget.

The source tests exercise exact byte and artifact limits, reopening, empty
payloads, an existing over-budget directory, failed preactivation publication,
corrupt repeated content, symlink rejection, caller stream ownership, competing
writers and local save/restore behavior:

```sh
./gradlew --no-daemon :core:test \
  --tests ai.pickyour.core.cache.VerifiedArtifactCacheRetentionTest \
  --tests ai.pickyour.core.drafts.LocalDraftRetentionTest \
  --tests ai.pickyour.core.preview.PreviewDraftRetentionTest
./gradlew --no-daemon :core:check :core:assemble
```

The actual preview-library tests additionally cover a full 128-entry history,
rejected writes after unactivated payloads exhaust the artifact budget, unchanged
save acknowledgment and explicit recovery into a separate draft. Both over-budget
write assertions failed before the preview policy was connected: the old library
accepted another artifact after 128 payloads were already retained.

These are local JVM/filesystem checks. Android filesystem power-loss behavior,
an accepted product quota, installed recovery UI, pinned-reference-aware cleanup and
production catalog integration remain separate acceptance requirements. The
canonical records remain `docs/delivery/android/work-packages/12.01.md` and
`12.03.md` in `Pick-Your-AI/pickyour.ai`; this document is a partial engineering
handoff, not a replacement acceptance record or an issue-closing receipt.
