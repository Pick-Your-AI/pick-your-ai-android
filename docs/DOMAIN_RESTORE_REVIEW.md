# Reviewing a retained domain restore

Partial [Android #122](https://github.com/Pick-Your-AI/pick-your-ai-android/issues/122)
in the existing unsigned Android Views preview. The
[durable restore](LOCAL_DRAFT_STORAGE.md) already appends the selected retained
content as a new revision and preserves current metadata. This correction makes
the confirmation match the [typed component aggregate](TYPED_LOCAL_SELECTIONS.md)
that format 4 now retains and restores.

## Visible scope and callback authority

The confirmation reports current saved and retained selection counts separately,
and does the same for relationships. It explicitly says that catalog versions,
roles, enabled states, settings and recorded extra fields are restored as part of
the complete aggregate. Equal counts do not mean equal content. Counts are a
quantity disclosure, not a detailed content comparison or compatibility result.

The restore still includes objective, context, workload, the opaque primary-model
input and optional-tier configuration. Current saved title, guest sample pins and
opaque metadata remain current. On acknowledgement, restored domain inputs replace
their pending editor values; pending title/sample edits remain unsaved. A failed
or uncertain write retains the existing recovery behavior. No report, price,
artifact lock or compatibility result is recomputed under an old label.

A history callback must come from a showing dialog and the same current,
available, idle draft baseline. This applies before opening a history detail,
comparison picker or restore confirmation; copying retained guest fields; or
dispatching a restore. Cancelled or dismissed dialogs cannot later perform those
actions through retained callbacks. Activity destruction and stale-baseline guards
remain in force, and an accepted restore still uses the existing serial worker
and canonical transaction.

A retained revision with valid domain content can still open this review when its
old guest-form metadata is missing or unsupported. Copying that unavailable form
remains unavailable; no old title or sample fields are inferred. The restore keeps
the current valid metadata through the same canonical transaction. The unavailable
form dialog also loses its review action after dismissal.

## Required regression evidence

The workflow requires all existing Activity cases plus these three named cases;
missing, failed, errored or skipped execution remains a failure:

- domainRestoreDisclosesTypedComponentsAndPreservesHistoryAndMetadata seeds
  different retained/current typed selections and links with unavailable retained
  guest-form metadata, checks visible counts, replacement scope and dismissed
  review rejection, then performs a restore, recreation and subsequent edit.
  It reads the acknowledged head and old pinned artifacts to check exact content,
  current metadata, retained history and single-dispatch behavior.
- cancelledHistoryDialogsCannotReplaceFieldsOrReopenChildren retains cancelled
  history, detail and comparison callbacks and checks pending fields, the durable
  baseline and child-dialog state after the actual serial worker has drained.
- cancelledDomainRestoreCannotCommitOrReplacePendingInputs cancels confirmation,
  changes pending title/objective/workload, then invokes the old button. It checks
  no new revision or field replacement, including after Activity recreation.

The exact source, independent review and executed build/lint/test results belong
in the scoped PR receipt. These are simulated Android Activity cases on the JVM;
they do not establish installation, device process death or physical durability.
No persistence format, frozen contract, dependency, permission or saved-state
serialization change is part of this correction.

## Criterion coverage and remaining acceptance

| #122 criterion | Bounded contribution | Remaining evidence |
| --- | --- | --- |
| Predictable restore and no lost edits | Explicit typed restore scope; cancelled callbacks cannot act | Accepted product interaction, duplicate/autosave policy, installed recovery and named domain/UX receiving review |
| Restore creates a new head and preserves history | Actual Activity restore reads the new head and prior pinned artifacts | Device filesystem and interruption qualification |
| Saved status reflects durable data; failure preserves the prior revision | Successful acknowledgement uses the existing transaction; cancellation does not dispatch | Installed failure injection and the complete recovery journey |
| Recreation preserves committed edits without duplicate revisions | Simulated restore/recreate/edit and cancelled callback replay cases | Actual process termination, rapid-edit interruption and device evidence |

All criteria remain incomplete. The canonical receiving record is still
docs/delivery/android/work-packages/08.06.md in Pick-Your-AI/pickyour.ai.
This implementation note does not relocate that record, assign an owner, grant
release authority or complete the work package.
