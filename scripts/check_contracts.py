#!/usr/bin/env python3
"""Verify the frozen import, then run its unmodified reference conformance suite."""
import hashlib
import json
from pathlib import Path
import subprocess
import sys

ROOT = Path(__file__).resolve().parents[1]
BASE = ROOT / 'contracts' / 'v1'
PINNED_METADATA = {
    'IMPORT_MANIFEST.json': 'ef6d5801eec10f3169e2bf84653e7026b33088e305a987991ec52faadcd7f554',
    'UPSTREAM_SHA256SUMS': '0587a133436268bc03684ad61ff29f3bd1a12eb83db8a497eabdbb8fd8803e15',
}


def verify():
    for name, digest in PINNED_METADATA.items():
        if hashlib.sha256((BASE / name).read_bytes()).hexdigest() != digest:
            raise ValueError(f'pinned metadata changed: {name}')
    manifest = json.loads((BASE / 'IMPORT_MANIFEST.json').read_text())
    sums = dict(line.split('  ', 1)[::-1] for line in
                (BASE / 'UPSTREAM_SHA256SUMS').read_text().splitlines())
    if len(manifest['files']) != 43 or len(sums) != 43:
        raise ValueError('expected all 43 original package entries')
    expected_files = {entry['path'] for entry in manifest['files']} | set(PINNED_METADATA)
    actual_files = {str(path.relative_to(BASE)) for path in BASE.rglob('*') if path.is_file()
                    and not ('__pycache__' in path.parts and path.suffix == '.pyc')}
    if actual_files != expected_files:
        raise ValueError('frozen import file inventory changed')
    for entry in manifest['files']:
        path = BASE / entry['path']
        if sums[entry['source_path']] != entry['sha256']:
            raise ValueError(f'checksum mapping differs: {entry["path"]}')
        if hashlib.sha256(path.read_bytes()).hexdigest() != entry['sha256']:
            raise ValueError(f'frozen bytes changed: {entry["path"]}')
    print('Verified all 43 frozen v1 files and both pinned manifests.', flush=True)


def main():
    try:
        verify()
    except (OSError, ValueError, KeyError) as error:
        print(f'Contract integrity failure: {error}', file=sys.stderr)
        return 1
    result = subprocess.call([sys.executable, '-m', 'unittest', 'discover',
                              '-s', str(BASE / 'tests'), '-v'], cwd=ROOT)
    if result:
        return result
    for name in ('check_report_fixtures.py', 'check_quality_run_fixtures.py', 'check_frozen_pattern_fixtures.py',
                 'check_event_fixtures.py', 'check_diagnostic_fixtures.py'):
        result = subprocess.call([sys.executable, str(ROOT / 'scripts' / name)], cwd=ROOT)
        if result:
            return result
    return subprocess.call([sys.executable,
                            str(ROOT / 'scripts' / 'check_dependency_graph_fixtures.py')], cwd=ROOT)


if __name__ == '__main__':
    sys.exit(main())
