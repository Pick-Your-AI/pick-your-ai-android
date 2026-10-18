# Local draft and revision model

Partial implementation for Android #26, #121 and #122. The Kotlin `drafts` package
models incomplete client editing state; it is not a new shared Draft wire contract
and is never presented as a valid frozen-v1 BuildSnapshot. Objective, context and
primary model can remain unanswered. The immutable workload fields (region, currency,
budget, request/token counts, concurrency, latency and classification) also remain
explicitly unanswered until entered. Counts use arbitrary-precision integers, budget
retains its exact decimal spelling, and budget may precede currency. Field bounds
align with existing frozen `Build.objectives`; no complete build or placement
verdict is inferred. See [local storage and migrations](LOCAL_DRAFT_STORAGE.md). Opaque local selections/settings are not
asserted to be accepted catalog references or contract extensions. A separate
[typed local selection/link aggregate](TYPED_LOCAL_SELECTIONS.md) retains frozen
schema-valid payloads without inferring them from these legacy inputs. It can
remain empty and establishes no compatibility or complete-build verdict.

T3, T4 and T5 activate independently. Disabling a tier preserves its selections and
settings, while removing it from the active optional-selection projection. Unknown
activation is separately available to consumers; an empty projection cannot mean
zero cost or compatibility pass. T6 cannot be disabled through this API. Actual
rule evaluation, pricing and UI messaging are separate consumers.

Revisions defensively copy all collections. An expected-head token prevents stale
or foreign edits from replacing current state. Identical edits are no-ops; restore
always creates a new revision, leaves history intact and retains current lock/version
metadata. Restore identifies its source revision. These choices apply only to the
local preview model, pending shared-contract and receiving acceptance.

| Live criterion | Executable evidence | Remaining acceptance |
| --- | --- | --- |
| #26 incomplete drafts without fabricated selections | Empty/partial content tests preserve null and entered values | Durable adapter, accepted versioned Draft serialization and validated snapshot conversion |
| #121 independent optional tiers with retained settings | All 27 activation combinations; inactive settings retained; unknowns explicit | Cost/rule integration, UI, persistent round trip and receiving review |
| #122 restore creates new head and keeps history unchanged | Edit/restore/edit, matching-content restore, defensive-copy tests | Durable transaction, process-death recovery and actual saved-state presentation |
| #122 rapid edits and duplicate handling | Concurrent edits yield one winner, loser remains available to caller; duplicate edits are no-ops | Disk-failure and kill/reopen evidence against Android storage |

`DraftHistory` is intentionally in-memory and retains all revisions. `Applied`
means accepted by this model, **not saved**. It performs no IO, migration, retention,
network synchronization or account isolation. A durable repository must commit
revision/head atomically before displaying saved feedback; it must not reuse this
in-memory history as evidence of successful persistence. No process-recreation or
Android/device acceptance is claimed.

Run the required commands in [CORE_FOUNDATION.md](CORE_FOUNDATION.md). Eight new
JUnit tests cover the behaviors above. Canonical acceptance paths remain in
`pickyour.ai` under `docs/delivery/android/work-packages/03.02.md`, `08.03.md` and
`08.06.md`; parent coordination should link this partial evidence without closing
those issues or changing the frozen contracts.
