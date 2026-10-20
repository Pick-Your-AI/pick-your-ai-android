"""Real CLI controls for exact integer tokens in the two priced-record fixtures.

All canonical corpora remain byte-identical. Private replacement cases retain
the original corpus length and exercise only structural frozen-schema meaning;
they supply no rounding, quote, rate-eligibility or Android-device result.
"""
import ast
from contextlib import contextmanager
from decimal import Decimal
import hashlib
import json
import os
from pathlib import Path
import re
import shutil
import subprocess
import sys
import tempfile
import unittest


ROOT = Path(__file__).resolve().parents[1]
FIXTURES = Path("core/src/test/resources/contracts")
PROFILES = (
    ("CostEstimate", "cost-estimate", "check_cost_estimate_fixtures.py",
     "cost-estimate-cases.json", ("build_revision", "period_days"), 224),
    ("Pricebook", "pricebook", "check_pricebook_fixtures.py",
     "pricebook-cases.json", ("revision",), 188),
)


def literal_case(name, field, token, expected, *, version=None, operation="set"):
    marker = "__PRIVATE_EXACT_NUMBER__"
    case = {"name": name, "expected": expected, operation: {field: marker}}
    if version is not None:
        case.setdefault("set", {})["schema_version"] = version
    text = json.dumps(case, separators=(",", ":"))
    if text.count('"' + marker + '"') != 1:
        raise ValueError("Ambiguous numeric witness")
    return text.replace('"' + marker + '"', token)


class CostPricebookFixtureNumberTests(unittest.TestCase):
    @contextmanager
    def copied_checkout(self):
        with tempfile.TemporaryDirectory(prefix="cost-pricebook-number-") as directory:
            root = Path(directory)
            for folder in ("contracts/v1/schemas", "contracts/v1/examples"):
                shutil.copytree(ROOT / folder, root / folder,
                                ignore=shutil.ignore_patterns("__pycache__"))
            paths = [Path("scripts") / p[2] for p in PROFILES]
            paths += [FIXTURES / p[3] for p in PROFILES]
            helper = Path("scripts/frozen_fixture_numbers.py")
            if (ROOT / helper).is_file():
                paths.append(helper)
            for relative in paths:
                target = root / relative
                target.parent.mkdir(parents=True, exist_ok=True)
                shutil.copyfile(ROOT / relative, target)
            yield root

    def replace_private_prefix(self, root, profile, private):
        """Keep the exact original metadata, suffix and fixed case-count gate."""
        path = root / FIXTURES / profile[3]
        source = (ROOT / FIXTURES / profile[3]).read_text(encoding="utf-8")
        original = json.loads(source, parse_float=Decimal)
        self.assertEqual("cases", list(original)[-1])
        opening = re.search(r'"cases"\s*:\s*\[', source)
        self.assertIsNotNone(opening)
        start = opening.end()
        while source[start].isspace():
            start += 1
        end = start
        decoder = json.JSONDecoder(parse_float=Decimal)
        for index in range(len(private)):
            if index:
                while source[end].isspace():
                    end += 1
                self.assertEqual(",", source[end])
                end += 1
                while source[end].isspace():
                    end += 1
            _, end = decoder.raw_decode(source, end)
        changed = source[:start] + ",\n    ".join(private) + source[end:]
        parsed = json.loads(changed, parse_float=Decimal)
        self.assertEqual(profile[5], len(parsed["cases"]))
        self.assertEqual(original["cases"][len(private):], parsed["cases"][len(private):])
        self.assertEqual({k: v for k, v in original.items() if k != "cases"},
                         {k: v for k, v in parsed.items() if k != "cases"})
        self.assertTrue(changed.startswith(source[:start]))
        self.assertTrue(changed.endswith(source[end:]))
        path.write_text(changed, encoding="utf-8")

    def input_state(self, root):
        result = {}
        for path in sorted(root.rglob("*")):
            if path.is_file():
                status = path.stat()
                result[str(path.relative_to(root))] = (
                    hashlib.sha256(path.read_bytes()).hexdigest(),
                    status.st_mode, status.st_ino, status.st_mtime_ns, status.st_size)
        return result

    def run_checker(self, root, profile, *, options=(), optimization=None, program=None):
        environment = {**os.environ, "PYTHONDONTWRITEBYTECODE": "1"}
        environment.pop("PYTHONOPTIMIZE", None)
        if optimization is not None:
            environment["PYTHONOPTIMIZE"] = optimization
        script = str(root / "scripts" / profile[2])
        command = [sys.executable, "-B", *options]
        command += [script] if program is None else ["-c", program, script]
        before = self.input_state(root)
        result = subprocess.run(command, cwd=root, env=environment, text=True,
                                capture_output=True, timeout=20)
        self.assertEqual(before, self.input_state(root), "The checker changed an input")
        return result

    def assert_agrees(self, root, profile, result):
        self.assertEqual(0, result.returncode, result.stderr)
        self.assertEqual("", result.stderr)
        fixture = json.loads((root / FIXTURES / profile[3]).read_text(), parse_float=Decimal)
        counts = {key: sum(case["expected"] == key for case in fixture["cases"])
                  for key in ("decoded", "unsupported_version", "schema_mismatch")}
        line, = result.stdout.splitlines()
        self.assertEqual(str(profile[5]), line.split()[0])
        self.assertEqual(counts, ast.literal_eval(line[line.index("{"):]))

    def assert_denied(self, result, message=None):
        self.assertNotEqual(0, result.returncode, result.stdout)
        self.assertNotIn("cases agree with", result.stdout)
        if message is not None:
            self.assertIn(message, result.stderr)

    def test_original_corpora_retain_all_224_and_188_outcomes(self):
        for profile in PROFILES:
            with self.subTest(family=profile[0]), self.copied_checkout() as root:
                self.assert_agrees(root, profile, self.run_checker(root, profile))

    def test_each_inline_integer_accepts_decimal_and_exponent_integral_forms(self):
        for profile in PROFILES:
            for field in profile[4]:
                for token in ("1.0", "1000e-3"):
                    with self.subTest(family=profile[0], field=field, token=token), self.copied_checkout() as root:
                        self.replace_private_prefix(root, profile, [
                            literal_case("eligible exact integer", field, token, "decoded")])
                        self.assert_agrees(root, profile, self.run_checker(root, profile))

    def test_wide_and_large_integral_exponents_stay_compact_and_eligible(self):
        for profile in PROFILES:
            with self.subTest(family=profile[0]), self.copied_checkout() as root:
                cases = [literal_case(f"wide {field} {index}", field, token, "decoded")
                         for field in profile[4]
                         for index, token in enumerate(("9007199254740993.0", "1e1000",
                                                        "1e1000000000", "1.0000000000000000000"))]
                self.replace_private_prefix(root, profile, cases)
                self.assert_agrees(root, profile, self.run_checker(root, profile))

    def test_fractions_minima_zero_and_nonnumbers_keep_the_exact_schema_rules(self):
        tokens = ("1.0000000000000001", "9007199254740992.1",
                  "0.99999999999999999999", "1e-9999", "-1e-9999",
                  "0", "-0.0", "0e1000000000", "true", "false", '"1"', "null")
        for profile in PROFILES:
            with self.subTest(family=profile[0]), self.copied_checkout() as root:
                cases = [literal_case(f"invalid {field} {index}", field, token, "schema_mismatch")
                         for field in profile[4] for index, token in enumerate(tokens)]
                self.replace_private_prefix(root, profile, cases)
                self.assert_agrees(root, profile, self.run_checker(root, profile))

    def test_claims_that_fractional_or_below_minimum_values_decode_still_fail(self):
        for profile in PROFILES:
            for field in profile[4]:
                for token in ("1.0000000000000001", "0.99999999999999999999"):
                    with self.subTest(family=profile[0], field=field, token=token), self.copied_checkout() as root:
                        self.replace_private_prefix(root, profile, [
                            literal_case("incorrect decoded claim", field, token, "decoded")])
                        self.assert_denied(self.run_checker(root, profile), "reference outcome differs")

    def test_common_ids_dates_and_money_remain_noncoercing(self):
        for profile in PROFILES:
            with self.subTest(family=profile[0]), self.copied_checkout() as root:
                stamp = "estimated_at" if profile[0] == "CostEstimate" else "captured_at"
                cases = [
                    literal_case("numeric id", "id", "1.0", "schema_mismatch"),
                    json.dumps({"name": "bad date", "expected": "schema_mismatch",
                                "set": {stamp: "not-a-date"}}),
                ]
                operation = "line_set" if profile[0] == "CostEstimate" else "rate_set"
                money_fields = ("quantity", "unit_price", "amount") if profile[0] == "CostEstimate" else ("unit_price",)
                for field in money_fields:
                    cases.append(literal_case(f"numeric {field}", field, "1.0",
                                              "schema_mismatch", operation=operation))
                    literal = ('"0.100000000000"' if field == "quantity" else
                               '{"amount":"0.100000000000","currency":"USD"}')
                    cases.append(literal_case(f"literal {field}", field, literal,
                                              "decoded", operation=operation))
                    if field != "quantity":
                        for label, money in (
                            ("numeric amount", '{"amount":0.1,"currency":"USD"}'),
                            ("numeric currency", '{"amount":"0.1","currency":1.0}'),
                        ):
                            cases.append(literal_case(f"{field} {label}", field, money,
                                                      "schema_mismatch", operation=operation))
                if profile[0] == "Pricebook":
                    cases.append(json.dumps({"name": "bad provenance object",
                                             "expected": "schema_mismatch",
                                             "rate_set": {"provenance": "not-an-object"}}))
                self.replace_private_prefix(root, profile, cases)
                self.assert_agrees(root, profile, self.run_checker(root, profile))

    def test_finite_unsupported_versions_keep_the_existing_precedence(self):
        for profile in PROFILES:
            with self.subTest(family=profile[0]), self.copied_checkout() as root:
                self.replace_private_prefix(root, profile, [
                    literal_case("finite unsupported", profile[4][0], "1.5",
                                 "unsupported_version", version="2.0.0")])
                self.assert_agrees(root, profile, self.run_checker(root, profile))

    def test_non_json_constants_are_rejected_before_unsupported_version_selection(self):
        for profile in PROFILES:
            for token in ("NaN", "Infinity", "-Infinity"):
                with self.subTest(family=profile[0], token=token), self.copied_checkout() as root:
                    self.replace_private_prefix(root, profile, [
                        literal_case("non JSON token", profile[4][0], token,
                                     "unsupported_version", version="2.0.0")])
                    self.assert_denied(self.run_checker(root, profile), "finite JSON numbers")

    def test_all_three_input_hashes_still_reject_equivalent_changed_bytes(self):
        for profile in PROFILES:
            for relative in (f"schemas/{profile[1]}.schema.json",
                             "schemas/common.schema.json", f"examples/{profile[1]}.json"):
                with self.subTest(family=profile[0], input=relative), self.copied_checkout() as root:
                    path = root / "contracts/v1" / relative
                    original = path.read_bytes()
                    path.write_bytes(original + b"\n")
                    self.assertEqual(json.loads(original), json.loads(path.read_bytes()))
                    self.assert_denied(self.run_checker(root, profile), "input identity changed")

    def test_case_count_and_profile_guards_are_not_relaxed(self):
        for profile in PROFILES:
            for change in ("count", "profile"):
                with self.subTest(family=profile[0], change=change), self.copied_checkout() as root:
                    path = root / FIXTURES / profile[3]
                    fixture = json.loads(path.read_text())
                    if change == "count":
                        fixture["cases"].pop()
                    else:
                        fixture["profile"] = "unknown-profile"
                    path.write_text(json.dumps(fixture), encoding="utf-8")
                    self.assert_denied(self.run_checker(root, profile))

    def test_integral_admission_survives_optimization_without_global_registration(self):
        program = (
            "from decimal import Decimal\n"
            "from jsonschema import Draft202012Validator, validators\n"
            "import runpy, sys\n"
            "from pathlib import Path\n"
            "schema = {'$schema':'https://json-schema.org/draft/2020-12/schema','type':'integer'}\n"
            "before = validators.validator_for(schema)\n"
            "if before is not Draft202012Validator or before(schema).is_valid(Decimal('1.0')):\n"
            "    raise RuntimeError('Unexpected stock validator baseline')\n"
            "sys.path.insert(0, str(Path(sys.argv[1]).parent))\n"
            "runpy.run_path(sys.argv[1], run_name='__main__')\n"
            "after = validators.validator_for(schema)\n"
            "if after is not before or after(schema).is_valid(Decimal('1.0')):\n"
            "    raise RuntimeError('The checker changed global validator selection')\n"
        )
        for profile in PROFILES:
            for options, optimization in ((("-O",), None), ((), "2")):
                with self.subTest(family=profile[0], optimization=optimization), self.copied_checkout() as root:
                    self.replace_private_prefix(root, profile, [
                        literal_case("optimized integral", profile[4][0], "1000e-3", "decoded")])
                    self.assert_agrees(root, profile, self.run_checker(
                        root, profile, options=options, optimization=optimization, program=program))


if __name__ == "__main__":
    unittest.main(verbosity=2)
