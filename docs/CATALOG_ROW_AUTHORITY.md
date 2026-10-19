# Current catalog result controls

This bounded [Android #82](https://github.com/Pick-Your-AI/pick-your-ai-android/issues/82)
correction keeps the existing synthetic catalog browser's row callbacks tied to
the result views currently rendered. It changes no catalog query contract,
fixture, display label, picker choice or saved-draft authority.

## Retired rows and current results

Search, paging, tier/kind facets and sort changes rebuild the visible result
controls even when the verified catalog object and snapshot hash remain the same.
The previous callback checked the Activity, selection-enabled state and browser
identity, so a detached Button could still open its old detail. That entry point
could also dismiss the user's current inspector before opening the stale row.

Each render now creates a fresh, opaque in-memory identity before replacing its
result views. A row's callback must retain that exact current identity as well as
the existing Activity, enabled-state and exact-browser guards before opening a
detail. Returning to an earlier query creates new controls; it cannot reactivate
the original detached Button. The identity is not serialized, a catalog version,
a draft mutation token or an authorization receipt for any other operation.

Changing selection-enabled state without rebuilding the result views preserves
their identity and still uses the existing enabled-state guard. Destruction
remains terminal through the existing Activity/section checks. The independent
checked-choice and quantity-resource work keeps its original picker and wording
changes.

## Regression evidence and limits

Four named Activity simulations cover a working current-row control, an old row
after search produces no results, a search-away/back transition while the new
detail remains visible, and page/tier/kind/sort replacement through actual
controls. They retain a saved baseline, pending inputs and a nonempty comparison
tray, and compare those fields after replaying each retired callback. The CI
attendance check requires all four methods to execute without failure, error,
skip, duplicate identity or a mismatched report; every earlier attendance check
remains unchanged.

The original accepted-source failing callback, corrected control timing and
subsequent exact-source execution stay separately recorded in the PR receipts.
JVM simulation does not establish installed-device behavior, remote request
ordering, API paging/retry, real-data publication or the full #82 receiving
criteria. Its canonical acceptance record remains
`docs/delivery/android/work-packages/07.01.md` in `Pick-Your-AI/pickyour.ai`.
