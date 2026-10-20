"""Reproduce native normalized CD-3 cases from exact, offline Research Git objects.

By default compare the complete checked-in fixture. Only --write regenerates it.
This is a research-prototype parity experiment, not source/contract adoption.
"""
import argparse
from dataclasses import asdict
import gzip
import hashlib
import io
from itertools import product
import json
from pathlib import Path
import subprocess
import sys
import types

ROOT = Path(__file__).resolve().parents[1]
COMMIT = "ec29b802ff1a3c8a2f1e89384a0c4dc9b89c8a60"
PROFILE = "android-normalized-support-membership/v1"
DESTINATION = ROOT / "core/src/test/resources/evidence/support-membership-cases.json.gz"
PINS = {
    "tests/support_membership_prototype.py": (
        "4406ccc9de150aa2955abce6173025c350e17da2",
        "a2120d738889253b5bf558827abc22c4de0be99dc6caa3ebee89551e4817a185",
    ),
    "tests/test_support_membership.py": (
        "c83741a4ad579d30e6c26441ff35b8e71053fb82",
        "958f9ab9fd3b480630c82de2ab05fbd2744d1679788de06e8f6c106f526751e0",
    ),
    "tests/test_support_evidence_labels.py": (
        "3612509c29d099a952d1a53f0ea062449ca148bf",
        "cec88444aa6a88a84b8b43af49922f5467447207b3d9c8db0e4590b14322603f",
    ),
    "research/T0-configuration/component-schema-compatibility.md": (
        "8ee5a7b7a960ae63c073c996cfdc73b80d939e7c",
        "e000ea7c05fb53b061ce7af9060f072eae89544079a62bd3626928e222a9a640",
    ),
    "research/T0-configuration/catalog-data-sources.md": (
        "c862112312d45bbe13b9e9261b8f83ee581cafa6",
        "42dc339caf0eb4c77b480e1e1be3bf84bea9bf983b585878623d5d23cc311bb7",
    ),
}


def digest(raw):
    return hashlib.sha256(raw).hexdigest()


def canonical(value):
    return json.dumps(value, ensure_ascii=True, sort_keys=True, separators=(",", ":"))


def load_reference(repository):
    if sys.version_info[:2] != (3, 12):
        raise ValueError("This experiment requires the pinned Python 3.12 semantics")
    sources = {}
    module_bytes = None
    for name, (blob, sha256) in PINS.items():
        raw = subprocess.check_output(["git", "-C", str(repository), "show", f"{COMMIT}:{name}"])
        git_blob = hashlib.sha1(f"blob {len(raw)}\0".encode() + raw).hexdigest()
        if git_blob != blob or digest(raw) != sha256:
            raise ValueError(f"Research source identity mismatch: {name}")
        sources[name] = {"blob": blob, "bytes": len(raw), "sha256": sha256}
        if name == "tests/support_membership_prototype.py":
            module_bytes = raw
    # Execute only the exact reviewed, pure standard-library reducer above.
    module = types.ModuleType("_pinned_android_support_membership")
    sys.modules[module.__name__] = module
    exec(compile(module_bytes, f"{COMMIT}:tests/support_membership_prototype.py", "exec"), module.__dict__)
    return module, sources


def inputs(requested=("a", "b"), *, supported=("a",), unsupported=(), exhaustive=None,
           requirement="any", requested_complete=None, eligible=True, applicable=True,
           checked_only=None, source_id="synthetic-scoped-source"):
    return {
        "requested": None if requested is None else list(requested),
        "requirement": requirement,
        "requested_complete": requested_complete,
        "applicable": applicable,
        "evidence": {
            "source_id": source_id, "eligible": eligible,
            "supported": None if supported is None else list(supported),
            "unsupported": list(unsupported), "exhaustive": exhaustive,
            "checked_only": checked_only,
        },
    }


def generated_cases(reference):
    cases = []

    def add(name, value, invalid_requested=False):
        raw = canonical(value)
        evidence = value["evidence"]
        try:
            result = reference.evaluate(
                None if value["requested"] is None else frozenset(value["requested"]),
                reference.Evidence(
                    evidence["source_id"], evidence["eligible"],
                    None if evidence["supported"] is None else frozenset(evidence["supported"]),
                    evidence["exhaustive"], frozenset(evidence["unsupported"]), evidence["checked_only"],
                ),
                reference.Requirement(value["requirement"]),
                applicable=value["applicable"], requested_complete=value["requested_complete"],
            )
        except ValueError:
            if not invalid_requested:
                raise
            expected = {"error": "invalid_requested_values"}
        else:
            if invalid_requested:
                raise ValueError("Pinned reducer unexpectedly admitted a blank request")
            expected = asdict(result)
        cases.append({"id": name, "input_json": raw, "input_sha256": digest(raw.encode("ascii")),
                      "expected": expected})

    facts = (None, False, True)
    for index, (supported, unsupported, exhaustive, requirement, complete, checked) in enumerate(product(
        (None, (), ("a",), ("b",), ("a", "b")),
        ((), ("a",), ("b",), ("a", "b")),
        facts, ("any", "all"), facts, facts,
    )):
        add(f"cohort-{index:04d}", inputs(
            supported=supported, unsupported=unsupported, exhaustive=exhaustive,
            requirement=requirement, requested_complete=complete, checked_only=checked,
        ))
    for index, (applicable, eligible, requested, requirement, complete) in enumerate(product(
        facts, facts, (None, (), ("a",), ("b",), ("a", "b"), ("a", "b", "c")),
        ("any", "all"), facts,
    )):
        add(f"shortcut-{index:04d}", inputs(
            requested, supported=("a",), unsupported=("a", "b"), exhaustive=True,
            requirement=requirement, requested_complete=complete, checked_only=True,
            eligible=eligible, applicable=applicable,
        ))
    whitespace = [code for code in range(0x3100) if chr(code).isspace()]
    if len(whitespace) != 29:
        raise ValueError("Pinned Python whitespace inventory changed")
    for code, applicable in product(whitespace, facts):
        add(f"blank-U{code:04X}-{applicable}", inputs(
            (chr(code),), applicable=applicable, eligible=False, checked_only=True,
        ), invalid_requested=True)
    for applicable in facts:
        add(f"blank-empty-{applicable}", inputs(("",), applicable=applicable), invalid_requested=True)
    literals = (" A ", "A", "a", "caf\u00e9", "cafe\u0301", "\ue000", "\U00010000",
                "\U0001f600", "\u200b", "\ufeff", "unknown")
    for requirement, complete, checked in product(("any", "all"), facts, facts):
        add(f"literal-order-{requirement}-{complete}-{checked}", inputs(
            tuple(reversed(literals)), supported=literals, requirement=requirement,
            requested_complete=complete, checked_only=checked,
        ))
    for source in ("verified", "unknown", "", " synthetic-private-source ", "source.caf\u00e9", "source.cafe\u0301"):
        for checked in facts:
            add(f"source-{len(cases):04d}", inputs(
                ("a",), supported=("a",), source_id=source, checked_only=checked,
            ))
    if len(cases) != 1530 or len({case["id"] for case in cases}) != len(cases):
        raise ValueError("Synthetic case inventory changed")
    return cases


def build(repository):
    reference, sources = load_reference(repository)
    return (canonical({
        "profile": PROFILE, "research_commit": COMMIT,
        "sources": sources, "cases": generated_cases(reference),
    }) + "\n").encode("ascii")


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--research-root", type=Path, required=True,
                        help="Existing read-only Research checkout containing the pinned Git objects")
    parser.add_argument("--write", action="store_true")
    options = parser.parse_args()
    result = build(options.research_root)
    if options.write:
        DESTINATION.parent.mkdir(parents=True, exist_ok=True)
        output = io.BytesIO()
        with gzip.GzipFile(filename="", mode="wb", fileobj=output, compresslevel=9, mtime=0) as compressed:
            compressed.write(result)
        DESTINATION.write_bytes(output.getvalue())
    elif gzip.decompress(DESTINATION.read_bytes()) != result:
        raise ValueError("Checked-in membership fixture differs from the exact pinned Python reducer")
    print(f"1530 normalized membership cases; {len(PINS)} exact Research inputs; "
          f"{len(result)} canonical fixture bytes; SHA-256 {digest(result)}; "
          f"{DESTINATION.stat().st_size} compressed bytes; SHA-256 {digest(DESTINATION.read_bytes())}")


if __name__ == "__main__":
    main()
