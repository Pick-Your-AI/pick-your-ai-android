# Current finding selection context

This is the current saved-draft slice of [Android #101](https://github.com/Pick-Your-AI/pick-your-ai-android/issues/101): a read-only destination for the exact affected selection IDs reported by an admitted current finding. It complements [complete current finding text](CURRENT_FINDING_TEXT.md). It does not change the evaluator, frozen contracts, [current re-evaluation](CURRENT_DRAFT_REEVALUATION.md), fixed report navigation, or retained evidence.

## Captured identity

Open **Inspect affected selections** from a current observation. The observation message and its existing Close, Back to observations, and Full text actions remain available. The new action is a multiline, full-width button with a 48dp minimum height in a wrap-content header alongside the same accessible observation heading; the message retains its standard scrolling dialog message view. Keeping the header separate from the message's content panel avoids competing custom-content and message panels consuming the available height.

The list preserves the finding's reported ID order and shows at most ten rows per page. Each destination resolves that exact ID only against the saved record captured by the same current result. It displays:

- Selection ID.
- Component kind.
- Role.
- Catalog item ID.
- Captured catalog item version.
- Enabled state.

The destination does not substitute a primary role, another item with a similar name, or a current/latest catalog entry. A defensive unresolved or ambiguous lookup remains unavailable. A finding with no affected IDs has an explicit empty-context explanation: it does not identify a missing choice or establish readiness.

The evaluator currently reports enabled saved selections by affected kind. This association is not proof that a selection caused the outcome, and it does not establish a feasible correction. The broader recheck set can include removed or disabled predecessor IDs; that set is not used to populate these destinations. Private settings, notes and storage paths are not rendered.

## Complete identifying text

All identity values use the existing quoted, escaped literal renderer and RTL isolation. Frozen ID, kind and role bounds fit within its 160 UTF-16-unit preview. Catalog item versions can be longer, so **Complete version** exposes every captured version unit in ordered parts of at most 160 raw UTF-16 units. The number of parts derives from the complete captured string, with no fixed page-count ceiling.

Escaping can make the displayed part longer than 160 characters. A supplementary character that crosses a part boundary is preserved as its two escaped surrogate units; joining the independently decoded parts restores the original value. Newlines, bidi controls, quotes and backslashes remain literal data. The dialog message scrolls; this source design and host simulation do not establish installed-device or assistive-technology acceptance.

## Window and source lifetime

Every retained entry, row, pagination and Back callback checks the exact captured source, the exact current analysis state, and the currently showing owned dialog. Internal navigation uses the existing private presentation transition and does not reenter the global sibling-inspection hook. Back returns to the same finding, selection and list page.

Closing or replacing a window retires its callbacks. Existing edit, saved-source replacement, input replacement, retry, store switch, sibling entry, pause and Activity replacement paths continue to retire current presentation authority. Reopening requires the current admitted state. No callback writes a draft, invokes a selection command, changes an outcome, or dispatches a new evaluation merely to navigate.

## Required host regression contract

`CurrentDraftFindingContextActivityTest` uses real canonical saves and reopen, actual catalog/input admission, and the real current evaluator. Its model fixtures use distinct roles and catalog IDs with exact matching versions. Every affected-context journey first checks the actual Current binding and the reported finding IDs; no outcome or Current state is injected.

The ten required methods cover:

1. Exact role, item, version, ID and enabled values with unchanged private draft/library bytes.
2. Complete RTL version reconstruction, independently decoded escaping, a surrogate pair split at the part boundary, and the final tail.
3. An actual current finding with no affected IDs.
4. Ten-plus-two list pagination and Back to the same observation.
5. Dismissed and superseded callbacks plus fresh reentry.
6. Sibling retirement of every context navigation level.
7. A genuinely queued retry and changed input binding.
8. Pending edits, saved replacement and an actually disabled selection excluded from affected rows while retained in the recheck set.
9. Store switching, pause and saved-state Activity replacement.
10. A real attached RTL dialog measured at 320dp width and 440dp height with font scale 1.6: unclipped complete action labels, minimum touch heights, a reachable scrolling message tail, normal navigation and unchanged observation text. Each action is brought fully into view through the platform's native scrolling button bar where needed, with an observed scroll and no enlargement of the viewport; this does not claim all actions are initially visible simultaneously.

The workflow requires all ten distinct named methods to execute successfully, rejects missing, repeated, foreign, nested, failed, errored or skipped results and inconsistent totals, and permits additional distinct passing methods. The preceding current-finding text, current checks, presentation and sibling suites remain unchanged. The focused correction cohort combines these ten methods with those existing 33 methods. The original nine causal methods remain unchanged; the additional geometry control is separately identified.

The file-preservation assertions cover regular private draft/library files under `noBackupFilesDir`. They are not a claim about every application directory. The finite 26-part loop in the test is only a fixture guard; production paging has no such limit. The host cases do not establish maximum-length performance, all window/font configurations, TalkBack, installed emulator/physical-device behavior, hardware evidence, or completion of all #101 acceptance criteria. Those receiving scopes and their existing owners remain separate.
