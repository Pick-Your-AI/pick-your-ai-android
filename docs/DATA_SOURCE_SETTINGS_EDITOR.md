# Typed data-source settings in the local diagnostic

This partial [#99](https://github.com/Pick-Your-AI/pick-your-ai-android/issues/99)
implementation replaces the CONFIGURE JSON text box only for an acknowledged
`data_source` Selection. The generic T3 ADD form and older raw checkpoints keep
their existing route. Other kinds and their editor owners are unchanged.
The frozen Selection schema remains the single definition of fields and bounds.

## Recorded fields

| Frozen field | Control and preserved meaning |
| --- | --- |
| `connector_secret_ref` | Optional supplied text. Only the existing opaque `secret://` pattern is admitted. Nothing resolves the reference or accepts credentials. |
| `refresh_minutes` | Optional literal numeric input, admitted only as an exact mathematical integer at least 1. No refresh task, calendar or scheduling policy is created. |
| `enabled` | Explicit Not supplied / False / True choice. This field is independent of outer `Selection.enabled`, and no selection or optional tier is activated by editing it. |

Clearing Supply removes the corresponding field rather than saving empty text.
A supplied empty number requires correction; a supplied empty reference is invalid.
No setting is copied from the catalog or silently defaulted. Integers wider than
binary floating-point precision and schema-admitted integral decimal/exponent
spellings use the existing bounded exact JSON reader. An untouched admitted
numeric node retains its type and scale. Pasted text is not trimmed, rounded or
coerced using locale separators. The reference pattern retains the frozen
validator's final-LF behavior; this is literal structural compatibility, not a
new reference format or a credential lookup.

The pure adapter assembles all prospective settings before the unchanged
`PreviewSelectionForms.configure` admits the entire Selection. Existing notes,
extensions, identity, catalog version, role, outer enabled flag and whole-payload
budgets remain authoritative. Field errors do not contain the entered value.
A global input-budget failure stays a rejected whole form, without publishing a
partial command or attributing another field's old value to the current control.

## Save and recovery authority

The actual Activity still captures the acknowledged record and draft ID, uses
the existing worker and managed compare-and-swap store, and waits for the save
acknowledgement. Cancel, an unchanged commit, a stale record and a deleted draft
retain their existing meanings. Controls freeze while a save is outstanding.
Only the current visible form and current boolean-choice dialog may act; pause,
destruction, cancellation and consumed choices retire old controls. Opening or
closing this form does not schedule autosave or execute a connector.

Private `typed-data-source/1` recovery stores only bounded primitive input plus
its captured draft, baseline, selection and catalog identities. The two raw text
fields share the existing 20,000 UTF-16-unit budget. Larger input becomes an
unavailable marker, with no prefix or automatic View-state fallback. The boolean
uses separate exact Boolean presence/value primitives, so absent and false do
not collapse. The checkpoint has a kind-specific string tag and cannot be
confused with existing numeric raw/context/model/compute checkpoint versions.

New typed recovery excludes a supplied connector reference that does not match
the opaque pattern. Its live editor still permits correction before leaving the
form; no invalid reference or sibling numeric input is copied into that new
checkpoint. This is a narrow capture boundary, not a general secret scanner.
Older raw checkpoints retain their original correction reader.

Recreation requires explicit review. A changed record, draft, selection, catalog
identity or an in-flight save gives only a read-only copy of the retained input,
never replacement save authority. Successful reconstruction is not proof of
persistence. These rules preserve the current private picker, summary, guest
form and T3 intent boundaries.

## Verification scope

`PreviewDataSourceSettingsTest` declares 15 meaningful Core methods for exact
metadata and values, omission, independent errors, global admission budgets,
immutable identity/links, managed no-op/conflict/deletion and explicit local
snapshot projection. `DataSourceSettingsActivityTest` declares 16 simulated
Activity cases, including actual typed controls, save/reopen, field correction,
all three boolean states, lifecycle retirement, stale CAS, in-flight recovery
and legacy raw input. `DataSourceFormCheckpointTest` declares 7 parcel/shape,
capture, identity, budget and historical-reader cases. Additive CI attendance
checks require these exact identities, class ownership and zero-adverse XML
counts. Source declarations alone are not a passing execution receipt.

The original test-only tree `f6d5f6ab10488278c52e77a071cd83a6cf6fad99` on
accepted `3c20f573019e7e86296a53b0183fbd4bc6598860` ran the first Activity case
against unchanged production. It failed at the expected missing typed caption,
`Connector secret reference`, with one failure and no errors or skips. An earlier
attempt stopped before execution when the sandbox denied Gradle's local daemon
socket; both original logs and the actual XML are preserved separately.
Corrected-source executions and hosted receiving checks must be bound to their
actual source rather than inferred from this causal failure.

This slice supplies local structural editing only. It does not establish source
rights, secret storage/resolution, connector execution, synchronization, freshness,
real catalog data, installed-device accessibility or process-death durability.
Broader #99/#127 and the work-package's literal acceptance, record placement,
review, device and release criteria remain open.

## Separate installed configure/save/reopen preparation

A subsequent five-file harness change adds
`typed_data_source_acknowledged_save_survives_force_stop` to the installed
synthetic lane described in [EMULATOR_SMOKE.md](EMULATOR_SMOKE.md). This is a
source protocol, not a claimed passing execution of this feature.

The proposed case creates and captures an actual draft and Selection through
the existing ADD form, then opens this typed CONFIGURE editor. It observes all
three fields omitted, supplies exact refresh text `60`, chooses inner
`enabled=true`, and leaves `connector_secret_ref` omitted and outer
`Selection.enabled=false`. It requires the existing changed-selection
acknowledgment only after the exact form and Commit control retire. The existing
force-stop/relaunch helper must observe a different process ID; reopening must
retain the same actual draft ID, Selection ID, complete displayed row and
configured values.

Omission is established through the current unchecked Supply control, disabled
adjacent editor and exact hint `Optional; supply explicitly`, not by treating
the accessibility hint as entered text. The inner choice uses the actual Material
single-choice rows. The outer `Enable selection` action is observed without
activating it. The receipt retains the three phase observations and separate save
and reload acknowledgments; a passing label alone is rejected.

These observations exercise the production managed save and reader through
visible installed controls. They do not directly inspect private storage bytes,
resolve a secret reference, execute a connector, prove general numeric storage
representation or establish physical-device/accessibility acceptance. The four
existing installed cases and 25-minute cap are preserved. Runtime fit of the
additional journey remains unproved until its own actual execution. This
preparation does not close #99/#127 or assign an earlier generic installed pass
to the new typed journey.
