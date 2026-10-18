# Transient catalog list position

This is a bounded contribution to the list-position part of
[Android #82](https://github.com/Pick-Your-AI/pick-your-ai-android/issues/82)
criterion 3. It extends the existing synthetic search, facets, sorting and local
page restoration. The canonical acceptance record remains
`docs/delivery/android/work-packages/07.01.md` in `Pick-Your-AI/pickyour.ai`.
No work-package checkbox, owner or acceptance path changes here.

## Restored behavior

When a catalog row intersects the scroll viewport, `CatalogViewport` records the
first visible row's exact item ID, version, loaded snapshot hash and signed pixel
offset from the viewport top. These five small, format-versioned Bundle entries
are transient UI state. They contain no draft revision, save authority, source
evidence or document payload. If the user is elsewhere in the editor, the catalog
does not invent an anchor or move them into the list.

After recreation, restoration waits for both catalog loading and the draft-loading
state to finish. The retained item must exist on the rendered page with the exact
version in the exact snapshot. A pre-draw callback uses the measured row's current
position, allowing upstream content to change height. It consumes the anchor
before scrolling, so subsequent draws never force the old position again. A
viewport or row size change clamps the old offset only enough to keep that exact
row visible; it does not select a nearby row. Long arithmetic prevents malformed
extreme offsets from overflowing the scroll destination.

The implementation follows Android's
[pre-draw callback contract](https://developer.android.com/reference/android/view/ViewTreeObserver.OnPreDrawListener),
which runs after the view tree has been measured and assigned frames. The
[saved UI state guidance](https://developer.android.com/topic/libraries/architecture/saving-states)
distinguishes this transient state from durable application storage. These platform
references were checked on 2026-10-18.

## Cancellation and lifecycle

Any newer search, facet, sort or page action cancels pending restoration. Touch,
keyboard, wheel and explicit accessibility scroll actions also take precedence
while loading. Their normal Android handlers still run. An accepted row click,
including a direct accessibility action, cancels a pending restoration before
opening details. Opening and closing that dialog keeps the existing viewport in
place.

Changed snapshot bytes, a missing row, a different version or unsupported saved
state cannot restore the old anchor. Existing catalog behavior still resets the
page and identifies a changed snapshot to the user. Invalid browse state discards
its viewport anchor together with the previous browse settings. A second
recreation before loading finishes retains a valid pending anchor. Activity
destruction removes the callback and retires its host and rows. If window attachment
has transferred the original observer's listeners, removal uses the host's current
live observer; a consumed or retired callback cannot remain in the attached tree.

This is view navigation only. Filtering, details and restoration cannot rewrite
the saved draft baseline or enable a save. The existing exact snapshot and
editor-baseline checks continue to own selection authority.

## Reproducible checks

Use the pinned toolchain and setup in [ANDROID_PREVIEW.md](ANDROID_PREVIEW.md):

```sh
./gradlew --no-daemon --dependency-verification=strict -PandroidPreview=true \
  :preview:assembleDebug :preview:lintDebug :preview:testDebugUnitTest
```

The workflow requires these separate reports, with every named case present and
no failed, errored or skipped case. It preserves the existing Activity, snapshot
callback and cost presentation gates.

| Report | Required coverage |
| --- | --- |
| `CatalogViewportTest` (nine cases) | Same row and offset after upstream height changes; consumption after one draw; repeated recreation during loading; changed snapshot, missing row, wrong version and unsupported format; newer touch, key, wheel, accessibility or explicit navigation; retired owner and snapshot replacement before draw; callback removal after observer transfer during window attachment; direct/accessibility detail action cancellation; no off-catalog anchor; resized geometry and extreme offsets. |
| `PreviewCatalogViewportTest` (one case) | Actual `PreviewActivity`, native text layout and explicit Bundle recreation: save a baseline, search the fictional fixture, reach page two, position a row at -19 pixels, open/close details, recreate, then verify the same query and exact row offset, unchanged durable baseline and disabled Save action. |

These Robolectric cases use API 28. The actual Activity case measures a fixed
480-by-640 mdpi window so its exact-pixel assertion compares the same geometry.
The utility cases separately exercise changed upstream and viewport geometry,
including extreme saved offsets and the row-visibility bounds.
The source change introduces no SDK, dependency, frozen contract or fixture change.

## Remaining acceptance

The local anchor adds reproducible Bundle restoration for the bounded synthetic
browser. It is not an installed rotation, system process-death, physical-device
or TalkBack result. Simulating an accessibility action establishes its cancellation
branch; it does not establish an accessible installed journey. A saved anchor
does not promise persistence after force-stop, dismissal or loss of transient
state, and positions outside visible catalog rows are not captured by this feature.

Remote paging, transport cancellation/retry/ordering, accepted real catalog
publication and display labels, installed navigation/accessibility and receiving
acceptance remain open in #82. Final source review and exact-source hosted checks
are required before delivery. The existing installed-emulator controller remains
separate; no production or issue-wide completion follows from these simulations.
