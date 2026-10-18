# Explicit local agent, tool and memory selections

This partial [Android #128](https://github.com/Pick-Your-AI/pick-your-ai-android/issues/128)
continuation adds the five frozen optional T4 kinds to the existing explicit local
component controls: `agent_framework`, `tool`, `mcp_server`, `memory_store` and
`integration`. It builds on the [retrieval editor](RETRIEVAL_SELECTIONS.md) and
[foundation editor](FOUNDATION_SELECTIONS.md), retaining the native Views form,
typed reducer, strict parser and canonical managed-library save adapter.
Actual published source, current-main composition, review and execution belong
to their exact delivery receipts.

The canonical acceptance record remains
`docs/delivery/android/work-packages/13.02.md` in `Pick-Your-AI/pickyour.ai`.
This file records implementation scope. It neither relocates acceptance nor
assigns research owners, grants tool privileges or closes #128.

## Exact frozen fields and catalog assumptions

All five kinds are optional and have cardinality `many` in the unchanged
[component registry](../contracts/v1/schemas/component-registry.json). The editor
uses exactly the existing kind-specific
[Selection settings](../contracts/v1/schemas/selection.schema.json). Every field
listed below is optional; `{}` remains an explicit unanswered settings object.

| Kind | Optional settings |
| --- | --- |
| `agent_framework` | `agent_count`, `max_iterations`, `timeout_ms`: integers of at least 1 |
| `tool` | `enabled`: boolean; `allowed_scopes`: array of unique strings; `timeout_ms`: integer of at least 1; `credential_ref`: opaque `secret://` reference |
| `mcp_server` | `endpoint`: URI string; `credential_ref`: opaque `secret://` reference; `read_only`: boolean |
| `memory_store` | `ttl_hours`, `max_entries`: integers of at least 1; `encryption_key_ref`: opaque `secret://` reference |
| `integration` | `enabled`: boolean; `endpoint`: URI string; `credential_ref`: opaque `secret://` reference; `rate_limit_per_minute`: integer of at least 0 |

The objects remain closed to extra fields. Optional omissions, explicit false,
permitted zero, exact large integers, array order, string whitespace, URI spelling
and opaque reference text remain unmodified. The `allowed_scopes` array can be
empty; its entries are unique strings under the frozen schema, without a new
scope vocabulary or trimming policy. A scope declaration is not a permission
grant. The existing frozen URI, reference and parser semantics remain authoritative;
this continuation adds no scheme allowlist or credential transport mechanism.

The exact catalog hash, item ID, kind and version retain the corresponding
[CatalogItem specifications](../contracts/v1/schemas/catalog-item.schema.json).
Their fields are distinct from editable Selection settings:

| Kind | Catalog declarations retained through its exact item/version |
| --- | --- |
| `agent_framework` | Programming languages and reported MCP, memory, multiple-agent and tool-execution support |
| `tool` | Protocol, side-effect classification, authentication methods, required scopes and whether it is networked |
| `mcp_server` | Transport, tool references, authentication methods and trust level |
| `memory_store` | Memory/backing types, encryption-at-rest declaration and TTL support |
| `integration` | Integration type, authentication methods and associated tool references |

The editor does not copy those specifications into settings or infer missing
capabilities. For example, `transport` is not a permitted MCP Selection field.
Adding it to settings rejects the complete form. Selecting a catalog item whose
transport is documented does not demonstrate a working connection or the user's
permission to use it. Existing catalog/detail and diagnostic inspection retain
their separate source/evidence scopes.

## Explicit controls and independent intent

Add opens with an empty role, unchecked component enabled checkbox and visible
`{}` settings. The user supplies the identity choice, role and enabled state;
there is no automatic framework, tool, server, memory store or integration.
All five kinds allow several enabled selections with distinct roles. A duplicate
`(kind, role)` rejects the entire edit even when one selection is disabled.

The add/settings forms show the relevant fields and limits. Invalid JSON,
duplicate keys/scopes, wrong types, malformed URIs, non-reference credentials,
unknown fields and over-limit input stay visible without a valid-prefix save.
The existing 16,384-character settings editor and bounded JSON parser remain
unchanged. Larger retained settings are never truncated, including when the
separate role editor preserves them through an explicit role change.

Each component's `enabled` property, the optional `settings.enabled` on tool or
integration, and the existing T4 `ENABLED`/`DISABLED`/`UNKNOWN` intent are separate
stored values. Editing one never silently changes another. The optional-tier
controller continues to own its original authority and lifecycle behavior;
this implementation does not replace that controller or its owner's continuation.

The accepted [snapshot projection](DRAFT_SNAPSHOT_PROJECTION.md) withholds unknown
T4 intent, disabled T4 with an enabled T4 component, and enabled T4 with no enabled
T4 component. The editor retains incomplete or contradictory inputs for explicit
correction. One enabled tool can satisfy the structural T4 presence condition
without forcing a framework, MCP server or memory store. Other typed prerequisites,
required foundation fields and exact artifact/catalog checks still apply.

Structural preparation of an in-memory Build candidate does not produce an
evaluation grade, approved skipped result, zero-cost result, transport finding or
readiness assertion. No unmeasured compatibility or missing permission appears as
a verified pass merely because local input was saved.

## Persistence and external effects

The existing add, exact same-kind replacement, settings/role edit, enable/disable
and remove commands now admit T4. All catalog pins remain exact; no latest-version
fallback or substitute item is chosen. Replacement retains selection identity,
role, enabled state, settings, notes, extensions and links. Removal deletes only
the selected identity and incident links. Other selections remain unchanged.

The canonical store retains draft/liveness authority, original revision/hash CAS,
guest fields, workload inputs, optional-tier maps, metadata and immutable history.
Format 4 already encodes these frozen payloads and operations. A real role change
uses the accepted format-5 `CONFIGURE_ROLE` transition; there is no new format or
silent legacy migration. Reopen, restore and stale/foreign/deleted rejection keep
the original boundaries. Saving does not create an archive or public export UI.

Canceled, replaced or catalog-stale dialogs cannot dispatch a later write.
Pending form checkpoints retain only bounded raw input for explicit review after
parcel recreation. A newer saved head or in-flight checkpoint restores read-only
input without rebasing old consent. Repeated or retired callbacks cannot replay an
acknowledged write. Existing technical-input/IME and optional-tier owners retain
their paths and accepted changes during current-main integration.

Endpoints and opaque secret references remain plain stored text. Form guidance
asks users to keep credentials out of endpoints and use only opaque references
where permitted. The component editor does not resolve a reference, open an
endpoint, connect an account, start an agent, invoke a tool, subscribe to a server,
send a webhook, create memory, enforce encryption or delete remote data. Declaring
`read_only` or an allowed scope cannot enforce it against an external system.

## Validation and remaining acceptance

Eight dedicated core cases cover all five minimal kinds and every frozen field;
exact arrays/URIs/references/numbers/booleans; rejected duplicate scopes and invalid
input; same-kind roles; exact catalog substitutions; source/history formats and
old bytes; independent intent and optional memory omission in structural candidate
preparation; and managed-library metadata/relationship preservation with stale,
foreign and deleted authority. Existing unsupported-kind regressions now retain
the remaining T5–T6 boundary without dropping their assertions or method names.

Nine paused-looper Activity cases cover all-kind creation/reopen; rejected
scopes/endpoints/references with retained input; literal URI fields without
automatic links or external Activity launches; T4 disable/reopen/re-enable with
exact settings; multiple roles and selective removal; all-kind parcel recovery;
canceled/catalog-stale controls; concurrent-head read-only recovery; in-flight
acknowledgment; and narrow RTL layout with twice the normal font scale. The
mandatory workflow guard names all nine methods, including the separately
annotated RTL case, and rejects absent/wrong/duplicate/skipped/failed reports while
permitting future added cases. Execution receipts retain actual source identities
and any failed attempts; authored tests alone are not a passing result.

Broader #128 remains open. Accepted research/Android decisions through
[#121](https://github.com/Pick-Your-AI/pick-your-ai-android/issues/121),
[#37](https://github.com/Pick-Your-AI/pick-your-ai-android/issues/37) and the relevant
T4 research owners still govern executable transport/permission/memory findings,
evidence grades, cost/skipped summaries and share-safe output. Provider access,
workload evaluation, public sharing, installed lifecycle, TalkBack, physical-device
durability and release qualification require their own evidence. Frozen v1 and
the successor-contract review boundary remain unchanged.
