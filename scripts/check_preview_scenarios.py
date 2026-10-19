#!/usr/bin/env python3
"""Reproduce preview-only synthetic data using the unchanged frozen reference.

The generator writes only its five assets and one Kotlin pin table. No frozen
file, application state, network resource or production admission rule is changed.
"""
from __future__ import annotations

import argparse
import copy
import hashlib
import importlib.util
import json
from pathlib import Path
import sys

from jsonschema import Draft202012Validator, FormatChecker
from referencing import Registry, Resource
from referencing.exceptions import NoSuchResource

ROOT = Path(__file__).resolve().parents[1]
ASSET_DIRECTORY = Path('preview/src/main/assets/preview-scenarios/v1')
PIN_TABLE = Path('preview/src/main/kotlin/ai/pickyour/android/preview/SyntheticScenarioPins.kt')
MANIFEST_LIMIT = 32_768
DOCUMENT_LIMIT = 262_144
BUNDLE_LIMIT = 1_048_576
FIXTURE_VERSION = 'android-preview-scenarios-1'
FROZEN_VERSION = '1.0.0'
FROZEN_ADOPTION = '9769fcd9ea380b1211669b2753181c7782444178'
SCENARIO_IDS = ('empty_draft', 'core_build', 'unknown_evidence', 'failed_rule', 'partial_quote')
SOURCE_ASSETS = {
    'ORIGINAL_BUILD': ('build.json', 'BUILD', 'build'),
    'CATALOG': ('catalog.json', 'CATALOG', 'catalog-snapshot'),
    'RULESET': ('ruleset.json', 'RULESET', 'ruleset'),
    'GRAPH': ('dependency-graph.json', 'DEPENDENCY_GRAPH', 'dependency-graph'),
    'PRICEBOOK': ('pricebook.json', 'PRICEBOOK', 'pricebook'),
}
DERIVED_ASSETS = {
    'EMPTY_BUILD': ('empty-build.json', 'BUILD', 'build'),
    'CORE_BUILD': ('core-build.json', 'BUILD', 'build'),
    'FAILED_BUILD': ('failed-build.json', 'BUILD', 'build'),
    'PARTIAL_ESTIMATE': ('partial-estimate.json', 'COST_ESTIMATE', 'cost-estimate'),
}


def digest(value: bytes) -> str:
    return hashlib.sha256(value).hexdigest()


def encoded(value: object) -> bytes:
    return (json.dumps(value, ensure_ascii=True, indent=2) + '\n').encode('utf-8')


def reject_external(uri: str):
    raise NoSuchResource(ref=uri)


def validators(root: Path):
    schemas = [json.loads(p.read_bytes()) for p in sorted((root / 'contracts/v1/schemas').glob('*.schema.json'))]
    registry = Registry(retrieve=reject_external).with_resources(
        (schema['$id'], Resource.from_contents(schema)) for schema in schemas)
    formats = FormatChecker()
    require({'date-time', 'uri'}.issubset(formats.checkers), 'Required frozen date-time/URI format assertions unavailable')
    return {schema['$id'].rsplit('/', 1)[-1].removesuffix('.schema.json'):
            Draft202012Validator(schema, registry=registry, format_checker=formats)
            for schema in schemas}


def frozen_reference(root: Path):
    spec = importlib.util.spec_from_file_location('preview_frozen_reference', root / 'contracts/v1/reference/evaluate.py')
    module = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(module)
    return module


def require(condition: bool, message: str):
    if not condition:
        raise ValueError(message)


def generated(root: Path = ROOT):
    """Return exact bytes and reference observations, with no writes."""
    examples = root / 'contracts/v1/examples'
    source = {key: (examples / name).read_bytes() for key, (name, _, _) in SOURCE_ASSETS.items()}
    original = json.loads(source['ORIGINAL_BUILD'])
    catalog, rules = json.loads(source['CATALOG']), json.loads(source['RULESET'])
    registry_path = root / 'contracts/v1/schemas/component-registry.json'
    registry = json.loads(registry_path.read_bytes())['kinds']

    empty = copy.deepcopy(original)
    empty.update(id='build.android.synthetic.empty.v1', name='SYNTHETIC expected-invalid empty draft',
                 objectives={}, selections=[], links=[])
    core = copy.deepcopy(original)
    core.update(id='build.android.synthetic.core.v1', name='SYNTHETIC core configuration with T6')
    for selection in core['selections']:
        selection['enabled'] = registry[selection['kind']]['tier'] not in ('T3', 'T4', 'T5')
    failed = copy.deepcopy(core)
    failed.update(id='build.android.synthetic.context.v1', name='SYNTHETIC intentional context rule failure')
    next(s for s in failed['selections'] if s['kind'] == 'context_policy')['settings']['requested_context_tokens'] = 9000

    quote_path = examples / 'cost-estimate.json'
    partial = json.loads(quote_path.read_bytes())
    partial.update(id='estimate.android.synthetic.partial.v1', build_id=core['id'], coverage='partial',
                   unpriced_selection_ids=['sel_compute_profile'])
    partial['line_items'] = [line for line in partial['line_items'] if line['selection_id'] == 'sel_storage']
    for key, amount in (('subtotal', '2.50'), ('tax', '0.00'), ('total', '2.50')):
        partial[key] = {'amount': amount, 'currency': 'USD'}
    partial['warnings'] = [
        'SYNTHETIC preview only: recorded storage amount, not a vendor quote or measured use.',
        'Compute is explicitly unpriced; other active selections without lines remain unclassified.',
        'The recorded 2.50 USD subtotal is not a complete stack price or a proven lower bound.',
        'Freshness, free or included treatment and budget approval are not established.',
    ]
    derived = dict(zip(DERIVED_ASSETS, (empty, core, failed, partial), strict=True))
    assets = source | {key: encoded(value) for key, value in derived.items()}
    schema_validators = validators(root)
    for key, value in assets.items():
        schema = (SOURCE_ASSETS | DERIVED_ASSETS)[key][2]
        errors = list(schema_validators[schema].iter_errors(json.loads(value)))
        if key == 'EMPTY_BUILD':
            actual = sorted((list(error.path), error.validator, error.message) for error in errors)
            expected = sorted([
                (['objectives'], 'required', "'region' is a required property"),
                (['objectives'], 'required', "'currency' is a required property"),
                (['objectives'], 'required', "'data_classification' is a required property"),
                (['selections'], 'minItems', '[] is too short'),
            ])
            require(actual == expected, 'Empty sample must have exactly the documented required-field/minItems failures')
        else:
            require(not errors, 'Positive preview input failed the original frozen schema')

    active = {s['id'] for s in core['selections'] if s['enabled']}
    require(len(active) == 15, 'Core sample must retain all fifteen T0-T2/T6 selections')
    require(all(s['enabled'] == (registry[s['kind']]['tier'] not in ('T3', 'T4', 'T5'))
                for s in core['selections']), 'Optional activation or core T6 drifted')
    reference = frozen_reference(root)
    reports = {name: reference.Evaluator(build, catalog, rules).evaluate()
               for name, build in (('core_build', core), ('unknown_evidence', original), ('failed_rule', failed))}
    for report in reports.values():
        require(schema_validators['evaluation-report'].is_valid(report), 'Reference output failed its original schema')
    unknown = reports['unknown_evidence']
    require(unknown['summary'] == {'passed': 38, 'failed': 0, 'unknown': 3, 'skipped': 4},
            'The supported missing-evidence reference profile changed')
    require(any(f['rule_id'] == 'rule.model.context.fit' and f['outcome'] == 'fail'
                for f in reports['failed_rule']['findings']), 'Intended context-fit failure did not occur')
    require(reports['failed_rule']['deployment_readiness'] == 'blocked', 'Failed rule cannot authorize readiness')
    require(partial['build_id'] == core['id'] and partial['build_revision'] == core['revision'] and
            partial['pricebook_id'] == core['locks']['pricebook_id'], 'Partial quote binding changed')
    require(len(partial['line_items']) == 1 and partial['line_items'][0]['amount'] == partial['subtotal'],
            'Partial quote must retain only the original recorded storage amount')
    unclassified = sorted(active - {'sel_storage', 'sel_compute_profile'})
    require(len(unclassified) == 13, 'Unclassified active selections must not be hidden')

    entries = []
    for key, (name, document, _) in (SOURCE_ASSETS | DERIVED_ASSETS).items():
        path = f'preview-scenarios/v1/{name}' if key in DERIVED_ASSETS else name
        entries.append(dict(key=key, asset_path=path, document=document, bytes=len(assets[key]), sha256=digest(assets[key]),
                            disposition='expected_schema_rejection' if key == 'EMPTY_BUILD' else 'frozen_schema_valid'))
    scenarios = [dict(id='empty_draft', build='EMPTY_BUILD', disposition='expected_schema_rejection',
                      expected=dict(missing_objectives=['region', 'currency', 'data_classification'], selections_min_items=2))]
    for name, key in (('core_build', 'CORE_BUILD'), ('unknown_evidence', 'ORIGINAL_BUILD'), ('failed_rule', 'FAILED_BUILD')):
        report = reports[name]
        scenarios.append(dict(id=name, build=key, disposition='frozen_diagnostic',
                              expected=dict(summary=report['summary'], result=report['result'],
                                            deployment_readiness=report['deployment_readiness'],
                                            reference_report_sha256=digest(json.dumps(report, sort_keys=True,
                                                ensure_ascii=False, separators=(',', ':')).encode('utf-8')),
                                            findings=[dict(rule_id=f['rule_id'], outcome=f['outcome'])
                                                      for f in report['findings'] if f['outcome'] in ('unknown', 'fail')])))
    scenarios.append(dict(id='partial_quote', build='CORE_BUILD', estimate='PARTIAL_ESTIMATE',
                          disposition='recorded_partial_quote', expected=dict(coverage='partial', subtotal='2.50',
                          tax='0.00', total='2.50', currency='USD', unpriced_selection_ids=['sel_compute_profile'],
                          unclassified_active_selection_ids=unclassified)))
    require(tuple(s['id'] for s in scenarios) == SCENARIO_IDS, 'Closed scenario inventory drifted')
    manifest = dict(fixture_set=FIXTURE_VERSION, classification='synthetic', schema_version=FROZEN_VERSION,
                    source=dict(android_adoption_commit=FROZEN_ADOPTION,
                                import_manifest_sha256=digest((root / 'contracts/v1/IMPORT_MANIFEST.json').read_bytes()),
                                registry_sha256=digest(registry_path.read_bytes()),
                                original_cost_estimate_sha256=digest(quote_path.read_bytes())),
                    evidence_limit='Local frozen-reference examples only; no product, vendor, measured or publication evidence.',
                    assets=entries, scenarios=scenarios)
    manifest_bytes = encoded(manifest)
    require(len(manifest_bytes) <= MANIFEST_LIMIT and all(len(b) <= DOCUMENT_LIMIT for b in assets.values()) and
            sum(map(len, assets.values())) <= BUNDLE_LIMIT, 'Generated fixture set exceeds its fixed admission bounds')

    lines = [
        '// Generated by scripts/check_preview_scenarios.py --write; review input and pin changes together.',
        'package ai.pickyour.android.preview', '', 'import ai.pickyour.core.contracts.FrozenDocument', '',
        '/** Closed preview-only asset names. A manifest cannot supply a path, URL, schema or extra input. */',
        'internal enum class SyntheticScenarioAsset(',
        '    val assetPath: String, val document: FrozenDocument, val byteCount: Int, val sha256: String,',
        ') {',
    ]
    for entry in entries:
        lines.append(f'    {entry["key"]}("{entry["asset_path"]}", FrozenDocument.{entry["document"]}, {entry["bytes"]},')
        lines.append(f'        "{entry["sha256"]}"),')
    lines += ['}', '', 'internal object SyntheticScenarioPins {',
              f'    const val FIXTURE_SET = "{FIXTURE_VERSION}"', f'    const val SCHEMA_VERSION = "{FROZEN_VERSION}"',
              '    const val CLASSIFICATION = "synthetic"',
              '    const val MANIFEST_PATH = "preview-scenarios/v1/manifest.json"',
              f'    const val MANIFEST_BYTES = {len(manifest_bytes)}',
              f'    const val MANIFEST_SHA256 = "{digest(manifest_bytes)}"',
              f'    const val MAX_MANIFEST_BYTES = {MANIFEST_LIMIT}',
              f'    const val MAX_DOCUMENT_BYTES = {DOCUMENT_LIMIT}',
              f'    const val MAX_BUNDLE_BYTES = {BUNDLE_LIMIT}', '}', '']
    outputs = {ASSET_DIRECTORY / 'manifest.json': manifest_bytes, PIN_TABLE: '\n'.join(lines).encode('utf-8')}
    outputs.update({ASSET_DIRECTORY / values[0]: assets[key] for key, values in DERIVED_ASSETS.items()})
    return outputs, assets, manifest, reports


def check_supplied(manifest_bytes: bytes, supplied: dict[str, bytes], expected_assets: dict[str, bytes], expected_manifest: bytes):
    """Exact versioned-bundle check used by the source checker, not an app-mode boundary."""
    require(len(manifest_bytes) <= MANIFEST_LIMIT and manifest_bytes == expected_manifest, 'Manifest identity rejected')
    require(supplied.keys() == expected_assets.keys(), 'Asset inventory rejected')
    require(sum(map(len, supplied.values())) <= BUNDLE_LIMIT, 'Bundle size rejected')
    for key, value in supplied.items():
        require(len(value) <= DOCUMENT_LIMIT and value == expected_assets[key], 'Asset identity rejected')


def check(root: Path = ROOT):
    outputs, expected_assets, manifest, reports = generated(root)
    directory = root / ASSET_DIRECTORY
    actual_paths = {p.relative_to(root) for p in directory.rglob('*') if p.is_file() or p.is_symlink()}
    expected_paths = {p for p in outputs if p.parent == ASSET_DIRECTORY}
    require(actual_paths == expected_paths, 'Preview asset inventory changed')
    for path, expected in outputs.items():
        target = root / path
        require(not target.is_symlink() and target.read_bytes() == expected, 'Generated preview output differs')
    actual = {entry['key']: (root / ('preview/src/main/assets' if entry['key'] in DERIVED_ASSETS
              else 'contracts/v1/examples') / entry['asset_path']).read_bytes() for entry in manifest['assets']}
    check_supplied((directory / 'manifest.json').read_bytes(), actual, expected_assets, outputs[ASSET_DIRECTORY / 'manifest.json'])
    print('Five synthetic scenarios, nine exact assets and generated Kotlin pins match frozen v1.')
    for name, report in reports.items():
        print(f'{name}: {report["result"]}, {report["deployment_readiness"]}, {report["summary"]}')
    print('Empty: exactly four expected schema errors; partial: recorded 2.50 USD, one unpriced and thirteen unclassified selections.')


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--write', action='store_true', help='Reproduce only the owned preview assets and Kotlin pin table')
    args = parser.parse_args()
    # Pin all original inputs before deriving any new output; never rewrite frozen bytes.
    sys.path.insert(0, str(ROOT / 'scripts'))
    from check_contracts import verify
    try:
        verify()
        if args.write:
            outputs, _, _, _ = generated()
            for path, content in outputs.items():
                target = ROOT / path
                require(not target.is_symlink(), 'Refusing a generated output symlink')
                target.parent.mkdir(parents=True, exist_ok=True)
                target.write_bytes(content)
        check()
        return 0
    except (OSError, ValueError) as error:
        print(f'Synthetic preview check failed: {error}', file=sys.stderr)
        return 1


if __name__ == '__main__':
    sys.exit(main())
