"""Check explicit synthetic Build cases against the unchanged frozen Python schemas."""
import copy
from decimal import Decimal
import hashlib
import json
from pathlib import Path

from jsonschema import Draft202012Validator, FormatChecker, validators
from referencing import Registry, Resource
from referencing.jsonschema import DRAFT202012

ROOT = Path(__file__).resolve().parents[1]
BASE = ROOT / "contracts/v1"


def reject_constant(_value):
    raise ValueError("Non-JSON numeric constant in Build fixture")


def read_json(path):
    return json.loads(path.read_text(encoding="utf-8"), parse_float=Decimal,
                      parse_constant=reject_constant)


def exact_integer(checker, value):
    if isinstance(value, Decimal):
        return value.is_finite() and value == value.to_integral_value()
    return Draft202012Validator.TYPE_CHECKER.is_type(value, "integer")


BuildValidator = validators.extend(
    Draft202012Validator,
    type_checker=Draft202012Validator.TYPE_CHECKER.redefine("integer", exact_integer),
)


def checker_schema(path):
    schema = read_json(path)
    if schema.pop("$schema", None) != Draft202012Validator.META_SCHEMA["$id"]:
        raise ValueError("Unexpected frozen Build schema dialect")
    # Select the known dialect explicitly while allowing referenced resources to
    # retain the exact-number type checker. Frozen schema bytes are not changed.
    return schema


def main():
    fixture = read_json(ROOT / "core/src/test/resources/contracts/build-cases.json")
    if fixture["profile"] != "android-frozen-build-wire/v1":
        raise ValueError("Unknown Build fixture profile")
    if set(fixture["schemas"]) != {"build.schema.json", "selection.schema.json", "common.schema.json"}:
        raise ValueError("Build fixture schema inventory changed")
    for name, expected in fixture["schemas"].items():
        if hashlib.sha256((BASE / "schemas" / name).read_bytes()).hexdigest() != expected:
            raise ValueError("Build fixture schema identity changed")
    example = BASE / "examples/build.json"
    if hashlib.sha256(example.read_bytes()).hexdigest() != fixture["example_sha256"]:
        raise ValueError("Build fixture example identity changed")
    schemas = [checker_schema(path) for path in (BASE / "schemas").glob("*.schema.json")]
    registry = Registry().with_resources((schema["$id"], Resource.from_contents(schema, default_specification=DRAFT202012)) for schema in schemas)
    schema = checker_schema(BASE / "schemas/build.schema.json")
    formats = FormatChecker()
    if not {"date-time", "uri"}.issubset(formats.checkers):
        raise ValueError("Build format checkers unavailable; install requirements-contracts.txt")
    validator = BuildValidator(schema, registry=registry, format_checker=formats)
    original = read_json(example)
    names = set()
    counts = {"decoded": 0, "unsupported_version": 0, "schema_mismatch": 0}
    for case in fixture["cases"]:
        if set(case) != {"name", "expected", "operations"} or not isinstance(case["name"], str) or not case["name"] or case["name"] in names:
            raise ValueError("Build fixture cases must be uniquely named and explicitly shaped")
        names.add(case["name"])
        value = copy.deepcopy(original)
        for operation in case["operations"]:
            op = operation["op"]
            if set(operation) != ({"op", "path", "value"} if op == "set" else {"op", "path"}) or op not in {"set", "remove"}:
                raise ValueError("Unsupported Build fixture operation")
            path = operation["path"]
            if not isinstance(path, list) or not path or not all(isinstance(part, str) and part for part in path):
                raise ValueError("Invalid Build fixture path")
            parent = value
            for part in path[:-1]:
                parent = parent[int(part)] if isinstance(parent, list) else parent[part]
            key = int(path[-1]) if isinstance(parent, list) else path[-1]
            if op == "set":
                parent[key] = copy.deepcopy(operation["value"])
            else:
                del parent[key]
        outcome = "unsupported_version" if value.get("schema_version") != "1.0.0" else "decoded" if validator.is_valid(value) else "schema_mismatch"
        if outcome != case["expected"]:
            raise ValueError(f"Build reference outcome differs: {case['name']}")
        counts[outcome] += 1
    if not all(counts.values()):
        raise ValueError("Build fixtures must retain positive, version and invalid cases")
    print(f"{len(names)} Build cases agree with the frozen Python schemas: {counts}")


if __name__ == "__main__":
    main()
