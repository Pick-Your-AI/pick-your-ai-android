# Local workload editor (#83, partial)

The guest preview exposes the nine existing incomplete workload inputs through
labeled native fields and a classification picker. They are user-entered planning
assumptions. Empty fields remain unanswered; entered zero counts and exact decimal
budget spelling survive saves and reopening. No currency, region, component,
provider, price, placement or readiness verdict is supplied automatically.

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
