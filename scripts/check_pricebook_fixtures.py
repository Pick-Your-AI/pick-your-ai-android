"""Check explicit structural Pricebook cases against the unchanged frozen Python schema."""
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
    fixture = read_json(ROOT / "core/src/test/resources/contracts/pricebook-cases.json")
    if fixture["profile"] != "android-frozen-pricebook-wire/v1":
        raise ValueError("Unknown Pricebook fixture profile")
    for key, path in (
        ("schema_sha256", BASE / "schemas/pricebook.schema.json"),
        ("common_sha256", BASE / "schemas/common.schema.json"),
        ("example_sha256", BASE / "examples/pricebook.json"),
    ):
        if hashlib.sha256(path.read_bytes()).hexdigest() != fixture[key]:
            raise ValueError("Pricebook fixture input identity changed")
    schemas = [read_json(path) for path in (BASE / "schemas").glob("*.schema.json")]
    registry = Registry().with_resources((schema["$id"], Resource.from_contents(schema)) for schema in schemas)
    formats = FormatChecker()
    if not {"date-time", "uri"}.issubset(formats.checkers):
        raise ValueError("Pricebook format checkers unavailable; install requirements-contracts.txt")
    validator = Draft202012Validator(read_json(BASE / "schemas/pricebook.schema.json"),
                                    registry=registry, format_checker=formats)
    original = read_json(BASE / "examples/pricebook.json")
    names = set()
    counts = {"decoded": 0, "unsupported_version": 0, "schema_mismatch": 0}
    for case in fixture["cases"]:
        if not isinstance(case["name"], str) or not case["name"] or case["name"] in names:
            raise ValueError("Pricebook fixture names must be nonempty and unique")
        names.add(case["name"])
        if set(case) - {"name", "expected", "set", "remove", "rate_set", "rate_remove"}:
            raise ValueError("Unsupported Pricebook mutation")
        value = copy.deepcopy(original)
        value.update(case.get("set", {}))
        for key in case.get("remove", []):
            del value[key]
        if "rate_set" in case:
            value["rates"][0].update(case["rate_set"])
        for key in case.get("rate_remove", []):
            del value["rates"][0][key]
        if value.get("schema_version") != "1.0.0":
            outcome = "unsupported_version"
        else:
            outcome = "decoded" if validator.is_valid(value) else "schema_mismatch"
        if outcome != case["expected"]:
            raise ValueError(f"Pricebook reference outcome differs: {case['name']}")
        counts[outcome] += 1
    if len(names) != 188 or not all(counts.values()):
        raise ValueError("Pricebook fixtures must retain all explicit positive, version and malformed cases")
    print(f"{len(names)} Pricebook cases agree with the frozen Python schema: {counts}")


if __name__ == "__main__":
    main()
