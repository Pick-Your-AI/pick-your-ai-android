"""Check synthetic ResourceEstimate decoder cases against the unchanged frozen Python schema."""
import copy
import hashlib
import json
from pathlib import Path

from jsonschema import Draft202012Validator, FormatChecker
from referencing import Registry, Resource

ROOT = Path(__file__).resolve().parents[1]
BASE = ROOT / "contracts/v1"


def main():
    fixture = json.loads((ROOT / "core/src/test/resources/contracts/resource-estimate-cases.json").read_text())
    if fixture["profile"] != "android-resource-estimate-wire/v1":
        raise ValueError("Unknown resource-estimate fixture profile")
    for key, path in (
        ("schema_sha256", BASE / "schemas/resource-estimate.schema.json"),
        ("example_sha256", BASE / "examples/resource-estimate.json"),
    ):
        if hashlib.sha256(path.read_bytes()).hexdigest() != fixture[key]:
            raise ValueError("Resource-estimate fixture input identity changed")
    schemas = [json.loads(path.read_text()) for path in (BASE / "schemas").glob("*.schema.json")]
    registry = Registry().with_resources((schema["$id"], Resource.from_contents(schema)) for schema in schemas)
    schema = json.loads((BASE / "schemas/resource-estimate.schema.json").read_text())
    formats = FormatChecker()
    if not {"date-time", "uri"} <= formats.checkers.keys():
        raise ValueError("Resource-estimate format checkers unavailable; install requirements-contracts.txt")
    validator = Draft202012Validator(schema, registry=registry, format_checker=formats)
    original = json.loads((BASE / "examples/resource-estimate.json").read_text())
    names = set()
    counts = {"decoded": 0, "unsupported_version": 0, "schema_mismatch": 0}
    for case in fixture["cases"]:
        if not isinstance(case["name"], str) or not case["name"] or case["name"] in names:
            raise ValueError("Resource-estimate fixture names must be unique")
        names.add(case["name"])
        if set(case) - {"name", "expected", "set", "remove", "estimate_set", "estimate_remove"}:
            raise ValueError("Unsupported resource-estimate mutation")
        value = copy.deepcopy(original)
        value.update(case.get("set", {}))
        for key in case.get("remove", []):
            del value[key]
        if "estimate_set" in case:
            value["estimates"][0].update(case["estimate_set"])
        for key in case.get("estimate_remove", []):
            del value["estimates"][0][key]
        if value.get("schema_version") != "1.0.0":
            outcome = "unsupported_version"
        else:
            outcome = "decoded" if validator.is_valid(value) else "schema_mismatch"
        if outcome != case["expected"]:
            raise ValueError(f"Resource-estimate reference outcome differs: {case['name']}")
        counts[outcome] += 1
    if not all(counts.values()):
        raise ValueError("Resource-estimate fixtures need positive, version and malformed cases")
    print(f"{len(names)} resource-estimate cases agree with the frozen Python schema: {counts}")


if __name__ == "__main__":
    main()
