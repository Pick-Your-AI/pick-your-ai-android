"""Real CLI checks for JSON-number admission in structural Ruleset fixtures.

Private first-case replacements preserve all 145 cases and the original frozen
inputs. These parser controls do not evaluate rules or qualify Android devices.
"""
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
FIXTURE = Path("core/src/test/resources/contracts/ruleset-cases.json")
EXECUTIONS = (((), None), (("-O",), None), ((), "1"))


class RulesetFixtureNumberTests(unittest.TestCase):
    @contextmanager
    def copied_checkout(self):
        with tempfile.TemporaryDirectory(prefix="ruleset-json-number-") as directory:
            root = Path(directory)
            for folder in ("contracts/v1/schemas", "contracts/v1/examples", "contracts/v1/baseline"):
                shutil.copytree(ROOT / folder, root / folder)
            for relative in (FIXTURE, Path("scripts/check_ruleset_fixtures.py"),
                             Path("scripts/frozen_fixture_numbers.py")):
                target = root / relative
                target.parent.mkdir(parents=True, exist_ok=True)
                shutil.copyfile(ROOT / relative, target)
            yield root

    def replace_literal(self, root, token, *, version=None):
        source = (ROOT / FIXTURE).read_text(encoding="utf-8")
        start = re.search(r'"cases"\s*:\s*\[\s*', source).end()
        _, end = json.JSONDecoder(parse_float=Decimal).raw_decode(source, start)
        case = {
            "name": "private JSON number admission control",
            "expected": "unsupported_version" if version else "decoded",
            "rule_set": {"assert": {"op": "exists", "value": {"literal": "__TOKEN__"}}},
        }
        if version:
            case["set"] = {"schema_version": version}
        replacement = json.dumps(case).replace('"__TOKEN__"', token)
        changed = source[:start] + replacement + source[end:]
        # Preserve raw numeric spelling for this harness-only suffix comparison;
        # the actual checker must receive even an unrepresentable exponent.
        original = json.loads(source, parse_float=str)
        parsed = json.loads(changed, parse_float=str)
        self.assertEqual(145, len(parsed["cases"]))
        self.assertEqual(original["cases"][1:], parsed["cases"][1:])
        self.assertEqual({k: v for k, v in original.items() if k != "cases"},
                         {k: v for k, v in parsed.items() if k != "cases"})
        (root / FIXTURE).write_text(changed, encoding="utf-8")

    def input_state(self, root):
        return {
            str(path.relative_to(root)): (hashlib.sha256(path.read_bytes()).hexdigest(),
                                         path.stat().st_mode, path.stat().st_mtime_ns)
            for path in root.rglob("*") if path.is_file()
        }

    def run_checker(self, root, execution=EXECUTIONS[0], *, disable_decimal_trap=False):
        options, optimization = execution
        env = {**os.environ, "PYTHONDONTWRITEBYTECODE": "1"}
        env.pop("PYTHONOPTIMIZE", None)
        if optimization is not None:
            env["PYTHONOPTIMIZE"] = optimization
        script = root / "scripts/check_ruleset_fixtures.py"
        command = [sys.executable, "-B", *options, str(script)]
        if disable_decimal_trap:
            program = (
                "import decimal,runpy,sys; "
                "decimal.getcontext().traps[decimal.InvalidOperation]=False; "
                "sys.path.insert(0,str(__import__('pathlib').Path(sys.argv[1]).parent)); "
                "runpy.run_path(sys.argv[1],run_name='__main__')"
            )
            command = [sys.executable, "-B", *options, "-c", program, str(script)]
        before = self.input_state(root)
        result = subprocess.run(command, cwd=root, env=env, text=True,
                                capture_output=True, timeout=20)
        self.assertEqual(before, self.input_state(root), "The checker changed an input")
        return result

    def assert_admitted(self, result):
        self.assertEqual(0, result.returncode, result.stderr)
        self.assertEqual("", result.stderr)
        self.assertIn("145 Ruleset cases agree with the frozen Python schema", result.stdout)

    def assert_rejected(self, result):
        self.assertNotEqual(0, result.returncode, result.stdout)
        self.assertNotIn("cases agree with", result.stdout)

    def test_unchanged_corpus_passes_normal_and_optimized_execution(self):
        for execution in EXECUTIONS:
            with self.subTest(execution=execution), self.copied_checkout() as root:
                self.assert_admitted(self.run_checker(root, execution))

    def test_non_json_constants_reject_at_every_literal_depth(self):
        for constant in ("NaN", "Infinity", "-Infinity"):
            for token in (constant, "[" + constant + "]", '{"nested":' + constant + '}'):
                with self.subTest(token=token), self.copied_checkout() as root:
                    self.replace_literal(root, token)
                    self.assert_rejected(self.run_checker(root))

    def test_non_json_constants_reject_before_unsupported_version_dispatch(self):
        for token in ("NaN", "Infinity", "-Infinity"):
            with self.subTest(token=token), self.copied_checkout() as root:
                self.replace_literal(root, token, version="2.0.0")
                self.assert_rejected(self.run_checker(root))

    def test_non_json_rejection_does_not_depend_on_assertions(self):
        for execution in EXECUTIONS[1:]:
            with self.subTest(execution=execution), self.copied_checkout() as root:
                self.replace_literal(root, "NaN")
                self.assert_rejected(self.run_checker(root, execution))

    def test_valid_extreme_json_numbers_and_lookalike_strings_remain_literals(self):
        for token in ("1e309", "1e-10000", "9007199254740993.0000000001",
                      '"NaN"', '"Infinity"', '"-Infinity"', "true", "null"):
            with self.subTest(token=token), self.copied_checkout() as root:
                self.replace_literal(root, token)
                self.assert_admitted(self.run_checker(root))

    def test_unrepresentable_exponent_rejects_even_when_decimal_trap_is_disabled(self):
        with self.copied_checkout() as root:
            self.replace_literal(root, "1e999999999999999999999999")
            self.assert_rejected(self.run_checker(root, disable_decimal_trap=True))


if __name__ == "__main__":
    unittest.main()
