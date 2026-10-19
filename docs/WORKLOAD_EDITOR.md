# Local workload editor (#83, partial)

The guest preview exposes the nine existing incomplete workload inputs through
labeled native fields and a classification picker. They are user-entered planning
assumptions. Empty fields remain unanswered; entered zero counts and exact decimal
budget spelling survive saves and reopening. No currency, region, component,
provider, price, placement or readiness verdict is supplied automatically.

The classification picker and selected-value caption use Android string resources
with translation context. The stored values `public`, `internal`, `confidential`
and `restricted` remain canonical; translating a label does not rewrite them.

The editor accepts the stored bounds described in [local storage](LOCAL_DRAFT_STORAGE.md).
Whole-number form inputs use digits without leading zeroes; invalid text remains
visible and disables Save. It is never converted to zero or silently trimmed.
The pending text buffer allows 16,385 code units per field; larger replacements
are rejected in full with visible feedback, retaining previous text. A rejection
marker survives recreation and blocks Save until a new accepted text edit or explicit
reopen. No valid-looking prefix is saved.
Budget and currency may be answered independently. These are not complete money,
cost coverage, compatibility or T0/T1 completion claims.

Save uses the existing canonical library guard and exact baseline CAS. The new
optional adapter argument preserves workload for older callers. Context, model,
tier configuration and unknown metadata remain intact. Raw pending inputs, including
invalid text and deliberately emptied fields, have an explicit Bundle checkpoint;
older bundles without the new keys hydrate from the durable record. A stale or
in-flight checkpoint still requires explicit reopen before another write.

Successful draft switches and domain restore replace workload inputs. Failed
switches and deletion retain pending text. Guest-history copy retains current
domain workload. Restore confirmation lists the selected workload with typed,
escaped values using the existing bounded display formatter. Classification
opening and selection callbacks check Activity liveness and editability; selection
also checks the captured input identity. A retired-control recreation regression
is included in the mandatory hosted gate.

Core regressions cover exact parsing, invalid pending inputs, older checkpoints,
CAS and content preservation. The hosted paused-looper Activity gate includes
exact save/recreation, invalid text retention and stale-writer reconciliation.
The existing semantics test now checks all 12 labeled text editors. These are
JVM simulations, not installed-device, process-kill, TalkBack or release evidence.
Hosted execution must pass for the exact final candidate before merge.

Remaining #83 work includes accepted placement guidance, required-input completion
presentation, actual-device lifecycle/keyboard/accessibility checks and receiving
acceptance. No issue is closed by this bounded editor.

## Field errors (#99, partial)

Each of the eight text inputs now identifies its own rejected value with a native
field error. The message describes the accepted format, unit or limit and offers
leaving the field unanswered. Messages use fixed resources and never include the
entered value or a parser exception. The existing labels and classification
picker retain their meanings.

`PreviewWorkloadValidation` projects each raw field through
`PreviewWorkloadInputs.parsed()`, with the other independently optional fields
unanswered. The result contains only immutable local field identities. The complete
form still uses `parsed()` for Save and recovery admission; an empty error set is
not a separate admission result. In particular, a rejected oversized replacement
can retain valid text while its existing form-level rejection marker blocks Save.

| Field | Existing accepted representation |
| --- | --- |
| Region | Unchanged user text, at most 16,384 UTF-16 code units; blank is unanswered |
| Currency | Exactly three ASCII uppercase letters; blank is unanswered |
| Monthly budget amount | Canonical nonnegative decimal text, no extra leading zeros, optionally a decimal point followed by 1–12 digits; at most 16,384 code units |
| Requests per day, average input tokens, average output tokens | Whole decimal integers from zero upward, no leading zeros, at most 128 digits |
| Maximum concurrency, maximum latency in milliseconds | Whole decimal integers from one upward, no leading zeros, at most 128 digits |

The numeric fields also allow blank as unanswered. Budget and currency remain
independent. The feedback does not trim text, accept locale commas or exponent
notation, round decimals, narrow integers, or interpret a literal `unknown` as a
numeric measurement. Free text admitted by its existing field remains a user
assumption. These local input limits do not establish complete snapshot, storage
capacity, cost, compatibility or deployment readiness.

The UI uses Android's existing
[`TextView.setError`](https://developer.android.com/reference/android/widget/TextView#setError(java.lang.CharSequence))
behavior on each labeled editor. It reapplies an invalid message during key edits
because the framework can clear errors when text changes. Correcting a field
clears its message; another invalid field keeps its own message. Recreating the
Activity derives feedback from the preserved raw checkpoint. Confirming Reopen
restores saved values and clears their errors without creating another revision;
canceling that confirmation keeps the pending edits.

The focused Core tests exercise exact decimal and integer boundaries, independent
field attribution, Unicode text-budget accounting, immutable value-free results
and the separate rejected-edit marker. The Activity suite uses the actual serial
worker and canonical private store, verifies baseline identities and hashes of all
private saved files, and covers each field, invalid-to-invalid keyboard editing,
native accessibility error metadata, recreation, correction and explicit Reopen.
The workflow requires every named regression to execute without failures, errors,
skips, duplicate names or substituted suites. These are JVM and simulated API 28
checks, with installed keyboard, TalkBack and physical lifecycle evidence separate.

This is one bounded improvement to the existing workload controls. Remaining #99
acceptance includes the general contract-driven component controls, governed
answer-state storage/export handoffs, full sensitive-input/export acceptance and
the required receiving and installed-device evidence. The intended acceptance
record remains `docs/delivery/android/work-packages/08.04.md` in
`Pick-Your-AI/pick-your-ai-website` (formerly `Pick-Your-AI/pickyour.ai`);
this implementation note does not relocate it or assert that the record exists.
