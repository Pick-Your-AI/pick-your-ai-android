# Inspect complete comparison values

The local Android preview lets you inspect the complete recorded value behind a
shortened comparison cell. Start with the existing two-component comparison tray,
open the comparison, and choose **Inspect component … complete value** beneath
the field you want to read.

The value page repeats the component identity and exact version, snapshot
context, field name and recorded value state. Use **Next part** and
**Previous part** to read a long value, or switch to the other component's value
for the same field. **Back to compared fields** returns to the captured table
with its differences filter and scroll position preserved.

Values retain their recorded JSON representation. Control and formatting
characters appear as visible escapes, and pagination does not discard recorded
characters. Missing fields, explicit unknowns, empty collections, false and zero
remain distinct. A field that is not defined for the other component's kind
does not borrow a value from an unrelated field.

Inspection does not change a draft, open a network link, substitute an unavailable
version, convert units or produce a cheaper/better verdict. Each component keeps
its own recorded conditions and pricing context.

Changing the catalog or tray, opening another detail or storage view, dismissing
the comparison, or destroying the Activity retires the old comparison controls.
Open a new comparison to inspect the currently available pair. This prevents a
button retained from an older page from replacing a newer dialog.

New Draft, Duplicate, Recovery Copy, library entry, delete or cleanup-retry,
retained-history details, and revision-restore confirmations also close the value
inspector. Cancel the storage confirmation and open a new comparison to continue
reading the captured pair.

Opening **Privacy & data** also retires the value inspector and any pending local
revision comparison. Its disclosure stays in front of controls retained from an
older comparison. Closing it leaves pending draft fields and the saved baseline
unchanged; open a new comparison to continue inspection.

Headings identify the component and field on every page. Navigation labels wrap
inside the scrolling view. This guide describes the synthetic preview; supported
installed-device and screen-reader acceptance, real-data semantics and the
production comparison limit remain part of the broader work package.

See [Component comparison](CATALOG_COMPARISON.md) for the existing comparison
semantics and remaining acceptance boundaries.
