"""Reproduce synthetic adapter fixtures using the unmodified frozen Python evaluator."""
import copy
import importlib.util
import json
from pathlib import Path
import sys

from reference_fixture_output import output_path, write_output

ROOT = Path(__file__).resolve().parents[1]
spec = importlib.util.spec_from_file_location('frozen_evaluate', ROOT / 'contracts/v1/reference/evaluate.py')
reference = importlib.util.module_from_spec(spec)
spec.loader.exec_module(reference)
examples = ROOT / 'contracts/v1/examples'
load = lambda name: json.loads((examples / f'{name}.json').read_text())
build, catalog, rules = load('build'), load('catalog'), load('ruleset')
cases = []
for name, metrics, strict in [
    ('missing-evidence', {}, False),
    ('strict-unknowns', {}, True),
    ('decimal-failure', {'cost.monthly_total_amount': '999999999999.000000000001'}, False),
    ('decimal-zero', {'cost.monthly_total_amount': '0.000000000000'}, False),
]:
    b = copy.deepcopy(build)
    b['policy']['fail_on_unknown'] = strict
    report = reference.Evaluator(b, catalog, rules, metrics=metrics).evaluate()
    cases.append(dict(name=name, strict=strict, report=report))
output = json.dumps(cases, indent=2) + '\n'
relative = Path('core/src/test/resources/evaluation/reference-reports.json')
path = output_path(ROOT, relative)
if '--write' in sys.argv:
    write_output(ROOT, relative, output)
else:
    if path.read_text() != output:
        raise SystemExit('Reference adapter fixtures differ; inspect upstream before regenerating')
    print(f'{len(cases)} reference report fixtures match frozen Python evaluator')
