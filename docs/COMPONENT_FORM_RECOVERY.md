# Recover unfinished component input

This is a bounded native-preview continuation of
[#76](https://github.com/Pick-Your-AI/pick-your-ai-android/issues/76),
[#98](https://github.com/Pick-Your-AI/pick-your-ai-android/issues/98) and
[#112](https://github.com/Pick-Your-AI/pick-your-ai-android/issues/112).
It preserves unfinished local add/settings/role input during Activity backgrounding
and when Android supplies saved Activity state. Canonical receiving paths and the broader work-package criteria
remain unchanged. The catalog is still synthetic, and a locally saved component
does not establish compatibility, pricing, deployment requirements or readiness.

## User behavior and authority

When the Activity pauses, it captures the current form through the same bounded
checkpoint serializer, including its in-flight flag and the typed context/model
policies. It then detaches every dialog and form owner before closing the windows.
Retained entry, picker, confirmation and form controls cannot dispatch a write or
replace pending input while paused, and retired controls remain inert after resume.
Resuming refreshes availability without reopening a window or restoring consent.
Pending editable input still requires explicit review; pickers and confirmations
without editable input are simply retired.

After recreation, the old dialog is closed. **Review pending component input**
opens a fresh form only when the original local draft ID, acknowledged revision
and hash, current clean editing state and exact component identity still match.
Add also requires the original catalog item ID, version, kind and snapshot hash.
The original generated selection ID survives review and further recreation.
No command is prepared or dispatched merely by restoring or reviewing input.
Saving still requires a new explicit confirmation and the existing managed-library
compare-and-swap operation, using the current record that matched the original
checkpoint. Another writer can still cause that operation to conflict.

Changed or unavailable draft, revision, component or catalog context keeps the
raw text in a selectable read-only review. It never substitutes the latest item
or rebases the pending input onto a newer revision. Original local and catalog
identities remain visible. A checkpoint taken while a component write was in
flight stays read-only even if that write later finishes or the user explicitly
reopens the draft. It explains that the saved result must be checked; it never
asserts that a dispatched write failed or retries an uncertain operation.

**Discard pending component input** clears only this transient recovery state.
Completed cancellation of an open form also clears its pending input. A pending
checkpoint or open form blocks starting another component edit; retained entry
buttons cannot replace the first form and lose its input. Dismissed and destroyed
dialogs keep their existing terminal callback guards. Toggle, replace and remove
confirmations carry no editable raw fields and are not restored.

## Bounded saved state

[`ComponentFormCheckpoint`](../preview/src/main/kotlin/ai/pickyour/android/preview/ComponentFormCheckpoint.kt)
uses a versioned nested `Bundle` containing only strings, booleans and an integer.
It stores neither a full draft/catalog nor a command, callback, custom Parcelable
or Serializable object. Raw role and settings text share a **20,000 UTF-16
character** budget; the small identity fields have separate bounds. This includes
invalid settings somewhat beyond the existing 16,384-character parser limit so
the user can correct the original input. The parser and frozen schema limits do
not change. All three forms disclose the recovery limit before the input fields.
Framework automatic saving is disabled for these dialog fields so
the explicit checkpoint remains their retention path.

Bundle version 2 adds a dedicated `ROLE` mode for
[editing an existing selection role](LOCAL_SELECTION_ROLES.md). It stores only
the exact entered role and the original draft/baseline/selection identity with
in-flight state; settings are empty and no catalog choice or enabled state is
adopted. Version-1 add and settings checkpoints keep their original meaning and
may still be reviewed. A version-1 checkpoint cannot introduce `ROLE`, and a
settings checkpoint cannot smuggle an entered role. Resaving a supported
checkpoint uses version 2 without changing its exact input. Version 3 and malformed
mode-specific shapes remain unavailable. The durable draft's format 5 is a separate
version boundary, not a Bundle migration or recovered save token.

Oversized input is never prefix-truncated into apparently valid JSON. Unsupported
versions, shapes or primitive types and over-budget state produce a small
**input unavailable** marker with explicit user-visible guidance. No raw input
is logged. The marker can be discarded to start a new form; restoring it cannot
change the acknowledged local draft.

The choice follows the Android guidance to retain only small primitive UI state
and the minimum identities needed to reconstruct it. Saved state is distinct
from persistent storage and does not survive every user dismissal or missing
checkpoint. Serialization also has a main-thread and shared transaction budget;
the component budget does not guarantee that all Activity or OS state fits.
See [Save UI states](https://developer.android.com/topic/libraries/architecture/saving-states)
and [Parcelables and bundles](https://developer.android.com/guide/components/activities/parcelables-and-bundles).
The preview adds no disk checkpoint, backup entitlement, transport, automatic
clipboard access, intent route or migration. Do not enter provider credentials.

## URI user information in newly captured settings

This additional [#99](https://github.com/Pick-Your-AI/pick-your-ai-android/issues/99)
slice applies when an open **Add** or **Edit settings** form is captured into new
Activity state, before or after a rejected commit and while a submitted operation
is still in flight. It uses the form's original catalog/selection kind, including
when another writer has changed the saved baseline. It never looks up a newer
selection to classify the old text. Role-only capture is unchanged.

After the existing combined 20,000-character and identity bounds, the
[recovery policy](../core/src/main/kotlin/ai/pickyour/core/preview/PreviewSelectionRecoveryPolicy.kt)
checks only the five URI fields in the existing
[new-input policy](../core/src/main/kotlin/ai/pickyour/core/preview/PreviewSelectionSensitiveInput.kt):
`model_provider.api_base_url`, `model.weights_uri`, `mcp_server.endpoint`,
`integration.endpoint` and `fine_tuned_model.artifact_uri`. A successfully parsed
URI whose raw hierarchical authority contains `@` is positively classified. This
includes registry authorities. Custom schemes and `@` in a path, query, fragment
or opaque URI retain their exact meaning. The policy does not enable another
editor kind or inspect arbitrary fields, opaque credential references or query
values for possible secrets.

The classifier uses the existing bounded JSON reader with a caller-specific
20,000-character string limit and 65,536-byte input limit. Its other structural,
Unicode and numeric bounds remain unchanged. This lets it recognize a complete
URI between the editor's 16,384-character limit and the recovery limit. Input
over the combined recovery budget becomes the existing generic unavailable
marker before classification; no valid prefix is parsed or retained. Malformed
JSON, malformed URI syntax, wrong field types, duplicate keys and other parser
rejections remain **unclassified** and keep ordinary invalid-text recovery.
Admission remains fail-closed under its original schema and editor limits.

A positive classification replaces the new checkpoint with exactly
`version=1`, `unavailable=true`, `reason="uri_userinfo"`. It carries no entered
text or draft, selection or catalog identity. A distinct fixed message explains
why input was excluded and asks the user to check the saved draft; it never
claims that an in-flight operation failed. Review is unavailable and explicit
Discard permits another edit. Repeated recreation/resaving preserves only that
fixed marker. Older readers already treat it as unavailable, while unknown or
malformed reasons remain generic unavailable state. Both settings forms disclose
this capture exclusion before their fields.

Saving a checkpoint while the Activity remains resumed does not clear or alter
the still-open editor. The user can correct its input and explicitly commit once
in that form. Pausing instead retires the form after capture; any retained input
then follows the explicit review or unavailable-marker flow. Restoring the marker
prepares no command, changes no saved revision and never retries an uncertain
write. This is not a migration or redactor for existing recovery state: legacy
version-1/2 `Pending` records remain readable and can be resaved with their exact
raw content, including in stale/read-only or in-flight recovery. Malformed or
otherwise unclassified new text can also remain. Saved history, role-only edits,
private archives and future export/disclosure requirements are unchanged; this
does not complete #99 or establish a general credential-free checkpoint contract.

## Validation and remaining acceptance

`ComponentFormRecoveryActivityTest` drives actual platform Views, the serial
worker and local persistence in a paused-looper API-28 Robolectric simulation.
Its ten required cases cover exact invalid input, fresh confirmation and one
selection/revision, repeated configure recreation, stale saved head after reopen,
different local identity at a matching baseline, changed catalog hash, a checkpoint
taken before a real write finishes, cancellation/discard and retired controls,
oversized input and preserving the first open form. Lifecycle checkpoints pass
through `Parcel` marshalling and unmarshalling before the new Activity consumes
them. The cancellation test waits for Android's posted standard dismissal before
asserting completed cancellation.

`ComponentFormCheckpointTest` retains its four original required cases covering maximum Unicode
input including supplementary characters, separators and NUL; malformed types,
versions and modes; a non-truncating oversized marker; and configure/in-flight
shape preservation. The maximum-input specimen must serialize below 48 KiB for
the component checkpoint alone. This is a simulated measurement assertion, not
an installed device or whole-Activity Binder guarantee.

Two additional container cases cover exact value-free exclusion/resaving and
unknown or malformed marker reasons. `ComponentFormUserinfoRecoveryActivityTest`
adds fourteen actual Activity-capture simulations: open/rejected add and
configure, in-flight capture, repeated exclusion/discard, stale configure,
same-process correction with one explicit save, complete long URI and over-budget
boundaries, ordinary invalid text, legitimate URI/opaque-reference controls and
legacy read-only raw resaving. A separate queued-preparation case keeps a test
barrier held through old-Activity destruction and replacement-Activity creation,
observes shutdown interruption, then releases it explicitly and checks that
retired controls cannot write. This is queued preparation retirement, not a
claim that a storage write was already executing. The causal baseline uses the original thirteen test
methods on unchanged product source; its eight proposed exclusion cases fail
and its five compatibility controls pass. This is additional privacy hardening,
not a claim that the earlier raw recovery contract already excluded such input.
The separate Core policy suite exercises all five frozen fields, exact authority
semantics, parser boundaries and unchanged admission behavior.

The original baseline regression failed because recreation had no pending-input
review action; the new implementation must pass it and all current main tests.
The hosted workflow additionally requires every new case by exact suite/name,
unique identities, matching report totals and no failure, error or skip. Strict
dependency verification, unsigned assembly, lint, source/artifact inventory and
independent receiving review remain merge gates.

The additional role and typed-model pause regressions replay retained positive
buttons, picker callbacks and recovery controls across an actual simulated
`Activity.pause()`/`resume()` pair. They require unchanged acknowledged components
and revision before a fresh reviewed confirmation, exact bounded input recovery,
and one explicit new revision. A separate picker/confirmation case retains no
editable checkpoint and checks that resume never restores the earlier consent.
These simulations also keep already admitted writes and their in-flight recovery
separate from attempts to dispatch a new write after pause.

The six remaining foundation, retrieval, agent, training, guardrail and operational
in-flight fixtures release their held worker, then use the existing bounded
worker/UI fence before their first canonical-store read. Pause now dismisses the
old form before the admitted write necessarily finishes; dialog invisibility is
therefore not a storage-acknowledgment barrier. Their exact saved revision,
settings, checkpoint, read-only recovery and retired-control assertions remain
unchanged. The original d3250c2e integration reported 778 simulated cases with one
training timeout at this unfenced read boundary; its typed write disposition was
not retained, so that artifact does not establish a BUSY result. This fixture
ordering does not change product synchronization, repeat a write, extend a
timeout or infer a corrected execution result.

Actual installed system process death, recovery across the supported device and
OS matrix, complete navigation/secure-entry routing, physical persistence faults,
TalkBack and keyboard qualification, named receiving acceptance and release
evidence remain outstanding. This slice does not close any of the three full
work packages or claim completion of the separate installed four-case harness.

## Typed workload Selection pending input

Fresh existing-workload CONFIGURE uses the private `typed-workload/1` protocol
for five optional integer inputs and their exact supplied/omitted states. The
strict primitive/identity and aggregate 20,000-character boundary grants no
replacement save authority. Same-identity non-saving input needs explicit
review and a new commit; changed or in-flight input is read-only. Raw legacy
recovery remains raw. See [the scoped editor guide](WORKLOAD_SELECTION_SETTINGS_EDITOR.md)
for the frozen field, lifecycle and original-evidence boundaries.
