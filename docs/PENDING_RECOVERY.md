# Explicit pending-edit recovery copy (#125, local partial)

The preview can save valid pending inputs as a separate local draft when a saved
baseline exists. This is an explicit new-identity copy, including after a local
CAS conflict or recreation reconciliation. It is not a merge, remote operation,
authorization policy or acknowledgment that the original save succeeded.

The confirmation names the loaded baseline revision. Visible title, synthetic
sample selection, objective, context and workload inputs are copied; other domain
settings and opaque metadata come from that baseline. Newer original changes are
not merged. The original bytes, revision and history remain untouched. The copy
starts at revision zero with its own save authority and records the source local
ID, revision and hash in private recovery metadata. No evaluation or price result
is accepted by this operation.

Invalid raw workload inputs and rejected oversized edits disable recovery. The
confirmation captures the exact current form and baseline; changed forms,
superseded records and destroyed Activities cannot submit it. Cancel retains the
form. The existing canonical library lock, limits, durable directory/create
protocol, unknown-format checks and new-store CAS apply. A deleted or foreign-root
source is rejected. Unconfirmed creation reports the new local ID for inspection
instead of silently retrying. A failed target reopen or active-pointer update
retains the original editor and its pending form.

Core tests cover stale-source copying without altering the newer original,
source metadata/domain retention, new history and independent CAS, busy storage,
deleted/foreign sources and a post-save unconfirmed result. Two mandatory simulated
Activity cases cover conflict/recreation/cancel/copy/reopen, exact budget and zero,
invalid input, changed confirmations and retired callbacks. Hosted execution is
required for the exact candidate; this document alone claims no test result.

Remaining #125 scope includes remote authority and common-base reconciliation,
durable unresolved-conflict/outbox storage across process death and account
transitions, explicit field merge policies, and installed-device receiving
acceptance. No issue is closed and no remote sync capability is introduced.
