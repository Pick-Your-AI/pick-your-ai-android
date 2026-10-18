"""Check explicit structural Ruleset cases against the unchanged frozen Python schema."""
import copy
from decimal import Decimal
import hashlib
import json
from pathlib import Path

from jsonschema import Draft202012Validator, FormatChecker
from referencing import Registry, Resource

ROOT = Path(__file__).resolve().parents[1]
BASE = ROOT / "contracts/v1"


def read_json(path):
    return json.loads(path.read_text(encoding="utf-8"), parse_float=Decimal)


def main():
    fixture = read_json(ROOT / "core/src/test/resources/contracts/ruleset-cases.json")
    if fixture["profile"] != "android-frozen-ruleset-wire/v1":
        raise ValueError("Unknown Ruleset fixture profile")
    for key, path in (
        ("schema_sha256", BASE / "schemas/ruleset.schema.json"),
        ("common_sha256", BASE / "schemas/common.schema.json"),
        ("example_sha256", BASE / "examples/ruleset.json"),
        ("baseline_sha256", BASE / "baseline/core-ruleset.json"),
    ):
        if hashlib.sha256(path.read_bytes()).hexdigest() != fixture[key]:
            raise ValueError("Ruleset fixture input identity changed")
    schemas = [read_json(path) for path in (BASE / "schemas").glob("*.schema.json")]
    registry = Registry().with_resources((schema["$id"], Resource.from_contents(schema)) for schema in schemas)
    formats = FormatChecker()
    if "date-time" not in formats.checkers:
        raise ValueError("Ruleset format checker unavailable; install requirements-contracts.txt")
    validator = Draft202012Validator(read_json(BASE / "schemas/ruleset.schema.json"),
                                    registry=registry, format_checker=formats)
    sources = {
        "minimal": fixture["base_document"],
        "example": read_json(BASE / "examples/ruleset.json"),
        "baseline": read_json(BASE / "baseline/core-ruleset.json"),
    }
    names = set()
    counts = {"decoded": 0, "unsupported_version": 0, "schema_mismatch": 0}
    for case in fixture["cases"]:
        if not isinstance(case["name"], str) or not case["name"] or case["name"] in names:
            raise ValueError("Ruleset fixture names must be nonempty and unique")
        names.add(case["name"])
        if set(case) - {"name", "expected", "source", "set", "remove", "rule_set", "rule_remove"}:
            raise ValueError("Unsupported Ruleset mutation")
        value = copy.deepcopy(sources[case.get("source", "minimal")])
        value.update(case.get("set", {}))
        for key in case.get("remove", []):
            del value[key]
        if "rule_set" in case:
            value["rules"][0].update(case["rule_set"])
        for key in case.get("rule_remove", []):
            del value["rules"][0][key]
        if value.get("schema_version") != "1.0.0":
            outcome = "unsupported_version"
        else:
            outcome = "decoded" if validator.is_valid(value) else "schema_mismatch"
        if outcome != case["expected"]:
            raise ValueError(f"Ruleset reference outcome differs: {case['name']}")
        counts[outcome] += 1
    if len(names) != 145 or not all(counts.values()):
        raise ValueError("Ruleset fixtures must retain all explicit positive, version and malformed cases")
    print(f"{len(names)} Ruleset cases agree with the frozen Python schema: {counts}")


if __name__ == "__main__":
    main()
