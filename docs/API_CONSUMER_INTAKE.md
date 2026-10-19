# Android #36: API consumer requirements and reference cases

Status, 2026-10-18: **Android consumer intake for central review.** This packet
supplies a concrete operation inventory, compatibility walkthrough and synthetic
reference cases for [Android #36](https://github.com/Pick-Your-AI/pick-your-ai-android/issues/36).
It proposes consumer requirements; it adopts no successor API, route spelling,
token format, server limit or runtime adapter.

The [Android preparation claim](https://github.com/Pick-Your-AI/pick-your-ai-android/issues/36#issuecomment-6255245140)
and [Research #40 handoff](https://github.com/Pick-Your-AI/pick-your-ai-research/issues/40#issuecomment-6255258324)
preserve the [existing canonical writer](https://github.com/Pick-Your-AI/pick-your-ai-research/issues/40#issuecomment-6253806270),
the live `zwright8`/`tynamite` assignments and the separately owned CD-2/CD-7 work.
No research note or shared-service implementation is duplicated here.

## Inputs, prerequisites and placement

| Input | Exact consumed boundary |
| --- | --- |
| [Frozen Android import](CONTRACT_ADOPTION.md) | Research `9769fcd9ea380b1211669b2753181c7782444178`, package `ai-builder-contract-v1`, version `1.0.0`; all 43 files and both original manifests remain unchanged |
| [OpenAPI](../contracts/v1/api/openapi.yaml) | 12,079 bytes; SHA-256 `6a08d7230c23576889167da6cff48d95093da9dc04d4bfa6ece443ff35089a79`; Git blob `117483451e00ac7d87260565b509f81bf383e405`. OpenAPI syntax `3.1.0` differs from API/artifact version `1.0.0` |
| [Android #21](https://github.com/Pick-Your-AI/pick-your-ai-android/issues/21) | The pinned import and existing local validation are available. Complete API/storage adoption and every receiving boundary remain open; this packet does not complete that native prerequisite |
| [Accepted Android #25](delivery/android/work-packages/01.05.md) | Signed `68e52dd9c79cefe97af65e8fd1e86e4b72d2c42c` selects free guest-local J1–J4. Remote sharing/synchronization and optional accounts remain conditional; private reads and writes still require canonical authority |
| [Accepted service decision](SERVICE_BASELINE.md) | Signed `4c753bd69f3857d9b15051be86078948e61074ac` selects the future shared-service boundary. W1–W9/O1–O6 retain the absent operating origin, custody, deployed authority and recovery evidence |
| [Current central research note](https://github.com/Pick-Your-AI/pick-your-ai-research/blob/08bdd5ef016a1033a00d8a6f6161b0f605712082/research/T0-configuration/component-schema-compatibility.md) | CD-7 consumer/projection experiments retain their explicit proposed/test-only interfaces. A test profile or constrained research mechanic does not amend frozen HTTP v1 |
| [Apple #310 intake](https://github.com/Pick-Your-AI/pick-your-ai-apple/blob/ac38311f51a03b4598d24026726a50217d1d1b08/docs/proposals/issue-34-api-consumer-intake.md) | Separate Apple requirements and 38 inline-schema cases, reviewed in [5564617332](https://github.com/Pick-Your-AI/pick-your-ai-apple/pull/310#pullrequestreview-5564617332). These are receiving inputs, not a shared-route or live-adapter approval |

The intake source is Android main `c0278410f5a8a90776df9de2f466103cba48f9f1`.
Native parent #4 and prerequisites #21/#25 were read directly; #21 remained open,
#25 closed, and #36 had no assignee. The complete 18-open-PR file inventory at
preparation contained no competing packet on these four authored paths.

The intended acceptance record remains
`docs/delivery/android/work-packages/03.03.md` in the website repository. This
consumer note does not move that record or update its criteria/ledger. The same
website repository, stable ID `1422238606`, now uses
[`Pick-Your-AI/pickyour-ai`](https://github.com/Pick-Your-AI/pickyour-ai); historical
`Pick-Your-AI/pickyour.ai` issue references retain their original authority.

## Complete frozen transport inventory

The relative server is `/v1`, not a deployed origin. **Every operation below
inherits global `bearerAuth`; none overrides it for anonymous reads.** Its
`OAuth2/JWT` description supplies no issuer, guest grant, account recovery or
object-access policy. `Error` below means the exact closed
`components/schemas/Error` object: required string `code`/`message`, optional string
`correlation_id`/`field_path`. Code strings are not an enumerated retry policy.

All fourteen operations require the applicable authority and client byte/decode
bounds discussed below. The API declares no universal body/page limit or
pagination contract for any row. Frozen external schemas retain their own field,
array and numeric constraints; their absence of a transport maximum does not
authorize unbounded admission.

| Operation / method and relative path | Existing request and response contract | Declared non-success responses / further intake |
| --- | --- | --- |
| `listComponentKinds` — GET `/component-kinds` | Inline object with `schema_version` string and `kinds` object; no operation parameter | None declared; discovery/version/label authority A1/A8 |
| `readCatalogSnapshot` — GET `/catalog/snapshots/{snapshot_id}` | Required string ID; complete immutable CatalogSnapshot | 404 Error; discovery, exact bytes and paging A1/A2 |
| `readDependencyGraph` — GET `/dependencies/{dependency_id}` | Required string ID; DependencyGraph | 404 Error; exact graph revision and discovery A1/A5 |
| `readRuleset` — GET `/rulesets/{ruleset_id}` | Required string ID and query `version`; exact Ruleset | 404 Error; independent rule/version locks A1/A5 |
| `readPricebook` — GET `/pricebooks/{pricebook_id}` | Required string ID; Pricebook | 404 Error; rates, eligibility and immutable pins A1/A5 |
| `createBuild` — POST `/builds` | Required `Idempotency-Key`; BuildSnapshot; 201 body and declared ETag | 422 Error; private creation, idempotency and uncertain commit A3/A6/A7 |
| `readBuild` — GET `/builds/{build_id}` | Required string ID; current immutable head and ETag | 404 Error; this is not listing or historical revision selection A3/A6 |
| `replaceBuildIfRevisionMatches` — PUT `/builds/{build_id}` | ID, required `If-Match` and idempotency; BuildSnapshot; 200 next revision and ETag | 409/422 Error; stale/uncertain write A7 |
| `evaluateBuild` — POST `/builds/{build_id}/evaluate` | ID/idempotency; positive integer `build_revision`, optional resource/cost estimate IDs; 200 EvaluationReport | 409/422 Error; exact requested tuple and retained evidence A5/A7 |
| `publishResourceEstimate` — POST `/builds/{build_id}/resource-estimates` | ID/idempotency; ResourceEstimate; 201 same artifact type | 422 Error; producer write is not a resource-read operation A5/A6 |
| `estimateCost` — POST `/builds/{build_id}/estimate-cost` | ID/idempotency; positive revision/period days and pricebook ID; 200 CostEstimate | 422 Error; exact build/rates/coverage A5/A7 |
| `recordQualityEvaluationRun` — POST `/builds/{build_id}/quality-runs` | ID/idempotency; QualityEvaluationRun; 201 same artifact type | 422 Error; recording a run neither reads nor performs a benchmark A5/A6 |
| `listCompatibleOptions` — GET `/builds/{build_id}/compatible-options` | ID, required string `kind`, optional string `role` default `primary`; inline revision/kind/options response | None declared; no cursor, limit or full kind/tuple check in this inline shape A2/A5/A8 |
| `proposeDeployment` — POST `/builds/{build_id}/deployment-proposals` | ID/idempotency; DeploymentManifest; 201 pending proposal | 422 Error; proposal creation is not execution authority A5/A6 |

There are 13 paths, fourteen operations and seven required idempotency references.
Creation's description promises Location, but its header map declares only ETag;
the canonical owner must reconcile that before relying on Location. `If-Match`
describes a quoted current revision and next-revision candidate; its schema alone
is only a string. Neither observation weakens the descriptive invariant.

No GET resource/quality/evaluation-history operation, discovery page, build list,
historical build read, fork, delete, share, token lifecycle or version-negotiation
operation is present. No `401`, `403`, `413`, `429` or unsupported-version response
mapping is declared. Their absence is an explicit contract gap, not a promise of
success, a particular replacement status or unlimited retries.

## Android requirements for central disposition

These requirements follow the accepted Android scope and current local boundaries.
Each row asks for an explicit accept/narrow/reject disposition and versioned
representation through #40. The shared website issues retain their current
`PUSHINGSQUARES` assignment; the packet assigns no new human commitment.

| ID | Android requirement and concrete example | Exact missing contract decision | Canonical receiving lane |
| --- | --- | --- | --- |
| A1 Discovery and identity | Discover eligible catalog, graph, rules and rates without treating the newest publication as a replacement for a saved pin. A retained catalog A with an unavailable required graph must not activate a mixed set from B | Published discovery/eligibility model, immutable ID/revision/hash/byte identity and withdrawal behavior; complete collection identity and source-rights policy | Website [#15](https://github.com/Pick-Your-AI/pickyour-ai/issues/15)/[#18](https://github.com/Pick-Your-AI/pickyour-ai/issues/18); Research #40/#41/#60 |
| A2 Query and paging | Search/filter/order changes create a new request identity. Page two of catalog A under one query cannot append after a user selects B or another query; empty discovery and unavailable discovery remain distinct | Query/filter grammar, stable ordering/ties, continuation binding, maximum page/body bounds, expiry and restart/rejection. No cursor field can be inserted into closed v1 CatalogSnapshot/options objects | Website #15/#18; Research #40; Android catalog and Apple R1 consumers |
| A3 List, history and fork | A local incomplete draft stays editable without fabricated required Build fields. A historical revision remains independently addressable; forking creates a distinct identity with explicit provenance and no inherited current evaluation | Authorized list/history/fork interfaces, head-versus-revision semantics, limits, ordering, lineage and incomplete-draft representation. Adopted #42 semantics remain prerequisite to server behavior | Website [#17](https://github.com/Pick-Your-AI/pickyour-ai/issues/17); Research [#42](https://github.com/Pick-Your-AI/pick-your-ai-research/issues/42)/#40 |
| A4 Delete, share and revisit | Preview the exact eligible projection before deliberate transfer. Creator, viewer and fork recipient have distinct powers. A late reply or restored old cache cannot restore a revoked share; local deletion is not remote erasure | Public/private projection, explicit create/read/revoke/delete responses, principal/object binding, retention/expiry and backup reconciliation. Decide historical read access and recipient-copy limits without promising recall | Website #17; Research #42/#40/#60; Android's conditional J5 and Apple R3 |
| A5 Resource and evidence reads | A resource/report body may be schema-valid for the wrong build/revision. Bind the selected build, catalog/rules/graph/rates and relevant model/runtime/context/concurrency to qualified evidence. Missing estimates stay unavailable/unknown, not zero | Actual read/discovery interface and tuple checks; estimator/conditions/uncertainty, measured-versus-estimated distinction, retraction/freshness and permitted caching. Existing POSTs and test-only CD profiles do not supply a released read API | Website [#16](https://github.com/Pick-Your-AI/pickyour-ai/issues/16); Research #40/#41/#39; affected capacity/evaluation consumers |
| A6 Guest authority and lifetime | Registration-free local use remains available. A client owner string, resource ID or verified App Link is not permission. After retirement/account/environment change, callbacks cannot mutate another draft, resurrect a dialog or publish private data | Issuance/custody/expiry/revocation, private read/write checks, viewer/creator scope, authorization error disclosure and any future account transfer. No token, credential or grant field is chosen here | Accepted Android #25/#35; website #17; Research #40/#42; security/affected-client receivers |
| A7 CAS, idempotency and unknown acknowledgment | Keep one logical operation identity, selected object, baseline and exact content through an authorized retry. A process death after a server commit but before acknowledgment cannot become a new write or overwrite a newer local edit | Key grammar/scope/retention, conflicting-body reuse, atomic receipt/head commit, ETag/Location grammar and bounded reconciliation. Recheck current authority before replaying any result; specify stale/commit-unknown/expired-receipt outcomes | Website #17; Research #40/#42; existing Android durable-draft and future synchronization lanes |
| A8 Versions, limits and cancellation | Preserve compatible eligible local data when a response is unsupported, malformed, oversized or late. Cancelled reads must not promote partial bytes. New extension fields cannot be silently discarded to manufacture a v1 object | Separate API/media/artifact versions, explicit compatibility or rejection, request/body/page/decoded-memory limits, typed failure/retry policy and migration/rollback handshake. Client parser limits apply even below a negotiated service maximum | Research #40; Android #21/#36; affected web/Apple/service consumers |

The [existing Android byte reader](https://github.com/Pick-Your-AI/pick-your-ai-android/blob/c0278410f5a8a90776df9de2f466103cba48f9f1/core/src/main/kotlin/ai/pickyour/core/importing/BoundedJsonReader.kt)
defaults to 1,048,576 bytes, depth 32, 100,000 tokens, 16,384 UTF-16 code units per
string, 256 per name and 128 per numeric token. Its current preview safety limits
are **not** approved HTTP or global production limits. Callers may have additional
bounds. A future adapter must bound actual received/decoded bytes, not trust a
declared length, and keep the stream/timeout/cancellation responsibility explicit.
The [frozen validator](https://github.com/Pick-Your-AI/pick-your-ai-android/blob/c0278410f5a8a90776df9de2f466103cba48f9f1/core/src/main/kotlin/ai/pickyour/core/contracts/FrozenSchemaValidator.kt)
resolves only bundled schemas and reports structural failures separately; it
does not confer source rights, caller authority or cross-document identity.

### Required failure/recovery examples

These are concrete review scenarios, not executed HTTP or installed-device cases.
Their future response codes/envelopes must come from the adopted extension.

| Scenario | Required Android result and governing gap |
| --- | --- |
| Catalog A/query Q page one is visible; a filter change starts Q2; Q's page two completes last | Retire Q's continuation and preserve Q2/selected draft. Never combine collection identities; A1/A2 |
| A compatible retained artifact exists; a candidate is truncated, too large, withdrawn or unsupported | Retain the eligible prior selection, report the actual failure, and do not promote partial/newer bytes by fallback; A1/A8 |
| A private build response arrives after environment, principal or editor lifetime changed | Discard the result before domain mutation or window creation. Current authority must be checked independently of matching object strings; A6 |
| Two authorized candidates use the same current If-Match; one commits first | Preserve the winner and return the adopted stale-write conflict to the other. X01 checks only Error shape and the existing declared409 boundary; A7 |
| An authorized write commits; response is lost; app restarts with a pending operation | Reconcile the same object/key/body/authority and durable result before displaying success or retrying. A timeout is not proof that no write committed; A7 |
| Revocation/deletion is acknowledged, then an old backup or queued response returns | Reconcile current deletion/authority before any read, replay or share activation; distinguish pending request, confirmed erasure and local-only removal; A4/A6 |
| A requested build revision has only evidence for a different tuple | Preserve unknown/unavailable and the actual evidence identity. R02/F02 intentionally pass schema shape and must fail the future association check; A5 |
| Artifact version2 arrives at a v1 client, or unauthenticated access is attempted | Reject unsupported artifact shape without rewriting pins; use the adopted authority/version error mapping when it exists. B03/C04 and X02/X03 do not invent HTTP negotiation; A6/A8 |

## Existing web-client compatibility walkthrough

Source read on 2026-10-18 at website
[`dc66a9e269917ab7241c7d08fd0e7f2a2f039872`](https://github.com/Pick-Your-AI/pickyour-ai/tree/dc66a9e269917ab7241c7d08fd0e7f2a2f039872).
The complete [HTTP client](https://github.com/Pick-Your-AI/pickyour-ai/blob/dc66a9e269917ab7241c7d08fd0e7f2a2f039872/src/data/http/client.ts),
[providers](https://github.com/Pick-Your-AI/pickyour-ai/blob/dc66a9e269917ab7241c7d08fd0e7f2a2f039872/src/data/http/providers.ts)
and [provider tests](https://github.com/Pick-Your-AI/pickyour-ai/blob/dc66a9e269917ab7241c7d08fd0e7f2a2f039872/tests/unit/providers.test.ts)
were read. This is a source walkthrough; those tests were not rerun here, and
their fake API does not establish live authority or server durability.

| Observed current consumer | Compatibility consequence for central review |
| --- | --- |
| Catalog reads use configured IDs and expect complete CatalogSnapshot bodies; filtering occurs locally | Keep v1 complete-body meaning. New discovery/pages need explicit opt-in/versioning, never an in-place paged replacement |
| Graph/rules/pricebook providers use known IDs and rule version; resource provider returns `unavailable` because v1 lacks a read operation | Preserve explicit unavailable behavior until an adopted provider exists. Do not reinterpret a producer POST or inject guessed capacity |
| HttpBuildRepository lists from its local index, reads the remote current head as a single revision, and removes only the local record | New server list/history/delete must preserve the local/remote distinction. Existing remove cannot be relabelled confirmed server deletion |
| Build writes send quoted If-Match and create a new idempotency key for each save call | Existing409 recognition is useful. Stable logical retries, lost-ack reconciliation and receipt retention still need reviewed adapter/server work; current key generation is not proof of those semantics |
| The client maps404 to `not_found`,409 to `conflict`, other failures to generic `http`; provider construction supplies no bearer issuer/credential lifecycle | New authority/version/error behavior requires explicit affected-client work. Do not claim current transport is already qualified for globally authenticated v1 |
| Responses pass through `response.json()` before object validation; provider checks vary (evaluation checks build ID/revision, cost explicitly checks revision) | Preserve exact numeric and full tuple requirements instead of assuming object-schema success supplies them. The separately reported [Android #49 numeric receipt](https://github.com/Pick-Your-AI/pick-your-ai-android/issues/49#issuecomment-6254033978) retains its actual executed scope; it is not rerun or cleared here |
| Compatible-options checks selected build/revision and the basic option members, with no paging negotiation | A successor must preserve current meaning or reject the old client explicitly. Android's stricter proposed request-generation/kind/tuple association is not automatically existing web behavior |

The explicit migration request is: retain frozen `/v1` representations for
supported old consumers; publish a separately reviewed capability/version for
new operations; have each consumer deliberately opt in after golden and failure
cases; otherwise return the **adopted** typed versioned rejection. Unknown new
fields are not stripped into old shapes, local histories are not silently
migrated, and native CD experiment limits are not imposed on frozen schemas.
The exact negotiation mechanism and retention period remain canonical decisions.

## Executable reference packet

[`tests/fixtures/android-api-consumer-intake-v1.json`](../tests/fixtures/android-api-consumer-intake-v1.json)
contains **58 individually named synthetic vectors**: 23 schema-valid and 35
schema-invalid expectations across five referenced artifact schemas and six
inline OpenAPI/header schemas. Original example bytes are read unchanged; each
case applies explicit mutations to a private copy. No credential, real user,
source publication or vendor evidence is included.

| Cases | Bounded observation |
| --- | --- |
| B01–B11 | Complete versus incomplete Build shape; versions/kinds/pins; Boolean/fractional revisions; decimal text and wide integer preservation; owner text is not authority |
| C01–C04 | Complete nonempty CatalogSnapshot; version rejection; no silently added paging cursor |
| R01–R08 | Resource shape, numeric text/metric/date/revision constraints, wrong-build schema admission and absent top-level catalog pin |
| F01–F04, K01–K05 | Report/cost shapes; distinct outcome/severity; wrong-revision admission; partial coverage and exact money representation |
| E01–E06, P01–P04 | Existing evaluation/cost request selectors, required fields and positive integer/Boolean distinctions |
| O01–O06, H01–H04 | Option verdicts/references, cursor rejection, weak inline kind/string constraints and existing header shapes |
| X01–X06 | Stale-write, missing-authority and unsupported-version **body shapes**; missing code and extra retry/private fields reject |

X01–X03 use clearly synthetic free-form codes. `Error` permits their strings,
but does not adopt these codes, unauthenticated HTTP responses or version
negotiation. R02/F02/B09 are deliberate examples of schema admission without
tuple/authority acceptance. E06/O06/H04 similarly expose inline-schema limits.
F04/K02 establish shape only, not recomputed aggregates or complete cost coverage.
B11 uses a Python integer; it proves no JavaScript/raw-number or native round-trip.

The [checker](../tests/test_api_consumer_intake.py) first invokes the existing
43-file/two-manifest integrity gate, uses the repository's pinned Python packages
and resolves external schema references from a local registry only. Every vector
gets an individual unittest name. Four additional boundary methods check the
complete operation/error inventory, inherited bearer/required write headers,
fixture omission/duplication/type/path failures, and exact wide values. They
do not instantiate a transport or invoke the Android application.

```sh
.venv-contracts/bin/python -m unittest discover -s tests -p 'test_api_consumer_intake.py' -v
.venv-contracts/bin/python -O -m unittest discover -s tests -p 'test_api_consumer_intake.py' -v
```

Use the Python3.12/pinned environment from [contract adoption](CONTRACT_ADOPTION.md).
The existing CI command `python -m unittest discover -s tests -v` automatically
includes these cases; no workflow or frozen import changes are needed. Retain
actual output and input hashes before reporting execution. A deliberately changed
expected result or source byte must fail; reference success never substitutes
for native, service or affected-owner qualification.

## Literal criterion and receiving disposition

| Exact Android #36 criterion | This packet supplies | Remaining gate |
| --- | --- | --- |
| Review discovery, pagination, listing/history/fork/share/delete, evidence and resource reads through Pick-Your-AI/pick-your-ai-research#40 with affected owners and backward compatibility. | Complete baseline inventory, A1–A8 requests, failure examples, actual existing-web source walkthrough and linked central intake | Actual central/affected-owner disposition and approved interface consequences; pending |
| Each route documents authority, relevant size/paging limits, versions and typed failures. | Every frozen route's actual authority/request/response scope; explicit absent limit/error/route decisions and requested bindings | Adopted route definitions, concrete agreed transport/paging limits and typed error/version mappings; pending |
| Existing clients have an explicit compatibility path or versioned rejection; no frozen field meaning changes in place. | Exact old-client observations and a proposed preserve/opt-in/reject migration path; unchanged frozen bytes and negative shape cases | Accepted negotiation/rejection mechanism and affected-client conformance/walkthrough receiving acceptance; pending |

The central writer can accept, narrow or reject each A-row against the unchanged
contract. Android, Apple, website and shared-service receivers must record their
actual exact-source decisions; linked role names and peer source review alone
do not supply those decisions. Publish the reviewed packet and its actual
reference receipt through the existing #40 handoff, then bind adopted outputs to
the designated #36 record. This leaves #36, #21, #40 and #42 completion, live
transport, installed devices, source rights and O1–O6 operational evidence separate.
