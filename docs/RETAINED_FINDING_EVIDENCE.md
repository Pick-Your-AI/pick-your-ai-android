# Evidence absence in retained-snapshot findings

This correction addresses the evidence explanation portion of Android
[#101 criterion 1](https://github.com/Pick-Your-AI/pick-your-ai-android/issues/101).
Each finding in an accepted retained-snapshot report now explicitly states when
no evidence entries were supplied. Evidence correctness, freshness and provenance
remain unestablished. The finding keeps its independently recorded outcome and
severity; missing evidence does not turn an unknown, skipped or failing finding
into a pass.

## Accepted input boundary

The existing `local-retained-frozen-v1-no-external-evidence/1` profile remains
unchanged. Its `LocalSnapshotEvaluator.completeReport` validation requires empty
evidence entries for every accepted finding. This UI correction describes that
actual absence. It does not admit an evidence attachment, infer source quality,
or change the frozen evaluator, report receiver, input diagnostics or readiness.
The report still identifies separately withheld resource and cost inputs,
captured Build revision, retained-bundle hash and fixed reference report time.
See [RETAINED_SNAPSHOT_EVALUATION.md](RETAINED_SNAPSHOT_EVALUATION.md) for the
complete local-profile and historical-inspection limits.

`SnapshotEvaluationSection` uses `FindingsInspectionText.evidence` for each
accepted finding. That function is extracted without changing the existing
fixed-report evidence rendering: absent values, typed JSON, quoted source
versions, escaped controls, truncation and retained-entry counts keep their
original treatment. Reusing the formatter adds no evidence-navigation or
remediation authority. The report remains a read-only inspection of exact saved
inputs; draft, archive and pending editor contents are not changed.

## Regression evidence

The original actual-Activity regression failed against accepted main
`95e74a6a0c03b9396f5dcbba0773feb6942af3de` at the missing no-evidence disclosure.
The same test then passed unchanged after the formatter correction. Its original
log, XML and test source are retained separately from later suite results.

`SnapshotEvaluationPresentationActivityTest` creates real private retained
snapshots and executes the unchanged frozen evaluator. Four required cases check
unknown, warning failure, pass and skipped findings. The skipped case supplies
an explicitly false applicability predicate; the unknown case requests an
unsupplied cost metric. Every case checks recorded outcome/severity, exact input
identity and absence of private or source-byte mutation. The new cases also
require the explicit no-evidence explanation and reject fabricated observed
evidence. The existing workflow guard requires those methods to execute without
missing, duplicate, failed, errored or skipped testcases.

The neighboring formatter suite checks the unchanged generic evidence renderer,
including JSON null versus missing values, wide numeric literals, empty source
versions, hostile controls and bounded multi-entry display. That generic
receiver coverage is separate from the retained profile's no-external-evidence
admission rule.

```sh
./gradlew --offline --no-daemon --dependency-verification=strict \
  -PandroidPreview=true :preview:testDebugUnitTest \
  --tests ai.pickyour.android.preview.SnapshotEvaluationActivityTest \
  --tests ai.pickyour.android.preview.SnapshotEvaluationPresentationActivityTest \
  --tests ai.pickyour.android.preview.FindingsInspectionTextTest \
  --tests ai.pickyour.android.preview.SnapshotFindingGuidanceActivityTest
```

These checks are simulated Android observations. Installed-emulator execution,
physical devices, TalkBack, accepted real-input evidence, feasible remediation
and complete #101 receiving review retain their separate requirements. Canonical
acceptance remains `docs/delivery/android/work-packages/09.03.md` in
`Pick-Your-AI/pickyour.ai`; this technical guide does not relocate it or close the
work package.
