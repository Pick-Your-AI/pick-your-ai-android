# Criterion evidence ledger

[criterion-evidence.json](criterion-evidence.json) indexes all 129 Android issues
and their 441 acceptance criteria, copied verbatim from a live GitHub read on
2026-10-18. This is a living evidence index, not another issue inventory, a native
dependency graph, receiving acceptance, or a replacement for GitHub status.
The earlier [issue audit](../proposals/issues-snapshot.md) remains a historical
snapshot; it is not rewritten to imply it observed later implementation.

Every criterion is **incomplete**. References to merged PRs #131–#139 identify
partial issue context, not an assertion that a linked artifact passes each
criterion. PRs #138 and #139 are now merged at their recorded merge revisions;
PR #140 remains a draft at its inspected head. Its proposed work is explicitly
distinguished from merged evidence. Exact
artifact revisions and limitations are in the top-level `evidence` catalog.
No JVM, unsigned APK, lint or fixture result proves installed-device, production,
source rights, store distribution or operational acceptance.

Selected criteria additionally have `partial_results` with precise supported
behavior, pinned evidence references and reproduction commands. Run commands from
the relevant pinned implementation checkout with its documented toolchain; this
ledger refresh itself did not rerun those application tests. Context references
remain broader than criterion-specific results, and neither implies completion.

Each criterion retains its original text, stable issue/ordinal ID, digest,
remaining engineering work and any concrete external prerequisites. An empty
external-prerequisite list means no external gate was identified for that exact
criterion in this audit; it does not waive future integration review. Explicit
external inputs include actual source rights/evidence, accepted contract changes,
owner commitments, device/user observations, publisher/domain/signing access and
release authority. Ordinary unfinished code is not labeled blocked. Conditional
features have issue-level scope notes and remain incomplete, not silently waived.
These classifications are review aids, not an assertion that the complete native
prerequisite graph has been resolved.

The `canonical_acceptance_record` fields preserve all original paths in
`Pick-Your-AI/pickyour.ai`. They name intended records and do not assert those files
exist. Parent coordination must reconcile accepted evidence there without moving
those records or assigning owners. In particular, Android #35 still needs one
shared-service placement coordinated with website #17 and Apple #23; local
engineering decisions must not invent an operator or competing backend.

## Validate and refresh

From the repository root, perform an offline integrity check:

```sh
python scripts/check_delivery_ledger.py
```

To verify exact criteria and acceptance paths against a fresh read (requires an
already-authorized GitHub session):

```sh
gh issue list --repo Pick-Your-AI/pick-your-ai-android --state open --limit 200 \
  --json number,title,body,assignees > /tmp/android-ledger-issues.json
python scripts/check_delivery_ledger.py --issues /tmp/android-ledger-issues.json
python -m unittest discover -s scripts/tests -p 'test_delivery_ledger.py'
```

The offline check explicitly reports that no live comparison occurred. A changed
issue inventory, criterion, order or path fails the live check and needs deliberate
reconciliation; the script never changes GitHub. Update digests only after reading
the changed criterion, not to suppress a discrepancy. If criteria are reordered,
reconcile their old ordinal references before recording new ones.

Refresh PR state and revision explicitly when evidence advances. Do not replace a
pinned artifact with a mutable branch link or turn a proposed test into a reported
pass. Add exact test commands/results, recovery cases and receiving dispositions
before narrowing a criterion's remaining work. This version intentionally rejects
completion claims: any future completion-support change needs independent review
and criterion-level receipts, rather than merely switching a status field.
