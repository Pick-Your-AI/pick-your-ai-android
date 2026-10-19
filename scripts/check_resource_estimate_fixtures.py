"""Check synthetic ResourceEstimate decoder cases against the unchanged frozen Python schema."""
import copy
from decimal import Decimal
import hashlib
import json
from pathlib import Path

from jsonschema import Draft202012Validator, FormatChecker, validators
from referencing import Registry, Resource

ROOT = Path(__file__).resolve().parents[1]
BASE = ROOT / "contracts/v1"


def reject_non_json_number(_value):
    raise ValueError("Resource-estimate fixtures require finite JSON numbers")


def read_json(path):
    return json.loads(path.read_text(encoding="utf-8"), parse_float=Decimal,
                      parse_constant=reject_non_json_number)


def is_exact_integer(checker, value):
    if isinstance(value, Decimal):
        return value.is_finite() and value == value.to_integral_value()
    return Draft202012Validator.TYPE_CHECKER.is_type(value, "integer")


ResourceFixtureValidator = validators.extend(
    Draft202012Validator,
    type_checker=Draft202012Validator.TYPE_CHECKER.redefine("integer", is_exact_integer),
)


def main():
    fixture = read_json(ROOT / "core/src/test/resources/contracts/resource-estimate-cases.json")
    if fixture["profile"] != "android-resource-estimate-wire/v1":
        raise ValueError("Unknown resource-estimate fixture profile")
    for key, path in (
        ("schema_sha256", BASE / "schemas/resource-estimate.schema.json"),
        ("example_sha256", BASE / "examples/resource-estimate.json"),
    ):
        if hashlib.sha256(path.read_bytes()).hexdigest() != fixture[key]:
            raise ValueError("Resource-estimate fixture input identity changed")
    schemas = [read_json(path) for path in (BASE / "schemas").glob("*.schema.json")]
    registry = Registry().with_resources((schema["$id"], Resource.from_contents(schema)) for schema in schemas)
    schema = read_json(BASE / "schemas/resource-estimate.schema.json")
    formats = FormatChecker()
    if not {"date-time", "uri"} <= formats.checkers.keys():
        raise ValueError("Resource-estimate format checkers unavailable; install requirements-contracts.txt")
    validator = ResourceFixtureValidator(schema, registry=registry, format_checker=formats)
    original = read_json(BASE / "examples/resource-estimate.json")
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
