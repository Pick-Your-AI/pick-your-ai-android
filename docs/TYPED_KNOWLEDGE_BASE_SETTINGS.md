# Typed knowledge-base settings in the local Android preview

This slice adds native **Configure** controls for an existing `knowledge_base`
selection. It addresses part of [#99](https://github.com/Pick-Your-AI/pick-your-ai-android/issues/99).
The frozen version-1 Selection schema remains the authority; the editor does not
change shared contracts, the catalog, evaluation rules or service capabilities.

## Four frozen fields

| Setting | Frozen type and constraint | Input behavior |
| --- | --- | --- |
| `chunk_size_tokens` | Optional integer, minimum 1 | Exact JSON-number input representing an integer |
| `chunk_overlap_tokens` | Optional integer, minimum 0 | Zero remains an explicit value |
| `refresh_minutes` | Optional integer, minimum 1 | Exact integer without a calendar or service limit |
| `acl_enforced` | Optional Boolean | A separate Supply choice preserves absent, false and true |

These fields have no frozen maximum. The schema does not require overlap to be
less than chunk size. The editor therefore adds neither that relationship nor a
refresh ceiling. A stored ACL Boolean is a declaration, not evidence that a
service enforces access controls. Saving these fields does not connect a service,
change ACLs, verify an index or imply complete RAG readiness.

Every field starts from the existing selection. Omitted fields remain omitted;
the catalog supplies no defaults. For a number, choose **Supply** and enter its
value. Supplied empty or invalid text remains available for correction. Clear
Supply to remove that field. For ACL enforcement, supplied and unchecked means
`false`; supplied and checked means `true`; clearing Supply removes the field.
Answer-state words are not numeric or Boolean replacements.

Numbers pass through the existing bounded strict JSON reader with exact
`BigInteger`/`BigDecimal` values. Mathematical integer aliases such as `1.000` and
`1e3` remain admissible. Untouched admitted numeric nodes keep their type and
scale. Commas, fractional values, injected JSON members, duplicate keys,
malformed Unicode and the existing reader's size/number-limit overflows are
rejected without rounding or locale coercion. Value-free field errors do not echo
entered text. All fields must pass before a command is prepared, followed by the
existing whole-Selection/schema and sensitive-input admission.

## Existing save authority

The Core helper prepares `SelectionCommand.Configure`; it cannot persist a draft.
The Activity captures the draft, saved baseline, selection and catalog-item
identity. The existing serial worker and managed compare-and-swap path still
perform the save. Cancel and unchanged settings create no additional revision.
A stale, deleted, retired or in-flight editor cannot acquire replacement save
authority. Other selections, links, roles, enabled flags, notes, workload data and
private metadata retain their existing behavior.

**Add** remains the explicit raw settings form. Other kinds and recovered legacy
raw forms retain their existing readers. The typed editor does not supply new
selection IDs, change optionality, or implement the rest of #99's component and
objective forms.

## Private pending-input recovery

The separate `typed-knowledge-base/1` discriminator carries primitive private UI
input, exact original identities and the `wasSaving` flag. It does not reuse a
numeric recovery version or modify another kind's format. Nullable numeric text
preserves absence separately from an empty correction. Two exact Boolean
primitives preserve ACL presence and value; an inconsistent absent/true pair is
rejected. Unknown keys, wrong primitive types and incompatible version/form/kind
bindings never fall back to current identity.

The existing 20,000 UTF-16-character aggregate budget covers the three numeric
controls. Oversized input produces an unavailable notice without retaining a
prefix. Automatic raw view state and autofill are disabled. Reopening valid
pending input requires explicit review against the same saved baseline and
captured identity. Stale or in-flight input stays read-only until discarded; no
save is replayed. These private records are not a new wire format or a general
credential scanner.

## Regression boundaries

Core tests exercise exact metadata, optional absence/false/zero, integer aliases
and limits, isolated errors, immutable selection copies, managed no-op/conflict/
deletion behavior and explicit frozen Build projection. Parcel tests exercise
the exact primitive shape, Boolean presence, input budget, identity bounds and
legacy readers. Robolectric Activity tests use the actual UI, owned worker and
managed storage to cover labeled controls, save/reopen, correction, cancellation,
reviewed recovery, retirement, stale state and an admitted in-flight write.

The Core workflow requires each new method identity to appear exactly once with
passing totals. These local JVM/Parcel/Robolectric tests are simulated evidence.
They are not an installed-device, physical-device, accessibility release or
whole-issue acceptance claim. The existing installed preview lane retains its
separate source, execution and artifact requirements.
