# Complete recorded cost inspection

This is the native recorded-input presentation slice of
[Android #103](https://github.com/Pick-Your-AI/pick-your-ai-android/issues/103).
It consumes the existing immutable
[CostEstimateAdapter](../core/src/main/kotlin/ai/pickyour/core/pricing/CostEstimateAdapter.kt)
result for the fixed packaged Build and CostEstimate examples. It introduces no
pricing calculation, real-data source, current-draft estimate or budget decision.

## Complete records behind the bounded summary

The existing [summary formatter](COST_PRESENTATION.md) remains unchanged. Its
eight-row/eight-entry limits and 160-unit excerpts keep the overview bounded.
The new **Review all recorded cost fields** action supplies complete inspection:

- Identity, Build revision, Pricebook ID, currency, period, recorded time, declared
  coverage and every subtotal/tax/total decimal string.
- Every cost line, including its exact quantity, unit price, amount, meter,
  selection/rate reference, method, confidence and every assumption.
- Every line-to-selection association, every explicitly unpriced selection and
  every locally derived unclassified active selection. Repeated line references
  remain repeated; one selection can have both a line and an unpriced meter.
- Every producer warning, including empty strings and text beyond the summary
  prefix. An empty collection explicitly says that none were declared and does
  not prove completeness.
- The complete retained Build and CostEstimate JSON values. String contents,
  including monetary decimal strings, remain exact. Decoded JSON is not a copy
  of original byte layout or numeric-token spelling, and the browser supplies
  no original-byte checksum or publisher authentication.

Lists contain at most eight entries per page. A selected value uses complete
160-UTF-16-unit parts, each escaped by the established literal renderer. Empty
strings, controls, directional formatting, Unicode line separators and split
surrogate units remain visible recorded data. Full parts can be reconstructed
in order without omitting the tail. URI-like content is never linkified,
resolved, copied to an intent or opened. Back, previous/next page and
previous/next value part retain their actual parent page.

Money fields show the exact accepted decimal string and its explicit currency.
Quantity remains literal, including a schema-permitted terminal newline that
the existing localized formatter declines. No quantity-times-rate operation,
rounding, currency conversion, implicit quantity or billing classification is
introduced. The overview and details come from the same captured immutable
inspection. The complete JSON also exposes the frozen schema version and the
remaining Build fields without inventing a new contract projection.

## Authority and lifecycle

[CostInspectionSection](../preview/src/main/kotlin/ai/pickyour/android/preview/CostInspectionSection.kt)
creates a fresh, nonreused request identity for every explicit read. It retires
the previous record and browser before that read starts. Only a result belonging
to that still-current request can update the summary or enable complete browsing.
A canceled or reversed read, failed source or rejected executor cannot expose a
partial record, adopt an old result or disclose provider exception text.

[CostInspectionBrowser](../preview/src/main/kotlin/ai/pickyour/android/preview/CostInspectionBrowser.kt)
also owns the exact current dialog. It installs a successor identity before
dismissing its predecessor, so the old dismissal cannot retire the new page.
Dismissal, cancellation, replacement and old captured row/button actions cannot
reopen a retired browser. Activity pause retires the request and its pages;
resume requires an explicit new inspection. Recreation restores the existing
guest editor through its own canonical recovery path, not the old cost window.

These controls read the fixed examples and affect only this inspection and
catalog viewport restoration intent. They neither acquire draft/save authority
nor change pending input, durable acknowledgment, revision history, autosave
deadlines, current diagnostic state, selection checks or source access. The
separate current-draft invalidation and comparison/storage-dialog controllers
retain their own implementation and receiving scope.

The platform
[AlertDialog reference](https://developer.android.com/reference/android/app/AlertDialog)
and [Activity lifecycle guidance](https://developer.android.com/guide/components/activities/activity-lifecycle)
were checked on 2026-10-19. Dialog lifetime and Activity pause are distinct
boundaries; these request/window guards are a local implementation choice.
They do not depend on onDestroy being delivered to preserve guest data.

## Meaningful validation

The 14 methods in
[CostInspectionActivityTest](../preview/src/test/kotlin/ai/pickyour/android/preview/CostInspectionActivityTest.kt)
exercise real Views, the unchanged frozen input adapter, queued workers and the
canonical local store in a simulated Activity:

- Full packaged fields/documents and summary/detail identity; eleven lines,
  thirteen assumptions per line, seventeen warnings and all selection references
  beyond the old display cutoffs.
- Large exact monetary strings and a 128-digit Build revision, with the original
  over-budget 240-digit revision retained as a rejected control; terminal-newline quantities,
  empty strings, a surrogate pair split at a part boundary, directional controls
  and URI-like text without navigation.
- Complete, partial and unknown producer declarations; old times, recorded zero,
  an unverified included claim and wholly empty/unknown input without a free,
  complete-total, freshness or budget approval.
- Wrong revision, future version, mixed currency, inconsistent amounts, malformed
  input, source-open failure, retry, executor rejection and caller stream closure.
- Cancellation/reversed completion, same-revision replacement, old menus/rows and
  page controls, actual pause/recreation, pending input and unchanged disk.
- The actual 749 ms plus 1 ms autosave boundary and exactly one resulting revision;
  a 320 dp RTL window at 200% font scale with complete reachable value lines and
  reachable controls.

The workflow requires every named new method, exact suite/class identity,
consistent report totals and zero failures/errors/skips/duplicates, without
forbidding future additional cases. Existing formatter tests and every prior
workflow byte, including the accepted installed-emulator job, remain intact.

With the documented pinned JDK/SDK and strict dependency verification:

    ./gradlew --no-daemon --dependency-verification=strict -PandroidPreview=true \
      :preview:testDebugUnitTest --tests ai.pickyour.android.preview.CostInspectionActivityTest

The ordinary full preview build, lint and configured checks still apply to the
actual published source and any later receiving composition. Source tests,
simulated resources, installed-emulator observations, physical accessibility and
release qualification are reported separately in the PR receipts.

The first local native run on `91dd9cd` compiled the source and ran 22 focused
cases: 21 passed and the extreme-value case failed before opening its browser.
That test supplied a 240-digit JSON revision beyond the unchanged reader's
128-character numeric-token budget. The correction uses an exact 128-digit
revision for the positive browsing path and keeps the 240-digit input as an
explicit rejected control. The 260-digit monetary string, 420-digit quantity,
Unicode parts and every browsing assertion remain. This is a fixture correction;
the production reader and its limits are unchanged. The earlier loopback-socket
setup failure executed no project tests. Both originals remain separate from
the corrected source's later execution.

## Exact #103 acceptance boundaries

All four literal criteria remain open. This work adds complete readable recorded
input; it does not turn an open shared-data or pricing prerequisite into a pass.

| Literal criterion | Bounded implementation and regression scope | Remaining receiving work |
| --- | --- | --- |
| Known subtotal, missing items, ranges and assumptions visible; unknown never becomes zero or a complete total. Partial, stale or mixed-currency evidence cannot falsely pass a budget gate; free, included and unknown are distinct. | Every recorded amount, missing association and assumption is inspectable. Existing mixed-currency rejection and all unestablished evidence states remain. | Accepted ranges, billing classifications, real-rate evidence and approved budget consumption are not represented by this fixed v1 inspector. |
| A partially priced stack displays a known subtotal under stated assumptions plus unpriced components, without presenting a complete total or assigning zero to missing items. Call it a lower bound only when the approved pricing model proves that interpretation. | The unchanged summary and complete details retain the same declaration and all unpriced/unclassified references. Zero and empty collections confer no completeness or lower-bound status. | Approved usage/arithmetic and an accepted real-data estimate under #85. |
| Free/included treatment follows accepted evidence policy and cannot silently mask an unknown billing classification. | Producer claims stay literal and free/included treatment remains NOT_ESTABLISHED. | Governed eligibility/source evidence and receiving approval; no classification is invented. |
| The summary and detailed breakdown agree on currency, period, coverage and current revision, including stale/error states. | Both views use one captured immutable fixed-example result; canceled/replaced/rejected work cannot update it. | A current-draft real-data estimate and approved stale/error integration, distinct from this fixed fixture. |

The research #39 deliverable is now closed for its reviewed research scope; its
closure is not Android implementation or approved real-price publication.
[Android #40](https://github.com/Pick-Your-AI/pick-your-ai-android/issues/40),
[#85](https://github.com/Pick-Your-AI/pick-your-ai-android/issues/85), the canonical
pricing/source authorities and affected receiving reviewers keep their actual
requirements. The authoritative acceptance destination remains
docs/delivery/android/work-packages/10.03.md in Pick-Your-AI/pickyour.ai.
This contribution moves no record, issue relationship, owner or criterion.
