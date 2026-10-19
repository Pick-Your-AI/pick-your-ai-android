#!/usr/bin/env python3
"""Reproduce additive configuration goldens with all 45 unchanged frozen rules."""
import argparse
import copy
import hashlib
import gzip
import importlib.util
import json
import zlib
from pathlib import Path

from jsonschema import Draft202012Validator, FormatChecker
from referencing import Registry, Resource

ROOT = Path(__file__).resolve().parents[1]
FIXTURE = Path('core/src/test/resources/evaluation/configuration-cases.json.gz')
PROFILE = 'local-retained-frozen-v1-no-external-evidence/1'
SCHEMA_DESCRIPTOR = 'eaaf51a04c9bd0c7fce23e7ecc75724226e26b337cc1da04b88a59d2a912914f'
PINNED = {
    'contracts/v1/reference/evaluate.py': '092c8a99495f9b5ae960df40b27e33f15eefe6d1a7cd80119658fdc79ada1a5a',
    'contracts/v1/examples/build.json': '26762208769a8cf1012b4f33b1c1608556d275187ce21117d0b9632cdf951864',
    'contracts/v1/examples/catalog.json': '2e9120db823faef3e1e330a7d2e8067b3ab064328e2faed699d15cf41880fbf8',
    'contracts/v1/examples/ruleset.json': '05474568204fdbfd407cb4e4cfc9334e22fdf24e75111bed8990868926fab418',
    'contracts/v1/examples/dependency-graph.json': 'e9b3bca19a9cf8241fd74f6618824882a3413649822add88be1ac93cb3f1bacc',
    'contracts/v1/examples/pricebook.json': '716a848822fc2b182d226c597f7e50f4b00a0fe01ba5c03879e858ca6f0c9b46',
    'contracts/v1/schemas/component-registry.json': 'cd60070e912a60504f605f2fd9f86c64520160cce7f4184c8ea200209c6b1918',
    'core/src/test/resources/evaluation/diagnostic-cases.json': 'f245bcb5d81ea3e2b633d21f14de93169219f9e355d06d70075da56a8ee1ad0d',
}

# Explicit fixture-author choices. None modifies a rule, catalog fact, graph edge or metric.
CONFIGURATIONS = (
    ('all-active-control', {}, {
        'rule.deployment.target.region': 'pass', 'rule.host.region': 'pass',
        'rule.model.serving.mode.selfhost': 'pass', 'rule.production.gates': 'pass',
        'rule.vram.estimate.fit': 'unknown', 'rule.budget.monthly.cap': 'unknown'}),
    ('contradictory-target-region', {'settings': [('deployment_target', 'region', 'eu-fixture-contradiction')]},
        {'rule.deployment.target.region': 'fail', 'rule.host.region': 'pass'}),
    ('contradictory-host-region', {'settings': [('hosting_platform', 'region', 'eu-fixture-contradiction')]},
        {'rule.host.region': 'fail', 'rule.deployment.target.region': 'pass'}),
    ('contradictory-self-hosted-serving', {'settings': [('model', 'serving_mode', 'api')]},
        {'rule.model.serving.mode.selfhost': 'fail', 'rule.selfhost.requires.weights': 'pass'}),
    ('optional-t3-t4-t5-disabled', {'disabled_tiers': ['T3', 'T4', 'T5']}, {
        'rule.rag.knowledge': 'skipped', 'rule.agent.tools.framework': 'skipped',
        'rule.agent.mcp.framework': 'skipped', 'rule.training.model.capable': 'skipped',
        'rule.training.dataset.governance': 'skipped', 'rule.production.gates': 'pass'}),
    ('optional-t3-enabled', {'disabled_tiers': ['T4', 'T5']}, {
        'rule.rag.knowledge': 'pass', 'rule.rag.dense.dependencies': 'pass',
        'rule.agent.tools.framework': 'skipped', 'rule.training.model.capable': 'skipped',
        'rule.production.gates': 'pass'}),
    ('optional-t4-enabled-without-t3-vector', {'disabled_tiers': ['T3', 'T5']}, {
        'rule.agent.tools.framework': 'pass', 'rule.agent.mcp.framework': 'pass',
        'rule.memory.vector.storage': 'fail', 'rule.training.model.capable': 'skipped',
        'rule.production.gates': 'pass'}),
    ('optional-t5-enabled', {'disabled_tiers': ['T3', 'T4']}, {
        'rule.training.model.capable': 'pass', 'rule.training.dataset.governance': 'pass',
        'rule.rag.knowledge': 'skipped', 'rule.agent.tools.framework': 'skipped',
        'rule.production.gates': 'pass'}),
    ('rag-with-disabled-knowledge', {'disabled_kinds': ['knowledge_base']},
        {'rule.rag.knowledge': 'fail', 'rule.rag.dense.dependencies': 'pass'}),
    ('agent-with-disabled-framework', {'disabled_kinds': ['agent_framework']},
        {'rule.agent.tools.framework': 'fail', 'rule.agent.mcp.framework': 'fail'}),
    ('training-with-disabled-compute', {'disabled_kinds': ['training_compute']},
        {'rule.training.model.capable': 'fail', 'rule.training.dataset.governance': 'pass'}),
    ('production-with-disabled-guardrail', {'disabled_kinds': ['guardrail']},
        {'rule.production.gates': 'fail'}),
    ('development-with-disabled-guardrail', {
        'disabled_kinds': ['guardrail'], 'settings': [('deployment_target', 'environment', 'development')]},
        {'rule.production.gates': 'skipped'}),
    ('hard-cap-disabled-without-cost', {'settings': [('cost_policy', 'enforce_hard_cap', False)]},
        {'rule.budget.monthly.cap': 'skipped', 'rule.vram.estimate.fit': 'unknown'}),
    ('zero-budget-without-cost', {'budget': '0.000000000000'},
        {'rule.budget.monthly.cap': 'unknown'}),
    ('precise-budget-without-cost', {'budget': '100000000000000000000.000000000001'},
        {'rule.budget.monthly.cap': 'unknown'}),
    ('adjacent-budget-without-cost', {'budget': '100000000000000000000.000000000002'},
        {'rule.budget.monthly.cap': 'unknown'}),
)


def digest(raw):
    return hashlib.sha256(raw).hexdigest()


def encoded(value):
    return (json.dumps(value, indent=2, ensure_ascii=True, allow_nan=False) + '\n').encode('ascii')


def generate(root=ROOT):
    """Check every input before loading the reference; return the exact reviewed corpus."""
    for name, expected in PINNED.items():
        if digest((root / name).read_bytes()) != expected:
            raise ValueError('Configuration input identity differs: ' + name)
    schema_paths = sorted((root / 'contracts/v1/schemas').glob('*.schema.json'), key=lambda path: path.name)
    descriptor = json.dumps([[path.name, digest(path.read_bytes())] for path in schema_paths],
                            ensure_ascii=True, separators=(',', ':')).encode('ascii')
    if len(schema_paths) != 14 or digest(descriptor) != SCHEMA_DESCRIPTOR:
        raise ValueError('Configuration schema descriptor differs')
    schemas = {path.name: json.loads(path.read_bytes()) for path in schema_paths}
    registry = Registry().with_resources((schema['$id'], Resource.from_contents(schema)) for schema in schemas.values())

    def validate(name, value):
        Draft202012Validator(schemas[name], registry=registry, format_checker=FormatChecker()).validate(value)

    load = lambda name: json.loads((root / ('contracts/v1/examples/' + name + '.json')).read_bytes())
    base, catalog, rules, graph, pricebook = [load(name) for name in ('build', 'catalog', 'ruleset', 'dependency-graph', 'pricebook')]
    for name, value in (('catalog-snapshot', catalog), ('ruleset', rules), ('dependency-graph', graph), ('pricebook', pricebook)):
        validate(name + '.schema.json', value)
    if len(rules['rules']) != 45:
        raise ValueError('Expected the complete unchanged 45-rule fixture')
    kinds = json.loads((root / 'contracts/v1/schemas/component-registry.json').read_bytes())['kinds']
    spec = importlib.util.spec_from_file_location('frozen_configuration_reference', root / 'contracts/v1/reference/evaluate.py')
    reference = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(reference)
    cases = []
    for revision, (name, change, focus) in enumerate(CONFIGURATIONS, 1):
        build = copy.deepcopy(base)
        build.pop('policy')  # The accepted local projection has no policy field; retain reference defaults.
        build.update(id='build.configuration.' + name, revision=revision,
                     name='FICTIONAL configuration: ' + name)
        selected = {item['kind']: item for item in build['selections']}
        for item in selected.values():
            if kinds[item['kind']]['tier'] in change.get('disabled_tiers', ()) or item['kind'] in change.get('disabled_kinds', ()):
                item['enabled'] = False  # Retain the typed selection and all original link endpoints.
        for kind, field, value in change.get('settings', ()):
            selected[kind]['settings'][field] = value
        if 'budget' in change:
            build['objectives']['monthly_budget_amount'] = change['budget']
        validate('build.schema.json', build)
        report = reference.Evaluator(build, catalog, rules).evaluate()
        validate('evaluation-report.schema.json', report)
        outcomes = {finding['rule_id']: finding['outcome'] for finding in report['findings']}
        for rule, expected in focus.items():
            if outcomes.get(rule) != expected:
                raise ValueError('Configuration focus differs: ' + name + '/' + rule)
        if [finding['rule_id'] for finding in report['findings']] != [rule['id'] for rule in rules['rules']]:
            raise ValueError('Configuration report omitted or reordered a rule')
        cases.append({
            'name': name,
            'optional_tiers': {tier: 'enabled' if any(item['enabled'] and kinds[item['kind']]['tier'] == tier
                                                      for item in selected.values()) else 'disabled'
                               for tier in ('T3', 'T4', 'T5')},
            'build': build,
            'focus': focus,
            'report': report,
        })
    return {
        'fixture_version': 1,
        'profile': PROFILE,
        'inputs': PINNED,
        'schema_descriptor_sha256': SCHEMA_DESCRIPTOR,
        'external_evidence': {'resource_estimate': 'not_supplied', 'cost_estimate': 'not_supplied'},
        'rule_count': 45,
        'cases': cases,
    }


def verify(root=ROOT):
    expected = encoded(generate(root))
    try:
        reader = zlib.decompressobj(wbits=31)
        raw = reader.decompress((root / FIXTURE).read_bytes(), 1024 * 1024)
        if not reader.eof or reader.unused_data or reader.unconsumed_tail:
            raise ValueError('Configuration fixture gzip is incomplete, oversized or has extra members')
    except zlib.error as error:
        raise ValueError('Configuration fixture gzip is invalid') from error
    if raw != expected:
        raise ValueError('Configuration goldens differ from the unchanged Python reference')
    return len(CONFIGURATIONS)


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--write-configurations', action='store_true',
                        help='Regenerate only the additive configuration corpus, preserving all original goldens.')
    args = parser.parse_args()
    try:
        if args.write_configurations:
            (ROOT / FIXTURE).write_bytes(gzip.compress(encoded(generate()), compresslevel=9, mtime=0))
        else:
            verify()
    except (OSError, ValueError, KeyError) as error:
        parser.exit(1, 'Configuration verification failed: ' + str(error) + '\n')
    print(str(len(CONFIGURATIONS)) + ' complete configuration reports match the unchanged frozen reference; no external metrics supplied.')


if __name__ == '__main__':
    main()
