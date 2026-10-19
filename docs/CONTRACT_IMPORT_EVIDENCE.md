# Frozen import evidence for Android criterion 21.2

This technical receipt addresses the exact requirement in
[Android #21](https://github.com/Pick-Your-AI/pick-your-ai-android/issues/21):

> The consumer records an exact upstream revision/hash and reproduces import integrity checks.

It records completed import behavior and inspected original execution at the
identities below. It does not relocate the canonical acceptance record:
`docs/delivery/android/work-packages/03.01.md` in `Pick-Your-AI/pickyour.ai` retains
that authority. Criterion-specific receiving acceptance, any strict-ledger update
and whole-issue completion are separate actions. The remaining #21 criteria and
the #32/#49 prerequisites are unchanged.

## Exact source and content identity

| Object | Recorded identity and meaning |
| --- | --- |
| Accepted upstream revision | Research [#44](https://github.com/Pick-Your-AI/pick-your-ai-research/pull/44), valid-signed merge commit `9769fcd9ea380b1211669b2753181c7782444178` |
| Upstream repository tree | `6320941c0437b6f069272955afafe96b198817f3` at that commit |
| Frozen package subtree | `b1c4eaa4037f2a1e02103849064a947e758a2b40`, the `contracts/v1` Git tree; this is not a commit |
| Package and wire version | `ai-builder-contract-v1`, `1.0.0` |
| Executed Android main | `40bd3d99dd7286f4b2fba0d6b17eb4e89b869657`, tree `11432a4017e41f1c7bdfe97482ee7a78561a5b8b` |
| Later source-only comparison | `e8241ef4ea75071339e6a945e48fc6d433819ffa`, tree `65632754b334745765bb2d6bc2fbac86420f7769`; all import and integrity-program bytes remain exact |

The complete untruncated upstream repository/subtree inventories and Android
inventory were compared. Every imported file has the same Git mode, blob identity
and independently recomputed SHA-256 as the accepted upstream bytes. The
[machine-readable receipt](evidence/frozen-import-21.2-20261018.json) records each
path, original path, byte count, mode, Git blob and SHA-256: **43 original package
files plus two pinned metadata files**.

The metadata files are themselves pinned before their entries are trusted:

| Metadata | SHA-256 |
| --- | --- |
| `IMPORT_MANIFEST.json` | `ef6d5801eec10f3169e2bf84653e7026b33088e305a987991ec52faadcd7f554` |
| `UPSTREAM_SHA256SUMS` | `0587a133436268bc03684ad61ff29f3bd1a12eb83db8a497eabdbb8fd8803e15` |

The two original prompt names map to `docs/IMPLEMENTATION_AGENTS.md` and
`docs/IMPLEMENTATION_HANDOFF.md`; content is unchanged. Research's additional
`ADOPTION.md` is deliberately excluded from the Android import, which documents
its own consumer boundary. Its omission is not a missing manifest entry: the
original 43-file manifest does not list it. These archived prompts remain
reference data under the current contributor instructions.

## Reproduced positive and negative behavior

The [actual default-main Core job](https://github.com/Pick-Your-AI/pick-your-ai-android/actions/runs/39166674498/job/117790951872)
is run `39166674498`, push event, attempt 1, checkout `40bd3d99`. It succeeded.
The original decoded log is 39,592 bytes, SHA-256
`82662a001afb0bbfc335ccbeb408fd26547290e654974fc523df5689f633e113`.
The original log, exact executed program and all four named test bodies were
inspected; no new project test run was performed to write this receipt.

`python scripts/check_contracts.py` verified all 43 original content hashes and
both metadata hashes before running the unmodified upstream tests. All **21
reference cases passed in 0.672 seconds**. Later shared panels retain their own
structural scope; they are not needed to substitute for this direct import proof.

The unchanged [four isolated tamper tests](../tests/test_contract_integrity.py)
then all passed in **0.182 seconds**. Each invokes the real command against a
temporary copy, requires exit 1 and `Contract integrity failure:`, and requires
that the original 21-case suite did not execute:

| Actual named case | Mutation rejected before reference execution |
| --- | --- |
| `test_modified_schema_fails_before_reference_execution` | Replace `schemas/common.schema.json` with an empty object |
| `test_modified_manifest_cannot_redefine_accepted_hashes` | Replace `IMPORT_MANIFEST.json` with an empty object |
| `test_missing_artifact_fails` | Remove `examples/build.json` |
| `test_extra_schema_is_not_silently_bundled` | Add an unreviewed schema file |

The runner also compares the complete regular-file inventory, preserves the
original-to-relocated checksum mapping and propagates a failed reference or
shared-panel process. Its bytecode-cache exclusion is explicit and narrow;
generated schemas or other extra artifacts are not accepted by that exclusion.
No tracked frozen file was modified by the isolated tests.

For a new checkout, use the Python 3.12/pinned-dependency commands in
[CONTRACT_ADOPTION.md](CONTRACT_ADOPTION.md), then run
`python -m unittest discover -s tests -v` with that same environment. Retain the
actual checkout, raw logs and outcomes of that new execution rather than assigning
the historical timing or result above to it.

## Earlier review and receiving scope

[Initial core review 5560798942](https://github.com/Pick-Your-AI/pick-your-ai-android/pull/133#pullrequestreview-5560798942)
was anchored to `8b4197a3` and reviewed import provenance alongside the initial
core. [Tamper-gate review 5560808403](https://github.com/Pick-Your-AI/pick-your-ai-android/pull/134#pullrequestreview-5560808403)
was anchored to `417e22ab` and reviewed all four subprocess checks. Their reported
executions retain those source identities; this receipt does not convert either
review into whole-issue or affected-consumer acceptance.

The [later actual default delivery receipt](https://github.com/Pick-Your-AI/pick-your-ai-android/pull/406#issuecomment-6255317950)
records `40bd3d99` and its complete original artifacts. The 43 original files, two
manifests and four tamper-test bodies remain exact at that main. The new source
comparison at `e8241ef4` adds no runtime evidence and changes no import bytes.

Receiving review of this supplement must assess **21.2 itself**, the exact
upstream pin and reproduced import integrity. It does not accept actual API,
persistence or user-import coverage under 21.1/21.3; compatible vendor facts,
prices or evidence; any released successor; cross-client numeric transport or
migration agreement; or installed-device, service or release behavior. The
[future revision handoff](CONTRACT_REVISION_HANDOFF.md) documents the remaining
coordinated procedure without declaring those future outcomes complete.
