# Component comparison (#111, partial)

The synthetic native preview can retain two exact catalog pins, show their typed
recorded fields in a stacked comparison, and hide matching recorded values while
keeping missing, unknown and non-comparable rows visible. Comparison is transient
read-only UI state. It does not select a draft component, acquire a save token,
write a revision, evaluate compatibility, load a pricebook rate or rank items.

## Bounded selection and exact identity

Two slots are an engineering bound for this synthetic phone prototype, keeping
each field's two values and repeated headings together. This does not adopt the
website's four-item limit or establish the accepted production mobile limit.
That product and receiving decision remains part of #111.

Adding from catalog details requires the same validated browser's exact complete
row and snapshot SHA-256. Each pin retains item ID, item version and snapshot hash;
matching display names never merge pins. A repeated exact pin is a no-op. The
third distinct addition is refused with an explicit remove-first message.
IDs are bounded to 160 UTF-16 code units and versions to 128; an oversized identity
is refused without truncating its pin or changing the existing tray. These bounds
cover the frozen ID grammar but deliberately bound the otherwise variable version
string in saved UI state. Full detail remains available for a refused identity.

Only the small bounded pin lists and differences-only preference enter the
Activity saved-state Bundle. Detail Back navigation, a pending editor form and
Activity recreation preserve that set without copying a save authority. Malformed
restoration clears the comparison tray visibly and preserves pending editor
inputs. The tray does not establish durable draft storage or a historical catalog
download facility.

A different snapshot hash makes the existing pin unavailable even when its item
ID, version and display name are unchanged. The row remains visible with its exact
identity and an explicit remove/reload recovery explanation. Only that original
snapshot can resolve it again. A user may remove it and explicitly add a currently
available item; no latest-version substitution is performed.

## Recorded values and conditions

Comparison consumes the immutable details projection described in
[CATALOG_DETAILS.md](CATALOG_DETAILS.md). It compares the same frozen kind, schema
field name and schema type, whose numerical units are already canonical. Different
kinds, absent field definitions and different types are marked non-comparable.
There is no general convertible-unit algorithm or conversion between measurements,
routes, currencies, meters or workload bases.

For supplied numeric fields, arbitrary-precision decimal comparison treats
equivalent JSON spellings such as `12.50` and `12.500` as the same recorded value
without changing the displayed source spelling. This does not establish capability
equivalence or applicability. Other supplied values use exact recorded JSON
equality, including collection order. Different precision or context values stay
different; similarity of a neighboring number does not combine their conditions.

Missing, explicitly unknown, empty collections and collections containing unknown
entries remain distinct from supplied false and zero. A missing numerical value
is not rendered as zero. The differences-only view hides only matching supplied
recorded values; it retains every unknown and non-comparable row. The associated
language describes recorded-value matches without a better/cheaper verdict.

Each item's exact identity, snapshot metadata, license declarations and bounded
provenance remain above the field comparison. Frozen condition/reference fields,
including provider/hosting/accelerator references, precision, context, region and
workload metrics, remain displayed even when their matching rows are hidden.
Provider references still do not establish an offering or serving route. No
pricebook rate is loaded, and the existing explicit unknown currency, region,
meter and effective-period language remains visible. A billing class is not an
applicable free quote. Existing conservative source labels remain unchanged.

## Phone presentation and lifetime

The comparison uses a vertical scroll view with wrapping headings. Every stacked
field cell repeats its component name, full bounded ID and exact version, so the
reader does not need to infer which similarly named version a value describes.
The two component headings and each field heading expose platform accessibility
heading semantics where supported. The differences control is a labeled native
check box with a minimum 48 dp target. The custom dialog carries the Activity's
resolved layout direction. Text uses the existing bounded literal formatter;
control/format characters are escaped and truncation is visible.

The add action occupies the detail custom view and preserves the accepted Close,
Select and Sources actions. Source navigation keeps its separate exact-URI
confirmation flow. Opening or changing a comparison never launches a URI.

Callbacks require their active owner. A dismissed detail cannot add a pin, a
replaced tray remove control cannot remove a subsequently re-added pin, and a
dismissed comparison check box cannot change the current preference. Activity
destruction closes the owned comparison dialog and rejects its retained add,
remove, open and toggle controls. These checks do not relax the existing editor,
source-navigation, managed selection or storage-liveness guards.

## Validation and remaining acceptance

Seven core regressions cover same-name exact versions, limit/removal, forged or
stale lookup, oversized identity refusal, exact-snapshot recovery, arbitrary
precision, unknown/false/zero/empty distinctions, cross-kind refusal and immutable
state. Six mandatory Activity regressions exercise actual dialog controls and
Back navigation, pending form preservation and recreation, retained/dismissed
callbacks, unavailable snapshot recovery, differences with conditions, malformed
restoration, and a measured 320 dp RTL layout at 2x font scale. That measurement
checks wrapping headings without ellipsis and the check box's target, label,
checkable state and focusability. It is simulated Android evidence, not a
screen-reader session on an installed device.

The combined local strict gate on accepted main `7f3d59b` includes core checks and
assembly, unsigned preview assembly, lint and all Activity simulations. Exact
published-source hosted results and independent review are recorded in the
delivery PR; local execution does not replace those gates.

| Live #111 criterion | This bounded slice | Remaining acceptance |
| --- | --- | --- |
| Normalize comparable fields and conditions; unknown remains explicit; accessible horizontal or stacked comparison on phones. | Same-kind canonical-field numeric spelling comparison; typed unknowns and conditions retained; stacked native prototype. | Accepted production comparison limit, any agreed convertible-unit cases, real-data semantics, complete #80/#97 inputs and receiving review. |
| Different versions or offerings remain distinguishable even when display names are identical, and missing fields are labeled rather than rendered as zero. | Exact ID/version/hash pins, repeated version headings, no latest fallback, explicit missing/unknown/false/zero distinctions. | Accepted offering identity/real catalog coverage and installed unavailable-version recovery. |
| A comparison never merges rates with different currencies or workload bases into an unsupported cheaper/better verdict. | No rate join, arithmetic, conversion or ranking; explicit unavailable rate context stays visible. | Accepted pricebook applicability/freshness joins and their versioned negative cases if rates are added. |
| The selected comparison set and headings remain usable at the supported narrow width and font scale with screen-reader navigation. | Bounded saved tray, wrapping repeated headings, 320 dp RTL/2x simulated measurement, platform heading and check-box semantics. | Supported device/window/font/locale matrix, installed TalkBack traversal, manual accessibility and Android catalog/UX/data-consumer acceptance. |

The canonical acceptance record remains
`docs/delivery/android/work-packages/07.04.md` in `Pick-Your-AI/pickyour.ai`.
This partial implementation does not close #111, change ownership or mark a
receiving decision complete. Frozen v1 bytes, dependencies, permissions, signing
configuration and production delivery are unchanged.
