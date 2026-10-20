"""Check synthetic structural EvaluationReport cases against the unchanged frozen schema."""
import copy
import hashlib
from pathlib import Path

from jsonschema import FormatChecker
from referencing import Registry, Resource

from frozen_fixture_numbers import FixtureValidator, read_json

ROOT = Path(__file__).resolve().parents[1]
BASE = ROOT / "contracts/v1"


def main():
    fixture = read_json(ROOT / "core/src/test/resources/contracts/frozen-evaluation-report-cases.json")
    if fixture["profile"] != "android-frozen-evaluation-report-wire/v1":
        raise ValueError("Unknown structural EvaluationReport fixture profile")
    for key, path in (
        ("schema_sha256", BASE / "schemas/evaluation-report.schema.json"),
        ("common_sha256", BASE / "schemas/common.schema.json"),
        ("example_sha256", BASE / "examples/evaluation-report.json"),
    ):
        if hashlib.sha256(path.read_bytes()).hexdigest() != fixture[key]:
            raise ValueError("Structural EvaluationReport fixture input identity changed")
    schemas = [read_json(path) for path in (BASE / "schemas").glob("*.schema.json")]
    registry = Registry().with_resources((schema["$id"], Resource.from_contents(schema)) for schema in schemas)
    schema = read_json(BASE / "schemas/evaluation-report.schema.json")
    formats = FormatChecker()
    if not {"date-time", "uri"} <= formats.checkers.keys():
        raise ValueError("Frozen format checkers unavailable; install requirements-contracts.txt")
    validator = FixtureValidator(schema, registry=registry, format_checker=formats)
    original = read_json(BASE / "examples/evaluation-report.json")
    names = set()
    counts = {"decoded": 0, "unsupported_version": 0, "schema_mismatch": 0}
    for case in fixture["cases"]:
        if not isinstance(case["name"], str) or not case["name"] or case["name"] in names:
            raise ValueError("Structural EvaluationReport fixture names must be unique")
        names.add(case["name"])
        if set(case) - {"name", "expected", "set", "remove", "finding_set", "finding_remove",
                        "summary_set", "summary_remove"}:
            raise ValueError("Unsupported structural EvaluationReport mutation")
        value = copy.deepcopy(original)
        value.update(case.get("set", {}))
        for key in case.get("remove", []):
            del value[key]
        if "finding_set" in case:
            value["findings"][0].update(case["finding_set"])
        for key in case.get("finding_remove", []):
            del value["findings"][0][key]
        if "summary_set" in case:
            value["summary"].update(case["summary_set"])
        for key in case.get("summary_remove", []):
            del value["summary"][key]
        outcome = "unsupported_version" if value.get("schema_version") != "1.0.0" else (
            "decoded" if validator.is_valid(value) else "schema_mismatch"
        )
        if outcome != case["expected"]:
            raise ValueError(f"Structural EvaluationReport reference differs: {case['name']}")
        counts[outcome] += 1
    if not all(counts.values()):
        raise ValueError("Structural EvaluationReport fixtures need positive, version and malformed cases")
    print(f"{len(names)} structural evaluation-report cases agree with the frozen Python schema: {counts}")


if __name__ == "__main__":
    main()
