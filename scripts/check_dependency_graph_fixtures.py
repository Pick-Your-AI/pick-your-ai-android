"""Check synthetic graph mutations against the unchanged frozen reference schema."""
import argparse
from copy import deepcopy
import hashlib
import json
from pathlib import Path

from jsonschema import FormatChecker
from referencing import Registry, Resource

from frozen_fixture_numbers import FixtureValidator, exact_decimal, read_json, reject_non_json_number
from reference_fixture_output import output_path, write_output

ROOT = Path(__file__).resolve().parents[1]
BASE = ROOT / "contracts/v1"
OUTPUT = ROOT / "core/src/test/resources/contracts/dependency-graph-cases.json"
PINS = {
    "schemas/common.schema.json": "0342ce2f1114e7276fc7f6a08ff5b86e8727caadd83ae4f443de9a7e1431c43c",
    "schemas/dependency-graph.schema.json": "fb6a8bb42a9424d4ff2b520301f816c247789a3d70d1124afea1a8dc5ed5bd75",
    "examples/dependency-graph.json": "e9b3bca19a9cf8241fd74f6618824882a3413649822add88be1ac93cb3f1bacc",
}


def vectors(schema, original):
    edge_schema = schema["properties"]["edges"]["items"]
    first = original["edges"][0]
    cases = []

    def add(name, path=(), value=None, *, operation="set", expected="schema_mismatch", raw=None):
        case = {"name": name, "path": list(path), "operation": operation, "expected": expected}
        if operation == "set":
            case["json"] = raw if raw is not None else json.dumps(value, ensure_ascii=False, separators=(",", ":"))
        cases.append(case)

    add("canonical-fixture", operation="none", expected="decoded")
    for key in schema["required"]:
        expected = "unsupported_version" if key == "schema_version" else "schema_mismatch"
        add("missing-" + key, [key], operation="remove", expected=expected)
        add("null-" + key, [key], None, expected=expected)
        add("wrong-type-" + key, [key], True, expected=expected)
    for key in edge_schema["required"]:
        path = ["edges", "0", key]
        add("edge-missing-" + key, path, operation="remove")
        add("edge-null-" + key, path, None)
        add("edge-wrong-type-" + key, path, True)
    for key in ("dependent_kind", "depends_on_kind"):
        kinds = edge_schema["properties"][key]["enum"]
        if len(kinds) != 29:
            raise ValueError("Frozen endpoint kind inventory changed")
        for kind in kinds:
            add(key + "-" + kind, ["edges", "0", key], kind, expected="decoded")
        add("unknown-" + key, ["edges", "0", key], "future_unsupported")
    relationships = edge_schema["properties"]["relationship"]["enum"]
    activations = edge_schema["properties"]["activation"]["enum"]
    if len(relationships) != 4 or len(activations) != 8:
        raise ValueError("Frozen relationship or activation inventory changed")
    for relationship in relationships:
        for activation in activations:
            add(relationship + "-" + activation, ["edges", "0"],
                dict(first, relationship=relationship, activation=activation), expected="decoded")
    for key in ("relationship", "activation"):
        add("unknown-" + key, ["edges", "0", key], "future_unsupported")
    add("unsupported-version", ["schema_version"], "2.0.0", expected="unsupported_version")
    add("wrong-root", [], [], expected="invalid_input")
    add("invalid-id", ["id"], "INVALID ID")
    add("invalid-edge-id", ["edges", "0", "id"], "INVALID ID")
    for name, raw, expected in (
        ("zero-revision", "0", "schema_mismatch"),
        ("negative-revision", "-1", "schema_mismatch"),
        ("fractional-revision", "1.5", "schema_mismatch"),
        ("large-revision", "123456789012345678901234567890123456789", "decoded"),
        ("beyond-long-revision", "9223372036854775808", "decoded"),
        ("integral-decimal-revision", "1.00e2", "decoded"),
        ("large-integral-exponent-revision", "1e19", "decoded"),
        ("negative-integral-exponent-revision", "-1e19", "schema_mismatch"),
    ):
        add(name, ["revision"], raw=raw, expected=expected)
    add("empty-edges", ["edges"], [])
    add("nonobject-edge", ["edges", "0"], [])
    add("empty-rationale", ["edges", "0", "rationale"], "")
    add("unicode-rationale", ["edges", "0", "rationale"], "\U0001f642", expected="decoded")
    add("whitespace-rationale", ["edges", "0", "rationale"], " \n\t", expected="decoded")
    add("inert-rationale", ["edges", "0", "rationale"],
        "https://example.invalid/graph; recorded text is not executed", expected="decoded")
    add("unknown-root-field", ["future_field"], True)
    add("unknown-edge-field", ["edges", "0", "future_field"], True)
    for key in ("placement", "component_role", "memory_pool"):
        add("unsupported-" + key, ["edges", "0", key], "synthetic")
    add("unsupported-hybrid-activation", ["edges", "0", "activation"], "hybrid")
    add("unsupported-extensions", ["extensions"], {"x_fixture": True})
    add("repeated-edge-preserved", ["edges"], [first, original["edges"][1], first], expected="decoded")
    add("repeated-id-different-edge-preserved", ["edges"],
        [first, dict(first, depends_on_kind="accelerator", rationale="Different recorded edge")], expected="decoded")
    add("self-edge-preserved", ["edges", "0", "depends_on_kind"], first["dependent_kind"], expected="decoded")
    reverse = dict(first, id="dep.synthetic.reverse", dependent_kind=first["depends_on_kind"],
                   depends_on_kind=first["dependent_kind"])
    add("cycle-preserved", ["edges"], [first, reverse], expected="decoded")
    invalid = deepcopy(first)
    del invalid["rationale"]
    add("one-invalid-edge-rejects-whole-graph", ["edges"], [first, invalid])
    add("terminal-lf-id-preserved", ["id"], "graph.valid\n", expected="decoded")
    add("terminal-lf-edge-id-preserved", ["edges", "0", "id"], "edge.valid\n", expected="decoded")
    add("terminal-cr-id-rejected", ["id"], "graph.valid\r")
    add("terminal-cr-edge-id-rejected", ["edges", "0", "id"], "edge.valid\r")
    # Append to the original corpus: never round a fractional revision into an
    # admitted integer or turn a finite integral exponent into float infinity.
    for name, raw, expected in (
        ("rounded-up-fractional-revision", "1.0000000000000001", "schema_mismatch"),
        ("rounded-large-fractional-revision", "9007199254740992.1", "schema_mismatch"),
        ("rounded-below-minimum-revision", "0.99999999999999999999", "schema_mismatch"),
        ("tiny-positive-fractional-revision", "1e-9999", "schema_mismatch"),
        ("tiny-negative-fractional-revision", "-1e-9999", "schema_mismatch"),
        ("negative-zero-decimal-revision", "-0.00", "schema_mismatch"),
        ("exact-wide-integral-decimal-revision", "9007199254740993.0", "decoded"),
        ("exact-wide-integral-exponent-revision", "90071992547409930e-1", "decoded"),
        ("integral-negative-exponent-revision", "1000e-3", "decoded"),
        ("huge-integral-exponent-revision", "1e9999", "decoded"),
        ("huge-negative-integral-exponent-revision", "-1e9999", "schema_mismatch"),
        ("many-digit-integral-decimal-revision", "123456789012345678901234567890123456789.000", "decoded"),
        ("many-digit-fractional-revision", "123456789012345678901234567890123456789.000000001", "schema_mismatch"),
    ):
        add(name, ["revision"], raw=raw, expected=expected)
    if len({case["name"] for case in cases}) != len(cases):
        raise ValueError("Duplicate graph fixture name")
    return cases


def mutate(original, case):
    value = deepcopy(original)
    if case["operation"] == "none":
        return value
    path = case["path"]
    if not path:
        return json.loads(case["json"], parse_float=exact_decimal,
                          parse_constant=reject_non_json_number)
    parent = value
    for part in path[:-1]:
        parent = parent[int(part)] if isinstance(parent, list) else parent[part]
    key = int(path[-1]) if isinstance(parent, list) else path[-1]
    if case["operation"] == "remove":
        del parent[key]
    else:
        parent[key] = json.loads(case["json"], parse_float=exact_decimal,
                                 parse_constant=reject_non_json_number)
    return value


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--write", action="store_true", help="regenerate only the synthetic graph fixture")
    args = parser.parse_args()
    output = output_path(ROOT, OUTPUT.relative_to(ROOT))
    for path, digest in PINS.items():
        if hashlib.sha256((BASE / path).read_bytes()).hexdigest() != digest:
            raise ValueError("Frozen graph input identity changed")
    schema = read_json(BASE / "schemas/dependency-graph.schema.json")
    original = read_json(BASE / "examples/dependency-graph.json")
    cases = vectors(schema, original)
    fixture = {"profile": "android-dependency-graph-wire/v1", "input_sha256": PINS, "cases": cases}
    encoded = json.dumps(fixture, ensure_ascii=False, indent=2) + "\n"
    formats = FormatChecker()
    if not {"date-time", "uri"} <= formats.checkers.keys():
        raise ValueError("Frozen graph format checkers unavailable; install requirements-contracts.txt")
    schemas = [read_json(path) for path in (BASE / "schemas").glob("*.schema.json")]
    registry = Registry().with_resources((item["$id"], Resource.from_contents(item)) for item in schemas)
    validator = FixtureValidator(schema, registry=registry, format_checker=formats)
    counts = {"decoded": 0, "unsupported_version": 0, "schema_mismatch": 0, "invalid_input": 0}
    for case in cases:
        value = mutate(original, case)
        valid = validator.is_valid(value)
        if not isinstance(value, dict):
            outcome = "invalid_input"
        elif value.get("schema_version") != "1.0.0":
            outcome = "unsupported_version"
        else:
            outcome = "decoded" if valid else "schema_mismatch"
        if outcome != case["expected"] or valid != (outcome == "decoded"):
            raise ValueError(f"Frozen graph reference outcome differs: {case['name']}")
        counts[outcome] += 1
    if not all(counts.values()):
        raise ValueError("Graph fixtures lost a required outcome")
    # Preserve the retained fixture until every frozen-reference check has succeeded.
    if args.write:
        write_output(ROOT, OUTPUT.relative_to(ROOT), encoded)
    elif output.read_text() != encoded:
        raise ValueError("Graph fixtures changed; explicitly regenerate and review them")
    print(f"{len(cases)} dependency graph cases agree with the frozen Python schema: {counts}")


if __name__ == "__main__":
    main()
