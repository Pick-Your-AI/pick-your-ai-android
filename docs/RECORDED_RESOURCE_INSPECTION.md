# Recorded resource input inspection

This is a partial local implementation for [Android #102](https://github.com/Pick-Your-AI/pick-your-ai-android/issues/102).
It adds receiving inspection and an explicit native browser over the existing frozen
Build and ResourceEstimate examples. It does not deliver the canonical estimator,
read service, evidence policy or an accepted memory-fit verdict.

## What the user can inspect

The **Recorded resource inputs** section reads the fixed `build.json` and
`resource-estimate.json` assets only after an explicit action. They remain separate
from the editable guest draft. A successful read establishes the supplied Build
ID/revision and selection-reference association, then exposes four groups:

- Binding, exact consumed-byte hashes and every unresolved applicability limit.
- Every estimate entry, including exact decimal text, unit, estimator ID/version,
  declared confidence, timestamp, source reference, assumptions and selection IDs.
- Every supplied Build selection, including enabled state, role, exact catalog
  identity/version and complete settings. Unreferenced active selections remain
  visible as association facts; no required metric is invented for them.
- Complete decoded Build and ResourceEstimate JSON values. These preserve decoded
  values, not the original byte layout or numeric spelling. The separate hashes
  identify every original consumed byte.

Rows use fixed field names and bounded pages of eight. Values remain accessible
in complete 160 UTF-16-unit parts, using the existing literal escaping behavior.
Absent and empty values remain distinct. Source references are plain text: no
linkification, URI resolution, permission request, network, share or export is added.
The frozen decimal string, including its admitted terminal-LF edge, is displayed
without arithmetic or a binary floating-point conversion.

## Receiving boundary

`RecordedResourceInspector` uses the existing bounded strict UTF-8 reader and
frozen validators. It checks the Build first, rejects ambiguous selection IDs,
binds exact integer Build revisions, and rejects unknown or disabled referenced
selections. It does not replace a mismatched Build with another head. The caller
owns its streams; native asset callers close both streams. Errors expose fixed
reason codes or an unavailable state, without input or provider exception text.

The immutable result records all of these unresolved limits on every successful
inspection, even when the producer declares `verified` confidence:

| Remaining qualification | Local behavior |
| --- | --- |
| Catalog/artifact facts | Recorded catalog IDs, versions and settings are visible; no catalog is resolved or approved. |
| Runtime/context/precision/topology | Supplied Build conditions remain inspectable; matching Build IDs alone cannot qualify applicability. |
| Estimator evidence and confidence policy | Identity and confidence stay producer declarations; the Build policy is shown without inventing a threshold decision. |
| Freshness | The supplied timestamp is retained; no clock or freshness policy is run. |
| Source access and rights | Source text is retained without opening it or assigning access/usage authority. |
| Measurement method and range | The frozen record has no dedicated measured-versus-estimated method or supported-range field; neither is invented. |
| Metric/unit relationship | Both exact enum declarations are visible; structural acceptance does not establish a meaningful pair. |

No estimate is attached to a private draft or a current evaluation. No amount of
recorded capacity, confidence declaration, old timestamp or lack of an estimate
can produce a passing memory-fit result through this interface.

## Lifecycle and storage behavior

Each read has a non-reused request identity. Explicit cancellation, a replacement
read, pause and destruction retire its result and every open page. An old or
reversed worker reply cannot replace a current inspection. Page transitions install
the successor before dismissing the predecessor; detached row and value controls
cannot reopen retired pages. Recreation restores the existing editor through its
own mechanism and requires an explicit new resource inspection.

The section receives no draft, save token, store adapter or evaluation authority.
It does not schedule, cancel or restart guest autosave. Its navigation callback
only retires pending catalog viewport restoration, following the existing explicit
navigation boundary. The actual Activity tests retain the 749/750 ms save boundary,
exact acknowledged revision and absence of an additional later revision.

## Validation and outstanding acceptance

Core tests exercise exact values and hashes, malformed/future/oversized inputs,
wrong and ambiguous bindings, unchanged caller-owned streams, immutable nested
collections, source absence, old/low-confidence declarations and unqualified
runtime/context/topology conditions. Native tests exercise actual frozen asset
reads, all recorded fields, long literals, cancellation/reversal, explicit retry,
detached controls, pause, recreation, real local storage and the autosave deadline.
A native-graphics 320dp RTL/two-times-font case covers reachable controls and every
line of the complete paged value. These are simulated Activity checks; they do not
supply installed Android, TalkBack, physical-device or inference evidence.

Every literal #102 criterion remains open at this partial boundary. Accepted
service/evidence intake through [#79](https://github.com/Pick-Your-AI/pick-your-ai-android/issues/79)
and canonical [application #16](https://github.com/Pick-Your-AI/pickyour.ai/issues/16),
qualified artifact/runtime/context/topology and estimator evidence, policy-consistent
missing/low-confidence handling, and evidence-arrival publication to the current
[#84](https://github.com/Pick-Your-AI/pick-your-ai-android/issues/84) evaluation
invalidation interface remain separate required work. A changed consumed-byte hash
is observable in a newly requested inspection; this is not automatic evidence
arrival or reevaluation without a user edit. No shared contract, service placement,
acceptance-record path, ownership or status is changed here.

The initial local candidate compiled and passed all 5,867 Core cases; six of its
twelve native cases exposed collapsed paragraph separators in the new value
resource. A one-case diagnostic retained the exact single-line dialog text. The
resource now uses Android newline escapes, and the helper explicitly requires the
complete separated literal body. The original failures remain retained separately
from the corrected run; no assertion or method identity was removed.

The corrected formatting run exposed two test-process heap failures during exhaustive
page traversal and a concurrent test-side read reporting `BUSY` during the real save.
Inspection of the pinned Robolectric 4.17 bytecode confirmed its static strong list
retains every dismissed dialog. The traversal helper now removes only dismissed
entries from that observer history, asserts at most one live window, and preserves
the latest pointer and explicitly held stale controls. It changes no application
state or heap budget. The save case now waits for the real UI acknowledgement before
opening its separate library reader, retaining the exact deadline/revision checks.
All original failures and all twelve method identities remain preserved.
