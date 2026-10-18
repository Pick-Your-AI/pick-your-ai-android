# Recover unfinished component input

This is a bounded native-preview continuation of
[#76](https://github.com/Pick-Your-AI/pick-your-ai-android/issues/76),
[#98](https://github.com/Pick-Your-AI/pick-your-ai-android/issues/98) and
[#112](https://github.com/Pick-Your-AI/pick-your-ai-android/issues/112).
It preserves unfinished local add/configure input when Android supplies saved
Activity state. Canonical receiving paths and the broader work-package criteria
remain unchanged. The catalog is still synthetic, and a locally saved component
does not establish compatibility, pricing, deployment requirements or readiness.

## User behavior and authority

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
not change. Both forms disclose the recovery limit before the input fields.
Framework automatic saving is disabled for these dialog fields so
the explicit checkpoint remains their retention path.

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

`ComponentFormCheckpointTest` has four required cases covering maximum Unicode
input including supplementary characters, separators and NUL; malformed types,
versions and modes; a non-truncating oversized marker; and configure/in-flight
shape preservation. The maximum-input specimen must serialize below 48 KiB for
the component checkpoint alone. This is a simulated measurement assertion, not
an installed device or whole-Activity Binder guarantee.

The original baseline regression failed because recreation had no pending-input
review action; the new implementation must pass it and all current main tests.
The hosted workflow additionally requires every new case by exact suite/name,
unique identities, matching report totals and no failure, error or skip. Strict
dependency verification, unsigned assembly, lint, source/artifact inventory and
independent receiving review remain merge gates.

Actual installed system process death, recovery across the supported device and
OS matrix, complete navigation/secure-entry routing, physical persistence faults,
TalkBack and keyboard qualification, named receiving acceptance and release
evidence remain outstanding. This slice does not close any of the three full
work packages or claim completion of the separate installed four-case harness.
