# Typed workload Selection settings

This bounded #99 contribution gives an existing frozen `workload` Selection a
five-field CONFIGURE form. It preserves the original explicit T0 choice from
#83. These per-selection overrides remain separate from the overall workload
form, objectives, budget, region and data classification.

The editor records user-entered assumptions. It does not measure requests or
latency, combine input/output context budgets, select placement, establish
compatibility or declare T0/T1 readiness. No field is automatically supplied.

## Frozen field boundary

| Frozen key | Control | Admitted value |
| --- | --- | --- |
| `concurrency` | Supply toggle and literal numeric text | Optional exact integer, minimum 1 |
| `requests_per_day` | Supply toggle and literal numeric text | Optional exact integer, minimum 0 |
| `avg_input_tokens` | Supply toggle and literal numeric text | Optional exact integer, minimum 0 |
| `avg_output_tokens` | Supply toggle and literal numeric text | Optional exact integer, minimum 0 |
| `latency_budget_ms` | Supply toggle and literal numeric text, milliseconds | Optional exact integer, minimum 1 |

The bundled Selection schema supplies the field metadata and bounds. There is
no machine-width maximum, invented cross-field relationship or new wire answer
state. Supplied zero is retained for the three fields whose minimum is zero;
turning Supply off removes only the chosen key. An empty supplied field needs
correction or explicit omission. Text such as `unknown` or `not_applicable` is
not converted into a value or treated as a measured fact.

## Preparation and persistence

`PreviewWorkloadSelectionSettings` prepares a complete existing Selection
command. Literal input passes through strict UTF-8 encoding and the existing
bounded JSON reader. Fractional values, malformed/locale syntax, nonnumbers,
extra injected members, excessive numeric representations and values outside
the frozen bounds are rejected. Mathematical integers represented as JSON
decimals or exponents stay within the frozen receiver's existing semantics.
The form does not trim, parse through floating point or impose an integer
machine-width cap.

An untouched admitted numeric node keeps its original scale and runtime type.
Independent value-free field issues are shown together; a valid sibling cannot
produce a partial command. The existing whole-selection form adapter remains
the final receiver. Selection ID, catalog item/version, role, enabled state,
notes, extensions, links and unrelated draft inputs remain unchanged.

The existing worker and managed expected-revision/CAS path own persistence.
Current no-op edits keep the revision, while stale or deleted managed authority
cannot be replaced by a freshly loaded record. Raw ADD remains unchanged.

## Private pending-input recovery

`WorkloadSelectionFormCheckpoint` uses the disjoint string discriminator
`typed-workload/1`. It accepts exactly its named keys and primitive types,
including present nullable strings for the five supplied/omitted states. It
records the original draft, baseline, selection and catalog item/version pins,
plus whether a write had already been admitted. It carries no command or new
save authority.

The combined pending text is bounded to 20,000 UTF-16 characters, including
invalid text. Oversized state retains only the existing unavailable marker.
The typed subtree disables automatic view-state fallback and autofill; it
requests the keyboard's no-personalized-learning flag. These settings do not
establish behavior of an installed third-party keyboard.

Cancellation and pause retire the old form callbacks. A paused form keeps only
its captured pending input; resume does not reopen it or restore confirmation.
Explicit review may reopen editable controls only for the same original
acknowledged identity and a form that was not saving. A changed identity or an
already admitted write produces selectable read-only text. A later write
acknowledgment cannot authorize replay of the recovered form. Existing raw
version-1/version-2 and typed context/model protocols remain independent.

## Regression boundary

The Core suite covers all five metadata definitions, omission and supplied
zero, wide exact integers and mathematical-integer representations, every
lower bound, malformed/fractional/locale/injected input, parser limits,
immutable field issues, complete Selection preservation, unrelated overall
workload inputs, managed no-op/conflict/deletion, durable reopen/history and
explicit Build projection.

The Parcel suite crosses the real Bundle/Parcel boundary for invalid and exact
input, all captured pins, omission/empty/zero, strict keys and primitive types,
combined text limits, foreign recovery protocols and saving-state retention.
The Activity suite uses the actual controls and canonical local storage for
all-field round trips, field errors, unchanged/canceled edits, explicit
recovery, stale pins/heads, an in-flight write, simulated pause and 320dp RTL
with 2x font scaling. The earlier foundation method retains its raw ADD and
overall-profile assertions while using the new controls for fresh CONFIGURE.

Two literal workflow guards require the named Core and native methods, matching
suite/class identities, consistent report totals and no missing, duplicate,
failed, errored or skipped cases. Authored regressions do not themselves prove
execution; exact compiled/tested source and original outputs belong in the PR
receipts. Installed workload-specific journeys, physical devices, TalkBack,
service behavior and release acceptance remain separate.

This is partial Android #99 and #83 input behavior. It changes no frozen
contract, criterion, prerequisite handoff, accountable assignment or canonical
`docs/delivery/android/work-packages/08.04.md` acceptance coordinate in the
website repository. No whole-issue closure follows from this editor.

## Complete identity at large text

The form uses a short “Workload” dialog title and places the complete unchanged
selection label as selectable multiline text inside its existing ScrollView.
A platform title permits ellipsis and is unsuitable as the only place for an
arbitrarily long identity. The narrow RTL / 2x-text regression requires every
line of the exact selection label to be reachable, in addition to its existing
field, guidance, touch-target, full-text, zero-ellipsis and exact-save assertions.
Its ellipsis assertion identifies the affected view if a future execution fails.

This presentation amendment follows inspection of the same dynamic-title pattern
while diagnosing the separate cost-policy #706 failure. It is not a workload
failure reproduction or a passing workload result. The initial c130 source and
its independent reviews remain historical; this amendment, compilation and actual
configured execution require their own qualification. Parsing, identity pins,
settings, recovery, currentness and commit authority remain unchanged.

## Projection fixture preserves contradiction admission

The initial Core run exposed a contradiction in the new projection success
fixture: it changed all five Selection values while retaining different supplied
overall numeric objectives. The existing projector returned `Withheld`, and the
test's cast to `Review` failed. This was not a failed durable save or permission
to weaken projection admission.

The success fixture now explicitly omits those five optional overall numbers,
retaining its required objectives and budget. Every original exact-value,
reopen, private-metadata, history and no-write assertion remains, with an added
check that projection does not invent the omitted overall values. A separate
control retains all five conflicting supplied objectives, requires the exact
five conflict paths, and verifies the saved Selection, metadata, version and
storage bytes remain unchanged. Its named workflow attendance requirement is
additive. Production projection, preparation, persistence and native source are
unchanged. The original failure remains distinct from any later execution of
this corrected fixture; an authored correction alone supplies no passing result.
