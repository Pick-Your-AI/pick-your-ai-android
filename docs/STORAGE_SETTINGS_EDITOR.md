# Typed storage settings in the Android preview

The local preview offers a typed **Configure** form for an existing `storage`
selection. It edits the three optional settings already declared by the frozen
Selection schema through the existing [local selection controls](LOCAL_SELECTION_CONTROLS.md)
and managed revision path. This is a bounded contribution to
[#99](https://github.com/Pick-Your-AI/pick-your-ai-android/issues/99).

This implementation guide keeps the canonical acceptance record at
`docs/delivery/android/work-packages/08.04.md` in `Pick-Your-AI/pickyour.ai`, as
determined by the canonical prompt and coverage entry. The broader issue criteria,
#27/#83 prerequisite handoffs and accountable assignment remain separate. The
editor records planning inputs; it does not measure available capacity, provision
storage, resolve a key, verify encryption or establish release acceptance.

## Frozen fields and explicit supply

`PreviewStorageSettings.fields` reads the existing `PreviewSettingsMetadata`.
Each field has a labelled input and a **Supply** checkbox. Clearing the checkbox
omits that property. No field is supplied automatically.

| Frozen field | Admitted value | Input behavior |
| --- | --- | --- |
| `provisioned_gb` | An exact JSON number at least zero; no schema maximum | Capacity is labelled in GB. Optional absence, supplied zero and a supplied blank requiring correction remain distinct. |
| `region` | Any string admitted by the whole Selection receiver | Empty, whitespace, Unicode, line breaks and literal `unknown` remain literal text, without trimming, lookup or case conversion. |
| `encryption_key_ref` | A string admitted by `^secret://[A-Za-z0-9/_:.-]+$` under the frozen receiver's actual semantics | Enter an opaque reference. The editor does not retrieve credentials, keys or account information. |

Omission is a missing property. It is never converted into JSON `null`, zero or
an empty string. A supplied empty region remains a string; an empty capacity or
key reference needs correction. Wrong JSON types, undeclared keys and aliases
remain invalid under the whole Selection receiver.

## Exact capacity and bounded representation

Capacity uses strict bounded JSON parsing rather than conversion through `Int`,
`Long`, `Float` or `Double`. Exact integers beyond machine precision and exact
decimal fractions remain supported. Valid JSON exponent notation and surrounding
JSON whitespace are admitted; locale commas, quoted numbers, non-finite values,
leading plus signs, injected sibling properties, duplicate keys and trailing
documents are rejected. A negative value is invalid. The original admitted
numeric node is retained when its displayed text has not changed, so editing
another field preserves its node type, scale and representation. Edited numeric
input passes through the existing canonical JSON representation.

The shared reader limits a number token to 128 characters and its decimal scale
to the inclusive range -10,000 through 10,000. These are local representation
bounds, not a maximum storage capacity. Per-field input is bounded before parsing.
After field validation, the complete serialized settings object must pass
`PreviewSelectionForms.configure`: at most **16,384 UTF-16 code units**, strict
UTF-8 encoding and the **65,536-byte** bounded JSON receiver. Property names,
quotes and JSON escapes consume that same whole-object budget. Individually
admissible fields cannot bypass the aggregate limit. A failure yields no partial
command; aggregate rejection uses the existing form-level invalid status.

The opaque-reference helper preserves the receiver's `Pattern.UNIX_LINES` and
`find()` behavior, including its admission of one final LF after an otherwise
matching reference. It retains exact case and punctuation without trimming.
Leading whitespace, trailing CR or CRLF, repeated LF, invalid punctuation and raw
credentials are rejected. Region text has no reference-pattern rule: a URI-like
string or a word resembling a status remains ordinary declared region text.

## Acknowledged persistence

Preparation starts with the original immutable Selection and preserves its ID,
catalog item/version, role, enabled state, notes and extensions. The existing
command path preserves unrelated selections, relationships, workload/objective
content and private draft metadata. The Activity submits the prepared command
through the existing worker and managed original-version compare-and-swap path.

Opening a form, typing, preparing a command or cancelling writes no acknowledged
content. Committing unchanged settings is a no-op. A stale baseline cannot
overwrite another writer, and a removed selection cannot be recreated by an old
form. Complete Selection admission and the managed store continue to decide
whether a command may be saved.

## Private pending input and explicit recovery

The private protocol uses string version **`typed-storage/1`**, form `storage`
and kind `storage`. It retains five original identities, three nullable raw input
strings and the Boolean `wasSaving` state. Its decoder requires exactly those
keys and primitive types; missing keys and values of another type are rejected.

Draft ID, saved baseline, selection ID and catalog item version each have a
1–128 UTF-16 code-unit bound; catalog item ID has a 1–256 bound. The three input
strings together have a **20,000 UTF-16 code-unit** recovery budget. Bounds are
checked before reference matching, and an over-limit form retains no prefix.
This recovery budget does not enlarge the separate save budget.

Invalid numeric input can remain in this bounded private checkpoint for explicit
correction. A supplied key reference must match the frozen reference pattern
before any new typed checkpoint is retained. An invalid reference leaves only the
existing fixed **Unavailable** marker; the pasted value is excluded. The live
field can still be corrected before the form is retired. No incidental input,
preparation, issue or checkpoint description includes entered values.

Actual Activity pause captures bounded pending input, dismisses the form and
retires its callbacks. Resuming requires **Review pending input**. Editable
recovery requires the same current draft, baseline, selection kind/ID and catalog
item/version, with `wasSaving == false`. Mismatched identities, a removed target
or an in-flight checkpoint produce read-only input until explicit discard. The
display separates **Not supplied**, **Supplied empty value** and **Supplied value**
from the literal value itself, including a value that spells a status label.

During an in-flight commit, all text, supply and form controls are frozen. An
already-owned worker request can finish, but retained input cannot acknowledge
or replay it. Old Commit and Supply controls cannot revive a closed form. The
integer-version raw readers, context-policy version 3 and model version 4 retain
their existing paths. Legacy raw Configure recovery and raw **Add** remain
available; this protocol does not alias another typed editor's recovery format.

Native inputs disable automatic view-state saving and request no autofill,
dictionary suggestions or personalized keyboard learning. The explicitly bounded
checkpoint supplies recovery. These control settings do not establish behavior of
an installed keyboard or a physical device. Android's
[Activity lifecycle](https://developer.android.com/guide/components/activities/activity-lifecycle),
[saved UI state](https://developer.android.com/topic/libraries/architecture/saving-states)
and [IME learning flag](https://developer.android.com/reference/android/view/inputmethod/EditorInfo#IME_FLAG_NO_PERSONALIZED_LEARNING)
documentation provide the platform context; they were reviewed on 2026-10-20.

## Regression and receiving scope

The source declares the following regression inventory. Counts describe authored
methods; actual execution requires original results from the reviewed source.

| Suite | Methods | Required behavior |
| --- | ---: | --- |
| `PreviewStorageSettingsTest` | 26 | Frozen metadata; all presence combinations; exact numbers and representation bounds; strict parsing and Unicode; literals and references; aggregate budgets; value-free errors; complete Selection preservation; managed cancellation, no-op, zero/removal, stale and deleted authority; exact Build projection/export and retained file bytes |
| `StorageFormCheckpointTest` | 10 | Actual Bundle/Parcel round trips; exact keys/types and namespace; identity bounds; aggregate recovery budget; absence/empty literals; numeric correction; reference exclusion; legacy readers and in-flight state |
| `StorageSettingsActivityTest` | 22 | Labelled private controls; raw Add; exact round trips and invalid input; explicit supply, zero, omission, cancellation and no-op; editable/read-only recovery; all five identity mismatches; actual simulated pause and retired controls; in-flight worker ordering; stale/removed targets and legacy raw Configure |

Two additive CI attendance checks require the literal class/method sets, exact
case counts, matching aggregate totals, zero failures/errors/skips and no adverse
per-case nodes. Missing, duplicate, renamed or skipped cases cannot satisfy them.
Existing attendance guards remain intact. Synthetic qualification of the guards
tests those readers only; it is not Kotlin or Android execution.

Core results establish behavior under the named JVM fixtures. Robolectric results
establish the specified simulated Activity, Bundle and worker behavior. The
separate installed-preview job, installed typed-storage journeys, physical-device
interaction, accessibility acceptance, operational storage and release evidence
retain their own requirements in [CI_BASELINE.md](CI_BASELINE.md) and
[ANDROID_PREVIEW.md](ANDROID_PREVIEW.md).
