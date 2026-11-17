"""Check explicit structural CostEstimate cases against the unchanged frozen Python schema."""
import copy
import hashlib
from pathlib import Path

from jsonschema import FormatChecker
from frozen_fixture_numbers import FixtureValidator, read_json
from referencing import Registry, Resource

ROOT = Path(__file__).resolve().parents[1]
BASE = ROOT / "contracts/v1"


def main():
    fixture = read_json(ROOT / "core/src/test/resources/contracts/cost-estimate-cases.json")
    if fixture["profile"] != "android-frozen-cost-estimate-wire/v1":
        raise ValueError("Unknown CostEstimate fixture profile")
    for key, path in (
        ("schema_sha256", BASE / "schemas/cost-estimate.schema.json"),
        ("common_sha256", BASE / "schemas/common.schema.json"),
        ("example_sha256", BASE / "examples/cost-estimate.json"),
    ):
        if hashlib.sha256(path.read_bytes()).hexdigest() != fixture[key]:
            raise ValueError("CostEstimate fixture input identity changed")
    schemas = [read_json(path) for path in (BASE / "schemas").glob("*.schema.json")]
    registry = Registry().with_resources((schema["$id"], Resource.from_contents(schema)) for schema in schemas)
    formats = FormatChecker()
    if "date-time" not in formats.checkers:
        raise ValueError("CostEstimate format checker unavailable; install requirements-contracts.txt")
    validator = FixtureValidator(read_json(BASE / "schemas/cost-estimate.schema.json"),
                                    registry=registry, format_checker=formats)
    original = read_json(BASE / "examples/cost-estimate.json")
    names = set()
    counts = {"decoded": 0, "unsupported_version": 0, "schema_mismatch": 0}
    for case in fixture["cases"]:
        if not isinstance(case["name"], str) or not case["name"] or case["name"] in names:
            raise ValueError("CostEstimate fixture names must be nonempty and unique")
        names.add(case["name"])
        if set(case) - {"name", "expected", "set", "remove", "line_set", "line_remove"}:
            raise ValueError("Unsupported CostEstimate mutation")
        value = copy.deepcopy(original)
        value.update(case.get("set", {}))
        for key in case.get("remove", []):
            del value[key]
        if "line_set" in case:
            value["line_items"][0].update(case["line_set"])
        for key in case.get("line_remove", []):
            del value["line_items"][0][key]
        if value.get("schema_version") != "1.0.0":
            outcome = "unsupported_version"
        else:
            outcome = "decoded" if validator.is_valid(value) else "schema_mismatch"
        if outcome != case["expected"]:
            raise ValueError(f"CostEstimate reference outcome differs: {case['name']}")
        counts[outcome] += 1
    if len(names) != 224 or not all(counts.values()):
        raise ValueError("CostEstimate fixtures must retain all explicit positive, version and malformed cases")
    print(f"{len(names)} CostEstimate cases agree with the frozen Python schema: {counts}")


if __name__ == "__main__":
    main()
