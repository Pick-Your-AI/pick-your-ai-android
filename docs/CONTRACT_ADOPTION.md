# Frozen v1 consumer baseline

This Android repository imports the complete 43-file `ai-builder-contract-v1`
package, version `1.0.0`, from research commit
`9769fcd9ea380b1211669b2753181c7782444178`, accepted through
[research PR #44](https://github.com/Pick-Your-AI/pick-your-ai-research/pull/44)
(merged 2026-10-16). Files were extracted directly from that Git revision and
verified against every original SHA-256 entry before copying.

[IMPORT_MANIFEST.json](../contracts/v1/IMPORT_MANIFEST.json) and
[UPSTREAM_SHA256SUMS](../contracts/v1/UPSTREAM_SHA256SUMS) retain their exact
research bytes. The import preserves schemas, synthetic examples, reference
Python code, generators, API/storage references, documentation and tests as one
reproducible package. The imported implementation prompts are archived context,
not instructions or production authorization. Their original names were already
relocated upstream: `AGENTS.md` to `docs/IMPLEMENTATION_AGENTS.md`, and
`HANDOFF_PROMPT.md` to `docs/IMPLEMENTATION_HANDOFF.md`. Original README links to
those old names use this mapping. Research-specific `ADOPTION.md` is not copied;
this document records the consumer's own adoption and limitations.

## Reproduce checks

Use Python 3.12 and an isolated environment:

```sh
python3.12 -m venv .venv-contracts
.venv-contracts/bin/python -m pip install -r requirements-contracts.txt
.venv-contracts/bin/python scripts/check_contracts.py
```

[The runner](../scripts/check_contracts.py) first checks pinned manifest hashes,
all 43 content hashes, and the original-to-relocated checksum mapping. Any missing
or modified file fails before the imported test suite runs. The validation
six upstream validation requirements are copied from that same commit;
`typing-extensions==4.16.0` additionally pins the Python 3.12 transitive dependency. The suite resolves schema identifiers locally, not via
`schemas.ai-builder.example`. It exercises 21 reference cases: schema/examples,
canonical kinds and references, decimal quote reconciliation, unknown capacity,
stale costs, optional branches, per-device memory and sensitive-data rejection.

Do not regenerate this frozen directory in place. Any successor requires an
explicit version, upstream acceptance, migration decisions and independent tests.

## Acceptance coverage and limits

This is partial delivery for
[Android #21](https://github.com/Pick-Your-AI/pick-your-ai-android/issues/21) and
[Android #49](https://github.com/Pick-Your-AI/pick-your-ai-android/issues/49).

| Criterion | Delivered evidence | Remaining gap |
| --- | --- | --- |
| #21 exact upstream revision/hash and reproducible integrity | Pinned merge revision, unchanged manifests and fail-closed hash runner | Receiving review |
| #21 preserve frozen kinds/enums/decimal strings | Complete original schemas and reference cases retained unchanged | Actual Android API/storage adapters, malformed-version rejection and money round-trip tests |
| #49 versioned language-neutral fixtures and Android-consumable bundle | Original JSON fixtures, schemas, Python evaluator and expectations are locally available | TypeScript reference comparison, Android harness/runtime parity |
| #49 historical meaning and explicit incompatible migrations | Baseline is immutable; no automatic upgrade is implemented | Accepted successor/migration semantics and rejection tests |

These are synthetic reference checks, not measured compatibility, real prices,
Android runtime validation, or production acceptance. Baseline rules and graph
are illustrative proposals; known limitations remain governed by research #40.
Passing tests does not authorize using missing list membership as a negative
vendor fact or substituting a newer artifact for a pinned one. The work packages
remain open until their full behavioral criteria and receiving reviews are met.

## Observed validation

On 2026-10-18, Python 3.12.14 in a fresh virtual environment installed the pinned
requirements and passed all 21 imported tests. Temporary-copy tampering of
`examples/build.json` and `IMPORT_MANIFEST.json` each failed integrity validation
with the expected explicit error. No imported file was changed by these checks.
