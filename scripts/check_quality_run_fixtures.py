"""Validate the synthetic Kotlin quality-run cases against the unchanged frozen Python schema."""
import copy
import hashlib
from pathlib import Path

from jsonschema import FormatChecker
from referencing import Registry, Resource

from frozen_fixture_numbers import FixtureValidator, read_json

ROOT = Path(__file__).resolve().parents[1]
BASE = ROOT / "contracts/v1"


def main():
    fixture = read_json(ROOT / "core/src/test/resources/contracts/quality-run-cases.json")
    if fixture["profile"] != "android-quality-run-wire/v1":
        raise ValueError("Unknown quality-run fixture profile")
    for key, path in (
        ("schema_sha256", BASE / "schemas/quality-evaluation-run.schema.json"),
        ("example_sha256", BASE / "examples/quality-evaluation-run.json"),
    ):
        if hashlib.sha256(path.read_bytes()).hexdigest() != fixture[key]:
            raise ValueError("Quality-run fixture input identity changed")
    schemas = [read_json(path) for path in (BASE / "schemas").glob("*.schema.json")]
    registry = Registry().with_resources((schema["$id"], Resource.from_contents(schema)) for schema in schemas)
    schema = read_json(BASE / "schemas/quality-evaluation-run.schema.json")
    formats = FormatChecker()
    if not {"date-time", "uri"} <= formats.checkers.keys():
        raise ValueError("Quality-run format checkers unavailable; install requirements-contracts.txt")
    validator = FixtureValidator(schema, registry=registry, format_checker=formats)
    original = read_json(BASE / "examples/quality-evaluation-run.json")
    names = set()
    counts = {"decoded": 0, "unsupported_version": 0, "schema_mismatch": 0}
    for case in fixture["cases"]:
        if not isinstance(case["name"], str) or not case["name"] or case["name"] in names:
            raise ValueError("Quality-run fixture names must be unique")
        names.add(case["name"])
        if set(case) - {"name", "expected", "set", "remove", "measurement_set", "measurement_remove"}:
            raise ValueError("Unsupported quality-run mutation")
        value = copy.deepcopy(original)
        value.update(case.get("set", {}))
        for key in case.get("remove", []):
            del value[key]
        if "measurement_set" in case:
            value["measurements"][0].update(case["measurement_set"])
        for key in case.get("measurement_remove", []):
            del value["measurements"][0][key]
        if value.get("schema_version") != "1.0.0":
            outcome = "unsupported_version"
        else:
            outcome = "decoded" if validator.is_valid(value) else "schema_mismatch"
        if outcome != case["expected"]:
            raise ValueError(f"Quality-run reference outcome differs: {case['name']}")
        counts[outcome] += 1
    if not all(counts.values()):
        raise ValueError("Quality-run fixtures must retain positive, version and malformed cases")
    print(f"{len(names)} quality-run cases agree with the frozen Python schema: {counts}")


if __name__ == "__main__":
    main()
