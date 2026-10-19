# Versioned synthetic preview data

This is the fixture/data portion of [Android #77](https://github.com/Pick-Your-AI/pick-your-ai-android/issues/77).
It supplies five closed samples and four immutable delivery-script definitions to
the existing opt-in `:preview` module. It does not add a scenario screen, an asset
repository, a worker, a scheduler or an asynchronous controller. The active
[#58 adapter and dispatch work](https://github.com/Pick-Your-AI/pick-your-ai-android/issues/58#issuecomment-6252008356)
owns those interfaces; a later reviewed consumer must use that accepted boundary.

## Exact inputs and outcomes

The [manifest](../preview/src/main/assets/preview-scenarios/v1/manifest.json) declares
`android-preview-scenarios-1`, classification `synthetic` and frozen schema
`1.0.0`. It binds all nine packaged input names, sizes and SHA-256 values, the
original Android adoption revision and derivation source hashes. It records the
expected dispositions and full reference-report digests for the three evaluated
samples. These are reference observations, not model, vendor or product evidence.

Only four new input files are needed. The five unchanged Build/catalog/ruleset/
dependency-graph/Pricebook examples remain the shared inputs. Original frozen
files, schemas, generators, registry, examples and import metadata are untouched.
Derived Build IDs use the explicit `build.android.synthetic.*.v1` prefix; the
partial estimate uses `estimate.android.synthetic.partial.v1`. The unchanged
all-tier Build retains its original `build.fixture.1` identity.

| Sample | Derived input and existing reader | Frozen-reference observation |
| --- | --- | --- |
| Empty draft | Empty objectives, selections and links; original frozen Build validator | Exactly four expected schema errors: missing region, currency and data classification, plus `selections` minimum two. It produces no accepted Build or evaluation. |
| Core build | Original selections with only T3–T5 disabled; existing diagnostic evaluator | All fifteen T0–T2/T6 selections retained; 21 pass, 0 fail, 3 unknown, 21 skipped; indeterminate and blocked. |
| Unknown evidence | Original all-tier Build, catalog and rules, without cost/resource estimates | 38 pass, 0 fail, 3 unknown, 4 skipped; indeterminate and blocked. Missing cost and resource evidence remain unknown. |
| Failed rule | Core Build requesting 9,000 context tokens plus the original 512 output reservation | Both model-context and context-policy limits actually fail; 19 pass, 2 fail, 3 unknown, 21 skipped; invalid and blocked. Rules are not modified. |
| Partial quote | Original storage line only, bound to the core Build, inspected by the existing cost adapter | Recorded 2.50 USD subtotal/total and 0.00 tax; compute explicitly unpriced; thirteen other active selections remain unclassified. |

The partial sample retains the original storage line's quantity, unit price,
rate ID, method, confidence and assumptions as JSON values. It does not
recompute use or rate eligibility. Its recorded subtotal is neither a complete
stack price nor a proved lower bound. The existing adapter continues to mark
freshness, free/included treatment and budget approval as not established.
Reference timestamps are inherited fixture values, not a clock or run receipt.

## Admission and isolation

[SyntheticScenarioData](../preview/src/main/kotlin/ai/pickyour/android/preview/SyntheticScenarioData.kt)
accepts a closed scenario enum, supplied manifest bytes and a map keyed only by
[compiled asset names](../preview/src/main/kotlin/ai/pickyour/android/preview/SyntheticScenarioPins.kt).
It is a synchronous data function. It cannot open an asset/file/URI, read a draft,
save, import, share, publish, contact a service or allocate an analysis ticket.

One compiled manifest hash is the version allowlist. Exact byte identity pins
every member of the manifest, including version, classification, expected-negative
disposition, source pins and every asset name/hash. Even harmless reformatting is
rejected. Duplicate keys, changed versions, extra paths, malformed UTF-8, trailing
JSON or a self-declared replacement hash cannot match that allowlist. The reader
never uses a manifest-provided path or schema. Updating a fixture requires a
reviewed version/pin change, not changing a manifest supplied at runtime.

Admission bounds the manifest at 32 KiB, each document at 256 KiB and the complete
document set at 1 MiB. Its caller must bound reads before constructing these byte
arrays. The function checks the exact nine-entry inventory, copies caller-owned
bytes and hashes the private copies before using the existing frozen validators.
Every positive input must validate. The one pinned empty input must produce
`SchemaMismatch`; invalid syntax or an unexpected successful empty Build is not
the documented negative sample. The existing diagnostic evaluator/report receiver
or CostEstimate adapter then validates the selected result. A corrupt unused
input rejects the entire bundle as well.

Returned sample identities always retain `synthetic`, fixture version, schema
version, selected case and the manifest digest. Results retain immutable existing
core values; they expose no mutable caller arrays or accepted empty Build. This
identity is not a saved revision or an asynchronous completion authority. Only
the existing `AnalysisCoordinator` can grant a current opaque attempt ticket.
Failures are finite component-admission reasons, with no raw parser/provider text;
#58 must map them into its canonical asynchronous error boundary.

## Deterministic delivery definitions

[SyntheticDeliveryScripts](../preview/src/main/kotlin/ai/pickyour/android/preview/SyntheticDeliveryScripts.kt)
declares `synthetic-delivery-scripts-1`. Lists and all step values are immutable.
They perform no scheduling, retries or delivery by themselves.

| Script | Fixed definition |
| --- | --- |
| Immediate | Start the selected sample and deliver its success. |
| Delayed | Start, advance virtual time by a disclosed synthetic 750 ms, then deliver success. |
| Failure then explicit retry | Deliver simulated unavailability; stop at a required explicit retry action; start a distinct retry slot and deliver success. |
| Newer first | Start an older core sample, start the selected sample, deliver selected success, then attempt the older success. |

The future #58 consumer must allocate actual tickets, keep version/synthetic
labels visible in every state, map simulated unavailability without claiming a
live outage, and reject the older delivery. It must require the explicit retry
action and retire pending work on cancellation/lifecycle changes. A virtual delay
is not observed performance. These definitions do not prove screen cancellation,
stale-result rejection, main-thread behavior or any installed outcome.

## Reproduction and checks

Use the repository's Python 3.12 environment with all dependencies in
[requirements-contracts.txt](../requirements-contracts.txt). The checker refuses
to qualify schema conformance when either date-time or URI format assertions are
missing; installing `jsonschema` alone is insufficient.

```sh
python scripts/check_preview_scenarios.py
python -m unittest discover -s scripts/tests -p 'test_preview_scenarios.py' -v
python -O -m unittest discover -s scripts/tests -p 'test_preview_scenarios.py' -v
```

The checker verifies all 43 frozen files and both pinned manifests, reconstructs
the four samples using only the unchanged Python reference, validates positive
documents and the precise expected negative, and compares all generated bytes and
the compiled pin table. `--write` reproduces only these five asset files and that
pin table. It never rewrites frozen inputs. Report digests sort object keys while
retaining arrays and every value, using compact UTF-8 JSON; they compare all
findings, messages, severities, selected IDs and reference fields.

The nine host methods cover concrete versions/provenance/disposition changes,
duplicate or malformed manifests, missing/extra/swapped/altered assets,
self-declared replacement pins, document/total bounds, generated-source drift,
unlisted/symlinked outputs and absent format assertions. They exercise the source
checker and frozen Python reference, not Android code.

The ten
[preview conformance methods](../preview/src/test/kotlin/ai/pickyour/android/preview/SyntheticScenarioDataTest.kt)
use packaged assets with the actual existing Kotlin validators/evaluator/cost
receiver. They require full report parity with the Python reference, preserve
core T6 and cost uncertainty, reject every changed/missing input and retain
immutable result/script definitions. The existing unsigned-preview job requires
all ten named cases to execute and pass, without skips, in its ordinary test run.
Follow the [preview build/lint commands](ANDROID_PREVIEW.md) for compiled checks.
No new required status context or dependency is introduced.

During source preparation the first local Python environment was missing both
format providers. Its earlier schema/example run did not qualify the required
format assertions; the corrected checker fails explicitly in that environment.
Compiled preview/JVM execution was also held under the shared disk-capacity
constraint. Those are unexecuted gates until a later exact-source receipt records
them, not test passes inferred from these definitions. No installed/device or
actual scenario-screen validation is claimed by this source note.

## Remaining issue scope

#77 remains incomplete. The #58-owned adapter/scheduler/Activity integration,
visible labels, actual success/failure/retry/cancel/newer-first screen transitions,
no-current-draft mutation/disclosure and lifecycle/accessibility cases still need
that accepted seam and observed tests. All native prerequisites and reviewers
remain. This data component does not close #58 or another consumer issue.

The preview has no enabled real-data publication boundary. Its isolated package
and lack of a promotion capability are actual local controls; they are not a
fabricated production mode or an executed reserved-ID publication rejection.
That literal #77 acceptance still requires the genuine canonical publication
boundary and source-rights admission. Synthetic fixtures never become real
catalog or pricing evidence.

The existing canonical #77 record remains
`Pick-Your-AI/pickyour.ai:docs/delivery/android/work-packages/02.06.md`, and #58's
record remains `02.04.md` there. No acceptance record, criterion, status, owner or
release/production authority is moved or supplied by this component.
