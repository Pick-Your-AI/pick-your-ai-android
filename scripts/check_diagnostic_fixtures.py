"""Reproduce bounded diagnostic goldens from the unchanged frozen Python reference."""
import copy
import hashlib
import importlib.util
import json
from pathlib import Path
import re
import sys

ROOT = Path(__file__).resolve().parents[1]
PROFILE_SOURCE = Path('core/src/main/kotlin/ai/pickyour/core/evaluation/RetainedSnapshotEvaluation.kt')
PROFILE_INPUTS = {
    'REFERENCE_SHA256': Path('contracts/v1/reference/evaluate.py'),
    'EVALUATOR_SHA256': Path('core/src/main/kotlin/ai/pickyour/core/evaluation/FrozenDiagnosticEvaluator.kt'),
    'REPORT_RECEIVER_SHA256': Path('core/src/main/kotlin/ai/pickyour/core/evaluation/ReferenceReportAdapter.kt'),
}


def verify_profile(root=ROOT):
    """Check declared source identities; full package and runtime gates remain separate."""
    schema_paths = sorted((root / 'contracts/v1/schemas').glob('*.schema.json'), key=lambda path: path.name)
    if len(schema_paths) != 14:
        raise ValueError('Expected exactly 14 original frozen schema files')
    # Exact existing CD-7 schema-only descriptor: filename/hash pairs, ASCII compact JSON,
    # sorted by basename, no trailing newline. The full 43-file import gate still runs first.
    rows = [[path.name, hashlib.sha256(path.read_bytes()).hexdigest()] for path in schema_paths]
    descriptor = json.dumps(rows, ensure_ascii=True, separators=(',', ':')).encode('ascii')
    expected = {key: hashlib.sha256((root / path).read_bytes()).hexdigest() for key, path in PROFILE_INPUTS.items()}
    expected.update(SCHEMAS_SHA256=hashlib.sha256(descriptor).hexdigest(),
                    ID='local-retained-frozen-v1-no-external-evidence/1', REFERENCE_TIME='2026-10-16T09:00:00Z')
    source = (root / PROFILE_SOURCE).read_text()
    for key, value in expected.items():
        found = re.findall(r'^\s*const val ' + re.escape(key) + r' = "([^"]+)"\s*$', source, re.MULTILINE)
        if found != [value]:
            raise ValueError(f'Retained evaluator profile identity differs: {key}')
    return expected


verify_profile()
print('Verified local retained-evaluation profile source/reference/schema pins.')
spec = importlib.util.spec_from_file_location('frozen_evaluate', ROOT / 'contracts/v1/reference/evaluate.py')
reference = importlib.util.module_from_spec(spec)
spec.loader.exec_module(reference)
load = lambda name: json.loads((ROOT / f'contracts/v1/examples/{name}.json').read_text())
base, catalog, rules = load('build'), load('catalog'), load('ruleset')
literal = lambda v: {'literal': v}
expr = lambda op, a, b: {'op': op, 'left': literal(a), 'right': literal(b)}
yes = expr('eq', 1, 1)
no = expr('eq', 1, 2)
unknown = {'op': 'eq', 'left': {'ref': {'scope': 'build', 'pointer': '/missing'}}, 'right': literal(1)}
cases = []
for strict in (False, True):
    b = copy.deepcopy(base)
    b['policy']['fail_on_unknown'] = strict
    cases.append(dict(name=f'original-strict-{strict}', build=b, ruleset=rules,
                      report=reference.Evaluator(b, catalog, rules).evaluate()))
expressions = [
    ('decimal-equal', expr('eq', '100000000000000000000.000000000001', '100000000000000000000.000000000001')),
    ('decimal-boundary', expr('gt', '100000000000000000000.000000000002', '100000000000000000000.000000000001')),
    ('decimal-zero', expr('eq', '0.000000000000', 0)),
    ('decimal-string-nonnumeric', expr('lt', '-1', 0)),
    ('bool-python-equality', expr('eq', True, 1)),
    ('nested-container-equality', expr('eq', [{'a': [True, 1]}], [{'a': [1, 1]}])),
    ('nested-no-string-coercion', expr('eq', ['1'], [1])),
    ('nested-containment', expr('contains', [{'a': [True]}], {'a': [1]})),
    ('substring', expr('contains', 'abc', 'bc')),
    ('empty-substring', expr('contains', 'abc', '')),
    ('in-array', expr('in', 2, [1, 2, 3])),
    ('subset', expr('subset_of', [True, 1, None], [1, None])),
    ('intersection', expr('intersects', ['1', 2], [1, 2])),
    ('empty-intersection', expr('intersects', [], [1])),
    ('missing', unknown),
    ('all-false-dominates', {'op': 'all', 'args': [unknown, no]}),
    ('any-true-dominates', {'op': 'any', 'args': [unknown, yes]}),
    ('not-unknown', {'op': 'not', 'arg': unknown}),
    ('implies-false', {'op': 'implies', 'if': no, 'then': unknown}),
    ('exists-null', {'op': 'exists', 'value': literal(None)}),
    ('exists-false', {'op': 'exists', 'value': literal(False)}),
]
for name, expression in expressions:
    r = copy.deepcopy(rules)
    rule = copy.deepcopy(r['rules'][0])
    rule['applies_when'], rule['assert'] = yes, expression
    r['rules'] = [rule]
    cases.append(dict(name=name, build=base, ruleset=r, report=reference.Evaluator(base, catalog, r).evaluate()))
for name, applies, assertion, severity in [('skipped', no, unknown, 'error'), ('unknown-applicability', unknown, no, 'error'), ('warning-failure', yes, no, 'warning')]:
    r = copy.deepcopy(rules)
    rule = copy.deepcopy(r['rules'][0])
    rule.update(applies_when=applies, **{'assert': assertion}, severity=severity)
    r['rules'] = [rule]
    cases.append(dict(name=name, build=base, ruleset=r, report=reference.Evaluator(base, catalog, r).evaluate()))
output = json.dumps(cases, indent=2) + '\n'
path = ROOT / 'core/src/test/resources/evaluation/diagnostic-cases.json'
if '--write' in sys.argv:
    path.write_text(output)
else:
    assert path.read_text() == output, 'Diagnostic goldens differ from the unchanged Python reference'
    print(f'{len(cases)} diagnostic cases match frozen Python reference')
