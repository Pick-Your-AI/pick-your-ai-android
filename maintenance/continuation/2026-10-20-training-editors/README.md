# Training editor continuation — 20 October 2026

Status: blocked by an offline local execution environment. This branch is a restart checkpoint, not a product change, PR, merge approval, test result, or complete backup.

## What is preserved

- The two exact, previously reviewed fine-tuned-model Core source/test inputs under inputs/, retained as .kt.txt files. Their original byte counts, SHA-256 hashes and Git blob identities were independently checked after GitHub readback.
- The exact unexecuted fine-tuned-model recovery correction proposal.
- The exact unexecuted final composition proposal.
- Source identities, completed deliveries, ownership, and remaining gates in handoff.json.
- A bounded original-log check for Android PR #751's installed job. Its ZIP contents have not been inspected.
- The completed proposal-only independent review in proposal-review.json, including the remaining conditional direct-read fixture-shape check.

The proposal files deliberately end in .py.txt. They depend on existing files under /tmp and must not be treated as runnable, validated scripts. In particular, the composition proposal still asserts the initial, uncleared fine-model tree 2250b369adf5533ab579b2debae30c7d12560969. Replace that expected identity only after applying the correction, regenerating the source receipt, and completing independent native review.

## What is not preserved by this branch

The offline local dataset, framework, combined dataset/framework, and fine-model native/shared source candidates; local test archives and qualification receipts; helper scripts not included here; and downloaded artifact bytes. Their recorded local paths and hashes identify work that existed before disconnection but are not a promise that those files will remain available. Only the two Core files explicitly listed above are backed up as source inputs here. The complete candidate tree IDs are calculated local identities. Exact reads of the fine-model and pair tree IDs returned GitHub 404 on 20 October.

The training-compute source already exists in PR #751. Evaluation PR #750 is already merged. This branch starts from the accepted evaluation commit and adds only these checkpoint files.

## Resume sequence

1. Reconnect local execution and inspect the existing task directories without deleting other work. Verify every input against its recorded receipt. Do not treat a recorded path as proof that a file exists.
2. Complete #751 installed original ZIP qualification using the retained original download reference when available. Verify its exact size and SHA-256 before inspecting safe archive members and linked receipts. The original log and job metadata alone are insufficient.
3. Apply the fine-model correction to the exact initial 2250 candidate. It must use the existing ComponentFormCheckpoint.captureNewSettings public API and preserve that API's actual outcomes. Do not infer or change the underlying recovery policy.
4. Regenerate the two attendance guards and the fine-model source receipt. Independently review the actual changed bytes, all entry points, the complete Parcel and Activity tests, the documentation, and insertion inverses. For the manually reconstructed incoming Bundle test, verify that the input is otherwise structurally valid so it actually exercises the new recovery guard; any optional-string presence flags must match if such flags exist. The follow-up reviewer did not retain the literal key list, so this is a conditional check, not a confirmed defect. Expected declared cases are 20 Core, 18 Activity, 8 Parcel and 10 legacy.
5. Update the composition proposal's expected fine-model tree to the reviewed corrected tree. Recheck current main before composing the four training editors with accepted evaluation source. The prepared proposal assumes af6abfc95ab227ac005a82488a6b4762f0ba4f6e. At 15:08:48 UTC, current main was observed at 6ebb0e4a30529afc82abb84a5ad741ec1557235f, its signed direct child. That later main change must be included and independently reviewed before publication.
6. Verify the intended source changes (the historical af6 proposal expected 36 paths, 32 additions and four shared edits), all actual base leaves and frozen contracts, exact workflow attendance, the guides, and cross-reader behavior. Recompute the source and attendance counts against the actual receiving base. Independently review the final complete tree.
7. Use ordinary protected PR delivery and fresh natural CI. Keep signed staging acceptance separate from main acceptance. Check the exact head, source tree, required reviews and original CI evidence before merging; never bypass protection.
8. Verify the accepted main commit and sync the exact accepted source. Close an issue only when all of its actual acceptance criteria are met.

## Important boundaries

The known fine-model candidate is not cleared for merge. Its preserved Core pair completed independent source review; it has not been newly edited or executed by this checkpoint. The native recovery correction is only a proposal. Predicted test totals are not executed test results. Existing installed smoke cases do not establish new-editor process-death behavior, physical-device acceptance, accessibility acceptance, real training/evaluation, or release readiness.

This checkpoint does not reserve main or supersede another active owner's work. No external Codex task is part of this continuation.
