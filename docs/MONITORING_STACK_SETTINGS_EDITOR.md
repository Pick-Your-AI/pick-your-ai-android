# Typed local monitoring settings

This is a bounded native-preview continuation of
[#99](https://github.com/Pick-Your-AI/pick-your-ai-android/issues/99) and the local
operational inputs in
[#52](https://github.com/Pick-Your-AI/pick-your-ai-android/issues/52). Fresh
**Edit settings** for an existing `monitoring_stack` Selection provides three
labelled optional controls. It uses the accepted local component command and
managed-library save path. Raw Add and explicitly reviewed legacy raw Configure
recovery retain their existing behavior. This does not close either issue or
move their canonical receiving records.

## Exact declared inputs

The field metadata comes from the existing frozen Selection schema and component
registry. No schema, kind, catalog pin, role, enabled state, durable format or
optional-tier intent changes.

| Field | Supplied value | Omission and meaning |
| --- | --- | --- |
| `sample_rate` | Exact JSON number from 0 through 1, inclusive | An absent value remains absent. A supplied zero is a fraction, not an inferred disabled state or percentage. |
| `retention_days` | Exact mathematically integral JSON number at least 0 | Wide integers and admitted integral decimal/exponent forms retain their exact value. Zero does not prove deletion or an effective retention policy. |
| `credential_ref` | Literal opaque reference admitted by the frozen `secret://` pattern | Omission does not supply credentials. The reference is neither opened nor resolved. |

Each field has a separate **Supply** control. Clearing it omits only that key;
checked empty numeric input or an unrecognized reference remains invalid and
requires correction or explicit omission. The absent hint disappears when Supply
is checked, so supplied empty input is not presented as absent. There are no
defaults or invented `unknown`/`not_applicable` values.

Numeric fields use bounded exact JSON parsing, without binary floating-point
rounding or locale conversion. A comma decimal, extra member, duplicate wrapper
key, non-number or structurally oversized number cannot prepare a command.
Per-field errors are independent and contain no entered values. Valid siblings
cannot be saved while another supplied field is invalid. An untouched admitted
numeric node keeps its representation and runtime type during a sibling edit.

Reference matching preserves the frozen receiver's LF-only anchor behavior,
including an already admitted single terminal LF. The editor does not trim,
normalize or widen the pattern. It displays the literal reference without link
activation. Field preparation never clones the full original Selection into a
temporary one-field validation probe. The exact final settings still pass the
existing whole-Selection parser, settings budget, frozen schema, sensitive-input
policy and command adapter; notes, extensions and unrelated settings cannot
escape their original aggregate bounds.

These are declared local planning inputs. They do not install instrumentation,
establish observed coverage, apply retention, contact a backend, provide a cost
hard stop, route alerts or establish readiness. The existing shared T6 guidance
and monitoring-specific explanation remain visible independently of selection
enabled state and T3–T5 intent. Mode applicability, measured evidence and the
remaining operational requirements stay separate.

## Save, privacy and recovery boundaries

Opening the form captures the exact acknowledged draft, baseline, Selection ID,
kind, catalog item ID and version. Only a new explicit confirmation can dispatch
the prepared command through the existing managed compare-and-swap operation.
No-op confirmation retains the revision. Changed or deleted saved authority,
another writer, a paused Activity or a retired dialog cannot silently rebase or
replay the command. In-flight input and cancellation remain frozen until their
existing result handling completes.

The private `typed-monitoring-stack/1` checkpoint has a closed primitive shape:
three nullable strings, the original identity pins and an in-flight Boolean.
The text fields share the existing 20,000 UTF-16-unit recovery budget. Invalid
numeric text can survive for correction, while a newly captured typed credential
field must already match the frozen opaque-reference pattern. This independent
check still excludes unrecognized credential input when the numeric siblings are
invalid. Empty or malformed supplied references produce only the fixed generic
unavailable marker, with no entered text or identity in that marker. They do not
clear the live editor while it is resumed; the user can correct the reference
and explicitly save before leaving.

Automatic View saving and autofill are disabled for the fields and supply
controls. The input method receives the existing no-suggestions and
no-personalized-learning requests; these do not qualify an installed keyboard.
Pausing captures through the bounded serializer and retires the controls.
Recreation does not reopen a dialog or restore consent. Explicit review requires
all original identities and the current acknowledged clean state. Stale or
in-flight checkpoints expose selectable read-only input without a save action.
Discard clears only transient recovery state.

This is not general secret detection. Arbitrary numeric correction text, legacy
raw Configure recovery, old stored history and opaque reference content retain
their separate existing boundaries. A string matching the allowed reference
shape is not proof that its contents are public or that a secret manager exists.
No export, logging, telemetry or remote-resolution path is added. The current
URI-userinfo policy and every existing checkpoint protocol keep their meanings.

## Verification and remaining acceptance

The dedicated Core source contains fifteen cases covering frozen bounds, exact
numbers and admitted LF references, independent errors, whole-payload limits,
lineage, managed no-op/conflict/deletion, reopen, retained history, private
metadata and explicit Build projection. Only monitoring settings change in the
projection fixture; the independent overall workload stays consistent.

Nine Parcel cases exercise the exact closed shape, nullable primitives,
malformed-reference exclusion, aggregate boundary, identity bounds, wrong
protocols and coexistence with existing recovery. Sixteen Activity simulations
drive actual platform controls and the canonical local store through omission,
invalid-to-valid correction, exact save/reopen, stale identities, cancellation,
retained actions, pause, in-flight acknowledgment, legacy raw recovery and the
new typed route. The narrow RTL/large-text case retains full identity and
field-line reachability, complete unellipsized text, 48dp controls and a final
real local save. Existing operational regression assertions remain present,
with only their monitoring CONFIGURE inspection using the new fields.

The configured CI attendance programs require the literal declared methods and
reject missing, duplicate, failed, errored or skipped cases. They permit later
additional real cases. Source inspection and these declarations are not an
execution result: each published preparation still needs its actual configured
Core and Preview checks, original report qualification and normal reviewed
delivery. Generic installed journeys, installed keyboard/accessibility work,
physical devices, operational rollout, named receiving review and all broader
#99/#52 criteria remain separate.
