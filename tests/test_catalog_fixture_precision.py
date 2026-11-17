"""Actual Catalog fixture CLI checks for exact JSON numbers and referenced items.

Only private copied fixtures are mutated. The 225 checked-in cases, their raw
number spellings, frozen schema bytes and the original checker guards stay intact.
Extreme exponents exercise this Python reference boundary, not Android budgets.
"""
import ast
from contextlib import contextmanager
import hashlib
import json
import os
from pathlib import Path
import shutil
import subprocess
import sys
import tempfile
import unittest


ROOT = Path(__file__).resolve().parents[1]
FIXTURE = Path("core/src/test/resources/contracts/frozen-catalog-cases.json")
CHECKER = Path("scripts/check_frozen_catalog_fixtures.py")
HELPER = Path("scripts/frozen_fixture_numbers.py")
INTEGER_FIELDS = (
    ("workload", "concurrency", 1),
    ("workload", "requests_per_day", 0),
    ("workload", "avg_input_tokens", 0),
    ("workload", "avg_output_tokens", 0),
    ("workload", "latency_budget_ms", 1),
    ("model", "context_window_tokens", 1),
    ("model", "max_output_tokens", 1),
    ("context_policy", "max_allowed_tokens", 1),
    ("compute_profile", "accelerator_count", 0),
    ("compute_profile", "cpu_cores", 1),
    ("inference_engine", "max_batch_size", 1),
    ("embedding_model", "embedding_dimensions", 1),
    ("embedding_model", "max_input_tokens", 1),
    ("vector_database", "max_dimensions", 1),
    ("integration", "rate_limit_per_minute", 0),
    ("training_dataset", "example_count", 0),
    ("monitoring_stack", "retention_days", 0),
)
NUMBER_FIELDS = (
    ("workload", "uptime_target_pct", 100),
    ("model", "min_weights_vram_gb", None),
    ("accelerator", "vram_gb", None),
    ("accelerator", "rated_power_w", None),
    ("compute_profile", "ram_gb", None),
    ("compute_profile", "disk_gb", None),
    ("compute_profile", "network_gbps", None),
    ("storage", "max_capacity_gb", None),
    ("training_compute", "max_gpu_hours", None),
    ("cost_policy", "alert_threshold_pct", 100),
)
INTEGER_LOCATIONS = tuple(
    (target, kind, field, minimum)
    for target in ("item", "snapshot")
    for kind, field, minimum in INTEGER_FIELDS
) + (("snapshot", None, "revision", 1),)


def numeric_case(target, kind, field, token, expected, *, version=None):
    path = ["revision"] if kind is None else (
        ["spec", field] if target == "item" else ["items", 0, "spec", field]
    )
    marker = "__CATALOG_EXACT_NUMBER__"
    value = {
        "name": f"precision {target} {kind} {field} {token} {version}",
        "target": target,
        "expected": expected,
        "operations": [{"op": "set", "path": path, "value": marker}],
    }
    if kind is not None:
        value["kind"] = kind
    if version is not None:
        value["operations"].append(
            {"op": "set", "path": ["schema_version"], "value": version})
    encoded = json.dumps(value, separators=(",", ":"))
    if encoded.count('"' + marker + '"') != 1:
        raise ValueError("Ambiguous private numeric case")
    return encoded.replace('"' + marker + '"', token)


def case_spans(source):
    """Locate complete case values without reserializing any original token."""
    position = source.index("[", source.index('"cases"')) + 1
    decoder = json.JSONDecoder()
    spans = []
    while True:
        while source[position] in " \t\r\n,":
            position += 1
        if source[position] == "]":
            return spans
        _, end = decoder.raw_decode(source, position)
        spans.append((position, end))
        position = end


class CatalogFixturePrecisionTests(unittest.TestCase):
    @contextmanager
    def copied_checkout(self):
        with tempfile.TemporaryDirectory(prefix="catalog-fixture-precision-") as directory:
            root = Path(directory)
            shutil.copytree(ROOT / "contracts/v1", root / "contracts/v1",
                            ignore=shutil.ignore_patterns("__pycache__"))
            paths = [CHECKER, FIXTURE]
            # The old checker has no helper. Copy the real dependency when present;
            # never replace the CLI with a mock or mutate its source in a test.
            if (ROOT / HELPER).is_file():
                paths.append(HELPER)
            for relative in paths:
                destination = root / relative
                destination.parent.mkdir(parents=True, exist_ok=True)
                shutil.copyfile(ROOT / relative, destination)
            yield root

    def replace_cases(self, root, cases):
        source = (ROOT / FIXTURE).read_text(encoding="utf-8")
        spans = case_spans(source)
        original = json.loads(source)["cases"]
        self.assertEqual(225, len(spans))
        # Preserve a real original witness for every required outcome category.
        anchors = {next(index for index, case in enumerate(original)
                        if case["expected"] == outcome)
                   for outcome in ("decoded", "unsupported_version", "schema_mismatch")}
        positions = [index for index in range(len(spans)) if index not in anchors]
        self.assertGreater(len(cases), 0)
        self.assertLessEqual(len(cases), len(positions))
        changes = dict(zip(positions, cases))
        parts = []
        cursor = 0
        for index, (start, end) in enumerate(spans):
            parts.extend((source[cursor:start], changes.get(index, source[start:end])))
            cursor = end
        changed = "".join(parts) + source[cursor:]
        new_spans = case_spans(changed)
        self.assertEqual(225, len(new_spans))
        inverse = []
        cursor = 0
        for index, (start, end) in enumerate(new_spans):
            old_start, old_end = spans[index]
            if index not in changes:
                self.assertEqual(source[old_start:old_end], changed[start:end])
            inverse.extend((changed[cursor:start], source[old_start:old_end]))
            cursor = end
        self.assertEqual(source, "".join(inverse) + changed[cursor:])
        (root / FIXTURE).write_text(changed, encoding="utf-8")

    def file_state(self, root):
        result = {}
        for path in sorted(root.rglob("*")):
            if path.is_file():
                status = path.stat()
                result[str(path.relative_to(root))] = (
                    hashlib.sha256(path.read_bytes()).hexdigest(),
                    status.st_mode, status.st_ino, status.st_mtime_ns,
                )
        return result

    def run_checker(self, root, *, options=(), optimization=None, program=None):
        environment = {**os.environ, "PYTHONDONTWRITEBYTECODE": "1"}
        environment.pop("PYTHONOPTIMIZE", None)
        if optimization is not None:
            environment["PYTHONOPTIMIZE"] = optimization
        command = [sys.executable, "-B", *options]
        script = str(root / CHECKER)
        if program is None:
            command.append(script)
        else:
            prefix = ("import runpy,sys; from pathlib import Path; "
                      "sys.path.insert(0,str(Path(sys.argv[1]).parent)); ")
            command += ["-c", prefix + program, script]
        before = self.file_state(root)
        result = subprocess.run(command, cwd=root, env=environment, text=True,
                                capture_output=True, timeout=25)
        self.assertEqual(before, self.file_state(root),
                         "The CLI changed a source or fixture input")
        return result

    def assert_agrees(self, root, result):
        self.assertEqual(0, result.returncode, result.stderr)
        self.assertEqual("", result.stderr)
        cases = json.loads((root / FIXTURE).read_text(encoding="utf-8"))["cases"]
        expected = {outcome: sum(case["expected"] == outcome for case in cases)
                    for outcome in ("decoded", "unsupported_version", "schema_mismatch")}
        self.assertTrue(all(expected.values()))
        line, = result.stdout.splitlines()
        self.assertEqual("225 catalog cases agree with the frozen Python schemas: ",
                         line[:line.index("{")])
        self.assertEqual(expected, ast.literal_eval(line[line.index("{"):]))

    def assert_denied(self, result, message):
        self.assertNotEqual(0, result.returncode, result.stdout)
        self.assertNotIn("cases agree with", result.stdout)
        self.assertIn(message, result.stderr)

    def replace_once(self, path, before, after):
        source = path.read_text(encoding="utf-8")
        self.assertEqual(1, source.count(before))
        path.write_text(source.replace(before, after), encoding="utf-8")

    def repin_private_input(self, root, relative, before):
        after = hashlib.sha256((root / "contracts/v1" / relative).read_bytes()).hexdigest()
        self.assertNotEqual(before, after)
        self.replace_once(root / FIXTURE, '"' + before + '"', '"' + after + '"')

    def test_numeric_locations_match_every_frozen_catalog_schema_field(self):
        schema = json.loads((ROOT / "contracts/v1/schemas/catalog-item.schema.json").read_text())
        integer_fields, number_fields = set(), set()
        for name, specification in schema["$defs"].items():
            for field, definition in specification.get("properties", {}).items():
                if definition.get("type") == "integer":
                    integer_fields.add((name.removeprefix("spec_"), field, definition["minimum"]))
                if definition.get("type") == "number":
                    self.assertEqual(0, definition["minimum"])
                    number_fields.add((name.removeprefix("spec_"), field, definition.get("maximum")))
        self.assertEqual(set(INTEGER_FIELDS), integer_fields)
        self.assertEqual(set(NUMBER_FIELDS), number_fields)
        snapshot = json.loads((ROOT / "contracts/v1/schemas/catalog-snapshot.schema.json").read_text())
        self.assertEqual({"type": "integer", "minimum": 1}, snapshot["properties"]["revision"])
        self.assertEqual(35, len(INTEGER_LOCATIONS))

    def test_complete_canonical_corpus_and_original_outcome_counts(self):
        with self.copied_checkout() as root:
            self.assertEqual((ROOT / FIXTURE).read_bytes(), (root / FIXTURE).read_bytes())
            result = self.run_checker(root)
            self.assert_agrees(root, result)
            self.assertEqual({"decoded": 78, "unsupported_version": 12, "schema_mismatch": 135},
                             ast.literal_eval(result.stdout[result.stdout.index("{"):]))

    def test_fractional_rejections_at_all_standalone_item_integer_fields(self):
        cases = [numeric_case("item", kind, field, token, "schema_mismatch")
                 for kind, field, _ in INTEGER_FIELDS
                 for token in ("9007199254740992.1", "1.0000000000000001")]
        with self.copied_checkout() as root:
            self.replace_cases(root, cases)
            self.assert_agrees(root, self.run_checker(root))

    def test_fractional_rejections_through_snapshot_item_references_and_revision(self):
        cases = [numeric_case(target, kind, field, token, "schema_mismatch")
                 for target, kind, field, _ in INTEGER_LOCATIONS if target == "snapshot"
                 for token in ("9007199254740992.1", "1.0000000000000001")]
        with self.copied_checkout() as root:
            self.replace_cases(root, cases)
            self.assert_agrees(root, self.run_checker(root))

    def test_integral_decimal_exponent_and_wide_values_at_all_integer_locations(self):
        tokens = ("1.0", "1000e-3", "9007199254740993.0", "1e1000", "1e1000000000")
        cases = [numeric_case(target, kind, field, token, "decoded")
                 for target, kind, field, _ in INTEGER_LOCATIONS for token in tokens]
        with self.copied_checkout() as root:
            self.replace_cases(root, cases)
            self.assert_agrees(root, self.run_checker(root))

    def test_boolean_string_and_nonintegral_underflow_keep_integer_rejections(self):
        cases = [numeric_case(target, kind, field, token, "schema_mismatch")
                 for target, kind, field, _ in INTEGER_LOCATIONS
                 for token in ("true", "false", '"1"', "1e-1000", "-1e-1000")]
        with self.copied_checkout() as root:
            self.replace_cases(root, cases)
            self.assert_agrees(root, self.run_checker(root))

    def test_integer_minima_and_signed_zero_are_mathematical(self):
        cases = [numeric_case(target, kind, field, token,
                              "decoded" if minimum == 0 else "schema_mismatch")
                 for target, kind, field, minimum in INTEGER_LOCATIONS
                 for token in ("0.0", "-0e1000")]
        cases += [numeric_case(target, kind, field, "-1e1000", "schema_mismatch")
                  for target, kind, field, _ in INTEGER_LOCATIONS]
        with self.copied_checkout() as root:
            self.replace_cases(root, cases)
            self.assert_agrees(root, self.run_checker(root))

    def test_number_minima_do_not_round_negative_underflow_to_zero(self):
        cases = [numeric_case(target, kind, field, token, expected)
                 for target in ("item", "snapshot")
                 for kind, field, _ in NUMBER_FIELDS
                 for token, expected in (("-1e-1000", "schema_mismatch"),
                                         ("1e-1000", "decoded"), ("-0e1000", "decoded"))]
        with self.copied_checkout() as root:
            self.replace_cases(root, cases)
            self.assert_agrees(root, self.run_checker(root))

    def test_percentage_maxima_and_unbounded_finite_numbers_remain_distinct(self):
        cases = [numeric_case(target, kind, field, token, expected)
                 for target in ("item", "snapshot")
                 for kind, field, maximum in NUMBER_FIELDS
                 for token, expected in (
                     (("100.0", "decoded"), ("100.0000000000000001", "schema_mismatch"),
                      ("1e1000", "schema_mismatch"))
                     if maximum is not None else (("1e1000", "decoded"),))]
        with self.copied_checkout() as root:
            self.replace_cases(root, cases)
            self.assert_agrees(root, self.run_checker(root))

    def test_fractional_decoded_claim_is_rejected_under_normal_and_optimized_python(self):
        for options, optimization in (((), None), (("-O",), None), ((), "2")):
            with self.subTest(options=options, optimization=optimization), self.copied_checkout() as root:
                self.replace_cases(root, [numeric_case(
                    "snapshot", "model", "context_window_tokens",
                    "9007199254740992.1", "decoded")])
                self.assert_denied(self.run_checker(root, options=options, optimization=optimization),
                                   "Catalog reference outcome differs")

    def test_non_json_constants_are_rejected_before_unsupported_version(self):
        for token in ("NaN", "Infinity", "-Infinity"):
            with self.subTest(token=token), self.copied_checkout() as root:
                self.replace_cases(root, [numeric_case(
                    "snapshot", None, "revision", token, "unsupported_version", version="2.0.0")])
                self.assert_denied(self.run_checker(root), "finite JSON numbers")

    def test_unrepresentable_exponents_reject_with_either_decimal_trap_setting(self):
        for program in (None, "import decimal; "
                        "decimal.getcontext().traps[decimal.InvalidOperation]=False; "
                        "runpy.run_path(sys.argv[1],run_name='__main__')"):
            with self.subTest(program=program), self.copied_checkout() as root:
                self.replace_cases(root, [numeric_case(
                    "item", "workload", "concurrency", "1e9223372036854775808",
                    "unsupported_version", version="2.0.0")])
                self.assert_denied(self.run_checker(root, program=program), "decimal representation")

    def test_finite_unsupported_version_precedes_schema_validation(self):
        cases = [numeric_case(target, "workload", "concurrency", token,
                              "unsupported_version", version="2.0.0")
                 for target in ("item", "snapshot")
                 for token in ("1.0000000000000001", "-1e1000", "1e1000000000")]
        with self.copied_checkout() as root:
            self.replace_cases(root, cases)
            self.assert_agrees(root, self.run_checker(root))

    def test_example_input_uses_the_same_exact_numeric_reader(self):
        with self.copied_checkout() as root:
            relative = "examples/catalog.json"
            path = root / "contracts/v1" / relative
            before = hashlib.sha256(path.read_bytes()).hexdigest()
            self.replace_once(path, '"concurrency": 2,', '"concurrency": 1.0000000000000001,')
            self.repin_private_input(root, relative, before)
            self.assert_denied(self.run_checker(root), "Catalog reference outcome differs")

    def test_id_uri_timestamp_and_closed_spec_guards_remain_active(self):
        cases = []
        for target in ("item", "snapshot"):
            for name, path, value in (
                ("id", ["id"], "invalid id"),
                ("uri", ["provenance", 0, "source_uri"], "invalid uri with spaces"),
                ("time", ["provenance", 0, "checked_at"], "not-a-time"),
                ("spec", ["spec", "unsupported_property"], True),
            ):
                item_path = path if target == "item" else ["items", 0, *path]
                cases.append(json.dumps({
                    "name": f"guard {target} {name}", "target": target, "kind": "workload",
                    "expected": "schema_mismatch",
                    "operations": [{"op": "set", "path": item_path, "value": value}],
                }))
        with self.copied_checkout() as root:
            self.replace_cases(root, cases)
            self.assert_agrees(root, self.run_checker(root))

    def test_profile_inventory_hash_and_fixed_case_count_guards_remain_active(self):
        for guard in ("profile", "inventory", "hash", "count"):
            with self.subTest(guard=guard), self.copied_checkout() as root:
                fixture = root / FIXTURE
                if guard == "profile":
                    self.replace_once(fixture, '"android-frozen-catalog-wire/v1"',
                                      '"android-frozen-catalog-wire/v2"')
                    message = "Unknown catalog fixture profile"
                elif guard == "inventory":
                    self.replace_once(fixture, '"examples/catalog.json"', '"examples/other.json"')
                    message = "Catalog fixture input inventory changed"
                elif guard == "hash":
                    path = root / "contracts/v1/schemas/catalog-item.schema.json"
                    path.write_bytes(path.read_bytes() + b" ")
                    message = "Catalog fixture input identity changed"
                else:
                    source = fixture.read_text(encoding="utf-8")
                    spans = case_spans(source)
                    first_start, _ = spans[0]
                    second_start, _ = spans[1]
                    fixture.write_text(source[:first_start] + source[second_start:], encoding="utf-8")
                    message = "Expected all 225"
                self.assert_denied(self.run_checker(root), message)

    def test_operation_shape_duplicate_name_and_unknown_case_fields_stay_rejected(self):
        baseline = json.loads((ROOT / FIXTURE).read_text(encoding="utf-8"))["cases"][0]["name"]
        controls = (
            ({"operations": [{"op": "copy", "path": ["id"]}]}, "Unknown catalog fixture operation"),
            ({"operations": [{"op": "set", "path": [], "value": 1}]}, "Malformed catalog fixture operation"),
            ({"operations": [{"op": "set", "path": [True], "value": 1}]}, "Malformed catalog fixture path"),
            ({"name": baseline}, "Catalog fixture names must be unique"),
            ({"future_field": True}, "Unsupported catalog fixture field"),
            ({"target": "unknown"}, "Unknown catalog fixture target"),
        )
        for change, message in controls:
            with self.subTest(change=change), self.copied_checkout() as root:
                case = {"name": "guard private", "target": "item", "kind": "workload",
                        "expected": "decoded"}
                case.update(change)
                self.replace_cases(root, [json.dumps(case)])
                self.assert_denied(self.run_checker(root), message)

    def test_external_schema_dialect_is_explicit_and_frozen_inputs_are_not_rewritten(self):
        with self.copied_checkout() as root:
            relative = "schemas/catalog-item.schema.json"
            path = root / "contracts/v1" / relative
            before = hashlib.sha256(path.read_bytes()).hexdigest()
            self.replace_once(path, '"$schema": "https://json-schema.org/draft/2020-12/schema"',
                              '"$schema": "https://example.invalid/unapproved-dialect"')
            self.repin_private_input(root, relative, before)
            self.assert_denied(self.run_checker(root), "Unexpected frozen Catalog schema dialect")

    def test_exact_extension_does_not_register_a_global_validator(self):
        program = (
            "import json; from decimal import Decimal; "
            "from jsonschema import Draft202012Validator,validators; "
            "schema=json.loads((Path(sys.argv[1]).parents[1]/"
            "'contracts/v1/schemas/catalog-item.schema.json').read_text()); "
            "before=validators.validator_for(schema); "
            "runpy.run_path(sys.argv[1],run_name='__main__'); "
            "after=validators.validator_for(schema); "
            "unchanged=(before is Draft202012Validator and after is before and "
            "not after.TYPE_CHECKER.is_type(Decimal('1.0'),'integer')); "
            "sys.exit(0 if unchanged else 13)"
        )
        with self.copied_checkout() as root:
            self.assert_agrees(root, self.run_checker(root, program=program))

    def test_missing_required_format_checkers_cannot_silently_pass(self):
        for name in ("date-time", "uri"):
            program = (f"from jsonschema import FormatChecker; FormatChecker.checkers.pop({name!r}); "
                       "runpy.run_path(sys.argv[1],run_name='__main__')")
            with self.subTest(format=name), self.copied_checkout() as root:
                self.assert_denied(self.run_checker(root, program=program),
                                   "Catalog format checkers unavailable")


if __name__ == "__main__":
    unittest.main()
