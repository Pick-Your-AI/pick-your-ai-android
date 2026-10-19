# Search and component-link selection handoff

The unsigned Android preview connects a catalog search result to the existing typed
component add/replace transaction. A bounded incoming component route can select an
exact catalog identity for inspection. Each component add or replacement requires
the explicit chooser/form action and the canonical managed-store transaction.

This implements the local behavior scoped by [Android #98](https://github.com/Pick-Your-AI/pick-your-ai-android/issues/98)
inside the adopted `:core` and `:preview` modules. The parser is preview navigation
state; it changes no frozen v1 contract, catalog publication, shared endpoint or
saved-build wire format.

## Search details to an acknowledged draft

Catalog details offer **Use in draft** alongside the existing comparison action.
The full-record, price, source and opaque sample controls remain available under
their existing rules. Opening details performs no draft transaction.

**Use in draft** captures the acknowledged draft ID and record identity, catalog
item/version/snapshot, and current storage authority. It is available only while
the Activity is foregrounded, the same draft and catalog are current, storage is
ready, the kind supports typed selection, and the builder matches the saved
baseline. Pending fields retain their ordinary 750 ms autosave behavior; opening
a search result performs no implicit save. A detail action captured before a
saved revision changes cannot acquire the later revision's selection authority.

The action enters [SelectionEditSection](../preview/src/main/kotlin/ai/pickyour/android/preview/SelectionEditSection.kt)'s
existing explicit Add/Replace chooser. The form retains its captured draft
revision, exact kind/item/version, entered role and optional replacement selection.
The existing command validates cardinality, role, settings, catalog membership and
expected revision before one transactional add or replacement. Repeated taps and
callbacks from a consumed or retired form cannot repeat the write.

A changed draft, changed catalog, removed replacement target, duplicate role or
cardinality violation does not overwrite a newer saved record. A current rejected
form retains its correction context; a retired dialog cannot resume navigation.
The underlying search/filter and pending builder fields remain owned by their
original screens.

## Bounded component route

[PreviewComponentRoute](../core/src/main/kotlin/ai/pickyour/core/preview/PreviewComponentRoute.kt)
accepts this private preview route shape:

| Part | Required value or admission rule |
| --- | --- |
| Intent action | `android.intent.action.VIEW` |
| Scheme and authority | `pickyourai-preview://component`, case-insensitive transport spelling |
| Path | Exactly `/v1` |
| Query keys | Exactly one each of `kind`, `id`, `version`, `snapshot`; no additional or encoded keys |
| `kind` | A frozen canonical kind |
| `id` | A canonical 3–120 character catalog item identifier |
| `version` | Exact opaque spelling, 1–256 characters and at most 256 strict UTF-8 bytes |
| `snapshot` | Exactly 64 hexadecimal characters, normalized to lowercase |
| Raw route size | At most 4,096 ASCII bytes |

A value containing reserved query delimiters must percent-encode them. Values are
decoded exactly once using strict UTF-8. Malformed escapes, replacement decoding,
raw spaces/non-ASCII/backslashes, raw `+`, decoded controls, fragments, user info,
ports and ambiguous query shape are rejected. Version spelling is neither trimmed
nor Unicode-normalized. A percent-encoded plus remains an ordinary literal value.

The Activity rejects a component entry carrying a MIME type, ClipData, selector,
or any read/write/persistable/prefix URI grant flag. It derives no draft ID, role,
replacement target or write authority from incoming query values or Intent extras.
The raw URI is neither displayed nor stored in the Activity checkpoint.

Here, **private preview** describes the navigation format and local functionality.
The existing Activity remains exported for MAIN/LAUNCHER, so explicit external
Intents are untrusted inputs. The manifest has no new VIEW intent filter or domain
association. Browser-resolved/public shared-build Android App Links and their
installed-device qualification remain with [#124](https://github.com/Pick-Your-AI/pick-your-ai-android/issues/124).

## Explicit inspection and add

A recognized input displays **Open linked component**. Delivery alone opens no
dialog, selects no local draft and edits no build. The control matches all four
normalized identity fields against the validated loaded catalog and the current
catalog controller. An unavailable kind/item/version/snapshot receives a generic
recoverable explanation. No current item or newer version substitutes for the
requested identity; retained historical bytes are not automatically activated.

A cold component entry remains unbound to the local draft store through
recreation. Read-only inspection is available, but **Use in draft** stays disabled
until the user explicitly chooses a local draft through the existing library flow.
A warm entry preserves the current draft, unsaved builder input, browse text and
open component form. Delivery preserves the existing autosave deadline: a
recognized route arriving at 749 ms neither commits early nor delays the ordinary
save at 750 ms. After its acknowledgement, a fresh explicit detail action can
capture the new saved revision. A new invalid entry retires a previously offered
unopened route and shows the existing generic unsupported-entry explanation.

After inspection, **Use in draft** enters the same captured chooser/form and managed
transaction used by search. The route itself carries no role or replacement
authority. Opening, repeated delivery, backgrounded controls and callbacks retained
from a destroyed Activity cannot write the draft or launch an external Activity.

## Checkpoint and lifecycle

The Activity checkpoint contains a presence marker plus bounded normalized kind,
item ID, opaque version and snapshot digest. Restoration re-runs the parser's
identity admission checks. An invalid or incomplete normalized checkpoint is
rejected without reparsing the original Intent. Existing local-entry recovery,
draft selection and builder checkpoint rules retain their own authority.

Catalog loading temporarily disables the linked-component action. A completed
reload can make the exact requested identity available; a failed or missing match
keeps the original route for an explicit retry. The action checks foreground,
destruction and current catalog state again when invoked. The subsequent detail
and form checks independently enforce saved-draft and transaction authority.

## Required regression evidence

The workflow names every case in these three suites and rejects missing, foreign,
repeated, skipped, failed or inconsistent suite results:

| Suite | Declared coverage |
| --- | --- |
| `PreviewComponentRouteTest` — 14 Core cases | Exact field identity, strict one-pass encoding, bounds, canonical kind/ID, opaque Unicode versions, checkpoint admission, exact snapshot matching and sanitized diagnostics |
| `CatalogSelectionHandoffActivityTest` — 8 Activity cases | Exactly-one insertion, primary/secondary replacement, cardinality/role correction, pending fields, changed draft, removed target, changed catalog and retired recreation callbacks |
| `IncomingComponentLinkActivityTest` — 10 Activity cases | Cold/warm replay, explicit local choice, malformed/oversized/grant-bearing inputs, preserved pending fields and forms, unchanged autosave deadline and fresh selection capture, unavailable pins, corrupted checkpoints and retired lifecycle controls |

Three pending-input observations explicitly remain before 750 ms using the accepted
AutosaveUiClock. The separate incoming positive control crosses the unchanged
749/750 ms boundary, observes one ordinary save and requires a fresh explicit
selection action; the existing full autosave suite remains required.

The incoming suite includes native-graphics measurement at 320dp width, RTL and
font scale 2. It measures the linked-component and detail actions for complete
labels, viewport reachability and 48dp touch targets, then follows the actual
explicit selection action. These remain JVM-simulated Android observations.

Receiving evidence must bind the actual source, full configured runs, original
reports and independent review. Targeted local runs, hosted simulation, installed
emulator/physical-device behavior and release qualification remain distinct. The
literal #98 criteria, accepted #76/#82 handoffs and canonical
`docs/delivery/android/work-packages/07.05.md` receiving record retain their existing
acceptance authorities; this guide does not relocate that record or close the
whole work package.
