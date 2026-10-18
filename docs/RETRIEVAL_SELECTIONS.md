# Explicit local retrieval selections

This bounded [Android #127](https://github.com/Pick-Your-AI/pick-your-ai-android/issues/127)
continuation adds the frozen registry's five T3 kinds to the existing local
component editor: `data_source`, `knowledge_base`, `embedding_model`,
`vector_database` and `rag_pipeline`. It reuses the accepted native Views controls,
typed command reducer, frozen validators and canonical managed-library save path.
The intake builds on the reviewed [T0 source preparation #388](https://github.com/Pick-Your-AI/pick-your-ai-android/pull/388)
and preserves its [main receiving path #397](https://github.com/Pick-Your-AI/pick-your-ai-android/pull/397).
Actual published source, accepted-main composition, checks and review are recorded
in the delivery PR receipts.

The canonical #127 acceptance record remains
`docs/delivery/android/work-packages/13.01.md` in `Pick-Your-AI/pickyour.ai`.
This document records partial implementation; it does not move acceptance,
assign a research owner, authorize ingestion or close the issue.

## Exact frozen fields

All five kinds are optional T3 kinds with cardinality `many` in the unchanged
[component registry](../contracts/v1/schemas/component-registry.json). The editor
admits exactly those existing kinds, keeps identity and roles explicit, and uses
the unchanged kind-specific settings in the
[Selection schema](../contracts/v1/schemas/selection.schema.json).

| Kind | Required settings | Optional settings |
| --- | --- | --- |
| `data_source` | None | `connector_secret_ref`: opaque `secret://` reference; `refresh_minutes`: integer at least 1; `enabled`: boolean |
| `knowledge_base` | None | `chunk_size_tokens` and `refresh_minutes`: integers at least 1; `chunk_overlap_tokens`: integer at least 0; `acl_enforced`: boolean |
| `embedding_model` | None | `credential_ref`: opaque `secret://` reference; `output_dimensions`: integer at least 1; `region`: string |
| `vector_database` | `index_dimension`: integer at least 1; `similarity_metric`: `cosine`, `dot` or `euclidean` | `region`: string; `index_name`: nonempty string; `credential_ref`: opaque `secret://` reference |
| `rag_pipeline` | None | `top_k` and `max_chunks`: integers at least 1; `rerank_enabled`: boolean |

Every settings object remains closed to extra fields. Omitted optional fields
remain absent. False booleans, zero where allowed, large exact integers, whitespace
inside strings and unmeasured declarations are retained; the editor does not trim,
round, coerce or fill them from catalog specifications. The frozen validator and
existing parser budgets remain authoritative, including accepted literal/pattern
behavior. This continuation introduces no schema restriction or transport field.

Add starts with an empty role, unchecked component enabled state and visible `{}`
input. A vector-database choice requires the user to enter its two required
settings before save. The other four kinds can retain an explicit empty settings
object. This does not invent a complete stack or require a vector database for
every retrieval design. The exact chosen catalog item retains its recorded
identity, version and specifications; the editor never selects a different item
or mode to make entered settings appear compatible.

The add and settings forms show the applicable field names, required values and
bounds. The original 16,384-character settings editor and strict bounded JSON
parser reject duplicate keys, malformed/over-limit input, wrong types, missing
required values and unknown fields without a valid-prefix save. Raw input stays
visible for correction. Existing saved settings larger than that editor limit
remain intact; the separate role editor does not round-trip them through the
smaller text form.

## Component state and T3 intent remain separate

There are three distinct recorded values for a data-source choice:

1. Its optional `settings.enabled` boolean is a source setting.
2. The Selection's required `enabled` boolean is its component state.
3. The existing optional-tier controls record T3's `ENABLED`, `DISABLED` or
   `UNKNOWN` intent.

Changing one never silently changes either of the others. Typed edits preserve
the complete optional-tier map, including retained opaque tier selections and
settings. Changing T3 intent preserves all typed component payloads and links.
The optional-tier controller's existing authority/recovery continuation remains
with its current owner; this implementation does not replace or edit it.

The accepted [snapshot projection](DRAFT_SNAPSHOT_PROJECTION.md) remains the
boundary for a structurally complete in-memory candidate. It withholds unknown
T3 intent, disabled T3 with enabled T3 components, and enabled T3 without any
enabled T3 component. The local editor retains those incomplete/contradictory
drafts for correction instead of normalizing intent, disabling a component or
claiming an approved skipped result. Turning T3 off alone does not establish
interoperability, zero cost, a pass or an exportable candidate.

All five kinds permit multiple enabled selections when their roles differ.
Duplicate `(kind, role)` pairs reject the whole edit even when one selection is
disabled. No component is silently promoted to `primary`, enabled or inserted as
a prerequisite. Cardinality-one T0/context behavior remains unchanged.

## Persistence, references and recovery

The existing add, same-kind exact-pin replace, settings/role edit, enable/disable
and remove commands now admit T3. Add/replace retain the captured exact catalog
hash, item ID, version and kind. Replacement keeps selection ID, role, enabled
state, settings, notes, extensions and links. Configuration replaces only the
explicit settings object. Removal deletes only the selected identity and its
incident links; unrelated relationships and other selections remain byte-for-byte
equivalent in their retained payloads.

No new private storage format is needed: format 4 already represents frozen T3
payloads and these history actions. A real explicit role change uses the accepted
format-5 `CONFIGURE_ROLE` transition. Earlier immutable revisions, guest fields,
workload inputs, metadata and original content-addressed bytes remain readable.
An unchanged action does not synthesize a new semantic change.

The canonical worker/adapter path keeps the original draft identity, record
authority and revision/hash token. Stale, foreign or deleted authority cannot
write; reopening does not rebase an old confirmation. Dialog identity and
in-flight controls remain the existing ones. Canceled/replaced catalog choices
cannot dispatch a later save, and a recreated pending form carries only bounded
raw input for explicit review. A changed saved head or an in-flight checkpoint
keeps recovered input read-only. The original acknowledged write cannot replay.

Opaque secret references are neither followed nor resolved. Catalog provenance,
access declarations, dimensions, metrics, regions, versions and relationships
remain recorded evidence inputs. Structural acceptance of a declaration is not
proof of access rights, ACL enforcement, index compatibility, supported reranking
or a measured outcome. No filesystem source is ingested, vector index created,
embedding computed, service invoked or retrieval workload executed by these edits.

## Validation and remaining acceptance

Eight dedicated core cases cover all five exact payload shapes; omitted values;
large integers and false booleans; rejected raw credentials and invalid settings;
same-kind multiple roles; catalog hash/identity/version substitution; retained
relationships; format-4/5 history and old bytes; optional intent and existing
projection findings; structurally valid in-memory candidate preservation; and
managed stale/foreign/deleted write authority. That source checkpoint left T4–T6
outside the command subset. The later [agent editor](AGENT_SELECTIONS.md) admits
T4; the existing unsupported-kind regressions continue to cover T5–T6.

Nine mandatory paused-looper Activity cases cover explicit all-kind creation and
reopen; required vector correction; exact settings through T3 enable/disable,
recreation and re-enable; multiple roles and selective removal; all-kind parcel
recovery; canceled/catalog-stale controls; concurrent saved-head recovery;
in-flight acknowledgment; and narrow RTL layout at twice the normal font scale.
The committed attendance program requires every named method, including the
separately annotated RTL method, and rejects missing/wrong suites, duplicate
identities, mismatched counts and failures/errors/skips. Future additional cases
are permitted. Actual execution results and retained failed attempts are tied to
their exact source in the PR receipts, rather than inferred from authored tests.

Broader #127 remains open. The accepted scope and rules supplied through
[#121](https://github.com/Pick-Your-AI/pick-your-ai-android/issues/121),
[#37](https://github.com/Pick-Your-AI/pick-your-ai-android/issues/37) and the
canonical research owners still govern executable dimension/metric/transport/
access/version findings, skipped dependencies, cost/evidence summaries and the
complete receiving outcome. The existing durable snapshot archive/UI work stays
with its #26 owner. The core test's in-memory candidate is not a delivered export
UI or archive. Simulated Activity tests and an unsigned assembly do not establish
installed lifecycle, TalkBack, physical-device, service or release qualification.
