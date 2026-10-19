# Android source-use register and offline promotion check

The [versioned register](source-use/android-source-use-v1.json) records **ten
specific materials and sixty separate Android use dispositions**. It consumes
the accepted Research source-use decisions for the selected guest-local planning
slice. Every source retains **Not approved for integration**. The included
[offline command](../scripts/check_android_source_use.py) rejects every current
promotion proposal and explains its applicable exclusions or missing conditions.
Checking the register itself never approves a source.

This is the supporting application use/ingestion matrix required by
[Android #22](https://github.com/Pick-Your-AI/pick-your-ai-android/issues/22).
The issue's intended acceptance record remains
`Pick-Your-AI/pickyour.ai/docs/delivery/android/work-packages/04.01.md`.
This document does not relocate that record, assign a catalog or Research owner,
or establish full issue acceptance. The [repository scope](REPOSITORY_SCOPE.md),
[accepted product decision](delivery/android/work-packages/01.02.md) and
[frozen import](CONTRACT_ADOPTION.md) retain their existing authorities.

## Exact accepted inputs

Research revision **`2dd26314de99f8e4c7f12d63215f95f1c10d1646`** supplies the
following complete pinned documents. The register records each path, Git blob,
UTF-8 byte length and SHA-256. These are identities of reviewed source documents,
not identities of an ingested catalog, dataset or model binary.

| Input | Immutable source | Git blob | Bytes | SHA-256 |
| --- | --- | --- | ---: | --- |
| Rights decisions | [rights-validation.md](https://github.com/Pick-Your-AI/pick-your-ai-research/blob/2dd26314de99f8e4c7f12d63215f95f1c10d1646/research/market/rights-validation.md) | `2fb0aa8d4c51448f111dbc82a8123206646f61c6` | 24,965 | `83b031b721205d6f88d4d27b5bbc003045c524372aea7127b2e9627fae556873` |
| Source policy | [catalog-data-sources.md](https://github.com/Pick-Your-AI/pick-your-ai-research/blob/2dd26314de99f8e4c7f12d63215f95f1c10d1646/research/T0-configuration/catalog-data-sources.md) | `c862112312d45bbe13b9e9261b8f83ee581cafa6` | 76,248 | `42dc339caf0eb4c77b480e1e1be3bf84bea9bf983b585878623d5d23cc311bb7` |
| Ten-source inventory | [integrations.json](https://github.com/Pick-Your-AI/pick-your-ai-research/blob/2dd26314de99f8e4c7f12d63215f95f1c10d1646/research/market/integrations.json) | `159766a48d7ec4460cbdc327dd5033d9f1b137a7` | 36,320 | `2fd9692555a41518fc372a541923a0285766b74709a5e5d8f657d862ebd8b5ad` |

The actual [Research #60 to #41 receiving receipt
6248616771](https://github.com/Pick-Your-AI/pick-your-ai-research/issues/60#issuecomment-6248616771)
is a scoped technical receiving decision. It supplies neither publisher
permission beyond the recorded material/use basis nor a production integration
approval. No statement in this register is attributed to an absent assignee.
Changed source-use questions return to those existing Research authorities.

Each row preserves its original inventory observation date separately from the
2026-10-18 material/terms review. `research_record_sha256` identifies that row's
complete original inventory object encoded as UTF-8 JSON with sorted keys,
no insignificant spaces and `ensure_ascii=False`. The full inventory file hash
above is the byte-level source authority. The row hash does not replace it.
No source payload or current terms were fetched by the offline check.

The Android scope pin is accepted product decision
`93bebf787a091fe4858c1c70fd07cabefe17170f`, received in the working source at
`554e1984e8923c5f098887a01f64be9e87ce3961`. This dated scope is guest-local
text planning. Conditional later export, remote services, provider execution,
device qualification and release retain their own acceptance requirements.

## Material and operation matrix

The register's canonical source identifiers are the existing `src-*` inventory
identities. A product name or label is not an alias for one of those identifiers.
Each proposal must name exactly one registered source and its exact `material_id`.
The paper, repository data, service outputs and model files are distinct materials.

- **C — conditional basis:** the accepted Research input describes an applicable
  source-level basis with conditions. Its notices, exact material applicability
  and receiving conditions have not been fulfilled for an Android promotion.
- **X — excluded:** the proposed Android product use is excluded by the accepted
  material/use decision. A historical conditional access cell does not override
  the later product-specific exclusion.
- **U — unreviewed Android use:** this finite input supplies no accepted
  Android-specific use and conditions. This records the absence of an accepted
  decision; it is not a new legal prohibition or permission.

**C is never an approval result.** All rows also fail the separate integration
approval gate. The executable register retains the exact input/section/record/use
basis, original Research value, conditions and exclusions for every matrix cell.

| Source | Exact material ID | Automated retrieval | Commercial display | Offline cache | Retention | Redistribution | Export | Intake path |
| --- | --- | :---: | :---: | :---: | :---: | :---: | :---: | --- |
| `src-models-dev` | `repository-compilation` | C | C | U | U | C | C | Conditional source basis |
| `src-modelfit` | `licensed-dataset` | C | C | U | U | C | C | Conditional source basis |
| `src-llmfit` | `repository-data` | C | C | U | U | C | C | Conditional source basis |
| `src-openrouter` | `service-information` | X | X | U | U | X | X | Manual research only |
| `src-mcp-registry` | `registry-metadata` | C | C | U | U | C | C | Conditional source basis |
| `src-hugging-face` | `public-factual-identifiers` | C | C | U | U | X | X | Conditional source basis for identifiers |
| `src-artificial-analysis` | `site-and-platform-data` | X | X | U | U | X | X | Manual research only |
| `src-benchlm` | `owned-service-outputs` | X | X | U | U | X | X | Separate applicable agreement required |
| `src-harness-bench` | `paper-v2` | X | C | U | U | C | C | Manual research; paper-only basis |
| `src-gpus-io` | `service-data` | X | X | U | U | X | X | Excluded for this product |

Retrieval means collection for the proposed Android product feed. It does not
include a researcher's separately permitted internal query. Commercial display
means product display of the described material; a manual research link does not
qualify the product data. Redistribution and export have separate cells. Their
source basis is shared only where the accepted rights definition expressly
includes bulk export within redistribution of that same material.

Offline caching and retention remain separate even where a source describes
downstream caching in general. No Android cache profile, retained material,
duration, notice implementation or withdrawal/deletion policy has been accepted
for these candidates. An availability target or rate limit is not a retention
period. User-authored guest drafts and bundled synthetic fixtures are not these
third-party production sources; this register does not change their storage.

### Conditions and material boundaries

| Material | Accepted source basis and required boundary |
| --- | --- |
| models.dev compilation | Preserve the MIT copyright and permission notice for the reviewed repository data. The [pinned licence](https://github.com/anomalyco/models.dev/blob/84a113860a478edfbbf42c8cce4d7ad76fa48d9d/LICENSE) does not license third-party model binaries, provider branding or unrelated measurements. |
| ModelFit dataset | The accepted dataset basis is CC BY 4.0. Credit ModelFit, link the licence, preserve supplied notices and indicate changes. Keep values labelled as estimates. The publisher and [mirror licence](https://github.com/Wecko-ai/modelfit-hardware-dataset/blob/6fa130db0efa61a5b7ac242fb400c632ab730c52/LICENSE) checks did not verify mirror payload equivalence, fit accuracy or upstream measurements. Generator/CLI code is separate. |
| llmfit repository data | Preserve its [MIT notice](https://github.com/AlexsJones/llmfit/blob/e915091dd58f7fefaa9814d2d34ee61bd754fdc0/LICENSE). The llmfit.org website, model binaries and independent fit measurements are separate material. |
| MCP registry metadata | The accepted [downstream metadata design](https://github.com/modelcontextprotocol/registry/blob/11b7b0e274d518b6f449c5ee3518a6151d9848b9/docs/design/ecosystem-vision.md) describes ETL/cache/serve use. Keep official provenance, credit and project annotations separate. Repository code/documentation licences and individual server packages remain separate; listing proves no trust, security, compatibility or execution result. Android-specific cache/retention receipt is absent. |
| Hugging Face identifiers | Only public factual repository identifiers, revisions, declared licence and size with a link have the recorded display basis within the Services. [Service terms](https://huggingface.co/terms-of-service) are not a blanket grant for model cards, weights, datasets, private/gated content or branding. A separately licensed repository export needs its own exact material/use record. |
| Harness-Bench paper | Preserve authors/title, [arXiv v2 identity](https://arxiv.org/abs/2605.27922v2), source/licence links, supplied notices and change indications for the licensed paper only. Its CC BY 4.0 basis does not apply to the separately unlicensed repository tasks, code, trajectories or catalog files. Paper rights establish no benchmark validity or comparability. |
| BenchLM outputs | Recorded API/MCP access is limited to internal queries within its account/plan conditions. [Product display/feed terms](https://benchlm.ai/terms) need a separate applicable agreement; none was acquired. Individual quotations, attributed embeds and original publishers retain their own scopes. |
| GPUs.io data | The accepted current-product decision excludes automated retrieval, product display and redistribution/export despite the historical conditional paid-API/display cells. A build estimate does not create an exemption from the [competing-product restriction](https://gpus.io/en/terms). Reconsideration requires a specific permitted-use basis and new Research #39/#41/#60 review. |
| OpenRouter information | The accepted [terms review](https://openrouter.ai/terms) excludes automated collection and commercial republication for this intake. Public API visibility is not a contrary grant. Manual research links remain separate. |
| Artificial Analysis data | The accepted [site](https://artificialanalysis.ai/docs/legal/Terms-of-Use.pdf) and [Data Platform](https://artificialanalysiscdn.com/legal/ProDataPlatformTerms.pdf) review retains the product-category exclusion. Manual market-research citation does not supply a model/provider-selection product feed. No written consent was acquired. |

The complete primary references and dated conditions are in each machine-readable
row. These paragraphs consume the pinned Research decisions; they do not extend a
licence, authorize outreach or establish a new source-rights finding.

## Run the offline check

Use Python 3.12 on Linux, matching the existing required host-test job. The command
uses only the standard library and makes no network request. It reads regular
local JSON files through a bounded, nonblocking descriptor, rejects a symlink in
the final path component, and creates or updates no files.

```sh
python3 scripts/check_android_source_use.py --check-register
python3 scripts/check_android_source_use.py --proposal docs/source-use/requests/models-dev-offline.json
python3 -m unittest discover -s tests -p test_android_source_use.py -v
```

The first command exits **0** with `register_consistent`, `promoted:false`, ten
sources and sixty use decisions. It checks the closed v1 profile, material/use
inventory, declared source pins and dispositions. It does not independently
re-read the upstream files, verify a licence or grant integration permission.

The second command deliberately exits **1** with `blocked` and `promoted:false`.
The [committed proposal](source-use/requests/models-dev-offline.json) binds the
register's exact bytes by SHA-256, selects `src-models-dev` and
`repository-compilation`, and requests retrieval, display and offline caching.
The output records `integration_not_approved`, two conditional-use receipts not
received and the unreviewed Android offline-cache use. The negative exit is the
expected result of a real promotion attempt at this offline planning boundary.

| Exit | Result | Meaning |
| ---: | --- | --- |
| 0 | `register_consistent` | Register validation only; no source promoted. Available only through `--check-register`. |
| 1 | `blocked` | A well-formed proposal cannot be promoted. Inspect fixed reason codes and per-use dispositions. |
| 2 | `invalid_input` | Malformed, ambiguous, unsupported, unreadable or oversized input; no decision or promotion. |

Proposals contain exactly `profile`, `register_sha256`, `source_id`, `material_id`
and `uses`. They cannot supply approval, reviewer, receipt or policy overrides.
One to six distinct uses are accepted as input; an unknown use is invalid. A
valid but unknown source or different material returns a blocking reason. The
check never substitutes a paper licence for repository files, a product label
for the canonical `src-*` identifier, or a current source for a missing version.

`--register PATH` supports a separately reviewed local copy or isolated rejection
test. Reformatting or changing its bytes changes its digest and makes old
proposals stale. Supplying the new digest proves only that the proposal selected
those bytes; it still cannot satisfy missing rights/notice/integration acceptance.
The current closed profile rejects approved statuses and reclassified exclusions.
A new permissive profile would need an independently accepted source/consumer
implementation and receiving evidence; no positive route is hidden in v1.

Inputs are bounded at 256 KiB for the register and 8 KiB for proposals. Empty
files, directories, FIFOs, final-component symlinks, invalid UTF-8, duplicate
JSON keys, nonfinite JSON constants and unsupported fields fail before promotion.
Errors contain fixed codes, not input values, filenames or exception details.

## Ingestion, changes and receiving handoff

The usable research subset is a set of **conditional source bases**, currently
with **zero approved Android production integrations**. It can guide a concrete
future intake proposal. It cannot enter a production catalog merely because the
register is structurally valid. The command is a runnable offline promotion
preflight; no existing catalog reader, saved-build importer, UI or service is
rewired to it by this artifact.

Before any future promotion, the canonical publication owner must receive the
exact source material/artifact and version, every intended operation's accepted
terms and conditions, implemented notices/attribution, evidence and freshness
validation, applicable Android display/cache/export behavior and independent
publication/consumer acceptance. Those are unfilled gates here, not values a
proposal may assert. Frozen CatalogItem/CatalogSnapshot/Pricebook/Ruleset and
provenance contracts keep their existing versioning authority; this register is
not a new field or entitlement embedded in one of those contracts.

Changed material, governing terms or intended use returns to Research #60/#41;
price-source exceptions also retain #39. A reviewed register revision changes
its identity so an old proposal cannot silently use a newer decision. Preserve
old observed dates and exact source receipts instead of rewriting history as a
new source fetch. The proposed typed source-policy inventory in the Research
document remains a proposal; this implementation does not activate that proposal
as an authorization system.

A withdrawal or revoked right blocks new promotion. A real consuming system
must also evaluate affected current values, cached material, retained snapshots,
exports and public disclosures, hiding or removing them as the applicable
decision requires. Permitted audit history is separate from continued public
distribution. This offline artifact has no live source payloads to delete and
establishes no running withdrawal, retention timer or synchronization service.

## Validation and acceptance boundaries

The focused test file runs the actual CLI in isolated temporary directories. It
attempts all sixty registered material/use combinations; exercises the concrete
models.dev, MCP, GPUs.io, BenchLM, Hugging Face and paper/repository distinctions;
and rejects stale digests, caller-supplied approvals, changed source pins,
duplicate/missing rows, false version types and unsafe file/JSON inputs. It also
checks that the command does not change source/proposal files or create output.
The existing `python -m unittest discover -s tests -v` workflow discovers these
tests without modifying a native job or adding an external dependency.

| Android #22 requirement | Evidence this artifact provides | Remaining acceptance |
| --- | --- | --- |
| Separate exact retrieval/display/cache/retention/redistribution/export paths | Ten material-bound rows, sixty versioned dispositions, per-use Research basis and a runnable rejecting proposal | Production material/versions, fulfilled conditions and actual publication/consumer acceptance remain absent. |
| Dated primary terms reference and disposition for every production-bound source | Exact Research input identities, original and subsequent review dates, primary references and material-specific decisions | No source is promoted. A future production-bound artifact needs its own identity and applicable receiving evidence. |
| Public links do not authorize scraping/reuse; unresolved or revoked rights block promotion | Current unapproved profile rejects every actual proposal, including historical-yes product exclusions and unrepresented Android uses | No live ingestion, retrospective withdrawal or cache/export service is implemented or validated here. |
| Review documentation, price and dataset uses; attempt unresolved promotion | Pinned Research material review is consumed; CLI regression results and exact source identity accompany the PR | Required catalog-publication, Research evidence/rights and Android receiving roles must review the actual subset and limitations. |

Keep partial references to #22 until its canonical acceptance record, exact-version
review, tests and every literal criterion have been reconciled. Python/offline
results establish no installed Android, device, legal clearance, service,
publisher, store or production result. No assignee, review role or commitment is
invented by this supporting register.
