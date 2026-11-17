# Typed deployment-target settings

This slice contributes to [#99](https://github.com/Pick-Your-AI/pick-your-ai-android/issues/99)
and the local planning controls in [#52](https://github.com/Pick-Your-AI/pick-your-ai-android/issues/52).
Fresh **Edit settings** for an existing `deployment_target` Selection offers the
five fields already defined by the frozen v1 Selection contract. Add retains its
explicit raw-settings and role path. No contract, storage format, role or
cardinality rule changes.

## Exact optional fields

| Field | Frozen type and constraint | Explicit control |
| --- | --- | --- |
| `environment` | Optional string: `development`, `staging` or `production` | Checked choice with a separate **Not supplied** entry |
| `replicas_min` | Optional integer, at least zero | Supply checkbox and exact numeric text |
| `replicas_max` | Optional integer, at least one | Supply checkbox and exact numeric text |
| `region` | Optional literal string | Supply checkbox and multiline text |
| `approval_ticket` | Optional literal string | Supply checkbox and multiline text |

An absent value stays absent until supplied. Zero minimum replicas and an empty
Region or Approval ticket reference remain distinct from absence. Selecting
**Not supplied** or clearing a Supply checkbox removes just that property; it
does not provide a default. The environment choices come from the frozen field
metadata. Choosing or dismissing a picker does not save the Selection.

The two replica values use the existing bounded exact-number reader. The editor
accepts mathematical JSON integers, including admitted decimal or exponent
spellings, without floating-point conversion or a machine-integer ceiling.
Untouched admitted numeric nodes keep their original type and decimal scale.
Fractional values, locale separators, non-numbers, injected fields, duplicate
keys and trailing documents are rejected. A supplied blank numeric field needs
correction rather than silently becoming absent. The contract specifies separate
minimums; this editor adds no `replicas_min <= replicas_max` rule or capacity cap.

Region and Approval ticket reference remain exact literal strings, including
supplied empty text, whitespace, Unicode and values such as `unknown`. The
editor does not trim, normalize, resolve links or infer a supported region.
The ticket is a private reference, not an approval or permission check. The form
asks for a reference and does not invite credentials. The frozen string fields
are not redefined as URI or secret-reference fields.

Each field is prepared independently before the complete updated settings pass
through `PreviewSelectionForms.configure` against the original Selection. There
is no whole-original-Selection field probe that can reject an otherwise valid
simultaneous sibling edit. The existing per-field/settings text budget, strict
Unicode encoding, aggregate escaped-settings bound, full Selection reader/schema
and sensitive-input policy still govern the final command. A rejected field or
final payload cannot publish a partial command. Selection identity, catalog pins,
role, enabled state, notes, extensions, links and unrelated draft content remain
unchanged.

## Foreground authority and private recovery

The form uses the existing clean acknowledged editor context, one visible owner,
foreground and catalog checks, bounded worker, original revision/hash and managed
CAS. Cancellation retires the original controls. Pause, a changed saved head,
changed catalog identity or uncertainty cannot give an old form fresh authority.
An admitted in-flight write has its own acknowledgment; fields, choice, Supply
checkboxes and cancellation freeze while it runs. Invalid input remains
correctable in the current form, without acknowledging a save.

The versioned private checkpoint uses the namespaced string
`typed-deployment-target/1`, the exact form/kind tag, original draft/baseline/
Selection/catalog identities, all five nullable input strings and a Boolean
`wasSaving`. Closed primitive keys prevent coercion or a missing nullable key
from becoming omission. Only an actual frozen environment choice or null can
reconstruct the checked picker. Ordinary invalid numeric text remains available
for correction.

The aggregate pending-input budget is 20,000 UTF-16 units across all five fields.
Oversized or invalid-shaped checkpoints keep only the existing unavailable
marker; they do not truncate a prefix or fall back to automatic View-state
saving. Each identity retains its existing bounded length. Checkpoint text and
input/result descriptions do not become a command, consent or replacement save
token. Ordinary field and checkpoint diagnostics do not echo entered values.

After recreation, matching original identities and `!wasSaving` allow explicit
review in fresh controls followed by a new confirmation. Otherwise the pending
values are read-only until explicit discard. Read-only recovery labels supplied
literal strings separately so an empty reference is not confused with an absent
one. Legacy raw Add/Configure checkpoints keep their original reader and raw
correction form. Compute, model-provider and every other existing recovery
protocol remain distinct.

Text controls request no dictionary suggestions or personalized keyboard
learning, disable autofill and automatic View-state saving, and keep multiline
input literal. A short dialog title and full selectable identity in the existing
scrolling content retain narrow-screen access. These requests and simulated
layout assertions do not qualify an installed keyboard, TalkBack, process death
or a physical device.

## Review and validation scope

The Core suite defines fifteen cases covering frozen metadata, exact optional
values and wide integers, invalid input, unchanged numeric nodes, whole-payload
rejection, immutable identity, managed no-op/conflict/deletion, real store
reopen/history/private metadata and an explicit frozen Build projection. The
checkpoint suite defines eight Parcel/shape/budget/protocol cases. Sixteen
Activity cases exercise actual controls and the canonical local store, including
checked choice, invalid correction, cancellation, no-op, pending recovery,
changed identities, in-flight and concurrent writes, pause, legacy recovery and
320dp RTL layout at 2x text. Existing operational tests retain their raw Add
negative inputs and no-op/role/replacement assertions, while fresh deployment
Configure checks the five typed values.

Named workflow guards require these actual test methods and reject missing,
repeated, failed, errored, skipped or inconsistent reports. The suites and guards
define required validation; their presence alone is not a passing execution
receipt. Current configured Core, Preview, scanner and applicable Installed jobs
remain independently qualified at their actual source identities.

## Remaining scope

Successful confirmation means local persistence only. Deployment-mode
applicability, provisioning, rollout, access and approval verification, capacity,
measured readiness, live monitoring and billing behavior remain separate. The
shared unconditional T6 guidance remains visible, and existing per-kind
operational guidance is retained in the form. This slice does not complete the
whole #99 or #52 acceptance criteria or change the frozen research contract.

## Explicit control sizes after the first hosted layout result

The first source-stage Android job for #733 failed the existing visible-button
minimum-height assertion in
`narrowRtlLargeTextKeepsIdentityFieldsGuidanceAndConfirmationReachable`.
The original assertion did not name the button or record its measured size;
it does not establish which individual control caused the failure.

The deployment editor now gives its environment button and all four Supply
checkboxes explicit minimum width and height of 48dp, rounded up to whole pixels
and preserving any larger existing minimum. Text length and inherited theme
dimensions no longer determine whether these controls receive that minimum.
The existing scrolling layout, field values, presence controls, callbacks and
confirmation authority are unchanged.

The same regression retains every dimension, ancestor-containment, complete-text,
ellipsis and final-save assertion. Dimension failures now identify the widget,
label and measured size. The original failed result remains separate; a fresh
configured Android result is required for this correction. These simulated
checks do not establish installed or physical-device accessibility.
